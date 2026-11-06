package com.noexcusehere.screenrecorder

import android.content.Context

enum class Quality(val shortSide: Int, val bitRate: Int) {
    HIGH(0, 12_000_000),
    MEDIUM(720, 6_000_000),
    LOW(480, 2_500_000);
}

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("recorder", Context.MODE_PRIVATE)

    var recordMic: Boolean
        get() = sp.getBoolean("mic", false)
        set(v) = sp.edit().putBoolean("mic", v).apply()

    var countdown: Boolean
        get() = sp.getBoolean("countdown", true)
        set(v) = sp.edit().putBoolean("countdown", v).apply()

    var quality: Quality
        get() = runCatching { Quality.valueOf(sp.getString("quality", Quality.HIGH.name)!!) }.getOrDefault(Quality.HIGH)
        set(v) = sp.edit().putString("quality", v.name).apply()
}
