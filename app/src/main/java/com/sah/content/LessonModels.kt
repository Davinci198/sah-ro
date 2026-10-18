package com.sah.content

/** Screen kinds used by the lesson scripts. */
enum class ScreenType {
    TALK,
    TASK,
    EXAM,
    SUMMARY;

    companion object {
        fun fromToken(token: String?): ScreenType = when (token) {
            "task" -> TASK
            "exam" -> EXAM
            "summary" -> SUMMARY
            else -> TALK
        }
    }
}

/** UI language of the bundled lesson texts. */
enum class Language { RO, EN }

/** A lesson text in both bundled languages. */
data class LocalizedText(val ro: String, val en: String) {
    fun of(language: Language): String =
        if (language == Language.RO && ro.isNotEmpty()) ro else en.ifEmpty { ro }
}

/** `arrows: [["e1","e8","bad"]]` — a hint arrow with its semantic kind. */
data class Arrow(val from: String, val to: String, val kind: String)

/** `marks: {good: [...], bad: [...], focus: [...]}` — highlighted squares. */
data class Marks(
    val good: List<String> = emptyList(),
    val bad: List<String> = emptyList(),
    val focus: List<String> = emptyList(),
) {
    val hasContent: Boolean get() = good.isNotEmpty() || bad.isNotEmpty() || focus.isNotEmpty()
}

/** One tap step: tap every square in [targets] (in order unless [all]). */
data class TapStep(
    val targets: List<String>,
    val all: Boolean = false,
    val text: String? = null,
    val fact: String? = null,
)

/** `tap` challenge: `steps` plus squares that must never be tapped. */
data class TapTask(
    val steps: List<TapStep> = emptyList(),
    val ignore: List<String> = emptyList(),
    val wrong: String? = null,
)

/**
 * One move step. [accept] holds every accepted UCI move; [reply] is a forced
 * opponent answer, [rule] the validation rule (`mate`, `see_win`, `check`,
 * `stop_mate`, `castle`, `mate_in:N`, …), [sf] marks engine-checked steps,
 * [unique] requires the exact move, [subset]/[lure] widen or trap the solution.
 */
data class MoveStep(
    val accept: List<String> = emptyList(),
    val reply: String? = null,
    val rule: String? = null,
    val unique: Boolean = false,
    val sf: Boolean = false,
    val subset: Boolean = false,
    val lure: Boolean = false,
)

/** `moves` challenge: play the accepted move(s), optionally with a forced reply. */
data class MoveTask(
    val steps: List<MoveStep> = emptyList(),
    val wrong: String? = null,
)

/** Place a specific piece on [targets] (used by the "set up the board" tasks). */
data class PlaceStep(val piece: Char, val targets: List<String> = emptyList())

/** `quiz`: pick the right option index; [fact] names the concept being tested. */
data class Quiz(
    val options: List<String> = emptyList(),
    val answer: Int = 0,
    val fact: String? = null,
    val wrong: String? = null,
)

/** `play`: play against the engine until [goal] (`mate`, `promote`) within [limit]. */
data class PlayTask(val goal: String, val limit: Int)

/**
 * `stars`: collect the star squares, scored by how many moves the solution took.
 * [stars] lists the target squares in reveal order (it may be shorter than
 * [max]); [solution] is the optimal UCI line.
 */
data class StarsTask(
    val stars: List<String> = emptyList(),
    val max: Int = 0,
    val solution: List<String> = emptyList(),
)

/** One lesson screen. Only the fields relevant to [type] are populated. */
data class LessonScreen(
    val type: ScreenType,
    val textKey: String,
    val fen: String? = null,
    val marks: Marks? = null,
    val arrows: List<Arrow> = emptyList(),
    val dotsFrom: String? = null,
    val demo: List<String> = emptyList(),
    val tap: TapTask? = null,
    val moves: MoveTask? = null,
    val place: List<PlaceStep> = emptyList(),
    val quiz: Quiz? = null,
    val play: PlayTask? = null,
    val stars: StarsTask? = null,
) {
    val hasBoard: Boolean get() = fen != null
}

/** A lesson = a numbered sequence of screens at a difficulty [level] (1..4). */
data class Lesson(
    val n: Int,
    val titleKey: String,
    val level: Int,
    val screens: List<LessonScreen>,
)

/** A section grouping lessons that share a theme. */
data class LessonSection(
    val n: Int,
    val titleKey: String,
    val lessons: List<Lesson>,
)

/**
 * The whole lesson book with its string table. `lessons.json` stores text as
 * string keys; [strings] resolves them for the active [Language].
 */
data class LessonBook(
    val version: Int,
    val sections: List<LessonSection>,
    val strings: Map<String, LocalizedText>,
) {
    val lessonCount: Int get() = sections.sumOf { it.lessons.size }

    val screenCount: Int get() = sections.sumOf { section -> section.lessons.sumOf { it.screens.size } }

    fun text(key: String?, language: Language): String {
        if (key == null) return ""
        return strings[key]?.of(language) ?: key
    }

    fun lesson(sectionNumber: Int, lessonNumber: Int): Lesson? =
        sections.firstOrNull { it.n == sectionNumber }
            ?.lessons?.firstOrNull { it.n == lessonNumber }

    /** Lessons in reading order, as (section, lesson) pairs. */
    fun flattened(): List<Lesson> = sections.flatMap { it.lessons }

    companion object {
        val EMPTY = LessonBook(0, emptyList(), emptyMap())
    }
}
