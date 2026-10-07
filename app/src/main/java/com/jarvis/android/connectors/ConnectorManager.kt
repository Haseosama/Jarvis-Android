package com.jarvis.android.connectors

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** What an action on a connector gives back: a sentence for the user, and the page to open when a login is needed. */
internal data class ConnectorOutcome(val message: String, val loginUrl: String? = null)

/**
 * The user's connectors: remote MCP servers added in the settings (or by voice), kept encrypted with their tokens through [load] and
 * [save], their tools published to the tool registry through InstalledConnectors. [reservedNames]: the names a connector's tool cannot
 * take (built-in tools and plugins).
 */
internal class ConnectorManager(
    private val http: OkHttpClient,
    private val load: () -> String?,
    private val save: (String) -> Unit,
    private val reservedNames: () -> Set<String>,
    private val clientVersion: String = "1",
    private val now: () -> Long = System::currentTimeMillis,
    private val urlAllowed: (String) -> Boolean = ::connectorUrlAllowed,
) {
    private val mutex = Mutex()
    @Volatile private var file = ConnectorsFile()
    private val clients = ConcurrentHashMap<String, McpClient>()
    private val _changes = MutableStateFlow(0)

    /** Ticks whenever a connector or its tools change, for the settings screen. */
    val changes: StateFlow<Int> = _changes

    /** Reads the stored connectors and their last known tools; no network. */
    fun loadCached() {
        file = decodeConnectors(load())
        publish()
    }

    fun list(): List<Connector> = file.connectors

    /** A connector by its id or its name (case and accents ignored). */
    fun find(nameOrId: String): Connector? {
        val wanted = nameOrId.trim()
        if (wanted.isEmpty()) return null
        val slug = connectorSlug(wanted)
        return file.connectors.firstOrNull { it.id == wanted }
            ?: file.connectors.firstOrNull { it.name.equals(wanted, ignoreCase = true) }
            ?: file.connectors.firstOrNull { it.id == slug }
    }

    private fun connector(id: String): Connector? = file.connectors.firstOrNull { it.id == id }

    private fun publish() {
        val taken = HashSet(reservedNames())
        val tools = file.connectors.filter { it.enabled && !it.needsLogin }.flatMap { c ->
            c.tools.map { t ->
                val name = connectorToolName(c.id, t.name, taken)
                taken += name
                McpTool(c.id, c.name, t, name)
            }
        }
        InstalledConnectors.set(tools)
        _changes.value++
    }

    private suspend fun update(transform: (ConnectorsFile) -> ConnectorsFile) {
        mutex.withLock {
            val next = transform(file)
            withContext(Dispatchers.IO) { save(encodeConnectors(next)) }
            file = next
            publish()
        }
    }

    private suspend fun updateConnector(id: String, change: (Connector) -> Connector) =
        update { f -> f.copy(connectors = f.connectors.map { if (it.id == id) change(it) else it }) }

    /** Adds a connector (or replaces the one of the same name), then asks the server for its tools; a login is started if it wants one. */
    suspend fun add(name: String, url: String, token: String = ""): ConnectorOutcome {
        val address = url.trim()
        if (!urlAllowed(address)) return ConnectorOutcome("Adresse refusée : il faut l’adresse https:// du serveur MCP.")
        val label = name.trim().ifEmpty { java.net.URI(address).host.removePrefix("www.").substringBefore('.') }
        val id = connectorSlug(label)
        if (file.connectors.size >= MAX_CONNECTORS && connector(id) == null) return ConnectorOutcome("$MAX_CONNECTORS connecteurs au maximum : retirez-en un d’abord.")
        update { f -> f.copy(connectors = f.connectors.filter { it.id != id } + Connector(id = id, name = label, url = address, token = token.trim())) }
        clients.remove(id)
        return refresh(id, startLoginIfNeeded = true)
    }

    suspend fun remove(id: String) {
        update { f -> f.copy(connectors = f.connectors.filter { it.id != id }, pending = f.pending?.takeIf { it.connectorId != id }) }
        clients.remove(id)
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = updateConnector(id) { it.copy(enabled = enabled) }

    /** Asks the server again for its tools. */
    suspend fun refresh(id: String, startLoginIfNeeded: Boolean = false): ConnectorOutcome {
        val c = connector(id) ?: return ConnectorOutcome("Ce connecteur n’existe plus.")
        return try {
            val tools = withAuth(c) { it.listTools() }
            updateConnector(id) { it.copy(tools = tools, status = "", needsLogin = false, checkedAt = now()) }
            ConnectorOutcome("« ${c.name} » est connecté : ${tools.size} outil${if (tools.size > 1) "s" else ""}.")
        } catch (e: McpException) {
            if (e.unauthorized && c.token.isEmpty()) {
                updateConnector(id) { it.copy(needsLogin = true, status = "Connexion nécessaire", checkedAt = now()) }
                if (startLoginIfNeeded) startLogin(id, e.resourceMetadata)
                else ConnectorOutcome("« ${c.name} » demande une connexion : Réglages > Connecteurs > Se connecter.")
            } else {
                val status = if (e.unauthorized) "Jeton refusé par le serveur" else e.message.orEmpty()
                updateConnector(id) { it.copy(status = status, checkedAt = now()) }
                ConnectorOutcome("« ${c.name} » : $status")
            }
        } catch (e: IOException) {
            val status = "Injoignable (${e.message ?: e.javaClass.simpleName})"
            updateConnector(id) { it.copy(status = status, checkedAt = now()) }
            ConnectorOutcome("« ${c.name} » : $status")
        }
    }

    /** Every enabled connector's tools, asked again (at start-up; a server that does not answer keeps its last known tools). */
    suspend fun refreshAll() {
        file.connectors.filter { it.enabled && !it.needsLogin }.forEach { refresh(it.id) }
    }

    /** Registers Jarvis with the server if needed and gives the login page to open in the browser. */
    suspend fun startLogin(id: String, resourceMetadataHint: String? = null): ConnectorOutcome {
        val c = connector(id) ?: return ConnectorOutcome("Ce connecteur n’existe plus.")
        return try {
            val client = c.oauthClient?.takeIf { it.resource == canonicalResource(c.url) } ?: McpOAuth.register(http, c.url, resourceMetadataHint)
            val state = randomToken()
            val verifier = randomToken(48)
            update { f ->
                f.copy(
                    connectors = f.connectors.map { if (it.id == id) it.copy(oauthClient = client) else it },
                    pending = PendingLogin(id, state, verifier),
                )
            }
            ConnectorOutcome("Connectez-vous à « ${c.name} » dans le navigateur.", McpOAuth.authorizationUrl(client, state, verifier))
        } catch (e: IOException) {
            ConnectorOutcome("« ${c.name} » : ${e.message}")
        }
    }

    /** The browser came back from a login with [code] (or [error]). */
    suspend fun finishLogin(code: String?, state: String?, error: String?): ConnectorOutcome {
        val pending = file.pending ?: return ConnectorOutcome("Aucune connexion en cours.")
        if (state != pending.state) return ConnectorOutcome("Réponse de connexion inattendue : recommencez depuis les réglages.")
        update { it.copy(pending = null) }
        val c = connector(pending.connectorId) ?: return ConnectorOutcome("Ce connecteur n’existe plus.")
        if (code.isNullOrEmpty()) return ConnectorOutcome("Connexion à « ${c.name} » refusée${error?.let { " ($it)" }.orEmpty()}.")
        val client = c.oauthClient ?: return ConnectorOutcome("Connexion à « ${c.name} » interrompue : recommencez.")
        return try {
            val tokens = McpOAuth.exchangeCode(http, client, code, pending.verifier)
            updateConnector(c.id) { it.copy(oauth = tokens, needsLogin = false, status = "") }
            clients[c.id]?.reset()
            refresh(c.id)
        } catch (e: IOException) {
            ConnectorOutcome("Connexion à « ${c.name} » impossible : ${e.message}")
        }
    }

    /** Runs [tool] of the connector [id] and gives its answer as text for the model. */
    suspend fun call(id: String, tool: RemoteTool, args: JsonObject): String {
        val c = connector(id) ?: return "Ce connecteur n’existe plus."
        if (!c.enabled) return "Le connecteur « ${c.name} » est désactivé dans les réglages."
        return try {
            toolResultText(withAuth(c) { it.callTool(tool.name, reviveArguments(args, tool.inputSchema)) })
        } catch (e: McpException) {
            if (e.unauthorized) {
                if (c.token.isEmpty()) updateConnector(id) { it.copy(needsLogin = true, status = "Connexion nécessaire") }
                "Le connecteur « ${c.name} » demande une nouvelle connexion : Réglages > Connecteurs > Se connecter."
            } else {
                "Le connecteur « ${c.name} » a échoué : ${e.message}"
            }
        } catch (e: IOException) {
            "Le connecteur « ${c.name} » est injoignable : ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun client(c: Connector): McpClient =
        clients.getOrPut(c.id) { McpClient(http, c.url, { bearer(c.id) }, clientVersion) }

    /** The pasted token first, else the login's. */
    private fun bearer(id: String): String? {
        val c = connector(id) ?: return null
        return c.token.ifEmpty { c.oauth?.accessToken }
    }

    /** Runs [block], with the login's token renewed before it expires and once more if the server refuses it. */
    private suspend fun <T> withAuth(c: Connector, block: suspend (McpClient) -> T): T {
        val client = client(c)
        if (c.token.isEmpty()) {
            val tokens = c.oauth
            if (tokens != null && tokens.expiresAt != 0L && tokens.expiresAt - now() < 60_000) renew(c.id)
        }
        return try {
            block(client)
        } catch (e: McpException) {
            if (!e.unauthorized || c.token.isNotEmpty() || !renew(c.id)) throw e
            client.reset()
            block(client)
        }
    }

    private suspend fun renew(id: String): Boolean {
        val c = connector(id) ?: return false
        val tokens = c.oauth ?: return false
        val oauthClient = c.oauthClient ?: return false
        val renewed = McpOAuth.refresh(http, oauthClient, tokens) ?: return false
        updateConnector(id) { it.copy(oauth = renewed) }
        return true
    }

    companion object {
        const val MAX_CONNECTORS = 20
    }
}
