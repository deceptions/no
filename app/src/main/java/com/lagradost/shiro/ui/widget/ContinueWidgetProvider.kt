package com.lagradost.shiro.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.RemoteViews
import com.lagradost.shiro.R
import com.lagradost.shiro.ui.MainActivity
import com.lagradost.shiro.utils.NekomoApi.Companion.getLastWatch
import com.lagradost.shiro.utils.mvvm.logError
import kotlin.concurrent.thread

/**
 * "Continue watching" home-screen widget: shows the newest entry from the same store the home
 * continue row uses. Tapping it opens the app. Refreshes on the system update tick and whenever
 * [refresh] is called (e.g. after playback saves position).
 */
class ContinueWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        thread { refresh(context) }
    }

    companion object {
        /** Rebuilds every placed widget — call after anything changes the continue-watching list. */
        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(android.content.ComponentName(ctx, ContinueWidgetProvider::class.java))
            if (ids.isEmpty()) return

            val last = runCatching { ctx.getLastWatch().firstOrNull() }.getOrNull()
            ids.forEach { id ->
                val views = RemoteViews(ctx.packageName, R.layout.widget_continue)
                if (last == null) {
                    views.setTextViewText(R.id.widget_title, "Nekomo")
                    views.setTextViewText(R.id.widget_episode, "Nothing playing yet")
                } else {
                    views.setTextViewText(R.id.widget_title, last.title)
                    views.setTextViewText(
                        R.id.widget_episode,
                        if (last.episode != null) "Episode ${last.episode.episode}" else "Continue watching"
                    )
                    // Poster: RemoteViews can't stream images, so decode it on the worker thread.
                    runCatching {
                        val bmp = com.bumptech.glide.Glide.with(ctx).asBitmap()
                            .load(last.bannerImage.ifBlank { last.coverImage })
                            .submit(420, 236).get()
                        views.setImageViewBitmap(R.id.widget_art, bmp)
                    }
                }
                val pi = PendingIntent.getActivity(
                    ctx, 0, Intent(ctx, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_root, pi)
                try { mgr.updateAppWidget(id, views) } catch (t: Throwable) { logError(t) }
            }
        }
    }
}
