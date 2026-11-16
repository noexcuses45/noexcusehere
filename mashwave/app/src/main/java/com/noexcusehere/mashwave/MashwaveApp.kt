package com.noexcusehere.mashwave

import android.app.Application
import android.content.Context
import com.noexcusehere.mashwave.automash.AutoMashupMaker
import com.noexcusehere.mashwave.cast.CastController
import com.noexcusehere.mashwave.data.LibraryRepository
import com.noexcusehere.mashwave.data.UserStore
import com.noexcusehere.mashwave.editor.EditorStore
import com.noexcusehere.mashwave.editor.ExportManager
import com.noexcusehere.mashwave.iptv.Multiview
import com.noexcusehere.mashwave.iptv.TvRepository
import com.noexcusehere.mashwave.photo.PhotoFiles
import com.noexcusehere.mashwave.playback.MashupEngine
import com.noexcusehere.mashwave.playback.PlayerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/** The name people see: on the home screen, inside the app, and on the folders it saves into. */
const val APP_NAME = "Ultimate Media Manager"

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
    lateinit var autoMashup: AutoMashupMaker
        private set
    lateinit var editorStore: EditorStore
        private set
    lateinit var exporter: ExportManager
        private set
    lateinit var cast: CastController
        private set
    lateinit var tv: TvRepository
        private set
    lateinit var multiview: Multiview
        private set

    /** Short messages for the user ("Added to playlist", "Can't play this file" ...). */
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 16)

    override fun onCreate() {
        super.onCreate()
        store = UserStore(this, scope)
        library = LibraryRepository(this, scope, store)
        player = PlayerConnection(this, scope)
        mashup = MashupEngine(this, scope)
        autoMashup = AutoMashupMaker(this, scope)
        editorStore = EditorStore(this, scope)
        // Edited photos nobody uses any more are cleared out in the background.
        scope.launch(Dispatchers.IO) { runCatching { PhotoFiles.sweep(this@MashwaveApp, editorStore.projects.value) } }
        exporter = ExportManager(this, scope)
        cast = CastController(this, scope)
        tv = TvRepository(this, scope)
        multiview = Multiview(this)
    }

    fun say(message: String) {
        messages.tryEmit(message)
    }
}

val Context.app: MashwaveApp
    get() = applicationContext as MashwaveApp
