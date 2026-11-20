package com.noexcusehere.mashwave.licence

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Locale
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/*
 * Unlock codes.
 *
 * The app is free to copy; what is sold is a code. A code is typed in once, checked by the code
 * service over the internet, and the service answers with a signed note saying "this code is in
 * use on this device". The note is kept on the device and the app opens without the internet from
 * then on. A code only unlocks a set number of devices, so passing a code around does not work.
 *
 * Everything here is plain Kotlin, shared by the phone app, its TV layout and the computer app.
 */

/** Where codes are checked, and the key that proves an answer really came from there. */
object LicenceConfig {
    /**
     * Address of the code service. While this is empty there is no service to ask, and the app
     * opens for everyone without asking for a code.
     */
    const val ENDPOINT = ""

    /**
     * The service's public key (X.509, base64). Its answers are signed with the private half,
     * which never leaves the service, so nobody else can write a note the app accepts.
     */
    const val PUBLIC_KEY = ""

    /** Where a code can be bought. Shown on the unlock screen when filled in. */
    const val BUY_URL = ""

    /** Which product a code is for, in case the same service ever looks after another one. */
    const val PRODUCT = "umm"
}

/** What a code looks like, and reading one that somebody typed. */
object LicenceCodes {
    /** No I, L, O, 0 or 1: nothing that can be mistaken for something else when read off a screen. */
    const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    const val LENGTH = 15
    const val EXAMPLE = "ABCDE-FGHJK-MNPQR"

    /** The code as the service knows it (letters and digits only), or null when this cannot be one. */
    fun tidy(typed: String): String? {
        val code = typed.uppercase(Locale.ROOT).filter { it.isLetterOrDigit() }
        if (code.length != LENGTH || code.any { it !in ALPHABET }) return null
        return code
    }

    /** In groups of five, the way codes are written down. */
    fun pretty(code: String): String = code.chunked(5).joinToString("-")

    /** What to say about something typed that cannot be a code; null when it can. */
    fun problemWith(typed: String): String? {
        val letters = typed.uppercase(Locale.ROOT).filter { it.isLetterOrDigit() }
        return when {
            letters.isEmpty() -> "Type your code first."
            letters.length < LENGTH -> "That is too short. A code has $LENGTH letters and numbers, like $EXAMPLE."
            letters.length > LENGTH -> "That is too long. A code has $LENGTH letters and numbers, like $EXAMPLE."
            letters.any { it !in ALPHABET } -> "Codes never contain the letters I, L or O, or the numbers 0 or 1. Please check it again."
            else -> null
        }
    }
}

/** The signed note from the code service, once it has been checked. */
class LicenceNote(
    /** The note exactly as the service sent it. */
    val raw: String,
    /** Which code it is for, as the service numbers it (not the code itself). */
    val codeId: String,
    /** The last few characters of the code, to show which one unlocked this device. */
    val hint: String,
    val device: String,
    val issuedAt: Long,
)

/** Checks that a note is genuine and meant for this device. */
@OptIn(ExperimentalEncodingApi::class)
object LicenceCheck {
    @Serializable
    private class Body(val v: Int = 0, val p: String = "", val c: String = "", val h: String = "", val d: String = "", val t: Long = 0)

    private val json = Json { ignoreUnknownKeys = true }
    private val urlSafe = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

    /**
     * The note behind [raw] when its signature is the service's ([publicKey]) and it names this
     * [device] and [product]; null for anything else - made up, altered, or copied from another device.
     */
    fun read(raw: String, publicKey: String, device: String, product: String = LicenceConfig.PRODUCT): LicenceNote? = runCatching {
        val dot = raw.indexOf('.')
        if (dot <= 0 || dot == raw.length - 1 || raw.indexOf('.', dot + 1) >= 0) return null
        val signed = raw.substring(0, dot)
        val signature = derFromRaw(urlSafe.decode(raw.substring(dot + 1))) ?: return null
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.Default.decode(publicKey)))
        val checker = Signature.getInstance("SHA256withECDSA")
        checker.initVerify(key)
        checker.update(signed.toByteArray(Charsets.US_ASCII))
        if (!checker.verify(signature)) return null
        val body = json.decodeFromString(Body.serializer(), String(urlSafe.decode(signed), Charsets.UTF_8))
        if (body.v != 1 || body.p != product || body.d != device || body.c.isEmpty()) return null
        LicenceNote(raw, body.c, body.h, body.d, body.t)
    }.getOrNull()

    /**
     * The service signs with the web's own tools, which write a signature as two 32-byte numbers
     * end to end. Java wants the same two numbers wrapped up ("DER"); this does the wrapping.
     */
    fun derFromRaw(raw: ByteArray): ByteArray? {
        if (raw.size != 64) return null
        fun number(bytes: ByteArray): ByteArray {
            var start = 0
            while (start < bytes.size - 1 && bytes[start] == 0.toByte()) start++
            val trimmed = bytes.copyOfRange(start, bytes.size)
            // A leading byte of 0x80 or more would read as a negative number; a zero in front fixes that.
            return if (trimmed[0] < 0) byteArrayOf(0) + trimmed else trimmed
        }
        val r = number(raw.copyOfRange(0, 32))
        val s = number(raw.copyOfRange(32, 64))
        val body = byteArrayOf(0x02, r.size.toByte()) + r + byteArrayOf(0x02, s.size.toByte()) + s
        return byteArrayOf(0x30, body.size.toByte()) + body
    }
}

/** Something that identifies this device to the code service without saying anything about it. */
object LicenceDevice {
    /**
     * A fingerprint of [identifier] (whatever the device calls itself). Only the fingerprint is
     * ever sent: it cannot be turned back into the identifier.
     */
    fun fingerprint(identifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("ultimate-media-manager:$identifier".toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }
}

/** Talks to the code service. Every call blocks, so use it off the main thread. */
class LicenceClient(private val endpoint: String) {
    enum class Why { NO_INTERNET, UNKNOWN_CODE, ALL_USED, SWITCHED_OFF, TROUBLE }

    sealed class Answer {
        class Unlocked(val note: String) : Answer()
        class Refused(val why: Why, val devices: Int = 0) : Answer()
    }

    @Serializable
    private class Ask(val action: String, val code: String = "", val device: String = "", val platform: String = "", val version: String = "", val note: String = "")

    @Serializable
    private class Reply(val ok: Boolean = false, val note: String = "", val reason: String = "", val devices: Int = 0)

    private val json = Json { ignoreUnknownKeys = true }

    /** Asks the service to put [code] to use on this [device]. */
    fun unlock(code: String, device: String, platform: String, version: String): Answer {
        val reply = ask(Ask("unlock", code = code, device = device, platform = platform, version = version))
            ?: return Answer.Refused(if (reached) Why.TROUBLE else Why.NO_INTERNET)
        if (reply.ok && reply.note.isNotEmpty()) return Answer.Unlocked(reply.note)
        return Answer.Refused(
            when (reply.reason) {
                "unknown" -> Why.UNKNOWN_CODE
                "full" -> Why.ALL_USED
                "off" -> Why.SWITCHED_OFF
                else -> Why.TROUBLE
            },
            reply.devices,
        )
    }

    /**
     * Whether the code behind [note] still stands: true, false (it was switched off, or this
     * device was taken off it), or null when the service could not be asked.
     */
    fun stillGood(note: String): Boolean? {
        val reply = ask(Ask("check", note = note)) ?: return null
        return when {
            reply.ok -> true
            reply.reason == "off" || reply.reason == "gone" -> false
            else -> null
        }
    }

    /** True when the last call got as far as an answer from a server, however unhelpful. */
    private var reached = false

    private fun ask(question: Ask): Reply? {
        reached = false
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 20_000
                doOutput = true
                instanceFollowRedirects = false
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }
            connection.outputStream.use { it.write(json.encodeToString(Ask.serializer(), question).toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            reached = true
            if (status !in 200..299) return null
            // An answer is a few hundred bytes; anything much longer is not one.
            val text = connection.inputStream.use { stream ->
                val buffer = ByteArray(16 * 1024)
                var filled = 0
                while (filled < buffer.size) {
                    val n = stream.read(buffer, filled, buffer.size - filled)
                    if (n < 0) break
                    filled += n
                }
                String(buffer, 0, filled, Charsets.UTF_8)
            }
            json.decodeFromString(Reply.serializer(), text)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    companion object {
        /** A refusal, in words for the unlock screen. */
        fun wordsFor(why: Why, devices: Int = 0): String = when (why) {
            Why.NO_INTERNET -> "The code could not be checked: there is no internet connection. Connect and try again - it is only needed this once."
            Why.UNKNOWN_CODE -> "That code is not known. Please check each letter and number and try again."
            Why.ALL_USED -> when {
                devices == 1 -> "That code is already in use on another device, and it unlocks one device only."
                devices > 1 -> "That code is already in use on $devices devices, which is as many as it unlocks."
                else -> "That code is already in use on as many devices as it unlocks."
            } + " Get in touch with whoever sold it to you to move it to this device."
            Why.SWITCHED_OFF -> "That code has been switched off. Get in touch with whoever sold it to you."
            Why.TROUBLE -> "The code service did not answer properly. Please try again in a few minutes."
        }
    }
}

/** Whether the app may be used. */
enum class Lock {
    /** There is no code service (see [LicenceConfig.ENDPOINT]): nothing to unlock. */
    NOT_NEEDED,
    UNLOCKED,
    LOCKED,
}

/**
 * Keeps track of whether this device is unlocked, and unlocks it with a code.
 *
 * [device] is this device's fingerprint (see [LicenceDevice]); [file] is where the signed note
 * is kept between visits.
 */
class LicenceManager(
    private val endpoint: String,
    private val publicKey: String,
    private val file: File,
    private val device: String,
    private val platform: String,
    private val version: String,
    private val scope: CoroutineScope,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    @Serializable
    private class Kept(val note: String = "", val checkedAt: Long = 0)

    private val json = Json { ignoreUnknownKeys = true }
    private val client = LicenceClient(endpoint)
    private var note: LicenceNote? = null
    private var checkedAt = 0L

    private val _state = MutableStateFlow(Lock.LOCKED)
    val state: StateFlow<Lock> = _state

    /** The end of the code this device was unlocked with, or empty. */
    var hint: String = ""
        private set

    /** Why the app locked itself again, for the unlock screen to say; empty when it never has. */
    var notice: String = ""
        private set

    val needed: Boolean get() = endpoint.isNotEmpty() && publicKey.isNotEmpty()

    init {
        if (!needed) {
            _state.value = Lock.NOT_NEEDED
        } else {
            val kept = runCatching { json.decodeFromString(Kept.serializer(), file.readText()) }.getOrNull()
            val read = kept?.note?.takeIf { it.isNotEmpty() }?.let { LicenceCheck.read(it, publicKey, device) }
            if (read != null) {
                note = read
                hint = read.hint
                checkedAt = kept?.checkedAt ?: 0
                _state.value = Lock.UNLOCKED
            }
        }
    }

    /** Tries [typed] as a code. Returns null when the device is now unlocked, or what went wrong, in words. */
    suspend fun unlock(typed: String): String? {
        if (!needed) return null
        LicenceCodes.problemWith(typed)?.let { return it }
        val code = LicenceCodes.tidy(typed) ?: return "That does not look like a code."
        val answer = withContext(Dispatchers.IO) { client.unlock(code, device, platform, version) }
        return when (answer) {
            is LicenceClient.Answer.Refused -> LicenceClient.wordsFor(answer.why, answer.devices)
            is LicenceClient.Answer.Unlocked -> {
                val read = LicenceCheck.read(answer.note, publicKey, device)
                    ?: return LicenceClient.wordsFor(LicenceClient.Why.TROUBLE)
                note = read
                hint = read.hint
                checkedAt = now()
                notice = ""
                keep()
                _state.value = Lock.UNLOCKED
                null
            }
        }
    }

    /**
     * Now and then, when the internet happens to be there, asks whether the code still stands
     * (a code can be switched off after a refund). Not being able to ask changes nothing: only a
     * clear "no" from the service locks the app again.
     */
    fun lookAgainIfDue() {
        scope.launch { lookAgain() }
    }

    /** The same, waiting for the answer. */
    suspend fun lookAgain() {
        val current = note ?: return
        if (!needed || now() - checkedAt < RECHECK_EVERY_MS) return
        val good = withContext(Dispatchers.IO) { client.stillGood(current.raw) } ?: return
        if (good) {
            checkedAt = now()
            keep()
        } else {
            note = null
            hint = ""
            checkedAt = 0
            withContext(Dispatchers.IO) { runCatching { file.delete() } }
            notice = "The code this device was unlocked with no longer works here. Type a code to carry on."
            _state.value = Lock.LOCKED
        }
    }

    private suspend fun keep() {
        val text = json.encodeToString(Kept.serializer(), Kept(note?.raw.orEmpty(), checkedAt))
        withContext(Dispatchers.IO) {
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(text)
            }
        }
    }

    companion object {
        const val RECHECK_EVERY_MS = 7 * 24 * 60 * 60 * 1000L
    }
}
