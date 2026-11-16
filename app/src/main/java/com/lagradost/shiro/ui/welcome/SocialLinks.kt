package com.lagradost.shiro.ui.welcome

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.lagradost.shiro.R

/** Shared "Follow Us" list, used by the welcome screen and Settings -> Community Rules. */
object SocialLinks {
    private data class Link(val icon: Int, val title: String, val desc: String, val url: Int, val color: Int)

    private val links = listOf(
        Link(R.drawable.ic_social_discord, "Discord", "Join the community", R.string.link_discord, 0xFF5865F2.toInt()),
        Link(R.drawable.ic_social_telegram, "Telegram", "Join the community", R.string.link_telegram, 0xFF26A5E4.toInt()),
        Link(R.drawable.ic_social_website, "Website", "Visit the official website", R.string.link_website, 0xFF3F51B5.toInt()),
        Link(R.drawable.ic_social_support, "Support", "Contact the support team", R.string.link_support, 0xFF009688.toInt()),
    )

    fun openUrl(context: Context, url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(context, "No app available to open this link", Toast.LENGTH_SHORT).show()
        }
    }

    fun dp(context: Context, v: Int) = (v * context.resources.displayMetrics.density).toInt()

    /** Rounded, focusable card background with a visible focus ring for D-pad use. */
    fun cardBackground(context: Context, fill: Int, radiusDp: Int = 12): android.graphics.drawable.Drawable {
        val shape = GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(context, radiusDp).toFloat()
        }
        val focused = GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(context, radiusDp).toFloat()
            setStroke(dp(context, 3), Color.WHITE)
        }
        val states = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(), shape)
        }
        return RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), states, null)
    }

    fun populate(container: LinearLayout, textColor: Int, cardColor: Int) {
        val ctx = container.context
        container.removeAllViews()
        for (link in links) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
                background = cardBackground(ctx, cardColor)
                isFocusable = true
                isClickable = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(ctx, 8) }
                setOnClickListener { openUrl(ctx, ctx.getString(link.url)) }
            }
            val iconBg = LinearLayout(ctx).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(link.color)
                }
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 40), dp(ctx, 40))
            }
            iconBg.addView(ImageView(ctx).apply {
                setImageResource(link.icon)
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 22), dp(ctx, 22))
                contentDescription = link.title
            })
            val texts = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(ctx, 14), 0, 0, 0)
            }
            texts.addView(TextView(ctx).apply {
                text = link.title
                setTextColor(textColor)
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            texts.addView(TextView(ctx).apply {
                text = link.desc
                setTextColor(textColor and 0x00FFFFFF or 0xB3000000.toInt())
                textSize = 13f
            })
            row.addView(iconBg)
            row.addView(texts)
            container.addView(row)
        }
    }
}
