package com.jarvis.android.device

import java.text.Normalizer

/** One thing visible on the screen that the assistant can read or act on. */
internal data class ScreenElement(
    val index: Int,
    val label: String,
    val role: String,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checked: Boolean? = null,
    val password: Boolean = false,
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
    /** For an input field: it holds text the user or Jarvis typed (its [label] is then that text, not the hint). */
    val filled: Boolean = false,
)

internal const val MAX_SCREEN_ELEMENTS = 150
internal const val MAX_LABEL_CHARS = 80

/** Lower-case, accent-free form used to compare labels with what the user asked for. */
internal fun normalizeLabel(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase()
        .replace(Regex("\\s+"), " ")
        .trim()

/** A readable listing of the screen, one numbered line per element, for the model to read. */
internal fun formatScreen(appLabel: String, packageName: String, elements: List<ScreenElement>): String {
    if (elements.isEmpty()) return "Écran de $appLabel ($packageName) : aucun élément lisible."
    val shown = elements.take(MAX_SCREEN_ELEMENTS)
    val lines = shown.map { e ->
        val traits = buildList {
            if (e.clickable) add("cliquable")
            if (e.editable) add("champ de saisie")
            if (e.scrollable) add("défilable")
            e.checked?.let { add(if (it) "coché" else "non coché") }
            if (e.password) add("mot de passe")
        }
        val label = if (e.password) "••••" else e.label.take(MAX_LABEL_CHARS)
        val suffix = if (traits.isEmpty()) "" else " (${traits.joinToString(", ")})"
        "[${e.index}] ${e.role} « $label »$suffix"
    }
    val more = if (elements.size > shown.size) "\n… ${elements.size - shown.size} autres éléments non affichés (défilez ou précisez)." else ""
    return "Écran de $appLabel ($packageName) :\n${lines.joinToString("\n")}$more"
}

/** Outcome of looking for an element by its visible text. */
internal sealed interface ElementMatch {
    data class Found(val element: ScreenElement) : ElementMatch
    data class Ambiguous(val candidates: List<ScreenElement>) : ElementMatch
    data object None : ElementMatch
}

// findByText et le rapprochement des libellés vivent dans ScreenMatch.kt.

private val SENSITIVE_WORDS = listOf(
    "envoyer", "send", "payer", "pay", "acheter", "buy", "purchase", "commander", "order", "passer la commande",
    "supprimer", "delete", "effacer", "erase", "remove", "virement", "virer", "transferer", "transfer",
    "publier", "post", "installer", "install", "desinstaller", "uninstall", "reinitialiser", "reset",
    "autoriser", "allow", "accepter", "accept", "j'accepte", "s'abonner", "subscribe", "appeler", "call",
)

/** True for buttons that send, pay, delete, install, grant access or accept terms. */
internal fun isSensitiveLabel(label: String): Boolean {
    val text = normalizeLabel(label)
    if (text.isEmpty()) return false
    return SENSITIVE_WORDS.any { word ->
        Regex("(^|[^a-z])${Regex.escape(word)}([^a-z]|$)").containsMatchIn(text)
    }
}

/** Messaging apps where, with automatic sending switched on, the button that sends a message needs no confirmation. */
internal val MESSAGING_PACKAGES = setOf(
    "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.thoughtcrime.securesms", "com.facebook.orca",
    "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms",
)

private val NOT_A_MESSAGE = listOf("argent", "money", "payment", "paiement", "payer", "pay ", "cash", "virement", "transfer")

/** True for the button that sends a typed message ("Envoyer", "Send", "Send SMS"), and not for one that sends money. */
internal fun isSendLabel(label: String): Boolean {
    val text = normalizeLabel(label)
    if (NOT_A_MESSAGE.any { it in "$text " }) return false
    return text == "envoyer" || text == "send" || text.startsWith("envoyer ") || text.startsWith("send ")
}

/** Screens where a wrong tap changes security, permissions or installs: every tap needs approval. */
internal val ALWAYS_CONFIRM_PACKAGES = setOf(
    // The confirmation notification lives here: the assistant must never be able to answer it itself.
    "com.android.systemui",
    "com.android.settings",
    "com.miui.securitycenter",
    "com.google.android.packageinstaller",
    "com.android.packageinstaller",
    "com.google.android.permissioncontroller",
    "com.android.permissioncontroller",
)

/** Why a tap needs the user's approval, or null when it can go ahead. */
internal fun confirmationReason(packageName: String, element: ScreenElement, allowMessageSend: Boolean = false): String? = when {
    packageName in ALWAYS_CONFIRM_PACKAGES -> "écran sensible ($packageName)"
    // the user switched on automatic sending: the send button of a messaging app is not asked about (nothing else is waived)
    allowMessageSend && packageName in MESSAGING_PACKAGES && isSendLabel(element.label) -> null
    isSensitiveLabel(element.label) -> "action sensible"
    else -> null
}

/** Directions understood by scroll and swipe tools. */
internal fun parseDirection(value: String): String? =
    when (normalizeLabel(value)) {
        "up", "haut" -> "up"
        "down", "bas" -> "down"
        "left", "gauche" -> "left"
        "right", "droite" -> "right"
        else -> null
    }

/** The finger movement that scrolls the content in [direction]: to see what is below, the finger goes up. */
internal fun swipeForScroll(direction: String): String = when (direction) {
    "up" -> "down"
    "down" -> "up"
    "left" -> "right"
    else -> "left"
}
