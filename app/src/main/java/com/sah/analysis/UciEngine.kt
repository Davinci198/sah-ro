package com.sah.analysis

import com.sah.chess.ChessBoard
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Stockfish 11 driven as a separate process over UCI — the same architecture
 * the original application used.
 *
 * The binary ships inside the APK as `libstockfish.so` (jniLibs, extracted at
 * install). Android refuses exec() from the app's writable directories, so the
 * installed native library directory is the only place a bundled engine can
 * run from; [create] returns null when that is not possible here and the
 * caller keeps the pure-Kotlin [HeuristicEngine].
 *
 * One instance owns one process; [evaluate] is synchronous, like
 * [HeuristicEngine]. A process that dies mid-analysis is restarted once, a
 * second failure reaches the caller.
 */
class UciEngine private constructor(
    private val executablePath: String,
    override val profile: EngineProfile,
) : PositionEngine {

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null

    /** The engine's `id name`, e.g. "Stockfish 11 64 POPCNT". */
    var engineName: String = "Stockfish"
        private set

    override fun evaluate(board: ChessBoard, milliseconds: Int): PositionEval {
        val lines = try {
            ask(board.toFen(), milliseconds)
        } catch (failure: IOException) {
            // A dead or wedged process: one clean restart, then the caller
            // sees the failure (the report path turns it into a message).
            restart()
            ask(board.toFen(), milliseconds)
        }
        return parseEval(lines)
    }

    override fun close() {
        runCatching {
            writer?.write("quit\n")
            writer?.flush()
        }
        process?.destroy()
        process = null
        writer = null
        reader = null
    }

    private fun restart() {
        close()
        start()
    }

    private fun ensureStarted() {
        if (process?.isAlive != true) start()
    }

    /** Spawns the process and completes the UCI handshake, or throws. */
    private fun start() {
        val spawned = ProcessBuilder(executablePath).redirectErrorStream(true).start()
        val out = BufferedWriter(OutputStreamWriter(spawned.outputStream))
        val incoming = BufferedReader(InputStreamReader(spawned.inputStream))
        val pool = Executors.newSingleThreadExecutor()
        try {
            val named = pool.submit(Callable<String?> {
                out.write("uci\n")
                out.flush()
                var name: String? = null
                var answered = false
                while (!answered) {
                    val line = incoming.readLine()
                        ?: throw IOException("Stockfish a închis canalul")
                    if (line.startsWith("id name ")) name = line.removePrefix("id name ")
                    if (line.startsWith("uciok")) answered = true
                }
                name
            }).get(HANDSHAKE_MILLIS, TimeUnit.MILLISECONDS)
            engineName = named ?: "Stockfish"
            // Two lines per search: the report classifies GREAT from the second.
            out.write("setoption name MultiPV value 2\n")
            out.flush()
            process = spawned
            writer = out
            reader = incoming
        } catch (failure: Exception) {
            spawned.destroy()
            throw IOException("handshake UCI eșuat", failure)
        } finally {
            pool.shutdownNow()
        }
    }

    /** One `go movetime`, collecting output up to and including `bestmove`. */
    private fun ask(fen: String, millis: Int): List<String> {
        ensureStarted()
        val out = writer ?: throw IOException("motorul nu e pornit")
        val incoming = reader ?: throw IOException("motorul nu e pornit")
        out.write("position fen $fen\n")
        out.write("go movetime $millis\n")
        out.flush()
        val lines = ArrayList<String>()
        while (true) {
            val line = incoming.readLine() ?: throw IOException("Stockfish a închis canalul")
            lines.add(line)
            if (line.startsWith("bestmove")) return lines
        }
    }

    companion object {
        private const val LIBRARY = "libstockfish.so"
        private const val HANDSHAKE_MILLIS = 5_000L

        /**
         * The bundled Stockfish from the installed native libraries, or null
         * when it cannot run here — the caller then keeps [HeuristicEngine].
         */
        fun create(nativeLibraryDir: String, profile: EngineProfile): UciEngine? {
            val binary = File(nativeLibraryDir, LIBRARY)
            if (!binary.isFile) return null
            if (!binary.canExecute()) binary.setExecutable(true, true)
            val engine = UciEngine(binary.absolutePath, profile)
            return try {
                engine.start()
                engine
            } catch (failure: IOException) {
                engine.close()
                null
            }
        }

        /**
         * The evaluation out of one search's output: the last multipv 1 line
         * with a score wins, multipv 2 supplies the second move the GREAT
         * classification needs, and `bestmove` is the fallback move. UCI
         * `score mate n` becomes plies for the side to move — 2n-1 when it
         * mates, -2n when it is mated, -1 when it is already mated — the same
         * convention as [HeuristicEngine].
         */
        internal fun parseEval(lines: List<String>): PositionEval {
            var bestCp: Int? = null
            var bestMate: Int? = null
            var bestPv: String? = null
            var secondCp: Int? = null
            var secondMate: Int? = null
            var secondPv: String? = null
            var depth = 0
            var nodes = 0L
            var bestmove: String? = null
            for (line in lines) {
                if (line.startsWith("bestmove ")) {
                    bestmove = line.split(' ').getOrNull(1)?.takeIf { it != "(none)" }
                    continue
                }
                if (!line.startsWith("info ")) continue
                var multipv = 1
                var cp: Int? = null
                var mate: Int? = null
                var pv: String? = null
                var lineDepth: Int? = null
                var lineNodes: Long? = null
                val parts = line.split(' ')
                var i = 0
                while (i < parts.size) {
                    when (parts[i]) {
                        "multipv" -> multipv = parts.getOrNull(i + 1)?.toIntOrNull() ?: 1
                        "depth" -> lineDepth = parts.getOrNull(i + 1)?.toIntOrNull()
                        "nodes" -> lineNodes = parts.getOrNull(i + 1)?.toLongOrNull()
                        "score" -> when (parts.getOrNull(i + 1)) {
                            "cp" -> cp = parts.getOrNull(i + 2)?.toIntOrNull()
                            "mate" -> mate = parts.getOrNull(i + 2)?.toIntOrNull()
                        }
                        "pv" -> pv = parts.getOrNull(i + 1)
                    }
                    i++
                }
                if (cp == null && mate == null) continue
                if (multipv == 2) {
                    secondCp = cp
                    secondMate = mate
                    secondPv = pv
                } else {
                    bestCp = cp
                    bestMate = mate
                    bestPv = pv
                    lineDepth?.let { depth = it }
                    lineNodes?.let { nodes = it }
                }
            }
            val plies = bestMate?.let { matePlies(it) }
            val scoreCp = if (plies != null) mateScore(plies) else (bestCp ?: 0)
            val secondPlies = secondMate?.let { matePlies(it) }
            return PositionEval(
                scoreCp = scoreCp,
                mateInPlies = plies,
                bestMove = bestPv ?: bestmove,
                secondMove = secondPv,
                secondScoreCp = if (secondPlies != null) mateScore(secondPlies) else (secondCp ?: scoreCp),
                depth = depth,
                nodes = nodes,
            )
        }

        /** UCI mate distance to [PositionEval.mateInPlies] plies. */
        private fun matePlies(uciMate: Int): Int = when {
            uciMate > 0 -> 2 * uciMate - 1
            uciMate < 0 -> -2 * (-uciMate)
            else -> -1
        }

        /** The mate score convention of [HeuristicEngine], just below mate. */
        private fun mateScore(plies: Int): Int =
            if (plies > 0) {
                Evaluator.MATE_SCORE - plies
            } else {
                -(Evaluator.MATE_SCORE - kotlin.math.abs(plies))
            }
    }
}
