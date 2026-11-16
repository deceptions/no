package com.lagradost.shiro.ui.home

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.lagradost.shiro.ui.BookmarkedTitle
import com.lagradost.shiro.utils.NekomoApi

class HomeViewModel : ViewModel() {
    val apiData: MutableLiveData<NekomoApi.ShiroHomePageNew> = MutableLiveData<NekomoApi.ShiroHomePageNew>().also {
        NekomoApi.onHomeFetched += ::homeLoaded
    }

    var favorites = MutableLiveData<List<BookmarkedTitle?>?>()
    val subscribed = MutableLiveData<List<BookmarkedTitle?>?>()

    private fun homeLoaded(data: NekomoApi.ShiroHomePageNew?) {
        if (data == null) return
        favorites.postValue(data.favorites)
        subscribed.postValue(data.subscribed)
        apiData.postValue(data)
    }
}