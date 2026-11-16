package com.lagradost.shiro.utils

import android.content.Context
import com.lagradost.shiro.utils.aniyomi.AniyomiManager
import com.lagradost.shiro.utils.cs3.CsPluginManager
import com.lagradost.shiro.utils.zg.ZangetsuManager

/** Whether the user has installed any playback source at all (Nekomo ships with none). */
object SourceState {
    fun hasAny(context: Context?): Boolean {
        val c = context ?: return false
        return try {
            CsPluginManager.getInstalled(c).isNotEmpty() || ZangetsuManager.getInstalled(c).isNotEmpty() ||
                    AniyomiManager.getInstalled(c).isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }
}
