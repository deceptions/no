package com.lagradost.shiro.ui

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.lagradost.shiro.ui.player.PlayerData
import com.lagradost.shiro.utils.VideoDownloadManager
import java.util.*

class MasterViewModel : ViewModel() {
    val playerData = MutableLiveData<PlayerData>()
    val downloadQueue = MutableLiveData<LinkedList<VideoDownloadManager.DownloadResumePackage>>()
    val isQueuePaused = MutableLiveData<Boolean>()
}