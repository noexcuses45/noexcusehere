package com.noexcusehere.vpn

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.noexcusehere.vpn.ui.ConnStatus
import com.noexcusehere.vpn.ui.EditorScreen
import com.noexcusehere.vpn.ui.EditorState
import com.noexcusehere.vpn.ui.HomeScreen
import com.noexcusehere.vpn.ui.HomeUiState
import com.noexcusehere.vpn.ui.KestrelTheme
import com.noexcusehere.vpn.ui.ServerItem
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders each screen state for real (Robolectric native graphics) and checks what a person
 * would see. Run with `./gradlew recordRoborazziDebug` to also write PNGs of every state to
 * app/build/screenshots.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-420dpi")
class ScreensTest {

    @get:Rule
    val compose = createComposeRule()

    private val servers = listOf(
        ServerItem("home", "Home server", "vpn.example.com:51820"),
        ServerItem("syd", "Sydney", "203.0.113.20:51820"),
    )

    private fun showHome(
        state: HomeUiState,
        dark: Boolean = true,
        onToggle: () -> Unit = {},
        onSelect: (String) -> Unit = {},
    ) {
        compose.setContent {
            KestrelTheme(darkTheme = dark) {
                HomeScreen(
                    state = state,
                    onToggle = onToggle,
                    onSelect = onSelect,
                    onEdit = {},
                    onDelete = {},
                    onImportFile = {},
                    onScanQr = {},
                    onPaste = {},
                    onOpenVpnSettings = {},
                )
            }
        }
    }

    private fun capture(name: String) {
        compose.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    @Test
    fun withNoServers_explainsWhatToAdd_andConnectIsDisabled() {
        showHome(HomeUiState())

        compose.onNodeWithText("No servers yet").assertIsDisplayed()
        compose.onNodeWithText("Import config file").assertIsDisplayed()
        compose.onNodeWithText("Scan QR code").assertIsDisplayed()
        compose.onNodeWithText("Paste or type config").assertIsDisplayed()
        compose.onNodeWithContentDescription("Connect").assertIsNotEnabled()
        capture("1_empty")
    }

    @Test
    fun disconnected_offersToConnectToTheSelectedServer() {
        var toggles = 0
        showHome(HomeUiState(servers = servers, selectedId = "home"), onToggle = { toggles++ })

        compose.onNodeWithText("Not connected").assertIsDisplayed()
        compose.onNodeWithText("Tap to connect to Home server").assertIsDisplayed()
        compose.onNodeWithText("Home server").assertIsSelected()
        compose.onNodeWithContentDescription("Connect").assertIsEnabled().performClick()
        assertEquals(1, toggles)
        capture("2_disconnected")
    }

    @Test
    fun tappingAnotherServerSelectsIt() {
        val picked = mutableListOf<String>()
        showHome(HomeUiState(servers = servers, selectedId = "home"), onSelect = { picked += it })

        compose.onNodeWithText("Sydney").performClick()

        assertEquals(listOf("syd"), picked)
    }

    @Test
    fun waitingForTheServer_saysSoRatherThanClaimingToBeConnected() {
        showHome(
            HomeUiState(
                servers = servers,
                selectedId = "home",
                status = ConnStatus.WAITING,
                activeId = "home",
                uptimeSeconds = 14,
                txBytes = 444,
            ),
        )

        compose.onNodeWithText("Connecting…").assertIsDisplayed()
        compose.onNodeWithText("No answer from Home server yet", substring = true).assertIsDisplayed()
        capture("3_waiting")
    }

    @Test
    fun connected_showsServerUptimeAndTraffic() {
        val state = HomeUiState(
            servers = servers,
            selectedId = "home",
            status = ConnStatus.CONNECTED,
            activeId = "home",
            uptimeSeconds = 3_725,
            rxBytes = 48_300_000,
            txBytes = 5_120_000,
            handshakeAgeSeconds = 42,
        )
        showHome(state)

        compose.onNodeWithText("Connected").assertIsDisplayed()
        compose.onNodeWithText("Home server · 1:02:05").assertIsDisplayed()
        compose.onNodeWithText("46.1 MB").assertIsDisplayed()
        compose.onNodeWithText("4.88 MB").assertIsDisplayed()
        compose.onNodeWithText("42s ago").assertIsDisplayed()
        compose.onNodeWithText("In use").assertIsDisplayed()
        compose.onNodeWithContentDescription("Disconnect").assertIsEnabled()
        capture("4_connected")
    }

    @Test
    fun connected_lightTheme() {
        showHome(
            HomeUiState(
                servers = servers,
                selectedId = "home",
                status = ConnStatus.CONNECTED,
                activeId = "home",
                uptimeSeconds = 95,
                rxBytes = 1_300_000,
                txBytes = 220_000,
                handshakeAgeSeconds = 3,
            ),
            dark = false,
        )

        compose.onNodeWithText("just now").assertIsDisplayed()
        capture("5_connected_light")
    }

    @Test
    fun editor_showsTheProblemWithABadConfig() {
        var saved: Pair<String, String>? = null
        compose.setContent {
            KestrelTheme(darkTheme = true) {
                EditorScreen(
                    editor = EditorState(
                        session = 1,
                        profileId = null,
                        name = "Server 1",
                        text = "[Interface]\nPrivateKey = oops\nAddress = 10.8.0.2/32\n\n[Peer]\nEndpoint = vpn.example.com:51820",
                        error = uiText(R.string.err_invalid_key, "Interface", "PrivateKey"),
                    ),
                    onSave = { name, text -> saved = name to text },
                    onEdited = {},
                    onClose = {},
                    onMessage = {},
                )
            }
        }

        compose.onNodeWithText("Add server").assertIsDisplayed()
        compose.onNodeWithText("PrivateKey in [Interface] is not a valid key").assertIsDisplayed()
        compose.onNodeWithText("Save").performClick()
        assertEquals("Server 1", saved?.first)
        capture("6_editor_error")
    }
}
