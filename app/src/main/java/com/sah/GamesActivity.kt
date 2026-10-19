package com.sah

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.sah.games.GamesImporter
import com.sah.games.GamesStore
import com.sah.games.ImportSummary
import com.sah.games.ImportedGame
import com.sah.games.HttpStatusError
import com.sah.games.Platform
import com.sah.games.errorMessage
import com.sah.games.outcomeWord
import java.io.File
import java.io.IOException

/**
 * Import screen: a username plus platform choice downloads every played game
 * into local storage, newest archives first, then lists them for the replay
 * screen. Partial imports keep what they managed to download.
 */
class GamesActivity : UiActivity() {

    private lateinit var store: GamesStore
    private lateinit var usernameInput: EditText
    private lateinit var progressLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var importButton: Button
    private lateinit var listContainer: LinearLayout
    private val platformButtons = LinkedHashMap<Platform, Button>()
    private var platform: Platform = Platform.CHESSCOM
    private var importing = false

    @Volatile
    private var cancelled = false

    private var games: List<ImportedGame> = emptyList()
    private var visibleCount = PAGE_SIZE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = GamesStore(File(filesDir, "games"))
        buildUi()
        pickPlatform(Platform.CHESSCOM)
        reloadList()
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

        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer, fullWidth())
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
        if (importing) return
        val username = usernameInput.text.toString().trim()
        if (username.isEmpty()) {
            progressLabel.setTextColor(errorColor)
            progressLabel.text = "Introdu username-ul tău de pe ${platform.label}."
            return
        }
        preferences().edit().putString(keyFor(platform), username).apply()
        importing = true
        importButton.isEnabled = false
        progressLabel.setTextColor(mutedColor)
        progressLabel.text = "Se pornește importul…"
        val chosen = platform
        Thread {
            val result = runCatching {
                GamesImporter(store).import(
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

    private fun reloadList() {
        Thread {
            val loaded = runCatching { store.loadAll().sortedWith(compareByDescending { it.date }) }
                .getOrDefault(emptyList())
            runOnUiThread {
                if (cancelled || isFinishing) return@runOnUiThread
                games = loaded
                statusLabel.text = if (loaded.isEmpty()) "" else "${loaded.size} jocuri salvate"
                visibleCount = PAGE_SIZE
                renderList()
            }
        }.start()
    }

    private fun renderList() {
        listContainer.removeAllViews()
        if (games.isEmpty()) {
            listContainer.addView(
                card("Nu ai jocuri importate încă. Introdu username-ul de pe Chess.com sau Lichess și apasă „Importă toate jocurile”."),
                fullWidth(),
            )
            return
        }
        for (game in games.take(visibleCount)) {
            listContainer.addView(gameRow(game), fullWidth())
            listContainer.addView(space(6))
        }
        if (games.size > visibleCount) {
            val remaining = games.size - visibleCount
            listContainer.addView(button("Mai multe ($remaining rămân)") {
                visibleCount += PAGE_SIZE
                renderList()
            }, fullWidth())
        }
    }

    private fun gameRow(game: ImportedGame): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = controlBackground(cardColor, strokeColor = Color.rgb(86, 86, 86))
            isClickable = true
            isFocusable = true
            setOnClickListener { openReplay(game) }
            contentDescription = buildString {
                append(outcomeWord(game.outcome))
                append(" contra ")
                append(game.opponent)
                append(", ")
                append(game.date)
                append(". Deschide redarea jocului.")
            }
        }
        val title = SpannableStringBuilder()
        val outcome = outcomeWord(game.outcome)
        title.append(outcome)
        title.setSpan(
            ForegroundColorSpan(outcomeColor(game.outcome)),
            0,
            title.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        title.append(" vs ${game.opponent}")
        game.opponentRating?.let { title.append(" ($it)") }
        row.addView(TextView(this).apply {
            text = title
            setTextColor(textColor)
            setTextSize(14f)
            setTypeface(typeface, Typeface.BOLD)
        }, fullWidth())
        val detail = listOf(
            game.date,
            colorWord(game),
            game.timeClass,
            game.timeControl,
            game.opening,
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

    private fun colorWord(game: ImportedGame): String =
        if (game.myColor == "white") "cu albele" else "cu negrele"

    private fun openReplay(game: ImportedGame) {
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
        })
    }

    private companion object {
        const val PREFS = "games"
        const val PAGE_SIZE = 150
    }
}
