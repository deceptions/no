package com.lagradost.shiro.ui

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RelativeLayout
import com.google.android.gms.cast.framework.media.widget.MiniControllerFragment
import com.jaredrummler.cyanea.Cyanea
import com.lagradost.shiro.R
import com.lagradost.shiro.utils.AppUtils.adjustAlpha
import com.lagradost.shiro.utils.AppUtils.getColorFromAttr

class MyMiniControllerFragment : MiniControllerFragment() {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // SEE https://github.com/dandar3/android-google-play-services-cast-framework/blob/master/res/layout/cast_mini_controller.xml
        try {
            val progressBar: ProgressBar = view.findViewById(R.id.progressBar)
            val containerAll: LinearLayout = view.findViewById(R.id.container_all)

            containerAll.getChildAt(0)?.alpha = 0f // REMOVE GRADIENT

            context?.let { ctx ->
                progressBar.setBackgroundColor(adjustAlpha(Cyanea.instance.primary, 0.35f))
                val params = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, 2.toPx)

                progressBar.layoutParams = params
            }
        } catch (e : Exception) {
            // JUST IN CASE
        }
    }
}