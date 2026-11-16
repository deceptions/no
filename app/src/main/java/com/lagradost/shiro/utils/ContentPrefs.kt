package com.lagradost.shiro.utils

import android.content.Context
import androidx.preference.PreferenceManager
import com.lagradost.shiro.AcraApplication

/**
 * Content filters controlled from Settings -> General. Both default to off: Nekomo is anime-only and
 * hides 18+ sources/extensions until the user opts in.
 */
object ContentPrefs {
    private fun prefs(context: Context?) =
        (context ?: AcraApplication.context)?.let { PreferenceManager.getDefaultSharedPreferences(it) }

    /** Show 18+ extensions/sources (in browse lists, search and the source chooser). */
    fun nsfw(context: Context? = null) = prefs(context)?.getBoolean("nsfw_enabled", false) ?: false

    /** Show sources that provide movies and TV shows, not only anime. */
    fun nonAnime(context: Context? = null) = prefs(context)?.getBoolean("non_anime_enabled", false) ?: false
}
