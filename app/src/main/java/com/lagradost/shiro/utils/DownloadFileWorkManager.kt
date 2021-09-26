package com.lagradost.shiro.utils

import DataStore.getKey
import DataStore.removeKey
import android.app.Notification
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.lagradost.shiro.utils.DownloadManager.WORK_KEY_INFO
import com.lagradost.shiro.utils.DownloadManager.WORK_KEY_LINK
import com.lagradost.shiro.utils.DownloadManager.WORK_KEY_PACKAGE
import com.lagradost.shiro.utils.DownloadManager.WORK_KEY_SHOW_TOAST
import com.lagradost.shiro.utils.DownloadManager.downloadEpisode
import com.lagradost.shiro.utils.VideoDownloadManager.downloadCheck
import com.lagradost.shiro.utils.VideoDownloadManager.downloadFromResume
import com.lagradost.shiro.utils.VideoDownloadManager.downloadStatusEvent
import kotlinx.coroutines.delay

const val DOWNLOAD_CHECK = "DownloadCheck"

class DownloadFileWorkManager(val context: Context, val workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {
    override suspend fun doWork(): Result {
        val key = workerParams.inputData.getString("key")
        try {
            println("KEY $key")
            if (key == DOWNLOAD_CHECK) {
                downloadCheck(applicationContext, ::handleNotification)?.let {
                    awaitDownload(it)
                }
            } else if (key != null) {
                val info = applicationContext.getKey<DownloadManager.DownloadInfo>(WORK_KEY_INFO, key)
                val link = applicationContext.getKey<Array<ExtractorLink>>(WORK_KEY_LINK, key)
                val pkg =
                    applicationContext.getKey<VideoDownloadManager.DownloadResumePackage>(WORK_KEY_PACKAGE, key)
                val showToast =
                    applicationContext.getKey(WORK_KEY_SHOW_TOAST, key, true) ?: true

                if (info != null && link != null) {
                    val id = downloadEpisode(applicationContext, info, link.toList(), ::handleNotification)
                    awaitDownload(id)
                } else if (pkg != null) {
                    downloadFromResume(applicationContext, pkg, ::handleNotification, showToast)
                    awaitDownload(pkg.item.ep.id)
                }
                removeKeys(key)
            }
            return Result.success()
        } catch (e: Exception) {
            if (key != null) {
                removeKeys(key)
            }
            return Result.failure()
        }
    }

    private fun removeKeys(key: String) {
        applicationContext.removeKey(WORK_KEY_INFO, key)
        applicationContext.removeKey(WORK_KEY_LINK, key)
        applicationContext.removeKey(WORK_KEY_SHOW_TOAST, key)
        applicationContext.removeKey(WORK_KEY_PACKAGE, key)
    }

    private suspend fun awaitDownload(id: Int) {
        var isDone = false
        val listener = { data: Pair<Int, VideoDownloadManager.DownloadType> ->
            if (id == data.first) {
                when (data.second) {
                    VideoDownloadManager.DownloadType.IsDone, VideoDownloadManager.DownloadType.IsFailed, VideoDownloadManager.DownloadType.IsStopped -> {
                        isDone = true
                    }
                    else -> {
                    }
                }
            }
        }
        downloadStatusEvent += listener
        while (!isDone) {
            delay(1000)
        }
        downloadStatusEvent -= listener
    }

    private fun handleNotification(id: Int, notification: Notification) {
        setForegroundAsync(ForegroundInfo(id, notification))
    }
}