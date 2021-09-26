package com.lagradost.shiro.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.ScaleAnimation
import androidx.core.view.updateMargins
import androidx.fragment.app.FragmentActivity
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.jaredrummler.cyanea.Cyanea
import com.lagradost.shiro.R
import com.lagradost.shiro.ui.GlideApp
import com.lagradost.shiro.ui.toPx
import com.lagradost.shiro.ui.tv.TvActivity.Companion.tvActivity
import com.lagradost.shiro.utils.AppUtils.fixCardTitle
import com.lagradost.shiro.utils.AppUtils.loadPage
import com.lagradost.shiro.utils.AppUtils.onLongCardClick
import com.lagradost.shiro.utils.ShiroApi
import com.lagradost.shiro.utils.ShiroApi.Companion.getFullUrlCdn
import kotlinx.android.synthetic.main.home_card.view.*


class CardAdapter(
    context: FragmentActivity,
    animeList: ArrayList<ShiroApi.CommonAnimePage?>,
    private val isOnTop: Boolean = false
) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    var cardList = animeList
    var activity: FragmentActivity? = context

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return CardViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.home_card, parent, false),
            activity!!
        )
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is CardViewHolder -> {
                holder.bind(cardList[position])
            }
        }
        holder.itemView.home_card?.setCardBackgroundColor(Cyanea.instance.backgroundColorDark)
        holder.itemView.setOnFocusChangeListener { v, hasFocus ->
            val toSize = if (hasFocus) 1.1f else 1.0f
            val fromSize = if (!hasFocus) 1.1f else 1.0f
            val animation = ScaleAnimation(
                fromSize,
                toSize,
                fromSize,
                toSize,
                Animation.RELATIVE_TO_SELF,
                0.5f,
                Animation.RELATIVE_TO_SELF,
                0.5f
            )
            animation.duration = 100
            animation.isFillEnabled = true
            animation.fillAfter = true
            v.startAnimation(animation)
            v.home_card.radius = if (hasFocus) 0F else 6.toPx.toFloat()
            if (isOnTop) {
                activity?.findViewById<View>(R.id.tv_menu_bar)?.visibility = VISIBLE
            } else {
                activity?.findViewById<View>(R.id.tv_menu_bar)?.visibility = GONE
            }
        }
    }


    override fun getItemCount(): Int {
        return cardList.size
    }

    class CardViewHolder(itemView: View, _context: FragmentActivity) : RecyclerView.ViewHolder(itemView) {
        val context = _context
        val card: View = itemView

        fun bind(cardInfo: ShiroApi.CommonAnimePage?) {
            val param = card.layoutParams as ViewGroup.MarginLayoutParams

            if (tvActivity != null) {
                param.updateMargins(
                    5.toPx,
                    10.toPx,
                    5.toPx,
                    10.toPx
                )
                card.layoutParams = param
            }
            if (cardInfo != null) {
                val glideUrl = getFullUrlCdn(cardInfo.image)
                //  activity?.runOnUiThread {
                context.let {
                    val settingsManager = PreferenceManager.getDefaultSharedPreferences(it)
                    val savingData = settingsManager.getBoolean("data_saving", false)

                    GlideApp.with(it)
                        .load(glideUrl)
                        .transition(DrawableTransitionOptions.withCrossFade(100))
                        .onlyRetrieveFromCache(savingData)
                        .into(card.imageView)
                }
                itemView.animate().scaleX(1.0f).scaleY(1.0f).setDuration(300).start()
                itemView.imageText?.text = fixCardTitle(cardInfo.name)

                itemView.home_card_root.setOnLongClickListener {
                    itemView.scaleY = 0.9f
                    itemView.scaleX = 0.9f
                    itemView.animate().scaleX(1.0f).scaleY(1.0f).setDuration(300).start()
                    context.onLongCardClick(cardInfo)
                    return@setOnLongClickListener true
                }
                itemView.home_card_root.setOnClickListener {
                    context.loadPage(
                        cardInfo.slug,
                        cardInfo.name,
                    )
                }
            }
        }
    }
}
