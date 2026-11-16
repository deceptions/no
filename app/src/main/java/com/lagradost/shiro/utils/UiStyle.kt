package com.lagradost.shiro.utils

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import androidx.cardview.widget.CardView
import androidx.core.view.children
import androidx.preference.PreferenceManager
import com.lagradost.shiro.R
import kotlin.math.roundToInt

/**
 * Central place for the user-facing interface tweaks: Classic vs Modern mode, poster size, card
 * corner radius and card spacing. Adapters call [restyle] once when a card view is inflated and
 * [entrance] when it is bound; everything is read from shared preferences so no restart is needed.
 *
 * Modern mode keeps the exact same layouts but adds larger corner radius, roomier spacing and a
 * subtle fade/rise entrance animation. "Reduce motion" (Settings -> General) wins over everything.
 */
object UiStyle {
    const val MODE_CLASSIC = "classic"
    const val MODE_MODERN = "modern"

    private fun prefs(ctx: Context) = PreferenceManager.getDefaultSharedPreferences(ctx)
    private fun density(ctx: Context) = ctx.resources.displayMetrics.density

    fun isModern(ctx: Context): Boolean =
        prefs(ctx).getString("ui_mode", MODE_CLASSIC) == MODE_MODERN

    fun reduceMotion(ctx: Context): Boolean =
        prefs(ctx).getBoolean("reduce_motion", false)

    fun cardRadiusDp(ctx: Context): Int =
        prefs(ctx).getInt("card_corner_dp", if (isModern(ctx)) 14 else 6)

    fun cardRadiusPx(ctx: Context): Float = cardRadiusDp(ctx) * density(ctx)

    /** Buttons: sharp-ish in Classic (matches the home buttons), pill-ish in Modern. */
    fun buttonRadiusDp(ctx: Context): Int = if (isModern(ctx)) 20 else 10
    fun buttonRadiusPx(ctx: Context): Float = buttonRadiusDp(ctx) * density(ctx)

    fun cardSpacingPx(ctx: Context): Int =
        (prefs(ctx).getInt("card_spacing_dp", if (isModern(ctx)) 8 else 5) * density(ctx)).toInt()

    fun posterScale(ctx: Context): Float =
        (prefs(ctx).getInt("poster_size", 100).coerceIn(60, 160)) / 100f

    fun animateCards(ctx: Context): Boolean = isModern(ctx) && !reduceMotion(ctx)

    /**
     * Applies the current style to an inflated card: corner radius on every CardView in the tree,
     * poster scaling on imageView/imageText pairs with fixed dimensions, and side spacing on
     * home_card_root rows. Call once in onCreateViewHolder so recycled views keep the style.
     */
    fun restyle(root: View) {
        val ctx = root.context
        applyRadius(root, cardRadiusPx(ctx))

        val scale = posterScale(ctx)
        if (scale != 1f) {
            // home_card_recently_seen stacks same-sized views (poster, play overlay, progress holder):
            // scale everything that shares the poster's original dimensions so overlays stay aligned.
            val poster = root.findViewById<ImageView>(R.id.imageView)
            val w0 = poster?.layoutParams?.width ?: 0
            val h0 = poster?.layoutParams?.height ?: 0
            if (w0 > 0) {
                fun scaleTree(v: View) {
                    v.layoutParams?.let { lp ->
                        if (lp.width == w0) lp.width = (w0 * scale).roundToInt()
                        if (h0 > 0 && lp.height == h0) lp.height = (h0 * scale).roundToInt()
                    }
                    (v as? ViewGroup)?.children?.forEach(::scaleTree)
                }
                scaleTree(root)
            } else {
                // Poster cards that are themselves fixed-size (e.g. search_result.xml's 200dp root)
                root.layoutParams?.let { lp ->
                    if (lp.width > 0 && lp.height > 0) {
                        lp.width = (lp.width * scale).roundToInt()
                        lp.height = (lp.height * scale).roundToInt()
                    }
                }
            }
        }

        if (root.id == R.id.home_card_root) {
            (root.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                val spacing = cardSpacingPx(ctx)
                lp.marginStart = spacing
                lp.marginEnd = spacing
                root.layoutParams = lp
            }
        }
    }

    private fun applyRadius(view: View, radiusPx: Float) {
        if (view is CardView) view.radius = radiusPx
        if (view is ViewGroup) view.children.forEach { applyRadius(it, radiusPx) }
    }

    /**
     * Plays the Modern-mode entrance animation (fade + small rise) or resets the view to its final
     * state when [animate] is false, so recycled views never stay half-transparent.
     */
    fun entrance(view: View, animate: Boolean) {
        view.animate().cancel()
        if (animate) {
            view.alpha = 0f
            view.translationY = 18f * density(view.context)
            view.animate().alpha(1f).translationY(0f)
                .setDuration(260)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else {
            view.alpha = 1f
            view.translationY = 0f
        }
    }
}
