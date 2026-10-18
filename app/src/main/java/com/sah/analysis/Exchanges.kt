package com.sah.analysis

/**
 * A run of consecutive captures — the "material exchange" that the story of a game
 * highlights.
 *
 * [whiteMaterial] and [blackMaterial] are the centipawns each side took during the
 * run, so a balanced trade has them equal and an exchange where one side wins
 * material has a gap.
 */
data class ExchangeEvent(
    /** Ply of the first capture in the run. */
    val ply: Int,
    /** Plies of the run, in order. */
    val plies: List<Int>,
    val whiteMaterial: Int,
    val blackMaterial: Int,
) {
    val material: Int get() = whiteMaterial + blackMaterial

    /** True when both sides took material of the same value. */
    val isTrade: Boolean get() = whiteMaterial == blackMaterial

    /** Positive when White came out ahead of the exchange. */
    val balance: Int get() = whiteMaterial - blackMaterial

    val plyCount: Int get() = plies.size

    fun describe(): String = when {
        isTrade -> "${plyCount} plies, an even trade of ${material}cp"
        balance > 0 -> "${plyCount} plies, White wins ${balance}cp"
        else -> "${plyCount} plies, Black wins ${-balance}cp"
    }
}

/**
 * Groups consecutive capturing plies into [ExchangeEvent]s.
 *
 * Two captures in a row are already an exchange; a run needs at least
 * [MIN_MATERIAL] centipawns of material to be worth a story stop, and a single
 * capture never counts.
 */
object Exchanges {

    /** A run is only reported from this much material up. */
    const val MIN_MATERIAL = 200

    /** How many plies a run must span to be an exchange. */
    const val MIN_PLIES = 2

    fun detect(facts: List<PlyFacts>): List<ExchangeEvent> {
        val events = ArrayList<ExchangeEvent>()
        var run = ArrayList<PlyFacts>()

        fun flush() {
            if (run.size >= MIN_PLIES) {
                var white = 0
                var black = 0
                for (fact in run) {
                    if (fact.moverWhite) white += fact.capturedValue else black += fact.capturedValue
                }
                if (white + black >= MIN_MATERIAL) {
                    events.add(
                        ExchangeEvent(
                            ply = run.first().ply,
                            plies = run.map { it.ply },
                            whiteMaterial = white,
                            blackMaterial = black,
                        ),
                    )
                }
            }
            run = ArrayList()
        }

        for (fact in facts) {
            if (fact.isCapture) run.add(fact) else flush()
        }
        flush()
        return events
    }
}
