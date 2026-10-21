package com.sah.analysis

import android.util.Log
import com.sah.content.Json
import com.sah.content.asJsonObject
import com.sah.content.toJsonString
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * One call to an OpenAI-compatible chat endpoint: it writes the game up as a
 * prompt, POSTs it and hands back the model's raw reply.
 *
 * Nothing here judges chess — that is [AiReportParser]'s job, so a slow or
 * badly behaved model can only cost time, never a wrong board. When [analyze]
 * throws, the caller falls back to the local engine.
 */
class AiAnalyzer(private val settings: AiSettings) {

    /** The whole reply for one game, already unwrapped from `choices[0]`. */
    fun analyze(pgn: String, playerIsWhite: Boolean, onProgress: (String) -> Unit): String =
        chat(SYSTEM_PROMPT, promptFor(pgn, playerIsWhite), onProgress)

    /** The cheapest round trip that proves endpoint, key and model are usable. */
    fun ping(onProgress: (String) -> Unit = {}): String =
        chat(PING_PROMPT, PING_PROMPT, onProgress)

    /**
     * Sends [system] + [user] and returns the answer. Progress is reported
     * after the request is written — the one moment where a stalled call and a
     * broken one look identical — and again while waiting for the reply.
     */
    private fun chat(system: String, user: String, onProgress: (String) -> Unit): String {
        val url = settings.chatUrl()
        if (url.isEmpty()) error("Endpoint-ul nu e configurat.")
        Log.i(TAG, "POST $url model=${settings.model} key=${settings.apiKey.isNotBlank()}")
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            if (settings.apiKey.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
            }
        }
        try {
            connection.outputStream.use { output ->
                output.write(chatRequestJson(settings, system, user).toByteArray(Charsets.UTF_8))
            }
            onProgress("Se așteaptă răspunsul modelului…")
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                .orEmpty()
            Log.i(TAG, "HTTP $code, ${body.length} bytes")
            if (code !in 200..299) {
                val detail = chatError(body)?.take(160)
                Log.w(TAG, "provider said: $detail")
                throw IOException("Serverul a răspuns $code" + (detail?.let { ": $it" } ?: ""))
            }
            val reply = chatReply(body)
            if (reply.isNullOrBlank()) {
                Log.w(TAG, "no content in reply, head=${body.take(300)}")
                throw IOException("Răspuns fără conținut de la model.")
            }
            return reply
        } catch (failure: Exception) {
            Log.w(TAG, "call failed: ${failure.javaClass.simpleName}: ${failure.message}")
            throw failure
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        /** Every AI request and its fate, so a failure is readable over adb. */
        private const val TAG = "SahAi"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 120_000

        private const val SYSTEM_PROMPT =
            "Ești un analist de șah care explică în limba română partide jucate de amatori. " +
                "Răspunzi exclusiv cu un obiect JSON valid, pe un singur rând, fără text " +
                "înainte sau după el și fără blocuri de cod."

        private const val PING_PROMPT = "Răspunde cu exact cuvântul: OK"

        /** The exact answer shape asked for, on one line so nothing is lost at a seam. */
        private const val EXAMPLE_JSON =
            """{"opening":"deschiderea în română","whiteAccuracy":0.0,"blackAccuracy":0.0,"moments":[{"moveNumber":9,"color":"white","san":"Bb5","bestSan":"Nc3","category":"MISTAKE","loss":29.0,"lesson":"de ce e greșită și ce joci în loc"}],"takeaway":"concluzia în una-două fraze"}"""

        /**
         * The prompt for one game: the exact shape of the answer, the rules that
         * keep it checkable, and the PGN. Kept as data so the request can be
         * asserted in a test without a network.
         */
        fun promptFor(pgn: String, playerIsWhite: Boolean): String = buildString {
            appendLine("Analizează partida de șah de mai jos și întoarce JSON cu exact această formă:")
            appendLine()
            appendLine(EXAMPLE_JSON)
            appendLine()
            appendLine("Reguli:")
            appendLine(
                "- \"moments\": maximum 6, doar greșelile jucătorului indicat mai jos, " +
                    "ordonate de la cea mai paguboasă la cea mai puțin; poți lăsa lista goală.",
            )
            appendLine(
                "- \"color\" e culoarea care a jucat mutarea; \"san\" e exact mutarea din partida " +
                    "dată; \"bestSan\" trebuie să fie legală în poziția de dinaintea ei.",
            )
            appendLine(
                "- \"category\" trebuie să fie exact unul dintre: BRILLIANT, GREAT, BEST, GOOD, " +
                    "BOOK, FORCED, INACCURACY, MISSED_OPPORTUNITY, MISTAKE, BLUNDER.",
            )
            appendLine(
                "- \"loss\" e cu cât scade șansa (0-100); \"whiteAccuracy\" și \"blackAccuracy\" " +
                    "sunt procente (0-100).",
            )
            appendLine("- \"lesson\" explică pe scurt greșeala, în română, și ce să joci în loc.")
            appendLine("- Nu inventa mutări care nu sunt în partidă.")
            appendLine()
            appendLine(
                if (playerIsWhite) "Jucătorul analizat joacă ALB. Momentele trebuie să fie doar ale lui."
                else "Jucătorul analizat joacă NEGRU. Momentele trebuie să fie doar ale lui.",
            )
            appendLine()
            appendLine("PGN:")
            appendLine(pgn)
        }

        /** The POST body: model, tight temperature, the two messages, JSON-escaped. */
        fun chatRequestJson(settings: AiSettings, system: String, user: String): String = buildString {
            append("{")
            append("\"model\":${settings.model.toJsonString()}")
            append(",\"temperature\":0.2")
            // A reasoning model thinks inside its output budget; 1200 tokens of
            // thinking left no room for the JSON at all.
            append(",\"max_tokens\":2048")
            append(",\"messages\":[")
            append("{\"role\":\"system\",\"content\":${system.toJsonString()}}")
            append(",{\"role\":\"user\",\"content\":${user.toJsonString()}}")
            append("]}")
        }

        /** The model's text from a `chat/completions` reply, or null when absent. */
        fun chatReply(replyJson: String): String? {
            val root = runCatching { Json.parse(replyJson) }.getOrNull()?.asJsonObject() ?: return null
            val choices = root["choices"] as? List<*> ?: return null
            val message = choices.firstOrNull()?.asJsonObject()?.get("message")?.asJsonObject() ?: return null
            return message["content"] as? String
        }

        /** The provider's reason for a non-2xx, from the usual error envelope. */
        fun chatError(replyJson: String): String? {
            val root = runCatching { Json.parse(replyJson) }.getOrNull()?.asJsonObject() ?: return null
            val nested = root["error"]?.asJsonObject()?.get("message") as? String
            if (!nested.isNullOrBlank()) return nested
            val direct = root["message"] as? String
            return direct?.takeIf { it.isNotBlank() }
        }
    }
}
