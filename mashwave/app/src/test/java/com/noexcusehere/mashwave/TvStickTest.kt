package com.noexcusehere.mashwave

import com.noexcusehere.mashwave.iptv.TvAccount
import com.noexcusehere.mashwave.iptv.TvClient
import com.noexcusehere.mashwave.iptv.TvPairing
import com.noexcusehere.mashwave.iptv.TvRepository
import com.noexcusehere.mashwave.ui.tv.TvMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** The parts of the TV-stick layout that can be checked without a TV: which layout to show, and signing in from a phone. */
class TvStickTest {
    @Test
    fun sticksAndBoxesGetTheRemoteLayout() {
        // A Fire TV stick, an Android TV, and a bare box with no touch screen.
        assertTrue(TvMode.looksLikeTv(television = false, fireTv = true, leanback = false, touchScreen = false))
        assertTrue(TvMode.looksLikeTv(television = true, fireTv = false, leanback = true, touchScreen = false))
        assertTrue(TvMode.looksLikeTv(television = false, fireTv = false, leanback = false, touchScreen = false))
        // A phone or tablet.
        assertFalse(TvMode.looksLikeTv(television = false, fireTv = false, leanback = false, touchScreen = true))
    }

    @Test
    fun theViewersChoiceOfLayoutWins() {
        assertTrue(TvMode.decide(TvMode.AUTO, tvDevice = true))
        assertFalse(TvMode.decide(TvMode.AUTO, tvDevice = false))
        // A TV box that calls itself a tablet can be switched over by hand, and back.
        assertTrue(TvMode.decide(TvMode.TV, tvDevice = false))
        assertFalse(TvMode.decide(TvMode.PHONE, tvDevice = true))
        // The TV app is the TV layout whatever the device calls itself and whatever was chosen before.
        assertTrue(TvMode.decide(TvMode.AUTO, tvDevice = false, tvApp = true))
        assertTrue(TvMode.decide(TvMode.PHONE, tvDevice = false, tvApp = true))
    }

    @Test
    fun readsWhatWasSentFromThePhone() {
        // The three boxes, when they are filled in.
        val typed = TvPairing.accountFrom(mapOf("server" to " http://tv.example:8080 ", "username" to "sam", "password" to "pw1"))!!
        assertEquals(TvAccount.XTREAM, typed.first.type)
        assertEquals("http://tv.example:8080", typed.first.server)
        assertEquals("sam", typed.first.username)

        // A provider's message pasted whole, with a spare address.
        val pasted = TvPairing.accountFrom(mapOf("text" to "Hi!\nURL: http://one.example:80\nUsername: sam\nPassword: pw1\nBackup: http://two.example:80"))!!
        assertEquals("http://one.example:80", pasted.first.server)
        assertEquals("pw1", pasted.first.password)
        assertEquals(listOf("http://two.example:80"), pasted.second)

        // A lone link is a playlist.
        val link = TvPairing.accountFrom(mapOf("text" to "http://lists.example/my.m3u"))!!
        assertEquals(TvAccount.PLAYLIST, link.first.type)
        assertEquals("http://lists.example/my.m3u", link.first.playlistUrl)

        // Chatter with nothing usable in it.
        assertNull(TvPairing.accountFrom(mapOf("text" to "hello there")))
        assertNull(TvPairing.accountFrom(emptyMap()))
    }

    @Test
    fun formFieldsAreDecoded() {
        val fields = TvPairing.fields("code=1234&text=URL%3A+http%3A%2F%2Fa.example%0AUsername%3A+x+y&empty=")
        assertEquals("1234", fields["code"])
        assertEquals("URL: http://a.example\nUsername: x y", fields["text"])
        assertEquals("", fields["empty"])
    }

    private fun post(port: Int, body: String): String {
        val connection = URL("http://127.0.0.1:$port/send").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        connection.outputStream.use { it.write(body.toByteArray()) }
        return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    @Test
    fun aPhoneSignsTheTvInWithTheRightCodeOnly() {
        var received: TvAccount? = null
        var answer: String? = null
        val pairing = TvPairing("4821") { account, _ ->
            received = account
            answer
        }
        try {
            val page = URL("http://127.0.0.1:${pairing.port}/").readText()
            assertTrue(page.contains("Sign in on your TV"))
            assertTrue(page.contains("name=\"code\""))

            val message = URLEncoder.encode("URL: http://one.example:80\nUsername: sam\nPassword: pw<1>", "UTF-8")
            // The wrong code: nothing reaches the TV.
            val refused = post(pairing.port, "code=0000&text=$message")
            assertTrue(refused.contains("not the code on the TV"))
            assertNull(received)
            // What was typed is kept in the form, safely escaped.
            assertTrue(refused.contains("pw&lt;1&gt;"))

            // The right code, but the service says no: the reason is shown on the phone.
            answer = "That user name or password was not accepted."
            val failed = post(pairing.port, "code=4821&text=$message")
            assertNotNull(received)
            assertEquals("sam", received?.username)
            assertTrue(failed.contains("That user name or password was not accepted."))

            // And when it works.
            answer = null
            assertTrue(post(pairing.port, "code=4821&text=$message").contains("Your TV is signed in"))

            // Rubbish is turned away without bothering the TV.
            received = null
            assertTrue(post(pairing.port, "code=4821&text=hello").contains("doesn't look like TV sign-in details"))
            assertNull(received)
        } finally {
            pairing.close()
        }
    }

    @Test
    fun theTipAboutThePhonesMoreMenuIsLeftOutOnATv() {
        val refused = "That user name or password was not accepted. " + TvClient.OTHER_PLAYER_HINT
        assertEquals("That user name or password was not accepted.", TvClient.withoutPhoneTip(refused))
        // Anything else is passed on as it is.
        assertEquals("Nothing answers at that address.", TvClient.withoutPhoneTip("Nothing answers at that address."))
        assertNull(TvClient.withoutPhoneTip(null))
    }

    @Test
    fun signingInAgainFindsTheServiceAlreadyThere() {
        val login = TvAccount(id = "a1", name = "Mine", type = TvAccount.XTREAM, server = "http://tv.example.com:8080", username = "sam", password = "old")
        val list = TvAccount(id = "b2", name = "List", type = TvAccount.PLAYLIST, playlistUrl = "http://lists.example.com/all.m3u")
        val here = listOf(login, list)
        // The same login, typed a little differently and with a new password.
        val again = TvAccount(id = "", name = "", type = TvAccount.XTREAM, server = "TV.example.com:8080/", username = " sam ", password = "new")
        assertEquals("a1", TvRepository.twinOf(here, again)?.id)
        // Another person at the same service, or the same person elsewhere, is a different service.
        assertNull(TvRepository.twinOf(here, again.copy(username = "alex")))
        assertNull(TvRepository.twinOf(here, again.copy(server = "http://other.example.com:8080")))
        // Playlist links: the same link, and a link that carries the login.
        assertEquals("b2", TvRepository.twinOf(here, TvAccount(id = "", name = "", type = TvAccount.PLAYLIST, playlistUrl = " http://lists.example.com/all.m3u "))?.id)
        assertEquals("a1", TvRepository.twinOf(here, TvAccount(id = "", name = "", type = TvAccount.PLAYLIST, playlistUrl = "http://tv.example.com:8080/get.php?username=sam&password=new&type=m3u_plus"))?.id)
        assertNull(TvRepository.twinOf(here, TvAccount(id = "", name = "", type = TvAccount.PLAYLIST, playlistUrl = "http://lists.example.com/other.m3u")))
        assertNull(TvRepository.twinOf(emptyList(), again))
    }

    @Test
    fun tooManyWrongCodesShutsTheDoor() {
        var calls = 0
        val pairing = TvPairing("4821") { _, _ ->
            calls++
            null
        }
        try {
            repeat(10) { post(pairing.port, "code=1111&text=x") }
            val message = URLEncoder.encode("URL: http://one.example:80\nUsername: sam\nPassword: pw1", "UTF-8")
            assertTrue(post(pairing.port, "code=4821&text=$message").contains("Too many wrong codes"))
            assertEquals(0, calls)
        } finally {
            pairing.close()
        }
    }
}
