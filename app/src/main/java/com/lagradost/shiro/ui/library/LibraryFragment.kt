package com.lagradost.shiro.ui.library
import com.lagradost.shiro.utils.fv

import ANILIST_SHOULD_UPDATE_LIST
import ANILIST_TOKEN_KEY
import DataStore.getKey
import DataStore.setKey
import LIBRARY_IS_MAL
import MAL_SHOULD_UPDATE_LIST
import MAL_TOKEN_KEY
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.FocusFinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AbsListView.CHOICE_MODE_SINGLE
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.AutoTransition
import androidx.transition.Transition
import androidx.transition.TransitionManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.tabs.TabLayoutMediator
import com.jaredrummler.cyanea.Cyanea
import com.lagradost.shiro.R
import com.lagradost.shiro.ui.MainActivity
import com.lagradost.shiro.ui.library.LibraryFragment.Companion.libraryViewModel
import com.lagradost.shiro.ui.library.LibraryFragment.Companion.onMenuCollapsed
import com.lagradost.shiro.ui.toPx
import com.lagradost.shiro.ui.tv.TvActivity.Companion.isInSearch
import com.lagradost.shiro.ui.tv.TvActivity.Companion.tvActivity
import com.lagradost.shiro.utils.*
import com.lagradost.shiro.utils.AniListApi.Companion.convertAnilistStringToStatus
import com.lagradost.shiro.utils.AniListApi.Companion.secondsToReadable
import com.lagradost.shiro.utils.AppUtils.getCurrentActivity
import com.lagradost.shiro.utils.AppUtils.getCurrentContext
import com.lagradost.shiro.utils.AppUtils.getTextColor
import com.lagradost.shiro.utils.AppUtils.guaranteedContext
import com.lagradost.shiro.utils.AppUtils.notNull
import com.lagradost.shiro.utils.AppUtils.reduceDragSensitivity
import com.lagradost.shiro.utils.AppUtils.settingsManager
import com.lagradost.shiro.utils.Coroutines.main
import com.lagradost.shiro.utils.MALApi.Companion.convertJapanTimeToTimeRemaining
import com.lagradost.shiro.utils.MALApi.Companion.convertToStatus
import com.lagradost.shiro.utils.mvvm.normalSafeApiCall
import com.lagradost.shiro.utils.mvvm.observe

class CustomSearchView(context: Context) : SearchView(context) {
    override fun onActionViewCollapsed() {
        onMenuCollapsed.invoke(true)
        super.onActionViewCollapsed()
    }
}

val tabs = listOf(
    Pair("Currently watching", 0),
    Pair("Plan to Watch", 1),
    Pair("On Hold", 2),
    Pair("Completed", 3),
    Pair("Dropped", 4),
    Pair("All Anime", 5),
)

class LibraryFragment : Fragment() {
    data class SortingMethod(val name: String, val id: Int)

    private val malSortingMethods = arrayOf(
        //SortingMethod("Default", DEFAULT_SORT),
        SortingMethod("Recently updated (New to Old)", LATEST_UPDATE),
        SortingMethod("Recently updated (Old to New)", LATEST_UPDATE_REVERSED),
        SortingMethod("Alphabetical (A-Z)", ALPHA_SORT),
        SortingMethod("Alphabetical (Z-A)", REVERSE_ALPHA_SORT),
        SortingMethod("Score (High to Low)", SCORE_SORT),
        SortingMethod("Score (Low to High)", SCORE_SORT_REVERSED),
        SortingMethod("Rank (High to Low)", RANK_SORT),
        SortingMethod("Rank (Low to High)", RANK_SORT_REVERSED),
    )

    private val anilistSortingMethods = arrayOf(
        //SortingMethod("Default", DEFAULT_SORT),
        SortingMethod("Recently updated (New to Old)", LATEST_UPDATE),
        SortingMethod("Recently updated (Old to New)", LATEST_UPDATE_REVERSED),
        SortingMethod("Alphabetical (A-Z)", ALPHA_SORT),
        SortingMethod("Alphabetical (Z-A)", REVERSE_ALPHA_SORT),
        SortingMethod("Score (High to Low)", SCORE_SORT),
        SortingMethod("Score (Low to High)", SCORE_SORT_REVERSED),
    )

    override fun onDestroy() {
        isInSearch = false
        super.onDestroy()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        libraryViewModel =
            libraryViewModel ?: ViewModelProvider(getCurrentActivity()!!).get(LibraryViewModel::class.java)
        val hasMAL = getCurrentActivity()!!.getKey<String>(MAL_TOKEN_KEY, MAL_ACCOUNT_ID, null) != null
        val hasAniList = getCurrentActivity()!!.getKey<String>(
            ANILIST_TOKEN_KEY,
            ANILIST_ACCOUNT_ID,
            null
        ) != null
        // TODO FIX
        isInSearch = true

        libraryViewModel?.isMal =
            (guaranteedContext(context).getKey(LIBRARY_IS_MAL, true) == true && hasMAL) || !hasAniList
        // Inflate the layout for this fragment
        val layout = if (tvActivity != null) R.layout.fragment_library_tv else R.layout.fragment_library
        return inflater.inflate(layout, container, false)
    }

    private fun getCurrentTabCorrected(): Int {
        return tabs[(fv<com.google.android.material.tabs.TabLayout>(R.id.result_tabs)?.selectedTabPosition ?: 0)].second
    }

    private fun sortCurrentListEventFunction(boolean: Boolean) {
        libraryViewModel?.displayList()
    }

    override fun onResume() {
        onMenuCollapsed += ::sortCurrentListEventFunction
        super.onResume()
    }

    override fun onStop() {
        onMenuCollapsed -= ::sortCurrentListEventFunction
        super.onStop()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        fv<com.google.android.material.tabs.TabLayout>(R.id.result_tabs)?.removeAllTabs()
        fv<com.google.android.material.tabs.TabLayout>(R.id.result_tabs)?.isFocusable = false

        /*tabs.forEach {
            fv<com.google.android.material.tabs.TabLayout>(R.id.result_tabs)?.addTab(fv<com.google.android.material.tabs.TabLayout>(R.id.result_tabs).newTab().setText(it))
        }*/
        fv<androidx.coordinatorlayout.widget.CoordinatorLayout>(R.id.fragment_list_root)?.setPadding(0, MainActivity.statusHeight, 0, 0)
        fv<android.widget.LinearLayout>(R.id.fragment_list_root_tv)?.setPadding(0, MainActivity.statusHeight, 0, 0)

        val hasAniList = guaranteedContext(context).getKey<String>(
            ANILIST_TOKEN_KEY,
            ANILIST_ACCOUNT_ID,
            null
        ) != null
        val hasMAL = guaranteedContext(context).getKey<String>(MAL_TOKEN_KEY, MAL_ACCOUNT_ID, null) != null
        fv<android.widget.LinearLayout>(R.id.login_overlay)?.background = ColorDrawable(Cyanea.instance.backgroundColor)
        fv<android.widget.LinearLayout>(R.id.login_overlay)?.isVisible = !hasAniList && !hasMAL
        fv<android.widget.Button>(R.id.local_library_open)?.setOnClickListener {
            context?.let { c -> startActivity(android.content.Intent(c, LocalLibraryActivity::class.java)) }
        }
        fv<com.google.android.material.appbar.MaterialToolbar>(R.id.library_toolbar)?.navigationIcon = if (hasAniList && hasMAL) ContextCompat.getDrawable(
            guaranteedContext(context),
            R.drawable.ic_baseline_swap_vert_24
        ) else null
        fv<com.google.android.material.appbar.MaterialToolbar>(R.id.library_toolbar)?.children?.forEach {
            if (it is ImageButton) {
                it.setOnClickListener {
                    val newIsMal = !(libraryViewModel?.isMal ?: true)
                    libraryViewModel?.isMal = newIsMal
                    context?.setKey(LIBRARY_IS_MAL, newIsMal)
                    libraryViewModel?.displayList()
                }
                return@forEach
            }
        }

        val searchView: CustomSearchView? =
            fv<com.google.android.material.appbar.MaterialToolbar>(R.id.library_toolbar)?.menu?.findItem(R.id.action_search)?.actionView as? CustomSearchView
        searchView?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                libraryViewModel?.sortCurrentList(getCurrentTabCorrected(), SEARCH, query)
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                libraryViewModel?.sortCurrentList(getCurrentTabCorrected(), SEARCH, newText)
                return true
            }
        })

        fun search() {
            fv<com.google.android.material.textfield.TextInputLayout>(R.id.search_library)?.editText?.text?.toString()?.let { text ->
                libraryViewModel?.sortCurrentList(getCurrentTabCorrected(), SEARCH, text)
            }
        }

        fv<com.google.android.material.textfield.TextInputLayout>(R.id.search_library)?.editText?.setOnEditorActionListener { textView, i, keyEvent ->
            if (i == EditorInfo.IME_ACTION_SEARCH ||
                i == EditorInfo.IME_ACTION_DONE
            ) {
                search()
                return@setOnEditorActionListener true
            }
            return@setOnEditorActionListener false
        }

        fun reload() {
            context?.setKey(MAL_SHOULD_UPDATE_LIST, true)
            context?.setKey(ANILIST_SHOULD_UPDATE_LIST, true)
            libraryViewModel?.requestMalList(context)
            libraryViewModel?.requestAnilistList(context)
            Toast.makeText(context, "Refreshing your list", Toast.LENGTH_SHORT).show()
        }

        fun sort() {
            val bottomSheetDialog = BottomSheetDialog(getCurrentActivity()!!, R.style.AppBottomSheetDialogTheme)
            bottomSheetDialog.setContentView(R.layout.bottom_sheet)
            bottomSheetDialog.fv<android.widget.TextView>(R.id.main_text)?.text = "Sort by"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                bottomSheetDialog.fv<androidx.cardview.widget.CardView>(R.id.bottom_sheet_top_bar).backgroundTintList =
                    ColorStateList.valueOf(Cyanea.instance.backgroundColorDark)
            }

            val res = bottomSheetDialog.findViewById<ListView>(R.id.sort_click)!!
            val arrayAdapter = ArrayAdapter<String>(
                requireContext(),
                R.layout.bottom_single_choice
            ) // checkmark_select_dialog
            res.choiceMode = CHOICE_MODE_SINGLE

            val sortingMethods =
                if (libraryViewModel?.isMal == true) malSortingMethods else anilistSortingMethods

            arrayAdapter.addAll(ArrayList(sortingMethods.map { t -> t.name }))
            res.adapter = arrayAdapter

            res.setItemChecked(
                sortingMethods.indexOfFirst { t ->
                    t.id == libraryViewModel?.sortMethods?.getOrNull(
                        fv<com.google.android.material.tabs.TabLayout>(R.id.result_tabs)?.selectedTabPosition ?: -1
                    ) ?: 0
                },
                true
            )
            res.setOnItemClickListener { _, _, position, _ ->
                val sel = sortingMethods[position].id
                libraryViewModel?.sortCurrentList(getCurrentTabCorrected(), sel)
                bottomSheetDialog.dismiss()
            }

            // Full expansion for TV
            bottomSheetDialog.setOnShowListener {
                normalSafeApiCall {
                    BottomSheetBehavior.from(bottomSheetDialog.fv<android.widget.LinearLayout>(R.id.bottom_sheet_root).parent as View).peekHeight =
                        bottomSheetDialog.fv<android.widget.LinearLayout>(R.id.bottom_sheet_root).height
                }
            }

            bottomSheetDialog.show()
        }


        fv<com.google.android.material.appbar.MaterialToolbar>(R.id.library_toolbar)?.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_reload -> {
                    reload()
                }

                R.id.action_sort -> {
                    sort()
                }
                else -> {
                }
            }
            return@setOnMenuItemClickListener true
        }

        val focusListener = View.OnFocusChangeListener { v, hasFocus ->
            val transition: Transition = AutoTransition()
            transition.duration = 2000 // DURATION OF ANIMATION IN MS
            fv<android.widget.RelativeLayout>(R.id.library_menu_bar)?.let {
                TransitionManager.beginDelayedTransition(it, transition)
            }
            val scale = if (hasFocus) 0.7f else 0.5f
            v?.scaleX = scale
            v?.scaleY = scale
        }

        fv<android.widget.ImageView>(R.id.reload_icon)?.onFocusChangeListener = focusListener
        fv<android.widget.ImageView>(R.id.reload_icon)?.setOnClickListener {
            reload()
        }
        fv<android.widget.ImageView>(R.id.sort_icon)?.onFocusChangeListener = focusListener
        fv<android.widget.ImageView>(R.id.sort_icon)?.setOnClickListener {
            sort()
        }
        fv<android.widget.ImageView>(R.id.search_icon)?.onFocusChangeListener = focusListener
        fv<android.widget.ImageView>(R.id.search_icon)?.setOnClickListener {
            search()
        }
        fv<android.widget.ImageView>(R.id.switch_icon)?.isVisible = hasAniList && hasMAL
        fv<android.widget.ImageView>(R.id.switch_icon)?.onFocusChangeListener = focusListener
        fv<android.widget.ImageView>(R.id.switch_icon)?.setOnClickListener {
            val newIsMal = !(libraryViewModel?.isMal ?: true)
            val client = if (newIsMal) "MAL" else "Anilist"
            Toast.makeText(getCurrentContext() ?: requireContext(), "Switched to $client", Toast.LENGTH_SHORT).show()
            libraryViewModel?.isMal = newIsMal
            context?.setKey(LIBRARY_IS_MAL, newIsMal)
            libraryViewModel?.displayList()
        }

        //fv<androidx.viewpager2.widget.ViewPager2>(R.id.viewpager)?.adapter = CustomFragmentPagerAdapter() // CustomPagerAdapter(getCurrentActivity()!!)
        fv<androidx.viewpager2.widget.ViewPager2>(R.id.viewpager)?.adapter = CustomPagerAdapter {
            // For android tv dpad support
            when (it) {
                View.FOCUS_RIGHT -> {
                    val location = IntArray(2)
                    getCurrentActivity()?.currentFocus?.getLocationOnScreen(location)

                    fv<android.widget.LinearLayout>(R.id.fragment_list_root_tv).notNull {
                        val index = fv<com.google.android.material.tabs.TabLayout>(R.id.result_tabs)?.selectedTabPosition?.plus(1) ?: 0
                        if (index == (fv<com.google.android.material.tabs.TabLayout>(R.id.result_tabs)?.tabCount ?: 0)) return@notNull

                        val x = 1
                        val y = location[1]
                        fv<android.widget.RelativeLayout>(R.id.library_menu_bar)?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS

//                        fv<androidx.viewpager2.widget.ViewPager2>(R.id.viewpager)?.currentItem = index
                        fv<androidx.viewpager2.widget.ViewPager2>(R.id.viewpager)?.setCurrentItem(index, false)

                        view.postDelayed({
                            run {
                                val focusFinder = FocusFinder.getInstance()

                                /**
                                 * 1. Try to go up from current y pos on the left side
                                 * 2. Try to go down from left
                                 * 3. Request the background
                                 * */
                                focusFinder.findNearestTouchable(
                                    fv<androidx.viewpager2.widget.ViewPager2>(R.id.viewpager),
                                    x,
                                    y,
                                    View.FOCUS_UP,
                                    IntArray(2)
                                )?.requestFocus() ?: focusFinder.findNearestTouchable(
                                    fv<androidx.viewpager2.widget.ViewPager2>(R.id.viewpager),
                                    1,
                                    0,
                                    View.FOCUS_DOWN,
                                    IntArray(2)
                                )?.requestFocus() ?: fv<com.lagradost.shiro.ui.AutofitRecyclerView>(R.id.library_card_space)?.requestFocus()
                                fv<android.widget.RelativeLayout>(R.id.library_menu_bar)?.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

                            }
                        }, 200)
                    }
                }
                View.FOCUS_LEFT -> {
                    fv<android.widget.LinearLayout>(R.id.fragment_list_root_tv).notNull {
                        val index = fv<com.google.android.material.tabs.TabLayout>(R.id.result_tabs)?.selectedTabPosition?.minus(1) ?: 0
                        if (index == -1) return@notNull

                        val location = IntArray(2)
                        getCurrentActivity()?.currentFocus?.getLocationOnScreen(location)

                        val x = it.width - 1
                        val y = location[1]

                        fv<android.widget.RelativeLayout>(R.id.library_menu_bar)?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS

//                        fv<androidx.viewpager2.widget.ViewPager2>(R.id.viewpager)?.currentItem = index
                        fv<androidx.viewpager2.widget.ViewPager2>(R.id.viewpager)?.setCurrentItem(index, false)
                        /**
                         * 1. Try to go up from current y pos on the right side
                         * 2. Try to go down from right
                         * 3. Try to go down from the left
                         * 4. Request the background
                         * */
                        view.postDelayed({
                            run {
                                val focusFinder = FocusFinder.getInstance()
                                focusFinder.findNearestTouchable(
                                    fv<androidx.viewpager2.widget.ViewPager2>(R.id.viewpager),
                                    x,
                                    y,
                                    View.FOCUS_UP,
                                    IntArray(2)
                                )?.requestFocus() ?: focusFinder.findNearestTouchable(
                                    fv<androidx.viewpager2.widget.ViewPager2>(R.id.viewpager),
                                    x,
                                    0,
                                    View.FOCUS_DOWN,
                                    IntArray(2)
                                )?.requestFocus() ?: focusFinder.findNearestTouchable(
                                    fv<androidx.viewpager2.widget.ViewPager2>(R.id.viewpager),
                                    1,
                                    0,
                                    View.FOCUS_DOWN,
                                    IntArray(2)
                                )?.requestFocus()
                                fv<com.lagradost.shiro.ui.AutofitRecyclerView>(R.id.library_card_space)?.requestFocus()
                                fv<android.widget.RelativeLayout>(R.id.library_menu_bar)?.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

                            }
                        }, 200)
                    }


                    //fv<com.google.android.material.tabs.TabLayout>(R.id.result_tabs)?.getTabAt(fv<com.google.android.material.tabs.TabLayout>(R.id.result_tabs)?.selectedTabPosition?.minus(1) ?: 0)?.select()
                }
            }
        }

        fv<androidx.viewpager2.widget.ViewPager2>(R.id.viewpager)?.reduceDragSensitivity()
        fv<androidx.viewpager2.widget.ViewPager2>(R.id.viewpager)?.adapter?.notifyDataSetChanged()

        //fv<com.google.android.material.tabs.TabLayout>(R.id.result_tabs)?.setupWithViewPager(fv<androidx.viewpager2.widget.ViewPager2>(R.id.viewpager))
        fv<com.google.android.material.tabs.TabLayout>(R.id.result_tabs)?.tabTextColors = ColorStateList.valueOf(getCurrentActivity()!!.getTextColor())
        fv<com.google.android.material.tabs.TabLayout>(R.id.result_tabs)?.setSelectedTabIndicatorColor(getCurrentActivity()!!.getTextColor())

        if (fv<com.google.android.material.tabs.TabLayout>(R.id.result_tabs) != null) {
            TabLayoutMediator(
                fv<com.google.android.material.tabs.TabLayout>(R.id.result_tabs),
                fv<androidx.viewpager2.widget.ViewPager2>(R.id.viewpager),
            ) { tab, position ->
                tab.view.isFocusable = false
                tab.text = tabs.getOrNull(position)?.first ?: ""
            }.attach()
        }

//            tabs.forEach {
//                fv<com.google.android.material.tabs.TabLayout>(R.id.result_tabs)?.addTab(fv<com.google.android.material.tabs.TabLayout>(R.id.result_tabs).newTab().setText(it.first))
//            }


        fv<com.google.android.material.appbar.MaterialToolbar>(R.id.library_toolbar)?.title = if (libraryViewModel?.isMal == true) "MAL" else "Anilist"
        observe(libraryViewModel!!.currentList)
        { list ->
            for (i in tabs.indices) {
                val size = list.getOrNull(tabs[i].second)?.size ?: 0
                main {
                    fv<com.google.android.material.tabs.TabLayout>(R.id.result_tabs)?.getTabAt(i)?.text = tabs[i].first + " ($size)"
                }
            }
            fv<androidx.viewpager2.widget.ViewPager2>(R.id.viewpager)?.adapter?.notifyDataSetChanged()
            fv<com.google.android.material.appbar.MaterialToolbar>(R.id.library_toolbar)?.title = if (libraryViewModel?.isMal == true) "MAL" else "Anilist"
        }
        /*fv<com.google.android.material.tabs.TabLayout>(R.id.result_tabs)?.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                val pos = tab?.position
                if (pos != null) {
                    libraryViewModel?.generateListByTab(pos)
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })*/
        libraryViewModel?.requestMalList(context)
        libraryViewModel?.requestAnilistList(context)
    }

    companion object {
        var libraryViewModel: LibraryViewModel? = null
        val onMenuCollapsed = Event<Boolean>()

        fun newInstance() =
            LibraryFragment().apply {
                /*arguments = Bundle().apply {
                    putString(ARG_PARAM1, param1)
                }*/
            }
    }
}

private val spanCountPortrait = settingsManager?.getInt("library_span_count", 1) ?: 1
private fun generateLibraryObject(list: List<Any>): List<LibraryObject> {
    if (list.firstOrNull() is MALApi.Companion.Data) {
        (list as? List<MALApi.Companion.Data>)?.let {
            return it.map { data ->
                LibraryObject(
                    data.node.title,
                    data.node.main_picture?.medium ?: "",
                    data.node.id.toString(),
                    null,
                    data.list_status?.score ?: 0,
                    data.list_status?.num_episodes_watched ?: 0,
                    data.node.num_episodes,
                    data.node.start_season?.season,
                    data.node.start_season?.year,
                    convertToStatus(data.list_status?.status ?: "").value,
                    data.node.broadcast?.day_of_the_week?.plus(" ")?.plus(data.node.broadcast.start_time)?.let {
                        convertJapanTimeToTimeRemaining(it, data.node.end_date)
                    }
                )
            }
        }
    } else if (list.firstOrNull() is AniListApi.Companion.Entries) {
        (list as? List<AniListApi.Companion.Entries>)?.let {
            return it.map {
                LibraryObject(
                    it.media.title.english ?: it.media.title.romaji ?: "",
                    it.media.coverImage.medium,
                    it.media.idMal.toString(),
                    it.media.id.toString(),
                    it.score,
                    it.progress,
                    it.media.episodes,
                    it.media.season,
                    if (it.media.seasonYear == 0) null else it.media.seasonYear,
                    convertAnilistStringToStatus(it.status ?: "").value,
                    it.media.nextAiringEpisode?.timeUntilAiring?.let { it -> secondsToReadable(it, "Now") }
                )
            }
        }
    }

    return listOf()
}


class CustomPagerAdapter(private val hitBorderCallback: (Int) -> Unit) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    override fun getItemCount(): Int {
        return tabs.size
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        parent.isFocusable = false
        parent.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

        return CardViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.mal_list, parent, false),
            parent.context,
            hitBorderCallback
        )
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is CardViewHolder -> {
                holder.bind(position)
            }
        }
    }

    class CardViewHolder
        (itemView: View, val context: Context, private val hitBorderCallback: (Int) -> Unit) :
        RecyclerView.ViewHolder(itemView) {

        val orientation = context.resources.configuration.orientation
        val spanCount = if (orientation == Configuration.ORIENTATION_LANDSCAPE || settingsManager?.getBoolean(
                "force_landscape",
                false
            ) == true
        ) spanCountPortrait * 2 else spanCountPortrait

        fun bind(position: Int) {
            itemView.fv<com.lagradost.shiro.ui.AutofitRecyclerView>(R.id.library_card_space)?.spanCount = spanCount
            itemView.fv<com.lagradost.shiro.ui.AutofitRecyclerView>(R.id.library_card_space)?.setBorderCallback(hitBorderCallback)

            if (tvActivity != null) {
                itemView.fv<com.lagradost.shiro.ui.AutofitRecyclerView>(R.id.library_card_space)?.setPadding(0, 5.toPx, 0, 200.toPx)
            }

            fun displayList(list: List<LibraryObject>) {
                if (itemView.fv<com.lagradost.shiro.ui.AutofitRecyclerView>(R.id.library_card_space)?.adapter == null) {
                    itemView.fv<com.lagradost.shiro.ui.AutofitRecyclerView>(R.id.library_card_space)?.adapter = LibraryCardAdapter(list)
                } else {
                    (itemView.fv<com.lagradost.shiro.ui.AutofitRecyclerView>(R.id.library_card_space)?.adapter as? LibraryCardAdapter)?.list = list
                    itemView.fv<com.lagradost.shiro.ui.AutofitRecyclerView>(R.id.library_card_space)?.adapter?.notifyDataSetChanged()
                }
            }

            libraryViewModel?.currentList?.value?.getOrNull(tabs[position].second)?.let {
                val list = generateLibraryObject(it)
                if ((itemView.fv<com.lagradost.shiro.ui.AutofitRecyclerView>(R.id.library_card_space)?.adapter as? LibraryCardAdapter)?.list != list) {
                    displayList(list)
                }
            }
        }
    }

}