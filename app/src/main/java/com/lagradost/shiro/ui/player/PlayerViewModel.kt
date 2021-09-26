package com.lagradost.shiro.ui.player

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.google.android.exoplayer2.video.VideoSize
import com.lagradost.shiro.ui.BookmarkedTitle
import com.lagradost.shiro.utils.ExtractorLink
import com.lagradost.shiro.utils.ShiroApi

class PlayerViewModel : ViewModel() {
    val videoSize = MutableLiveData<VideoSize?>()
    val selectedSource = MutableLiveData<ExtractorLink?>()
}