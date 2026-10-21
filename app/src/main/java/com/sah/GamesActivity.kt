package com.sah

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.sah.analysis.MistakePuzzles
import com.sah.content.GeneratedPuzzlesStore
import com.sah.content.OpeningRepository
import com.sah.games.GameRef
import com.sah.games.GamesImporter
import com.sah.games.GamesIndex
import com.sah.games.GamesStore
import com.sah.games.ImportSummary
import com.sah.games.HttpStatusError
import com.sah.games.Platform
import com.sah.games.errorMessage
import com.sah.games.outcomeWord
import java.io.File
import java.io.IOException

/**
 * Import screen: a username plus platform choice downloads every played game into
 * local storage, newest archives first. The list below is filterable — by opponent
 * search, result, colour and time control — over a compact in-memory index that
 * holds each game's metadata but not its PGN, so thirty thousand games stay a few
 * megabytes instead of a hundred. Tapping a row fetches that one game's PGN on
 * demand for the replay screen.
 */
class GamesActivity : UiActivity() {

    private lateinit var store: GamesStore
    private lateinit var generatedStore: GeneratedPuzzlesStore
    private lateinit var usernameInput: EditText
    private lateinit var progressLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var importButton: Button
    private lateinit var generateButton: Button
    private lateinit var searchInput: EditText
    private lateinit var countLabel: TextView
    private lateinit var timeChipsRow: LinearLayout
    private lateinit var listContainer: LinearLayout
    private val platformButtons = LinkedHashMap<Platform, Button>()
    private var platform: Platform = Platform.CHESSCOM
    private var importing = false
    private var generating = false

    @Volatile
    private var cancelled = false

    /** Every stored game's metadata, loaded without PGNs; [filtered] is what the list shows. */
    private var refs: List<GameRef> = emptyList()
    private var filtered: List<GameRef> = emptyList()
    private var visibleCount = PAGE_SIZE

    private var outcomeFilter = "all"
    private var colorFilter = "all"
    private var timeFilter = "all"
    private val outcomeButtons = LinkedHashMap<String, Button>()
    private val colorButtons = LinkedHashMap<String, Button>()
    private val timeButtons = LinkedHashMap<String, Button>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = GamesStore(File(filesDir, "games"))
        generatedStore = GeneratedPuzzlesStore(File(filesDir, "generated-puzzles"))
        buildUi()
        pickPlatform(Platform.CHESSCOM)
        reloadList()
        maybeBackfillOpenings()
    }

    /** The opening book, parsed once from assets on whichever thread first needs it. */
    private val openingBook: com.sah.content.OpeningBook? by lazy {
        runCatching { OpeningRepository.parse(readAsset("openings/openings.csv")) }.getOrNull()
    }

    override fun onDestroy() {
        cancelled = true
        super.onDestroy()
    }

    private fun buildUi() {
        val root = column()
        root.addView(header(), fullWidth())

        root.addView(label("Username", 12f, mutedColor), fullWidth())
        usernameInput = EditText(this).apply {
            hint = "ex: hikaru"
            setHintTextColor(mutedColor)
            setTextColor(textColor)
            setTextSize(14f)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = controlBackground(cardColor, strokeColor = Color.rgb(86, 86, 86))
        }
        root.addView(usernameInput, fullWidth())
        root.addView(space(8))

        val toggle = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val platforms = Platform.values()
        platforms.forEachIndexed { index, option ->
            val chip = button(option.label) { pickPlatform(option) }.apply {
                if (index < platforms.size - 1) layoutParams = weightParams()
            }
            platformButtons[option] = chip
            toggle.addView(chip)
        }
        root.addView(toggle, fullWidth())
        root.addView(space(8))

        importButton = button("Importă toate jocurile") { startImport() }
        root.addView(importButton, fullWidth())
        progressLabel = label("", 12f, mutedColor)
        root.addView(progressLabel, fullWidth())
        statusLabel = label("", 12f, mutedColor)
        root.addView(statusLabel, fullWidth())
        root.addView(space(4))

        generateButton = button("Extrage puzzle-uri din jocurile tale") { startGenerate() }
        root.addView(generateButton, fullWidth())
        root.addView(space(10))

        // --- filters ---------------------------------------------------------
        searchInput = EditText(this).apply {
            hint = "Caută adversar…"
            setHintTextColor(mutedColor)
            setTextColor(textColor)
            setTextSize(13f)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = controlBackground(cardColor, strokeColor = Color.rgb(86, 86, 86))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) = applyFilters()
            })
        }
        root.addView(searchInput, fullWidth())

        root.addView(label("Rezultat", 11f, mutedColor), fullWidth())
        root.addView(
            buildChipRow(
                outcomeButtons,
                listOf("all" to "Toate", "W" to "V", "L" to "Î", "D" to "Egal"),
                fill = true,
            ) { outcomeFilter = it },
            fullWidth(),
        )

        root.addView(label("Culoare", 11f, mutedColor), fullWidth())
        root.addView(
            buildChipRow(
                colorButtons,
                listOf("all" to "Toate", "white" to "Albe", "black" to "Negre"),
                fill = true,
            ) { colorFilter = it },
            fullWidth(),
        )

        root.addView(label("Timp", 11f, mutedColor), fullWidth())
        timeChipsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val timeScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(timeChipsRow, LinearLayout.LayoutParams(-2, -2))
        }
        root.addView(timeScroll, fullWidth())

        countLabel = label("", 12f, mutedColor)
        root.addView(countLabel, fullWidth())
        root.addView(space(4))

        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer, fullWidth())
    }

    /** Builds a horizontal row of toggle chips; [fill] spreads them across the width. */
    private fun buildChipRow(
        buttons: MutableMap<String, Button>,
        options: List<Pair<String, String>>,
        fill: Boolean,
        onPick: (String) -> Unit,
    ): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        options.forEachIndexed { index, (key, text) ->
            val chip = button(text) {
                onPick(key)
                repaintFilters()
                applyFilters()
            }.apply {
                minHeight = dp(38)
                minimumHeight = dp(38)
                contentDescription = text
                if (fill && index < options.size - 1) layoutParams = weightParams()
            }
            buttons[key] = chip
            row.addView(chip)
        }
        return row
    }

    /** Rebuilds the time-control chips from the classes actually present in the data. */
    private fun populateTimeChips() {
        timeButtons.clear()
        timeChipsRow.removeAllViews()
        val classes = refs.map { it.timeClass }.filter { it.isNotBlank() }.distinct().sorted()
        val options = listOf("all" to "Toate") + classes.map { it to it.replaceFirstChar { c -> c.uppercase() } }
        options.forEach { (key, text) ->
            val chip = button(text) {
                timeFilter = key
                repaintFilters()
                applyFilters()
            }.apply {
                minHeight = dp(38)
                minimumHeight = dp(38)
                contentDescription = text
            }
            timeButtons[key] = chip
            timeChipsRow.addView(chip)
        }
        if (timeFilter !in timeButtons) timeFilter = "all"
        repaintFilters()
    }

    private fun repaintFilters() {
        repaintGroup(outcomeButtons, outcomeFilter)
        repaintGroup(colorButtons, colorFilter)
        repaintGroup(timeButtons, timeFilter)
    }

    private fun repaintGroup(buttons: Map<String, Button>, selected: String) {
        buttons.forEach { (key, chip) ->
            val active = key == selected
            chip.setTextColor(if (active) Color.BLACK else textColor)
            chip.background = controlBackground(if (active) accentColor else cardColor)
        }
    }

    private fun header(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val back = button("‹ Meniu") { finish() }
        row.addView(back, LinearLayout.LayoutParams(dp(110), dp(48)))
        row.addView(label("Jocurile mele", 18f, textColor, bold = true), weightParams())
        return row
    }

    private fun weightParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) }

    private fun pickPlatform(option: Platform) {
        platform = option
        platformButtons.forEach { (key, chip) ->
            chip.background = controlBackground(if (key == option) primaryColor else cardColor)
        }
        val saved = preferences().getString(keyFor(option), "")
        if (!saved.isNullOrBlank() && usernameInput.text.isNullOrBlank()) {
            usernameInput.setText(saved)
        }
    }

    private fun preferences(): android.content.SharedPreferences =
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun keyFor(option: Platform) = "user.${option.fileName}"

    private fun startImport() {
        if (importing || generating) return
        val username = usernameInput.text.toString().trim()
        if (username.isEmpty()) {
            progressLabel.setTextColor(errorColor)
            progressLabel.text = "Introdu username-ul tău de pe ${platform.label}."
            return
        }
        preferences().edit().putString(keyFor(platform), username).apply()
        importing = true
        importButton.isEnabled = false
        generateButton.isEnabled = false
        progressLabel.setTextColor(mutedColor)
        progressLabel.text = "Se pornește importul…"
        val chosen = platform
        Thread {
            val result = runCatching {
                GamesImporter(store, openingBook).import(
                    chosen,
                    username,
                    { message -> runOnUiThread { if (!cancelled && !isFinishing) progressLabel.text = message } },
                    { cancelled },
                )
            }
            runOnUiThread {
                if (cancelled || isFinishing) return@runOnUiThread
                importing = false
                importButton.isEnabled = true
                generateButton.isEnabled = true
                reportResult(result, username, chosen)
                reloadList()
            }
        }.start()
    }

    private fun reportResult(
        result: Result<ImportSummary>,
        username: String,
        chosen: Platform,
    ) {
        when {
            result.isSuccess -> {
                val summary = result.getOrThrow()
                progressLabel.setTextColor(successColor)
                progressLabel.text = "Gata: ${summary.added} jocuri noi · ${summary.total} în total."
            }
            result.exceptionOrNull() is HttpStatusError -> {
                val error = result.exceptionOrNull() as HttpStatusError
                progressLabel.setTextColor(errorColor)
                progressLabel.text = errorMessage(error.code, username, chosen)
            }
            result.exceptionOrNull() is IOException -> {
                progressLabel.setTextColor(errorColor)
                progressLabel.text = "Nu am putut ajunge la server. Verifică conexiunea la internet."
            }
            else -> {
                progressLabel.setTextColor(errorColor)
                progressLabel.text = result.exceptionOrNull()?.message ?: "Importul a eșuat."
            }
        }
    }

    private fun startGenerate() {
        if (importing || generating) return
        generating = true
        generateButton.isEnabled = false
        importButton.isEnabled = false
        progressLabel.setTextColor(mutedColor)
        progressLabel.text = "Se pregătește analiza jocurilor…"
        Thread {
            val result = runCatching { generatePuzzles { message -> runOnUiThread { if (!cancelled && !isFinishing) progressLabel.text = message } } }
            runOnUiThread {
                if (cancelled || isFinishing) return@runOnUiThread
                generating = false
                generateButton.isEnabled = true
                importButton.isEnabled = true
                val total = runCatching { generatedStore.load().size }.getOrDefault(0)
                if (result.isSuccess) {
                    val (analyzed, found) = result.getOrThrow()
                    progressLabel.setTextColor(successColor)
                    progressLabel.text =
                        "Gata: $found puzzle-uri din $analyzed jocuri analizate · $total în total. " +
                            "Mergi la „Puzzle-uri” și alege „Jocurile tale”."
                } else {
                    progressLabel.setTextColor(errorColor)
                    progressLabel.text = "Analiza a eșuat: ${result.exceptionOrNull()?.message}"
                }
            }
        }.start()
    }

    /**
     * Mines the most recent not-yet-analyzed games for the player's mistakes and
     * appends the puzzles they yield. Each run walks one batch further back through
     * history (analyzed ids are skipped), so repeated taps grow the set gradually.
     */
    private fun generatePuzzles(report: (String) -> Unit): Pair<Int, Int> {
        val book = OpeningRepository.parse(readAsset("openings/openings.csv"))
        val analyzed = generatedStore.analyzedIds()
        val pending = store.loadAll()
            .filter { it.id !in analyzed }
            .sortedWith(compareByDescending { it.date })
            .take(BATCH_PER_RUN)
        var found = 0
        pending.forEachIndexed { index, game ->
            report("Se analizează jocul ${index + 1}/${pending.size} · $found puzzle-uri…")
            val puzzles = MistakePuzzles.extract(game, book)
            generatedStore.append(puzzles)
            generatedStore.markAnalyzed(listOf(game.id))
            found += puzzles.size
        }
        return pending.size to found
    }

    private fun reloadList() {
        Thread {
            val loaded = runCatching { GamesIndex(store).load().sortedWith(compareByDescending { it.date }) }
                .getOrDefault(emptyList())
            runOnUiThread {
                if (cancelled || isFinishing) return@runOnUiThread
                refs = loaded
                statusLabel.text = if (loaded.isEmpty()) "" else "${loaded.size} jocuri salvate"
                populateTimeChips()
                applyFilters()
            }
        }.start()
    }

    /**
     * Chess.com games arrive without an opening name, so the first time the list
     * is opened after this update every stored game missing one gets it detected
     * from its moves. Runs once (guarded by a preference), off the UI thread, and
     * re-reads the list afterwards so the freshly filled openings show up.
     */
    private fun maybeBackfillOpenings() {
        if (preferences().getBoolean(KEY_OPENINGS_BACKFILLED, false)) return
        Thread {
            val filled = runCatching {
                store.backfillOpenings(
                    openingBook,
                    onProgress = { message -> runOnUiThread { if (!cancelled && !isFinishing) statusLabel.text = message } },
                )
            }.getOrDefault(0)
            runOnUiThread {
                if (cancelled || isFinishing) return@runOnUiThread
                preferences().edit().putBoolean(KEY_OPENINGS_BACKFILLED, true).apply()
                if (filled > 0) reloadList()
            }
        }.start()
    }

    /** Narrows [refs] by the current search and chip filters, then re-renders the list. */
    private fun applyFilters() {
        val query = searchInput.text.toString().trim().lowercase()
        filtered = refs.filter { ref ->
            (outcomeFilter == "all" || ref.outcome == outcomeFilter) &&
                (colorFilter == "all" || ref.myColor == colorFilter) &&
                (timeFilter == "all" || ref.timeClass.equals(timeFilter, ignoreCase = true)) &&
                (query.isEmpty() || ref.opponent.lowercase().contains(query))
        }
        countLabel.text = "${filtered.size} din ${refs.size} jocuri"
        visibleCount = PAGE_SIZE
        renderList()
    }

    private fun renderList() {
        listContainer.removeAllViews()
        if (filtered.isEmpty()) {
            val message = if (refs.isEmpty()) {
                "Nu ai jocuri importate încă. Introdu username-ul de pe Chess.com sau Lichess și apasă „Importă toate jocurile”."
            } else {
                "Niciun joc nu corespunde filtrelor."
            }
            listContainer.addView(card(message), fullWidth())
            return
        }
        for (ref in filtered.take(visibleCount)) {
            listContainer.addView(gameRow(ref), fullWidth())
            listContainer.addView(space(6))
        }
        if (filtered.size > visibleCount) {
            val remaining = filtered.size - visibleCount
            listContainer.addView(button("Mai multe ($remaining rămân)") {
                visibleCount += PAGE_SIZE
                renderList()
            }, fullWidth())
        }
    }

    private fun gameRow(ref: GameRef): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = controlBackground(cardColor, strokeColor = Color.rgb(86, 86, 86))
            isClickable = true
            isFocusable = true
            setOnClickListener { openReplay(ref) }
            contentDescription = buildString {
                append(outcomeWord(ref.outcome))
                append(" contra ")
                append(ref.opponent)
                append(", ")
                append(ref.date)
                append(". Deschide redarea jocului.")
            }
        }
        val title = SpannableStringBuilder()
        title.append(outcomeWord(ref.outcome))
        title.setSpan(
            ForegroundColorSpan(outcomeColor(ref.outcome)),
            0,
            title.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        title.append(" vs ${ref.opponent}")
        ref.opponentRating?.let { title.append(" ($it)") }
        row.addView(TextView(this).apply {
            text = title
            setTextColor(textColor)
            setTextSize(14f)
            setTypeface(typeface, Typeface.BOLD)
        }, fullWidth())
        val detail = listOf(
            ref.date,
            colorWord(ref),
            ref.timeClass,
            ref.timeControl,
            ref.opening,
        ).filter { it.isNotBlank() }.joinToString(" · ")
        row.addView(TextView(this).apply {
            text = detail
            setTextColor(mutedColor)
            setTextSize(12f)
            setPadding(0, dp(2), 0, 0)
        }, fullWidth())
        return row
    }

    private fun outcomeColor(outcome: String): Int = when (outcome) {
        "W" -> successColor
        "L" -> errorColor
        else -> mutedColor
    }

    private fun colorWord(ref: GameRef): String =
        if (ref.myColor == "white") "cu albele" else "cu negrele"

    /** Fetches the one game's PGN on a background thread, then opens the replay. */
    private fun openReplay(ref: GameRef) {
        Thread {
            val game = runCatching { store.findById(ref.id) }.getOrNull()
            runOnUiThread {
                if (cancelled || isFinishing) return@runOnUiThread
                if (game == null) {
                    progressLabel.setTextColor(errorColor)
                    progressLabel.text = "Jocul nu a putut fi încărcat."
                    return@runOnUiThread
                }
                startActivity(Intent(this, GameReplayActivity::class.java).apply {
                    putExtra(GameReplayActivity.EXTRA_PGN, game.pgn)
                    putExtra(GameReplayActivity.EXTRA_OPPONENT, game.opponent)
                    putExtra(GameReplayActivity.EXTRA_DATE, game.date)
                    putExtra(
                        GameReplayActivity.EXTRA_TIME,
                        listOf(game.timeClass, game.timeControl, game.opening)
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
                    )
                    putExtra(GameReplayActivity.EXTRA_OUTCOME, game.outcome)
                    putExtra(GameReplayActivity.EXTRA_MY_COLOR, game.myColor)
                })
            }
        }.start()
    }

    private companion object {
        const val PREFS = "games"
        const val PAGE_SIZE = 150
        const val KEY_OPENINGS_BACKFILLED = "openingsBackfilled"

        /** Games mined per generation run; each tap walks one batch further back. */
        const val BATCH_PER_RUN = 30
    }
}
