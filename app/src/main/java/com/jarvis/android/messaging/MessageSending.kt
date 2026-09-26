package com.jarvis.android.messaging

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jarvis.android.JarvisContainer
import com.jarvis.android.R
import com.jarvis.android.device.ActionResult
import com.jarvis.android.device.JarvisAccessibilityService
import com.jarvis.android.device.MESSAGING_PACKAGES
import com.jarvis.android.device.isSendLabel
import com.jarvis.android.i18n.tr
import com.jarvis.android.i18n.trf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/*
 * Messages that go out on the user's word ("envoie"), when they switched that on in the settings (it is off by default).
 *
 * What stays in place: only a contact of the phone can be written to (the model never types a number), the text is the one asked for, a
 * few messages only are allowed in a short time, every one is announced by a notification and written in a history, and the model is told
 * that a text read in a mail, a page or a notification is data and never a request to send.
 */

/** What happened when a message was sent. */
internal sealed interface SendOutcome {
    data class Sent(val via: String) : SendOutcome
    /** [mayHaveGone]: the message may have been sent all the same (no answer in time), so it keeps its place in the allowance. */
    data class Failed(val reason: String, val mayHaveGone: Boolean = false) : SendOutcome
}

/** At most [max] messages in [windowMs]: a runaway model, or a hostile text it read, cannot spam a contact list or empty the phone's credit. */
internal class SendLimiter(private val max: Int = 5, private val windowMs: Long = 10 * 60_000L) {
    private val times = ArrayDeque<Long>()

    @Synchronized fun tryAcquire(now: Long = System.currentTimeMillis()): Boolean {
        while (times.isNotEmpty() && now - times.first() >= windowMs) times.removeFirst()
        if (times.size >= max) return false
        times.addLast(now)
        return true
    }

    /** Minutes to wait before the next message is allowed, rounded up (0 when one is allowed now). */
    @Synchronized fun minutesToWait(now: Long = System.currentTimeMillis()): Int {
        while (times.isNotEmpty() && now - times.first() >= windowMs) times.removeFirst()
        if (times.size < max) return 0
        return (((times.first() + windowMs - now) + 59_999) / 60_000).toInt().coerceAtLeast(1)
    }

    /** Gives back the place of the last message: it did not go out, so it must not count against the allowance. */
    @Synchronized fun giveBack() {
        if (times.isNotEmpty()) times.removeLast()
    }

    @Synchronized fun reset() = times.clear()
}

internal object MessageLimiter {
    val shared = SendLimiter()
}

private val DIAL_CODES = mapOf(
    "FR" to "33", "BE" to "32", "CH" to "41", "LU" to "352", "DE" to "49", "ES" to "34", "IT" to "39", "PT" to "351", "GB" to "44",
    "IE" to "353", "NL" to "31", "US" to "1", "CA" to "1", "MA" to "212", "DZ" to "213", "TN" to "216", "SN" to "221", "CI" to "225",
    "CM" to "237", "BR" to "55",
)

/**
 * A phone number as WhatsApp's links want it: digits only, with the country code and no leading zero or plus. A national number (0612345678)
 * needs the [region] of the phone; null when it cannot be worked out.
 */
internal fun internationalDigits(number: String, region: String?): String? {
    val raw = number.trim()
    val digits = raw.filter { it.isDigit() }
    if (digits.length < 6) return null
    return when {
        raw.startsWith("+") -> digits
        raw.startsWith("00") -> digits.drop(2)
        digits.startsWith("0") -> DIAL_CODES[region?.uppercase(Locale.ROOT)]?.let { it + digits.drop(1) }
        else -> digits
    }
}

internal fun smsFailureMessage(code: Int): String = when (code) {
    SmsManager.RESULT_ERROR_RADIO_OFF -> tr("Le mode avion est activé : le SMS n’est pas parti.")
    SmsManager.RESULT_ERROR_NO_SERVICE -> tr("Pas de réseau mobile : le SMS n’est pas parti.")
    SmsManager.RESULT_ERROR_NULL_PDU -> tr("Le SMS est invalide : il n’est pas parti.")
    else -> trf("Android a refusé d’envoyer le SMS (code {0}).", code)
}

private val smsCounter = AtomicInteger()

/** Sends [text] by SMS straight from the phone (no screen involved). Needs the SEND_SMS permission. */
internal suspend fun sendSms(context: Context, number: String, text: String): SendOutcome = withContext(Dispatchers.Default) {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
        return@withContext SendOutcome.Failed(tr("L’autorisation d’envoyer des SMS n’est pas accordée : elle se donne dans les réglages de Jarvis (carte Contrôle du téléphone)."))
    }
    val sms = try {
        if (Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
    } catch (_: Exception) {
        null
    } ?: return@withContext SendOutcome.Failed(tr("Ce téléphone ne sait pas envoyer de SMS."))
    val parts = sms.divideMessage(text)
    val action = "com.jarvis.android.SMS_SENT." + smsCounter.incrementAndGet()
    // The reports come on a thread of their own: they must not wait for the main thread, which may be busy.
    val thread = android.os.HandlerThread("SmsResult").also { it.start() }
    val outcome = try { kotlinx.coroutines.withTimeoutOrNull(30_000L) { suspendCancellableCoroutine<Int> { cont ->
        var remaining = parts.size
        var first = Activity.RESULT_OK
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (resultCode != Activity.RESULT_OK && first == Activity.RESULT_OK) first = resultCode
                if (--remaining == 0) {
                    try { context.unregisterReceiver(this) } catch (_: Exception) { }
                    if (cont.isActive) cont.resume(first)
                }
            }
        }
        val filter = IntentFilter(action)
        val handler = android.os.Handler(thread.looper)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, null, handler, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") context.registerReceiver(receiver, filter, null, handler)
        cont.invokeOnCancellation { try { context.unregisterReceiver(receiver) } catch (_: Exception) { } }
        val sent = ArrayList<PendingIntent>(parts.size)
        for (i in parts.indices) {
            sent += PendingIntent.getBroadcast(context, i, Intent(action).setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE)
        }
        try {
            sms.sendMultipartTextMessage(number, null, parts, sent, null)
        } catch (e: Exception) {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) { }
            if (cont.isActive) cont.resume(SmsManager.RESULT_ERROR_GENERIC_FAILURE)
        }
    } } } finally { thread.quitSafely() }
        ?: return@withContext SendOutcome.Failed(tr("Le réseau n’a pas confirmé l’envoi du SMS dans les 30 secondes : il est peut-être parti, vérifiez dans l’application de SMS."), mayHaveGone = true)
    if (outcome == Activity.RESULT_OK) SendOutcome.Sent(tr("SMS")) else SendOutcome.Failed(smsFailureMessage(outcome))
}

/**
 * Opens [intent] (a conversation with the text already in the box) and presses the send button of the messaging app through the
 * accessibility service. The button is only looked for in a known messaging app, and only a button that says "Envoyer" or "Send" is pressed.
 */
internal suspend fun sendThroughScreen(ctx: JarvisContainer, intent: Intent, appName: String): SendOutcome {
    val service = JarvisAccessibilityService.instance
        ?: return SendOutcome.Failed(tr("Le contrôle du téléphone (service d’accessibilité) doit être activé pour envoyer par cette application."))
    try {
        ctx.appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        return SendOutcome.Failed(trf("{0} n’est pas installé ou ne peut pas s’ouvrir.", appName))
    }
    val deadline = System.currentTimeMillis() + 15_000
    while (System.currentTimeMillis() < deadline) {
        delay(500)
        val snapshot = service.readScreen() ?: continue
        if (snapshot.packageName !in MESSAGING_PACKAGES) continue
        val button = snapshot.elements.firstOrNull { it.clickable && isSendLabel(it.label) } ?: continue
        return when (service.tap(button.index)) {
            is ActionResult.Done -> SendOutcome.Sent(appName)
            is ActionResult.Failed -> SendOutcome.Failed(trf("Le bouton Envoyer de {0} n’a pas répondu : le message est prêt dans l’application, appuyez vous-même sur envoyer.", appName))
        }
    }
    return SendOutcome.Failed(trf("Je n’ai pas trouvé le bouton Envoyer de {0} : le message est prêt dans l’application, appuyez vous-même sur envoyer.", appName))
}

internal const val MESSENGER_PACKAGE = "com.facebook.orca"

/** What Messenger's "send to" list shows for the person asked for. */
internal sealed interface MessengerPick {
    /** One row matches: [sendIndex] is its own "Envoyer" button. */
    data class Found(val sendIndex: Int, val name: String) : MessengerPick
    /** Several people match: never guess who gets the message. */
    data class Ambiguous(val names: List<String>) : MessengerPick
    /** Nobody matches (yet: the search may not have run); [visible] are the names on screen, to say what was there. */
    data class None(val visible: List<String>) : MessengerPick
}

private fun centreY(e: com.jarvis.android.device.ScreenElement) = (e.top + e.bottom) / 2

/** True when [b]'s vertical middle lies within [n]'s row (with some slack: a name and its button are rarely the same height). */
private fun sameRow(n: com.jarvis.android.device.ScreenElement, b: com.jarvis.android.device.ScreenElement): Boolean {
    val slack = maxOf(24, (n.bottom - n.top) / 2)
    return centreY(b) in (n.top - slack)..(n.bottom + slack)
}

/**
 * In Messenger's "send to" list — one row per person, their name and an "Envoyer" button — the button on the row of [person]
 * (every word of the name, accents and case ignored). A button whose own label names the person ("Envoyer à Paul") counts too.
 * The first "Envoyer" on the screen is NEVER taken blindly: that would send to whoever Messenger lists first.
 */
internal fun pickMessengerSend(elements: List<com.jarvis.android.device.ScreenElement>, person: String): MessengerPick {
    val wanted = com.jarvis.android.offline.normalize(person)
    val words = wanted.split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return MessengerPick.None(emptyList())
    fun names(label: String) = com.jarvis.android.offline.normalize(label).let { l -> words.all { it in l } }
    val buttons = elements.filter { it.clickable && isSendLabel(it.label) }
    val direct = buttons.filter { names(it.label) }.map { it to it.label }
    val byRow = elements.filter { !it.editable && !isSendLabel(it.label) && it.label.isNotBlank() && names(it.label) }
        .mapNotNull { n -> buttons.firstOrNull { sameRow(n, it) }?.let { it to n.label } }
    val pairs = (direct + byRow).distinctBy { it.first.index }
    return when {
        pairs.size == 1 -> MessengerPick.Found(pairs[0].first.index, pairs[0].second)
        pairs.size > 1 -> {
            // "Paul" with both "Paul" and "Paul Durand" listed: an exact name settles it, anything else is asked.
            val exact = pairs.filter { com.jarvis.android.offline.normalize(it.second) == wanted }
            if (exact.size == 1) MessengerPick.Found(exact[0].first.index, exact[0].second)
            else MessengerPick.Ambiguous(pairs.map { it.second }.distinct())
        }
        else -> MessengerPick.None(
            elements.filter { e -> !e.clickable && !e.editable && e.label.isNotBlank() && !isSendLabel(e.label) && buttons.any { sameRow(e, it) } }
                .map { it.label.take(40) }.distinct().take(8),
        )
    }
}

/** Messenger marks a row once its message went: "Envoyé", "Sent", or an "Annuler"/"Undo" button in place of "Envoyer". */
internal fun messengerConfirmsSent(elements: List<com.jarvis.android.device.ScreenElement>, rowName: String): Boolean {
    val row = elements.firstOrNull { com.jarvis.android.offline.normalize(it.label) == com.jarvis.android.offline.normalize(rowName) }
    val marks = setOf("envoye", "sent", "annuler", "undo")
    return elements.any { e ->
        com.jarvis.android.offline.normalize(e.label) in marks && (row == null || sameRow(row, e))
    }
}

private fun isSearchField(e: com.jarvis.android.device.ScreenElement) =
    e.editable && com.jarvis.android.offline.normalize(e.label).let { "recherch" in it || "search" in it }

/**
 * Sends [text] to [person] through Messenger's "send to" screen: opens it with the text, types the name in its search field,
 * presses the "Envoyer" button on that person's row, and checks that Messenger marks it sent. [person] is the name as Messenger
 * shows it (a Facebook name), not a phone contact: Messenger cannot be opened on a conversation from a phone number.
 */
internal suspend fun sendThroughMessenger(ctx: JarvisContainer, person: String, text: String): SendOutcome {
    val service = JarvisAccessibilityService.instance
        ?: return SendOutcome.Failed(tr("Le contrôle du téléphone (service d’accessibilité) doit être activé pour envoyer par Messenger."))
    try {
        ctx.appContext.startActivity(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                .setPackage(MESSENGER_PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: Exception) {
        return SendOutcome.Failed(tr("Messenger n’est pas installé ou ne peut pas s’ouvrir."))
    }
    var searched = false
    var visible = emptyList<String>()
    val deadline = System.currentTimeMillis() + 20_000
    while (System.currentTimeMillis() < deadline) {
        delay(700)
        val snapshot = service.readScreen() ?: continue
        if (snapshot.packageName != MESSENGER_PACKAGE) continue
        when (val pick = pickMessengerSend(snapshot.elements, person)) {
            is MessengerPick.Ambiguous -> return SendOutcome.Failed(
                trf("Plusieurs personnes correspondent dans Messenger ({0}) : demandez le nom complet. Rien n’est envoyé.", pick.names.joinToString(", ")),
            )
            is MessengerPick.Found -> {
                if (service.tap(pick.sendIndex) !is ActionResult.Done) {
                    return SendOutcome.Failed(tr("Le bouton Envoyer de Messenger n’a pas répondu. Rien n’est envoyé."))
                }
                // Messenger turns the button into "Envoyé" / "Annuler": that is the only proof it went.
                repeat(4) {
                    delay(600)
                    val after = service.readScreen()
                    if (after != null && messengerConfirmsSent(after.elements, pick.name)) {
                        after.elements.firstOrNull { it.clickable && com.jarvis.android.offline.normalize(it.label) in setOf("termine", "done", "ok") }
                            ?.let { service.tap(it.index) }
                        return SendOutcome.Sent("Messenger")
                    }
                }
                return SendOutcome.Failed(
                    trf("J’ai appuyé sur Envoyer à côté de « {0} » dans Messenger, mais Messenger n’a pas indiqué que c’était parti : vérifiez dans l’application.", pick.name),
                    mayHaveGone = true,
                )
            }
            is MessengerPick.None -> {
                visible = pick.visible.ifEmpty { visible }
                if (!searched) {
                    snapshot.elements.firstOrNull { isSearchField(it) }?.let { field ->
                        searched = service.type(field.index, person) is ActionResult.Done
                    }
                }
            }
        }
    }
    val seen = if (visible.isEmpty()) "" else trf(" Noms visibles : {0}.", visible.joinToString(", "))
    return SendOutcome.Failed(
        trf("Je n’ai pas trouvé « {0} » dans la liste d’envoi de Messenger.{1} Le message est prêt dans Messenger : choisissez la personne et appuyez sur Envoyer.", person, seen),
    )
}

/** WhatsApp's own link to a conversation with a number, the text already typed. */
internal fun whatsAppIntent(digits: String, text: String): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits?text=" + Uri.encode(text))).setPackage("com.whatsapp")

/** The system's SMS composer for a number, the text already typed. */
internal fun smsComposerIntent(number: String, text: String): Intent =
    Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)).putExtra("sms_body", text)

private const val RECEIPT_CHANNEL = "jarvis_sent_messages"

/** A notification for each message that went out, with the text: the user always sees what was sent in their name. */
internal fun notifyReceipt(context: Context, to: String, via: String, text: String) {
    try {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(RECEIPT_CHANNEL, tr("Messages envoyés par Jarvis"), NotificationManager.IMPORTANCE_DEFAULT))
        manager.notify(
            (System.currentTimeMillis() % 100_000).toInt() + 7600,
            NotificationCompat.Builder(context, RECEIPT_CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(trf("Message envoyé à {0} ({1})", to, via))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build(),
        )
    } catch (_: SecurityException) {
        // notifications not allowed: the history in the settings still has it
    }
}
