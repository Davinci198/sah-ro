package com.sah.content

/**
 * Loads the bundled lesson scripts (`assets/lessons/lessons.json`) together with
 * their translations (`assets/lessons/strings.json`).
 *
 * Both inputs are plain strings so the parser is fully unit-testable on a JVM;
 * the Android layer only has to read the two asset files and hand them over.
 */
object LessonRepository {

    fun parse(lessonsJson: String, stringsJson: String): LessonBook {
        val root = Json.parseObject(lessonsJson)
        val version = root.int("version") ?: 1
        val sections = root.objectList("sections").map(::parseSection)
        return LessonBook(version = version, sections = sections, strings = parseStrings(stringsJson))
    }

    /** Parses `{ "key": {"ro": "...", "en": "..."} }`. */
    fun parseStrings(stringsJson: String): Map<String, LocalizedText> {
        val root = Json.parseObject(stringsJson)
        val result = LinkedHashMap<String, LocalizedText>(root.size)
        for ((key, value) in root) {
            val entry = value as? Map<*, *> ?: continue
            result[key] = LocalizedText(
                ro = entry["ro"] as? String ?: "",
                en = entry["en"] as? String ?: "",
            )
        }
        return result
    }

    private fun parseSection(map: Map<String, Any?>): LessonSection = LessonSection(
        n = map.int("n") ?: 0,
        titleKey = map.string("title").orEmpty(),
        lessons = map.objectList("lessons").map(::parseLesson),
    )

    /**
     * The script only writes `fen` on the screens whose position changes; every
     * other screen keeps the last position of its lesson (an empty board for a
     * lesson whose first screen has no `fen`). The shipped app parses the same
     * way, which is why so few screens carry a position.
     */
    private fun parseLesson(map: Map<String, Any?>): Lesson {
        var fen = ""
        val screens = map.objectList("screens").map { screen ->
            screen.string("fen")?.let { fen = it }
            parseScreen(screen, fen)
        }
        return Lesson(
            n = map.int("n") ?: 0,
            titleKey = map.string("title").orEmpty(),
            level = map.int("level") ?: 1,
            screens = screens,
        )
    }

    private fun parseScreen(map: Map<String, Any?>, fen: String): LessonScreen = LessonScreen(
        type = ScreenType.fromToken(map.string("t")),
        textKey = map.string("text").orEmpty(),
        fen = fen,
        marks = parseMarks(map.objectOrNull("marks")),
        arrows = parseArrows(map.arrayOrNull("arrows")),
        dotsFrom = map.string("dotsFrom"),
        demo = map.stringList("demo"),
        tap = parseTap(map.objectOrNull("tap")),
        moves = parseMoves(map.objectOrNull("moves")),
        place = parsePlace(map.objectOrNull("place")),
        quiz = parseQuiz(map.objectOrNull("quiz")),
        play = parsePlay(map.objectOrNull("play")),
        stars = parseStars(map.objectOrNull("stars")),
    )

    private fun parseMarks(map: Map<String, Any?>?): Marks? {
        if (map == null) return null
        val marks = Marks(
            good = map.stringList("good"),
            bad = map.stringList("bad"),
            focus = map.stringList("focus"),
        )
        return if (marks.hasContent) marks else null
    }

    private fun parseArrows(raw: List<Any?>?): List<Arrow> {
        if (raw == null) return emptyList()
        val arrows = ArrayList<Arrow>(raw.size)
        for (entry in raw) {
            val parts = entry as? List<*> ?: continue
            val from = parts.getOrNull(0) as? String ?: continue
            val to = parts.getOrNull(1) as? String ?: continue
            val kind = parts.getOrNull(2) as? String ?: "focus"
            arrows.add(Arrow(from, to, kind))
        }
        return arrows
    }

    private fun parseTap(map: Map<String, Any?>?): TapTask? {
        if (map == null) return null
        return TapTask(
            steps = map.objectList("steps").map { step ->
                TapStep(
                    targets = step.stringList("targets"),
                    all = step.boolean("all") ?: false,
                    text = step.string("text"),
                    fact = step.string("fact"),
                )
            },
            ignore = map.stringList("ignore"),
            wrong = map.string("wrong"),
            strict = map.boolean("strict") ?: false,
        )
    }

    private fun parseMoves(map: Map<String, Any?>?): MoveTask? {
        if (map == null) return null
        return MoveTask(
            steps = map.objectList("steps").map { step ->
                MoveStep(
                    accept = step.stringList("accept"),
                    reply = step.string("reply"),
                    rule = step.string("rule"),
                    unique = step.boolean("unique") ?: false,
                    sf = step.boolean("sf") ?: false,
                    subset = step.boolean("subset") ?: false,
                    lure = step.boolean("lure") ?: false,
                )
            },
            wrong = map.string("wrong"),
        )
    }

    private fun parsePlace(map: Map<String, Any?>?): List<PlaceStep> {
        if (map == null) return emptyList()
        return map.objectList("steps").mapNotNull { step ->
            val piece = step.string("piece")?.firstOrNull() ?: return@mapNotNull null
            PlaceStep(piece = piece, targets = step.stringList("targets"))
        }
    }

    private fun parseQuiz(map: Map<String, Any?>?): Quiz? {
        if (map == null) return null
        return Quiz(
            options = map.stringList("options"),
            answer = map.int("answer") ?: 0,
            fact = map.string("fact"),
            wrong = map.string("wrong"),
        )
    }

    private fun parsePlay(map: Map<String, Any?>?): PlayTask? {
        if (map == null) return null
        return PlayTask(
            goal = map.string("goal") ?: "mate",
            limit = map.int("limit") ?: 0,
        )
    }

    private fun parseStars(map: Map<String, Any?>?): StarsTask? {
        if (map == null) return null
        val stars = map.stringList("stars")
        return StarsTask(
            stars = stars,
            max = map.int("max") ?: stars.size,
            solution = map.stringList("solution"),
            only = map.string("only"),
            wrong = map.string("wrong"),
        )
    }
}

/** Resolves the string keys of a screen for display. */
object LessonTexts {

    fun text(book: LessonBook, screen: LessonScreen, language: Language): String =
        book.strings[screen.textKey]?.of(language) ?: screen.textKey

    fun title(book: LessonBook, titleKey: String, language: Language): String =
        book.strings[titleKey]?.of(language) ?: titleKey

    fun quizOptions(book: LessonBook, quiz: Quiz, language: Language): List<String> =
        quiz.options.map { book.strings[it]?.of(language) ?: it }

    /** True when the script's [Quiz.answer] index is in range. */
    fun quizAnswerText(book: LessonBook, quiz: Quiz, language: Language): String? =
        quizOptions(book, quiz, language).getOrNull(quiz.answer)
}
