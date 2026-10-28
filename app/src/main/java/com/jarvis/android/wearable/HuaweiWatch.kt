package com.jarvis.android.wearable

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.huawei.wearengine.HiWear
import com.huawei.wearengine.auth.AuthCallback
import com.huawei.wearengine.auth.Permission
import com.huawei.wearengine.device.Device
import com.huawei.wearengine.p2p.Message
import com.huawei.wearengine.p2p.Receiver
import com.huawei.wearengine.p2p.SendCallback
import com.jarvis.android.JarvisApp
import com.jarvis.android.car.CarChat
import com.jarvis.android.i18n.tr
import com.jarvis.android.i18n.trf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.security.MessageDigest

/*
 * Jarvis on a Huawei watch (Watch GT 4 and the other HarmonyOS "lite" watches): they cannot install an Android app, but a small watch app
 * (watch/ in the repository) talks to this phone through Huawei's Wear Engine, which goes through the Huawei Health app. The watch sends a
 * button or a question (WatchProtocol.kt), Jarvis answers with the phone's text chat (every tool, the memory, the same history), and the
 * answer goes back to the watch's screen. Wear Engine only answers once Huawei has granted the service to this app (its package and signing
 * certificate), so everything here is quiet when it is not there: no Huawei Health, no grant, no watch, nothing happens.
 */

internal object HuaweiWatch {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var device: Device? = null
    private var linking: Job? = null

    private val _status = MutableStateFlow(tr("Pas encore reliée."))
    /** Where the link stands, in words, for the settings card. */
    val status: StateFlow<String> = _status

    /** Starts looking for the watch when the setting is on; checks again every few minutes until it is linked. */
    fun start(context: Context) {
        val app = context.applicationContext
        if (linking?.isActive == true) return
        linking = scope.launch {
            val config = (app as JarvisApp).container.configStore
            if (!config.huaweiWatchEnabled.first()) return@launch
            while (isActive && device == null) {
                link(app)
                if (device == null) delay(5 * 60_000L)
            }
        }
    }

    /** Stops listening to the watch (the setting was turned off). */
    fun stop(context: Context) {
        linking?.cancel()
        linking = null
        if (device == null) return
        device = null
        try {
            HiWear.getP2pClient(context.applicationContext).unregisterReceiver(receiver(context.applicationContext))
        } catch (_: Throwable) {
        }
        _status.value = tr("Désactivée.")
    }

    /** Asks Huawei Health for the right to see the watches (its own screen, once), then links. */
    fun requestPermission(context: Context) {
        val app = context.applicationContext
        try {
            HiWear.getAuthClient(app).requestPermission(object : AuthCallback {
                override fun onOk(grantedPermissions: Array<out Permission>?) {
                    device = null
                    linking?.cancel()
                    linking = null
                    start(app)
                }

                override fun onCancel() {
                    _status.value = tr("Autorisation refusée dans Huawei Santé.")
                }
            }, Permission.DEVICE_MANAGER)
                .addOnFailureListener { e -> _status.value = failure(e) }
        } catch (e: Throwable) {
            _status.value = failure(e)
        }
    }

    private suspend fun link(app: Context) {
        val config = (app as JarvisApp).container.configStore
        val pkg = config.huaweiWatchPackage.first()
        val fingerprint = config.huaweiWatchFingerprint.first()
        if (fingerprint.isBlank()) {
            _status.value = tr("Collez l’empreinte de l’appli montre pour la relier.")
            return
        }
        try {
            HiWear.getDeviceClient(app).bondedDevices
                .addOnSuccessListener { devices ->
                    val watch = devices?.firstOrNull { it.isConnected }
                    if (watch == null) {
                        _status.value = tr("Aucune montre connectée à Huawei Santé.")
                        return@addOnSuccessListener
                    }
                    val p2p = HiWear.getP2pClient(app)
                    p2p.setPeerPkgName(pkg)
                    p2p.setPeerFingerPrint(fingerprint)
                    p2p.registerReceiver(watch, receiver(app))
                        .addOnSuccessListener {
                            device = watch
                            _status.value = trf("Reliée à {0}.", watch.name ?: tr("la montre"))
                        }
                        .addOnFailureListener { e -> _status.value = failure(e) }
                }
                .addOnFailureListener { e -> _status.value = failure(e) }
        } catch (e: Throwable) {
            _status.value = failure(e)
        }
    }

    private var receiverInstance: Receiver? = null

    @Synchronized
    private fun receiver(app: Context): Receiver = receiverInstance ?: Receiver { message ->
        val text = message?.data?.toString(Charsets.UTF_8).orEmpty()
        when (val request = parseWatchMessage(text)) {
            WatchRequest.Hello -> send(app, "OK")
            is WatchRequest.Ask -> scope.launch {
                send(app, "W")
                val answer = CarChat.ask(app, watchQuestion(request.question))
                watchAnswerMessages(answer).forEach { send(app, it) }
            }
            null -> Unit
        }
    }.also { receiverInstance = it }

    private fun send(app: Context, text: String) {
        val watch = device ?: return
        try {
            val builder = Message.Builder()
            builder.setPayload(text.toByteArray(Charsets.UTF_8))
            val message = builder.build()
            HiWear.getP2pClient(app).send(watch, message, object : SendCallback {
                override fun onSendResult(resultCode: Int) = Unit
                override fun onSendProgress(progress: Long) = Unit
            })
        } catch (_: Throwable) {
        }
    }

    private fun failure(e: Throwable): String {
        val why = e.message.orEmpty()
        return when {
            why.contains("unauthorized", ignoreCase = true) || why.contains("scope", ignoreCase = true) ->
                tr("Huawei n’a pas encore accordé Wear Engine à Jarvis (ou le paquet, l’empreinte ou l’App ID ne correspondent pas).")
            else -> tr("Huawei Santé ne répond pas (installé et à jour ?).") + if (why.isNotBlank()) " ($why)" else ""
        }
    }
}

/** The SHA-256 of the certificate this app is signed with, as the watch app wants it (hex, upper case, no colons), or null. */
internal fun phoneSigningSha256(context: Context): String? = try {
    val pm = context.packageManager
    val signatures = if (Build.VERSION.SDK_INT >= 28) {
        pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
    } else {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
    }
    signatures?.firstOrNull()?.toByteArray()?.let { hexSha256(it) }
} catch (_: Exception) {
    null
}

internal fun hexSha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02X".format(it) }
