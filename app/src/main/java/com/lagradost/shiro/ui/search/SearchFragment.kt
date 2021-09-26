package com.lagradost.shiro.ui.search

import android.annotation.SuppressLint
import android.content.Context
import android.content.Context.INPUT_METHOD_SERVICE
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.transition.ChangeBounds
import android.transition.Transition
import android.transition.TransitionManager
import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.setMargins
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.jaredrummler.cyanea.Cyanea
import com.lagradost.shiro.R
import com.lagradost.shiro.ui.MainActivity
import com.lagradost.shiro.ui.toPx
import com.lagradost.shiro.utils.AppUtils.changeStatusBarState
import com.lagradost.shiro.utils.AppUtils.filterCardList
import com.lagradost.shiro.utils.AppUtils.getCurrentActivity
import com.lagradost.shiro.utils.AppUtils.guaranteedContext
import com.lagradost.shiro.utils.AppUtils.observe
import com.lagradost.shiro.utils.AppUtils.settingsManager
import com.lagradost.shiro.utils.ShiroApi
import com.lagradost.shiro.utils.ShiroApi.Companion.getSearchMethods
import kotlinx.android.synthetic.main.fragment_search.*
import kotlinx.android.synthetic.main.genres_search.*
import kotlin.concurrent.thread

class SearchFragment : Fragment() {
    private var searchViewModel: SearchViewModel? = null
    private val searchSettingsManager =
        settingsManager ?: PreferenceManager.getDefaultSharedPreferences(guaranteedContext(context))!!

    private val compactView = searchSettingsManager.getBoolean("compact_search_enabled", true)
    private val spanCountLandscape = if (compactView) 2 else 6
    private val spanCountPortrait = if (compactView) 1 else 3
    private val hideChinese = searchSettingsManager.getBoolean("hide_chinese", false)
    private val topSearch = searchSettingsManager.getBoolean("top_search_bar", false)
    private val statusBarHidden = searchSettingsManager.getBoolean("statusbar_hidden", true)


    override fun onResume() {
//        activity?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        super.onResume()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
//        if (context?.getKey(HAS_DISMISSED_SEARCH_INFO, false) == false) {
//            val builder: AlertDialog.Builder =
//                AlertDialog.Builder(getCurrentActivity()!!, R.style.AlertDialogCustom)
//            builder.setPositiveButton("OK") { dialog, _ ->
//                dialog.dismiss()
//                context?.setKey(HAS_DISMISSED_SEARCH_INFO, true)
//            }
//            builder.setMessage("Press the return/search button on your keyboard to search for more than 5 titles.")
//                .setTitle("Search info")
//            val dialog = builder.create()
//            dialog.setCanceledOnTouchOutside(false)
//            dialog.setCancelable(false)
//            dialog.show()
//        }

        /*if (!isInResults && this.isVisible) {
            activity?.window?.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
            )
        }*/
//        val orientation = resources.configuration.orientation
        if (settingsManager!!.getBoolean("force_landscape",false)) {
            cardSpace?.spanCount = spanCountLandscape
        } else {
            cardSpace?.spanCount = spanCountPortrait
        }

        val topParams: LinearLayout.LayoutParams = LinearLayout.LayoutParams(
            LinearLayoutCompat.LayoutParams.MATCH_PARENT, // view width
            MainActivity.statusHeight // view height
        )
        top_padding.layoutParams = topParams

        search_results_layout?.background = ColorDrawable(Cyanea.instance.backgroundColor)
        main_search?.background = ColorDrawable(Cyanea.instance.backgroundColorDark)

        progress_bar?.visibility = GONE
        val adapter: RecyclerView.Adapter<RecyclerView.ViewHolder> =
            ResAdapter(
                ArrayList(),
                cardSpace,
                false
            )
        cardSpace?.adapter = adapter
        search_fab_button.backgroundTintList = ColorStateList.valueOf(
            Cyanea.instance.primaryDark
        )

        var isGenresOpen = false
        search_fab_button.setOnClickListener {
            if (isGenresOpen) return@setOnClickListener
            activity?.let { activity ->
                if (searchViewModel!!.searchOptions.value == null) {
                    thread {
                        searchViewModel!!.searchOptions.postValue(getSearchMethods())
                    }
                }
                isGenresOpen = true

                val tags = searchViewModel!!.searchOptions.value?.sortedBy { it }
                val bottomSheetDialog = BottomSheetDialog(activity, R.style.AppBottomSheetDialogTheme)
                bottomSheetDialog.setContentView(R.layout.genres_search)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    bottomSheetDialog.genres_top_bar.backgroundTintList = ColorStateList.valueOf(
                        Cyanea.instance.primaryDark
                    )
                }

                val filterButton = bottomSheetDialog.findViewById<MaterialButton>(R.id.filter_button)!!
                val searchTags = bottomSheetDialog.findViewById<MyFlowLayout>(R.id.search_tags)!!

                tags?.forEachIndexed { index, tag ->
                    val viewBtt = layoutInflater.inflate(R.layout.genre_tag, null)
                    val btt = viewBtt.findViewById<MaterialButton>(R.id.result_tag_card)
                    btt?.text = tag
                    activity.changeTagState(btt, tag)

                    btt.setOnClickListener {
                        activity.changeTagState(btt, tag, true)
                    }

                    searchTags.addView(viewBtt, index)
                }

                filterButton.setOnClickListener {
                    searchViewModel!!.selectedGenres.postValue(listOf())
                    bottomSheetDialog.dismiss()
                }
                bottomSheetDialog.setOnDismissListener {
                    isGenresOpen = false
                }
                bottomSheetDialog.show()
                //  MainActivity.showNavbar()
            }
        }

        observe(searchViewModel!!.selectedGenres) {
            if (!it.isNullOrEmpty()) {
                (cardSpace?.adapter as ResAdapter).cardList.clear()
                progress_bar.visibility = View.VISIBLE

                thread {
                    val data = ShiroApi.searchNew("", it, hideChinese)
                    activity?.runOnUiThread {
                        if (data == null) {
                            //Toast.makeText(activity, "Server error", Toast.LENGTH_LONG).show()
                            progress_bar?.visibility = GONE
                        } else {
                            val filteredData =
                                filterCardList(data.map {
                                    ShiroApi.CommonAnimePageData(
                                        it.title,
                                        it.poster,
                                        it.slug,
                                        it.title_english
                                    )
                                })
                            progress_bar?.visibility =
                                GONE // GONE for remove space, INVISIBLE for just alpha = 0
                            (cardSpace?.adapter as ResAdapter?)?.cardList =
                                filteredData as ArrayList<ShiroApi.CommonAnimePage>
                            (cardSpace?.adapter as ResAdapter?)?.notifyDataSetChanged()
                        }
                    }
                }
            }
        }

        main_search.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                if (query == null) return false
                progress_bar.visibility = View.VISIBLE
                (cardSpace?.adapter as ResAdapter).cardList.clear()
                thread {
                    val data = ShiroApi.searchNew(
                        query,
                        genres = (searchViewModel?.selectedGenres?.value ?: listOf()),
                        hideChinese
                    )
                    activity?.runOnUiThread {
                        if (data == null) {
                            //Toast.makeText(activity, "Server error", Toast.LENGTH_LONG).show()
                            progress_bar?.visibility = View.GONE
                        } else {
                            val filteredData =
                                filterCardList(data.map {
                                    ShiroApi.CommonAnimePageData(
                                        it.title,
                                        it.poster,
                                        it.slug,
                                        it.title_english
                                    )
                                })
                            progress_bar?.visibility =
                                View.GONE // GONE for remove space, INVISIBLE for just alpha = 0
                            (cardSpace?.adapter as ResAdapter?)?.cardList =
                                filteredData as ArrayList<ShiroApi.CommonAnimePage>
                            (cardSpace?.adapter as ResAdapter?)?.notifyDataSetChanged()
                        }
                    }
                }
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                if (newText == null) return false
                (cardSpace?.adapter as ResAdapter).cardList.clear()
                searchViewModel?.searchQuery?.postValue(newText)
                if (newText != "") {
                    progress_bar.visibility = View.VISIBLE
                    thread {
                        val data =
                            ShiroApi.searchNew(
                                newText,
                                genres = (searchViewModel?.selectedGenres?.value ?: listOf()),
                                hideChinese
                            )
                        activity?.runOnUiThread {
                            // Nullable since takes time to get data
                            if (data == null) {
                                //Toast.makeText(activity, "Server error", Toast.LENGTH_LONG).show()
                                progress_bar?.visibility = GONE
                            } else {
                                progress_bar?.visibility =
                                    GONE // GONE for remove space, INVISIBLE for just alpha = 0
                                val filteredData =
                                    filterCardList(data.map {
                                        ShiroApi.CommonAnimePageData(
                                            it.title,
                                            it.poster,
                                            it.slug,
                                            it.title_english
                                        )
                                    })
                                filteredData?.let {
                                    (cardSpace?.adapter as ResAdapter?)?.cardList = ArrayList(it)
                                    (cardSpace?.adapter as ResAdapter?)?.notifyDataSetChanged()
                                }
                            }
                        }
                    }
                }
                return true
            }
        })
//        activity?.let { AndroidBug5497Workaround.assistActivity(it) }
//        activity?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
//        activity?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)


        main_search.setOnQueryTextFocusChangeListener { view, b ->
            val searchParams: LinearLayout.LayoutParams = LinearLayout.LayoutParams(
                LinearLayoutCompat.LayoutParams.MATCH_PARENT, // view width
                60.toPx // view height
            )

            /** Because resize-mode is always pan when in fullscreen mode!
             *  This heavily fucks up bottom search
             *  See https://stackoverflow.com/questions/7417123/android-how-to-adjust-layout-in-full-screen-mode-when-softkeyboard-is-visible
             */
            if (!topSearch && statusBarHidden) {
                activity?.changeStatusBarState(!b)
            }

            if (b) {
                // https://stackoverflow.com/questions/12022715/unable-to-show-keyboard-automatically-in-the-searchview
                view.postDelayed({
                    run {
//                        if (!isInResults && !isInPlayer) {
                        val imm: InputMethodManager? =
                            activity?.getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager?
                        imm?.showSoftInput(view.findFocus(), 0)
                        activity?.findViewById<View>(R.id.search_mag_icon)?.visibility = GONE

//                        }
                    }
                }, 200)
            }

            val transition: Transition = ChangeBounds()
            transition.duration = 100 // DURATION OF ANIMATION IN MS

            TransitionManager.beginDelayedTransition(main_search, transition)

            val margins = if (b) 0 else 6.toPx
            searchParams.height -= margins * 2 // TO KEEP
            searchParams.setMargins(margins)
            main_search?.layoutParams = searchParams
        }
//        if (!isInResults && !isInPlayer) {
        main_search?.onActionViewExpanded()
        searchViewModel?.searchQuery?.value?.let {
            main_search?.setQuery(it, false)
        }
//        }

        //main_search.findViewById<EditText>(R.id.search_src_text).requestFocus()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        searchViewModel =
            searchViewModel ?: ViewModelProvider(getCurrentActivity()!!).get(SearchViewModel::class.java)

        if (searchViewModel!!.searchOptions.value == null) {
            thread {
                searchViewModel!!.searchOptions.postValue(getSearchMethods())
            }
        }
        val layout = if (topSearch) R.layout.fragment_search else R.layout.fragment_search_bottom
        return inflater.inflate(layout, container, false)
    }


    private fun Context.changeTagState(view: MaterialButton, tag: String, changed: Boolean = false) {
        val contains = (searchViewModel!!.selectedGenres.value ?: listOf()).contains(tag) == changed

        activity?.let {
            if (!contains) {
                if (changed) {
                    // Same as .add(tag)
                    val newGenres =
                        listOf(searchViewModel!!.selectedGenres.value ?: listOf(), listOf(tag)).flatten()
                    searchViewModel!!.selectedGenres.postValue(newGenres)
                }
                view.backgroundTintList = ColorStateList.valueOf(
                    Cyanea.instance.primaryLight
                )
                //view.setTextColor(ContextCompat.getColor(requireContext(), R.color.white))
            } else if (changed) {
                val newGenres = searchViewModel!!.selectedGenres.value?.filter { genre -> genre != tag }
                searchViewModel!!.selectedGenres.postValue(newGenres)
                view.backgroundTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(this, R.color.transparent))
                /*view.setTextColor(
                    it.getColorFromAttr(R.attr.colorAccent)
                )*/

            }
        }
    }

    override fun onStop() {
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            cardSpace?.spanCount = spanCountLandscape
            //Toast.makeText(activity, "landscape", Toast.LENGTH_SHORT).show();
        } else if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
            cardSpace?.spanCount = spanCountPortrait
            //Toast.makeText(activity, "portrait", Toast.LENGTH_SHORT).show();
        }
    }

}
