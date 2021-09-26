package com.lagradost.shiro.ui.library

import DataStore.getKey
import DataStore.removeKey
import DataStore.setKey
import LIBRARY_PAGE_MAL_OVERRIDE_SLUG
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.jaredrummler.cyanea.Cyanea
import com.lagradost.shiro.R
import com.lagradost.shiro.ui.GlideApp
import com.lagradost.shiro.ui.toPx
import com.lagradost.shiro.utils.AppUtils.getCurrentActivity
import com.lagradost.shiro.utils.AppUtils.loadPage
import com.lagradost.shiro.utils.AppUtils.settingsManager
import com.lagradost.shiro.utils.mvvm.normalSafeApiCall
import kotlinx.android.synthetic.main.fragment_library_edit_slug.*
import kotlinx.android.synthetic.main.list_card_compact.view.*
import java.util.*
import kotlin.math.ceil
import kotlin.math.sqrt


enum class LibraryStatusType(var value: Int) {
    Watching(0),
    Completed(1),
    Paused(2),
    Dropped(3),
    Planning(4),
    Rewatching(5),
    None(-1)
}

data class LibraryObject(
    val title: String,
    val poster: String,
    val id: String,
    val idAnilist: String?,
    val score: Int,
    val progress: Int,
    val episodes: Int,
    val season: String?,
    val year: Int?,
    val status: Int,
    val nextEpisode: String?,
)

class LibraryCardAdapter(var list: List<LibraryObject>) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return LibraryCardViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.list_card_compact, parent, false),
        )
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is LibraryCardViewHolder -> {
                // Because getOrNull expression seems to mis-trigger
                if (itemCount == 1 && list.isEmpty()){
                    holder.bind(null)
                } else {
                    holder.bind(list[position])
                }
            }
        }
    }

    override fun getItemCount(): Int {
        return maxOf(list.size, 1)
    }

    class LibraryCardViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val context: Context? = itemView.context
        fun bind(item: LibraryObject?) {
            /**
             * A hack to allow an invisible item to exist if there's no items
             * This is to solve d-pad focus when no views are present
             */
            if (item == null) {
                itemView.isFocusable = true
                itemView.alpha = 0f
//                itemView.visibility = INVISIBLE
//                itemView.layoutParams = itemView.layoutParams?.apply {
//                    width = 0
//                    height = 0
//                }
                return
            }
            context ?: return
            fun loadPage() {
                val slug: String? = context.getKey(
                    LIBRARY_PAGE_MAL_OVERRIDE_SLUG,
                    item.id,
                    null
                )

                if (slug != null) {
                    getCurrentActivity()?.loadPage(slug.lowercase().replace(" ", "-"), item.title, false)
                } else {
                    getCurrentActivity()?.loadPage(item.id, item.title, true)
                }
            }

            val coverHeight: Int = (settingsManager?.getInt("library_view_height", 80) ?: 80).toPx
            itemView.apply {
                layoutParams = LinearLayout.LayoutParams(
                    MATCH_PARENT,
                    coverHeight
                )
            }
            itemView.imageView.apply {
                layoutParams = FrameLayout.LayoutParams(
                    ceil(coverHeight / sqrt(2.0)).toInt(),
                    coverHeight
                )
            }
            val marginParams = FrameLayout.LayoutParams(
                LinearLayoutCompat.LayoutParams.MATCH_PARENT, // view width
                LinearLayoutCompat.LayoutParams.MATCH_PARENT, // view height
            )
            marginParams.setMargins(ceil(coverHeight / sqrt(2.0)).toInt(), 0, 0, 0)

            itemView.text_holder.layoutParams = marginParams
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                itemView.backgroundCard.backgroundTintList = ColorStateList.valueOf(
                    Cyanea.instance.backgroundColorDark
                )
            }

            itemView.backgroundCard.setOnClickListener {
                loadPage()
            }

            itemView.imageText?.text = item.title
            itemView.imageSubText?.visibility = VISIBLE

            val statusColor = when (item.status) {
                LibraryStatusType.Watching.value -> R.color.colorWatching
                LibraryStatusType.Completed.value -> R.color.colorCompleted
                LibraryStatusType.Paused.value -> R.color.colorOnHold
                LibraryStatusType.Dropped.value -> R.color.colorDropped
                LibraryStatusType.Planning.value -> R.color.colorPlanToWatch
                else -> R.color.colorWatching
            }

            itemView.episode_progress?.max = item.episodes
            itemView.episode_progress?.progress = item.progress
            /*episode_progress?.progressDrawable?.setColorFilter(
                ContextCompat.getColor(context, statusColor), android.graphics.PorterDuff.Mode.SRC_IN
            )*/
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                itemView.episode_progress?.progressTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(context, statusColor))
            }

            val scoreText = if (item.score != 0) "★ " + item.score else null
            val separator = if (scoreText != null && item.nextEpisode != null) " - " else ""

            val seasonText = (item.season?.let {
                it.lowercase().replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(
                        Locale.getDefault()
                    ) else it.toString()
                }
            } ?: "") + " " + (item.year ?: "")
            val episodeText =
                "${item.progress}/${if (item.episodes != 0) item.episodes else "???"}"

            itemView.imageTextSecond?.text = episodeText
            itemView.imageSubText?.text = "${scoreText ?: ""}$separator${item.nextEpisode ?: ""}"
            itemView.imageSubTextSecond?.text = seasonText
            itemView.setOnClickListener {
                loadPage()
            }

            itemView.backgroundCard.setOnLongClickListener {
                val bottomSheetDialog = BottomSheetDialog(context, R.style.AppBottomSheetDialogTheme)
                bottomSheetDialog.setContentView(R.layout.fragment_library_edit_slug)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    bottomSheetDialog.bottom_sheet_top_bar_slug.backgroundTintList =
                        ColorStateList.valueOf(Cyanea.instance.backgroundColorDark)
                }
                bottomSheetDialog.slug_selector_root.background = ColorDrawable(Cyanea.instance.backgroundColor)

                val slug: String? = context.getKey(
                    LIBRARY_PAGE_MAL_OVERRIDE_SLUG,
                    item.id,
                    null
                )

                slug?.let {
                    bottomSheetDialog.slug_text_holder.editText?.setText(it)
                }

                bottomSheetDialog.slug_save_btt.setOnClickListener {
                    val id = bottomSheetDialog.slug_text_holder.editText?.text?.toString()
                    if (id == "") {
                        context.removeKey(LIBRARY_PAGE_MAL_OVERRIDE_SLUG, item.id)
                    } else {
                        context.setKey(LIBRARY_PAGE_MAL_OVERRIDE_SLUG, item.id, id)
                    }
                }

                bottomSheetDialog.copy_mal_id_btt.setOnClickListener {
                    val clipboard: ClipboardManager? =
                        it.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager?
                    val clip = ClipData.newPlainText("Mal ID", item.id)
                    clipboard?.setPrimaryClip(clip)
                    Toast.makeText(it.context, "Mal id copied to clipboard", Toast.LENGTH_SHORT).show()
                }

                item.idAnilist?.let { idAnilist ->
                    bottomSheetDialog.copy_anilist_id_btt.visibility = VISIBLE
                    bottomSheetDialog.copy_anilist_id_btt.setOnClickListener {
                        val clipboard: ClipboardManager? =
                            it.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager?
                        val clip = ClipData.newPlainText("Anilist ID", idAnilist)
                        clipboard?.setPrimaryClip(clip)
                        Toast.makeText(it.context, "Anilist id copied to clipboard", Toast.LENGTH_SHORT).show()
                    }
                }

                // Full expansion for TV
                bottomSheetDialog.setOnShowListener {
                    normalSafeApiCall {
                        BottomSheetBehavior.from(bottomSheetDialog.slug_selector_root.parent as View).peekHeight =
                            bottomSheetDialog.slug_selector_root.height
                    }
                }

                bottomSheetDialog.show()

                return@setOnLongClickListener true
            }

            /*itemView.imageView.setOnClickListener {
                getCurrentActivity()?.loadPage(item.id, item.title, true)
                //activity?.loadPage(card.slug, card.name)
                /*MainActivity.loadPage(card)*/
            }*/

            if (item.poster != "") {
                val glideUrl = item.poster
                context.let {
                    val settingsManager = PreferenceManager.getDefaultSharedPreferences(it)
                    val savingData = settingsManager.getBoolean("data_saving", false)
                    GlideApp.with(it)
                        .load(glideUrl)
                        .transition(DrawableTransitionOptions.withCrossFade(100))
                        .onlyRetrieveFromCache(savingData)
                        .into(itemView.imageView)
                }
            }
        }
    }
}