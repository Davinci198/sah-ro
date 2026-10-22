package com.sah.analysis

import com.sah.content.Json
import com.sah.content.asJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire side of the AI analysis: what URL gets POSTed to, that the body we
 * build is valid JSON even when the prompt is full of quotes, and that a reply
 * is unwrapped (or refused) without ever touching the chess layer.
 */
class AiAnalyzerTest {

    private val settings = AiSettings(
        endpoint = "https://api.openai.com/v1",
        apiKey = "sk-test",
        model = "gpt-4o-mini",
    )

    @Test
    fun baseUrlGainsTheChatPath() {
        assertEquals("https://api.openai.com/v1/chat/completions", settings.chatUrl())
        assertEquals("https://api.openai.com/v1/chat/completions", settings.copy(endpoint = "https://api.openai.com/v1/").chatUrl())
    }

    @Test
    fun aUrlThatAlreadyPointsAtTheEndpointIsKept() {
        val full = "https://gateway.example.com/openai/deployments/x/chat/completions"

        assertEquals(full, settings.copy(endpoint = full).chatUrl())
    }

    @Test
    fun deeperPathsAreLeftAlone() {
        val deep = "https://host.local:11434/v1/extra"

        assertEquals("$deep/chat/completions", settings.copy(endpoint = deep).chatUrl())
    }

    @Test
    fun aBareHostGetsTheConventionalVersionPrefix() {
        // api.openai.com alone would 404 — the path every provider documents is /v1.
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            settings.copy(endpoint = "https://api.openai.com").chatUrl(),
        )
        assertEquals(
            "http://192.168.1.10:11434/v1/chat/completions",
            settings.copy(endpoint = "http://192.168.1.10:11434").chatUrl(),
        )
    }

    @Test
    fun aHostTypedWithoutASchemeIsAssumedSecure() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            settings.copy(endpoint = "api.openai.com/v1").chatUrl(),
        )
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            settings.copy(endpoint = "api.openai.com").chatUrl(),
        )
    }

    @Test
    fun withoutEndpointOrModelThereIsNothingToCall() {
        assertTrue(settings.isConfigured)
        // A key is only what hosted providers demand; local servers need no key.
        assertTrue(settings.copy(apiKey = "").isConfigured)
        assertFalse(settings.copy(model = "  ").isConfigured)
        assertFalse(settings.copy(endpoint = "").isConfigured)
        assertEquals("", settings.copy(endpoint = "").chatUrl())
    }

    @Test
    fun theSwitchOffStillNeedsBothFieldsBeforeItCanCall() {
        // On by default, so an already-configured model keeps being asked.
        assertTrue(settings.isActive)
        // Switching off stops the call even with endpoint and model in place;
        // this is the case a provider in cooldown is saved from waiting on.
        val off = settings.copy(enabled = false)
        assertFalse(off.isActive)
        assertTrue(off.isConfigured)
        // Switching off while unconfigured changes nothing: still no call.
        assertFalse(settings.copy(enabled = false, endpoint = "").isActive)
        // Switching on is not enough without endpoint and model.
        assertFalse(settings.copy(enabled = true, model = "").isActive)
    }

    @Test
    fun requestIsJsonThatSurvivesAHostilePrompt() {
        val body = AiAnalyzer.chatRequestJson(settings, """Spui "salut" și atât.""", "Un PGN cu \" nu strică")

        val parsed = runCatching { Json.parse(body) }.getOrNull()?.asJsonObject()
        assertTrue("body should parse as JSON", parsed != null)
        assertEquals("gpt-4o-mini", parsed?.get("model"))
        val messages = parsed?.get("messages") as? List<*>
        assertEquals(2, messages?.size)
        assertEquals("Spui \"salut\" și atât.", (messages?.first() as? Map<*, *>)?.get("content"))
        assertEquals("Un PGN cu \" nu strică", (messages?.get(1) as? Map<*, *>)?.get("content"))
    }

    @Test
    fun replyIsPulledOutOfTheOpenAiEnvelope() {
        val envelope = """{"choices":[{"message":{"role":"assistant","content":"Bună ziua"}}],"usage":{}}"""

        assertEquals("Bună ziua", AiAnalyzer.chatReply(envelope))
        assertNull(AiAnalyzer.chatReply("{}"))
        assertNull(AiAnalyzer.chatReply("nu-i JSON"))
        assertNull(AiAnalyzer.chatReply("""{"choices":[]}"""))
    }

    @Test
    fun providerErrorsAreSurfacedForThePlayer() {
        assertEquals(
            "Invalid API key",
            AiAnalyzer.chatError("""{"error":{"message":"Invalid API key","type":"auth"}}"""),
        )
        assertEquals("model not found", AiAnalyzer.chatError("""{"message":"model not found"}"""))
        assertNull(AiAnalyzer.chatError("""{"choices":[]}"""))
    }

    @Test
    fun promptCarriesTheGameTheColourAndTheCategories() {
        val prompt = AiAnalyzer.promptFor("1. e4 e5 2. Nf3 *", playerIsWhite = true)

        assertTrue(prompt.contains("1. e4 e5 2. Nf3 *"))
        assertTrue(prompt.contains("joacă ALB"))
        assertTrue(prompt.contains("MISSED_OPPORTUNITY"))
        assertTrue(prompt.contains("maximum 6"))
        assertFalse(prompt.contains("joacă NEGRU"))
    }

    @Test
    fun promptAsksForThePlayersBrilliantMovesToo() {
        val prompt = AiAnalyzer.promptFor("1. e4 *", playerIsWhite = true)

        // Praises, not errors: 1-3 positive moments, zero loss, an honest category.
        assertTrue(prompt.contains("1-3 momente pozitive"))
        assertTrue(prompt.contains("BRILLIANT sau GREAT"))
        assertTrue(prompt.contains("\"loss\" 0"))
        assertTrue(prompt.contains("laude, nu greșeli"))
    }

    @Test
    fun blackPlayerPromptSaysSo() {
        val prompt = AiAnalyzer.promptFor("1. e4 *", playerIsWhite = false)

        assertTrue(prompt.contains("joacă NEGRU"))
    }

    @Test
    fun theJsonExampleInsideThePromptIsItselfValid() {
        val jsonLine = AiAnalyzer.promptFor("1. e4 *", playerIsWhite = true)
            .lineSequence()
            .first { it.startsWith("{\"opening\"") }

        val parsed = runCatching { Json.parseObject(jsonLine) }.getOrNull()

        assertNotNull("example should be valid JSON but was: $jsonLine", parsed)
        val moments = parsed?.get("moments") as? List<*>
        assertEquals(2, moments?.size)
        val mistake = moments?.getOrNull(0) as? Map<*, *>
        assertEquals("Bb5", mistake?.get("san"))
        assertEquals("MISTAKE", mistake?.get("category"))
        assertEquals(29.0, mistake?.get("loss"))
        val praise = moments?.getOrNull(1) as? Map<*, *>
        assertEquals("Ng6+", praise?.get("san"))
        assertEquals("BRILLIANT", praise?.get("category"))
        assertEquals(0.0, praise?.get("loss"))
        assertNotNull(parsed?.get("takeaway"))
    }
}
