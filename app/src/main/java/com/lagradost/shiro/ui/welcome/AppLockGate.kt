package com.lagradost.shiro.ui.welcome

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.jaredrummler.cyanea.Cyanea
import com.lagradost.shiro.utils.AppLock

/**
 * Full-screen PIN gate shown over the launcher on every cold start while AppLock is enabled.
 * Same pattern as [WelcomeGate]: non-cancelable, Back leaves the app, works with a TV remote
 * (the field opens the IME; D-pad moves to Unlock).
 */
class AppLockGate private constructor(private val activity: FragmentActivity) {

    private val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
    private var attempts = 0

    companion object {
        fun attachIfNeeded(activity: FragmentActivity) {
            if (!AppLock.enabled(activity) || AppLock.unlocked) return
            AppLockGate(activity).show()
        }
    }

    private fun show() {
        val bg = Cyanea.instance.backgroundColor
        val fg = if (Cyanea.instance.isDark) Color.WHITE else Color.BLACK
        dialog.apply {
            setCancelable(false)
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    activity.finish()
                    true
                } else false
            }
            window?.apply {
                setBackgroundDrawable(ColorDrawable(bg))
                setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            }
        }

        fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
            setPadding(dp(40), dp(24), dp(40), dp(24))
        }
        val error = TextView(activity).apply { setTextColor(0xFFE53935.toInt()); textSize = 14f; setPadding(0, dp(12), 0, 0) }
        val field = EditText(activity).apply {
            hint = "PIN"
            setHintTextColor(fg and 0x66FFFFFF.toInt() or (fg and 0x00FFFFFF))
            setTextColor(fg)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            maxLines = 1
            imeOptions = EditorInfo.IME_ACTION_DONE
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(220), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val unlock = {
            if (AppLock.check(activity, field.text.toString())) {
                dialog.dismiss()
            } else {
                attempts++
                field.setText("")
                error.text = if (attempts >= 5) "Wrong PIN. ${attempts} failed attempts." else "Wrong PIN. Try again."
            }
        }
        field.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE || event?.keyCode == KeyEvent.KEYCODE_ENTER || event?.keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                unlock(); true
            } else false
        }
        val unlockButton = Button(activity).apply {
            text = "Unlock"
            setOnClickListener { unlock() }
        }

        root.addView(TextView(activity).apply {
            text = "Nekomo is locked"
            setTextColor(fg)
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(activity).apply {
            text = "Enter your PIN to continue"
            setTextColor(fg)
            alpha = 0.75f
            textSize = 15f
            setPadding(0, dp(8), 0, dp(20))
        })
        root.addView(field)
        root.addView(error)
        root.addView(unlockButton, LinearLayout.LayoutParams(dp(220), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        dialog.setContentView(root)
        dialog.show()
        field.requestFocus()
    }
}
