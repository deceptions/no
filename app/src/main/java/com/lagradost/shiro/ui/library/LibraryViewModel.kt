package com.lagradost.shiro.ui.library

import ANILIST_TOKEN_KEY
import DataStore.getKey
import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.lagradost.shiro.utils.ANILIST_ACCOUNT_ID
import com.lagradost.shiro.utils.AniListApi
import com.lagradost.shiro.utils.AniListApi.Companion.convertAnilistStringToStatus
import com.lagradost.shiro.utils.AniListApi.Companion.getAnilistAnimeListSmart
import com.lagradost.shiro.utils.MALApi
import com.lagradost.shiro.utils.MALApi.Companion.convertToStatus
import com.lagradost.shiro.utils.MALApi.Companion.getMalAnimeListSmart
import me.xdrop.fuzzywuzzy.FuzzySearch
import java.text.SimpleDateFormat
import kotlin.concurrent.thread

const val DEFAULT_SORT = 0
const val ALPHA_SORT = 1
const val REVERSE_ALPHA_SORT = 2
const val SCORE_SORT = 3
const val SCORE_SORT_REVERSED = 4
const val RANK_SORT = 5
const val RANK_SORT_REVERSED = 6
const val SEARCH = 7
const val LATEST_UPDATE = 8
const val LATEST_UPDATE_REVERSED = 9

class LibraryViewModel : ViewModel() {
    private var anilistList = listOf<List<AniListApi.Companion.Entries>?>()
    private var malList = listOf<List<MALApi.Companion.Data>?>()
    var isMal = true

    private val _currentList = MutableLiveData<List<List<Any>?>>()
    val currentList: LiveData<List<List<Any>?>> = _currentList

    val sortMethods =
        mutableListOf(LATEST_UPDATE, LATEST_UPDATE, LATEST_UPDATE, LATEST_UPDATE, LATEST_UPDATE, LATEST_UPDATE)

    /*data class SortedMalList(
        val watching: List<MALApi.Companion.Data>?,
        val planToWatch: List<MALApi.Companion.Data>?,
        val onHold: List<MALApi.Companion.Data>?,
        val completed: List<MALApi.Companion.Data>?,
        val dropped: List<MALApi.Companion.Data>?,
        val allAnime: List<MALApi.Companion.Data>?,
    )

    data class SortedAnilistList(
        val watching: List<AniListApi.Companion.Entries>?,
        val planToWatch: List<AniListApi.Companion.Entries>?,
        val onHold: List<AniListApi.Companion.Entries>?,
        val completed: List<AniListApi.Companion.Entries>?,
        val dropped: List<AniListApi.Companion.Entries>?,
        val allAnime: List<AniListApi.Companion.Entries>?,
    )*/

    fun sortCurrentList(tab: Int, sortMethod: Int, query: String? = null) {
        if (sortMethod != SEARCH) sortMethods[tab] = sortMethod
        if (isMal && malList.size > tab) {
            val sortedMalList = malList.toMutableList().apply {
                this[tab] = sortMalLis(this[tab], sortMethod, query)
            }
            _currentList.postValue(
                sortedMalList
            )
        } else if (!isMal && anilistList.size > tab) {
            val sortedAnilistList = anilistList.toMutableList().apply {
                this[tab] = sortAnilistList(this[tab], sortMethod, query)
            }
            _currentList.postValue(
                sortedAnilistList
            )
        }
    }

    private fun sortMalLis(
        array: List<MALApi.Companion.Data>?,
        sortMethod: Int? = null,
        searchText: String? = null
    ): List<MALApi.Companion.Data> {
        val array = array?.toTypedArray() ?: return listOf()
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")
        return when (sortMethod) {
            DEFAULT_SORT -> {
                array.sortBy { it.node.title.lowercase() }
                array.toList()
            }
            ALPHA_SORT -> {
                array.sortBy { it.node.title.lowercase() }
                array.toList()
            }
            REVERSE_ALPHA_SORT -> {
                array.sortBy { it.node.title.lowercase() }
                array.reverse()
                array.toList()
            }
            SCORE_SORT -> {
                array.sortBy { -(it.list_status?.score ?: 0) }
                array.toList()
            }
            SCORE_SORT_REVERSED -> {
                array.sortBy { it.list_status?.score ?: 0 }
                array.toList()
            }
            RANK_SORT -> {
                array.sortBy { if (it.node.rank == 0) Int.MAX_VALUE else it.node.rank }
                array.toList()
            }
            RANK_SORT_REVERSED -> {
                array.sortBy { -it.node.rank }
                array.toList()
            }
            LATEST_UPDATE -> {
                array.sortBy {
                    it.list_status?.updated_at?.dropLast(6)?.let {
                        -(format.parse(it)?.time ?: Long.MAX_VALUE)
                    } ?: Long.MAX_VALUE
                }
                array.toList()
            }
            LATEST_UPDATE_REVERSED -> {
                array.sortBy {
                    it.list_status?.updated_at?.dropLast(6)?.let {
                        (format.parse(it)?.time ?: Long.MAX_VALUE)
                    } ?: Long.MAX_VALUE
                }
                array.toList()
            }
            SEARCH -> {
                if (searchText != null) {
                    array.sortBy { -FuzzySearch.partialRatio(searchText.lowercase(), it.node.title.lowercase()) }
                }
                array.toList()
            }
            else -> array.toList()
        }
    }

    private fun sortAnilistList(
        array: List<AniListApi.Companion.Entries>?,
        sortMethod: Int? = null,
        searchText: String? = null
    ): List<AniListApi.Companion.Entries> {
        val array = array?.toTypedArray() ?: return listOf()
        return when (sortMethod) {
            DEFAULT_SORT -> {
                array.sortBy { (it.media.title.english ?: it.media.title.romaji ?: "").lowercase() }
                array.toList()
            }
            ALPHA_SORT -> {
                array.sortBy { (it.media.title.english ?: it.media.title.romaji ?: "").lowercase() }
                array.toList()
            }
            REVERSE_ALPHA_SORT -> {
                array.sortBy { (it.media.title.english ?: it.media.title.romaji ?: "").lowercase() }
                array.reverse()
                array.toList()
            }
            SCORE_SORT -> {
                array.sortBy { -it.score }
                array.toList()
            }
            SCORE_SORT_REVERSED -> {
                array.sortBy { it.score }
                array.toList()
            }
            RANK_SORT -> {
                array.toList()
            }
            RANK_SORT_REVERSED -> {
                array.toList()
            }
            LATEST_UPDATE -> {
                array.sortBy { -it.updatedAt }
                array.toList()
            }
            LATEST_UPDATE_REVERSED -> {
                array.sortBy { it.updatedAt }
                array.toList()
            }
            SEARCH -> {
                if (searchText != null) {
                    array.sortBy {
                        -FuzzySearch.partialRatio(
                            searchText.lowercase(),
                            (it.media.title.english ?: it.media.title.romaji ?: "").lowercase()
                        )
                    }
                }
                array.toList()
            }
            else -> array.toList()
        }
    }

    fun displayList() {
        if (isMal) {
            _currentList.postValue(
                malList.mapIndexed { index, value ->
                    sortMalLis(value, sortMethods[index])
                }
            )
        } else {
            _currentList.postValue(
                anilistList.mapIndexed { index, value ->
                    sortAnilistList(value, sortMethods[index])
                }
            )
        }
    }

    private fun updateAnilistList(list: List<AniListApi.Companion.Lists>) {
        anilistList =
            listOf(
                // Rewatching & Watching
                (list.firstOrNull {
                    convertAnilistStringToStatus(it.status ?: "") == AniListApi.Companion.AniListStatusType.Watching
                }?.entries ?: listOf()).union(list.firstOrNull {
                    convertAnilistStringToStatus(
                        it.status ?: ""
                    ) == AniListApi.Companion.AniListStatusType.Rewatching
                }?.entries ?: listOf()).toList(),

                list.firstOrNull {
                    convertAnilistStringToStatus(it.status ?: "") == AniListApi.Companion.AniListStatusType.Planning
                }?.entries,
                list.firstOrNull {
                    convertAnilistStringToStatus(it.status ?: "") == AniListApi.Companion.AniListStatusType.Paused
                }?.entries,
                list.firstOrNull {
                    convertAnilistStringToStatus(it.status ?: "") == AniListApi.Companion.AniListStatusType.Completed
                }?.entries,
                list.firstOrNull {
                    convertAnilistStringToStatus(it.status ?: "") == AniListApi.Companion.AniListStatusType.Dropped
                }?.entries,
                list.map { it.entries }.flatten()
            )
        if (!isMal) {
            _currentList.postValue(
                anilistList.mapIndexed { index, value ->
                    sortAnilistList(value, sortMethods[index])
                }
            )
        }
    }

    fun requestMalList(context: Context?) {
        thread {
//            val hasMAL = context?.getKey<String>(MAL_TOKEN_KEY, MAL_ACCOUNT_ID, null) != null
            context?.getMalAnimeListSmart()?.let {
                updateMALList(it)
            }
        }
    }

    fun requestAnilistList(context: Context?) {
        thread {
            val hasAniList = context?.getKey<String>(
                ANILIST_TOKEN_KEY,
                ANILIST_ACCOUNT_ID,
                null
            ) != null
            if (hasAniList) {
                context?.getAnilistAnimeListSmart()?.let {
                    updateAnilistList(it.toList())
                }
            }
        }
    }

    fun updateMALList(list: Array<MALApi.Companion.Data>) {
        malList =
            listOf(
                list.filter {
                    convertToStatus(
                        it.list_status?.status ?: ""
                    ) == MALApi.Companion.MalStatusType.Watching
                },
                list.filter {
                    convertToStatus(
                        it.list_status?.status ?: ""
                    ) == MALApi.Companion.MalStatusType.PlanToWatch
                },
                list.filter { convertToStatus(it.list_status?.status ?: "") == MALApi.Companion.MalStatusType.OnHold },
                list.filter {
                    convertToStatus(
                        it.list_status?.status ?: ""
                    ) == MALApi.Companion.MalStatusType.Completed
                },
                list.filter { convertToStatus(it.list_status?.status ?: "") == MALApi.Companion.MalStatusType.Dropped },
                list.toList()
            )

        if (isMal) {
            _currentList.postValue(
                malList.mapIndexed { index, value ->
                    sortMalLis(value, sortMethods[index])
                }
            )
        }
    }
}