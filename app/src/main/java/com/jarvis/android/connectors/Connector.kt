package com.jarvis.android.connectors

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** A tool a connector offers, as its server listed it (kept so that a session can start before the server answers again). */
@Serializable
internal data class RemoteTool(
    val name: String,
    val description: String = "",
    val inputSchema: JsonObject = JsonObject(emptyMap()),
)

/** The OAuth client Jarvis registered with a connector's authorization server, and where that server takes logins and tokens. */
@Serializable
internal data class OAuthClient(
    val clientId: String,
    val clientSecret: String = "",
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val resource: String,
    val scope: String = "",
)

@Serializable
internal data class OAuthTokens(
    val accessToken: String,
    val refreshToken: String = "",
    /** Epoch milliseconds; 0 when the server did not say. */
    val expiresAt: Long = 0,
)

/**
 * A connector: a remote MCP server whose tools Jarvis uses. [token] is a fixed key the user pasted; [oauth] the tokens of a login made in
 * the browser. [status] is the last problem met, empty when the server answered.
 */
@Serializable
internal data class Connector(
    val id: String,
    val name: String,
    val url: String,
    val token: String = "",
    val enabled: Boolean = true,
    val oauthClient: OAuthClient? = null,
    val oauth: OAuthTokens? = null,
    val tools: List<RemoteTool> = emptyList(),
    val status: String = "",
    val needsLogin: Boolean = false,
    val checkedAt: Long = 0,
)

/** A login under way in the browser, kept until the browser comes back with its code. */
@Serializable
internal data class PendingLogin(
    val connectorId: String,
    val state: String,
    val verifier: String,
)

@Serializable
internal data class ConnectorsFile(
    val connectors: List<Connector> = emptyList(),
    val pending: PendingLogin? = null,
)

internal val connectorJson = Json { ignoreUnknownKeys = true; encodeDefaults = false }

internal fun decodeConnectors(text: String?): ConnectorsFile =
    if (text.isNullOrBlank()) ConnectorsFile() else try { connectorJson.decodeFromString(ConnectorsFile.serializer(), text) } catch (_: Exception) { ConnectorsFile() }

internal fun encodeConnectors(file: ConnectorsFile): String = connectorJson.encodeToString(ConnectorsFile.serializer(), file)
