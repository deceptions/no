package com.lagradost.shiro.ui.settings

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class SettingsViewModel : ViewModel() {
    val hasLoggedIntoMAL = MutableLiveData<Boolean>()
    val hasLoggedIntoAnilist = MutableLiveData<Boolean>()
}