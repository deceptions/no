package com.lagradost.shiro.ui

import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.View.INVISIBLE
import android.view.View.VISIBLE
import android.widget.AbsListView.CHOICE_MODE_SINGLE
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import com.google.android.gms.cast.MediaStatus.REPEAT_MODE_REPEAT_SINGLE
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.media.uicontroller.UIController
import com.google.android.gms.cast.framework.media.widget.ExpandedControllerActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.jaredrummler.cyanea.Cyanea
import com.lagradost.shiro.R
import com.lagradost.shiro.utils.AppUtils.settingsManager
import org.json.JSONObject

class SkipOpController(val view: ImageView) : UIController() {
    init {
        view.setImageResource(R.drawable.exo_controls_fastforward)
        view.setOnClickListener {
            remoteMediaClient?.let { client ->
                client.seek(client.approximateStreamPosition + 85000)
            }
        }
    }
}

class SelectSourceController(val view: ImageView) : UIController() {
    init {
        view.setImageResource(R.drawable.ic_baseline_playlist_play_24)
        view.setOnClickListener {
            val client = remoteMediaClient ?: return@setOnClickListener
            val items = mutableListOf<Pair<Int, String>>()
            for (i in 0 until client.mediaQueue.itemCount) {
                (client.mediaQueue.getItemAtIndex(i)?.media?.customData?.get("data") as? String)?.let { name ->
                    items.add(
                        client.mediaQueue.getItemAtIndex(i)!!.itemId to name
                    )
                }
            }
            if (items.isNotEmpty()) {
                val bottomSheetDialog = BottomSheetDialog(view.context, R.style.AppBottomSheetDialogTheme)
                bottomSheetDialog.setContentView(R.layout.bottom_sheet)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    bottomSheetDialog.findViewById<androidx.cardview.widget.CardView>(R.id.bottom_sheet_top_bar)?.backgroundTintList = ColorStateList.valueOf(
                        Cyanea.instance.backgroundColorDark
                    )
                }

                val res = bottomSheetDialog.findViewById<ListView>(R.id.sort_click)!!
                val arrayAdapter = ArrayAdapter<String>(view.context, R.layout.bottom_single_choice)
                arrayAdapter.addAll(ArrayList(items.map { it.second }))

                res.choiceMode = CHOICE_MODE_SINGLE
                res.adapter = arrayAdapter
                res.setItemChecked(
                    (client.currentItem?.itemId ?: 0) - 1,
                    true
                )
                res.setOnItemClickListener { _, _, position, _ ->
                    client.queueJumpToItem(
                        items[position].first,
                        client.approximateStreamPosition,
                        null
                    )
                    bottomSheetDialog.dismiss()
                }
                bottomSheetDialog.findViewById<TextView>(R.id.main_text)?.text = "Pick source"
                bottomSheetDialog.show()
            }
        }
    }

    override fun onMediaStatusUpdated() {
        super.onMediaStatusUpdated()
        // If there's 1 item it won't show
        val dataString = remoteMediaClient?.mediaQueue?.getItemAtIndex(1)?.media?.customData?.get("data") as? String
        view.visibility = if (dataString != null) VISIBLE else INVISIBLE
    }

    override fun onSessionConnected(castSession: CastSession) {
        super.onSessionConnected(castSession)
        remoteMediaClient?.queueSetRepeatMode(REPEAT_MODE_REPEAT_SINGLE, JSONObject())
    }
}

class SkipTimeController(val view: ImageView, forwards: Boolean) : UIController() {
    init {
        val time = settingsManager?.getInt("chromecast_tap_time", 30) ?: 30
        view.setImageResource(if (forwards) R.drawable.netflix_skip_forward else R.drawable.netflix_skip_back)
        view.setOnClickListener {
            remoteMediaClient?.let { client ->
                client.seek(client.approximateStreamPosition + time * 1000 * if (forwards) 1 else -1)
            }
        }
    }
}

class ControllerActivity : ExpandedControllerActivity() {
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)
        menuInflater.inflate(R.menu.cast_expanded_controller_menu, menu)
        CastButtonFactory.setUpMediaRouteButton(this, menu, R.id.media_route_menu_item)
        return true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sourcesButton: ImageView = getButtonImageViewAt(0)
        val skipBackButton: ImageView = getButtonImageViewAt(1)
        val skipForwardButton: ImageView = getButtonImageViewAt(2)
        val skipOpButton: ImageView = getButtonImageViewAt(3)
        uiMediaController.bindViewToUIController(sourcesButton, SelectSourceController(sourcesButton))
        uiMediaController.bindViewToUIController(skipBackButton, SkipTimeController(skipBackButton, false))
        uiMediaController.bindViewToUIController(skipForwardButton, SkipTimeController(skipForwardButton, true))
        uiMediaController.bindViewToUIController(skipOpButton, SkipOpController(skipOpButton))
    }
}
