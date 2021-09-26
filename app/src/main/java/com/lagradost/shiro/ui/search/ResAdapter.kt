package com.lagradost.shiro.ui.search

import BOOKMARK_KEY
import DataStore.containsKey
import DataStore.removeKey
import DataStore.setKey
import android.content.res.ColorStateList
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.jaredrummler.cyanea.Cyanea
import com.lagradost.shiro.R
import com.lagradost.shiro.ui.AutofitRecyclerView
import com.lagradost.shiro.ui.BookmarkedTitle
import com.lagradost.shiro.ui.GlideApp
import com.lagradost.shiro.ui.home.HomeFragment.Companion.homeViewModel
import com.lagradost.shiro.ui.toPx
import com.lagradost.shiro.utils.AppUtils.filterCardList
import com.lagradost.shiro.utils.AppUtils.fixCardTitle
import com.lagradost.shiro.utils.AppUtils.getColorFromAttr
import com.lagradost.shiro.utils.AppUtils.getCurrentActivity
import com.lagradost.shiro.utils.AppUtils.guaranteedContext
import com.lagradost.shiro.utils.AppUtils.loadPage
import com.lagradost.shiro.utils.AppUtils.onLongCardClick
import com.lagradost.shiro.utils.AppUtils.settingsManager
import com.lagradost.shiro.utils.ShiroApi
import com.lagradost.shiro.utils.ShiroApi.Companion.getFav
import com.lagradost.shiro.utils.ShiroApi.Companion.getFullUrlCdn
import kotlinx.android.synthetic.main.search_result.view.*
import kotlinx.android.synthetic.main.search_result.view.imageText
import kotlinx.android.synthetic.main.search_result.view.imageView
import kotlinx.android.synthetic.main.search_result_compact.view.*
import kotlin.concurrent.thread
import kotlin.math.roundToInt

class ResAdapter(
    animeList: ArrayList<ShiroApi.CommonAnimePage>,
    val resView: AutofitRecyclerView,
    val isMalId: Boolean,
    forceDisableCompact: Boolean = false
) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    var cardList = animeList
    private val compactView =
        settingsManager?.getBoolean("compact_search_enabled", true) == true && !forceDisableCompact

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        cardList = ArrayList(filterCardList(cardList) ?: listOf())

        val layout = if (compactView) R.layout.search_result_compact else R.layout.search_result
        return CardViewHolder(
            LayoutInflater.from(parent.context).inflate(layout, parent, false),
            resView,
            isMalId,
            compactView
        )
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is CardViewHolder -> {
                holder.bind(cardList[position])
            }

        }
    }

    override fun getItemCount(): Int {
        return cardList.size
    }

    class CardViewHolder
    constructor(itemView: View, resView: AutofitRecyclerView, private val isMalId: Boolean, private val compactView: Boolean) :
        RecyclerView.ViewHolder(itemView) {
        val cardView: ImageView = itemView.imageView
        private val coverHeight: Int = if (compactView) 80.toPx else (resView.itemWidth / 0.68).roundToInt()
        val context = guaranteedContext(itemView.context)

        fun bind(card: ShiroApi.CommonAnimePage) {
            if (compactView) {
                // COPIED -----------------------------------------
                var isBookmarked = context.containsKey(BOOKMARK_KEY, card.slug)
                fun toggleHeartVisual(_isBookmarked: Boolean) {
                    if (_isBookmarked) {
                        itemView.title_bookmark.setImageResource(R.drawable.filled_heart)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            itemView.title_bookmark.imageTintList = ColorStateList.valueOf(Cyanea.instance.primary)
                        }
                    } else {
                        itemView.title_bookmark.setImageResource(R.drawable.outlined_heart)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            itemView.title_bookmark.imageTintList =
                                ColorStateList.valueOf(context.getColorFromAttr(R.attr.white))
                        }
                    }
                }

                fun toggleHeart(_isBookmarked: Boolean) {
                    isBookmarked = _isBookmarked
                    toggleHeartVisual(_isBookmarked)
                    /*Saving the new bookmark in the database*/
                    if (_isBookmarked) {
                        context.setKey(
                            BOOKMARK_KEY,
                            card.slug,
                            BookmarkedTitle(
                                card.name,
                                card.image,
                                card.slug,
                                card.english
                            )
                        )
                    } else {
                        context.removeKey(BOOKMARK_KEY, card.slug)
                    }
                    thread {
                        homeViewModel?.favorites?.postValue(context.getFav())
                    }
                }
                toggleHeartVisual(isBookmarked)
                itemView.bookmark_holder.setOnClickListener {
                    toggleHeart(!isBookmarked)
                }
                // ------------------------------------------------
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    itemView.backgroundCard.backgroundTintList = ColorStateList.valueOf(
                        Cyanea.instance.backgroundColorDark
                    )
                }

                itemView.backgroundCard.setOnClickListener {
                    getCurrentActivity()?.loadPage(card.slug, card.name, isMalId)
                }
                cardView.setOnLongClickListener {
                    if (context.onLongCardClick(card)) toggleHeart(!isBookmarked)
                    return@setOnLongClickListener true
                }
            } else {
                itemView.animate().scaleX(1.0f).scaleY(1.0f).setDuration(300).start()
                cardView.setOnLongClickListener {
                    itemView.scaleY = 0.9f
                    itemView.scaleX = 0.9f
                    itemView.animate().scaleX(1.0f).scaleY(1.0f).setDuration(300).start()
                    context.onLongCardClick(card)
                    return@setOnLongClickListener true
                }
            }

            itemView.apply {
                layoutParams = LinearLayout.LayoutParams(
                    MATCH_PARENT,
                    coverHeight
                )
            }
            itemView.search_result_card?.setCardBackgroundColor(Cyanea.instance.backgroundColorDark)
            itemView.imageText?.text = fixCardTitle(card.name)
            if (card.english != null) {
                itemView.imageSubText?.visibility = VISIBLE
                itemView.imageSubText?.text = fixCardTitle(card.english!!)
            } else {
                itemView.imageSubText?.visibility = GONE
            }

            cardView.setOnClickListener {
                getCurrentActivity()?.loadPage(card.slug, card.name, isMalId)
                /*MainActivity.loadPage(card)*/
            }

            val glideUrl = getFullUrlCdn(card.image)
            context.let {
                val settingsManager = PreferenceManager.getDefaultSharedPreferences(it)
                val savingData = settingsManager.getBoolean("data_saving", false)
                GlideApp.with(it)
                    .load(glideUrl)
                    .transition(DrawableTransitionOptions.withCrossFade(100))
                    .onlyRetrieveFromCache(savingData)
                    .into(cardView)
            }


        }

    }
}
