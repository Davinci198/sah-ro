package com.sah.analysis

import com.sah.chess.ChessBoard
import com.sah.chess.MoveGenerator
import com.sah.chess.San
import com.sah.content.OpeningBook
import com.sah.content.Puzzle
import com.sah.content.PuzzleRepository
import com.sah.games.ImportedGame
import com.sah.games.Pgn

/**
 * Turns the player's own mistakes into playable puzzles.
 *
 * For every half-move the player made that threw away at least [MIN_LOSS] win%
 * while a clearly better move existed, the position becomes a puzzle in the
 * bundled format: [Puzzle.fen] is the board *before* the opponent's setup move,
 * [Puzzle.moves] is `[opponent setup, better move]`. `PuzzleSession` replays
 * moves[0] and then asks the solver — sitting in the player's seat — to find
 * moves[1]: the move they should have played in the real game.
 *
 * The pipeline reuses [GameAnalyzer] but with [BATCH_PROFILE], a quick-only
 * configuration (one shallow pass per position, no deep re-check) so a whole
 * game costs seconds instead of a minute on-device. Puzzle extraction only
 * needs the engine's best move and the win% each move threw away, both of which
 * the quick pass already provides.
 */
object MistakePuzzles {

    /**
     * Quick-only profile: a single shallow pass per position. The huge
     * [EngineProfile.suspectLoss] disables the deep re-check pass and the tiny
     * deep/check budgets make the sacrifice and threat probes no-ops, so a game
     * is analysed at roughly the cost of its quick pass alone.
     */
    val BATCH_PROFILE: EngineProfile = EngineProfile.CARBALLO.copy(
        quickMillis = 80,
        deepMillis = 1,
        checkMillis = 1,
        suspectLoss = 9999.0,
    )

    /** Win% a move must throw away before it becomes a puzzle. */
    const val MIN_LOSS: Double = 25.0

    /** Cap per game so one wild game cannot flood the set. */
    const val MAX_PER_GAME: Int = 3

    /** Runs the engine over [game] and extracts the puzzles it contains. */
    fun extract(game: ImportedGame, book: OpeningBook? = null): List<Puzzle> {
        val parsed = Pgn.parse(game.pgn)
        val uci = toUci(parsed.startFen, parsed.moves)
        if (uci.size < 2) return emptyList()
        val playerIsWhite = game.myColor == "white"
        return GameAnalyzer(HeuristicEngine(BATCH_PROFILE), book).use { analyzer ->
            fromAnalysis(analyzer.analyze(parsed.startFen, uci, playerIsWhite), game, playerIsWhite)
        }
    }

    /**
     * Pure extraction from an already-computed [analysis]: the engine-free half of
     * [extract], so the selection and the puzzle layout can be tested without a
     * real search. Picks the player's costly mistakes and lays them out as
     * `[setup, better move]` puzzles.
     */
    fun fromAnalysis(analysis: GameAnalysis, game: ImportedGame, playerIsWhite: Boolean): List<Puzzle> {
        val plies = analysis.plies
        val puzzles = ArrayList<Puzzle>()
        for (ply in plies) {
            if (puzzles.size >= MAX_PER_GAME) break
            if (ply.moverWhite != playerIsWhite) continue        // only the player's own moves
            if (ply.ply < 1) continue                             // needs an opponent setup move before it
            if (ply.loss < MIN_LOSS) continue                     // not costly enough to be instructive
            val better = ply.bestMove ?: continue
            if (better == ply.moveUci) continue                   // the player already found the best move
            val setup = plies[ply.ply - 1]
            if (setup.moverWhite == playerIsWhite) continue       // setup must be the opponent's move
            puzzles += build(game, setup, better, ply)
        }
        return puzzles
    }

    private fun build(game: ImportedGame, setup: PlyAnalysis, betterMove: String, ply: PlyAnalysis): Puzzle {
        val rating = game.myRating ?: game.opponentRating ?: 1200
        val difficulty = difficultyForLoss(ply.loss)
        val moveNumber = ply.ply / 2 + 1
        return Puzzle(
            fen = setup.before.toFen(),
            moves = listOf(setup.moveUci, betterMove),
            rating = rating,
            difficulty = difficulty,
            pointsPerMove = PuzzleRepository.pointsFor(difficulty),
            plies = 2,
            source = "Jocul cu ${game.opponent}, ${game.date}, mutarea $moveNumber",
        )
    }

    /** Clearer mistakes are easier puzzles: the bigger the loss, the more obvious. */
    fun difficultyForLoss(loss: Double): Int = when {
        loss >= 60.0 -> 3
        loss >= 40.0 -> 5
        else -> 7
    }

    /** SAN half-moves to UCI, stopping at the first that cannot be replayed. */
    private fun toUci(startFen: String, sanMoves: List<String>): List<String> {
        val uci = ArrayList<String>(sanMoves.size)
        var board = runCatching { ChessBoard.fromFen(startFen) }.getOrNull() ?: return emptyList()
        for (san in sanMoves) {
            val move = San.parse(board, san) ?: break
            uci += move.uci()
            board = MoveGenerator.applyMove(board, move)
        }
        return uci
    }
}
