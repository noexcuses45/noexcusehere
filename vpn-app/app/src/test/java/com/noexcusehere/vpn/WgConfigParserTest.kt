package com.noexcusehere.vpn

import com.noexcusehere.vpn.data.ConfigResult
import com.noexcusehere.vpn.data.WgConfigParser
import com.wireguard.crypto.KeyPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WgConfigParserTest {

    private fun valid(text: String) = WgConfigParser.parse(text) as ConfigResult.Valid

    private fun invalidReason(text: String): Int = (WgConfigParser.parse(text) as ConfigResult.Invalid).reason.id

    @Test
    fun acceptsAnOrdinaryClientConfig() {
        val result = valid(validConfig(endpoint = "vpn.example.com:51820"))

        assertEquals("vpn.example.com:51820", result.endpoint)
        assertEquals(1, result.config.peers.size)
        assertTrue(result.ignoredKeys.isEmpty())
    }

    @Test
    fun acceptsWindowsLineEndingsAndAByteOrderMark() {
        val text = "\uFEFF" + validConfig().replace("\n", "\r\n")

        assertEquals("vpn.example.com:51820", valid(text).endpoint)
    }

    @Test
    fun dropsDesktopOnlySettingsInsteadOfRejectingTheFile() {
        val text = validConfig(extraInterfaceLine = "PostUp = iptables -A FORWARD -i %i -j ACCEPT")

        val result = valid(text)

        assertEquals(listOf("PostUp"), result.ignoredKeys)
        assertFalse(result.text.contains("PostUp"))
    }

    @Test
    fun rejectsTextThatIsNotAConfig() {
        assertEquals(R.string.err_unknown_section, invalidReason("hello there"))
        assertEquals(R.string.err_config_empty, invalidReason("   \n "))
    }

    @Test
    fun rejectsABrokenPrivateKey() {
        val text = validConfig().replace(Regex("PrivateKey = .*"), "PrivateKey = not-a-key")

        assertEquals(R.string.err_invalid_key, invalidReason(text))
    }

    @Test
    fun rejectsAConfigWithNoServer() {
        val text = "[Interface]\nPrivateKey = ${KeyPair().privateKey.toBase64()}\nAddress = 10.0.0.2/32"

        assertEquals(R.string.err_no_peer, invalidReason(text))
    }

    @Test
    fun rejectsAServerWithNoAddress() {
        val text = validConfig().lines().filterNot { it.startsWith("Endpoint") }.joinToString("\n")

        assertEquals(R.string.err_no_endpoint, invalidReason(text))
    }

    @Test
    fun rejectsSettingsItDoesNotKnow() {
        val text = validConfig(extraInterfaceLine = "Colour = teal")

        assertEquals(R.string.err_unknown_attribute, invalidReason(text))
    }

    @Test
    fun sessionNameIsShortPlainAscii() {
        assertEquals("Home_server", WgConfigParser.sessionName("Home server"))
        assertEquals("vpn", WgConfigParser.sessionName("🏠"))
        assertEquals("A_very_long_ser", WgConfigParser.sessionName("A very long server name indeed"))
    }
}
