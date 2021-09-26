package com.lagradost.shiro.utils

import android.content.Context
import android.content.Intent
import androidx.media.session.MediaButtonReceiver

class NonCrashMediaButtonReceiver : MediaButtonReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            super.onReceive(context, intent)
        } catch (e: IllegalStateException) {
        }
    }
}