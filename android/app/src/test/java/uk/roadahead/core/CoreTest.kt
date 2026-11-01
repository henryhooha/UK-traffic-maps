package uk.roadahead.core

import org.junit.Assert.*
import org.junit.Test

class CoreTest {
    private fun fx(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    // A straight 40 km northbound motorway.
    private val start = Pt(-1.0, 52.0)
    private fun at(m: Double, side: Double = 0.0) = destination(start, 0.0, m).let { if (side != 0.0) destination(it, 90.0, side) else it }
    private fun route(ref: String = "M1"): Route {
        val coords = (0..80).map { destination(start, 0.0, it * 500.0) }
        return Route(coords, 40000.0, 40000 / 31.0, listOf(Step(0.0, 40000.0, ref, null, "depart", null, start),
            Step(40000.0, 40000.0, null, null, "arrive", null, coords.last())), emptyList(), ref).also { it.id = "t" }
    }
    private fun gantry(m: Double, speed: Int?, closed: List<Int> = emptyList(), brg: Double = 0.0, side: Double = 5.0, road: String = "M1") = Event(
        "g$m$brg$road", "test", EventType.SIGNAL, if (closed.isNotEmpty()) Severity.HIGH else Severity.MEDIUM, at(m, side), road = road, bearing = brg,
        speedLimit = speed, lanesClosed = closed,
        signal = (1..3).map { if (it in closed) SignalLane(it, Aspect.CLOSED) else SignalLane(it, if (speed != null) Aspect.SPEED else Aspect.BLANK, speed) })

    @Test fun haversineLondonBirmingham() { assertEquals(163000.0, haversine(Pt(-0.1276, 51.5072), Pt(-1.8904, 52.4862)), 2000.0) }

    @Test fun snapRespectsBearing() {
        val line = RouteLine(listOf(Pt(0.0, 50.0), Pt(0.0, 50.1), Pt(0.0, 50.2)))
        val p = destination(Pt(0.0, 50.05), 90.0, 20.0)
        assertNotNull(line.snap(p, 50.0))
        assertNull(line.snap(p, 50.0, 180.0, 45.0))
    }

    @Test fun zonesClosureDelayAndCause() {
        val r = route()
        val events = listOf(gantry(10000.0, null), gantry(11000.0, 60), gantry(12000.0, 50), gantry(13000.0, 40, listOf(1)), gantry(14000.0, 40, listOf(1)), gantry(15000.0, null),
            Event("acc", "test", EventType.ACCIDENT, Severity.HIGH, at(14500.0, 10.0), road = "M1", bearing = 0.0, title = "Accident", lanesClosed = listOf(1), delayMinutes = 15))
        val a = Analyser.analyse(r, events)
        val z = a.items.single { it.kind == ItemKind.ZONE }
        assertEquals(11000.0, z.start, 5.0); assertEquals(15000.0, z.end, 5.0)
        assertEquals(40, z.speedLimit); assertEquals(listOf(1), z.lanesClosed)
        assertEquals("acc", z.events.first().id); assertEquals(15.0, z.delayMinutes, 0.01)
    }

    @Test fun oppositeCarriagewayAndBridgeIgnored() {
        val r = route()
        assertTrue(Analyser.analyse(r, listOf(gantry(20000.0, 40, listOf(1, 2), brg = 180.0, side = -25.0))).items.isEmpty())
        assertTrue(Analyser.analyse(r, listOf(gantry(20000.0, 30, road = "A45", side = 30.0))).items.isEmpty())
    }

    @Test fun lastExitBefore() {
        val r = route()
        val j = listOf(Exit("J15", null, at(18000.0)), Exit("J16", null, at(24900.0)), Exit("J17", null, at(30000.0)))
        val z = Analyser.analyse(r, listOf(gantry(25000.0, 50), gantry(26000.0, 50)), j).items.first()
        assertEquals("J15", z.exitBefore?.ref)
    }

    @Test fun camerasAndReportsBecomeItems() {
        val r = route()
        val cam = Event("cam", "osm", EventType.SPEED_CAMERA, Severity.MEDIUM, at(5000.0, 15.0), speedLimit = 50, title = "Speed camera · 50 mph")
        val rep = Report("1", ReportType.STOPPED_VEHICLE, at(8000.0, 3.0), 0.0, "M1", System.currentTimeMillis()).toEvent()
        val a = Analyser.analyse(r, listOf(cam, rep))
        assertEquals(ItemKind.CAMERA, a.items[0].kind)
        assertEquals(EventType.STOPPED_VEHICLE, a.items[1].type)
        assertTrue(a.items[1].isUserReport)
    }

    @Test fun datexSituationsAndSignals() {
        val acc = Datex2.parse(fx("situations-v2.xml"), "nh").first { it.id == "nh:NH-001-R1" }
        assertEquals(EventType.ACCIDENT, acc.type); assertEquals("M1", acc.road); assertEquals(0.0, acc.bearing!!, 0.0)
        assertEquals(listOf(1, 2), acc.lanesClosed); assertEquals(4, acc.lanesTotal); assertEquals(20, acc.delayMinutes)
        assertEquals(Pt(-0.81, 52.12), acc.end)
        val sm = Datex2.parse(fx("situations-v2.xml"), "nh").first { it.id == "nh:NH-002-R1" }
        assertEquals(50, sm.speedLimit)
        val evs = Datex2.parse(fx("vms.xml"), "nh")
        val g = evs.single { it.type == EventType.SIGNAL }
        assertEquals(listOf(1), g.lanesClosed); assertEquals(40, g.speedLimit); assertEquals(Aspect.CLOSED, g.signal!![0].aspect)
        assertEquals(listOf("ACCIDENT", "J14-15", "2 LANES CLOSED"), evs.single { it.type == EventType.VMS }.vmsText)
    }

    @Test fun laneText() {
        assertEquals(listOf(1, 2), lanesFromText("Lanes one and two (of three) are closed").lanesClosed)
        assertEquals(listOf(1, 2, 3), lanesFromText("lanes 1 to 3 are closed").lanesClosed)
        assertTrue(lanesFromText("The road is closed").fullClosure)
    }

    @Test fun tflBreakdownIsStoppedVehicle() {
        val e = Tfl.parse("""[{"id":"T1","point":"[-0.1,51.5]","severity":"Moderate","category":"Incidents","subCategory":"Breakdown","comments":"[A2] (Eastbound) Broken down vehicle. Lane one (of two) is closed.","location":"[A2] Old Kent Road"}]""").single()
        assertEquals(EventType.STOPPED_VEHICLE, e.type); assertEquals(listOf(1), e.lanesClosed); assertEquals(90.0, e.bearing!!, 0.0); assertEquals("A2", e.road)
    }

    @Test fun overpassCameras() {
        val json = """{"elements":[{"type":"node","id":1,"lat":52.0,"lon":-1.0,"tags":{"highway":"speed_camera","maxspeed":"50 mph","direction":"0"}},
            {"type":"relation","id":9,"tags":{"type":"enforcement","enforcement":"average_speed","maxspeed":"50 mph"},"members":[{"type":"node","ref":2,"role":"from"},{"type":"node","ref":3,"role":"to"}]},
            {"type":"node","id":2,"lat":52.1,"lon":-1.0},{"type":"node","id":3,"lat":52.2,"lon":-1.0}]}"""
        val cams = Overpass.parseCameras(json)
        assertEquals(50, cams.first { it.type == EventType.SPEED_CAMERA }.speedLimit)
        val avg = cams.first { it.type == EventType.AVERAGE_SPEED }
        assertEquals(Pt(-1.0, 52.2), avg.end); assertEquals(0.0, avg.bearing!!, 0.5)
    }

    @Test fun alertEngineWarnsAtHorizonThenTwoMilesThenHalf() {
        val r = route()
        r.analysis = Analyser.analyse(r, listOf(gantry(30000.0, 50), gantry(31000.0, 40, listOf(1)), gantry(32000.0, null)),
            listOf(Exit("J20", null, at(26000.0))))
        val eng = AlertEngine(AlertSettings(horizonMetres = 10 * METRES_PER_MILE, turnByTurn = false))
        assertTrue(eng.update(r, 0.0, 31.0, null).none { it.kind == AlertKind.HAZARD }) // 18.6 mi away
        val first = eng.update(r, 30000.0 - 9 * METRES_PER_MILE, 31.0, null).single { it.kind == AlertKind.HAZARD }
        assertTrue(first.spoken, first.spoken.contains("Lane 1 closed"))
        assertTrue(first.spoken, first.spoken.contains("junction 20"))
        assertTrue(eng.update(r, 30000.0 - 5 * METRES_PER_MILE, 31.0, null).none { it.kind == AlertKind.HAZARD })
        assertEquals(1, eng.update(r, 30000.0 - 1.9 * METRES_PER_MILE, 31.0, null).count { it.kind == AlertKind.HAZARD })
        assertEquals(1, eng.update(r, 30000.0 - 0.4 * METRES_PER_MILE, 31.0, null).count { it.kind == AlertKind.HAZARD })
        assertEquals(40, eng.currentGantryLimit(r, 31100.0))
    }

    @Test fun instructions() {
        assertEquals("Turn left onto High Street", Instructions.text(Step(0.0, 1.0, null, "High Street", "turn", "left", start)))
        assertEquals("At the roundabout, take the second exit onto A45", Instructions.text(Step(0.0, 1.0, "A45", null, "roundabout", "right", start, exit = 2)))
        assertEquals("Take the exit at junction 13 towards Bedford", Instructions.text(Step(0.0, 1.0, null, null, "off ramp", "slight left", start, destinations = "Bedford, Milton Keynes", junction = "13")))
    }

    @Test fun demoScenarioDetected() {
        val demo = DemoProvider { 0L }
        var found: Item? = null
        for (ref in listOf("M1", "M6", "M25", "M4", "M5", "M62")) {
            val r = route(ref); demo.prepareRoute(r)
            found = Analyser.analyse(r, demo.events()).items.firstOrNull { it.kind == ItemKind.ZONE }
            if (found != null) break
        }
        assertNotNull(found); assertTrue(found!!.start > 2 * METRES_PER_MILE)
    }

    @Test fun reportRoundTrip() {
        val r = Report("5", ReportType.SPEED_CAMERA, Pt(-1.0, 52.0), 90.0, "A1", 1000L)
        val back = Report.listFromJson(Report.listToJson(listOf(r)).toString()).single()
        assertEquals(r, back); assertTrue(back.active(System.currentTimeMillis()))
        assertTrue(Report.toGpx(listOf(r)).contains("Fixed camera"))
    }

    @Test fun spokenReports() {
        assertEquals(ReportType.STOPPED_VEHICLE, ReportType.fromSpeech("Broken down car on the hard shoulder"))
        assertEquals(ReportType.MOBILE_CAMERA, ReportType.fromSpeech("mobile camera van"))
        assertEquals(ReportType.SPEED_CAMERA, ReportType.fromSpeech("speed camera"))
        assertEquals(ReportType.ACCIDENT, ReportType.fromSpeech("crash ahead"))
        assertEquals(ReportType.LANE_BLOCKED, ReportType.fromSpeech("lane two blocked"))
        assertEquals(ReportType.TRAFFIC, ReportType.fromSpeech("traffic jam"))
        assertNull(ReportType.fromSpeech("hello"))
    }
}
