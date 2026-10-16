package com.nuvio.tv.core.party

import com.nuvio.tv.core.deeplink.DeepLinkParser
import com.nuvio.tv.domain.deeplink.AppDeepLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Tuvora invite links, deep links and names. JUnit order: assertEquals(message, expected, actual). */
class PartyBrandingTest {

    @Test
    fun officialInvitesAreTuvoraLinks() {
        assertEquals("official backend", "https://tuvora.co/party/ABC234", partyInviteLink("abc234", isCustomServer = false))
        assertEquals("custom server keeps the app scheme", "nuvio://party/ABC234", partyInviteLink("ABC234", isCustomServer = true))
    }

    @Test
    fun tuvoraInviteLinksOpenTheJoinScreen() {
        assertEquals("tuvora.co", AppDeepLink.Party("ABC234"), DeepLinkParser.parse("https://tuvora.co/party/abc234"))
        assertEquals("www", AppDeepLink.Party("ABC234"), DeepLinkParser.parse("https://www.tuvora.co/party/ABC234/"))
        assertNull("other tuvora.co pages are not party links", DeepLinkParser.parse("https://tuvora.co/s/ABC234"))
        assertNull("bad code", DeepLinkParser.parse("https://tuvora.co/party/ABC10"))
    }

    @Test
    fun upstreamNuvioInviteLinksAreNotOurs() {
        assertNull("nuvio.tv", DeepLinkParser.parse("https://nuvio.tv/party/ABC234"))
        assertNull("look-alike host", DeepLinkParser.parse("https://tuvora.co.evil.example/party/ABC234"))
    }

    @Test
    fun anUnnamedProfileJoinsAsTuvora() {
        assertEquals("blank name", "Tuvora", partyDisplayName("  "))
        assertEquals("no profile", "Tuvora", partyDisplayName(null))
        assertEquals("trimmed and capped", "a".repeat(40), partyDisplayName(" " + "a".repeat(50)))
    }
}
