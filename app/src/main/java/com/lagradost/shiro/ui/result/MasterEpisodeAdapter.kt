package com.lagradost.shiro.ui.result

import DataStore.containsKey
import VIEWSTATE_KEY
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewTreeLifecycleOwner
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import com.google.common.math.IntMath.mod
import com.jaredrummler.cyanea.Cyanea
import com.lagradost.shiro.R
import com.lagradost.shiro.ui.downloads.DownloadClickEvent
import com.lagradost.shiro.utils.AppUtils.dubbify
import com.lagradost.shiro.utils.AppUtils.getColorFromAttr
import com.lagradost.shiro.utils.AppUtils.getViewKey
import com.lagradost.shiro.utils.ShiroApi
import kotlinx.android.synthetic.main.episode_expander.view.*

class MasterEpisodeAdapter(
    val context: Context,
    var data: ShiroApi.Companion.AnimePageNewData,
    var isFiller: HashMap<Int, Boolean>? = null,
    private val clickCallback: (EpisodeClickEvent) -> Unit,
    private val downloadClickCallback: (DownloadClickEvent) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    val episodes = data.episodes

    data class MasterEpisode(
        val start: Int,
        val end: Int,
        var visible: Boolean = false,
    )

    var items = context.generateItems(episodes, data.anime.slug)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return MasterEpisodeViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.episode_expander, parent, false),
            context,
            isFiller,
            clickCallback,
            downloadClickCallback
        ) {
            items[it].visible = !items[it].visible
            notifyItemChanged(it)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is MasterEpisodeViewHolder -> {
                holder.bind(items[position], position, data)
            }
        }
    }

    override fun getItemCount(): Int {
        return items.size
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        (holder.itemView.episodes_res_view?.adapter as? EpisodeAdapter?)?.killAdapter()
    }

    class MasterEpisodeViewHolder(
        itemView: View,
        val context: Context,
        private val fillerList: HashMap<Int, Boolean>? = null,
        private val clickCallback: (EpisodeClickEvent) -> Unit,
        private val downloadClickCallback: (DownloadClickEvent) -> Unit,
        private val expandCallback: (Int) -> Unit
    ) :
        RecyclerView.ViewHolder(itemView) {

        var selectedItem = MutableLiveData<Int>()

        fun bind(
            item: MasterEpisode,
            position: Int,
            data: ShiroApi.Companion.AnimePageNewData,
        ) {
            val episodeOffset = if (data.episodes.filter { it.episode == "0" }.isNullOrEmpty()) 0 else -1

            //println("BIND $position" + "|" + (fillerList?.size ?: "NULLL"))
            itemView.cardTitle?.text =
                if (item.start + 1 == item.end) "Episode ${item.end + episodeOffset}"
                else "Episodes ${item.start + 1 + episodeOffset} - ${item.end + episodeOffset}"

            val adapter: RecyclerView.Adapter<RecyclerView.ViewHolder> = EpisodeAdapter(
                data,
                itemView,
                position,
                item.start,
                item.end,
                fillerList,
                clickCallback,
                downloadClickCallback
            )
            val settingsManager = PreferenceManager.getDefaultSharedPreferences(context)
            itemView.episodes_res_view.spanCount =
                (if (settingsManager.getBoolean(
                        "no_episode_thumbnails",
                        false
                    )
                ) 2 else 1) //* if (tvActivity != null) 2 else 1
            itemView.episodes_res_view.adapter = adapter
            (itemView.episodes_res_view.adapter as EpisodeAdapter).notifyDataSetChanged()
            itemView.card_outline.setOnClickListener {
                expandCallback.invoke(position)
            }
            //val transition: Transition = ChangeTransform()
            //transition.duration = 3000
            //TransitionManager.beginDelayedTransition(itemView.cardBg, transition)

            itemView.expand_icon.rotation = if (item.visible) 90f else 0f
            itemView.episodes_res_view?.isVisible = item.visible

            var isSeen = false
            for (episode in item.start..item.end) {
                val key = getViewKey(data.anime.slug.dubbify(false), episode)
                val keyDubbed = getViewKey(data.anime.slug.dubbify(true), episode)
                if (context.containsKey(VIEWSTATE_KEY, key) || context.containsKey(VIEWSTATE_KEY, keyDubbed)) {
                    isSeen = true
                }
            }

            fun setVisibility(focused: Boolean) {
                when {
                    focused -> {
                        itemView.card_outline.setCardBackgroundColor(
                            context.getColorFromAttr(R.attr.white)
                        )
                    }
                    isSeen -> {
                        itemView.card_outline.setCardBackgroundColor(
                            Cyanea.instance.accentDark
                        )
                        itemView.card_bg.setCardBackgroundColor(
                            Cyanea.instance.backgroundColorDark
                        )
                    }
                    else -> {
                        itemView.card_outline.setCardBackgroundColor(
                            Cyanea.instance.backgroundColorDark
                        )
                        itemView.card_bg.setCardBackgroundColor(
                            Cyanea.instance.backgroundColor
                        )
                    }
                }
            }

            ViewTreeLifecycleOwner.get(itemView)?.let {
                selectedItem.observe(it) {
                    if (it < position) {
                        itemView.card_outline.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    } else {
                        itemView.card_outline.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                    }
                }
            }

            setVisibility(itemView.card_outline.isFocused)
            itemView.card_outline.setOnFocusChangeListener { view, b ->
                selectedItem.postValue(position)
                setVisibility(b)
            }

        }
    }

}

fun Context.generateItems(
    episodes: List<ShiroApi.Companion.AnimePageNewEpisodes>,
    slug: String
): MutableList<MasterEpisodeAdapter.MasterEpisode> {
    val stepSize = 20 //settingsManager!!.getInt("episode_group_size", 50)
    val items = mutableListOf<MasterEpisodeAdapter.MasterEpisode>()

    /*
    if (stepSize == 0) {
        return items.apply {
            this.add(MasterEpisodeAdapter.MasterEpisode(0, episodes.size, isSeen = false, visible = true))
        }
    }*/

    for (i in episodes.indices step stepSize) {
        if (i + stepSize < episodes.size) {
            items.add(
                MasterEpisodeAdapter.MasterEpisode(i, i + stepSize)
            )
        }
    }
    // To account for the loop skipping stuff
    // Double mod to turn 0 -> stepSize
    val overflow = mod(mod(episodes.size, stepSize) - 1, stepSize) + 1
    // Dunno if != 0 is possible, but might as well keep for security
    if (overflow != 0) {
        var isSeen = false
        for (episode in episodes.size - overflow..episodes.size) {
            val key = getViewKey(slug, episode)
            if (containsKey(VIEWSTATE_KEY, key)) {
                isSeen = true
            }
        }
        items.add(
            MasterEpisodeAdapter.MasterEpisode(episodes.size - overflow, episodes.size, isSeen)
        )
    }
    if (items.size == 1) {
        items[0].visible = true
    }
    return items
}
