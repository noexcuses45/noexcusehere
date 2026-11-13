package com.noexcusehere.mashwave

import android.app.Application
import android.content.Context
import com.noexcusehere.mashwave.data.LibraryRepository
import com.noexcusehere.mashwave.data.UserStore
import com.noexcusehere.mashwave.playback.MashupEngine
import com.noexcusehere.mashwave.playback.PlayerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow

/** Holds the few long-lived objects the whole app shares. */
class MashwaveApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var store: UserStore
        private set
    lateinit var library: LibraryRepository
        private set
    lateinit var player: PlayerConnection
        private set
    lateinit var mashup: MashupEngine
        private set

    /** Short messages for the user ("Added to playlist", "Can't play this file" ...). */
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 16)

    override fun onCreate() {
        super.onCreate()
        store = UserStore(this, scope)
        library = LibraryRepository(this, scope)
        player = PlayerConnection(this, scope)
        mashup = MashupEngine(this, scope)
    }

    fun say(message: String) {
        messages.tryEmit(message)
    }
}

val Context.app: MashwaveApp
    get() = applicationContext as MashwaveApp
