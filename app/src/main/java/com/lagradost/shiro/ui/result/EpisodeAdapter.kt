package com.lagradost.shiro.ui.result

import DataStore.containsKey
import VIEWSTATE_KEY
import android.content.res.ColorStateList
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.cardview.widget.CardView
import androidx.core.view.isVisible
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.jaredrummler.cyanea.Cyanea
import com.lagradost.shiro.R
import com.lagradost.shiro.ui.GlideApp
import com.lagradost.shiro.ui.NextEpisode
import com.lagradost.shiro.ui.downloads.*
import com.lagradost.shiro.ui.toPx
import com.lagradost.shiro.ui.tv.TvActivity.Companion.tvActivity
import com.lagradost.shiro.utils.AppUtils.dubbify
import com.lagradost.shiro.utils.AppUtils.getColorFromAttr
import com.lagradost.shiro.utils.AppUtils.getLatestSeenEpisode
import com.lagradost.shiro.utils.AppUtils.getViewKey
import com.lagradost.shiro.utils.AppUtils.getViewPosDur
import com.lagradost.shiro.utils.ShiroApi
import com.lagradost.shiro.utils.ShiroApi.Companion.getFullUrlCdn
import com.lagradost.shiro.utils.VideoDownloadManager
import kotlinx.android.synthetic.main.episode_result.view.*
import kotlinx.android.synthetic.main.episode_result_compact.view.cardTitle
import kotlinx.android.synthetic.main.episode_result_compact.view.cdi
import kotlinx.android.synthetic.main.episode_result_compact.view.episode_result_root
import kotlinx.android.synthetic.main.episode_result_compact.view.progressBar
import kotlinx.android.synthetic.main.episode_result_compact.view.video_progress
import kotlinx.android.synthetic.main.fragment_results.view.*
import java.util.*

const val ACTION_PLAY_EPISODE_IN_PLAYER = 1
const val ACTION_PLAY_EPISODE_IN_VLC_PLAYER = 2
const val ACTION_PLAY_EPISODE_IN_BROWSER = 3

const val ACTION_CHROME_CAST_EPISODE = 4
const val ACTION_CHROME_CAST_MIRROR = 5

const val ACTION_DOWNLOAD_EPISODE = 6
const val ACTION_DOWNLOAD_MIRROR = 7

const val ACTION_RELOAD_EPISODE = 8
const val ACTION_COPY_LINK = 9

const val ACTION_SHOW_OPTIONS = 10

const val ACTION_CLICK_DEFAULT = 11
const val ACTION_CLICK_LONG = 12


data class EpisodeClickEvent(
    val action: Int,
    val data: AllDataWithId,
    val adapterPosition: Int
)

class EpisodeAdapter(
    val data: ShiroApi.Companion.AnimePageNewData,
    private val resView: View,
    private val parentPosition: Int,
    rangeStart: Int? = null,
    rangeStop: Int? = null,
//    val anilistID: Int?,
//    val malID: Int?,
    private val isFiller: HashMap<Int, Boolean>? = null,
    private val clickCallback: (EpisodeClickEvent) -> Unit,
    private val downloadClickCallback: (DownloadClickEvent) -> Unit,
) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    val stop = rangeStop ?: data.episodes.size
    val start = rangeStart ?: 0
    var episodes = data.episodes.subList(start, stop)

    private var prevFocus: Int? = null

    private val mBoundViewHolders: HashSet<DownloadButtonViewHolder> = HashSet()
    private fun getAllBoundViewHolders(): Set<DownloadButtonViewHolder?>? {
        return Collections.unmodifiableSet(mBoundViewHolders)
    }

    fun killAdapter() {
        getAllBoundViewHolders()?.forEach { view ->
            view?.downloadButton?.dispose()
        }
    }

    override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
        if (holder is DownloadButtonViewHolder) {
            holder.downloadButton?.dispose()
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is DownloadButtonViewHolder) {
            holder.downloadButton?.dispose()
            mBoundViewHolders.remove(holder)
        }
    }

    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        if (holder is DownloadButtonViewHolder) {
            holder.reattachDownloadButton()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val settingsManager = PreferenceManager.getDefaultSharedPreferences(parent.context)
        val layout =
            if (settingsManager.getBoolean(
                    "no_episode_thumbnails",
                    false
                )
            ) R.layout.episode_result_compact else R.layout.episode_result
        val hasDescription = !settingsManager.getBoolean("no_episode_description", false)

        return CardViewHolder(
            LayoutInflater.from(parent.context).inflate(layout, parent, false),
            resView,
            data,
            start,
            parentPosition,
            isFiller,
            hasDescription,

//            anilistID,
//            malID,

            clickCallback,
            downloadClickCallback,
        )
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        //lastSelectedEpisode = 0
        holder.itemView.setOnFocusChangeListener { _, _ ->
            //lastSelectedEpisode = position
            if (prevFocus != null) {
                if (kotlin.math.abs(position - prevFocus!!) > 3 * 2) {
                    this.resView.episodes_res_view.layoutManager?.scrollToPosition(0)
                }
            }
            prevFocus = position
            //updateFocusPositions(holder, hasFocus, position)
        }

        when (holder) {
            is CardViewHolder -> {
                holder.bind(position)
            }
        }
    }

    override fun getItemCount(): Int {
        return episodes.size
    }

    class CardViewHolder
    constructor(
        itemView: View,
        private val resView: View,
        val data: ShiroApi.Companion.AnimePageNewData,
        val start: Int,
        private val parentPosition: Int,
        private val isFiller: HashMap<Int, Boolean>?,
        private val hasDescription: Boolean,

//        val anilistID: Int?,
//        val malID: Int?,

        private val clickCallback: (EpisodeClickEvent) -> Unit,
        private val downloadClickCallback: (DownloadClickEvent) -> Unit,
    ) :
        RecyclerView.ViewHolder(itemView), DownloadButtonViewHolder {
        val card: CardView = itemView.episode_result_root
        var cardPosition = 0
        override var downloadButton: EasyDownloadButton? = if (tvActivity == null) EasyDownloadButton() else null

        override fun reattachDownloadButton() {
            if (tvActivity != null) return

            downloadButton?.dispose()
            val episode = start + cardPosition
            val id = (data.anime.slug + "E$episode").hashCode()
            val downloadInfo = VideoDownloadManager.getDownloadFileInfoAndUpdateSettings(itemView.context, id)
            val episodeOffset = if (data.episodes.filter { it.episode == "0" }.isNullOrEmpty()) 0 else -1

            downloadButton?.setUpButton(
                downloadInfo?.fileLength, downloadInfo?.totalBytes, itemView.progressBar, itemView.cdi, null,
                AllDataWithId(
                    id,
                    data.anime.slug,
                    data.anime.title,
                    episode,
                    episodeOffset,
//                    anilistID,
//                    malID,
                    isFiller,
                    data
                )
            ) {
                if (it.action == DOWNLOAD_ACTION_DOWNLOAD) {
                    clickCallback.invoke(
                        EpisodeClickEvent(
                            ACTION_DOWNLOAD_EPISODE,
                            AllDataWithId(
                                id,
                                data.anime.slug,
                                data.anime.title,
                                episode,
                                episodeOffset,
//                                anilistID,
//                                malID,
                                isFiller,
                                data
                            ),
                            parentPosition
                        )
                    )
                } else {
                    downloadClickCallback.invoke(it)
                }
            }
        }

        // Downloads is only updated when re-bound!
        fun bind(position: Int) {
            with(itemView.context) {
                cardPosition = position

                if (position == 0) {
                    card.requestFocus()
                }
                if (position == 0 || position == 1) {
                    card.setOnFocusChangeListener { _: View, focused: Boolean ->
                        resView.isFocusable = focused
                    }
                }
                val episodeOffset = if (data.episodes.filter { it.episode == "0" }.isNullOrEmpty()) 0 else -1
                val episodePos = start + position
                val id = (data.anime.slug + "E$episodePos").hashCode()
                card.video_thumbnail?.let {
                    data.episodes.getOrNull(episodePos)?.image?.let { thumbnail ->
                        val guaranteedThumbnail = thumbnail.ifBlank { data.anime.poster }
                        val settingsManager = PreferenceManager.getDefaultSharedPreferences(this)
                        val savingData = settingsManager.getBoolean("data_saving", false)
                        val url = getFullUrlCdn(guaranteedThumbnail)
                        GlideApp.with(this)
                            .load(url)
                            .transition(DrawableTransitionOptions.withCrossFade(100))
                            .onlyRetrieveFromCache(savingData)
                            .into(it)
                    }
                }

                val defaultData =
                    AllDataWithId(
                        id,
                        data.anime.slug,
                        data.anime.title,
                        episodePos,
                        episodeOffset,
//                        anilistID,
//                        malID,
                        isFiller,
                        data
                    )

                card.cdi.isVisible = tvActivity == null
                card.setOnClickListener {
                    clickCallback.invoke(
                        EpisodeClickEvent(
                            ACTION_CLICK_DEFAULT,
                            defaultData,
                            parentPosition
                        )
                    )
                }

                val longClickListener = View.OnLongClickListener {
                    clickCallback.invoke(
                        EpisodeClickEvent(
                            ACTION_CLICK_LONG,
                            defaultData,
                            parentPosition
                        )
                    )
                    return@OnLongClickListener true
                }

                card.setOnLongClickListener(longClickListener)

                val isCurrentFiller = if (isFiller != null) {
                    if (isFiller.containsKey(episodePos + 1)) {
                        isFiller[episodePos + 1] ?: false
                    } else false
                } else false

                val settingsManager = PreferenceManager.getDefaultSharedPreferences(this)
                val noThumbnails = settingsManager.getBoolean("no_episode_thumbnails", false)
                val fillerSuffix = if (isCurrentFiller) "(Filler)" else ""

                val siteTitle = data.episodes.getOrNull(episodePos)?.title

                /**
                 * 1. No thumbnails -> Episode 1 (Filler)
                 * 2. Has episode in title -> (Filler) <title>Episode 1....</title>
                 * 3. Doesn't have episode in title -> Episode 1. (Filler) <title>....</title>
                 * 4. else -> Episode 1 (Filler)
                 */
                val fixedSiteTitle = when {
                    noThumbnails -> "Episode ${episodePos + 1 + episodeOffset} $fillerSuffix"
                    Regex("""Episode \d+""").matches(siteTitle ?: "") -> "$fillerSuffix $siteTitle"
                    siteTitle != null -> "${episodePos + 1 + episodeOffset}. $fillerSuffix $siteTitle"
                    else -> "Episode ${episodePos + 1 + episodeOffset} $fillerSuffix"
                }

                card.cardTitle?.text = fixedSiteTitle

                card.cardSummary?.isVisible = hasDescription
                if (hasDescription) {
                    card.cardSummary?.setOnLongClickListener(longClickListener)
                    val summary = data.episodes.getOrNull(episodePos)?.insight?.replace("`", "'") ?: "None"
                    if (summary == "None") {
                        card.cardSummary?.visibility = GONE
                    } else {
                        card.cardSummary?.text = summary
                        card.cardSummary?.setOnClickListener {
                            val builder: AlertDialog.Builder =
                                AlertDialog.Builder(this, R.style.AlertDialogCustom)
                            builder.setMessage(summary).setTitle("Insight")
                                .show()
                        }
                    }
                }


                val pro = getViewPosDur(data.anime.slug, episodePos)
                if (pro.dur > 0 && pro.pos > 0) {
                    var progress: Int = (pro.pos * 100L / pro.dur).toInt()
                    if (progress < 5) {
                        progress = 5
                    } else if (progress > 90) {
                        progress = 100
                    }
                    card.video_progress.alpha = 1f
                    card.video_progress.progress = progress
                } else {
                    card.video_progress.alpha = 0f
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    card.progressBar.progressTintList = ColorStateList.valueOf(Cyanea.instance.accent)
                }


                val keyNormal = getViewKey(data.anime.slug.dubbify(false), episodePos)
                val keyDubbed = getViewKey(data.anime.slug.dubbify(true), episodePos)

                var lastDubbed: NextEpisode? = null
                var lastNormal: NextEpisode? = null

                fun setVisibility(focused: Boolean) {
                    if (focused) {
                        card.setCardBackgroundColor(
                            getColorFromAttr(R.attr.white)
                        )
                    } else if (containsKey(VIEWSTATE_KEY, keyNormal) || containsKey(VIEWSTATE_KEY, keyDubbed)) {
                        lastNormal = lastNormal ?: getLatestSeenEpisode(data.dubbify(false))
                        lastDubbed = lastDubbed ?: getLatestSeenEpisode(data.dubbify(true))
                        val last =
                            if (lastDubbed!!.episodeIndex > lastNormal!!.episodeIndex) lastDubbed!! else lastNormal!!

                        card.card_bg.setCardBackgroundColor(
                            Cyanea.instance.backgroundColorDark
                        )

                        val margins = if (last.isFound && last.episodeIndex == episodePos) {
                            card.setCardBackgroundColor(
                                Cyanea.instance.accent
                            )
                            4.toPx
                        } else {
                            card.setCardBackgroundColor(
                                Cyanea.instance.accentDark
                            )
                            2.toPx
                        }
                        card.card_bg.layoutParams = card.card_bg.layoutParams.apply {
                            (this as FrameLayout.LayoutParams).setMargins(margins, margins, margins, margins)
                        }
                    } else {
                        card.card_bg.setCardBackgroundColor(
                            Cyanea.instance.backgroundColor
                        )
                        card.setCardBackgroundColor(
                            Cyanea.instance.backgroundColorLight
                        )
                    }
                }

                setVisibility(card.isFocused)
                card.setOnFocusChangeListener { view, b ->
                    setVisibility(b)
                }
            }
        }
    }

}

