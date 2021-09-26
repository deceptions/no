package com.lagradost.shiro.ui.result

import ANILIST_SHOULD_UPDATE_LIST
import ANILIST_TOKEN_KEY
import DataStore.getKey
import DataStore.mapper
import DataStore.setKey
import MAL_SHOULD_UPDATE_LIST
import MAL_TOKEN_KEY
import RESULTS_PAGE_OVERRIDE_ANILIST
import RESULTS_PAGE_OVERRIDE_MAL
import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.shiro.ui.library.LibraryFragment.Companion.libraryViewModel
import com.lagradost.shiro.utils.*
import com.lagradost.shiro.utils.AniListApi.Companion.fromIntToAnimeStatus
import com.lagradost.shiro.utils.AniListApi.Companion.getDataAboutId
import com.lagradost.shiro.utils.AniListApi.Companion.getShowId
import com.lagradost.shiro.utils.AniListApi.Companion.postDataAboutId
import com.lagradost.shiro.utils.AppUtils.guaranteedContext
import com.lagradost.shiro.utils.MALApi.Companion.getDataAboutMalId
import com.lagradost.shiro.utils.MALApi.Companion.malStatusAsString
import com.lagradost.shiro.utils.MALApi.Companion.setScoreRequest
import com.lagradost.shiro.utils.ShiroApi.Companion.getAnimePageNew
import com.lagradost.shiro.utils.ShiroApi.Companion.getAnimePageNewMal
import kotlin.concurrent.thread

data class SyncData(
    var malId: Int?,
    var anilistId: Int?,
    var title: String,
    var status: Int,
    var progress: Int,
    var episodes: Int,
    var score: Int,
)

class ResultsViewModel : ViewModel() {

    val localData = MutableLiveData<SyncData?>(null)
    val syncData = MutableLiveData<SyncData?>(null)

    private var isCurrentlySubbed = true
    private var allowSyncDataUpdates = false

    private var lastMalId: Int? = null
    private var lastAnilistId: Int? = null

    private val dataSubbed = MutableLiveData<ShiroApi.Companion.AnimePageNewData?>()
    private val dataDubbed = MutableLiveData<ShiroApi.Companion.AnimePageNewData?>()
    private val _data = MutableLiveData<ShiroApi.Companion.AnimePageNewData?>()
    val data: LiveData<ShiroApi.Companion.AnimePageNewData?> = _data
    val recommendations = MutableLiveData<List<AniListApi.Recommendation>?>(null)
    val related = MutableLiveData<List<AniListApi.Recommendation>?>(null)

    // Because data cannot be posted as null
    private val _hasFailed = MutableLiveData(false)
    val hasFailed: LiveData<Boolean> = _hasFailed

    private val _hasLoadedOther = MutableLiveData(false)
    val hasLoadedOther: LiveData<Boolean> = _hasLoadedOther


    private fun loadOther(slug: String) {
        if (slug.endsWith("-dub")) {
            getAnimePageNew(
                slug.replace("shippuuden", "shippuden").removeSuffix("-dub")
            )?.data?.let {
                dataSubbed.postValue(it)
                _hasLoadedOther.postValue(true)
            }
        } else {
            getAnimePageNew(slug.replace("shippuden", "shippuuden") + "-dub")?.data?.let {
                dataDubbed.postValue(it)
                _hasLoadedOther.postValue(true)
            }
        }
    }

    data class IdObject(
        @JsonProperty("mal") val mal: String?,
    )

    private fun Context.updateSyncData(
        /** Because viewModel values aren't instant */
        overrideMalId: Int? = null,
        overrideAnilistId: Int? = null
    ) {
        val hasAniList = getKey<String>(
            ANILIST_TOKEN_KEY,
            ANILIST_ACCOUNT_ID,
            null
        ) != null
        val hasMAL = getKey<String>(MAL_TOKEN_KEY, MAL_ACCOUNT_ID, null) != null

        val malId = overrideMalId ?: getMalId()
        val anilistId = overrideAnilistId ?: getAnilistId()

        println("HERE! $malId $anilistId $lastAnilistId $lastMalId")


        if (malId == lastMalId && anilistId == lastAnilistId) return

        lastAnilistId = anilistId
        lastMalId = malId

        val holder = if (hasAniList) anilistId?.let {
            getDataAboutId(
                it
            )
        } else null
        val malHolder =
            if (hasMAL && holder == null) malId?.let {
                getDataAboutMalId(
                    it
                )
            } else null

        val localSyncData = when {
            holder != null -> {
                SyncData(
                    malId,
                    anilistId,
                    holder.title.english ?: holder.title.romaji ?: "",
                    holder.type.value,
                    holder.progress,
                    holder.episodes,
                    holder.score,
                )
            }
            malHolder != null -> {
                SyncData(
                    malId,
                    anilistId,
                    malHolder.title,
                    malStatusAsString.indexOf(malHolder.my_list_status?.status ?: "none"),
                    malHolder.my_list_status?.num_episodes_watched ?: 0,
                    malHolder.num_episodes,
                    malHolder.my_list_status?.score ?: 0,
                )
            }
            else -> return
        }

        syncData.postValue(localSyncData)
        if (localData.value == null || (overrideAnilistId ?: overrideMalId) != null) localData.postValue(localSyncData)
    }

    fun syncLocalData(context: Context, overrideData: SyncData? = null) {
        val data = overrideData ?: localData.value ?: return

        context.setKey(MAL_SHOULD_UPDATE_LIST, true)
        context.setKey(ANILIST_SHOULD_UPDATE_LIST, true)
        libraryViewModel?.requestMalList(context)
        libraryViewModel?.requestAnilistList(context)
        val hasAniList = context.getKey<String>(
            ANILIST_TOKEN_KEY,
            ANILIST_ACCOUNT_ID,
            null
        ) != null
        val hasMAL = context.getKey<String>(MAL_TOKEN_KEY, MAL_ACCOUNT_ID, null) != null


        thread {
            val anilistPost =
                if (hasAniList) getAnilistId()?.let {
                    context.postDataAboutId(
                        it,
                        fromIntToAnimeStatus(data.status),
                        data.score,
                        data.progress
                    )
                } else true
            val malPost = if (hasMAL)
                getMalId()?.let {
                    context.setScoreRequest(
                        it,
                        MALApi.fromIntToAnimeStatus(data.status),
                        data.score,
                        data.progress
                    )
                } else true

            // TODO ERROR HANDLING

            if (malPost == true && anilistPost == true) {
                syncData.postValue(data)
            }
        }
    }

    private fun Context.assignData(data: ShiroApi.Companion.AnimePageNewData?) {
        if (data == null) {
            _hasFailed.postValue(true)
            return
        }
        isCurrentlySubbed = if (data.anime.slug.endsWith("-dub")) {
            dataDubbed.postValue(data)
            false
        } else {
            dataSubbed.postValue(data)
            true
        }

        val overrideAnilistId = getKey<Int>(RESULTS_PAGE_OVERRIDE_ANILIST, data.anime.slug.removeSuffix("-dub"))
        val overrideMalId = getKey<Int>(RESULTS_PAGE_OVERRIDE_MAL, data.anime.slug.removeSuffix("-dub"))
        setMalIdOverride(overrideMalId)
        setAnilistIdOverride(overrideAnilistId)

        thread {
            val idMal = mapper.readValue<IdObject?>(data.anime.ids)?.mal
            val anilistPage = getShowId(idMal.toString(), data.anime.title, data.anime.release_year?.toIntOrNull())
            val fixedAnilistId = anilistPage?.id ?: -1
            val fixedMalId = idMal?.toIntOrNull() ?: anilistPage?.idMal ?: -1

            val localRecommendations = anilistPage?.recommendations?.nodes?.map {
                AniListApi.Recommendation(
                    it.mediaRecommendation.title.english ?: it.mediaRecommendation.title.romaji ?: "",
                    it.mediaRecommendation.idMal ?: -1,
                    it.mediaRecommendation.coverImage.large ?: it.mediaRecommendation.coverImage.medium,
                    it.mediaRecommendation.averageScore
                )
            } ?: listOf()

            val relations = anilistPage?.relations?.edges?.filter { it.node.format?.startsWith("TV") == true }?.map {
                val node = it.node
                AniListApi.Recommendation(
                    node.title.english ?: node.title.romaji ?: "",
                    node.idMal ?: -1,
                    node.coverImage.large ?: node.coverImage.medium,
                    node.averageScore
                )
            } ?: listOf()

            related.postValue(relations)
            recommendations.postValue(localRecommendations)
            setAnilistId(fixedAnilistId)
            setMalId(fixedMalId)
            updateSyncData(overrideMalId ?: fixedMalId, overrideAnilistId ?: fixedAnilistId)
            allowSyncDataUpdates = true
        }

        loadOther(data.anime.slug)
    }

    fun loadData(context: Context, slug: String, isMal: Boolean) {
        thread {
            if (isMal) {
                val data = getAnimePageNewMal(slug)?.data
                println("DATA $data")
                _data.postValue(data)
                context.assignData(data)
            } else {
                val data = getAnimePageNew(slug)?.data
                _data.postValue(data)
                context.assignData(data)
            }
        }
    }

    fun swapData() {
        if (hasLoadedOther.value != true) return
        if (isCurrentlySubbed) {
            _data.postValue(dataDubbed.value)
        } else {
            _data.postValue(dataSubbed.value)
        }
        isCurrentlySubbed = !isCurrentlySubbed
    }


//    val visibleEpisodeProgress = MutableLiveData<Int?>()
//    val episodes = MutableLiveData<Int?>()
//    val slug = MutableLiveData<String?>()

    /** For automatic selection */

    private val _currentMalId = MutableLiveData<Int?>()
    val currentMalId: LiveData<Int?> = _currentMalId

    private val _currentAniListId = MutableLiveData<Int?>()
    val currentAniListId: LiveData<Int?> = _currentAniListId

    /** For manual selection */

    private val _overrideMalId = MutableLiveData<Int?>()
    val overrideMalId: LiveData<Int?> = _overrideMalId

    private val _overrideAniListId = MutableLiveData<Int?>()
    val overrideAniListId: LiveData<Int?> = _overrideAniListId

    /** Overridden + current with overridden as priority */

    private val _totalAniListId = MutableLiveData<Int?>()
    val totalAniListId: LiveData<Int?> = _totalAniListId

    private val _totalMalId = MutableLiveData<Int?>()
    val totalMalId: LiveData<Int?> = _totalMalId

    /** ------------------------------------------------- */

    private fun syncData(
        /** Because viewModel values aren't instant */
        overrideMalId: Int? = null,
        overrideAnilistId: Int? = null
    ) {
        if (allowSyncDataUpdates) {
            thread {
                guaranteedContext(null).updateSyncData(overrideMalId, overrideAnilistId)
            }
        }
    }


    fun setAnilistId(id: Int) {
        // For instant update, postValue is slow and threaded
        _currentAniListId.postValue(id)
        _totalAniListId.postValue(overrideAniListId.value ?: id)
        syncData(overrideAnilistId = overrideAniListId.value ?: id)
    }

    fun setAnilistIdOverride(id: Int?) {
        _overrideAniListId.postValue(id)
        _totalAniListId.postValue(id ?: currentAniListId.value)
        syncData(overrideAnilistId = id ?: currentAniListId.value)
    }

    fun setMalId(id: Int) {
        _currentMalId.postValue(id)
        _totalMalId.postValue(overrideMalId.value ?: id)
        syncData(overrideMalId = overrideMalId.value ?: id)
    }

    fun setMalIdOverride(id: Int?) {
        _overrideMalId.postValue(id)
        _totalMalId.postValue(id ?: currentMalId.value)
        syncData(overrideMalId = id ?: currentMalId.value)
    }

    fun getAnilistId(): Int? {
        return overrideAniListId.value ?: currentAniListId.value
    }

    fun getMalId(): Int? {
        return overrideMalId.value ?: currentMalId.value
    }
}