package uk.roadahead.core

import kotlin.math.roundToInt

private val WORD_NUM = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6)
private fun num(w: String) = WORD_NUM[w] ?: w.toInt()

data class LaneText(val lanesClosed: List<Int>, val lanesTotal: Int?, val fullClosure: Boolean)

private val LANES_RE = Regex("""\blanes?\s+((?:(?:one|two|three|four|five|six|\d)(?:\s*,\s*|\s+and\s+|\s*&\s*|\s+to\s+)?)+)\s*(?:\(of [^)]*\)\s*)?(?:is|are|have been|will be)?\s*closed""")
private val OF_RE = Regex("""\(of (one|two|three|four|five|six|\d)\)""")
private val NUM_RE = Regex("one|two|three|four|five|six|\\d")

/** "Lanes one and two (of three) are closed" → [1,2], total 3. */
fun lanesFromText(text: String?): LaneText {
    val t = (text ?: "").lowercase()
    val out = sortedSetOf<Int>()
    val total = OF_RE.find(t)?.groupValues?.get(1)?.let(::num)
    LANES_RE.find(t)?.let { m ->
        val g = m.groupValues[1]
        val nums = NUM_RE.findAll(g).map { num(it.value) }.toList()
        if (Regex("\\bto\\b").containsMatchIn(g) && nums.size == 2) (nums[0]..nums[1]).forEach { out.add(it) } else out.addAll(nums)
    }
    if (Regex("hard shoulder (is )?closed").containsMatchIn(t)) out.add(0)
    val all = Regex("all lanes (are )?closed|road (is )?closed|carriageway (is )?closed").containsMatchIn(t)
    return LaneText(out.toList(), total, all)
}

fun roadFromText(text: String?): String? =
    Regex("""\b([MA]\d{1,4}(?:\(M\))?)\b""").find(text ?: "")?.groupValues?.get(1)?.uppercase()

fun normRoad(r: String?): String? {
    if (r == null) return null
    return Regex("""^([MAB]\d{1,4}(?:\(M\))?)""").find(r.uppercase().replace(Regex("\\s+"), ""))?.groupValues?.get(1)
}

fun isMotorwayRef(ref: String?) = Regex("""(^|;)\s*(M\d+|A\d+\(M\))""", RegexOption.IGNORE_CASE).containsMatchIn(ref ?: "")

/** DATEX II speeds are km/h; UK signs show mph in tens. */
fun kmhToPostedMph(kmh: Double): Int = ((kmh / 1.609344) / 10).roundToInt() * 10
