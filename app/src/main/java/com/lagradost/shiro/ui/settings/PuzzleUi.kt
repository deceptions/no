package com.lagradost.shiro.ui.settings

import android.app.Activity
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.lagradost.shiro.utils.Puzzle

/** Shows the multi-step puzzle for [kind]. The title also hides the bypass: tap it [Puzzle.BYPASS_TAPS] times quickly. */
object PuzzleUi {
    fun require(activity: Activity, kind: Puzzle.Kind, title: String, onCancel: () -> Unit = {}, onSolved: () -> Unit) {
        if (Puzzle.unlocked(kind)) { onSolved(); return }
        var rounds = Puzzle.generate(kind)
        var index = 0
        var fails = 0
        var taps = 0
        var windowStart = 0L
        val dp = activity.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val titleView = TextView(activity).apply { text = title; textSize = 20f; setPadding(px(24), px(20), px(24), px(4)) }
        val progress = TextView(activity).apply { textSize = 13f; alpha = 0.7f }
        val prompt = TextView(activity).apply { textSize = 17f; setPadding(0, px(8), 0, px(12)) }
        val input = EditText(activity).apply { setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT; hint = "Your answer"; gravity = Gravity.START }
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24), px(8), px(24), 0)
            addView(progress); addView(prompt); addView(input)
        }
        fun refresh() {
            progress.text = "Puzzle ${index + 1} of ${rounds.size}. Solve all to continue."
            prompt.text = rounds[index].prompt
            input.setText("")
        }
        val dialog = AlertDialog.Builder(activity).setCustomTitle(titleView).setView(body).setCancelable(false)
            .setPositiveButton("Check", null).setNegativeButton("Cancel") { _, _ -> onCancel() }.create()
        fun solved() { Puzzle.markUnlocked(kind); dialog.dismiss(); onSolved() }
        titleView.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now - windowStart > Puzzle.BYPASS_WINDOW_MS) { taps = 0; windowStart = now }
            if (++taps >= Puzzle.BYPASS_TAPS) solved()
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (Puzzle.isCorrect(rounds[index], input.text.toString())) {
                    index++
                    if (index >= rounds.size) solved() else refresh()
                } else {
                    fails++
                    if (fails % 3 == 0) { rounds = Puzzle.generate(kind); index = 0; Toast.makeText(activity, "Wrong again. Here is a new puzzle.", Toast.LENGTH_SHORT).show() }
                    else Toast.makeText(activity, "Not quite. Try again.", Toast.LENGTH_SHORT).show()
                    refresh()
                }
            }
        }
        refresh()
        dialog.show()
    }
}
