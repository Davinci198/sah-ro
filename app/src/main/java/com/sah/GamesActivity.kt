package com.sah

import android.app.AlertDialog
import android.content.ClipboardManager
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
import android.view.Gravity
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
import com.sah.games.ManualImport
import com.sah.games.Platform
import com.sah.games.errorMessage
import com.sah.games.gameCountWord
import com.sah.games.monthKey
import com.sah.games.monthTitle
import com.sah.games.outcomeWord
import java.io.File
import java.io.IOException
import java.util.UUID

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
    private var platformFilter = "all"
    private var yearFilter = "all"
    private val outcomeButtons = LinkedHashMap<String, Button>()
    private val colorButtons = LinkedHashMap<String, Button>()
    private val timeButtons = LinkedHashMap<String, Button>()
    private val platformFilterButtons = LinkedHashMap<String, Button>()
    private val yearButtons = LinkedHashMap<String, Button>()
    private lateinit var yearChipsRow: LinearLayout

    /**
     * Games picked by a long-press, capped at [MAX_SELECTION]; an empty set means
     * the list is idle and a tap opens the replay as it always did.
     */
    private val selection = LinkedHashSet<String>()

    /** Ids already mined into puzzles — these rows carry the green dot. */
    private var analyzed: Set<String> = emptySet()

    private lateinit var selectionBar: LinearLayout
    private lateinit var selectionCount: TextView

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
        // Pasted games have no account behind them, so they stay out of the username row.
        val platforms = Platform.values().filter { it != Platform.MANUAL }
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

        root.addView(button("Adaugă joc lipit (PGN sau FEN)") { showPasteDialog() }, fullWidth())
        root.addView(space(8))

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

        root.addView(label("Platformă", 11f, mutedColor), fullWidth())
        root.addView(
            buildChipRow(
                platformFilterButtons,
                listOf(
                    "all" to "Toate",
                    "chesscom" to "Chess.com",
                    "lichess" to "Lichess",
                    "checkmate" to "Checkmate",
                    "manual" to "Lipite",
                ),
                fill = true,
            ) { platformFilter = it },
            fullWidth(),
        )

        root.addView(label("An", 11f, mutedColor), fullWidth())
        yearChipsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val yearScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(yearChipsRow, LinearLayout.LayoutParams(-2, -2))
        }
        root.addView(yearScroll, fullWidth())

        countLabel = label("", 12f, mutedColor)
        root.addView(countLabel, fullWidth())
        selectionBar = buildSelectionBar()
        root.addView(selectionBar, fullWidth())
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

    /** Rebuilds the year chips from the years actually present in the data. */
    private fun populateYearChips() {
        yearButtons.clear()
        yearChipsRow.removeAllViews()
        val years = refs.map { it.date.take(4) }
            .filter { it.length == 4 && it.all(Char::isDigit) }
            .distinct()
            .sortedDescending()
        val options = listOf("all" to "Toate") + years.map { it to it }
        options.forEach { (key, text) ->
            val chip = button(text) {
                yearFilter = key
                repaintFilters()
                applyFilters()
            }.apply {
                minHeight = dp(38)
                minimumHeight = dp(38)
                contentDescription = text
            }
            yearButtons[key] = chip
            yearChipsRow.addView(chip)
        }
        if (yearFilter !in yearButtons) yearFilter = "all"
        repaintFilters()
    }

    private fun repaintFilters() {
        repaintGroup(outcomeButtons, outcomeFilter)
        repaintGroup(colorButtons, colorFilter)
        repaintGroup(timeButtons, timeFilter)
        repaintGroup(platformFilterButtons, platformFilter)
        repaintGroup(yearButtons, yearFilter)
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

    /** Reads the clipboard's plain text, for the paste dialog to start pre-filled. */
    private fun clipboardText(): String? =
        (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
            ?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.text
            ?.toString()

    /** One dialog for both shapes of hand-paste: a whole PGN or a bare FEN. */
    private fun showPasteDialog() {
        val input = EditText(this).apply {
            setSingleLine(false)
            minLines = 6
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            hint = "Lipește aici PGN-ul jocului sau poziția FEN"
            setHintTextColor(mutedColor)
            setTextColor(textColor)
            setTextSize(13f)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = controlBackground(cardColor, strokeColor = Color.rgb(86, 86, 86))
        }
        clipboardText()?.takeIf { it.isNotBlank() }?.let { input.setText(it) }
        val pasted = clipboardText().orEmpty()

        // The paste itself carries no notion of "me": the player says which side,
        // and the result follows from that answer until they override it.
        var isWhite = true
        var chosenOutcome: String? = ManualImport.outcomeOf(pasted, "white")
        var resultTouched = false
        var repaint: () -> Unit = {}
        val whiteChip = button("Cu albul") {
            isWhite = true
            if (!resultTouched) chosenOutcome = ManualImport.outcomeOf(input.text.toString(), "white")
            repaint()
        }
        val blackChip = button("Cu negrul") {
            isWhite = false
            if (!resultTouched) chosenOutcome = ManualImport.outcomeOf(input.text.toString(), "black")
            repaint()
        }
        repaint = {
            whiteChip.setTextColor(if (isWhite) Color.BLACK else textColor)
            whiteChip.background = controlBackground(if (isWhite) accentColor else cardColor)
            blackChip.setTextColor(if (isWhite) textColor else Color.BLACK)
            blackChip.background = controlBackground(if (isWhite) cardColor else accentColor)
        }
        repaint()
        val colorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(whiteChip, weightParams())
            addView(blackChip, weightParams())
        }

        // Where the game came from: a Checkmate export is not a Lichess game, and
        // Checkmate is the default because that is where these pastes come from.
        var chosenPlatform = Platform.CHECKMATE.name
        val platformRow = choiceRow(
            listOf(
                Platform.CHECKMATE.name to Platform.CHECKMATE.label,
                Platform.CHESSCOM.name to Platform.CHESSCOM.label,
                Platform.LICHESS.name to Platform.LICHESS.label,
            ),
            selected = { chosenPlatform },
        ) { chosenPlatform = it }

        // How it ended. The header is trusted only when it says so: a `*` or a
        // missing Result leaves the answer blank until the player fills it in,
        // rather than filing a won game as a draw.
        val resultRow = choiceRow(
            listOf("W" to "Câștigat", "L" to "Pierdut", "D" to "Egal"),
            selected = { chosenOutcome.orEmpty() },
        ) {
            chosenOutcome = it
            resultTouched = true
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(label("Ai jucat cu…", 12f, mutedColor), fullWidth())
            addView(colorRow, fullWidth())
            addView(space(8))
            addView(label("Jocul vine de la…", 12f, mutedColor), fullWidth())
            addView(platformRow, fullWidth())
            addView(space(8))
            addView(label("S-a terminat cu…", 12f, mutedColor), fullWidth())
            addView(resultRow, fullWidth())
            addView(space(10))
            addView(input, fullWidth())
        }

        AlertDialog.Builder(this)
            .setTitle("Adaugă joc lipit")
            .setView(panel)
            .setNegativeButton("Renunță", null)
            .setPositiveButton("Adaugă") { _, _ ->
                addPastedGame(
                    input.text.toString(),
                    if (isWhite) "white" else "black",
                    Platform.valueOf(chosenPlatform),
                    chosenOutcome,
                )
            }
            .show()
    }

    /**
     * A dialog row of single-choice chips: [options] as key-to-label pairs laid
     * side by side, the one whose key equals [selected] filled in, and every tap
     * reported through [onPick]. The caller owns the state — this only repaints
     * it, the way the colour row above always has.
     */
    private fun choiceRow(
        options: List<Pair<String, String>>,
        selected: () -> String,
        onPick: (String) -> Unit,
    ): LinearLayout {
        val chips = LinkedHashMap<String, Button>()
        var repaint: () -> Unit = {}
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        options.forEach { (key, text) ->
            val chip = button(text) {
                onPick(key)
                repaint()
            }.apply { layoutParams = weightParams() }
            chips[key] = chip
            row.addView(chip)
        }
        repaint = {
            val active = selected()
            chips.forEach { (key, chip) ->
                val on = key == active
                chip.setTextColor(if (on) Color.BLACK else textColor)
                chip.background = controlBackground(if (on) accentColor else cardColor)
            }
        }
        repaint()
        return row
    }

    /**
     * Validates the pasted text off the UI thread, stores it under the platform
     * and outcome the player chose, then refreshes the list.
     */
    private fun addPastedGame(text: String, myColor: String, platform: Platform, outcome: String?) {
        statusLabel.setTextColor(mutedColor)
        statusLabel.text = "Se adaugă jocul lipit…"
        Thread {
            val game = ManualImport.game(
                text,
                myColor,
                UUID.randomUUID().toString(),
                ManualImport.today(),
                platform = platform,
                outcome = outcome,
            )
            if (game != null) runCatching { store.append(platform, game) }
            runOnUiThread {
                if (cancelled || isFinishing) return@runOnUiThread
                if (game == null) {
                    statusLabel.setTextColor(errorColor)
                    statusLabel.text = "Textul nu conține nici o poziție validă și nici mutări de șah."
                } else {
                    statusLabel.setTextColor(mutedColor)
                    statusLabel.text =
                        "Joc adăugat la ${platform.label}: ${game.opponent} (${outcomeWord(game.outcome)})."
                    reloadList()
                }
            }
        }.start()
    }

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

    /** Analyses every game still awaiting it, or only [ids] when a selection named them. */
    private fun startGenerate(ids: Set<String>? = null) {
        if (importing || generating) return
        generating = true
        generateButton.isEnabled = false
        importButton.isEnabled = false
        progressLabel.setTextColor(mutedColor)
        progressLabel.text = "Se pregătește analiza jocurilor…"
        Thread {
            val result = runCatching {
                generatePuzzles(ids) { message ->
                    runOnUiThread { if (!cancelled && !isFinishing) progressLabel.text = message }
                }
            }
            runOnUiThread {
                if (cancelled || isFinishing) return@runOnUiThread
                generating = false
                generateButton.isEnabled = true
                importButton.isEnabled = true
                val total = runCatching { generatedStore.load().size }.getOrDefault(0)
                if (result.isSuccess) {
                    val (analyzedCount, found) = result.getOrThrow()
                    progressLabel.setTextColor(successColor)
                    progressLabel.text =
                        "Gata: $found puzzle-uri din $analyzedCount jocuri analizate · $total în total. " +
                            "Mergi la „Puzzle-uri” și alege „Jocurile tale”."
                    if (ids != null) clearSelection()
                    // The fresh green dots come from this run's marks.
                    reloadList()
                } else {
                    progressLabel.setTextColor(errorColor)
                    progressLabel.text = "Analiza a eșuat: ${result.exceptionOrNull()?.message}"
                }
            }
        }.start()
    }

    /**
     * Mines games for the player's mistakes and appends the puzzles they yield.
     * Without [ids] each run walks one batch further back through history
     * (analyzed ids are skipped), so repeated taps grow the set gradually; with
     * [ids] only those games are considered, which is what analysing a selection
     * means. Either way an already-mined game is left alone, or its puzzles would
     * be appended a second time.
     */
    private fun generatePuzzles(ids: Set<String>?, report: (String) -> Unit): Pair<Int, Int> {
        val book = OpeningRepository.parse(readAsset("openings/openings.csv"))
        val mined = generatedStore.analyzedIds()
        val pending = store.loadAll()
            .filter { it.id !in mined && (ids == null || it.id in ids) }
            .sortedWith(compareByDescending { it.date })
            .let { if (ids == null) it.take(BATCH_PER_RUN) else it }
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
            val scanned = runCatching { generatedStore.analyzedIds() }.getOrDefault(emptySet())
            runOnUiThread {
                if (cancelled || isFinishing) return@runOnUiThread
                refs = loaded
                analyzed = scanned
                // A deleted or reloaded game cannot stay picked.
                selection.retainAll(loaded.mapTo(HashSet()) { it.id })
                renderSelectionBar()
                statusLabel.text = if (loaded.isEmpty()) "" else "${loaded.size} jocuri salvate"
                populateTimeChips()
                populateYearChips()
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
                (platformFilter == "all" || ref.platform.equals(platformFilter, ignoreCase = true)) &&
                (yearFilter == "all" || ref.date.take(4) == yearFilter) &&
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
                "Nu ai jocuri importate încă. Introdu username-ul de pe Chess.com sau Lichess și " +
                    "apasă „Importă toate jocurile”, sau lipește un joc PGN — exportat din Checkmate " +
                    "sau de oriunde altundeva — cu „Adaugă joc lipit”."
            } else {
                "Niciun joc nu corespunde filtrelor."
            }
            listContainer.addView(card(message), fullWidth())
            return
        }
        // One header per month, showing that month's true total across the whole
        // filtered set (not just the rendered slice), so the list reads as a timeline.
        val monthCounts = LinkedHashMap<String, Int>()
        for (ref in filtered) monthCounts.merge(monthKey(ref.date), 1, Int::plus)
        var lastKey: String? = null
        for (ref in filtered.take(visibleCount)) {
            val key = monthKey(ref.date)
            if (key != lastKey) {
                listContainer.addView(monthHeader(key, monthCounts[key] ?: 1), fullWidth())
                lastKey = key
            }
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

    private fun monthHeader(key: String, count: Int): View {
        return TextView(this).apply {
            text = "${monthTitle(key)} · ${gameCountWord(count)}"
            setTextColor(accentColor)
            setTextSize(13f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.START
            setPadding(dp(4), dp(14), dp(4), dp(4))
        }
    }

    private fun gameRow(ref: GameRef): View {
        val picked = selection.contains(ref.id)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = controlBackground(
                if (picked) accentColor else cardColor,
                strokeColor = Color.rgb(86, 86, 86),
            )
            isClickable = true
            isFocusable = true
            // A long-press starts a selection; once one exists a tap joins or
            // leaves it, and only an idle tap opens the replay as it always did.
            setOnClickListener {
                if (selection.isEmpty()) openReplay(ref) else toggleSelection(ref)
            }
            setOnLongClickListener {
                toggleSelection(ref)
                true
            }
            contentDescription = buildString {
                if (analyzed.contains(ref.id)) append("Scanat, ")
                append(outcomeWord(ref.outcome))
                append(" contra ")
                append(ref.opponent)
                append(", ")
                append(ref.date)
                if (picked) append(", selectat")
                append(". ")
                append(if (selection.isEmpty()) "Deschide redarea jocului." else "Comută selecția.")
            }
        }
        val title = SpannableStringBuilder()
        if (analyzed.contains(ref.id)) {
            // The green dot: this game has already been mined for puzzles.
            title.append("● ")
            title.setSpan(
                ForegroundColorSpan(if (picked) Color.rgb(20, 120, 60) else successColor),
                0,
                title.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        title.append(outcomeWord(ref.outcome))
        if (!picked) {
            title.setSpan(
                ForegroundColorSpan(outcomeColor(ref.outcome)),
                title.length - outcomeWord(ref.outcome).length,
                title.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        title.append(" vs ${ref.opponent}")
        ref.opponentRating?.let { title.append(" ($it)") }
        row.addView(TextView(this).apply {
            text = title
            setTextColor(if (picked) Color.BLACK else textColor)
            setTextSize(14f)
            setTypeface(typeface, Typeface.BOLD)
        }, fullWidth())
        val platformName = platformDisplayName(ref.platform)
        val rest = listOf(
            ref.date,
            colorWord(ref),
            ref.timeClass,
            ref.timeControl,
            ref.opening,
        ).filter { it.isNotBlank() }.joinToString(" · ")
        val detailText = if (rest.isEmpty()) platformName else "$platformName · $rest"
        val detail = SpannableStringBuilder(detailText)
        if (!picked) {
            detail.setSpan(
                ForegroundColorSpan(platformColor(ref.platform)),
                0,
                platformName.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        row.addView(TextView(this).apply {
            text = detail
            setTextColor(if (picked) Color.BLACK else mutedColor)
            setTextSize(12f)
            setPadding(0, dp(2), 0, 0)
        }, fullWidth())
        return row
    }

    private fun platformDisplayName(platform: String): String = when (platform.lowercase()) {
        "chesscom" -> Platform.CHESSCOM.label
        "lichess" -> Platform.LICHESS.label
        "checkmate" -> Platform.CHECKMATE.label
        else -> Platform.MANUAL.label
    }

    private fun platformColor(platform: String): Int = when (platform.lowercase()) {
        "chesscom" -> Color.rgb(120, 170, 110)
        "lichess" -> Color.rgb(160, 150, 220)
        "checkmate" -> Color.rgb(220, 170, 120)
        else -> Color.rgb(150, 150, 150)
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
                    putExtra(GameReplayActivity.EXTRA_GAME_ID, ref.id)
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

    /** The bar that appears while games are picked: the count, then what to do. */
    private fun buildSelectionBar(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = controlBackground(cardColor, strokeColor = Color.rgb(86, 86, 86))
            visibility = View.GONE
            selectionCount = label("", 12f, accentColor, bold = true).apply { gravity = Gravity.START }
            addView(selectionCount, LinearLayout.LayoutParams(0, -2, 1f))
            addView(button("Șterge") { deleteSelection() }.apply { layoutParams = weightParams() })
            addView(button("Analizează") { analyzeSelection() }.apply { layoutParams = weightParams() })
            addView(button("Renunță") { clearSelection() }.apply { layoutParams = weightParams() })
        }
    }

    private fun renderSelectionBar() {
        selectionBar.visibility = if (selection.isEmpty()) View.GONE else View.VISIBLE
        if (selection.isNotEmpty()) {
            selectionCount.text =
                if (selection.size == 1) "1 joc ales" else "${selection.size} jocuri alese"
        }
    }

    /** Adds or drops one game; the [MAX_SELECTION]th is the last that fits. */
    private fun toggleSelection(ref: GameRef) {
        if (!selection.remove(ref.id)) {
            if (selection.size >= MAX_SELECTION) {
                statusLabel.setTextColor(errorColor)
                statusLabel.text = "Poți alege cel mult $MAX_SELECTION de jocuri odată."
                return
            }
            selection.add(ref.id)
        }
        renderSelectionBar()
        renderList()
    }

    private fun clearSelection() {
        selection.clear()
        renderSelectionBar()
        renderList()
    }

    /** Drops the chosen games from storage, then re-reads what is left. */
    private fun deleteSelection() {
        val ids = HashSet(selection)
        if (ids.isEmpty()) return
        clearSelection()
        Thread {
            val removed = runCatching { store.remove(ids) }.getOrDefault(0)
            runOnUiThread {
                if (cancelled || isFinishing) return@runOnUiThread
                statusLabel.setTextColor(mutedColor)
                statusLabel.text =
                    if (removed == ids.size) {
                        "Au fost șterse $removed jocuri."
                    } else {
                        "Au fost șterse $removed din ${ids.size} jocuri."
                    }
                reloadList()
            }
        }.start()
    }

    /** Mines only the chosen games; the already-mined are skipped, as ever. */
    private fun analyzeSelection() {
        if (selection.isEmpty()) return
        startGenerate(HashSet(selection))
    }

    private companion object {
        const val PREFS = "games"
        const val PAGE_SIZE = 150
        const val KEY_OPENINGS_BACKFILLED = "openingsBackfilled"

        /** Games mined per generation run; each tap walks one batch further back. */
        const val BATCH_PER_RUN = 30

        /** Long-press picks at most this many games before the bar has to be used. */
        const val MAX_SELECTION = 20
    }
}
