package com.lagradost.shiro.utils.mvvm

import android.util.Log
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.lagradost.shiro.BuildConfig
import com.lagradost.shiro.utils.ErrorLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun <T> LifecycleOwner.observe(liveData: LiveData<T>, action: (t: T) -> Unit) {
    liveData.observe(this, Observer { it?.let { t -> try { action(t) } catch (e: Exception) { logError(e) } } })
}

fun <T> LifecycleOwner.observeDirectly(liveData: LiveData<T>, action: (t: T) -> Unit) {
    liveData.observe(this, Observer { it?.let { t -> try { action(t) } catch (e: Exception) { logError(e) } } })
    val currentValue = liveData.value
    if (currentValue != null)
        try { action(currentValue) } catch (e: Exception) { logError(e) }
}

sealed class Resource<out T> {
    data class Success<out T>(val value: T) : Resource<T>()
    data class Failure(
        val isNetworkError: Boolean,
        val errorCode: Int?,
        val errorResponse: Any?, //ResponseBody
        val errorString: String,
    ) : Resource<Nothing>()

    data class Loading(val url: String? = null) : Resource<Nothing>()
}

fun logError(throwable: Throwable) {
    if (BuildConfig.BUILD_TYPE == "debug") {
        Log.d("ApiError", "-------------------------------------------------------------------")
        Log.d("ApiError", "safeApiCall: " + throwable.localizedMessage)
        Log.d("ApiError", "safeApiCall: " + throwable.message)
        throwable.printStackTrace()
        Log.d("ApiError", "-------------------------------------------------------------------")
    }
    // Persisted even in release builds so users can view/share it from Settings.
    ErrorLogger.log(throwable)
}

fun <T> normalSafeApiCall(apiCall: () -> T): T? {
    return try {
        apiCall.invoke()
    } catch (throwable: Throwable) {
        logError(throwable)
        return null
    }
}

suspend fun <T> safeApiCall(
    apiCall: suspend () -> T,
): Resource<T> {
    return withContext(Dispatchers.IO) {
        try {
            Resource.Success(apiCall.invoke())
        } catch (throwable: Throwable) {
            logError(throwable)
            when (throwable) {
                /*is HttpException -> {
                    Resource.Failure(false, throwable.code(), throwable.response()?.errorBody(), throwable.localizedMessage)
                }
                is SocketTimeoutException -> {
                    Resource.Failure(true,null,null,"Please try again later.")
                }
                is UnknownHostException ->{
                    Resource.Failure(true,null,null,"Cannot connect to server, try again later.")
                }*/
                else -> {
                    Resource.Failure(true, null, null, throwable.localizedMessage)
                }
            }
        }
    }
}