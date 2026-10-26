package io.github.garemat.crumpet.gym

/** Exercise-name canonicalisation — a port of the brain's crumpet/core/fitness.py
 *  `_canon_lift`, so a name picked or typed on the phone keys the same history the brain
 *  stores it under (lowercase, hyphens → spaces, a small alias map, last-word singular).
 *  Change one, change both. */
object Canon {
    private val ALIASES = mapOf(
        "bench" to "bench press", "flat bench" to "bench press", "bb bench" to "bench press",
        "ohp" to "overhead press", "military press" to "overhead press",
        "rdl" to "romanian deadlift", "sldl" to "romanian deadlift",
        "deads" to "deadlift", "dl" to "deadlift",
        "squats" to "squat", "back squats" to "squat", "back squat" to "squat",
        "chins" to "chin up", "chinups" to "chin up", "chin ups" to "chin up",
        "pullups" to "pull up", "pull ups" to "pull up",
        "lat pulldowns" to "lat pulldown", "pulldowns" to "lat pulldown",
        "bor" to "barbell row", "bb row" to "barbell row",
    )

    fun lift(name: String): String {
        var n = name.trim().lowercase().replace("-", " ").split(Regex("\\s+")).filter { it.isNotEmpty() }
            .joinToString(" ")
        ALIASES[n]?.let { return it }
        val words = n.split(" ")
        if (words.isNotEmpty() && words.last().length > 3) {
            var last = words.last()
            last = when {
                last.endsWith("ches") || last.endsWith("shes") || last.endsWith("xes") || last.endsWith("zes") ->
                    last.dropLast(2)
                last.endsWith("s") && !last.endsWith("ss") -> last.dropLast(1)
                else -> last
            }
            if (last != words.last()) {
                val singular = (words.dropLast(1) + last).joinToString(" ")
                n = ALIASES[singular] ?: singular
            }
        }
        return n
    }

    /** "chest press (machine)" → "Chest Press (Machine)" for display only. */
    fun pretty(name: String): String = name.split(" ").joinToString(" ") { w ->
        val i = w.indexOfFirst { it.isLetter() }
        if (i < 0) w else w.substring(0, i) + w[i].uppercaseChar() + w.substring(i + 1)
    }
}
