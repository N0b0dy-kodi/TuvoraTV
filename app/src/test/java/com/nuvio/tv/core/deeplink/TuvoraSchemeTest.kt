package com.nuvio.tv.core.deeplink

import com.nuvio.tv.core.party.ActiveParty
import com.nuvio.tv.core.party.PartyMedia
import com.nuvio.tv.core.party.partyInviteLink
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** tuvora:// is an exact alias of nuvio://. JUnit order: assertEquals(message, expected, actual). */
class TuvoraSchemeTest {
    private val nuvioLinks = listOf(
        "nuvio://meta?type=series&id=tt0944947",
        "nuvio://series/tt0944947",
        "nuvio://movie/tt0111161",
        "nuvio://detail/series/tt0944947",
        "nuvio://imdb/series/tt0944947",
        "nuvio://tmdb/tv/1399",
        "nuvio://party/abc234",
        "nuvio://party?code=ABC-234",
        "nuvio://party/ABC10",
        "nuvio://auth/trakt?code=abc",
        "nuvio://free.nebulapro.xyz/sports/i/free/manifest.json",
        "nuvio://unknown-route",
    )

    @Test
    fun everyNuvioRouteParsesTheSameAsTuvora() {
        nuvioLinks.forEach { link ->
            val alias = "tuvora" + link.removePrefix("nuvio")
            assertEquals(alias, DeepLinkParser.parse(link), DeepLinkParser.parse(alias))
        }
        assertNotNull("a tuvora party link opens the join screen", DeepLinkParser.parse("tuvora://party/ABC234"))
        assertNotNull("upper-case scheme", DeepLinkParser.parse("TUVORA://party/ABC234"))
    }

    @Test
    fun generatedAppLinksUseTuvora() {
        assertEquals("custom-server invite", "tuvora://party/ABC234", partyInviteLink("abc234", isCustomServer = true))
        assertEquals("official invite stays the web link", "https://tuvora.co/party/ABC234", partyInviteLink("ABC234", isCustomServer = false))
        val party = ActiveParty(
            id = "p", code = "ABC234", memberId = "m", hostMemberId = "m", members = emptyList(),
            presence = emptyMap(), media = PartyMedia(type = "movie", metaId = "tt1", metaType = "movie"),
            maxMembers = 3, connected = true,
        )
        assertEquals("model default", "tuvora://party/ABC234", party.inviteLink)
    }

    @Test
    fun manifestRegistersTuvoraBesideNuvio() {
        val root = generateSequence(File(System.getProperty("user.dir")!!).canonicalFile) { it.parentFile }
            .first { File(it, "app/src/main/AndroidManifest.xml").isFile }
        val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()
        val filter = Regex("<intent-filter>((?:(?!</intent-filter>).)*?android:scheme=\"nuvio\"(?:(?!</intent-filter>).)*)</intent-filter>", RegexOption.DOT_MATCHES_ALL)
            .find(manifest)?.groupValues?.get(1).orEmpty()
        assertTrue("tuvora shares the nuvio VIEW filter (same routes)", filter.contains("android:scheme=\"tuvora\""))
    }
}
