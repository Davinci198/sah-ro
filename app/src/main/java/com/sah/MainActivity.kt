package com.sah

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.CountDownTimer
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.DateFormat
import java.util.Date

class MainActivity : Activity() {
    private val backgroundColor = Color.rgb(18, 18, 18)
    private val cardColor = Color.rgb(30, 30, 30)
    private val primaryColor = Color.rgb(118, 150, 86)
    private val textColor = Color.WHITE
    private val mutedColor = Color.rgb(160, 160, 160)
    private val successColor = Color.rgb(134, 239, 172)
    private val errorColor = Color.rgb(252, 165, 165)

    private lateinit var game: TrainerGame
    private lateinit var board: ChessBoardView
    private lateinit var prompt: TextView
    private lateinit var feedback: TextView
    private lateinit var score: TextView
    private lateinit var streak: TextView
    private lateinit var bestStreak: TextView
    private lateinit var diagonalButton: Button
    private lateinit var lessonCard: TextView
    private lateinit var timerLabel: TextView
    private lateinit var answers: LinearLayout
    private lateinit var resetButton: Button
    private lateinit var dashboardGreeting: TextView
    private lateinit var sessionsStat: TextView
    private lateinit var gamesStat: TextView
    private lateinit var mistakesStat: TextView
    private lateinit var globalBestStat: TextView
    private lateinit var mistakesCard: TextView
    private lateinit var mistakesTrainingButton: Button
    private lateinit var historyButton: Button
    private lateinit var learningStartButton: Button
    private val modeButtons = linkedMapOf<TrainerMode, Button>()

    private lateinit var profileStore: PlayerProfileStore
    private lateinit var historyStore: SessionHistoryStore
    private var profile = PlayerProfile()

    private var feedbackSquare: Square? = null
    private var feedbackCorrect: Boolean? = null
    private var lessonSquare: Square? = null
    private var timedRemaining = TIMED_SECONDS
    private var timedFinished = false
    private var timer: CountDownTimer? = null
    private var timerGeneration = 0L
    private var pendingRoundAdvance: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = backgroundColor
        window.navigationBarColor = backgroundColor
        profileStore = PlayerProfileStore(applicationContext)
        historyStore = SessionHistoryStore(applicationContext)
        profile = profileStore.load()
        game = TrainerGame(
            initialDifficulty = profile.difficultyLevel,
            historicalMistakes = historyStore.aggregateMistakes(),
        )
        savedInstanceState?.let {
            game.restore(it)
            timedRemaining = it.getInt(KEY_TIMED_REMAINING, TIMED_SECONDS).coerceIn(0, TIMED_SECONDS)
            timedFinished = it.getBoolean(KEY_TIMED_FINISHED, false)
            if (!game.state.acceptingAnswer) game.nextRound()
        }
        buildUi()
        refreshUi()
        if (game.state.mode == TrainerMode.TIMED && !timedFinished) startTimer()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        game.save(outState)
        outState.putInt(KEY_TIMED_REMAINING, timedRemaining)
        outState.putBoolean(KEY_TIMED_FINISHED, timedFinished)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        cancelPendingRoundAdvance()
        cancelTimer()
        super.onDestroy()
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply {
            setFillViewport(true)
            setBackgroundColor(backgroundColor)
            isVerticalScrollBarEnabled = false
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        scroll.addView(root, ViewGroup.LayoutParams(-1, -2))
        setContentView(scroll)

        root.addView(buildHeader())
        root.addView(buildDashboard(), fullWidthParams())
        root.addView(space(12))
        root.addView(buildModes())
        root.addView(space(12))
        root.addView(buildContent(), fullWidthParams())
        root.addView(space(12))
        root.addView(buildGamesCard(), fullWidthParams())
        root.addView(space(12))
        root.addView(buildSettingsCard(), fullWidthParams())
        root.addView(space(12))

        diagonalButton = controlButton("") { game.toggleDiagonals(); refreshUi() }.apply {
            contentDescription = "Arată sau ascunde diagonalele tablei"
        }
        root.addView(diagonalButton, fullWidthParams())
        root.addView(space(10))

        lessonCard = cardText("")
        root.addView(lessonCard, fullWidthParams())
        learningStartButton = controlButton("Începe lecția") { selectMode(TrainerMode.LEARN) }
        learningStartButton.contentDescription = "Începe lecția interactivă despre diagonale"
        root.addView(learningStartButton, fullWidthParams())
        root.addView(space(8))

        prompt = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(textColor)
            setTextSize(18f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(8), dp(8), dp(8), dp(4))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        root.addView(prompt, fullWidthParams())

        feedback = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(successColor)
            setTextSize(13f)
            setPadding(dp(8), 0, dp(8), dp(6))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        root.addView(feedback, fullWidthParams())

        board = ChessBoardView(this).apply {
            setOnSquareTapped { square -> handleSquareTap(square) }
        }
        val boardScroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            contentDescription = "Tabla de șah. Fiecare pătrățel este un buton."
            addView(board, ViewGroup.LayoutParams(dp(384), -2))
        }
        val boardParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) }
        root.addView(boardScroller, boardParams)
        root.addView(space(16))

        answers = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        repeat(4) {
            val answerButton = controlButton("") { }
            answerButton.setTextSize(17f)
            answerButton.contentDescription = "Variantă de răspuns"
            answers.addView(answerButton, LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                marginStart = if (it == 0) 0 else dp(4)
            })
        }
        root.addView(answers, fullWidthParams())
        root.addView(space(12))

        timerLabel = cardText("").apply { gravity = Gravity.CENTER }
        root.addView(timerLabel, fullWidthParams())
        root.addView(space(8))

        bestStreak = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(mutedColor)
            setTextSize(12f)
            setPadding(0, dp(4), 0, dp(4))
        }
        root.addView(bestStreak, fullWidthParams())

        resetButton = controlButton("↻  Resetează scorul") {
            recordSessionSnapshot()
            cancelPendingRoundAdvance()
            cancelTimer()
            game.resetScore()
            feedbackSquare = null
            feedbackCorrect = null
            lessonSquare = null
            timedRemaining = TIMED_SECONDS
            timedFinished = false
            refreshUi()
            if (game.state.mode == TrainerMode.TIMED) startTimer()
        }
        resetButton.contentDescription = "Resetează scorul și începe o rundă nouă"
        root.addView(resetButton, fullWidthParams())

        val help = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(mutedColor)
            setTextSize(11f)
            setPadding(0, dp(10), 0, 0)
            text = "a1–h8 = diagonală neagră   •   a8–h1 = diagonală albă"
        }
        root.addView(help, fullWidthParams())
    }

    private fun buildDashboard(): View {
        val dashboard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val profileRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        dashboardGreeting = TextView(this).apply {
            setTextColor(textColor)
            setTextSize(18f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        profileRow.addView(dashboardGreeting)
        val profileButton = controlButton("Editează") { showProfileDialog() }.apply {
            contentDescription = "Editează porecla"
        }
        profileRow.addView(profileButton, LinearLayout.LayoutParams(dp(104), dp(48)))
        dashboard.addView(profileRow, fullWidthParams())
        dashboard.addView(space(8))

        val firstStats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sessionsStat = statCard("Sesiuni")
        gamesStat = statCard("Jocuri")
        firstStats.addView(sessionsStat, statParams())
        firstStats.addView(gamesStat, statParams())
        dashboard.addView(firstStats, fullWidthParams())
        dashboard.addView(space(8))

        val secondStats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        mistakesStat = statCard("Greșeli")
        globalBestStat = statCard("Cel mai bun streak")
        secondStats.addView(mistakesStat, statParams())
        secondStats.addView(globalBestStat, statParams())
        dashboard.addView(secondStats, fullWidthParams())
        dashboard.addView(space(8))

        historyButton = controlButton("Istoricul sesiunilor") { showHistoryDialog() }.apply {
            contentDescription = "Deschide istoricul sesiunilor și jocurilor"
        }
        dashboard.addView(historyButton, fullWidthParams())
        dashboard.addView(space(8))

        mistakesCard = cardText("")
        dashboard.addView(mistakesCard, fullWidthParams())
        mistakesTrainingButton = controlButton("Antrenează greșelile") {
            game.setHistoricalMistakes(historyStore.aggregateMistakes())
            selectMode(TrainerMode.FIND)
        }.apply {
            contentDescription = "Antrenează coordonatele greșite cel mai des"
        }
        dashboard.addView(mistakesTrainingButton, fullWidthParams())
        dashboard.addView(space(8))

        val learningIntro = cardText(
            "Învață diagonalele\nAtinge pătrățelele pentru a vedea coordonata și culoarea diagonalei.",
        )
        dashboard.addView(learningIntro, fullWidthParams())
        return dashboard
    }

    private fun statCard(label: String): TextView = TextView(this).apply {
        gravity = Gravity.CENTER
        setTextColor(textColor)
        setTextSize(13f)
        setPadding(dp(8), dp(12), dp(8), dp(12))
        minHeight = dp(72)
        text = "$label\n0"
        contentDescription = label
        background = controlBackground(cardColor)
    }

    private fun statParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) }

    private fun buildHeader(): View {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        titleColumn.addView(TextView(this).apply {
            text = "Antrenor Coordonate Șah"
            setTextColor(textColor)
            setTextSize(20f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 2
        })
        titleColumn.addView(TextView(this).apply {
            text = "ÎNVAȚĂ TABLA PE DE ROST • RAPID"
            setTextColor(Color.rgb(130, 130, 130))
            setTextSize(10f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(2), 0, 0)
        })
        header.addView(titleColumn)

        score = metricChip("SCOR")
        streak = metricChip("STREAK")
        header.addView(score, LinearLayout.LayoutParams(dp(72), dp(52)).apply { marginStart = dp(6) })
        header.addView(streak, LinearLayout.LayoutParams(dp(78), dp(52)).apply { marginStart = dp(6) })
        return header
    }

    private fun buildModes(): View {
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            contentDescription = "Moduri de antrenament"
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val modes = listOf(
            TrainerMode.FIND to "Găsește",
            TrainerMode.GUESS to "Ghicește",
            TrainerMode.LEARN to "Învață diagonale",
            TrainerMode.TIMED to "Rapid 30s",
        )
        modes.forEach { (mode, label) ->
            val button = controlButton(label) { selectMode(mode) }.apply {
                contentDescription = "Mod: $label"
            }
            modeButtons[mode] = button
            row.addView(button, LinearLayout.LayoutParams(dp(if (mode == TrainerMode.LEARN) 150 else 112), dp(50)).apply {
                marginEnd = dp(6)
            })
        }
        scroll.addView(row, ViewGroup.LayoutParams(-2, -2))
        return scroll
    }

    /** Entry points for the ported Checkmate content: the lesson book and puzzles. */
    private fun buildContent(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
            background = controlBackground(cardColor)
        }
        card.addView(
            TextView(this).apply {
                text = "Conținut Checkmate"
                setTextColor(textColor)
                setTextSize(13f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(dp(2), 0, dp(2), dp(8))
            },
            fullWidthParams(),
        )
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(controlButton("Lecții (64)") {
            startActivity(Intent(this, LessonActivity::class.java))
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        row.addView(controlButton("Puzzle-uri") {
            startActivity(Intent(this, PuzzleActivity::class.java))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(row, fullWidthParams())
        return card
    }

    /** Entry point for importing and replaying the player's own games. */
    private fun buildGamesCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
            background = controlBackground(cardColor)
        }
        card.addView(
            TextView(this).apply {
                text = "Jocurile mele"
                setTextColor(textColor)
                setTextSize(13f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(dp(2), 0, dp(2), dp(4))
            },
            fullWidthParams(),
        )
        card.addView(
            TextView(this).apply {
                text = "Importă partidele jucate pe Chess.com și Lichess și redă-le pe tablă."
                setTextColor(mutedColor)
                setTextSize(12f)
                setPadding(dp(2), 0, dp(2), dp(8))
            },
            fullWidthParams(),
        )
        card.addView(
            controlButton("Deschide") { startActivity(Intent(this, GamesActivity::class.java)) },
            fullWidthParams(),
        )
        return card
    }

    /** Where the AI analysis endpoint, key and model get entered. */
    private fun buildSettingsCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
            background = controlBackground(cardColor)
        }
        card.addView(
            TextView(this).apply {
                text = "Setări"
                setTextColor(textColor)
                setTextSize(13f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(dp(2), 0, dp(2), dp(4))
            },
            fullWidthParams(),
        )
        card.addView(
            TextView(this).apply {
                text = "Analiză cu AI: alege endpoint, cheie și model compatibile OpenAI."
                setTextColor(mutedColor)
                setTextSize(12f)
                setPadding(dp(2), 0, dp(2), dp(8))
            },
            fullWidthParams(),
        )
        card.addView(
            controlButton("Deschide") { startActivity(Intent(this, SettingsActivity::class.java)) },
            fullWidthParams(),
        )
        return card
    }

    private fun selectMode(mode: TrainerMode) {
        recordSessionSnapshot()
        cancelPendingRoundAdvance()
        cancelTimer()
        timedFinished = false
        timedRemaining = TIMED_SECONDS
        feedbackSquare = null
        feedbackCorrect = null
        lessonSquare = null
        game.setMode(mode)
        refreshUi()
        if (mode == TrainerMode.TIMED) startTimer()
    }

    private fun handleSquareTap(square: Square) {
        if (game.state.mode == TrainerMode.LEARN) {
            lessonSquare = square
            feedbackSquare = null
            feedbackCorrect = null
            refreshUi()
            return
        }
        if (game.state.mode == TrainerMode.TIMED && timedFinished) return
        submit(square)
    }

    private fun submit(square: Square) {
        val submittedRoundId = game.state.roundId
        val result = game.submit(square)
        if (!result.accepted) return
        recordSessionSnapshot()
        feedbackSquare = square
        feedbackCorrect = result.isCorrect
        board.performHapticFeedback(
            if (result.isCorrect) HapticFeedbackConstants.KEYBOARD_TAP else HapticFeedbackConstants.LONG_PRESS,
        )
        refreshUi()
        scheduleNextRound(submittedRoundId)
    }

    private fun startTimer() {
        cancelTimer()
        val generation = timerGeneration
        timer = object : CountDownTimer(timedRemaining * 1_000L, 1_000L) {
            override fun onTick(millisUntilFinished: Long) {
                if (generation != timerGeneration || game.state.mode != TrainerMode.TIMED) return
                timedRemaining = ((millisUntilFinished + 999L) / 1_000L).toInt().coerceIn(0, TIMED_SECONDS)
                refreshUi()
            }

            override fun onFinish() {
                if (generation != timerGeneration || game.state.mode != TrainerMode.TIMED) return
                timedRemaining = 0
                timedFinished = true
                feedbackSquare = null
                feedbackCorrect = null
                board.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                refreshUi()
            }
        }.start()
    }

    private fun cancelTimer() {
        timerGeneration++
        timer?.cancel()
        timer = null
    }

    private fun cancelPendingRoundAdvance() {
        if (::board.isInitialized) pendingRoundAdvance?.let { board.removeCallbacks(it) }
        pendingRoundAdvance = null
    }

    private fun scheduleNextRound(expectedRoundId: Long) {
        cancelPendingRoundAdvance()
        val runnable = Runnable {
            if (game.state.roundId != expectedRoundId) return@Runnable
            if (game.state.acceptingAnswer) return@Runnable
            if (game.state.mode == TrainerMode.TIMED && timedFinished) return@Runnable
            pendingRoundAdvance = null
            feedbackSquare = null
            feedbackCorrect = null
            game.nextRound(expectedRoundId)
            refreshUi()
        }
        pendingRoundAdvance = runnable
        board.postDelayed(runnable, FEEDBACK_DELAY_MS)
    }

    private fun refreshUi() {
        if (!::board.isInitialized) return
        val state = game.state
        score.text = "SCOR\n${state.correct}/${state.total}"
        streak.text = "STREAK\n${state.streak}"
        bestStreak.text = "Cel mai bun streak: ${state.bestStreak}"
        dashboardGreeting.text = "Salut, ${profile.nickname.ifBlank { "Jucător" }}"
        val history = historyStore.load()
        val mistakes = historyStore.aggregateMistakes()
        sessionsStat.text = "Sesiuni\n${history.size}"
        gamesStat.text = "Jocuri\n${history.sumOf { it.total }}"
        mistakesStat.text = "Greșeli\n${mistakes.values.sum()}"
        globalBestStat.text = "Cel mai bun streak\n${history.maxOfOrNull { it.bestStreak } ?: 0}"
        mistakesCard.text = "${topMistakesText(mistakes)}\nNivel adaptiv: ${state.difficultyLevel}/3"

        modeButtons.forEach { (mode, button) ->
            val selected = mode == state.mode
            button.background = controlBackground(if (selected) primaryColor else cardColor)
            button.setTextColor(textColor)
            button.isSelected = selected
            button.contentDescription = "Mod: ${button.text}${if (selected) ", selectat" else ""}"
        }

        diagonalButton.text = if (state.showDiagonals) "✓  Ascunde diagonalele" else "◫  Arată diagonalele"
        diagonalButton.background = controlBackground(if (state.showDiagonals) Color.rgb(238, 238, 210) else cardColor)
        diagonalButton.setTextColor(if (state.showDiagonals) Color.rgb(58, 90, 42) else textColor)

        lessonCard.visibility = if (state.mode == TrainerMode.LEARN) View.VISIBLE else View.GONE
        lessonCard.text = lessonText()
        prompt.text = when (state.mode) {
            TrainerMode.FIND -> "Atinge pătrățelul: ${state.target.coordinate.uppercase()}"
            TrainerMode.GUESS -> "Ce coordonată este marcată?"
            TrainerMode.LEARN -> "Studiază culorile și diagonalele"
            TrainerMode.TIMED -> if (timedFinished) "Timpul s-a terminat" else "Atinge: ${state.target.coordinate.uppercase()}"
        }
        feedback.text = when {
            state.mode == TrainerMode.TIMED && timedFinished -> "Scor final: ${state.correct}/${state.total} • apasă Reset pentru o rundă nouă"
            feedbackCorrect == true -> "Corect!"
            feedbackCorrect == false -> "Nu. Era ${state.target.coordinate.uppercase()}."
            else -> ""
        }
        feedback.setTextColor(if (feedbackCorrect == false) errorColor else successColor)

        board.render(
            mode = state.mode,
            showDiagonals = state.showDiagonals || state.mode == TrainerMode.LEARN,
            target = state.target,
            feedbackSquare = feedbackSquare,
            feedbackCorrect = feedbackCorrect,
        )
        board.isEnabled = state.mode != TrainerMode.TIMED || !timedFinished
        refreshAnswerButtons(state)
        answers.visibility = if (state.mode == TrainerMode.GUESS) View.VISIBLE else View.GONE
        timerLabel.visibility = if (state.mode == TrainerMode.TIMED) View.VISIBLE else View.GONE
        timerLabel.text = if (timedFinished) "Timp: 0 secunde" else "Timp rămas: $timedRemaining secunde"
        learningStartButton.visibility = if (state.mode == TrainerMode.LEARN) View.GONE else View.VISIBLE
    }

    private fun refreshAnswerButtons(state: TrainerState) {
        for (index in 0 until answers.childCount) {
            val button = answers.getChildAt(index) as Button
            val square = state.options.getOrNull(index)
            button.text = square?.coordinate?.uppercase() ?: "—"
            button.visibility = if (square == null) View.GONE else View.VISIBLE
            button.contentDescription = square?.let { "Răspuns ${it.coordinate.uppercase()}" } ?: "Fără variantă"
            button.isEnabled = square != null && state.acceptingAnswer
            button.setOnClickListener { square?.let { submit(it) } }
        }
    }

    private fun lessonText(): String {
        val selected = lessonSquare?.let {
            val diagonal = when {
                DiagonalRules.isDarkDiagonal(it.file, it.rank) -> "diagonala neagră a1–h8"
                DiagonalRules.isLightDiagonal(it.file, it.rank) -> "diagonala albă a8–h1"
                else -> "o pătrățică din afara celor două diagonale"
            }
            "\nSelectat: ${it.coordinate.uppercase()} — $diagonal"
        } ?: ""
        return "a1–h8: diagonală neagră\na1, b2, c3, d4, e5, f6, g7, h8\n\na8–h1: diagonală albă\na8, b7, c6, d5, e4, f3, g2, h1$selected"
    }

    private fun recordSessionSnapshot() {
        if (game.state.total == 0) return
        historyStore.upsert(game.sessionSummary())
        if (profile.difficultyLevel != game.state.difficultyLevel) {
            profile = profile.copy(difficultyLevel = game.state.difficultyLevel)
            profileStore.save(profile)
        }
    }

    private fun topMistakesText(mistakes: Map<Square, Int>): String {
        val top = mistakes.entries.sortedByDescending { it.value }.take(3)
        return if (top.isEmpty()) {
            "Greșelile mele\nÎncă nu ai răspunsuri greșite. Continuă antrenamentul!"
        } else {
            "Greșelile mele\n" + top.joinToString("\n") { "${it.key.coordinate.uppercase()} — ${it.value} greșeli" }
        }
    }

    private fun showProfileDialog() {
        val input = EditText(this).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            hint = "Poreclă"
            setText(profile.nickname)
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle("Porecla ta")
            .setView(input)
            .setNegativeButton("Anulează", null)
            .setPositiveButton("Salvează") { _, _ ->
                val nickname = NicknamePolicy.normalize(input.text.toString())
                if (nickname.isBlank()) {
                    input.error = "Introdu o poreclă"
                    showProfileDialog()
                } else {
                    profile = profileStore.saveNickname(nickname)
                    refreshUi()
                }
            }
            .show()
    }

    private fun showHistoryDialog() {
        val history = historyStore.load()
        val message = if (history.isEmpty()) {
            "Nu există sesiuni salvate încă. Joacă o rundă pentru a începe istoricul."
        } else {
            history.joinToString("\n\n") { historyLine(it) }
        }
        AlertDialog.Builder(this)
            .setTitle("Istoricul sesiunilor")
            .setMessage(message)
            .setNegativeButton("Închide", null)
            .setNeutralButton("Șterge istoricul") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("Ștergi istoricul?")
                    .setMessage("Statisticile și sesiunile salvate vor fi șterse.")
                    .setNegativeButton("Anulează", null)
                    .setPositiveButton("Șterge") { _, _ ->
                        historyStore.clear()
                        refreshUi()
                    }
                    .show()
            }
            .show()
    }

    private fun historyLine(summary: SessionSummary): String {
        val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(summary.updatedAtEpochMs))
        return "$date • ${summary.mode.name}\nScor: ${summary.correct}/${summary.total} • streak: ${summary.bestStreak}\nNivel: ${summary.difficultyLevel}"
    }

    private fun metricChip(label: String): TextView = TextView(this).apply {
        gravity = Gravity.CENTER
        setTextColor(textColor)
        setTextSize(11f)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(dp(4), 0, dp(4), 0)
        text = "$label\n0"
        contentDescription = label
        background = controlBackground(cardColor)
    }

    private fun cardText(value: String): TextView = TextView(this).apply {
        text = value
        setTextColor(textColor)
        setTextSize(13f)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = controlBackground(cardColor)
    }

    private fun controlButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(textColor)
        setTextSize(14f)
        minHeight = dp(48)
        minimumHeight = dp(48)
        minWidth = 0
        setPadding(dp(10), dp(4), dp(10), dp(4))
        background = controlBackground(cardColor)
        setOnClickListener { action() }
    }

    private fun controlBackground(color: Int): RippleDrawable {
        val content = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12).toFloat()
            setColor(color)
        }
        return RippleDrawable(ColorStateList.valueOf(Color.argb(70, 255, 255, 255)), content, null)
    }

    private fun fullWidthParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(-1, -2)

    private fun space(heightDp: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(heightDp))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val TIMED_SECONDS = 30
        const val FEEDBACK_DELAY_MS = 650L
        const val KEY_TIMED_REMAINING = "sah.timed_remaining"
        const val KEY_TIMED_FINISHED = "sah.timed_finished"
    }
}
