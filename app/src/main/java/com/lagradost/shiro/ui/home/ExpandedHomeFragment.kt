package com.lagradost.shiro.ui.home

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.jaredrummler.cyanea.Cyanea
import com.lagradost.shiro.R
import com.lagradost.shiro.ui.MainActivity.Companion.statusHeight
import com.lagradost.shiro.ui.search.ResAdapter
import com.lagradost.shiro.utils.AppUtils.filterCardList
import com.lagradost.shiro.utils.AppUtils.settingsManager
import com.lagradost.shiro.utils.ShiroApi
import kotlinx.android.synthetic.main.fragment_expanded_home.*

const val CARD_LIST = "card_list"
const val TITLE = "title"

// private const val spanCountLandscape = 6
const val EXPANDED_HOME_FRAGMENT_TAG = "EXPANDED_HOME_FRAGMENT_TAG"

class ExpandedHomeFragment : Fragment() {
    private var cardList: List<ShiroApi.CommonAnimePageData>? = null
    private var title: String? = null
    private val mapper = JsonMapper.builder().addModule(KotlinModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let { bundle ->
            val cards = bundle.getString(CARD_LIST)
            cardList = cards?.let { mapper.readValue(it) }
            title = bundle.getString(TITLE)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_expanded_home, container, false)
    }

    companion object {
        var isInExpandedView: Boolean = false
        fun newInstance(cardList: String, title: String) =
            ExpandedHomeFragment().apply {
                arguments = Bundle().apply {
                    putString(CARD_LIST, cardList)
                    putString(TITLE, title)
                }
            }
    }

    override fun onDestroy() {
        isInExpandedView = false
        super.onDestroy()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        isInExpandedView = true
        title_go_back_holder.setOnClickListener {
            activity?.onBackPressed()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            expanded_home_title_holder.backgroundTintList = ColorStateList.valueOf(
                Cyanea.instance.backgroundColorDark
            )
        }

        val topParams: LinearLayout.LayoutParams = LinearLayout.LayoutParams(
            LinearLayoutCompat.LayoutParams.MATCH_PARENT, // view width
            statusHeight // view height
        )
        top_padding_expanded_home.layoutParams = topParams

        val spanCountPortrait = settingsManager?.getInt("expanded_span_count", 3) ?: 3

        val orientation = resources.configuration.orientation
        if (orientation == Configuration.ORIENTATION_LANDSCAPE || settingsManager!!.getBoolean(
                "force_landscape",
                false
            )
        ) {
            expanded_card_list_view?.spanCount = spanCountPortrait * 2
        } else {
            expanded_card_list_view?.spanCount = spanCountPortrait
        }
        title_text?.text = title
        val adapter: RecyclerView.Adapter<RecyclerView.ViewHolder> =
            ResAdapter(
                ArrayList(),
                expanded_card_list_view,
                false,
                forceDisableCompact = true
            )
        expanded_card_list_view?.adapter = adapter
        (expanded_card_list_view?.adapter as? ResAdapter)?.cardList =
            ArrayList(filterCardList(cardList) ?: listOf())
        (expanded_card_list_view?.adapter as? ResAdapter)?.notifyDataSetChanged()

    }
}