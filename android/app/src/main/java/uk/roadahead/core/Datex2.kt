package uk.roadahead.core

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Tolerant DATEX II (v2.3 / v3.x) parser — National Highways NTIS, Traffic
 * Scotland, Traffic Wales. Walks the DOM for well-known element names rather
 * than binding to one schema version. Speeds are km/h in DATEX II.
 */
object Datex2 {
    private const val XSI = "http://www.w3.org/2001/XMLSchema-instance"

    data class SignMeta(val location: Pt?, val bearing: Double?, val road: String?, val direction: String?, val isSignal: Boolean, val description: String?)

    fun parseDoc(xml: String): Element {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = true
        runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { f.isExpandEntityReferences = false }
        return f.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8))).documentElement
    }

    // ---------------------------------------------------------------- DOM helpers
    private val Node.local: String get() = localName ?: nodeName.substringAfter(':')
    private fun Element.kids(): List<Element> {
        val out = ArrayList<Element>()
        var n = firstChild
        while (n != null) { if (n is Element) out.add(n); n = n.nextSibling }
        return out
    }
    private fun Element.kid(name: String) = kids().firstOrNull { it.local == name }
    private fun Element.all(name: String): List<Element> {
        val nl = getElementsByTagNameNS("*", name)
        return (0 until nl.length).map { nl.item(it) as Element }
    }
    /** First descendant (breadth-first) with any of the names. */
    private fun Element.find(vararg names: String): Element? {
        val q = ArrayDeque<Element>(); q.addAll(kids())
        while (q.isNotEmpty()) {
            val e = q.removeFirst()
            if (e.local in names) return e
            q.addAll(e.kids())
        }
        return null
    }
    private fun Element.text(): String? = textContent?.trim()?.takeIf { it.isNotEmpty() }
    private fun Element.findText(vararg names: String) = find(*names)?.text()
    private fun Element.xsiType() = (getAttributeNS(XSI, "type").ifEmpty { getAttribute("xsi:type") }).substringAfter(':')
    private fun Element.attrId(): String? = getAttribute("id").ifEmpty { null }

    /** Multilingual string → English (or first) value. */
    private fun Element.ml(): String? {
        val vals = all("value")
        if (vals.isEmpty()) return text()
        return (vals.firstOrNull { it.getAttribute("lang").startsWith("en", true) } ?: vals.first()).text()
    }

    private fun coords(e: Element?): Pt? {
        if (e == null) return null
        val lat = e.findText("latitude")?.toDoubleOrNull() ?: return null
        val lon = e.findText("longitude")?.toDoubleOrNull() ?: return null
        return Pt(lon, lat)
    }

    data class Loc(val start: Pt?, val end: Pt?, val bearing: Double?, val direction: String?, val road: String?)

    fun location(e: Element): Loc {
        var start = e.find("from")?.let { coords(it.find("pointCoordinates")) }
        val end = e.find("to")?.let { coords(it.find("pointCoordinates")) }
        if (start == null) start = coords(e.find("pointCoordinates"))
        if (start == null) start = coords(e.find("locationForDisplay"))
        var brg = e.findText("bearing")?.toDoubleOrNull()
        val dir = e.findText("tpegDirection", "directionBoundOnLinearSection", "directionBound", "carriagewayDirection", "direction", "tpegDirectionRoad")
        if (brg == null) brg = directionToBearing(dir)
        val road = e.findText("roadNumber", "roadName")
        return Loc(start, if (end != null && end != start) end else null, brg, dir, road)
    }

    private fun prettyDir(d: String) = d.replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase()

    // ---------------------------------------------------------------- situations
    private val TYPE_MAP = mapOf(
        "Accident" to EventType.ACCIDENT,
        "VehicleObstruction" to EventType.STOPPED_VEHICLE,
        "GeneralObstruction" to EventType.OBJECT,
        "AnimalPresenceObstruction" to EventType.OBJECT,
        "EnvironmentalObstruction" to EventType.OBJECT,
        "InfrastructureDamageObstruction" to EventType.INCIDENT,
        "EquipmentOrSystemFault" to EventType.INCIDENT,
        "AbnormalTraffic" to EventType.CONGESTION,
        "MaintenanceWorks" to EventType.ROADWORKS,
        "ConstructionWorks" to EventType.ROADWORKS,
        "Roadworks" to EventType.ROADWORKS,
        "RoadOrCarriagewayOrLaneManagement" to EventType.LANE_CLOSURE,
        "SpeedManagement" to EventType.SPEED_RESTRICTION,
        "PoorEnvironmentConditions" to EventType.WEATHER,
        "WeatherRelatedRoadConditions" to EventType.WEATHER,
        "ReroutingManagement" to EventType.CLOSURE,
        "NetworkManagement" to EventType.CLOSURE,
        "DisturbanceActivity" to EventType.INCIDENT,
        "PublicEvent" to EventType.INCIDENT,
    )
    private val SEV = mapOf("highest" to Severity.SEVERE, "high" to Severity.HIGH, "medium" to Severity.MEDIUM, "low" to Severity.LOW, "lowest" to Severity.LOW, "none" to Severity.LOW)

    private fun laneNumbers(rec: Element): List<Int> {
        val out = sortedSetOf<Int>()
        for (name in listOf("lane", "laneNumber", "laneUsage")) for (e in rec.all(name)) {
            if (e.kids().isNotEmpty()) continue
            val s = e.text() ?: continue
            val m = Regex("lane\\s*(\\d+)", RegexOption.IGNORE_CASE).find(s) ?: Regex("^(\\d+)$").find(s)
            if (m != null) out.add(m.groupValues[1].toInt()) else if (s.contains("hardShoulder", true)) out.add(0)
        }
        return out.toList()
    }

    fun parseSituations(root: Element, source: String): List<Event> {
        val out = mutableListOf<Event>()
        for (rec in root.all("situationRecord")) {
            val sit = rec.parentNode as? Element
            val xsi = rec.xsiType()
            var type = TYPE_MAP[xsi] ?: EventType.INCIDENT
            val locEl = rec.kid("groupOfLocations") ?: rec.kid("locationReference") ?: rec
            val loc = location(locEl)
            val start = loc.start ?: continue
            val comments = rec.kids().filter { it.local == "generalPublicComment" }.mapNotNull { (it.find("comment") ?: it).ml() }
            val description = comments.joinToString(" ").ifEmpty { null }
            val subType = rec.findText("accidentType", "vehicleObstructionType", "obstructionType", "abnormalTrafficType",
                "roadMaintenanceType", "constructionWorkType", "roadOrCarriagewayOrLaneManagementType",
                "speedManagementType", "poorEnvironmentType", "reroutingManagementType")
            val lt = lanesFromText(description)
            val lanes = laneNumbers(rec).ifEmpty { lt.lanesClosed }
            val restricted = rec.findText("numberOfLanesRestricted")?.toIntOrNull() ?: 0
            val original = rec.findText("originalNumberOfLanes")?.toIntOrNull() ?: lt.lanesTotal
            val speed = rec.findText("temporarySpeedLimit", "speedLimit")?.toDoubleOrNull()?.takeIf { it > 0 }?.let(::kmhToPostedMph)
            val delay = rec.findText("delayTimeValue", "delaysTimeValue")?.toDoubleOrNull()?.takeIf { it > 0 }?.let { Math.round(it / 60).toInt() }
            val sevRaw = (rec.kid("severity")?.text() ?: sit?.kid("overallSeverity")?.text() ?: "").lowercase()
            var severity = SEV[sevRaw] ?: if (type == EventType.ACCIDENT) Severity.HIGH else Severity.MEDIUM
            val full = lt.fullClosure || Regex("carriagewayclosures|roadclosed|closed$").containsMatchIn((subType ?: "").lowercase())
            if (full) { severity = Severity.SEVERE; type = EventType.CLOSURE }
            val lanesClosed = lanes.ifEmpty { (1..restricted).toList() }
            if (lanesClosed.size >= 2) severity = Severity.max(severity, Severity.HIGH)
            // Broken-down vehicles: NTIS describes these as VehicleObstruction; make the text Waze-like.
            if (type == EventType.STOPPED_VEHICLE && subType?.contains("broken", true) == true) severity = Severity.max(severity, if (lanesClosed.isNotEmpty()) Severity.HIGH else Severity.MEDIUM)
            val road = loc.road ?: roadFromText(description)
            val subLabel = subType?.replace(Regex("([a-z])([A-Z])"), "$1 $2")?.lowercase()?.takeUnless { it in setOf("other", "unknown", "accident") }
            val base = if (full) "Road closed" else type.label + (subLabel?.let { " ($it)" } ?: "")
            out.add(Event(
                id = "$source:${rec.attrId() ?: sit?.attrId() ?: out.size}", source = source, type = type, severity = severity,
                location = start, end = loc.end, road = road, direction = loc.direction?.let(::prettyDir), bearing = loc.bearing,
                title = listOfNotNull(base, road, loc.direction?.let(::prettyDir)).joinToString(" · "),
                description = description, subType = subType, lanesClosed = lanesClosed, lanesTotal = original,
                speedLimit = speed, delayMinutes = delay,
                startTime = rec.findText("overallStartTime"), endTime = rec.findText("overallEndTime"),
                updated = rec.kid("situationRecordVersionTime")?.text(),
            ))
        }
        return out
    }

    // ---------------------------------------------------------------- VMS & signals
    fun parseVmsTable(root: Element, table: MutableMap<String, SignMeta>) {
        for (name in listOf("vmsUnitRecord", "vmsControllerRecord")) for (rec in root.all(name)) {
            val id = rec.attrId() ?: continue
            val loc = location(rec)
            val vmsType = rec.findText("vmsType", "vmsTypeCode", "vmsUsage") ?: ""
            table[id] = SignMeta(loc.start, loc.bearing, loc.road, loc.direction,
                Regex("matrix|lane|signal|amis|ms[1-4]", RegexOption.IGNORE_CASE).containsMatchIn(vmsType),
                rec.findText("vmsDescription", "description"))
        }
    }

    private fun pictoAspect(p: Element): SignalLane? {
        val d = (p.findText("pictogramDescription", "pictogramCode", "pictogramDescriptionCode") ?: "").replace(" ", "")
        val speed = p.findText("speedValue", "speedAttribute", "maximumSpeedLimit")?.toDoubleOrNull()?.takeIf { it > 0 }?.let(::kmhToPostedMph)
        val l = d.lowercase()
        val aspect = when {
            l in setOf("laneclosed", "redcross", "closed") || l.contains("close") || l.contains("cross") -> Aspect.CLOSED
            l.contains("left") -> Aspect.MERGE_LEFT
            l.contains("right") -> Aspect.MERGE_RIGHT
            l.contains("end") || l.contains("national") -> Aspect.END
            l.contains("speed") || speed != null -> Aspect.SPEED
            else -> Aspect.BLANK
        }
        return SignalLane(0, aspect, speed)
    }

    fun parseVmsStatus(root: Element, table: Map<String, SignMeta>, source: String): List<Event> {
        val out = mutableListOf<Event>()
        val units = root.all("vmsUnit") + root.all("vmsControllerStatus")
        for (unit in units) {
            val ref = unit.kid("vmsUnitReference") ?: unit.kid("vmsControllerReference")
            val id = ref?.attrId() ?: ref?.text() ?: unit.attrId() ?: continue
            val meta = table[id]
            var location = meta?.location; var brg = meta?.bearing; var road = meta?.road; var dir = meta?.direction
            if (location == null) { val l = location(unit); location = l.start; brg = brg ?: l.bearing; road = road ?: l.road; dir = dir ?: l.direction }
            if (location == null) continue
            val lanes = mutableListOf<SignalLane>()
            val lines = mutableListOf<String>()
            var updated: String? = null
            val signs = unit.kids().filter { it.local == "vms" || it.local == "vmsStatus" }
            signs.forEachIndexed { idx, v ->
                val laneIdx = v.getAttribute("vmsIndex").toIntOrNull() ?: v.getAttribute("index").toIntOrNull() ?: (idx + 1)
                for (p in v.all("vmsPictogram").filter { it.find("pictogramDescription", "pictogramCode", "speedAttribute") != null && it.kids().none { k -> k.local == "vmsPictogram" } } )
                    pictoAspect(p)?.let { lanes.add(it.copy(lane = laneIdx)) }
                for (l in v.all("vmsTextLine")) if (l.kids().isEmpty()) l.text()?.let { lines.add(it) }
                updated = updated ?: v.findText("timeLastSet", "statusUpdateTime", "lastSetTime")
            }
            if (lanes.isEmpty() && lines.isEmpty()) continue
            val active = lanes.filter { it.aspect != Aspect.BLANK }
            val isSignal = meta?.isSignal == true || (lanes.isNotEmpty() && lines.isEmpty())
            if (isSignal && active.isEmpty()) continue
            val speed = active.mapNotNull { it.speed }.minOrNull()
            val closed = active.filter { it.aspect == Aspect.CLOSED }.map { it.lane }
            val g = Gantry(id, 0.0, location, lanes, speed, closed, road, source)
            var sev = Analyser.gantrySeverity(g)
            if (!isSignal && Regex("accident|closed|queue|stop|incident|congestion", RegexOption.IGNORE_CASE).containsMatchIn(lines.joinToString(" "))) sev = Severity.MEDIUM
            out.add(Event(
                id = "$source:vms:$id", source = source, type = if (isSignal) EventType.SIGNAL else EventType.VMS, severity = sev,
                location = location, road = road ?: roadFromText(meta?.description), direction = dir, bearing = brg,
                title = if (isSignal) signalTitle(speed, closed) else "Sign: ${lines.joinToString(" / ")}",
                description = lines.joinToString(" / ").ifEmpty { null }, lanesClosed = closed, speedLimit = speed,
                signal = lanes.ifEmpty { null }, vmsText = lines.ifEmpty { null }, updated = updated,
            ))
        }
        return out
    }

    fun signalTitle(speed: Int?, closed: List<Int>): String {
        val parts = mutableListOf<String>()
        if (closed.isNotEmpty()) parts.add("${if (closed.size > 1) "Lanes" else "Lane"} ${closed.joinToString(" & ")} closed (red X)")
        if (speed != null) parts.add("$speed mph")
        return if (parts.isEmpty()) "Gantry signal set" else "Gantry: ${parts.joinToString(", ")}"
    }

    /** Parse any DATEX II document. Sign tables found are merged into [table]. */
    fun parse(xml: String, source: String, table: MutableMap<String, SignMeta> = HashMap()): List<Event> {
        val root = parseDoc(xml)
        parseVmsTable(root, table)
        return parseSituations(root, source) + parseVmsStatus(root, table, source)
    }
}
