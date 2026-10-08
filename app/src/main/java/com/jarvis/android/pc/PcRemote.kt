package com.jarvis.android.pc

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.X509TrustManager

/**
 * Drives Jarvis on the PC (Jarvis 2.0, or Mark-LIV) from the phone (protocol in PcLink.kt). The pairing lives in [load]/[save]/[clear], which the caller backs
 * with encrypted storage. Every answer is a sentence for the model to say, never an exception.
 */
class PcRemote(
    private val load: () -> PcPairing?,
    private val save: (PcPairing) -> Unit,
    private val clear: () -> Unit,
) {
    private val random = SecureRandom()

    val isPaired: Boolean get() = load() != null

    /** Pairs with the PC from the QR link or an address plus the key it shows; trusts the certificate it presents now, and only that one afterwards. */
    suspend fun pair(address: String, code: String = ""): String = withContext(Dispatchers.IO) {
        val target = pairingTarget(address, code)
            ?: return@withContext "Il faut l'adresse du PC (par exemple 192.168.1.20) et le code à 6 caractères affiché sur le PC (Jarvis 2.0 : Poste de Contrôle PC → « Appairer un téléphone »), ou scanner son QR code."
        if (target.baseUrl.startsWith("http://")) {
            return@withContext "Ce PC répond en HTTP simple, qu'Android refuse : il faut l'adresse en https (Jarvis 2.0 sert toujours en HTTPS ; pour Mark-LIV, installez le paquet cryptography)."
        }
        var seen: X509Certificate? = null
        val client = client(trust = { chain -> seen = chain.firstOrNull() })
        try {
            client.newCall(Request.Builder().url(target.autoLoginUrl).build()).execute().use { r ->
                val html = r.body?.string().orEmpty()
                val login = parseAutoLogin(html)
                    ?: return@withContext "Code refusé ou expiré : sur le PC, redemandez un code (Jarvis 2.0 : « Appairer un téléphone ») (il vaut 10 minutes et ne sert qu'une fois)."
                val cert = seen ?: return@withContext "Le PC n'a pas présenté de certificat : appairage annulé."
                save(PcPairing(target.baseUrl, sha256Hex(cert.encoded), login.sessionKey, login.token, login.deviceToken))
                "PC appairé (${target.baseUrl.substringAfter("://")}). Je peux maintenant lui transmettre des ordres."
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            "PC injoignable à ${target.baseUrl.substringAfter("://")} : vérifiez que Jarvis 2.0 est lancé avec le contrôle à distance activé, " +
                if (isTailnetUrl(target.baseUrl)) TAILSCALE_HINT else "et que le téléphone est sur le même réseau Wi-Fi (ou, pour y accéder de partout, passez Jarvis 2.0 en « accès à distance » avec Tailscale)."
        }
    }

    fun forget(): String {
        clear()
        return "PC oublié : il faudra un nouveau code pour le reconnecter."
    }

    /** Whether the paired PC answers and still knows this phone. */
    suspend fun status(): String = withContext(Dispatchers.IO) {
        val p = load() ?: return@withContext NOT_PAIRED
        try {
            when (authorized(p)) {
                null -> NEEDS_PAIRING
                else -> "Le PC répond et ce téléphone est reconnu (${p.baseUrl.substringAfter("://")})."
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            failure(e, p)
        }
    }

    /**
     * Sends [instruction] to Jarvis PC as if typed there, and returns what it said back. Its answer is read from the dashboard
     * WebSocket, opened before the command so nothing is missed; what arrives before the command is old history and is dropped.
     */
    suspend fun command(instruction: String, waitMs: Long = 35_000): String = withContext(Dispatchers.IO) {
        val p0 = load() ?: return@withContext NOT_PAIRED
        val text = instruction.trim().take(2_000)
        if (text.isEmpty()) return@withContext "Quel ordre faut-il transmettre au PC ?"
        try {
            val p = authorized(p0) ?: return@withContext NEEDS_PAIRING
            val http = client(pin = p.certSha256)
            val events = Channel<PcEvent>(Channel.UNLIMITED)
            val open = CompletableDeferred<Boolean>()
            val listening = AtomicBoolean(false)
            val ws = http.newWebSocket(
                Request.Builder().url(p.baseUrl.replaceFirst("https://", "wss://") + "/ws?token=" + p.token).build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) { open.complete(true) }
                    override fun onMessage(webSocket: WebSocket, text: String) { if (listening.get()) parsePcEvent(text)?.let { events.trySend(it) } }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { open.complete(false); events.close() }
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { open.complete(false); events.close() }
                },
            )
            try {
                val opened = withTimeoutOrNull(5_000) { open.await() } == true
                if (opened) {
                    delay(400) // the last 50 messages are replayed on connect
                    listening.set(true)
                }
                val iv = ByteArray(16).also(random::nextBytes)
                val sent = http.newCall(
                    Request.Builder().url(p.baseUrl + "/api/command")
                        .header("Authorization", "Bearer ${p.token}")
                        .post(commandBody(p.sessionKey, text, iv).toRequestBody(JSON))
                        .build(),
                ).execute().use { it.code }
                when {
                    sent == 401 -> return@withContext NEEDS_PAIRING
                    sent !in 200..299 -> return@withContext "Le PC a refusé l'ordre (code $sent)."
                    !opened -> return@withContext "Ordre transmis au PC (sa réponse n'a pas pu être lue)."
                }
                val said = mutableListOf<String>()
                withTimeoutOrNull(waitMs) {
                    while (true) {
                        // after a first sentence, a pause means it has finished (an acknowledgement is often followed by the result)
                        val next = if (said.isEmpty()) events.receiveCatching().getOrNull()
                        else withTimeoutOrNull(QUIET_MS) { events.receiveCatching().getOrNull() }
                        when (next) {
                            null -> break
                            is PcEvent.Said -> said += next.text
                            is PcEvent.State -> Unit
                        }
                    }
                }
                if (said.isEmpty()) "Ordre transmis au PC ; Jarvis n'y a encore rien répondu." else "Jarvis sur le PC : " + joinAnswer(said)
            } finally {
                ws.close(1000, null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            failure(e, p0)
        }
    }

    /**
     * One action for the Playwright browser Jarvis 2.0 runs on the PC (electron/browserControl.cjs): open, read, click, fill…
     * The page comes back read, or a screenshot for `look`. A navigation may take a while, hence the longer wait.
     */
    suspend fun browser(action: JsonObject): PcBrowserReply = withContext(Dispatchers.IO) {
        val p0 = load() ?: return@withContext PcBrowserReply(false, NOT_PAIRED)
        try {
            val p = authorized(p0) ?: return@withContext PcBrowserReply(false, NEEDS_PAIRING)
            val iv = ByteArray(16).also(random::nextBytes)
            client(pin = p.certSha256, readSeconds = 60).newCall(
                Request.Builder().url(p.baseUrl + "/api/browser")
                    .header("Authorization", "Bearer ${p.token}")
                    .post(browserBody(p.sessionKey, action, iv).toRequestBody(JSON))
                    .build(),
            ).execute().use { r ->
                when (r.code) {
                    401 -> PcBrowserReply(false, NEEDS_PAIRING)
                    404, 501 -> PcBrowserReply(false, "Jarvis 2.0 sur le PC ne sait pas encore piloter de navigateur : mettez-le à jour (version 2.0.33 ou plus).")
                    in 200..299 -> parseBrowserReply(r.body?.string().orEmpty()) ?: PcBrowserReply(false, "Réponse illisible du navigateur du PC.")
                    else -> PcBrowserReply(false, "Le PC a refusé l'action sur le navigateur (code ${r.code}).")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: java.net.SocketTimeoutException) {
            PcBrowserReply(false, "Le navigateur du PC n'a pas répondu à temps (page trop lente ?). Relisez la page pour voir où il en est.")
        } catch (e: IOException) {
            PcBrowserReply(false, failure(e, p0))
        }
    }

    /**
     * A picture made by the generator on the PC (Jarvis 2.0 2.0.34+, electron/imageGen.cjs), or an `install` / `status` request
     * about it. Making one takes up to a few minutes (ComfyUI may start first), hence the long wait.
     */
    suspend fun image(request: JsonObject): PcImageReply = withContext(Dispatchers.IO) {
        val p0 = load() ?: return@withContext PcImageReply(false, NOT_PAIRED)
        try {
            val p = authorized(p0) ?: return@withContext PcImageReply(false, NEEDS_PAIRING)
            val iv = ByteArray(16).also(random::nextBytes)
            client(pin = p.certSha256, readSeconds = 240).newCall(
                Request.Builder().url(p.baseUrl + "/api/image")
                    .header("Authorization", "Bearer ${p.token}")
                    .post(browserBody(p.sessionKey, request, iv).toRequestBody(JSON))
                    .build(),
            ).execute().use { r ->
                when (r.code) {
                    401 -> PcImageReply(false, NEEDS_PAIRING)
                    404, 501 -> PcImageReply(false, "Jarvis 2.0 sur le PC ne sait pas encore créer d'images : mettez-le à jour (version 2.0.34 ou plus).")
                    in 200..299 -> parseImageReply(r.body?.string().orEmpty()) ?: PcImageReply(false, "Réponse illisible du générateur d'images du PC.")
                    else -> PcImageReply(false, "Le PC a refusé la demande d'image (code ${r.code}).")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: java.net.SocketTimeoutException) {
            PcImageReply(false, "Le PC n'a pas fini l'image à temps. Réessayez (le premier démarrage du générateur est plus long).")
        } catch (e: IOException) {
            PcImageReply(false, failure(e, p0))
        }
    }

    /** The pairing with a working auth token, refreshed with the device token when Jarvis PC forgot the old one; null when it forgot the device too. */
    private fun authorized(p: PcPairing): PcPairing? {
        val http = client(pin = p.certSha256)
        val code = http.newCall(Request.Builder().url(p.baseUrl + "/api/files").header("Authorization", "Bearer ${p.token}").build())
            .execute().use { it.code }
        if (code != 401) return p
        val body = buildJsonObject { put("device_token", p.deviceToken) }.toString().toRequestBody(JSON)
        http.newCall(Request.Builder().url(p.baseUrl + "/api/device-login").post(body).build()).execute().use { r ->
            if (!r.isSuccessful) return null
            val (token, key) = parseDeviceLogin(r.body?.string().orEmpty()) ?: return null
            return p.copy(token = token, sessionKey = key ?: p.sessionKey).also(save)
        }
    }

    private fun failure(e: IOException, p: PcPairing): String =
        if (e is SSLHandshakeException || e.cause is CertificateException) {
            "Le PC à ${p.baseUrl.substringAfter("://")} ne présente plus le certificat appairé : connexion refusée par sécurité. Si Jarvis a été réinstallé sur le PC, appairez-le de nouveau."
        } else if (isTailnetUrl(p.baseUrl)) {
            "PC injoignable (${p.baseUrl.substringAfter("://")}) : il est éteint, Jarvis 2.0 est fermé ou son contrôle à distance est désactivé, ou " + TAILSCALE_HINT
        } else {
            "PC injoignable (${p.baseUrl.substringAfter("://")}) : il est éteint, Jarvis 2.0 est fermé ou son contrôle à distance est désactivé, ou le téléphone n'est pas sur le même réseau. Si son adresse IP a changé, ou si Jarvis 2.0 est passé en « accès à distance » (Tailscale), appairez-le de nouveau."
        }

    /**
     * HTTPS client for the PC's self-made certificate. With [pin], only the certificate with that SHA-256 passes (the host
     * name is not checked: the certificate names the IP it had when made, and the pin is the stronger check). Without one
     * (pairing), whatever is presented is handed to [trust] to be pinned.
     */
    private fun client(pin: String? = null, readSeconds: Long = 20, trust: (Array<X509Certificate>) -> Unit = {}): OkHttpClient {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw CertificateException()
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val leaf = chain.firstOrNull() ?: throw CertificateException("no certificate")
                if (pin != null && !sha256Hex(leaf.encoded).equals(pin, ignoreCase = true)) throw CertificateException("certificate changed")
                trust(chain)
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), random) }
        return OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, tm)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(readSeconds, TimeUnit.SECONDS)
            .build()
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        const val QUIET_MS = 6_000L
        const val TAILSCALE_HINT =
            "Tailscale n'est pas connecté : ouvrez l'appli Tailscale sur le téléphone et activez-la (même compte que sur le PC), et vérifiez qu'elle l'est aussi sur le PC."
        const val NOT_PAIRED =
            "Aucun PC appairé. Sur le PC, dans Jarvis 2.0 : Poste de Contrôle PC → « Appairer un téléphone », puis dans Jarvis Android : Réglages > Jarvis PC, scannez le QR code (ou dites l'adresse et le code affichés)."
        const val NEEDS_PAIRING =
            "Le PC ne reconnaît plus ce téléphone (téléphones oubliés sur le PC) : sur le PC, « Appairer un téléphone », puis scannez de nouveau le QR code dans Réglages > Jarvis PC."
    }
}
