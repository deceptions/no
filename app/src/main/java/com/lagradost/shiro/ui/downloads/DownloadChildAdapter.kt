package com.lagradost.shiro.ui.downloads

import DataStore.containsKey
import VIEWSTATE_KEY
import android.content.res.ColorStateList
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.jaredrummler.cyanea.Cyanea
import com.lagradost.shiro.R
import com.lagradost.shiro.ui.result.ACTION_CLICK_DEFAULT
import com.lagradost.shiro.ui.result.ACTION_CLICK_LONG
import com.lagradost.shiro.ui.result.ACTION_DOWNLOAD_EPISODE
import com.lagradost.shiro.ui.result.EpisodeClickEvent
import com.lagradost.shiro.ui.result.ResultFragment.Companion.fixEpTitle
import com.lagradost.shiro.ui.tv.TvActivity.Companion.tvActivity
import com.lagradost.shiro.utils.AppUtils.getColorFromAttr
import com.lagradost.shiro.utils.AppUtils.getViewKey
import com.lagradost.shiro.utils.AppUtils.getViewPosDur
import com.lagradost.shiro.utils.DownloadManager
import com.lagradost.shiro.utils.VideoDownloadManager
import kotlinx.android.synthetic.main.episode_result_downloaded.view.*
import java.util.*


class DownloadChildAdapter(
    private val parentData: DownloadManager.DownloadParentFileMetadata,
    var data: List<DownloadManager.DownloadFileMetadata>,
    private val clickCallback: (EpisodeClickEvent) -> Unit,
    private val downloadClickCallback: (DownloadClickEvent) -> Unit,
) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {
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
//        val settingsManager = PreferenceManager.getDefaultSharedPreferences(parent.context)
//        val layout =
//            if (settingsManager.getBoolean(
//                    "no_episode_thumbnails",
//                    false
//                )
//            ) R.layout.episode_result_compact else R.layout.episode_result

        return CardViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.episode_result_downloaded, parent, false),
            parentData,
            clickCallback,
            downloadClickCallback,
        )
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is CardViewHolder -> {
                holder.bind(position, data[position])
            }
        }
    }

    override fun getItemCount(): Int {
        return data.size
    }

    class CardViewHolder(
        itemView: View,
        private val parent: DownloadManager.DownloadParentFileMetadata,
        private val clickCallback: (EpisodeClickEvent) -> Unit,
        private val downloadClickCallback: (DownloadClickEvent) -> Unit,
    ) :
        RecyclerView.ViewHolder(itemView), DownloadButtonViewHolder {
        override var downloadButton: EasyDownloadButton? = if (tvActivity == null) EasyDownloadButton() else null
        var currentEntry: DownloadManager.DownloadFileMetadata? = null
        var currentPos = 0

        override fun reattachDownloadButton() {
            if (tvActivity != null) return
            currentEntry?.let { currentEntry ->
                downloadButton?.dispose()
                val id = currentEntry.internalId
                val downloadInfo = VideoDownloadManager.getDownloadFileInfoAndUpdateSettings(itemView.context, id)
                val allData = AllDataWithId(
                    id,
                    parent.slug,
                    parent.title,
                    currentEntry.episodeIndex,
                    currentEntry.episodeOffset,
//                    parent.anilistID,
//                    parent.malID,
                    parent.fillerEpisodes,
                    null
                )
                downloadButton?.setUpButton(
                    downloadInfo?.fileLength,
                    downloadInfo?.totalBytes,
                    itemView.progressBar,
                    itemView.cdi,
                    itemView.cardTitleExtra,
                    allData
                ) {
                    if (it.action == DOWNLOAD_ACTION_DOWNLOAD) {
                        clickCallback.invoke(
                            EpisodeClickEvent(
                                ACTION_DOWNLOAD_EPISODE,
                                allData,
                                currentPos
                            )
                        )
                    } else {
                        downloadClickCallback.invoke(it)
                    }
                }
            }
        }

        // Downloads is only updated when re-bound!
        fun bind(position: Int, child: DownloadManager.DownloadFileMetadata) {
            currentEntry = child
            currentPos = position
            with(itemView.context) {
                val fillerInfo =
                    if (parent.fillerEpisodes?.get(child.episodeIndex + 1) == true) " (Filler) " else ""

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    itemView.progressBar.progressTintList = ColorStateList.valueOf(Cyanea.instance.primary)
                    itemView.video_progress.progressTintList = ColorStateList.valueOf(getColorFromAttr(R.attr.white))
                    itemView.cdi.imageTintList = ColorStateList.valueOf(getColorFromAttr(R.attr.white))
                }

                val title = fixEpTitle(
                    null, child.episodeIndex + 1 + child.episodeOffset,
                    parent.isMovie, true
                )

                val defaultData = AllDataWithId(
                    child.internalId,
                    parent.slug,
                    parent.title,
                    child.episodeIndex,
                    child.episodeOffset,
//                    parent.anilistID,
//                    parent.malID,
                    parent.fillerEpisodes,
                    null
                )

                itemView.setOnLongClickListener {
                    clickCallback.invoke(
                        EpisodeClickEvent(
                            ACTION_CLICK_LONG,
                            defaultData,
                            position
                        )
                    )
                    return@setOnLongClickListener true
                }

                itemView.setOnClickListener {
                    clickCallback.invoke(
                        EpisodeClickEvent(
                            ACTION_CLICK_DEFAULT,
                            defaultData,
                            position
                        )
                    )
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    itemView.progressBar.progressTintList = ColorStateList.valueOf(Cyanea.instance.accent)
                }

                val key = getViewKey(parent.slug, child.episodeIndex)
                if (containsKey(VIEWSTATE_KEY, key)) {
                    itemView.card_outline.setCardBackgroundColor(
                        Cyanea.instance.accent
                    )
                    itemView.download_card_bg.setCardBackgroundColor(
                        Cyanea.instance.backgroundColorDark
                    )
                } else {
                    itemView.card_outline.setCardBackgroundColor(
                        Cyanea.instance.backgroundColorLight
                    )
                    itemView.download_card_bg.setCardBackgroundColor(
                        Cyanea.instance.backgroundColor
                    )
                }

                val pro = getViewPosDur(parent.slug, child.episodeIndex)
                if (pro.dur > 0 && pro.pos > 0) {
                    var progress: Int = (pro.pos * 100L / pro.dur).toInt()
                    if (progress < 5) {
                        progress = 5
                    } else if (progress > 90) {
                        progress = 100
                    }
                    itemView.video_progress?.progress = progress
                } else {
                    itemView.video_progress?.alpha = 0f
                }

                itemView.cardTitle?.text = title + fillerInfo
            }
        }
    }

}

