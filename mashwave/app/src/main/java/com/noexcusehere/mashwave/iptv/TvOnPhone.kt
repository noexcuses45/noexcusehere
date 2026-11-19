package com.noexcusehere.mashwave.iptv

import android.content.Context
import android.os.Build
import android.util.Log
import com.noexcusehere.mashwave.playback.PlayerConnection
import kotlinx.coroutines.CoroutineScope
import java.io.File

/** The TV tab's data on a phone: its files live in a folder Android leaves out of cloud backups. */
fun phoneTvRepository(context: Context, scope: CoroutineScope): TvRepository {
    // The name the app gives itself to TV services. Kept exactly as it has always been, so a
    // service that accepted it at sign-in goes on accepting it.
    TvNet.userAgent = "UltimateBeats/1.0 (Linux;Android ${Build.VERSION.RELEASE}) AndroidXMedia3/1.9.4"
    return TvRepository(TvStore(File(context.noBackupFilesDir, "tv")), scope) { Log.w("UltimateBeats", it) }
}

/** Changes channel on the phone's own player. */
fun TvRepository.zap(player: PlayerConnection, delta: Int): Boolean {
    val entry = step(player.state.value.current, delta) ?: return false
    player.play(listOf(entry))
    return true
}
