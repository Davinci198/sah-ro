package com.sah.analysis

import android.content.Context
import android.content.SharedPreferences

/**
 * Where analyses that should be written by a model go: an OpenAI-compatible
 * chat endpoint, its key (optional — local servers such as Ollama or LM Studio
 * accept none) and the model to ask for.
 *
 * The rules that matter — "is it set up at all" and "what URL do I POST to" —
 * live free of Android so they can be checked on the JVM; touching
 * [SharedPreferences] stays a thin wrapper around them.
 */
data class AiSettings(
    val endpoint: String = "",
    val apiKey: String = "",
    val model: String = "",
) {
    /** Endpoint and model are enough to try; hosted providers are what need the key. */
    val isConfigured: Boolean
        get() = endpoint.isNotBlank() && model.isNotBlank()

    /**
     * The POST target. A bare base URL (how providers document themselves) gets
     * `/chat/completions` appended, while a URL that already points at it — or
     * at anything deeper the user typed — is left as it is.
     */
    fun chatUrl(): String {
        val base = endpoint.trim().trimEnd('/')
        if (base.isEmpty()) return ""
        return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
    }

    companion object {
        private const val PREFS = "ai-analysis"
        private const val KEY_ENDPOINT = "endpoint"
        private const val KEY_API_KEY = "apiKey"
        private const val KEY_MODEL = "model"

        fun load(context: Context): AiSettings {
            val prefs = preferences(context)
            return AiSettings(
                endpoint = prefs.getString(KEY_ENDPOINT, "").orEmpty(),
                apiKey = prefs.getString(KEY_API_KEY, "").orEmpty(),
                model = prefs.getString(KEY_MODEL, "").orEmpty(),
            )
        }

        fun save(context: Context, settings: AiSettings) {
            preferences(context).edit()
                .putString(KEY_ENDPOINT, settings.endpoint.trim())
                .putString(KEY_API_KEY, settings.apiKey.trim())
                .putString(KEY_MODEL, settings.model.trim())
                .apply()
        }

        private fun preferences(context: Context): SharedPreferences =
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}
