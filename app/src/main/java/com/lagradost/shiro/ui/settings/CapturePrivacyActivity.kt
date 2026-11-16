package com.lagradost.shiro.ui.settings

import android.os.Bundle
import com.jaredrummler.cyanea.app.CyaneaAppCompatActivity
import com.lagradost.shiro.utils.CapturePolicy
import com.lagradost.shiro.utils.Puzzle

/** Settings -> Screen capture. Opening this page requires solving a puzzle; then screenshots can be allowed or blocked again. */
class CapturePrivacyActivity : CyaneaAppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Screen capture"
        PuzzleUi.require(this, Puzzle.Kind.CAPTURE, "Screen capture", onCancel = { finish() }) { build() }
    }

    private fun build() {
        val b = PageBuilder(this)
        val r = b.root
        val status = b.text("", 15f, bold = true)
        fun refresh() { status.text = if (CapturePolicy.allowed(this)) "Screenshots and screen recording are ALLOWED." else "Screenshots and screen recording are BLOCKED (default)." }
        r.addView(b.text("By default Nekomo blocks screenshots, screen recording and the recent-apps preview to keep your library and account private. You can allow them here.", 13f, dim = true))
        r.addView(b.card(status))
        refresh()
        r.addView(b.primaryButton("Allow screenshots and screen recording") { CapturePolicy.set(this, true); CapturePolicy.apply(this); refresh() })
        r.addView(b.primaryButton("Block screenshots (default)") { CapturePolicy.set(this, false); CapturePolicy.apply(this); refresh() })
        setContentView(b.finish())
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
