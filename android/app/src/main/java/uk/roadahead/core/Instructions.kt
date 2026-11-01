package uk.roadahead.core

/** Turn-by-turn text from OSRM step data, UK English. */
object Instructions {
    private fun ord(n: Int) = when (n) { 1 -> "first"; 2 -> "second"; 3 -> "third"; 4 -> "fourth"; 5 -> "fifth"; 6 -> "sixth"; else -> "${n}th" }
    private fun road(s: Step): String? = listOfNotNull(s.ref?.split(';')?.first(), s.name?.takeIf { it.isNotBlank() && it != s.ref }).joinToString(" ").ifEmpty { null }
    private fun dir(m: String?) = when (m) { null -> ""; "uturn" -> "make a U-turn"; "straight" -> "go straight on"; else -> "turn $m" }

    /** Whether a step is worth announcing. */
    fun announce(s: Step): Boolean = when (s.maneuver) {
        "depart", "new name", "notification" -> false
        "continue" -> s.modifier != null && s.modifier != "straight"
        else -> true
    }

    /** [next] is the step after the manoeuvre; OSRM puts the road name on the step that starts with the manoeuvre. */
    fun text(s: Step): String {
        val onto = road(s)?.let { " onto $it" } ?: ""
        val towards = s.destinations?.let { " towards ${it.split(',').first().trim()}" } ?: ""
        return when (s.maneuver) {
            "arrive" -> "You have arrived"
            "turn", "continue" -> dir(s.modifier).replaceFirstChar { it.uppercase() } + onto
            "end of road" -> "At the end of the road, ${dir(s.modifier)}$onto"
            "merge" -> "Merge${s.modifier?.let { " $it" } ?: ""}$onto"
            "on ramp" -> "Take the slip road$onto$towards"
            "off ramp" -> "Take the exit${s.junction?.let { " at junction ${it.split(';').first()}" } ?: ""}$towards"
            "fork" -> "Keep ${s.modifier?.removePrefix("slight ") ?: "ahead"}$towards$onto"
            "roundabout", "rotary" -> "At the roundabout, take the ${ord(s.exit ?: 1)} exit$onto"
            "roundabout turn" -> "At the roundabout, ${dir(s.modifier)}$onto"
            "exit roundabout", "exit rotary" -> "Exit the roundabout$onto"
            else -> (dir(s.modifier).ifEmpty { "Continue" }).replaceFirstChar { it.uppercase() } + onto
        }
    }

    /** Arrow glyph for UI. */
    fun arrow(s: Step): String = when {
        s.maneuver == "arrive" -> "🏁"
        s.maneuver == "roundabout" || s.maneuver == "rotary" -> "⟳"
        s.modifier == "uturn" -> "⤸"
        s.modifier?.contains("left") == true -> if (s.modifier.contains("slight")) "↖" else "↰"
        s.modifier?.contains("right") == true -> if (s.modifier.contains("slight")) "↗" else "↱"
        else -> "↑"
    }
}
