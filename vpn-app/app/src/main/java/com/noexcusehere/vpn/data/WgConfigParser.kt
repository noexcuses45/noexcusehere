package com.noexcusehere.vpn.data

import com.noexcusehere.vpn.R
import com.noexcusehere.vpn.UiText
import com.noexcusehere.vpn.uiText
import com.wireguard.config.BadConfigException
import com.wireguard.config.BadConfigException.Reason
import com.wireguard.config.Config
import java.io.BufferedReader
import java.io.IOException
import java.io.StringReader
import java.util.Locale

sealed interface ConfigResult {
    /**
     * @param text the configuration as it should be stored (settings Android can't apply removed)
     * @param ignoredKeys the names of those removed settings, for telling the user
     */
    data class Valid(
        val config: Config,
        val text: String,
        val ignoredKeys: List<String>,
    ) : ConfigResult {
        /** "host:port" of the first server in the configuration. */
        val endpoint: String?
            get() = config.peers.firstNotNullOfOrNull { it.endpoint.orElse(null) }?.toString()
    }

    data class Invalid(val reason: UiText) : ConfigResult
}

/**
 * Validates WireGuard configuration text and turns the library's parse errors into messages
 * a person can act on.
 */
object WgConfigParser {

    /** Far larger than any real configuration; guards against importing the wrong file. */
    const val MAX_CONFIG_CHARS = 64 * 1024

    /**
     * wg-quick settings that only make sense on a desktop (shell hooks, routing tables).
     * Configurations written for Linux often contain them; they are dropped on import
     * instead of rejecting the whole file.
     */
    private val DESKTOP_ONLY_KEYS =
        setOf("preup", "postup", "predown", "postdown", "table", "saveconfig", "fwmark")

    fun parse(raw: String): ConfigResult {
        val normalised = raw
            .removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
        if (normalised.isEmpty()) return ConfigResult.Invalid(uiText(R.string.err_config_empty))

        val ignored = LinkedHashSet<String>()
        val text = normalised.lineSequence()
            .filter { line ->
                val key = line.substringBefore('#').substringBefore('=', missingDelimiterValue = "").trim()
                val desktopOnly = key.lowercase(Locale.ROOT) in DESKTOP_ONLY_KEYS
                if (desktopOnly) ignored += key
                !desktopOnly
            }
            .joinToString("\n")

        val config = try {
            Config.parse(BufferedReader(StringReader(text)))
        } catch (e: BadConfigException) {
            return ConfigResult.Invalid(describe(e))
        } catch (e: IOException) {
            return ConfigResult.Invalid(uiText(R.string.err_generic_config))
        }

        // The library accepts these, but a phone can't connect anywhere without them.
        if (config.peers.isEmpty()) return ConfigResult.Invalid(uiText(R.string.err_no_peer))
        if (config.peers.none { it.endpoint.isPresent }) {
            return ConfigResult.Invalid(uiText(R.string.err_no_endpoint))
        }
        return ConfigResult.Valid(config, text, ignored.toList())
    }

    /**
     * Android shows this name in its VPN dialogs, and the tunnel library only accepts a
     * short, plain-ASCII name, so the display name is reduced to that.
     */
    fun sessionName(displayName: String): String {
        val cleaned = displayName.trim()
            .replace(Regex("[^a-zA-Z0-9_=+.-]+"), "_")
            .trim('_')
            .take(15)
            .trim('_')
        return cleaned.ifEmpty { "vpn" }
    }

    private fun describe(e: BadConfigException): UiText {
        val section = e.section.getName()
        val field = e.location.getName()
        val text = e.text?.toString().orEmpty()
        return when (e.reason) {
            Reason.MISSING_SECTION -> uiText(R.string.err_missing_interface)
            Reason.UNKNOWN_SECTION -> uiText(R.string.err_unknown_section, text)
            Reason.UNKNOWN_ATTRIBUTE -> uiText(R.string.err_unknown_attribute, section, text)
            Reason.MISSING_ATTRIBUTE -> uiText(R.string.err_missing_attribute, section, field)
            Reason.INVALID_KEY -> uiText(R.string.err_invalid_key, section, field)
            Reason.INVALID_NUMBER, Reason.INVALID_VALUE ->
                uiText(R.string.err_invalid_value, section, field, text)
            Reason.SYNTAX_ERROR -> uiText(R.string.err_syntax, section, text)
            else -> uiText(R.string.err_generic_config)
        }
    }
}
