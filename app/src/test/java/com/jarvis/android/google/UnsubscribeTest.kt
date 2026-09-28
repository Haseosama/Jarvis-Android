package com.jarvis.android.google

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnsubscribeTest {
    private val gmailPass = "mx.google.com; dkim=pass header.i=@news.zalando.fr header.s=s1 header.b=abc; spf=pass (google.com: domain of " +
        "bounce@news.zalando.fr designates 1.2.3.4 as permitted sender) smtp.mailfrom=bounce@news.zalando.fr; dmarc=pass (p=REJECT) header.from=zalando.fr"

    @Test fun `one click needs an https address and the one-click header`() {
        val u = parseUnsubscribe("<mailto:leave@list.zalando.fr?subject=unsubscribe>, <https://news.zalando.fr/u/abc123>", "List-Unsubscribe=One-Click")
        assertEquals("https://news.zalando.fr/u/abc123", u.oneClick)
        assertEquals("leave@list.zalando.fr", u.mailto!!.to)
        assertNull(u.web)
        // without the header, the same address is only a page to open
        val page = parseUnsubscribe("<https://news.zalando.fr/u/abc123>", null)
        assertNull(page.oneClick)
        assertEquals("https://news.zalando.fr/u/abc123", page.web)
    }

    @Test fun `plain http, credentials in the address and broken addresses are left out`() {
        assertFalse(parseUnsubscribe("<http://example.com/u>", "List-Unsubscribe=One-Click").any)
        assertFalse(safeHttps("https://user:pw@example.com/u"))
        assertFalse(safeHttps("https://exa mple.com/u"))
        assertFalse(parseUnsubscribe("<mailto:not an address>", null).any)
        assertFalse(parseUnsubscribe(null, null).any)
    }

    @Test fun `a mailto gives its subject and text, decoded, with defaults`() {
        val m = parseMailto("mailto:unsub%2Bid42@lists.example.org?subject=Unsubscribe%20me&body=please+stop")!!
        assertEquals("unsub+id42@lists.example.org", m.to)
        assertEquals("Unsubscribe me", m.subject)
        assertEquals("please+stop", m.body)
        assertEquals("unsubscribe", parseMailto("mailto:leave@example.org")!!.subject)
    }

    @Test fun `a sender is trusted by Gmail's own DMARC or an aligned DKIM, not by a header the sender wrote`() {
        assertTrue(authenticated(listOf(gmailPass), "news@zalando.fr"))
        val dkimOnly = "mx.google.com; dkim=pass header.i=@zalando.fr header.s=s1; spf=softfail smtp.mailfrom=x@y.biz"
        assertTrue(authenticated(listOf(dkimOnly), "news@mail.zalando.fr"))
        // signed by another domain
        assertFalse(authenticated(listOf("mx.google.com; dkim=pass header.i=@bulkmailer.biz; spf=pass"), "news@zalando.fr"))
        // a pass claimed in a header that Gmail did not write
        assertFalse(authenticated(listOf("evil.example; dmarc=pass header.from=zalando.fr"), "news@zalando.fr"))
        assertFalse(authenticated(emptyList(), "news@zalando.fr"))
    }

    @Test fun `senders are grouped, the busiest first, and spam or unproven ones are marked`() {
        val list = "<https://news.zalando.fr/u/1>"
        val one = "List-Unsubscribe=One-Click"
        val mails = listOf(
            MailHeaders("1", "Zalando <News@Zalando.fr>", "Soldes", listUnsubscribe = list, listUnsubscribePost = one, authResults = listOf(gmailPass)),
            MailHeaders("2", "\"Zalando\" <news@zalando.fr>", "Nouveautés", listUnsubscribe = list, listUnsubscribePost = one, authResults = listOf(gmailPass)),
            MailHeaders("3", "Lots <win@prizes.biz>", "Gagné !", listUnsubscribe = "<https://prizes.biz/x>", labels = listOf("SPAM")),
            MailHeaders("4", "Ami <ami@gmail.com>", "Salut"),
        )
        val subs = subscriptions(mails)
        assertEquals(listOf("news@zalando.fr", "win@prizes.biz"), subs.map { it.address })
        assertEquals(2, subs[0].count)
        assertEquals("Zalando", subs[0].name)
        assertTrue(subs[0].trusted)
        assertFalse(subs[1].trusted)
        assertEquals("désabonnement en un clic", unsubscribeWords(subs[0].unsubscribe))
        assertEquals("désabonnement sur une page web (prizes.biz)", unsubscribeWords(subs[1].unsubscribe))
    }
}
