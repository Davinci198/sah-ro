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
    fun withoutEndpointOrModelThereIsNothingToCall() {
        assertTrue(settings.isConfigured)
        // A key is only what hosted providers demand; local servers need no key.
        assertTrue(settings.copy(apiKey = "").isConfigured)
        assertFalse(settings.copy(model = "  ").isConfigured)
        assertFalse(settings.copy(endpoint = "").isConfigured)
        assertEquals("", settings.copy(endpoint = "").chatUrl())
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
        assertEquals(1, moments?.size)
        val first = moments?.firstOrNull() as? Map<*, *>
        assertEquals("Bb5", first?.get("san"))
        assertEquals("MISTAKE", first?.get("category"))
        assertEquals(29.0, first?.get("loss"))
        assertNotNull(parsed?.get("takeaway"))
    }
}
