package com.lagradost.shiro.ui.home
import com.lagradost.shiro.utils.fv

import android.view.LayoutInflater
import android.view.View
import android.view.View.*
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.shiro.R
import com.lagradost.shiro.ui.LastEpisodeInfo
import com.lagradost.shiro.ui.home.HomeFragment.Companion.homeViewModel
import com.lagradost.shiro.utils.AppUtils.displayCardData
import com.lagradost.shiro.utils.NekomoApi
import com.lagradost.shiro.utils.home.HomeFeed
import com.lagradost.shiro.utils.home.HomeLayout
import com.lagradost.shiro.utils.home.SourceKind
import kotlin.concurrent.thread


class MasterCardAdapter(
    context: FragmentActivity,
) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var activity: FragmentActivity? = context
    private var filtered: List<Pair<List<Any?>?, String?>> = generateFiltered()

    // Sections the user enabled in Settings -> Home page, beyond the two fixed rows (trending / recently updated).
    private fun extraSections() = activity?.let { act ->
        HomeLayout.active(act).filter {
            it.source.kind != SourceKind.LOCAL && it.source.kind != SourceKind.OFFICIAL &&
                    it.def.id != "trending" && it.def.id != "recent"
        }
    } ?: emptyList()

    init {
        val act = activity
        val pending = extraSections().filter { HomeFeed.cached(it.def.id) == null }
        if (act != null && pending.isNotEmpty()) thread {
            pending.forEach { HomeFeed.load(act, it) }
            act.runOnUiThread {
                filtered = generateFiltered()
                notifyDataSetChanged()
            }
        }
    }

    private fun generateFiltered(): List<Pair<List<Any?>?, String?>> {
        val data = homeViewModel!!.apiData.value
        return arrayOf(
            //Pair(data?.searchResults, "Search results"),
            Pair(data?.recentlySeen, activity?.getString(R.string.continue_watching)),
            Pair(homeViewModel!!.favorites.value, activity?.getString(R.string.favorites)),
            Pair(data?.trending?.data?.map {
                    NekomoApi.CommonAnimePageData(
                        it.title,
                        it.poster,
                        it.slug,
                        it.title_english
                    )
                }, activity?.getString(R.string.trending_anime)),
            Pair(data?.recents?.map {
                NekomoApi.CommonAnimePageData(
                    it.anime.title,
                    it.anime.poster,
                    it.anime.slug,
                )
            }?.distinctBy { it.slug }, activity?.getString(R.string.home_recently_updated)),
//            Pair(data?.data?.ongoing_animes, activity?.getString(R.string.home_ongoing)),
//            Pair(data?.data?.latest_animes, activity?.getString(R.string.latest_anime))
        ).plus(extraSections().map { Pair(HomeFeed.cached(it.def.id), it.def.title) }).filter {
            it.first != null && it.first?.isNotEmpty() == true
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return MasterCardViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.vertical_grid_view_child, parent, false),
            activity!!
        )
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        filtered = generateFiltered()
        when (holder) {
            is MasterCardViewHolder -> {
                holder.bind(filtered[position], position)
            }
        }
        holder.itemView.setOnFocusChangeListener { _, _ ->
            val menu = activity?.findViewById<LinearLayout>(R.id.tv_menu_bar)
            if (position == 0) {
                menu?.visibility = VISIBLE
            } else {
                menu?.visibility = INVISIBLE
            }
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        holder.itemView.fv<androidx.leanback.widget.HorizontalGridView>(R.id.horizontalGridView)?.adapter = null
        holder.itemView.visibility = GONE
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int {
        return filtered.size
    }

    class MasterCardViewHolder(itemView: View, _context: FragmentActivity) : RecyclerView.ViewHolder(itemView) {
        val activity = _context
        val card: View = itemView

        fun bind(pair: Pair<List<Any?>?, String?>, position: Int) {
            card.fv<android.widget.TextView>(R.id.expand_text)?.text = pair.second
            card.visibility = VISIBLE
            val isOnTop = position == 0
            val isFavorite = activity.getString(R.string.favorites) == pair.second
            when {
                pair.first as? List<NekomoApi.CommonAnimePage?> != null && pair.second != activity.getString(R.string.continue_watching) -> {
                    activity.displayCardData(
                        pair.first as List<NekomoApi.CommonAnimePage>?,
                        card.fv<androidx.leanback.widget.HorizontalGridView>(R.id.horizontalGridView),
                        card.fv<android.widget.TextView>(R.id.expand_text),
                        isOnTop,
                        overrideHideDubbed = isFavorite
                    )
                }
                pair.first as? List<LastEpisodeInfo?> != null -> {
                    activity.displayCardData(
                        pair.first as? List<LastEpisodeInfo>,
                        card.fv<androidx.leanback.widget.HorizontalGridView>(R.id.horizontalGridView),
                        isOnTop
                    )
                }
                else -> {
                }
            }
        }
    }
}
