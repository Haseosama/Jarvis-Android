package com.markliv.android

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class GeminiClientTest {
    private val client = GeminiClient()

    @Test
    fun parsesSuccessfulResponse() {
        assertEquals("Bonjour !", client.parseResponseJson(response(JSONObject().put("text", "Bonjour !"))))
    }

    @Test
    fun concatenatesPartsWithoutAddingSeparators() {
        assertEquals(
            "Bonjour Mark LIV !",
            client.parseResponseJson(
                response(
                    JSONObject().put("text", "Bonjour "),
                    JSONObject().put("text", "Mark LIV"),
                    JSONObject().put("text", " !")
                )
            )
        )
    }

    @Test
    fun ignoresThoughtAndNonTextParts() {
        assertEquals(
            "Réponse visible",
            client.parseResponseJson(
                response(
                    JSONObject().put("thought", true).put("text", "secret"),
                    JSONObject().put("inlineData", JSONObject().put("data", "secret")),
                    JSONObject().put("text", JSONObject.NULL),
                    JSONObject().put("text", 42),
                    JSONObject().put("thought", false).put("text", "Réponse visible")
                )
            )
        )
    }

    @Test
    fun rejectsEmptyResponses() {
        val bodies = listOf(
            "", "  ", "{}", "{\"candidates\":[]}",
            "{\"candidates\":[{}]}",
            response(),
            response(JSONObject().put("text", " \n\t")),
            response(JSONObject().put("thought", true).put("text", "secret")),
            response(JSONObject().put("functionCall", JSONObject().put("name", "action")))
        )
        bodies.forEach { body ->
            assertSafeError(GeminiClient.ERROR_EMPTY) { client.parseResponseJson(body) }
        }
    }

    @Test
    fun rejectsBlockedPromptsWithoutReflectingDetails() {
        val root = JSONObject(response(JSONObject().put("text", "secret")))
        root.put("promptFeedback", JSONObject().put("blockReason", "SAFETY").put("blockReasonMessage", "secret"))
        assertSafeError(GeminiClient.ERROR_BLOCKED) { client.parseResponseJson(root.toString()) }
    }

    @Test
    fun acceptsUnspecifiedPromptBlockReason() {
        val root = JSONObject(response(JSONObject().put("text", "Bonjour")))
        root.put("promptFeedback", JSONObject().put("blockReason", "BLOCK_REASON_UNSPECIFIED"))
        assertEquals("Bonjour", client.parseResponseJson(root.toString()))
    }

    @Test
    fun rejectsBlockedCandidatesEvenWithText() {
        listOf("SAFETY", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "RECITATION", "IMAGE_SAFETY").forEach { reason ->
            val root = JSONObject(response(JSONObject().put("text", "secret")))
            root.getJSONArray("candidates").getJSONObject(0).put("finishReason", reason)
            assertSafeError(GeminiClient.ERROR_BLOCKED) { client.parseResponseJson(root.toString()) }
        }
    }

    @Test
    fun usesOnlyFirstCandidate() {
        val root = JSONObject(response(JSONObject().put("text", "Premier")))
        val second = JSONObject(response(JSONObject().put("text", "Second")))
        root.getJSONArray("candidates").put(second.getJSONArray("candidates").getJSONObject(0))
        assertEquals("Premier", client.parseResponseJson(root.toString()))
    }

    @Test
    fun malformedResponseDoesNotExposeBodyOrCause() {
        assertSafeError(GeminiClient.ERROR_MALFORMED) { client.parseResponseJson("secret") }
        assertSafeError(GeminiClient.ERROR_EMPTY) {
            client.parseResponseJson("{\"error\":{\"message\":\"secret\"}}")
        }
    }

    @Test
    fun mapsHttpErrorsToSafeFrenchMessages() {
        val expectations = mapOf(
            400 to "Requête refusée par le service Gemini (requête invalide).",
            401 to "Clé API refusée par le service Gemini. Vérifiez votre clé.",
            403 to "Accès refusé par le service Gemini. Votre clé n'a pas les droits nécessaires.",
            404 to "Modèle Gemini introuvable. Vérifiez le nom du modèle.",
            429 to "Limite de requêtes atteinte. Patientez avant de réessayer."
        )
        expectations.forEach { (status, expected) ->
            assertEquals(expected, client.httpErrorMessage(status))
        }
        (500..599).forEach { status ->
            assertEquals(
                "Le service Gemini est momentanément indisponible. Réessayez plus tard.",
                client.httpErrorMessage(status)
            )
        }
        listOf(301, 302, 307, 308, 418).forEach { status ->
            assertEquals("Erreur inattendue du service Gemini (code $status).", client.httpErrorMessage(status))
        }
    }

    @Test
    fun rejectsMaliciousModelNames() {
        listOf("", " ", "../secret", "gemini?key=secret", "gemini#secret", "gemini/secret", "gemini%2Fsecret", "gemini:secret", "gemini\n", "gemini\\secret", "gémini").forEach { model ->
            assertSafeError(GeminiClient.ERROR_INVALID_MODEL) { client.requireValidModel(model) }
        }
    }

    @Test
    fun acceptsValidModelNames() {
        listOf("gemini-2.5-flash", "Gemini_1.0-test", "a").forEach(client::requireValidModel)
    }

    @Test
    fun validatesInputsBeforeNetworkAccess() {
        assertSafeError(GeminiClient.ERROR_INVALID_MODEL) {
            runBlocking { client.generate("secret", "../secret", listOf(ChatMessage("user", "Bonjour"))) }
        }
        assertSafeError(GeminiClient.ERROR_MISSING_KEY) {
            runBlocking { client.generate("", "gemini", listOf(ChatMessage("user", "Bonjour"))) }
        }
        assertSafeError(GeminiClient.ERROR_INVALID_KEY) {
            runBlocking { client.generate("secret\r\nInjected: value", "gemini", listOf(ChatMessage("user", "Bonjour"))) }
        }
    }

    @Test
    fun preservesHistoryOrderTextAndMapsAssistantRole() {
        val messages = listOf(
            ChatMessage("user", " Bonjour \"Mark\"\n"),
            ChatMessage("assistant", "Bonjour"),
            ChatMessage("user", "Suite"),
            ChatMessage("model", "Réponse")
        )
        val request = JSONObject(client.buildRequestJson(messages).toString())
        val contents = request.getJSONArray("contents")
        assertEquals(4, contents.length())
        messages.forEachIndexed { index, message ->
            val content = contents.getJSONObject(index)
            assertEquals(if (message.role == "assistant") "model" else message.role, content.getString("role"))
            assertEquals(message.text, content.getJSONArray("parts").getJSONObject(0).getString("text"))
        }
        val instruction = request.getJSONObject("system_instruction").getJSONArray("parts").getJSONObject(0).getString("text")
        assertTrue(instruction.contains("Mark LIV"))
        assertTrue(instruction.contains("français"))
        assertTrue(instruction.contains("aucune action réelle"))
        assertFalse(request.has("tools"))
        assertFalse(request.has("key"))
    }

    @Test
    fun rejectsUnsupportedHistoryRoles() {
        listOf("system", "tool", "secret").forEach { role ->
            assertSafeError(GeminiClient.ERROR_INVALID_ROLE) {
                client.buildRequestJson(listOf(ChatMessage(role, "Texte")))
            }
        }
    }

    @Test
    fun skipsBlankMessagesAndRejectsEmptyHistory() {
        val contents = client.buildRequestJson(
            listOf(ChatMessage("user", "  "), ChatMessage("user", "Bonjour"))
        ).getJSONArray("contents")
        assertEquals(1, contents.length())
        assertSafeError(GeminiClient.ERROR_EMPTY_CONVERSATION) { client.buildRequestJson(emptyList()) }
        assertSafeError(GeminiClient.ERROR_EMPTY_CONVERSATION) {
            client.buildRequestJson(listOf(ChatMessage("user", "  ")))
        }
    }

    @Test
    fun readsUtf8AndClosesStream() {
        val stream = TrackingStream("Réponse française".toByteArray(Charsets.UTF_8))
        assertEquals("Réponse française", client.readBody(stream))
        assertTrue(stream.closed)
    }

    @Test
    fun enforcesResponseByteLimitAndClosesStream() {
        val exact = TrackingStream(ByteArray(2_000_000) { 65 })
        assertEquals(2_000_000, client.readBody(exact).length)
        assertTrue(exact.closed)
        val oversized = TrackingStream(ByteArray(2_000_001) { 65 })
        assertSafeError(GeminiClient.ERROR_TOO_LARGE) { client.readBody(oversized) }
        assertTrue(oversized.closed)
    }

    @Test
    fun sanitizesIoExceptionsAndClosesStream() {
        var closed = false
        val stream = object : InputStream() {
            override fun read(): Int = throw IOException("secret")

            override fun close() {
                closed = true
            }
        }
        assertSafeError(GeminiClient.ERROR_NETWORK) { client.readBody(stream) }
        assertTrue(closed)
    }

    @Test
    fun parsesMultipleModelsWithUniqueSortedNames() {
        val body = modelsResponse(
            listedModel("models/gemini-z", "generateContent"),
            listedModel("models/Gemini_1.0-test", "generateContent"),
            listedModel("models/gemini-a", "generateContent"),
            listedModel("models/gemini-z", "generateContent")
        )
        val result = client.parseModelsJson(body)
        assertEquals(listOf("Gemini_1.0-test", "gemini-a", "gemini-z"), result.first)
        assertNull(result.second)
    }

    @Test
    fun filtersModelsBySupportedGenerationMethods() {
        val body = modelsResponse(
            listedModel("models/accepted", "countTokens", "generateContent"),
            listedModel("models/embedding", "embedContent"),
            listedModel("models/wrong-case", "GenerateContent"),
            listedModel("models/no-methods"),
            JSONObject().put("name", "models/missing-methods"),
            JSONObject().put("name", "models/not-array").put("supportedGenerationMethods", "generateContent")
        )
        assertEquals(listOf("accepted"), client.parseModelsJson(body).first)
    }

    @Test
    fun ignoresMissingAndInvalidModelNames() {
        val models = JSONArray()
            .put(JSONObject().put("supportedGenerationMethods", JSONArray().put("generateContent")))
            .put(listedModel("models/valid", "generateContent").put("name", JSONObject.NULL))
            .put(listedModel("models/valid", "generateContent").put("name", 42))
            .put(JSONObject.NULL)
            .put("secret")
        listOf(
            "", "gemini", "other/gemini", "models/", "models/../secret",
            "models/gemini?key=secret", "models/gemini/secret", "models/gemini%2Fsecret",
            "models/gemini#secret", "models/gemini\n", "models/gémini"
        ).forEach { models.put(listedModel(it, "generateContent")) }
        assertEquals(emptyList<String>(), client.parseModelsJson(JSONObject().put("models", models).toString()).first)
        assertEquals(emptyList<String>(), client.parseModelsJson("{}").first)
        assertEquals(emptyList<String>(), client.parseModelsJson("{\"models\":[]}").first)
    }

    @Test
    fun rejectsMalformedModelResponsesWithoutExposingDetails() {
        listOf(
            "secret", "{", "[]", "{\"models\":\"secret\"}", "{\"models\":null}",
            "{\"error\":{\"message\":\"secret\"}}",
            "{\"models\":[],\"nextPageToken\":{\"value\":\"secret\"}}",
            "{\"models\":[],\"nextPageToken\":42}"
        ).forEach { body ->
            assertSafeError(GeminiClient.ERROR_MALFORMED) { client.parseModelsJson(body) }
        }
        listOf("", "  ").forEach { body ->
            assertSafeError(GeminiClient.ERROR_EMPTY) { client.parseModelsJson(body) }
        }
    }

    @Test
    fun parsesAndEncodesPaginationToken() {
        val token = "a+b/c== &pageSize=1#é%"
        val body = JSONObject(modelsResponse(listedModel("models/gemini", "generateContent")))
            .put("nextPageToken", token).toString()
        val result = client.parseModelsJson(body)
        assertEquals(token, result.second)
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000" +
                "&pageToken=a%2Bb%2Fc%3D%3D+%26pageSize%3D1%23%C3%A9%25",
            client.buildModelsUrl(result.second)
        )
        assertEquals(token, client.parseModelsJson(JSONObject().put("nextPageToken", token).toString()).second)
    }

    @Test
    fun missingOrEmptyPaginationTokenEndsListing() {
        listOf("{}", "{\"nextPageToken\":\"\"}", "{\"nextPageToken\":null}").forEach { body ->
            assertNull(client.parseModelsJson(body).second)
        }
        listOf(null, "").forEach { token ->
            assertEquals(
                "https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000",
                client.buildModelsUrl(token)
            )
        }
    }

    @Test
    fun validatesListModelsKeyBeforeNetworkAccess() {
        listOf("", " ", "\t\r\n").forEach { key ->
            assertSafeError(GeminiClient.ERROR_MISSING_KEY) { runBlocking { client.listModels(key) } }
        }
        listOf("secret\r\nInjected: value", "secret value", "secrét", "secret\u007f").forEach { key ->
            assertSafeError(GeminiClient.ERROR_INVALID_KEY) { runBlocking { client.listModels(key) } }
        }
    }

    private fun listedModel(name: String, vararg methods: String): JSONObject {
        val array = JSONArray()
        methods.forEach { array.put(it) }
        return JSONObject().put("name", name).put("supportedGenerationMethods", array)
    }

    private fun modelsResponse(vararg models: JSONObject): String {
        val array = JSONArray()
        models.forEach { array.put(it) }
        return JSONObject().put("models", array).toString()
    }

    private fun response(vararg parts: JSONObject): String {
        val array = JSONArray()
        parts.forEach { array.put(it) }
        return JSONObject().put(
            "candidates",
            JSONArray().put(
                JSONObject().put("finishReason", "STOP").put("content", JSONObject().put("parts", array))
            )
        ).toString()
    }

    private fun assertSafeError(expected: String, action: () -> Unit) {
        try {
            action()
            throw AssertionError("GeminiException attendue")
        } catch (error: GeminiException) {
            assertEquals(expected, error.message)
            var cause = error.cause
            while (cause != null) {
                assertTrue(cause is GeminiException)
                assertEquals(expected, cause.message)
                cause = cause.cause
            }
            assertTrue(error.suppressed.isEmpty())
            assertFalse(error.toString().contains("secret"))
        }
    }

    private class TrackingStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false

        override fun close() {
            closed = true
            super.close()
        }
    }
}
