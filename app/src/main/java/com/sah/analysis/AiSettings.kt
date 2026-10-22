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
    val enabled: Boolean = true,
) {
    /** Endpoint and model are enough to try; hosted providers are what need the key. */
    val isConfigured: Boolean
        get() = endpoint.isNotBlank() && model.isNotBlank()

    /**
     * Whether the model is actually asked. Turning the switch off keeps the
     * saved endpoint, key and model on disk but sends every game straight to
     * the local engine — no HTTP call, no waiting for a provider that may be
     * rate-limiting or unreachable.
     */
    val isActive: Boolean
        get() = enabled && isConfigured

    /**
     * The POST target. Providers document themselves in three sloppy ways and
     * each is met where it stands: a URL already ending in `/chat/completions`
     * is kept, a URL with any path (like `/v1`) gets the endpoint appended, a
     * bare host gets the conventional `/v1` first — and a host typed without a
     * scheme (the most common typo) is assumed to be https, never http.
     */
    fun chatUrl(): String {
        val raw = endpoint.trim().trimEnd('/')
        if (raw.isEmpty()) return ""
        val base = if ("://" in raw) raw else "https://$raw"
        if (base.endsWith("/chat/completions")) return base
        val path = base.substringAfter("://").substringAfter('/', "")
        return if (path.isEmpty()) "$base/v1/chat/completions" else "$base/chat/completions"
    }

    companion object {
        private const val PREFS = "ai-analysis"
        private const val KEY_ENDPOINT = "endpoint"
        private const val KEY_API_KEY = "apiKey"
        private const val KEY_MODEL = "model"
        private const val KEY_ENABLED = "enabled"

        fun load(context: Context): AiSettings {
            val prefs = preferences(context)
            return AiSettings(
                endpoint = prefs.getString(KEY_ENDPOINT, "").orEmpty(),
                apiKey = prefs.getString(KEY_API_KEY, "").orEmpty(),
                model = prefs.getString(KEY_MODEL, "").orEmpty(),
                // On by default so an already-configured model keeps being asked;
                // the switch is only for a user who wants the local engine back.
                enabled = prefs.getBoolean(KEY_ENABLED, true),
            )
        }

        fun save(context: Context, settings: AiSettings) {
            preferences(context).edit()
                .putString(KEY_ENDPOINT, settings.endpoint.trim())
                .putString(KEY_API_KEY, settings.apiKey.trim())
                .putString(KEY_MODEL, settings.model.trim())
                .putBoolean(KEY_ENABLED, settings.enabled)
                .apply()
        }

        /**
         * The switch on its own. Toggling it must take effect the moment it is
         * tapped, so it writes its own key instead of waiting for Salvează:
         * a switch that still needs saving is a switch that reports being off
         * while every game still calls the model. Only this one key is touched,
         * so endpoint, key and model keep whatever the text fields last saved.
         */
        fun saveEnabled(context: Context, enabled: Boolean) {
            preferences(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        }

        private fun preferences(context: Context): SharedPreferences =
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}
