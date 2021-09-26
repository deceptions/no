package com.lagradost.shiro.services

import android.app.IntentService
import android.content.Intent
import com.lagradost.shiro.utils.VideoDownloadManager

class VideoDownloadService : IntentService("VideoDownloadService") {
    override fun onHandleIntent(intent: Intent?) {
        if (intent != null) {
            val id = intent.getIntExtra("id", -1)
            val type = intent.getStringExtra("type")
            if (id != -1 && type != null) {
                val state = when (type) {
                    "resume" -> VideoDownloadManager.DownloadActionType.Resume
                    "pause" -> VideoDownloadManager.DownloadActionType.Pause
                    "stop" -> VideoDownloadManager.DownloadActionType.Stop
                    else -> return
                }
                VideoDownloadManager.downloadEvent.invoke(Pair(id, state))
            }
        }
    }
}