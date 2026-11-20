package com.noexcusehere.mashwave

import com.noexcusehere.mashwave.licence.LicenceCheck
import com.noexcusehere.mashwave.licence.LicenceClient
import com.noexcusehere.mashwave.licence.LicenceCodes
import com.noexcusehere.mashwave.licence.LicenceConfig
import com.noexcusehere.mashwave.licence.LicenceDevice
import com.noexcusehere.mashwave.licence.LicenceManager
import com.noexcusehere.mashwave.licence.Lock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/** Unlock codes: reading a code, telling a genuine note from a made-up one, and staying unlocked. */
class LicenceTest {
    private val device = LicenceDevice.fingerprint("this-test-device")
    private val keys: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val publicKey: String = Base64.getEncoder().encodeToString(keys.public.encoded)
    private var server: ServerSocket? = null
    private val folder = File(System.getProperty("java.io.tmpdir"), "umm-licence-" + System.nanoTime()).apply { mkdirs() }

    @After
    fun tidyUp() {
        stopServing()
        folder.deleteRecursively()
    }

    /** Signs the way the code service does: two 32-byte numbers end to end, base64 for web addresses. */
    private fun note(forDevice: String = device, product: String = "umm", signer: KeyPair = keys, hint: String = "MNPQR"): String {
        val url = Base64.getUrlEncoder().withoutPadding()
        val body = """{"v":1,"p":"$product","c":"aa79bd01-e3c8-483c-ab36-95b21b21cd94","h":"$hint","d":"$forDevice","t":1795000000}"""
        val signed = url.encodeToString(body.toByteArray())
        val signature = Signature.getInstance("SHA256withECDSAinP1363Format").apply {
            initSign(signer.private)
            update(signed.toByteArray())
        }.sign()
        return signed + "." + url.encodeToString(signature)
    }

    /** A stand-in code service. [reply] gets the question and returns the answer's status and text. */
    private fun serve(reply: (String) -> Pair<Int, String>): String {
        val made = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        server = made
        Thread {
            while (!made.isClosed) {
                val client = try {
                    made.accept()
                } catch (e: Exception) {
                    break
                }
                runCatching {
                    client.use { socket ->
                        val input = socket.getInputStream()
                        // The headers, up to the empty line; then as many bytes as Content-Length says.
                        val head = StringBuilder()
                        while (!head.endsWith("\r\n\r\n")) {
                            val b = input.read()
                            if (b < 0) break
                            head.append(b.toChar())
                        }
                        val length = Regex("""(?i)content-length:\s*(\d+)""").find(head)?.groupValues?.get(1)?.toInt() ?: 0
                        val body = ByteArray(length)
                        var filled = 0
                        while (filled < length) {
                            val n = input.read(body, filled, length - filled)
                            if (n < 0) break
                            filled += n
                        }
                        val (status, text) = reply(String(body, 0, filled, Charsets.UTF_8))
                        val bytes = text.toByteArray()
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 $status X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes)
                            flush()
                        }
                    }
                }
            }
        }.apply { isDaemon = true }.start()
        return "http://127.0.0.1:${made.localPort}/"
    }

    private fun stopServing() {
        runCatching { server?.close() }
    }

    private fun manager(endpoint: String, clock: () -> Long = { 1_000L }, file: File = File(folder, "licence.json")) =
        LicenceManager(endpoint, publicKey, file, device, "android", "1.0.20", CoroutineScope(Dispatchers.Unconfined), clock)

    @Test
    fun readsACodeHoweverItIsTyped() {
        assertEquals("ABCDEFGHJKMNPQR", LicenceCodes.tidy("abcde-fghjk-mnpqr"))
        assertEquals("ABCDEFGHJKMNPQR", LicenceCodes.tidy("  ABCDE FGHJK MNPQR "))
        assertEquals("ABCDE-FGHJK-MNPQR", LicenceCodes.pretty("ABCDEFGHJKMNPQR"))
        assertNull(LicenceCodes.tidy("ABCDE-FGHJK-MNPQ"))
        assertNull(LicenceCodes.tidy("ABCDE-FGHJK-MNPQRS"))
        // No I, L, O, 0 or 1 in any code.
        assertNull(LicenceCodes.tidy("ABCDE-FGHJK-MN0QR"))
        assertNull(LicenceCodes.tidy("ABCDE-FGHJK-MNIQR"))
        assertNull(LicenceCodes.problemWith("abcde-fghjk-mnpqr"))
        assertTrue(LicenceCodes.problemWith("")!!.contains("first"))
        assertTrue(LicenceCodes.problemWith("ABCDE")!!.contains("short"))
        assertTrue(LicenceCodes.problemWith("ABCDE-FGHJK-MNPQR-ST")!!.contains("long"))
        assertTrue(LicenceCodes.problemWith("ABCDE-FGHJK-MN0QR")!!.contains("never"))
        assertEquals(LicenceCodes.LENGTH, LicenceCodes.EXAMPLE.count { it != '-' })
        assertNotNull(LicenceCodes.tidy(LicenceCodes.EXAMPLE))
    }

    @Test
    fun aGenuineNoteForThisDeviceIsAccepted() {
        val read = LicenceCheck.read(note(), publicKey, device)
        assertNotNull(read)
        assertEquals("MNPQR", read!!.hint)
        assertEquals(device, read.device)
    }

    @Test
    fun anythingElseIsNot() {
        val good = note()
        // Copied from another device.
        assertNull(LicenceCheck.read(note(forDevice = LicenceDevice.fingerprint("someone-else")), publicKey, device))
        // For another product.
        assertNull(LicenceCheck.read(note(product = "other"), publicKey, device))
        // Signed by somebody who is not the code service.
        val stranger = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        assertNull(LicenceCheck.read(note(signer = stranger), publicKey, device))
        // Altered after signing: the words of another note with this note's signature.
        val other = note(forDevice = LicenceDevice.fingerprint("someone-else"))
        assertNull(LicenceCheck.read(other.substringBefore('.') + "." + good.substringAfter('.'), publicKey, LicenceDevice.fingerprint("someone-else")))
        // Cut short, empty, rubbish.
        assertNull(LicenceCheck.read(good.substringBefore('.'), publicKey, device))
        assertNull(LicenceCheck.read(good.dropLast(6), publicKey, device))
        assertNull(LicenceCheck.read("", publicKey, device))
        assertNull(LicenceCheck.read("a.b", publicKey, device))
        assertNull(LicenceCheck.read(good, "not a key", device))
    }

    @Test
    fun aNoteWrittenByTheRealServiceCodeIsUnderstood() {
        // Made by licence-service/test/note.test.mjs --print: the same code that runs in the service.
        val key = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEaYVWuYYiCxpr1Z2MyI4njQihTfGGt/kdFR8QWECoOut3+51ZOgmRWRXeyD5OWY/WwaNKJMqvW3kDAh5FVkRgww=="
        val real = "eyJ2IjoxLCJwIjoidW1tIiwiYyI6ImFhNzliZDAxLWUzYzgtNDgzYy1hYjM2LTk1YjIxYjIxY2Q5NCIsImgiOiJGVE1RNSIsImQiOiIwMTIzNDU2Nzg5YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZiIsInQiOjE3OTUxNDQwMTd9.KFZrOU3kBUrp1qqbZDEh-12gXmyplx9lKgSaVjLGUSKwcGhbRu79GjlXuzNEub6w0OeZoz57liz9qEVxNfswhw"
        val read = LicenceCheck.read(real, key, "0123456789abcdef0123456789abcdef")
        assertNotNull(read)
        assertEquals("FTMQ5", read!!.hint)
        assertNull(LicenceCheck.read(real, key, device))
    }

    @Test
    fun signaturesAreWrappedTheWayJavaWantsThem() {
        // Leading zeros are dropped, and a first byte that would read as negative gets a zero in front.
        val raw = ByteArray(64)
        raw[31] = 0x05
        raw[32] = 0x80.toByte()
        val der = LicenceCheck.derFromRaw(raw)!!
        assertEquals(listOf(0x30, 38, 0x02, 1, 0x05, 0x02, 33, 0x00, 0x80), der.take(9).map { it.toInt() and 0xff })
        assertNull(LicenceCheck.derFromRaw(ByteArray(63)))
    }

    @Test
    fun fingerprintsSayNothingAboutTheDevice() {
        val one = LicenceDevice.fingerprint("a1b2c3d4e5f6")
        assertEquals(32, one.length)
        assertTrue(one.all { it in "0123456789abcdef" })
        assertFalse(one.contains("a1b2c3d4e5f6"))
        assertEquals(one, LicenceDevice.fingerprint("a1b2c3d4e5f6"))
        assertFalse(one == LicenceDevice.fingerprint("a1b2c3d4e5f7"))
    }

    @Test
    fun withNoCodeServiceNothingIsLocked() {
        val open = LicenceManager("", "", File(folder, "none.json"), device, "android", "1", CoroutineScope(Dispatchers.Unconfined))
        assertEquals(Lock.NOT_NEEDED, open.state.value)
        assertNull(runBlocking { open.unlock("whatever") })
    }

    @Test
    fun aCodeUnlocksAndStaysUnlockedWithoutTheInternet() {
        val asked = ArrayList<String>()
        val endpoint = serve { question ->
            asked += question
            200 to """{"ok":true,"note":"${note()}"}"""
        }
        val first = manager(endpoint)
        assertEquals(Lock.LOCKED, first.state.value)
        assertNull(runBlocking { first.unlock("abcde-fghjk-mnpqr") })
        assertEquals(Lock.UNLOCKED, first.state.value)
        assertEquals("MNPQR", first.hint)
        // What was sent: the code as the service knows it and the fingerprint, nothing about the person.
        assertTrue(asked.single().contains("\"code\":\"ABCDEFGHJKMNPQR\""))
        assertTrue(asked.single().contains("\"device\":\"$device\""))
        assertTrue(asked.single().contains("\"action\":\"unlock\""))

        // Next time the app opens, with the service gone: still unlocked, and nothing is asked.
        stopServing()
        val later = manager(endpoint)
        assertEquals(Lock.UNLOCKED, later.state.value)
        assertEquals("MNPQR", later.hint)
    }

    @Test
    fun refusalsAreExplained() {
        var answer = 200 to """{"ok":false,"reason":"unknown"}"""
        val endpoint = serve { answer }
        val lock = manager(endpoint)
        assertTrue(runBlocking { lock.unlock("ABCDE-FGHJK-MNPQR") }!!.contains("not known"))
        answer = 200 to """{"ok":false,"reason":"full","devices":3}"""
        assertTrue(runBlocking { lock.unlock("ABCDE-FGHJK-MNPQR") }!!.contains("3 devices"))
        answer = 200 to """{"ok":false,"reason":"off"}"""
        assertTrue(runBlocking { lock.unlock("ABCDE-FGHJK-MNPQR") }!!.contains("switched off"))
        answer = 500 to """{"ok":false,"reason":"trouble"}"""
        assertTrue(runBlocking { lock.unlock("ABCDE-FGHJK-MNPQR") }!!.contains("did not answer properly"))
        // Something typed that cannot be a code is caught before anything is sent.
        assertTrue(runBlocking { lock.unlock("ABC") }!!.contains("short"))
        // A "yes" with a note that is not genuine (or is for another device) unlocks nothing.
        answer = 200 to """{"ok":true,"note":"${note(forDevice = LicenceDevice.fingerprint("someone-else"))}"}"""
        assertNotNull(runBlocking { lock.unlock("ABCDE-FGHJK-MNPQR") })
        assertEquals(Lock.LOCKED, lock.state.value)
        // No connection at all.
        stopServing()
        assertTrue(runBlocking { lock.unlock("ABCDE-FGHJK-MNPQR") }!!.contains("no internet"))
        assertEquals(Lock.LOCKED, lock.state.value)
    }

    @Test
    fun aCodeSwitchedOffLocksTheAppAgainButSilenceDoesNot() {
        var answer = 200 to """{"ok":true,"note":"${note()}"}"""
        var checks = 0
        val endpoint = serve { question ->
            if (question.contains("\"check\"")) checks++
            answer
        }
        var time = 1_000L
        val file = File(folder, "licence.json")
        val lock = manager(endpoint, { time }, file)
        assertNull(runBlocking { lock.unlock("ABCDE-FGHJK-MNPQR") })

        // Not due yet: nothing is asked.
        time += LicenceManager.RECHECK_EVERY_MS - 1
        runBlocking { lock.lookAgain() }
        assertEquals(0, checks)

        // Due, and the service says the code still stands.
        time += 2
        answer = 200 to """{"ok":true}"""
        runBlocking { lock.lookAgain() }
        assertEquals(1, checks)
        assertEquals(Lock.UNLOCKED, lock.state.value)

        // Due again, but the service is in trouble: nothing changes.
        time += LicenceManager.RECHECK_EVERY_MS + 1
        answer = 500 to "oops"
        runBlocking { lock.lookAgain() }
        assertEquals(Lock.UNLOCKED, lock.state.value)
        assertTrue(file.exists())

        // The service says the code was switched off: locked, and it stays locked next time too.
        answer = 200 to """{"ok":false,"reason":"off"}"""
        runBlocking { lock.lookAgain() }
        assertEquals(Lock.LOCKED, lock.state.value)
        assertEquals(Lock.LOCKED, manager(endpoint, { time }, file).state.value)
    }

    @Test
    fun aNoteCopiedOntoAnotherDeviceUnlocksNothing() {
        val endpoint = serve { 200 to """{"ok":true,"note":"${note()}"}""" }
        val file = File(folder, "licence.json")
        assertNull(runBlocking { manager(endpoint, file = file).unlock("ABCDE-FGHJK-MNPQR") })
        val elsewhere = LicenceManager(endpoint, publicKey, file, LicenceDevice.fingerprint("another-computer"), "windows", "1", CoroutineScope(Dispatchers.Unconfined))
        assertEquals(Lock.LOCKED, elsewhere.state.value)
    }

    @Test
    fun theBuiltInSettingsAreSafeToShip() {
        // Either there is no code service yet (the app opens for everyone), or it is a real one:
        // reached over a secure connection, never a test address left in by mistake.
        if (LicenceConfig.ENDPOINT.isNotEmpty()) {
            assertTrue(LicenceConfig.ENDPOINT.startsWith("https://"))
            assertFalse(Regex("""localhost|127\.|10\.|192\.168\.""").containsMatchIn(LicenceConfig.ENDPOINT))
            assertTrue(LicenceConfig.PUBLIC_KEY.length > 100)
        }
        assertTrue(LicenceClient.wordsFor(LicenceClient.Why.ALL_USED, 1).contains("one device"))
    }
}
