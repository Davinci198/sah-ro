package com.sah

import com.sah.content.LessonBook
import com.sah.content.LessonRepository
import com.sah.content.OpeningBook
import com.sah.content.OpeningRepository
import com.sah.content.Puzzle
import com.sah.content.PuzzleRepository
import java.io.File

/**
 * The bundled assets, resolved from whichever directory the tests run in.
 *
 * Gradle runs unit tests with the module directory as the working directory, but a
 * plain `kotlinc` run from the repo root is just as common, so both
 * `<root>/app/src/main/assets` and `<root>/src/main/assets` are tried while walking
 * up from [System.getProperty] `user.dir`. Everything is a `by lazy` singleton
 * because `lessons.json` and `puzzles.csv` are several megabytes and a full test
 * JVM shares one copy.
 */
object TestAssets {

    private fun file(path: String): File {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory != null) {
            for (candidate in listOf(
                File(directory, "app/src/main/assets/$path"),
                File(directory, "src/main/assets/$path"),
            )) {
                if (candidate.isFile) return candidate
            }
            directory = directory.parentFile
        }
        error("bundled asset not found: $path (working directory ${System.getProperty("user.dir")})")
    }

    val lessonsJson: String by lazy { file("lessons/lessons.json").readText() }
    val stringsJson: String by lazy { file("lessons/strings.json").readText() }
    val puzzlesCsv: String by lazy { file("puzzles/puzzles.csv").readText() }
    val openingsCsv: String by lazy { file("openings/openings.csv").readText() }

    /** The whole lesson book, as the app loads it. */
    val book: LessonBook by lazy { LessonRepository.parse(lessonsJson, stringsJson) }

    val puzzles: List<Puzzle> by lazy { PuzzleRepository.parse(puzzlesCsv) }

    val openings: OpeningBook by lazy { OpeningRepository.parse(openingsCsv) }
}
