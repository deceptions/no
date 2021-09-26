package com.lagradost.shiro.utils

object VideoDownloadHelper {
    data class DownloadEpisodeCached(
        val name: String?,
        val poster: String?,
        val episode: Int,
        val season: Int?,
        val id: Int,
        val parentId: Int,
        val rating: Int?,
        val descript: String?,
        val cacheTime: Long,
    )

    data class DownloadHeaderCached(
        val apiName: String,
        val url: String,
        val name: String,
        val poster: String?,
        val id: Int,
        val cacheTime: Long,
    )
}