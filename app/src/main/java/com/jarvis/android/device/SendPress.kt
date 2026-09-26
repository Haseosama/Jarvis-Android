package com.jarvis.android.device

import com.jarvis.android.offline.normalize
import kotlinx.coroutines.delay

/*
 * Pressing "send" in a messaging app and knowing whether the message really went. An accessibility click can be answered
 * "done" by the app and do nothing (Messenger does this at times), the button can still be the empty-field one (Messenger's
 * thumbs-up turns into "Envoyer" a moment after the text arrives), and a snapshot taken before typing points at a button
 * that no longer exists. So: the button is looked up again on the screen as it is now, pressed, and the message counts as
 * sent only once the compose field no longer holds it; otherwise it is pressed again with a real finger tap, and checked again.
 */

internal sealed interface SendPress {
    /** Went: the compose field was emptied. [verified] false when there was no filled field to watch (nothing to check against). */
    data class Sent(val verified: Boolean) : SendPress
    data class NotSent(val reason: String) : SendPress
}

/** The text waiting in the compose field (the filled input nearest the bottom of the screen), or null. */
internal fun draftInField(elements: List<ScreenElement>): String? =
    elements.filter { it.editable && it.filled && !it.password && it.label.isNotBlank() }.maxByOrNull { it.bottom }?.label

/** True when [draft] is still waiting in a compose field. */
internal fun stillInField(elements: List<ScreenElement>, draft: String): Boolean {
    val d = normalize(draft)
    return elements.any { it.editable && it.filled && normalize(it.label) == d }
}

/**
 * The one send button of a conversation screen, or null when there is none or several (a share list has one per person:
 * those are never chosen here). Buttons that send money are never send buttons (see [isSendLabel]).
 */
internal fun singleSendButton(elements: List<ScreenElement>): ScreenElement? =
    elements.filter { it.clickable && isSendLabel(it.label) }.singleOrNull()

/** Whether a tap requested on [label] (or by [text]) is meant as the send of a message. */
internal fun meansSend(label: String?, text: String?): Boolean =
    (label != null && isSendLabel(label)) || (text != null && isSendLabel(text))

private const val SETTLE_MS = 900L

/**
 * In a messaging app: waits up to ~2 s for the send button (the text may have just been typed), presses it, and verifies the
 * field emptied, retrying once with a real finger tap. [fallbackIndex] is the element the caller had chosen, used when the
 * fresh screen does not show exactly one send button.
 */
internal suspend fun JarvisAccessibilityService.pressSend(fallbackIndex: Int?): SendPress {
    var snapshot = readScreen() ?: return SendPress.NotSent("Écran illisible.")
    var button = singleSendButton(snapshot.elements)
    var waited = 0L
    while (button == null && waited < 2_000) {
        delay(300); waited += 300
        snapshot = readScreen() ?: continue
        button = singleSendButton(snapshot.elements)
    }
    val index = button?.index ?: fallbackIndex ?: return SendPress.NotSent("Aucun bouton Envoyer à l’écran : le message est-il bien écrit dans le champ ?")
    val draft = draftInField(snapshot.elements)

    if (tap(index) !is ActionResult.Done) return SendPress.NotSent("Le bouton Envoyer n’a pas répondu.")
    if (draft == null) return SendPress.Sent(verified = false)
    delay(SETTLE_MS)
    var after = readScreen()
    if (after == null || !stillInField(after.elements, draft)) return SendPress.Sent(verified = after != null)

    // The click was accepted but the message is still there: press like a finger would, on the button as it is now.
    val again = singleSendButton(after.elements)?.index ?: index
    if (fingerTap(again) !is ActionResult.Done) return SendPress.NotSent("Le message est toujours dans le champ et le bouton Envoyer ne répond pas.")
    delay(SETTLE_MS)
    after = readScreen()
    return if (after != null && !stillInField(after.elements, draft)) SendPress.Sent(verified = true)
    else SendPress.NotSent("Le message est toujours dans le champ : il n’est pas parti.")
}
