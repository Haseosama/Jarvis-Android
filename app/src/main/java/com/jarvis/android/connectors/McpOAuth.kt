package com.jarvis.android.connectors

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Where the browser comes back after a connector's login (see McpOAuthActivity in the manifest). */
internal const val OAUTH_REDIRECT_URI = "com.jarvis.android.mcp:/oauth"

/**
 * The OAuth login of an MCP server, as the protocol describes it: find the authorization server (RFC 9728, then RFC 8414), register
 * Jarvis there as a client (RFC 7591), then a login in the browser with PKCE, and tokens refreshed when they expire.
 */
internal object McpOAuth {
    private fun JsonObject.s(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun getJson(http: OkHttpClient, url: String): JsonObject? = try {
        http.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { r ->
            if (!r.isSuccessful) null else Json.parseToJsonElement(r.body?.string().orEmpty()) as? JsonObject
        }
    } catch (_: Exception) {
        null
    }

    /** Finds the authorization server of [serverUrl] and registers Jarvis with it. */
    suspend fun register(http: OkHttpClient, serverUrl: String, resourceMetadataHint: String?): OAuthClient = withContext(Dispatchers.IO) {
        val resourceMeta = (listOfNotNull(resourceMetadataHint) + protectedResourceMetadataUrls(serverUrl))
            .firstNotNullOfOrNull { getJson(http, it) }
        val issuer = (resourceMeta?.get("authorization_servers") as? JsonArray)
            ?.firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNull } ?: originOf(serverUrl)
        val asMeta = authorizationServerMetadataUrls(issuer).firstNotNullOfOrNull { getJson(http, it) }
        val base = originOf(issuer)
        val authorize = asMeta?.s("authorization_endpoint") ?: "$base/authorize"
        val token = asMeta?.s("token_endpoint") ?: "$base/token"
        // without a description the usual address is tried; a server that describes itself without one takes no new clients
        val registration = if (asMeta == null) "$base/register" else asMeta.s("registration_endpoint")
            ?: throw McpException("Ce serveur ne permet pas d’inscrire Jarvis tout seul : collez plutôt un jeton d’accès.")
        val scopes = (resourceMeta?.get("scopes_supported") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        val body = buildJsonObject {
            put("client_name", "Jarvis Android")
            putJsonArray("redirect_uris") { add(JsonPrimitive(OAUTH_REDIRECT_URI)) }
            putJsonArray("grant_types") { add(JsonPrimitive("authorization_code")); add(JsonPrimitive("refresh_token")) }
            putJsonArray("response_types") { add(JsonPrimitive("code")) }
            put("token_endpoint_auth_method", "none")
            if (scopes.isNotEmpty()) put("scope", scopes.joinToString(" "))
        }
        val request = Request.Builder().url(registration).header("Accept", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        val answer = http.newCall(request).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw McpException("Inscription refusée par le serveur (${r.code}) : collez plutôt un jeton d’accès.")
            Json.parseToJsonElement(text) as? JsonObject ?: throw McpException("Réponse d’inscription illisible.")
        }
        OAuthClient(
            clientId = answer.s("client_id") ?: throw McpException("Le serveur n’a pas donné d’identifiant client."),
            clientSecret = answer.s("client_secret").orEmpty(),
            authorizationEndpoint = authorize,
            tokenEndpoint = token,
            resource = canonicalResource(serverUrl),
            scope = scopes.joinToString(" "),
        )
    }

    /** The page to open in the browser for the login. */
    fun authorizationUrl(client: OAuthClient, state: String, verifier: String): String {
        val url = client.authorizationEndpoint.toHttpUrlOrNull() ?: throw McpException("Adresse de connexion invalide.")
        return url.newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", client.clientId)
            .addQueryParameter("redirect_uri", OAUTH_REDIRECT_URI)
            .addQueryParameter("code_challenge", pkceChallenge(verifier))
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("state", state)
            .addQueryParameter("resource", client.resource)
            .apply { if (client.scope.isNotEmpty()) addQueryParameter("scope", client.scope) }
            .build().toString()
    }

    suspend fun exchangeCode(http: OkHttpClient, client: OAuthClient, code: String, verifier: String): OAuthTokens =
        tokenRequest(http, client, null) {
            add("grant_type", "authorization_code")
            add("code", code)
            add("redirect_uri", OAUTH_REDIRECT_URI)
            add("code_verifier", verifier)
        }

    /** New tokens from the refresh token; null when there is none or the server no longer takes it. */
    suspend fun refresh(http: OkHttpClient, client: OAuthClient, tokens: OAuthTokens): OAuthTokens? {
        if (tokens.refreshToken.isEmpty()) return null
        return try {
            tokenRequest(http, client, tokens.refreshToken) {
                add("grant_type", "refresh_token")
                add("refresh_token", tokens.refreshToken)
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun tokenRequest(http: OkHttpClient, client: OAuthClient, previousRefresh: String?, fill: FormBody.Builder.() -> Unit): OAuthTokens =
        withContext(Dispatchers.IO) {
            val form = FormBody.Builder().apply(fill)
                .add("client_id", client.clientId)
                .add("resource", client.resource)
                .apply { if (client.clientSecret.isNotEmpty()) add("client_secret", client.clientSecret) }
                .build()
            val request = Request.Builder().url(client.tokenEndpoint).header("Accept", "application/json").post(form).build()
            http.newCall(request).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw McpException("Le serveur a refusé la connexion (${r.code}).")
                val o = Json.parseToJsonElement(text) as? JsonObject ?: throw McpException("Réponse de connexion illisible.")
                val expiresIn = (o["expires_in"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
                OAuthTokens(
                    accessToken = o.s("access_token") ?: throw McpException("Le serveur n’a pas donné de jeton."),
                    refreshToken = o.s("refresh_token") ?: previousRefresh.orEmpty(),
                    expiresAt = if (expiresIn != null && expiresIn > 0) System.currentTimeMillis() + expiresIn * 1000 else 0,
                )
            }
        }
}
