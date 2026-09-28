package com.jarvis.android.google

/*
 * Leaving a mailing list the way the sender says to: the List-Unsubscribe header (RFC 2369) gives a mail address and/or a web
 * address; with List-Unsubscribe-Post: List-Unsubscribe=One-Click (RFC 8058, what Gmail and Yahoo ask big senders for) a single
 * POST to that web address does it, no page to open. None of it is for real spam: answering spam tells its sender the address is
 * read, so a sender Gmail put in the spam, or one whose mail does not prove where it comes from, is left alone.
 */

/** How a sender lets you leave its list: [oneClick] (a web address to POST to), a [mailto] mail, or a [web] page to open. */
internal data class Unsubscribe(val oneClick: String? = null, val mailto: MailtoUnsubscribe? = null, val web: String? = null) {
    val any: Boolean get() = oneClick != null || mailto != null || web != null
}

internal data class MailtoUnsubscribe(val to: String, val subject: String, val body: String)

/** A mail's headers, as far as sorting needs them. */
internal data class MailHeaders(
    val id: String,
    val from: String,
    val subject: String = "",
    val date: String = "",
    val listUnsubscribe: String? = null,
    val listUnsubscribePost: String? = null,
    val authResults: List<String> = emptyList(),
    val labels: List<String> = emptyList(),
) {
    val address: String get() = senderAddress(from)
    val inSpam: Boolean get() = "SPAM" in labels
    val unsubscribe: Unsubscribe get() = parseUnsubscribe(listUnsubscribe, listUnsubscribePost)
}

/** A sender that sends lists: how many of its mails were seen, the newest, and how to leave. */
internal data class Subscription(val address: String, val name: String, val count: Int, val latest: MailHeaders, val unsubscribe: Unsubscribe, val trusted: Boolean)

private val MAIL = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")

/** The address in a From header ("Zalando <news@zalando.fr>" → news@zalando.fr), lower case. */
internal fun senderAddress(from: String): String =
    (Regex("<([^>]+)>").find(from)?.groupValues?.get(1) ?: from).trim().trim('"').lowercase()

/** The name in a From header, or the address. */
internal fun senderName(from: String): String =
    from.substringBefore('<').trim().trim('"').ifBlank { senderAddress(from) }

internal fun parseUnsubscribe(header: String?, post: String?): Unsubscribe {
    if (header.isNullOrBlank()) return Unsubscribe()
    val uris = Regex("<([^>]+)>").findAll(header).map { it.groupValues[1].trim() }.toList()
    // plain http is left out: the address would travel in the clear
    val https = uris.firstOrNull { safeHttps(it) }
    val mailto = uris.firstNotNullOfOrNull { if (it.startsWith("mailto:", ignoreCase = true)) parseMailto(it) else null }
    val oneClick = https?.takeIf { post?.replace(" ", "")?.equals("List-Unsubscribe=One-Click", ignoreCase = true) == true }
    return Unsubscribe(oneClick, mailto, if (oneClick == null) https else null)
}

/** An https address with a host, and nothing that could be a trick (credentials, spaces, control characters). */
internal fun safeHttps(url: String): Boolean {
    if (!url.startsWith("https://", ignoreCase = true) || url.any { it.isWhitespace() || it.isISOControl() }) return false
    val u = try { java.net.URI(url) } catch (_: Exception) { return false }
    return !u.host.isNullOrBlank() && u.rawUserInfo == null
}

/** "mailto:leave@list.example?subject=unsubscribe" → where to write, with what subject and text. */
internal fun parseMailto(uri: String): MailtoUnsubscribe? {
    val rest = uri.substringAfter(':')
    val to = decode(rest.substringBefore('?')).trim()
    if (!MAIL.matches(to)) return null
    val params = rest.substringAfter('?', "").split('&').filter { '=' in it }
        .associate { it.substringBefore('=').lowercase() to decode(it.substringAfter('=')) }
    return MailtoUnsubscribe(to, params["subject"]?.take(200)?.ifBlank { null } ?: "unsubscribe", params["body"]?.take(1000) ?: "unsubscribe")
}

private fun decode(s: String): String = try {
    java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
} catch (_: Exception) {
    s
}

/**
 * Whether the mail proves it comes from its From domain, by Gmail's own checks (the Authentication-Results headers written by
 * mx.google.com): DMARC passed, or a DKIM signature that passed for that domain (or one of its parents or children).
 */
internal fun authenticated(authResults: List<String>, fromAddress: String): Boolean {
    val domain = fromAddress.substringAfter('@', "").lowercase()
    if (domain.isEmpty()) return false
    val gmail = authResults.filter { it.trimStart().startsWith("mx.google.com", ignoreCase = true) }
    if (gmail.any { Regex("\\bdmarc=pass\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) }) return true
    val signers = gmail.flatMap { r ->
        Regex("dkim=pass[^;]*?header\\.(?:i=@?|d=)([A-Za-z0-9.-]+)", RegexOption.IGNORE_CASE).findAll(r).map { it.groupValues[1].lowercase() }.toList()
    }
    return signers.any { s -> s == domain || domain.endsWith(".$s") || s.endsWith(".$domain") }
}

/** The senders of [mails] (newest first) that say how to leave their list, most mails first. */
internal fun subscriptions(mails: List<MailHeaders>): List<Subscription> =
    mails.groupBy { it.address }.mapNotNull { (address, list) ->
        val withWay = list.firstOrNull { it.unsubscribe.any } ?: return@mapNotNull null
        Subscription(address, senderName(list.first().from), list.size, list.first(), withWay.unsubscribe, !list.any { it.inSpam } && authenticated(withWay.authResults, address))
    }.sortedWith(compareByDescending<Subscription> { it.count }.thenBy { it.address })

/** How to leave, in words. */
internal fun unsubscribeWords(u: Unsubscribe): String = when {
    u.oneClick != null -> "désabonnement en un clic"
    u.mailto != null -> "désabonnement par mail"
    u.web != null -> "désabonnement sur une page web (${hostOf(u.web)})"
    else -> "aucun lien de désabonnement"
}

internal fun hostOf(url: String): String = try { java.net.URI(url).host.orEmpty() } catch (_: Exception) { "" }
