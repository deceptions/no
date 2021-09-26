package com.lagradost.shiro.ui.downloads

import DOWNLOAD_CHILD_KEY
import DOWNLOAD_PARENT_KEY
import DataStore.getKey
import DataStore.getKeys
import DataStore.removeKey
import DataStore.setKey
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.fasterxml.jackson.annotation.JsonProperty
import com.jaredrummler.cyanea.Cyanea
import com.lagradost.shiro.R
import com.lagradost.shiro.ui.GlideApp
import com.lagradost.shiro.ui.MainActivity
import com.lagradost.shiro.ui.MainActivity.Companion.isDonor
import com.lagradost.shiro.ui.MainActivity.Companion.masterViewModel
import com.lagradost.shiro.utils.*
import com.lagradost.shiro.utils.AppUtils.getCurrentActivity
import com.lagradost.shiro.utils.AppUtils.getNavController
import com.lagradost.shiro.utils.AppUtils.guaranteedContext
import com.lagradost.shiro.utils.AppUtils.isUsingMobileData
import com.lagradost.shiro.utils.AppUtils.loadPage
import com.lagradost.shiro.utils.AppUtils.settingsManager
import com.lagradost.shiro.utils.Coroutines.main
import com.lagradost.shiro.utils.VideoDownloadManager.currentDownloads
import com.lagradost.shiro.utils.VideoDownloadManager.downloadQueue
import com.lagradost.shiro.utils.VideoDownloadManager.saveQueue
import com.lagradost.shiro.utils.mvvm.normalSafeApiCall
import com.lagradost.shiro.utils.mvvm.observe
import kotlinx.android.synthetic.main.download_card.view.*
import kotlinx.android.synthetic.main.fragment_download.*
import java.io.File

class DownloadFragment : Fragment() {
    private val downloadFragmentTag = "DownloadFragment"

    data class EpisodesDownloaded(
        @JsonProperty("count") val count: Int,
        @JsonProperty("countDownloading") val countDownloading: Int,
        @JsonProperty("countBytes") val countBytes: Long,
    )

    private fun updateItems(bool: Boolean = true) {
        main {
            childMetadataKeys.clear()
            val childKeys = guaranteedContext(context).getChildren()
            downloadRoot?.removeAllViews()
            val inflater = activity?.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
            val epData = hashMapOf<String, EpisodesDownloaded>()
            try {
                downloadCenterText?.text =
                    if (isDonor) getString(R.string.resultpage1) else getString(R.string.resultpage2)
                downloadCenterRoot?.isVisible = childKeys.isEmpty()

                childKeys.forEach { k ->
                    val child = guaranteedContext(context).getKey<DownloadManager.DownloadFileMetadata>(k)
                    if (child != null) {
                        val fileInfo = VideoDownloadManager.getDownloadFileInfoAndUpdateSettings(
                            guaranteedContext(context),
                            child.internalId
                        )

                        if (fileInfo == null) {
                            val id = (child.slug + "E${child.episodeIndex}").hashCode()
                            if (!downloadQueue.toList()
                                    .any { it.item.ep.id == id }
                                && !currentDownloads.any { it == id }
                            ) {
                                try {
                                    child.thumbPath?.let {
                                        val thumbFile = File(it)
                                        if (thumbFile.exists()) {
                                            thumbFile.delete()
                                        }
                                    }
                                } catch (e: Exception) {
                                }
                                context?.removeKey(k)
                            }
                        } else {
                            if (childMetadataKeys.containsKey(child.slug)) {
                                childMetadataKeys[child.slug]?.add(k)
                            } else {
                                childMetadataKeys[child.slug] = mutableListOf(k)
                            }

                            val id = child.slug
                            val isDownloading =
                                VideoDownloadManager.downloadStatus.containsKey(child.internalId) &&
                                        VideoDownloadManager.downloadStatus[child.internalId] == VideoDownloadManager.DownloadType.IsDownloading

                            if (!epData.containsKey(id)) {
                                epData[id] = EpisodesDownloaded(1, if (isDownloading) 1 else 0, fileInfo.totalBytes)
                            } else {
                                val current = epData[id]!!
                                epData[id] = EpisodesDownloaded(
                                    current.count + 1,
                                    current.countDownloading + (if (isDownloading) 1 else 0),
                                    current.countBytes + fileInfo.totalBytes
                                )
                            }
                        }
                    }
                }

                val keys = context?.getKeys(DOWNLOAD_PARENT_KEY)
                for (k in keys ?: listOf()) {
                    val parent = context?.getKey<DownloadManager.DownloadParentFileMetadata>(k)
                    if (parent != null) {
                        if (epData.containsKey(parent.slug.replace("-dubbed", "-dub"))) {
                            val cardView = inflater.inflate(R.layout.download_card, view?.parent as? ViewGroup, false)

                            cardView.imageView.setOnClickListener {
                                activity?.loadPage(parent.slug.replace("-dubbed", "-dub"), parent.title)
                            }

                            cardView.cardTitle?.text = parent.title
                            //cardView.imageView.setImageURI(Uri.parse(parent.coverImagePath))

                            // Legacy
                            if (parent.coverImagePath.startsWith(getCurrentActivity()!!.filesDir.toString())) {
                                context?.let {
                                    GlideApp.with(it)
                                        .load(Uri.fromFile(File(parent.coverImagePath)))
                                        .transition(DrawableTransitionOptions.withCrossFade(200))
                                        .into(cardView.imageView)
                                }
                            } else {
                                val glideUrlMain =
                                    ShiroApi.getFullUrlCdn(parent.coverImagePath)
                                context?.let {
                                    GlideApp.with(it)
                                        .load(glideUrlMain)
                                        .transition(DrawableTransitionOptions.withCrossFade(200))
                                        .into(cardView.imageView)
                                }
                            }

                            val childData = epData[parent.slug.replace("-dubbed", "-dub")]!!
                            val megaBytes = DownloadManager.convertBytesToAny(childData.countBytes, 0, 2.0).toInt()
                            cardView.cardInfo?.text =
                                if (parent.isMovie) "$megaBytes MB" else
                                    "${childData.count} Episode${(if (childData.count == 1) "" else "s")} | $megaBytes MB"
                            cardView.card_outline?.setCardBackgroundColor(Cyanea.instance.backgroundColorDark)
                            cardView.card_outline?.setCardBackgroundColor(Cyanea.instance.backgroundColorDark)
                            cardView.card_outline.setOnClickListener {
                                val arguments = Bundle().apply {
                                    putString(SLUG, parent.slug.replace("-dubbed", "-dub"))
                                }
                                activity?.getNavController()?.navigate(
                                    R.id.action_navigation_downloads_to_navigation_download_child, arguments
                                )
//                                activity?.addFragmentOnlyOnce(
//                                    R.id.homeRoot,
//                                    DownloadFragmentChild.newInstance(
//                                        parent.slug.replace("-dubbed", "-dub")
//                                    ),
//                                    downloadFragmentTag
//                                )

                                /*MainActivity.activity?.supportFragmentManager?.beginTransaction()
                                    ?.setCustomAnimations(R.anim.enter, R.anim.exit, R.anim.pop_enter, R.anim.pop_exit)
                                    ?.replace(
                                        R.id.homeRoot, DownloadFragmentChild(
                                            parent.anilistId
                                        )
                                    )
                                    ?.commitAllowingStateLoss()*/
                            }

                            downloadRoot?.addView(cardView)
                        } else {
                            if (currentDownloads.isEmpty() && downloadQueue.isEmpty()
                            ) {
                                val coverFile = File(parent.coverImagePath)
                                if (coverFile.exists()) {
                                    coverFile.delete()
                                }
                                context?.removeKey(k)
                            }
                        }
                    }
                }

            } catch (e: Exception) {
                println("ERROR LOADING DOWNLOADS:::")
                e.printStackTrace()
            }
        }
    }


    @SuppressLint("SetTextI18n")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val topParams: LinearLayout.LayoutParams = LinearLayout.LayoutParams(
            LinearLayoutCompat.LayoutParams.MATCH_PARENT, // view width
            MainActivity.statusHeight // view height
        )

        download_fragment_background?.background = ColorDrawable(Cyanea.instance.backgroundColor)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            queue_card?.backgroundTintList = ColorStateList.valueOf(Cyanea.instance.backgroundColorDark)
        }
        queue_card?.setOnClickListener {
            activity?.getNavController()?.navigate(
                R.id.action_navigation_downloads_to_navigation_download_queue
            )
//            activity?.addFragmentOnlyOnce(
//                R.id.homeRoot,
//                QueueFragment.newInstance(),
//                downloadFragmentTag
//            )
        }

        fun setQueueText() {
            val size = downloadQueue.toList().filterNotNull().distinctBy { it.item.ep.id }.size
            val suffix = if (size == 1) "" else "s"
            val pausedStatus = if (masterViewModel?.isQueuePaused?.value != false) "\nPaused" else ""
            queue_card_text?.text = "Queue (${
                size
            } item$suffix) $pausedStatus"
        }
        setQueueText()

        observe(masterViewModel!!.downloadQueue) {
            setQueueText()
        }

        observe(masterViewModel!!.isQueuePaused) {
            setQueueText()
            if (it) {
                queue_pause_play?.setImageResource(R.drawable.netflix_play)
            } else {
                queue_pause_play?.setImageResource(R.drawable.netflix_pause)
            }
            context?.let { ctx -> saveQueue(ctx) }
        }

        queue_pause_play?.setOnClickListener {
            // If on data => pause downloads
            if (settingsManager?.getBoolean(
                    "disable_data_downloads",
                    false
                ) == true && it.context.isUsingMobileData() && masterViewModel?.isQueuePaused?.value == true
            ) {
                masterViewModel?.isQueuePaused?.postValue(true)
                Toast.makeText(context, "Downloads on data are disabled", Toast.LENGTH_LONG).show()
            } else {
                masterViewModel?.isQueuePaused?.postValue(masterViewModel?.isQueuePaused?.value?.not() ?: true)
            }
        }
        top_padding_download.layoutParams = topParams
        updateItems()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_download, container, false)
    }

    companion object {
        val downloadsUpdated = Event<Boolean>()
        val childMetadataKeys = hashMapOf<String, MutableList<String>>()
        const val LEGACY_DOWNLOADS = "legacy_downloads_3"

        fun Context.getChildren(): List<String> {
            val legacyDownloads = getKey(LEGACY_DOWNLOADS, true)
            if (legacyDownloads == true) {
                convertOldDownloads()
            }

            return getKeys(DOWNLOAD_CHILD_KEY)
        }

        private fun Context.convertOldDownloads() {
            normalSafeApiCall {
                val keys = getKeys(DOWNLOAD_CHILD_KEY)
                normalSafeApiCall {
                    keys.pmap {
                        getKey<DownloadManager.DownloadFileMetadataLegacy>(it)
                    }
                }
                keys.forEach {
                    val data = getKey<DownloadManager.DownloadFileMetadataLegacy>(it)
                    if (data != null) {
                        // NEEDS REMOVAL TO PREVENT DUPLICATES
                        removeKey(it)
                        val episodeOffset =
                            if (data.animeData.episodes?.filter { it.episode_number == 0 }.isNullOrEmpty()) 0 else -1
                        setKey(
                            it, DownloadManager.DownloadFileMetadata(
                                data.internalId,
                                data.slug.replace("-dubbed", "-dub"),
                                data.thumbPath,
                                data.videoTitle,
                                data.episodeIndex,
                                data.downloadAt,
                                episodeOffset
                            )
                        )
                    } else {
                        removeKey(it)
                    }
                }
                setKey(LEGACY_DOWNLOADS, false)
            }
        }

    }


    override fun onResume() {
        downloadsUpdated += ::updateItems
        super.onResume()
    }

    override fun onDestroy() {
        downloadsUpdated -= ::updateItems
        super.onDestroy()
    }

}