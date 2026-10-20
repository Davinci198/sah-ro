package com.sah.games

/** Romanian month names, indexed by `month - 1`. */
val MONTH_NAMES = listOf(
    "Ianuarie", "Februarie", "Martie", "Aprilie", "Mai", "Iunie",
    "Iulie", "August", "Septembrie", "Octombrie", "Noiembrie", "Decembrie",
)

/** `YYYY-MM` for grouping a game by month, or `""` when the date is missing or too short. */
fun monthKey(date: String): String = if (date.length >= 7) date.take(7) else ""

/** Readable label for a [monthKey]: "Decembrie 2024", just the year, or "Fără dată". */
fun monthTitle(key: String): String {
    val monthIndex = if (key.length >= 7) key.substring(5).toIntOrNull() ?: 0 else 0
    val year = key.take(4)
    return when {
        monthIndex in 1..12 && year.isNotEmpty() -> "${MONTH_NAMES[monthIndex - 1]} $year"
        year.isNotEmpty() -> year
        else -> "Fără dată"
    }
}

/** "1 joc" or "N jocuri", for the month section headers. */
fun gameCountWord(count: Int): String = if (count == 1) "1 joc" else "$count jocuri"
