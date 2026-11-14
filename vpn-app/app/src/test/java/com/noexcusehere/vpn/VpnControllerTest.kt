package com.noexcusehere.vpn

import com.noexcusehere.vpn.data.Profile
import com.noexcusehere.vpn.data.ProfileStore
import com.noexcusehere.vpn.vpn.Phase
import com.noexcusehere.vpn.vpn.TunnelStats
import com.noexcusehere.vpn.vpn.VpnController
import com.wireguard.android.backend.BackendException
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VpnControllerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val backend = FakeBackend()
    private lateinit var store: ProfileStore
    private var clock = 10_000L

    @Before
    fun setUp() {
        store = ProfileStore(File(folder.root, "profiles.json"))
        store.save(Profile("home", "Home server", validConfig(), "vpn.example.com:51820"))
        store.save(Profile("work", "Work", validConfig("work.example.com:51820"), "work.example.com:51820"))
    }

    private fun controller(scope: CoroutineScope = this.scope.backgroundScope) =
        VpnController(backend, store, scope, io = dispatcher, elapsedRealtime = { clock }, settleMs = 0)

    @Test
    fun connectBringsTheSelectedTunnelUp() = scope.runTest {
        val controller = controller()

        controller.connect("home").join()

        assertEquals(Phase.UP, controller.state.value.phase)
        assertEquals("home", controller.state.value.profileId)
        assertEquals(listOf("Home_server"), backend.sessions)
        assertTrue(backend.isUp)
    }

    @Test
    fun disconnectBringsItDown() = scope.runTest {
        val controller = controller()
        controller.connect("home").join()

        controller.disconnect().join()

        assertEquals(Phase.DOWN, controller.state.value.phase)
        assertNull(controller.state.value.profileId)
        assertFalse(backend.isUp)
    }

    @Test
    fun aFailedStartReportsWhyAndLeavesNothingRunning() = scope.runTest {
        val controller = controller()
        val messages = mutableListOf<UiText>()
        backgroundScope.launch { controller.messages.toList(messages) }
        backend.failWith = BackendException(BackendException.Reason.DNS_RESOLUTION_FAILURE, "vpn.example.com")

        controller.connect("home").join()

        assertEquals(Phase.DOWN, controller.state.value.phase)
        assertFalse(backend.isUp)
        assertEquals(listOf(uiText(R.string.err_dns, "vpn.example.com")), messages)
    }

    @Test
    fun connectingToAnotherServerSwitchesOver() = scope.runTest {
        val controller = controller()
        controller.connect("home").join()

        controller.connect("work").join()

        assertEquals(Phase.UP, controller.state.value.phase)
        assertEquals("work", controller.state.value.profileId)
        assertEquals(listOf("Home_server", "Work"), backend.sessions)
        assertTrue("the first tunnel is stopped before the second starts", backend.downCalls >= 1)
    }

    @Test
    fun androidStoppingTheVpnIsReflectedAndReported() = scope.runTest {
        val controller = controller()
        val messages = mutableListOf<UiText>()
        backgroundScope.launch { controller.messages.toList(messages) }
        controller.connect("home").join()

        backend.stopBySystem()

        assertEquals(Phase.DOWN, controller.state.value.phase)
        assertEquals(listOf(uiText(R.string.msg_revoked)), messages)
    }

    @Test
    fun aBrokenSavedConfigIsReportedInsteadOfStarting() = scope.runTest {
        store.save(Profile("bad", "Bad", "[Interface]\nPrivateKey = nope", null))
        val controller = controller()
        val messages = mutableListOf<UiText>()
        backgroundScope.launch { controller.messages.toList(messages) }

        controller.connect("bad").join()

        assertEquals(Phase.DOWN, controller.state.value.phase)
        assertEquals(listOf(R.string.err_invalid_key), messages.map { it.id })
        assertTrue(backend.sessions.isEmpty())
    }

    @Test
    fun snapshotsCarryUptimeAndTrafficWhileConnected() = scope.runTest {
        val controller = controller()
        assertNull(controller.snapshots().first())

        controller.connect("home").join()
        backend.stats = TunnelStats(rxBytes = 2048, txBytes = 512, lastHandshakeEpochMs = 99)
        clock += 7_000

        val snapshot = controller.snapshots().first()!!
        assertEquals(7_000, snapshot.uptimeMs)
        assertEquals(2048, snapshot.stats.rxBytes)
        assertEquals(99, snapshot.stats.lastHandshakeEpochMs)
    }

    @Test
    fun disconnectingDuringASlowStartAbandonsTheAttempt() = runBlocking {
        // Real threads here: the start blocks the way a DNS lookup would.
        backend.holdUp = CountDownLatch(1)
        val job = Job()
        val controller = VpnController(
            backend, store, CoroutineScope(coroutineContext + job), io = Dispatchers.IO, settleMs = 0,
        )

        controller.connect("home")
        withTimeout(5_000) { controller.state.first { it.phase == Phase.STARTING } }
        assertTrue(backend.upEntered.await(5, TimeUnit.SECONDS))

        withTimeout(5_000) { controller.disconnect().join() }

        assertEquals(Phase.DOWN, controller.state.value.phase)
        assertFalse(backend.isUp)
        job.cancel()
    }
}
