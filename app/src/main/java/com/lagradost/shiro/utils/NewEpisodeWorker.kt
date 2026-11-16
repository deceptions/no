package com.lagradost.shiro.utils

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.lagradost.shiro.ui.MainActivity
import com.lagradost.shiro.utils.NekomoApi.Companion.getSubbed
import com.lagradost.shiro.utils.mvvm.logError
import java.util.concurrent.TimeUnit

/**
 * Periodic check (every 12 h, network required) that notifies about new episodes of shows the user
 * subscribed to. Compares the catalogue "recently updated" feed against per-show seen episode
 * numbers in `episode_notify_seen`; the first run only records the baseline so there is no spam.
 */
class NewEpisodeWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        if (!enabled(ctx)) return Result.success()
        // Quiet hours: skip this run entirely so pending episodes are still reported next window.
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        if (prefs.getBoolean("quiet_hours_enabled", false)) {
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            val start = prefs.getInt("quiet_start_hour", 23)
            val end = prefs.getInt("quiet_end_hour", 7)
            val inQuiet = if (start <= end) hour in start until end else hour >= start || hour < end
            if (inQuiet) return Result.success()
        }
        val subs = try {
            ctx.getSubbed().filterNotNull()
        } catch (t: Throwable) {
            logError(t)
            emptyList()
        }
        if (subs.isEmpty()) return Result.success()
        // Per-show mute list (long-press the subscribe bell on a title page).
        val muted = prefs.getStringSet("episode_notify_muted", emptySet()) ?: emptySet()

        val recents = try {
            LiveApi.getRecents()
        } catch (t: Throwable) {
            logError(t)
            null
        } ?: return Result.retry()

        val seen = ctx.getSharedPreferences("episode_notify_seen", Context.MODE_PRIVATE)
        val fresh = ArrayList<Pair<String, String>>()
        recents.forEach { holder ->
            val ep = holder.episode.episode.toFloatOrNull() ?: return@forEach
            if (holder.anime.slug in muted || subs.none { it.slug == holder.anime.slug }) return@forEach
            val key = "ep_${holder.anime.slug}"
            val last = seen.getFloat(key, -1f)
            // Only notify when a baseline exists, so the very first check never alerts.
            if (last >= 0f && ep > last) fresh += holder.anime.title to holder.episode.episode
            if (ep > last) seen.edit().putFloat(key, ep).apply()
        }

        // Manga follows: check chapter counts on each followed title (baseline first run).
        try {
            com.lagradost.shiro.utils.manga.MangaFollows.all(ctx).forEach { f ->
                try {
                    val src = com.lagradost.shiro.utils.mihon.MihonBridge.sourceById(ctx, f.sourceId) ?: return@forEach
                    val count = runCatching {
                        com.lagradost.shiro.utils.mihon.MihonBridge.details(src, f.url, f.title).chapters.size
                    }.getOrDefault(-1)
                    val last = com.lagradost.shiro.utils.manga.MangaFollows.lastCount(ctx, f.sourceId, f.url)
                    if (count >= 0) {
                        if (last >= 0 && count > last) {
                            fresh += f.title to "${count - last} new chapter(s)"
                        }
                        com.lagradost.shiro.utils.manga.MangaFollows.setCount(ctx, f.sourceId, f.url, count)
                    }
                } catch (t: Throwable) {
                    logError(t)
                }
            }
        } catch (t: Throwable) {
            logError(t)
        }

        when {
            fresh.isEmpty() -> Unit
            fresh.size == 1 -> notify(ctx, fresh[0].first, "Episode ${fresh[0].second} is out")
            else -> notify(
                ctx,
                "${fresh.size} new episodes",
                fresh.take(3).joinToString(", ") { "${it.first} ep ${it.second}" } +
                        if (fresh.size > 3) ", +${fresh.size - 3} more" else ""
            )
        }
        return Result.success()
    }

    private fun notify(ctx: Context, title: String, text: String) {
        try {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "New episodes", NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
            val intent = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(com.lagradost.shiro.R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(intent)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(ctx).notify(7401, n)
        } catch (e: Exception) {
            logError(e)
        }
    }

    companion object {
        private const val CHANNEL_ID = "new_episodes"
        private const val WORK_NAME = "new_episode_check"

        fun enabled(ctx: Context) =
            PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean("notify_new_episodes", false)

        fun schedule(ctx: Context, enabledOverride: Boolean? = null) {
            val wm = WorkManager.getInstance(ctx)
            val on = enabledOverride ?: enabled(ctx)
            if (!on) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<NewEpisodeWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
