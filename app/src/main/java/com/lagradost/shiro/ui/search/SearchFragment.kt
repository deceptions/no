package com.lagradost.shiro.ui.search
import com.lagradost.shiro.utils.fv

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
import android.widget.Toast
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
import com.lagradost.shiro.utils.AppUtils.loadPage
import com.lagradost.shiro.utils.AppUtils.getCurrentActivity
import com.lagradost.shiro.utils.AppUtils.guaranteedContext
import com.lagradost.shiro.utils.AppUtils.observe
import com.lagradost.shiro.utils.AppUtils.settingsManager
import com.lagradost.shiro.utils.NekomoApi
import com.lagradost.shiro.utils.NekomoApi.Companion.getSearchMethods
import com.lagradost.shiro.utils.SearchHistory
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
        // The search bar must not grab focus on entry: the keyboard opens only when the user taps it
        view.isFocusableInTouchMode = true
        view.requestFocus() // keeps the keyboard closed until the bar is tapped
        view.post {
            (activity?.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                ?.hideSoftInputFromWindow(view.windowToken, 0)
        }
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
            fv<com.lagradost.shiro.ui.AutofitRecyclerView>(R.id.cardSpace)?.spanCount = spanCountLandscape
        } else {
            fv<com.lagradost.shiro.ui.AutofitRecyclerView>(R.id.cardSpace)?.spanCount = spanCountPortrait
        }

        val topParams: LinearLayout.LayoutParams = LinearLayout.LayoutParams(
            LinearLayoutCompat.LayoutParams.MATCH_PARENT, // view width
            MainActivity.statusHeight // view height
        )
        fv<android.view.View>(R.id.top_padding).layoutParams = topParams

        fv<android.widget.LinearLayout>(R.id.search_results_layout)?.background = ColorDrawable(Cyanea.instance.backgroundColor)
        fv<androidx.appcompat.widget.SearchView>(R.id.main_search)?.background = ColorDrawable(Cyanea.instance.backgroundColorDark)

        fv<androidx.core.widget.ContentLoadingProgressBar>(R.id.progress_bar)?.visibility = GONE
        val adapter: RecyclerView.Adapter<RecyclerView.ViewHolder> =
            ResAdapter(
                ArrayList(),
                fv<com.lagradost.shiro.ui.AutofitRecyclerView>(R.id.cardSpace),
                false
            )
        fv<com.lagradost.shiro.ui.AutofitRecyclerView>(R.id.cardSpace)?.adapter = adapter
        fv<com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton>(R.id.search_fab_button).backgroundTintList = ColorStateList.valueOf(
            Cyanea.instance.primaryDark
        )

        var isGenresOpen = false
        fv<com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton>(R.id.search_fab_button).setOnClickListener {
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
                    bottomSheetDialog.fv<androidx.cardview.widget.CardView>(R.id.genres_top_bar).backgroundTintList = ColorStateList.valueOf(
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
                (fv<com.lagradost.shiro.ui.AutofitRecyclerView>(R.id.cardSpace)?.adapter as ResAdapter).cardList.clear()
                fv<androidx.core.widget.ContentLoadingProgressBar>(R.id.progress_bar).visibility = View.VISIBLE

                thread {
                    val data = NekomoApi.searchNew("", it, hideChinese)
                    activity?.runOnUiThread {
                        if (data == null) {
                            //Toast.makeText(activity, "Server error", Toast.LENGTH_LONG).show()
                            fv<androidx.core.widget.ContentLoadingProgressBar>(R.id.progress_bar)?.visibility = GONE
                        } else {
                            val filteredData =
                                filterCardList(data.map {
                                    NekomoApi.CommonAnimePageData(
                                        it.title,
                                        it.poster,
                                        it.slug,
                                        it.title_english
                                    )
                                })
                            fv<androidx.core.widget.ContentLoadingProgressBar>(R.id.progress_bar)?.visibility =
                                GONE // GONE for remove space, INVISIBLE for just alpha = 0
                            (fv<com.lagradost.shiro.ui.AutofitRecyclerView>(R.id.cardSpace)?.adapter as ResAdapter?)?.cardList =
                                filteredData as ArrayList<NekomoApi.CommonAnimePage>
                            (fv<com.lagradost.shiro.ui.AutofitRecyclerView>(R.id.cardSpace)?.adapter as ResAdapter?)?.notifyDataSetChanged()
                        }
                    }
                }
            }
        }

        fv<androidx.appcompat.widget.SearchView>(R.id.main_search).setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                if (query == null) return false
                context?.let { SearchHistory.add(it, query) }
                refreshHistory()
                fv<androidx.core.widget.ContentLoadingProgressBar>(R.id.progress_bar).visibility = View.VISIBLE
                (fv<com.lagradost.shiro.ui.AutofitRecyclerView>(R.id.cardSpace)?.adapter as ResAdapter).cardList.clear()
                thread {
                    val data = NekomoApi.searchNew(
                        query,
                        genres = (searchViewModel?.selectedGenres?.value ?: listOf()),
                        hideChinese
                    )
                    activity?.runOnUiThread {
                        // A newer search has started: ignore this older answer
                        if (fv<androidx.appcompat.widget.SearchView>(R.id.main_search)?.query?.toString()?.trim() != query.trim()) return@runOnUiThread
                        if (data == null) {
                            //Toast.makeText(activity, "Server error", Toast.LENGTH_LONG).show()
                            fv<androidx.core.widget.ContentLoadingProgressBar>(R.id.progress_bar)?.visibility = View.GONE
                        } else {
                            val filteredData =
                                filterCardList(data.map {
                                    NekomoApi.CommonAnimePageData(
                                        it.title,
                                        it.poster,
                                        it.slug,
                                        it.title_english
                                    )
                                })
                            fv<androidx.core.widget.ContentLoadingProgressBar>(R.id.progress_bar)?.visibility =
                                View.GONE // GONE for remove space, INVISIBLE for just alpha = 0
                            (fv<com.lagradost.shiro.ui.AutofitRecyclerView>(R.id.cardSpace)?.adapter as ResAdapter?)?.cardList =
                                filteredData as ArrayList<NekomoApi.CommonAnimePage>
                            (fv<com.lagradost.shiro.ui.AutofitRecyclerView>(R.id.cardSpace)?.adapter as ResAdapter?)?.notifyDataSetChanged()
                        }
                    }
                }
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                if (newText == null) return false
                (fv<com.lagradost.shiro.ui.AutofitRecyclerView>(R.id.cardSpace)?.adapter as ResAdapter).cardList.clear()
                searchViewModel?.searchQuery?.postValue(newText)
                fv<View>(R.id.search_history_panel)?.visibility = if (newText.isEmpty()) View.VISIBLE else GONE
                historyHandler.removeCallbacksAndMessages(null)
                if (newText.length >= 2) historyHandler.postDelayed({
                    context?.let { SearchHistory.add(it, newText) }
                }, 1800)
                if (newText != "") {
                    fv<androidx.core.widget.ContentLoadingProgressBar>(R.id.progress_bar).visibility = View.VISIBLE
                    thread {
                        // Wait for the user to pause typing so each keystroke does not hit the APIs
                        Thread.sleep(450)
                        if (searchViewModel?.searchQuery?.value != newText) return@thread
                        val data =
                            NekomoApi.searchNew(
                                newText,
                                genres = (searchViewModel?.selectedGenres?.value ?: listOf()),
                                hideChinese
                            )
                        activity?.runOnUiThread {
                            // A newer search has started: ignore this older answer
                            if (fv<androidx.appcompat.widget.SearchView>(R.id.main_search)?.query?.toString() != newText) return@runOnUiThread
                            // Nullable since takes time to get data
                            if (data == null) {
                                //Toast.makeText(activity, "Server error", Toast.LENGTH_LONG).show()
                                fv<androidx.core.widget.ContentLoadingProgressBar>(R.id.progress_bar)?.visibility = GONE
                            } else {
                                fv<androidx.core.widget.ContentLoadingProgressBar>(R.id.progress_bar)?.visibility =
                                    GONE // GONE for remove space, INVISIBLE for just alpha = 0
                                val filteredData =
                                    filterCardList(data.map {
                                        NekomoApi.CommonAnimePageData(
                                            it.title,
                                            it.poster,
                                            it.slug,
                                            it.title_english
                                        )
                                    })
                                filteredData?.let {
                                    (fv<com.lagradost.shiro.ui.AutofitRecyclerView>(R.id.cardSpace)?.adapter as ResAdapter?)?.cardList = ArrayList(it)
                                    (fv<com.lagradost.shiro.ui.AutofitRecyclerView>(R.id.cardSpace)?.adapter as ResAdapter?)?.notifyDataSetChanged()
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


        fv<androidx.appcompat.widget.SearchView>(R.id.main_search).setOnQueryTextFocusChangeListener { view, b ->
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

            TransitionManager.beginDelayedTransition(fv<androidx.appcompat.widget.SearchView>(R.id.main_search), transition)

            val margins = if (b) 0 else 6.toPx
            searchParams.height -= margins * 2 // TO KEEP
            searchParams.setMargins(margins)
            fv<androidx.appcompat.widget.SearchView>(R.id.main_search)?.layoutParams = searchParams
        }
//        if (!isInResults && !isInPlayer) {
        fv<androidx.appcompat.widget.SearchView>(R.id.main_search)?.onActionViewExpanded()
        // onActionViewExpanded() focuses the bar and pops the keyboard: give focus back so it opens only on tap
        fv<androidx.appcompat.widget.SearchView>(R.id.main_search)?.let { sv ->
            sv.post {
                sv.clearFocus()
                (activity?.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)?.hideSoftInputFromWindow(sv.windowToken, 0)
            }
        }
        refreshHistory()
        searchViewModel?.searchQuery?.value?.let {
            fv<androidx.appcompat.widget.SearchView>(R.id.main_search)?.setQuery(it, false)
        }
//        }

        //fv<androidx.appcompat.widget.SearchView>(R.id.main_search).findViewById<EditText>(R.id.search_src_text).requestFocus()
    }

    private val historyHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Rebuilds the "Recent searches" chips; the panel only shows while the search box is empty. */
    private fun refreshHistory() {
        val ctx = context ?: return
        val panel = fv<View>(R.id.search_history_panel) ?: return
        val chips = fv<com.google.android.material.chip.ChipGroup>(R.id.search_history_chips) ?: return
        val items = SearchHistory.all(ctx)
        chips.removeAllViews()
        items.forEach { q ->
            chips.addView(com.google.android.material.chip.Chip(ctx).apply {
                text = q
                isCloseIconVisible = true
                setOnClickListener { fv<SearchView>(R.id.main_search)?.setQuery(q, true) }
                setOnCloseIconClickListener { SearchHistory.remove(ctx, q); refreshHistory() }
            })
        }
        fv<View>(R.id.search_history_clear)?.setOnClickListener { SearchHistory.clear(ctx); refreshHistory() }
        fv<View>(R.id.search_random)?.setOnClickListener {
            Toast.makeText(ctx, "Picking a random anime…", Toast.LENGTH_SHORT).show()
            thread {
                val pick = try {
                    com.lagradost.shiro.utils.NekomoApi.Companion.getRandom()?.data
                } catch (t: Throwable) {
                    null
                }
                activity?.runOnUiThread {
                    if (pick != null) {
                        activity?.loadPage(pick.slug, pick.title)
                    } else {
                        Toast.makeText(ctx, "Could not load a random anime. Try again.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        val empty = fv<SearchView>(R.id.main_search)?.query?.isEmpty() ?: true
        // Panel stays up on an empty query so the Random button is always reachable;
        // only the history label and Clear hide when there is nothing to show.
        panel.visibility = if (empty) View.VISIBLE else GONE
        fv<View>(R.id.search_history_title)?.visibility = if (items.isNotEmpty()) View.VISIBLE else GONE
        fv<View>(R.id.search_history_clear)?.visibility = if (items.isNotEmpty()) View.VISIBLE else GONE
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
            fv<com.lagradost.shiro.ui.AutofitRecyclerView>(R.id.cardSpace)?.spanCount = spanCountLandscape
            //Toast.makeText(activity, "landscape", Toast.LENGTH_SHORT).show();
        } else if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
            fv<com.lagradost.shiro.ui.AutofitRecyclerView>(R.id.cardSpace)?.spanCount = spanCountPortrait
            //Toast.makeText(activity, "portrait", Toast.LENGTH_SHORT).show();
        }
    }

}
