package com.lagradost.shiro.utils

import android.os.Handler
import android.os.Looper
import com.lagradost.shiro.utils.mvvm.logError
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

object Coroutines {
    // Without this, any exception thrown inside main {} (very common: touching a view after
    // the fragment's view has been destroyed) is uncaught and crashes the whole app.
    private val mainExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        logError(throwable)
    }

    fun main(work: suspend (() -> Unit)) : Job {
        return CoroutineScope(Dispatchers.Main + mainExceptionHandler).launch {
            work()
        }
    }
    fun runOnMainThread(work: (() -> Unit)) {
        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.post {
            work()
        }
    }
}