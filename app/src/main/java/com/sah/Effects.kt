package com.sah

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.sah.chess.ChessBoard
import com.sah.chess.Pieces
import java.io.File
import kotlin.math.abs
import kotlin.random.Random

/**
 * The original app's `EffectsMaker`, without the parts the port has no use for
 * (chat, billing): a SoundPool over the bundled m4a files of `assets/sounds`.
 *
 * The files are copied out of the APK into the cache on first use, because
 * `SoundPool` needs real paths and compressed assets have none. Every activity
 * calls [prepare] from `onCreate`; a missing sound only stays silent.
 */
object Effects {

    private const val VOLUME = 0.8f

    /** Every sound the port plays; `stopwatch` is wired up with the puzzle timer. */
    private val bank = listOf(
        "move1", "move2", "capture1", "capture2", "castling", "promotion",
        "check", "step_correct", "step_wrong", "task_completed", "bell",
        "victory", "chesspiecesfall", "stopwatch",
    )

    private val pool: SoundPool by lazy {
        SoundPool.Builder()
            .setMaxStreams(8)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .build()
    }
    private val ids = HashMap<String, Int>()

    /** Loads the sound bank once; safe to call from every screen. */
    fun prepare(context: Context) {
        if (ids.isNotEmpty()) return
        val dir = File(context.cacheDir, "sounds")
        for (name in bank) {
            try {
                val file = File(dir, "$name.m4a")
                if (!file.exists()) {
                    dir.mkdirs()
                    context.assets.open("sounds/$name.m4a").use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                ids[name] = pool.load(file.absolutePath, 1)
            } catch (_: Exception) {
                // One broken file must not take the rest of the bank down.
            }
        }
    }

    private fun play(name: String) {
        ids[name]?.let { pool.play(it, VOLUME, VOLUME, 1, 0, 1f) }
    }

    private fun playEither(first: String, second: String) =
        play(if (Random.nextBoolean()) first else second)

    /** A fresh puzzle or game is on the board. */
    fun start() = play("chesspiecesfall")

    /** The "Indiciu" button. */
    fun hint() = play("bell")

    fun check() = play("check")
    fun stepCorrect() = play("step_correct")
    fun stepWrong() = play("step_wrong")
    fun taskCompleted() = play("task_completed")
    fun victory() = play("victory")

    /**
     * The move's own sound on the position [before] it was played: promotions,
     * castling and captures each have their file in the original, the quiet moves
     * alternate between the two variants like the original did.
     */
    fun moveOn(before: ChessBoard, from: Square, to: Square) {
        val piece = before.pieceAt(from)
        when {
            Pieces.typeOf(piece) == Pieces.PAWN && (to.rank == 0 || to.rank == 7) -> play("promotion")
            Pieces.typeOf(piece) == Pieces.KING && abs(from.file - to.file) == 2 -> play("castling")
            before.pieceAt(to) != Pieces.EMPTY -> playEither("capture1", "capture2")
            else -> playEither("move1", "move2")
        }
    }
}
