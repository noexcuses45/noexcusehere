package com.noexcusehere.mashwave.desktop

import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * A name for this computer that stays the same from one day to the next, so that it keeps its
 * place on an unlock code. Only a fingerprint of it is ever sent anywhere.
 */
object ThisComputer {
    /** True when the last [name] was the computer's own rather than one made up for it. */
    var usedItsOwn = false
        private set

    /** [dataDir] is where a made-up name is kept on a computer that will not give its own. */
    fun name(dataDir: File): String {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val own = runCatching {
            when {
                // Every Windows installation has a long number of its own.
                os.contains("win") -> lineAfter(run("reg", "query", "HKLM\\SOFTWARE\\Microsoft\\Cryptography", "/v", "MachineGuid"), "REG_SZ")
                os.contains("mac") -> lineAfter(run("ioreg", "-rd1", "-c", "IOPlatformExpertDevice"), "IOPlatformUUID")?.trim('"', ' ', '=')
                else -> listOf("/etc/machine-id", "/var/lib/dbus/machine-id").firstNotNullOfOrNull { path -> runCatching { File(path).readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() } }
            }
        }.getOrNull()?.trim()
        usedItsOwn = !own.isNullOrEmpty() && own.length >= 8
        if (usedItsOwn) return own!!
        val kept = File(dataDir, "computer-name")
        runCatching { kept.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        return UUID.randomUUID().toString().also { made -> runCatching { kept.writeText(made) } }
    }

    fun platform(): String {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        return when {
            os.contains("win") -> "windows"
            os.contains("mac") -> "mac"
            else -> "linux"
        }
    }

    /** What follows [marker] on the first line of [text] that has it. */
    internal fun lineAfter(text: String?, marker: String): String? =
        text?.lineSequence()?.firstOrNull { it.contains(marker) }?.substringAfter(marker)?.trim()?.takeIf { it.isNotEmpty() }

    private fun run(vararg command: String): String? {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        return text
    }
}
