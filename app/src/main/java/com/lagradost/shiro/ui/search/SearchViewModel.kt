package com.lagradost.shiro.ui.search

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class SearchViewModel : ViewModel() {
    val searchOptions = MutableLiveData<List<String>>()
    val selectedGenres = MutableLiveData<List<String>>()
    val searchQuery = MutableLiveData<String>()

    private fun searchLoaded(data: List<String>?) {
        searchOptions.postValue(data!!)
    }
}