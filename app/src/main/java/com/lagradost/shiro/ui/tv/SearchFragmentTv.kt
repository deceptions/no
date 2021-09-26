package com.lagradost.shiro.ui.tv

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.leanback.widget.SearchBar
import androidx.leanback.widget.SpeechOrbView
import com.lagradost.shiro.R
import com.lagradost.shiro.ui.home.CardAdapter
import com.lagradost.shiro.ui.tv.TvActivity.Companion.isInSearch
import com.lagradost.shiro.utils.AppUtils.displayCardData
import com.lagradost.shiro.utils.AppUtils.filterCardList
import com.lagradost.shiro.utils.Coroutines.main
import com.lagradost.shiro.utils.ShiroApi
import com.lagradost.shiro.utils.ShiroApi.Companion.searchNew
import kotlinx.android.synthetic.main.fragment_search_tv.*
import kotlin.concurrent.thread

class SearchFragmentTv : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        isInSearch = true
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_search_tv, container, false)
    }

    override fun onDestroy() {
        isInSearch = false
        super.onDestroy()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val speechOrbView: SpeechOrbView = view.findViewById(R.id.lb_search_bar_speech_orb)
        speechOrbView.visibility = GONE

        //val snapHelper = PagerSnapHelper()
        //snapHelper.attachToRecyclerView(scrollView)

        val adapter = CardAdapter(
            requireActivity(),
            ArrayList(),
            false
        )

        search_bar.setSearchBarListener(object : SearchBar.SearchBarListener {
            override fun onSearchQueryChange(query: String?) {
                if (query == null) return
                thread {
                    val data = searchNew(query)
                    data?.let { data ->
                        val filteredData =
                            filterCardList(data.map {
                                ShiroApi.CommonAnimePageData(
                                    it.title,
                                    it.poster,
                                    it.slug,
                                    it.title_english
                                )
                            })
                        main {
                            activity?.displayCardData(filteredData, search_recycler, expand_text, adapter = adapter)
                        }
                    }
                }
            }

            override fun onSearchQuerySubmit(query: String?) {
                if (query == null) return
                thread {
                    val data = searchNew(query)
                    data?.let { data ->
                        val filteredData =
                            filterCardList(data.map {
                                ShiroApi.CommonAnimePageData(
                                    it.title,
                                    it.poster,
                                    it.slug,
                                    it.title_english
                                )
                            })
                        main {
                            activity?.displayCardData(filteredData, search_recycler, expand_text, adapter = adapter)
                        }
                    }
                }
            }

            override fun onKeyboardDismiss(query: String) {

            }
        })
    }

    companion object {
        fun newInstance() =
            SearchFragmentTv().apply {
                arguments = Bundle().apply {

                }
            }
    }
}