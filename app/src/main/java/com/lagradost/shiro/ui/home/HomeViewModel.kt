package com.lagradost.shiro.ui.home

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.lagradost.shiro.ui.BookmarkedTitle
import com.lagradost.shiro.utils.ShiroApi

class HomeViewModel : ViewModel() {
    val apiData: MutableLiveData<ShiroApi.ShiroHomePageNew> = MutableLiveData<ShiroApi.ShiroHomePageNew>().also {
        ShiroApi.onHomeFetched += ::homeLoaded
    }

    var favorites = MutableLiveData<List<BookmarkedTitle?>?>()
    val subscribed = MutableLiveData<List<BookmarkedTitle?>?>()

    private fun homeLoaded(data: ShiroApi.ShiroHomePageNew?) {
        favorites.postValue(data?.favorites)
        subscribed.postValue(data?.subscribed)
        apiData.postValue(data!!)
    }
}