package com.lagradost.shiro.utils

import DOWNLOAD_CHILD_KEY
import DOWNLOAD_PARENT_KEY
import DataStore.setKey
import android.app.Notification
import android.content.Context
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.shiro.utils.AppUtils.checkWrite
import com.lagradost.shiro.utils.AppUtils.getViewKey
import com.lagradost.shiro.utils.AppUtils.requestRW
import com.lagradost.shiro.utils.ShiroApi.Companion.getFullUrlCdn
import kotlin.math.pow
import kotlin.math.round

object DownloadManager {
    data class DownloadParentFileMetadata(
        @JsonProperty("title") val title: String,
        @JsonProperty("coverImagePath") val coverImagePath: String,
        @JsonProperty("isMovie") val isMovie: Boolean,
        @JsonProperty("slug") val slug: String,

        @JsonProperty("anilistID") val anilistID: Int?,
        @JsonProperty("malID") val malID: Int?,
        @JsonProperty("fillerEpisodes") val fillerEpisodes: HashMap<Int, Boolean>?
    )

    // Glue for invoke()
    /*data class DownloadEventAndChild(
        @JsonProperty("downloadEvent") val downloadEvent: DownloadEvent,
        @JsonProperty("child") val child: DownloadFileMetadata,
    )*/

    data class DownloadFileMetadata(
        @JsonProperty("internalId") val internalId: Int, // UNIQUE ID BASED ON aniListId season and index
        @JsonProperty("slug") val slug: String,
        @JsonProperty("thumbPath") val thumbPath: String?,
        @JsonProperty("videoTitle") val videoTitle: String,
        @JsonProperty("episodeIndex") val episodeIndex: Int,
        @JsonProperty("downloadAt") val downloadAt: Long,
        @JsonProperty("episodeOffset") val episodeOffset: Int,
    )

    data class DownloadFileMetadataLegacy(
        @JsonProperty("internalId") val internalId: Int, // UNIQUE ID BASED ON aniListId season and index
        @JsonProperty("slug") val slug: String,
        @JsonProperty("animeData") val animeData: ShiroApi.AnimePageData,
        @JsonProperty("thumbPath") val thumbPath: String?,
        @JsonProperty("videoTitle") val videoTitle: String,
        @JsonProperty("episodeIndex") val episodeIndex: Int,
        @JsonProperty("downloadAt") val downloadAt: Long,
    )

    data class DownloadInfo(
        //val card: FastAniApi.Card?,
        @JsonProperty("episodeIndex") val episodeIndex: Int,
        @JsonProperty("animeData") val animeData: ShiroApi.Companion.AnimePageNewData,
        @JsonProperty("anilistID") val anilistID: Int? = null,
        @JsonProperty("malID") val malID: Int? = null,
        @JsonProperty("fillerEpisodes") val fillerEpisodes: HashMap<Int, Boolean>? = null
    )

    private fun Double.round(decimals: Int): Double {
        var multiplier = 1.0
        repeat(decimals) { multiplier *= 10 }
        return round(this * multiplier) / multiplier
    }

    fun convertBytesToAny(bytes: Long, digits: Int = 2, steps: Double = 3.0): Double {
        return (bytes / 1024.0.pow(steps)).round(digits)
    }

    private fun startWork(context: Context, key: String) {
        val req = OneTimeWorkRequest.Builder(DownloadFileWorkManager::class.java)
            .setInputData(
                Data.Builder()
                    .putString("key", key)
                    .build()
            )
            .build()
        (WorkManager.getInstance(context)).enqueueUniqueWork(
            key,
            ExistingWorkPolicy.KEEP,
            req
        )
    }

    fun checkDownloadsUsingWorker(
        context: Context,
    ) {
        startWork(context, DOWNLOAD_CHECK)
    }

    fun resumeEpisodeUsingWorker(
        context: Context,
        pkg: VideoDownloadManager.DownloadResumePackage,
        showToast: Boolean = true
    ) {
        context.setKey(WORK_KEY_PACKAGE, pkg.item.ep.id.toString(), pkg)
        context.setKey(WORK_KEY_SHOW_TOAST, pkg.item.ep.id.toString(), showToast)
        startWork(context, pkg.item.ep.id.toString())
    }

    const val WORK_KEY_SHOW_TOAST = "work_key_show_toast"
    const val WORK_KEY_PACKAGE = "work_key_package"
    const val WORK_KEY_INFO = "work_key_info"
    const val WORK_KEY_LINK = "work_key_link"

    fun downloadEpisodeUsingWorker(context: Context, info: DownloadInfo, link: List<ExtractorLink>) {
        val key = getViewKey(info.animeData.anime.slug, info.episodeIndex)
        context.setKey(WORK_KEY_INFO, key, info)
        context.setKey(WORK_KEY_LINK, key, link)

        startWork(context, key)
    }

    fun downloadEpisode(
        context: Context,
        info: DownloadInfo,
        link: List<ExtractorLink>,
        notificationCallback: (Int, Notification) -> Unit
    ): Int {
        val id = (info.animeData.anime.slug + "E${info.episodeIndex}").hashCode()
        if (!context.checkWrite()) {
            Toast.makeText(context, "Accept storage permissions to download", Toast.LENGTH_LONG).show()
            (context as? FragmentActivity)?.requestRW()
            println("No write capabilities!")
            return id
        }

        val isMovie: Boolean =
            info.animeData.episodes.size == 1 && info.animeData.anime.status.lowercase() == "finished airing"

        val episodeOffset = if (info.animeData.episodes.filter { it.episode == "0" }.isNullOrEmpty()) 0 else -1

        context.setKey(
            DOWNLOAD_PARENT_KEY, info.animeData.anime.slug,
            DownloadParentFileMetadata(
                info.animeData.anime.title,
                getFullUrlCdn(info.animeData.anime.poster), //mainPosterPath
                isMovie,
                info.animeData.anime.slug,
                info.anilistID,
                info.malID,
                info.fillerEpisodes
            )
        )

        var title = info.animeData.anime.title
        if (title.replace(" ", "") == "") {
            title = "Episode " + info.episodeIndex + 1
        }

        context.setKey(
            DOWNLOAD_CHILD_KEY, id.toString(),
            DownloadFileMetadata(
                id,
                info.animeData.anime.slug,
                getFullUrlCdn(info.animeData.anime.poster), //TODO Download poster
                title,
                info.episodeIndex,
                System.currentTimeMillis(),
                episodeOffset
            )
        )

        val mainTitle = info.animeData.anime.title

        val folder = if (isMovie) {
            "Movies"
        } else {
            "Anime/${VideoDownloadManager.sanitizeFilename(mainTitle)}"
        }
        val name = if (isMovie) mainTitle else null

        VideoDownloadManager.downloadEpisode(
            context,
            "********/${info.animeData.anime.slug}",
            folder,
            VideoDownloadManager.DownloadEpisodeMetadata(
                id,
                mainTitle,
                null, // "Shiro"
                getFullUrlCdn(info.animeData.anime.poster),
                name,
                null,
                if (isMovie) null else info.episodeIndex + 1 + episodeOffset
            ),
            link,
            notificationCallback
        )
        return id
    }
}