package com.noexcusehere.vpn

import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.noexcusehere.vpn.vpn.TunnelStats
import com.noexcusehere.vpn.vpn.VpnController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowVpnService

/**
 * Drives the real app (activity, view model, storage, controller) the way a person would:
 * add a server, connect, disconnect. Only the WireGuard engine itself is replaced, because
 * it needs a real device.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-420dpi")
class AppFlowTest {

    private val backend = FakeBackend()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Swaps in the fake engine before the activity starts. */
    @get:Rule(order = 0)
    val fakeEngine = object : ExternalResource() {
        override fun before() {
            val app = ApplicationProvider.getApplicationContext<VpnApp>()
            app.controller = VpnController(backend, app.store, scope, io = Dispatchers.Unconfined, settleMs = 0)
            // As if the user had already accepted Android's "Connection request" dialog.
            ShadowVpnService.setPrepareResult(null)
        }

        override fun after() {
            scope.cancel()
        }
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private fun waitForText(text: String) {
        compose.waitUntil(timeoutMillis = 10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun addAServer_connect_thenDisconnect() {
        // A fresh install has no servers and nothing to connect to.
        compose.onNodeWithText("No servers yet").assertIsDisplayed()

        // Add one by typing its configuration.
        compose.onNodeWithText("Paste or type config").performClick()
        compose.onNodeWithText("Server 1").performTextReplacement("Home")
        compose.onNodeWithText("WireGuard configuration").performTextInput(validConfig("vpn.example.com:51820"))
        compose.onNodeWithText("Save").performClick()

        waitForText("Tap to connect to Home")
        compose.onNodeWithText("vpn.example.com:51820").assertIsDisplayed()

        // Connect. The tunnel is up, but until the server answers we must not say "Connected".
        compose.onNodeWithContentDescription("Connect").performClick()
        waitForText("Waiting for Home to answer")
        assertTrue(backend.isUp)
        assertEquals(listOf("Home"), backend.sessions)

        // The server answers: now it is connected, and traffic is shown.
        backend.stats = TunnelStats(rxBytes = 3_000_000, txBytes = 150_000, lastHandshakeEpochMs = System.currentTimeMillis())
        compose.waitUntil(timeoutMillis = 10_000) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
            compose.onAllNodesWithText("Connected").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("2.86 MB").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/screenshots/7_flow_connected.png")

        // Disconnect.
        compose.onNodeWithContentDescription("Disconnect").performClick()
        waitForText("Not connected")
        assertFalse(backend.isUp)
    }

    @Test
    fun savingABrokenConfig_keepsTheEditorOpenAndSaysWhy() {
        compose.onNodeWithText("Paste or type config").performClick()
        compose.onNodeWithText("WireGuard configuration").performTextInput("this is not a config")
        compose.onNodeWithText("Save").performClick()

        waitForText("Unrecognised line: this is not a config")
        compose.onNodeWithText("Add server").assertIsDisplayed()
    }
}
