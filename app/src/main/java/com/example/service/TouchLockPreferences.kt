package com.example.service

import android.content.Context
import android.content.SharedPreferences

class TouchLockPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("touch_lock_settings", Context.MODE_PRIVATE)

    var isServiceEnabled: Boolean
        get() = prefs.getBoolean("service_enabled", false)
        set(value) = prefs.edit().putBoolean("service_enabled", value).apply()

    var idleTimeoutSeconds: Int
        get() = prefs.getInt("idle_timeout_seconds", 10)
        set(value) = prefs.edit().putInt("idle_timeout_seconds", value).apply()

    var isVibrationEnabled: Boolean
        get() = prefs.getBoolean("vibration_enabled", true)
        set(value) = prefs.edit().putBoolean("vibration_enabled", value).apply()

    var showTapHint: Boolean
        get() = prefs.getBoolean("show_tap_hint", true)
        set(value) = prefs.edit().putBoolean("show_tap_hint", value).apply()
}
