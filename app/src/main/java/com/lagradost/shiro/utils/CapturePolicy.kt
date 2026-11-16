package com.lagradost.shiro.utils

import android.app.Activity
import android.content.Context
import android.view.WindowManager

/** Screenshots, screen recording and the recents thumbnail are blocked unless the user turns that off (behind a puzzle). */
object CapturePolicy {
    private fun prefs(c: Context) = c.getSharedPreferences("capture_policy", Context.MODE_PRIVATE)
    fun allowed(c: Context) = prefs(c).getBoolean("allow", false)
    fun set(c: Context, allow: Boolean) = prefs(c).edit().putBoolean("allow", allow).apply()

    fun apply(a: Activity) {
        if (allowed(a)) a.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else a.window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
    }
}
