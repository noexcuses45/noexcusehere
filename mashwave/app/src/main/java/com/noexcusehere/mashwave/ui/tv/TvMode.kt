package com.noexcusehere.mashwave.ui.tv

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration

/**
 * Whether the app should show its TV layout - big, sideways, and worked entirely with a remote's
 * arrows, OK and Back - instead of the phone layout. Fire TV and Android TV sticks and boxes get
 * it by themselves; a plain Android box that calls itself a tablet can be switched over by hand.
 */
object TvMode {
    private const val PREFS = "layout"
    private const val KEY = "layout"
    const val AUTO = "auto"
    const val TV = "tv"
    const val PHONE = "phone"

    /** Launching with this extra chooses the layout, as if it had been chosen in Settings (used when trying the app out). */
    const val EXTRA_FORCE = "tvmode"

    fun choice(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, AUTO) ?: AUTO

    fun choose(context: Context, layout: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, layout).apply()
    }

    /** True on a Fire TV or Android TV device, or anything else with no touch screen. */
    fun isTvDevice(context: Context): Boolean {
        val packages = context.packageManager
        val mode = (context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.currentModeType
        return looksLikeTv(
            television = mode == Configuration.UI_MODE_TYPE_TELEVISION,
            fireTv = packages.hasSystemFeature("amazon.hardware.fire_tv"),
            leanback = packages.hasSystemFeature(PackageManager.FEATURE_LEANBACK) || packages.hasSystemFeature("android.software.leanback_only"),
            touchScreen = packages.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN),
        )
    }

    fun looksLikeTv(television: Boolean, fireTv: Boolean, leanback: Boolean, touchScreen: Boolean): Boolean =
        television || fireTv || leanback || !touchScreen

    /** The layout to use now: what the viewer chose (in Settings, or when launching), else what the device is. */
    fun wanted(context: Context, intent: Intent?): Boolean {
        if (intent?.hasExtra(EXTRA_FORCE) == true) {
            // Remembered, so the layout stays the same if the app is restarted behind the scenes.
            choose(context, if (intent.getBooleanExtra(EXTRA_FORCE, false)) TV else PHONE)
            intent.removeExtra(EXTRA_FORCE)
        }
        return decide(choice(context), isTvDevice(context))
    }

    fun decide(choice: String, tvDevice: Boolean): Boolean = when (choice) {
        TV -> true
        PHONE -> false
        else -> tvDevice
    }
}
