package com.sah.analysis

import com.sah.Square
import com.sah.chess.ChessBoard
import com.sah.chess.ChessMove
import com.sah.chess.MoveGenerator
import com.sah.content.GeneratedPuzzlesStore
import com.sah.content.Puzzle
import com.sah.content.toJsonLine
import com.sah.games.ImportedGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Turning the player's own mistakes into puzzles: the selection rules (whose
 * move, how costly, was there a better one), the `[setup, better move]` layout,
 * the difficulty mapping, and the append-only generated-puzzles store. Every
 * puzzle here is checked for a legal replay, so a bad FEN or an unresolvable
 * move fails the test rather than the solver.
 */
class MistakePuzzlesTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val game = ImportedGame(
        id = "g1",
        platform = "lichess",
        opponent = "Adversar",
        opponentRating = 1450,
        myRating = 1500,
        myColor = "white",
        outcome = "L",
        date = "2024-01-02",
        timeControl = "600+0",
        timeClass = "blitz",
        opening = "Sicilian",
        url = "",
        pgn = "",
    )

    private fun uci(text: String): ChessMove = ChessMove.fromUci(text) ?: error("bad uci $text")

    /** Replays a fixed line so every ply has a real before/after board. */
    private fun boards(line: List<String>): List<ChessBoard> {
        val boards = arrayListOf(ChessBoard.START)
        var board = ChessBoard.START
        for (move in line) {
            board = MoveGenerator.applyMove(board, uci(move))
            boards += board
        }
        return boards
    }

    private fun ply(
        index: Int,
        before: ChessBoard,
        after: ChessBoard,
        moveUci: String,
        loss: Double,
        bestMove: String?,
        category: MoveCategory = MoveCategory.GOOD,
    ) = PlyAnalysis(
        ply = index,
        moveUci = moveUci,
        san = moveUci,
        moverWhite = index % 2 == 0,
        before = before,
        after = after,
        winBefore = 50.0,
        winAfter = 50.0 - loss,
        loss = loss,
        category = category,
        accuracy = 100.0 - loss,
        motif = null,
        bestMove = bestMove,
    )

    private fun analysisOf(plies: List<PlyAnalysis>) = GameAnalysis(
        startFen = ChessBoard.START_FEN,
        moves = plies.map { it.moveUci },
        plies = plies,
        exchanges = emptyList(),
        enPassantPlies = emptyList(),
        bookEndPly = 0,
        finalCount = plies.size,
        profile = EngineProfile.CARBALLO,
    )

    private fun replay(puzzle: Puzzle): ChessBoard {
        var board = ChessBoard.fromFen(puzzle.fen)
        for (move in puzzle.moves) {
            val resolved = MoveGenerator.resolveUci(board, move) ?: error("illegal $move in ${puzzle.fen}")
            board = MoveGenerator.applyMove(board, resolved)
        }
        return board
    }

    // -- the layout ----------------------------------------------------------

    @Test
    fun playerMistakeBecomesSetupAndSolutionPuzzle() {
        // 1.e4 c5 2.Nf3 ... — White (the player) should have played d4, not Nf3.
        val moves = listOf("e2e4", "c7c5", "g1f3", "d7d5", "f1b5", "b8c6", "e1g1", "a7a6")
        val b = boards(moves)
        val plies = List(moves.size) { i ->
            val isMistake = i == 2
            ply(
                index = i,
                before = b[i],
                after = b[i + 1],
                moveUci = moves[i],
                loss = if (isMistake) 40.0 else 0.0,
                bestMove = if (isMistake) "d2d4" else moves[i],
                category = if (isMistake) MoveCategory.MISTAKE else MoveCategory.GOOD,
            )
        }

        val puzzles = MistakePuzzles.fromAnalysis(analysisOf(plies), game, playerIsWhite = true)
        assertEquals(1, puzzles.size)

        val puzzle = puzzles.first()
        // FEN is the board before the opponent's setup move; moves = [setup, better move].
        assertEquals(b[1].toFen(), puzzle.fen)
        assertEquals(listOf("c7c5", "d2d4"), puzzle.moves)
        assertEquals(listOf("c7c5"), puzzle.opponentMoves)
        assertEquals(listOf("d2d4"), puzzle.playerMoves)
        assertEquals(2, puzzle.plies)
        assertEquals(1500, puzzle.rating)
        assertEquals(MistakePuzzles.difficultyForLoss(40.0), puzzle.difficulty)
        assertEquals("Jocul cu Adversar, 2024-01-02, mutarea 2", puzzle.source)

        // The puzzle must actually be playable: setup and solution are legal in sequence.
        val solved = replay(puzzle)
        assertFalse(solved.whiteToMove)
        assertEquals('P', solved.pieceAt(Square.parse("d4")!!))
    }

    @Test
    fun filtersSkipOpeningNoLossAndBestMoveAlreadyFound() {
        val moves = listOf("e2e4", "c7c5", "g1f3", "d7d5", "f1b5", "b8c6", "e1g1", "a7a6")
        val b = boards(moves)
        // i=0: a costly first move, but there is no opponent setup before it (ply < 1).
        // i=2: a real mistake -> the one puzzle.
        // i=4: costly but the player already played the engine's best (best == move).
        // i=6: a better move existed, but the loss is under the puzzle threshold.
        val loss = doubleArrayOf(40.0, 0.0, 40.0, 0.0, 40.0, 0.0, 10.0, 0.0)
        val best = arrayOf("d2d4", moves[1], "d2d4", moves[3], moves[4], moves[5], "d1d2", moves[7])
        val plies = List(moves.size) { i ->
            ply(
                index = i,
                before = b[i],
                after = b[i + 1],
                moveUci = moves[i],
                loss = loss[i],
                bestMove = best[i],
                category = if (loss[i] >= 25.0) MoveCategory.MISTAKE else MoveCategory.GOOD,
            )
        }

        val puzzles = MistakePuzzles.fromAnalysis(analysisOf(plies), game, playerIsWhite = true)
        assertEquals(1, puzzles.size)
        assertEquals(listOf("c7c5", "d2d4"), puzzles.first().moves)
    }

    @Test
    fun onlyThePlayersOwnMistakesAreConsidered() {
        // A Black blunder (the opponent's) while the player is White must not become a
        // puzzle: extraction is driven by the *player's* errors, not everyone's.
        val moves = listOf("e2e4", "c7c5", "g1f3", "d7d5", "f1b5", "b8c6")
        val b = boards(moves)
        val plies = List(moves.size) { i ->
            val blackBlunder = i == 3
            ply(
                index = i,
                before = b[i],
                after = b[i + 1],
                moveUci = moves[i],
                loss = if (blackBlunder) 40.0 else 0.0,
                bestMove = if (blackBlunder) "c8f5" else moves[i],
                category = if (blackBlunder) MoveCategory.BLUNDER else MoveCategory.GOOD,
            )
        }

        val asWhite = MistakePuzzles.fromAnalysis(analysisOf(plies), game, playerIsWhite = true)
        assertTrue(asWhite.isEmpty())

        // Playing as Black, that same blunder is the *player's* and does yield a puzzle.
        val asBlack = MistakePuzzles.fromAnalysis(analysisOf(plies), game, playerIsWhite = false)
        assertEquals(1, asBlack.size)
    }

    @Test
    fun atMostThreePuzzlesPerGameSurvive() {
        val moves = listOf(
            "e2e4", "c7c5", "g1f3", "d7d5", "f1b5", "b8c6",
            "e1g1", "a7a6", "d1e2", "b7b5", "c2c3", "g8f6",
        )
        val b = boards(moves)
        val plies = List(moves.size) { i ->
            val isMistake = i % 2 == 0 && i in listOf(2, 6, 8, 10)
            ply(
                index = i,
                before = b[i],
                after = b[i + 1],
                moveUci = moves[i],
                loss = if (isMistake) 45.0 else 0.0,
                bestMove = if (isMistake) "a2a3" else moves[i],
                category = if (isMistake) MoveCategory.MISTAKE else MoveCategory.GOOD,
            )
        }

        val puzzles = MistakePuzzles.fromAnalysis(analysisOf(plies), game, playerIsWhite = true)
        assertEquals(MistakePuzzles.MAX_PER_GAME, puzzles.size)
        // The three that survive are the earliest ones, in board order.
        assertEquals(listOf("c7c5", "b8c6", "a7a6"), puzzles.map { it.moves[0] })
    }

    @Test
    fun difficultyRisesAsTheMistakeGrowsObvious() {
        assertEquals(3, MistakePuzzles.difficultyForLoss(75.0))
        assertEquals(3, MistakePuzzles.difficultyForLoss(60.0))
        assertEquals(5, MistakePuzzles.difficultyForLoss(59.9))
        assertEquals(5, MistakePuzzles.difficultyForLoss(40.0))
        assertEquals(7, MistakePuzzles.difficultyForLoss(39.9))
        assertEquals(7, MistakePuzzles.difficultyForLoss(25.0))
    }

    @Test
    fun batchProfileSkipsTheDeepAndThreatPasses() {
        // Puzzle extraction only needs the quick pass, so the batch profile must not
        // spend time on deep re-checks (huge suspectLoss) or sacrifice/threat probes.
        val profile = MistakePuzzles.BATCH_PROFILE
        assertTrue(profile.suspectLoss >= 9999.0)
        assertTrue(profile.quickMillis in 1..120)
        assertTrue(profile.deepMillis <= 5)
        assertTrue(profile.checkMillis <= 5)
    }

    // -- the store -----------------------------------------------------------

    @Test
    fun generatedPuzzlesRoundTripAndTrackAnalyzedGames() {
        val store = GeneratedPuzzlesStore(temporaryFolder.newFolder("generated-puzzles"))
        assertTrue(store.load().isEmpty())
        assertTrue(store.analyzedIds().isEmpty())

        val puzzle = Puzzle(
            fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1",
            moves = listOf("c7c5", "d2d4"),
            rating = 1500,
            difficulty = 5,
            pointsPerMove = 2,
            plies = 2,
            source = "Jocul cu Adversar, 2024-01-02, mutarea 2",
        )
        store.append(listOf(puzzle))
        store.markAnalyzed(listOf("g1", "g2"))

        val loaded = store.load()
        assertEquals(1, loaded.size)
        assertEquals(puzzle, loaded.first())
        assertEquals(setOf("g1", "g2"), store.analyzedIds())
    }

    @Test
    fun appendIsIdempotentAcrossReloadsAndDedupesIdenticalPuzzles() {
        val store = GeneratedPuzzlesStore(temporaryFolder.newFolder("generated-puzzles"))
        val puzzle = Puzzle(
            fen = "8/8/8/8/8/8/8/K6k w - - 0 1",
            moves = listOf("a1a2", "h1h2"),
            rating = 1200,
            difficulty = 3,
            pointsPerMove = 1,
            plies = 2,
            source = "Jocul cu Cineva, 2024-02-02, mutarea 9",
        )
        store.append(listOf(puzzle, puzzle))
        // A rerun appends the same puzzle again; load() must keep a single copy.
        store.append(listOf(puzzle))
        assertEquals(1, store.load().size)

        // Marking the same game twice does not duplicate it either.
        store.markAnalyzed(listOf("g9"))
        store.markAnalyzed(listOf("g9"))
        assertEquals(setOf("g9"), store.analyzedIds())
    }

    @Test
    fun corruptStoreLinesAreSkippedRatherThanThrown() {
        val dir = temporaryFolder.newFolder("mixed")
        java.io.File(dir, "puzzles.ndjson").writeText(
            listOf(
                "{not json at all",
                Puzzle("8/8/8/8/8/8/8/K6k w - - 0 1", listOf("a1a2"), 1200, 3, 1, 2, "ok").toJsonLine(),
                "{}",
                Puzzle("8/8/8/8/8/8/8/K6k b - - 0 1", listOf("a1a2", "h1h2"), 1200, 3, 1, 2, "two").toJsonLine(),
            ).joinToString("\n") + "\n",
        )
        // Only the two complete puzzle lines survive; garbage and empty objects are dropped.
        val loaded = GeneratedPuzzlesStore(dir).load()
        assertEquals(2, loaded.size)
        assertTrue(loaded.all { it.moves.isNotEmpty() && it.fen.isNotBlank() })
    }
}
