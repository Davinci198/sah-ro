package com.sah.analysis

/**
 * The badge a notable move earns on the board: a compact glyph and a colour,
 * both plain data. The report lines, the stored report and the squares of the
 * replay board all speak this one palette, so a briliant looks the same
 * wherever the player meets it.
 */
object MoveBadges {

    /**
     * The board step each badge belongs to: the move that earned a badge at
     * ply `p` lands on `positions[p + 1]`, so the badge keys are exactly the
     * replay steps the board renders. Both sides are kept — the board grades
     * every notable move, not only the player's.
     */
    fun badgesOf(plies: List<PlyAnalysis>): Map<Int, MoveCategory> {
        val badges = LinkedHashMap<Int, MoveCategory>()
        for (ply in plies) {
            if (!ply.category.hasBadge) continue
            badges[ply.ply + 1] = ply.category
        }
        return badges
    }

    /** Compact on-disk form: `3:BLUNDER 18:MISTAKE`. */
    fun encodeBadges(badges: Map<Int, MoveCategory>): String =
        badges.entries.joinToString(" ") { "${it.key}:${it.value.name}" }

    /** [encodeBadges] read back; unknown names and broken pieces are skipped. */
    fun parseBadges(raw: String?): Map<Int, MoveCategory> {
        if (raw.isNullOrBlank()) return emptyMap()
        val badges = LinkedHashMap<Int, MoveCategory>()
        for (token in raw.split(' ')) {
            val (step, name) = token.split(':', limit = 2).takeIf { it.size == 2 } ?: continue
            val stepIndex = step.toIntOrNull()?.takeIf { it >= 0 } ?: continue
            val category = runCatching { MoveCategory.valueOf(name) }.getOrNull() ?: continue
            badges[stepIndex] = category
        }
        return badges
    }
}

/**
 * The badge a category draws, in the order the board shows them: `!!`
 * briliant, `!` great, `?!` inaccuracy and missed chance, `?` mistake,
 * `??` blunder, `b` book, `→` forced.
 */
fun MoveCategory.roSymbol(): String = when (this) {
    MoveCategory.BRILLIANT -> "!!"
    MoveCategory.GREAT -> "!"
    MoveCategory.BEST -> "★"
    MoveCategory.GOOD -> "•"
    MoveCategory.BOOK -> "b"
    MoveCategory.FORCED -> "→"
    MoveCategory.INACCURACY -> "?!"
    MoveCategory.MISSED_OPPORTUNITY -> "?!"
    MoveCategory.MISTAKE -> "?"
    MoveCategory.BLUNDER -> "??"
}

/**
 * The category's colour as one packed RGB int — the bit pattern
 * `android.graphics.Color.rgb` produces, computed here so the palette is
 * testable on the JVM and identical on every screen.
 */
fun MoveCategory.colorRgb(): Int = when (this) {
    MoveCategory.BRILLIANT -> packed(34, 211, 238)
    MoveCategory.GREAT -> packed(147, 197, 253)
    MoveCategory.BEST -> packed(134, 239, 172)
    MoveCategory.GOOD -> packed(160, 160, 160)
    MoveCategory.BOOK -> packed(196, 150, 90)
    MoveCategory.FORCED -> packed(160, 160, 160)
    MoveCategory.INACCURACY -> packed(253, 224, 71)
    MoveCategory.MISSED_OPPORTUNITY -> packed(253, 224, 71)
    MoveCategory.MISTAKE -> packed(253, 186, 116)
    MoveCategory.BLUNDER -> packed(252, 165, 165)
}

private fun packed(red: Int, green: Int, blue: Int): Int =
    (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
