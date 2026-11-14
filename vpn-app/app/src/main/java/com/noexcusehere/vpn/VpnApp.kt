package com.noexcusehere.vpn

import android.app.Application
import com.noexcusehere.vpn.data.ProfileStore
import com.noexcusehere.vpn.vpn.VpnController
import com.noexcusehere.vpn.vpn.WireGuardBackend
import com.wireguard.android.backend.GoBackend
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class VpnApp : Application() {

    /** Lives as long as the process: the tunnel outlives any single screen. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var store: ProfileStore
        private set

    // Replaceable so tests can run the real UI against a fake tunnel.
    lateinit var controller: VpnController

    override fun onCreate() {
        super.onCreate()
        store = ProfileStore(File(filesDir, "profiles.json"))
        controller = VpnController(WireGuardBackend(this), store, appScope)

        // Android starts the VPN service itself when "Always-on VPN" is enabled (for example
        // after a reboot) or when it restarts the service after reclaiming memory. In both
        // cases reconnect to the selected server.
        GoBackend.setAlwaysOnCallback { controller.connectSelected() }
    }
}
