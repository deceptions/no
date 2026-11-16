package com.lagradost.shiro.utils.integrations

import DataStore.getKey
import DataStore.setKey
import android.content.Context
import android.widget.Toast
import com.lagradost.shiro.utils.AppUtils.getCurrentActivity
import com.lagradost.shiro.utils.Coroutines.main
import com.lagradost.shiro.utils.auth.AuthManager
import com.lagradost.shiro.utils.auth.KitsuApi
import com.lagradost.shiro.utils.mvvm.logError

/**
 * Mirrors list changes to the trackers that don't have their own code path in the results/
 * player screens (Kitsu, SIMKL). The existing AniList/MAL code keeps doing its own writes;
 * every place that writes there also calls [push], so all connected services stay in step.
 *
 * Statuses use the AniList int convention the app uses everywhere
 * (0 watching, 1 completed, 2 paused, 3 dropped, 4 planning, 5 rewatching).
 * Blocking - call from a worker thread.
 */
object TrackerSync {
    private const val FOLDER = "tracker_sync_last"

    fun anyConnected(context: Context) =
        AuthManager.isLoggedIntoKitsu(context) || SimklApi.isConnected(context)

    /**
     * @return names of services that failed (empty = all good or nothing connected).
     */
    fun push(
        context: Context, malId: Int?, anilistId: Int?,
        status: Int, score: Int, progress: Int, monotonic: Boolean = false
    ): List<String> {
        if (malId == null && anilistId == null) return emptyList()
        val failed = ArrayList<String>()
        val id = malId?.let { "mal$it" } ?: "al$anilistId"
        val signature = "$status:$progress:$score"

        fun run(name: String, connected: Boolean, block: () -> Boolean) {
            if (!connected) return
            if (context.getKey<String>(FOLDER, "$name-$id", null) == signature) return
            val ok = try {
                block()
            } catch (t: Throwable) {
                logError(t); false
            }
            if (ok) context.setKey(FOLDER, "$name-$id", signature) else failed.add(name)
        }

        run("Kitsu", AuthManager.isLoggedIntoKitsu(context)) {
            KitsuApi.updateEntry(context, malId, anilistId, status, score, progress, monotonic)
        }
        run("SIMKL", SimklApi.isConnected(context)) {
            SimklApi.updateEntry(context, malId, anilistId, status, score, progress, monotonic)
        }
        return failed
    }

    /** Same as [push] but shows a toast if a service failed. */
    fun pushAndNotify(
        context: Context, malId: Int?, anilistId: Int?,
        status: Int, score: Int, progress: Int, monotonic: Boolean = false
    ) {
        val failed = push(context, malId, anilistId, status, score, progress, monotonic)
        if (failed.isNotEmpty()) {
            main {
                getCurrentActivity()?.let {
                    Toast.makeText(it, "Could not sync progress to ${failed.joinToString()}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
