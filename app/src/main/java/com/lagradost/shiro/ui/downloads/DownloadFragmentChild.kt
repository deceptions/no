package com.lagradost.shiro.ui.downloads

import DOWNLOAD_PARENT_KEY
import DataStore.containsKey
import DataStore.getKey
import DataStore.removeKey
import DataStore.setKey
import VIEWSTATE_KEY
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.fragment.app.Fragment
import com.jaredrummler.cyanea.Cyanea
import com.lagradost.shiro.R
import com.lagradost.shiro.ui.MainActivity
import com.lagradost.shiro.ui.downloads.DownloadButtonSetup.handleDownloadClick
import com.lagradost.shiro.ui.downloads.DownloadFragment.Companion.getChildren
import com.lagradost.shiro.ui.player.PlayerData
import com.lagradost.shiro.ui.player.PlayerFragment
import com.lagradost.shiro.ui.result.ACTION_CLICK_DEFAULT
import com.lagradost.shiro.ui.result.ACTION_CLICK_LONG
import com.lagradost.shiro.ui.result.ACTION_PLAY_EPISODE_IN_PLAYER
import com.lagradost.shiro.ui.result.EpisodeClickEvent
import com.lagradost.shiro.ui.result.ResultFragment.Companion.isInResults
import com.lagradost.shiro.utils.AppUtils.dubbify
import com.lagradost.shiro.utils.AppUtils.getCurrentActivity
import com.lagradost.shiro.utils.AppUtils.getViewKey
import com.lagradost.shiro.utils.AppUtils.guaranteedContext
import com.lagradost.shiro.utils.AppUtils.loadPlayer
import com.lagradost.shiro.utils.Coroutines.main
import com.lagradost.shiro.utils.DownloadManager
import com.lagradost.shiro.utils.VideoDownloadManager
import com.lagradost.shiro.utils.VideoDownloadManager.downloadDeleteEvent
import kotlinx.android.synthetic.main.fragment_download_child.*
import kotlinx.coroutines.Job

const val SLUG = "slug"

class DownloadFragmentChild : Fragment() {
    var slug: String? = null
    var sortedEpisodes: List<DownloadManager.DownloadFileMetadata> = listOf()
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        isInResults = true
        arguments?.getString(SLUG)?.let {
            slug = it
        }
        download_child_scroll_view.background = ColorDrawable(Cyanea.instance.backgroundColor)
        val topParams: LinearLayout.LayoutParams = LinearLayout.LayoutParams(
            LinearLayoutCompat.LayoutParams.MATCH_PARENT, // view width
            MainActivity.statusHeight // view height
        )
        top_padding_download_child?.layoutParams = topParams
        PlayerFragment.onPlayerNavigated += ::onPlayerLeft
        download_go_back?.setOnClickListener {
            activity?.onBackPressed()
        }
        loadData()
    }

    override fun onDestroy() {
        downloadDeleteEvent -= ::updateAdapter
        (download_child_res_view?.adapter as? DownloadChildAdapter?)?.killAdapter()
        super.onDestroy()
        PlayerFragment.onPlayerNavigated -= ::onPlayerLeft
        isInResults = false
    }

    private fun onPlayerLeft(it: Boolean) {
        loadData()
    }

    private fun handleAction(episodeClick: EpisodeClickEvent, anilistId: Int?, malId: Int?): Job = main {
        when (episodeClick.action) {
            ACTION_PLAY_EPISODE_IN_PLAYER, ACTION_CLICK_DEFAULT -> {
                val info =
                    VideoDownloadManager.getDownloadFileInfoAndUpdateSettings(
                        guaranteedContext(context),
                        episodeClick.data.id
                    )

                info?.let {
                    (activity ?: getCurrentActivity())?.loadPlayer(
                        PlayerData(
                            "Episode ${episodeClick.data.episode + 1 + episodeClick.data.episodeOffset} · ${episodeClick.data.title}",
                            info.path.toString(),// child.videoPath,
                            episodeClick.data.episode,
                            0,
                            null,
                            null,
                            episodeClick.data.slug,
                            anilistId,
                            malId,
                            episodeClick.data.fillerEpisodes
                        )
                    )
                }
            }


            ACTION_CLICK_LONG -> {
                with(guaranteedContext(context)) {
                    val key = getViewKey(episodeClick.data.slug, episodeClick.data.episode)

                    val keyNormal = getViewKey(episodeClick.data.slug.dubbify(false), episodeClick.data.episode)
                    val keyDubbed = getViewKey(episodeClick.data.slug.dubbify(true), episodeClick.data.episode)

                    if (containsKey(VIEWSTATE_KEY, keyNormal) || containsKey(
                            VIEWSTATE_KEY,
                            keyDubbed
                        )
                    ) {
                        removeKey(VIEWSTATE_KEY, keyNormal)
                        removeKey(VIEWSTATE_KEY, keyDubbed)
                    } else {
                        setKey(VIEWSTATE_KEY, key, System.currentTimeMillis())
                    }
                    download_child_res_view?.adapter?.notifyItemChanged(episodeClick.adapterPosition)
                }
            }

        }
    }


    private fun updateAdapter(id: Int) {
        if (sortedEpisodes.any { it.internalId == id }) {
            sortedEpisodes = guaranteedContext(context).getSortedEpisodes(slug).filter { it.internalId != id }
            (download_child_res_view?.adapter as? DownloadChildAdapter)?.data = sortedEpisodes
            download_child_res_view?.adapter?.notifyDataSetChanged()
        }
    }


    override fun onResume() {
        downloadDeleteEvent += ::updateAdapter
        super.onResume()
    }

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun loadData() {
        downloadRootChild?.removeAllViews()
//        val save = settingsManager!!.getBoolean("save_history", true)
        val parent =
            guaranteedContext(context).getKey<DownloadManager.DownloadParentFileMetadata>(DOWNLOAD_PARENT_KEY, slug!!)
        download_header_text?.text = parent?.title
        // Sorts by Seasons and Episode Index

        sortedEpisodes = guaranteedContext(context).getSortedEpisodes(slug)

        download_child_res_view?.adapter = parent?.let {
            DownloadChildAdapter(
                it,
                sortedEpisodes,
                { episodeClick ->
                    handleAction(episodeClick, parent.anilistID, parent.malID)
                },
                { downloadClickEvent ->
                    handleDownloadClick(activity, downloadClickEvent, parent.anilistID, parent.malID)
                }
            )
        }
        download_child_res_view?.adapter?.notifyDataSetChanged()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_download_child, container, false)
    }


    companion object {
        fun newInstance(slug: String) =
            DownloadFragmentChild().apply {
                arguments = Bundle().apply {
                    putString(SLUG, slug)
                }
            }

        fun Context.getSortedEpisodes(slug: String?): List<DownloadManager.DownloadFileMetadata> {
            slug ?: return listOf()
            val metadataKeys = mutableListOf<DownloadManager.DownloadFileMetadata>()
            getChildren().forEach {
                getKey<DownloadManager.DownloadFileMetadata>(it)?.let { child ->
                    if (child.slug == slug) {
                        val fileInfo = VideoDownloadManager.getDownloadFileInfoAndUpdateSettings(
                            guaranteedContext(this),
                            child.internalId
                        )
                        if (fileInfo != null && fileInfo.fileLength > 100) {
                            metadataKeys.add(child)
                        }
                    }
                }
            }

            return metadataKeys.sortedBy { it.episodeIndex }

        }
    }
}