package com.sah.content

/**
 * One bundled puzzle.
 *
 * [moves] is the full UCI line. The original app replays the first entry when
 * the puzzle opens — the opponent's last move, the one that created the
 * position — and then the solver answers it: the odd-indexed moves are the
 * solver's, the even-indexed ones are played automatically.
 */
data class Puzzle(
    val fen: String,
    val moves: List<String>,
    val rating: Int,
    val difficulty: Int,
    val pointsPerMove: Int,
    val plies: Int,
    val source: String,
) {
    /** Moves the solver must find, in order (indices 1, 3, 5, …). */
    val playerMoves: List<String>
        get() = moves.filterIndexed { index, _ -> index % 2 == 1 }

    /** Opponent moves (indices 0, 2, 4, …); index 0 opens the puzzle. */
    val opponentMoves: List<String>
        get() = moves.filterIndexed { index, _ -> index % 2 == 0 }
}

/**
 * Parses `assets/puzzles/puzzles.csv` (`fen,moves,rating,difficulty,
 * points_per_move,plies,sursa`).
 *
 * [difficultyFor] and [pointsFor] reproduce the mapping of the original
 * `PuzzleLoader`, verified against all 20 758 bundled rows.
 */
object PuzzleRepository {

    const val MIN_DIFFICULTY = 1
    const val MAX_DIFFICULTY = 10

    private val moveSeparator = Regex("\\s+")

    fun parse(csv: String): List<Puzzle> = Csv.records(csv).mapNotNull(::parseRecord)

    private fun parseRecord(record: Map<String, String>): Puzzle? {
        val fen = record["fen"]?.trim().orEmpty()
        val moves = record["moves"]
            ?.trim()
            ?.split(moveSeparator)
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
        if (fen.isEmpty() || moves.isEmpty()) return null
        val rating = record["rating"]?.trim()?.toIntOrNull() ?: 0
        val difficulty = record["difficulty"]?.trim()?.toIntOrNull() ?: difficultyFor(rating)
        return Puzzle(
            fen = fen,
            moves = moves,
            rating = rating,
            difficulty = difficulty,
            pointsPerMove = record["points_per_move"]?.trim()?.toIntOrNull() ?: pointsFor(difficulty),
            plies = record["plies"]?.trim()?.toIntOrNull() ?: moves.size,
            source = record["sursa"]?.trim().orEmpty(),
        )
    }

    /** Rating to difficulty bucket, exactly as the original app computes it. */
    fun difficultyFor(rating: Int): Int = when {
        rating <= 800 -> 1
        rating <= 900 -> 2
        rating <= 1000 -> 3
        rating <= 1100 -> 4
        rating <= 1200 -> 5
        rating <= 1300 -> 6
        rating <= 1400 -> 7
        rating <= 1500 -> 8
        rating <= 1600 -> 9
        else -> 10
    }

    /** Always at least one point; two difficulty steps are worth one point. */
    fun pointsFor(difficulty: Int): Int = maxOf(1, difficulty / 2)
}

/** Index over the parsed puzzle list, for picking puzzles by difficulty. */
class PuzzleBook(private val puzzles: List<Puzzle>) {

    val size: Int get() = puzzles.size

    fun all(): List<Puzzle> = puzzles

    fun byDifficulty(difficulty: Int): List<Puzzle> =
        puzzles.filter { it.difficulty == difficulty.coerceIn(PuzzleRepository.MIN_DIFFICULTY, PuzzleRepository.MAX_DIFFICULTY) }

    /** Puzzles within a +/- [tolerance] rating window around [rating]. */
    fun byRating(rating: Int, tolerance: Int = 100): List<Puzzle> =
        puzzles.filter { it.rating in (rating - tolerance)..(rating + tolerance) }

    fun random(difficulty: Int, random: kotlin.random.Random = kotlin.random.Random): Puzzle? =
        byDifficulty(difficulty).let { list -> if (list.isEmpty()) null else list[random.nextInt(list.size)] }

    fun get(index: Int): Puzzle? = puzzles.getOrNull(index)
}
