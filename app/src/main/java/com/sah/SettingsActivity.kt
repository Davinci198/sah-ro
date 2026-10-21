package com.sah

import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.sah.analysis.AiAnalyzer
import com.sah.analysis.AiSettings

/** Shared with the replay screen so `adb logcat -s SahAi` reads every step. */
private const val TAG = "SahAi"

/**
 * Sets up the AI analysis: the OpenAI-compatible endpoint, its key and the model
 * to ask for. Saving here is what makes "Analizează jocul" call that model —
 * with these three fields empty nothing changes and the app keeps analysing
 * offline with its own engine, so this screen can never break a game report.
 */
class SettingsActivity : UiActivity() {

    private lateinit var endpointField: EditText
    private lateinit var keyField: EditText
    private lateinit var modelField: EditText
    private lateinit var testButton: Button
    private lateinit var statusLabel: TextView
    private var cancelled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        val saved = AiSettings.load(this)
        endpointField.setText(saved.endpoint)
        keyField.setText(saved.apiKey)
        modelField.setText(saved.model)
        status(
            if (saved.isConfigured) "Analiza AI e activă pentru ${saved.model}."
            else "Analiza AI nu e configurată — se folosește analiza locală.",
            mutedColor,
        )
    }

    override fun onDestroy() {
        cancelled = true
        super.onDestroy()
    }

    private fun buildUi() {
        val root = column()
        root.addView(label("Setări analiză AI", 16f, accentColor, bold = true), fullWidth())
        root.addView(
            label(
                "Un API compatibil OpenAI: OpenAI, OpenRouter, Groq, DeepSeek sau un server " +
                    "local din rețea (Ollama, LM Studio).",
                12f,
                mutedColor,
            ),
            fullWidth(),
        )
        root.addView(space(10))

        root.addView(caption("Endpoint"), fullWidth())
        endpointField = field("https://api.openai.com/v1")
        root.addView(endpointField, fullWidth())

        root.addView(space(8))
        root.addView(caption("Cheie API (opțională la serverele locale)"), fullWidth())
        keyField = field("sk-…", password = true)
        root.addView(keyField, fullWidth())

        root.addView(space(8))
        root.addView(caption("Model"), fullWidth())
        modelField = field("ex: gpt-4o-mini")
        root.addView(modelField, fullWidth())

        root.addView(space(12))
        testButton = button("Testează conexiunea") { testConnection() }
        root.addView(testButton, fullWidth())
        root.addView(space(6))
        root.addView(button("Salvează") { save() }, fullWidth())

        statusLabel = label("", 12f, mutedColor)
        root.addView(statusLabel, fullWidth())

        root.addView(space(10))
        root.addView(
            label(
                "Analiza AI se cere pe câte un joc, la apăsarea „Analizează jocul”. Fără " +
                    "aceste setări rămâne analiza locală: gratuită, instantă pe telefon și " +
                    "funcțională fără internet.",
                11f,
                mutedColor,
            ),
            fullWidth(),
        )
    }

    private fun save() {
        AiSettings.save(this, draft())
        val saved = AiSettings.load(this)
        Log.i(TAG, "saved endpoint=${saved.endpoint} model=${saved.model} key=${saved.apiKey.isNotBlank()} configured=${saved.isConfigured}")
        status(
            if (saved.isConfigured) "Setări salvate — ${saved.model} e activ."
            else "Setări salvate — analiza AI rămâne închisă (lipsesc endpointul sau modelul).",
            successColor,
        )
    }

    private fun testConnection() {
        val settings = draft()
        if (settings.isConfigured.not()) {
            status("Completează endpointul și modelul înainte de test.", errorColor)
            return
        }
        testButton.isEnabled = false
        status("Se testează conexiunea…", mutedColor)
        Thread {
            val result = runCatching { AiAnalyzer(settings).ping() }
            runOnUiThread {
                if (cancelled || isFinishing) return@runOnUiThread
                testButton.isEnabled = true
                if (result.isSuccess) {
                    Log.i(TAG, "ping ok: ${result.getOrThrow().trim().take(80)}")
                    status("Conectat: ${result.getOrThrow().trim().take(40).ifEmpty { "fără text" }}", successColor)
                } else {
                    val message = result.exceptionOrNull()?.message.orEmpty().take(160)
                    Log.w(TAG, "ping failed: ${result.exceptionOrNull()?.javaClass?.simpleName}: $message")
                    status("Nu mă pot conecta: ${message.ifEmpty { "eroare necunoscută" }}", errorColor)
                }
            }
        }.start()
    }

    private fun draft() = AiSettings(
        endpoint = endpointField.text.toString(),
        apiKey = keyField.text.toString(),
        model = modelField.text.toString(),
    )

    private fun status(text: String, color: Int) {
        statusLabel.visibility = android.view.View.VISIBLE
        statusLabel.setTextColor(color)
        statusLabel.text = text
    }

    private fun caption(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(mutedColor)
        setTextSize(12f)
        gravity = Gravity.START
        setPadding(dp(2), 0, dp(2), dp(4))
    }

    private fun field(hint: String, password: Boolean = false): EditText = EditText(this).apply {
        this.hint = hint
        setHintTextColor(mutedColor)
        setTextColor(textColor)
        setTextSize(14f)
        isSingleLine = true
        inputType = if (password) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = controlBackground(cardColor, strokeColor = Color.rgb(86, 86, 86))
    }
}
