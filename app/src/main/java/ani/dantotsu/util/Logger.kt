package ani.dantotsu.util

import android.util.Log

/** Minimal stand-in for the logger the vendored extension runtime expects. */
object Logger {
    fun log(message: String) {
        Log.d("ExtensionRuntime", message)
    }

    fun log(e: Throwable) {
        Log.w("ExtensionRuntime", e)
    }
}
