package com.lagradost.shiro.services

import DataStore.getKey
import DataStore.getKeys
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.lagradost.shiro.utils.VideoDownloadManager

const val RESTART_ALL_DOWNLOADS_AND_QUEUE = 1
const val RESTART_NONE = 0
const val START_VALUE_KEY = "start_value"

class VideoDownloadKeepAliveService : Service() {
    override fun onBind(p0: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channelId = "persistent_notification";
            val channel = NotificationChannel(
                channelId,
                "Download service notification",
                NotificationManager.IMPORTANCE_DEFAULT
            )

            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)

            val notification = NotificationCompat.Builder(this, channelId)
                .setContentTitle("")
                .setPriority(Notification.PRIORITY_MIN)
                .setContentText("").build()

            startForeground(1, notification)
        }
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val startValue = intent?.getIntExtra(START_VALUE_KEY, RESTART_NONE) ?: RESTART_NONE
        Log.i("Service", "Restarted with start value of $startValue")

        if (startValue == RESTART_ALL_DOWNLOADS_AND_QUEUE) {
            val keys = this.getKeys(VideoDownloadManager.KEY_RESUME_PACKAGES)
            val resumePkg = keys.mapNotNull { k -> this.getKey<VideoDownloadManager.DownloadResumePackage>(k) }

            for (pkg in resumePkg) { // ADD ALL CURRENT DOWNLOADS
//                VideoDownloadManager.downloadFromResume(this, pkg)
            }

            // ADD QUEUE
            val resumeQueue =
                this.getKey<List<VideoDownloadManager.DownloadQueueResumePackage>>(VideoDownloadManager.KEY_RESUME_QUEUE_PACKAGES)
            if (resumeQueue != null && resumeQueue.isNotEmpty()) {
                val sorted = resumeQueue.sortedBy { item -> item.index }
                for (queueItem in sorted) {
//                    VideoDownloadManager.downloadFromResume(this, queueItem.pkg)
                }
            }
        }

        return START_STICKY//super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        /*val broadcastIntent = Intent()
        broadcastIntent.action = "restart_service"
        broadcastIntent.setClass(this, VideoDownloadRestartReceiver::class.java)
        this.sendBroadcast(broadcastIntent)*/
        super.onDestroy()
    }
}