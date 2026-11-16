package com.lagradost.shiro.ui.settings

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.jaredrummler.cyanea.app.CyaneaAppCompatActivity
import com.lagradost.shiro.utils.AppIdentity

/** Settings -> App name and icon: pick from the bundled Nekomo names/icons — presets only, no imports. */
class AppIdentityActivity : CyaneaAppCompatActivity() {
    private var pendingIcon: Bitmap? = null
    private var chosenName: String = ""
    private lateinit var preview: ImageView
    private lateinit var previewLabel: TextView

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun refreshPreview() {
        previewLabel.text = chosenName.ifBlank { AppIdentity.name(this) ?: AppIdentity.defaultName(this) }
        val bmp = pendingIcon ?: AppIdentity.icon(this)
        if (bmp != null) preview.setImageBitmap(bmp) else preview.setImageDrawable(packageManager.getApplicationIcon(packageName))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "App name and icon"
        val b = PageBuilder(this)
        val r = b.root

        r.addView(b.text("Pick a name and icon for ${AppIdentity.defaultName(this)} from the options below. Custom images can't be imported — only the bundled designs are available. This is separate from NEX.", 13f, dim = true))
        if (AppIdentity.isTv(this) || !AppIdentity.pinSupported(this)) {
            r.addView(b.text("Android TV and some launchers cannot add shortcuts to the home screen or change the app tile. Your name and icon still apply in the recent-apps card and as a long-press shortcut where the launcher supports it.", 12f, dim = true).apply { setPadding(0, b.dp(8), 0, 0) })
        }

        preview = ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(b.dp(96), b.dp(96)); scaleType = ImageView.ScaleType.FIT_CENTER }
        previewLabel = TextView(this).apply { textSize = 14f; setTextColor(b.fg); gravity = Gravity.CENTER; setPadding(0, b.dp(8), 0, 0); maxLines = 1 }
        val previewBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, b.dp(12), 0, b.dp(12))
            addView(preview); addView(previewLabel)
        }
        r.addView(b.heading("Preview"))
        r.addView(b.card(previewBox))
        chosenName = AppIdentity.name(this) ?: ""
        refreshPreview()

        // Names — presets only (ReSpotF-ck style: names stay close to the real one, Neko included).
        r.addView(b.heading("Name"))
        val nameRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Nekomo", "Neko", "NekoHub", "AnimeNeko", "Neko Stream", "Neko Club").forEach { n ->
            nameRow.addView(b.primaryButton(n) { chosenName = n; refreshPreview() })
        }
        r.addView(android.widget.HorizontalScrollView(this).apply { addView(nameRow) })

        // Real launcher disguise — activity-aliases swap the tile's name + icon system-wide.
        if (!AppIdentity.isTv(this)) {
            r.addView(b.heading("Launcher name & icon"))
            r.addView(b.text("Changes the app's own launcher tile. The launcher updates it a few seconds after you pick.", 12f, dim = true).apply { setPadding(0, 0, 0, b.dp(8)) })
            val aliasRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            AppIdentity.aliases.forEach { (id, label) ->
                aliasRow.addView(b.primaryButton(label) {
                    AppIdentity.applyAlias(this, id)
                    toast("Launcher tile set to \"$label\"")
                })
            }
            r.addView(android.widget.HorizontalScrollView(this).apply { addView(aliasRow) })
        }

        // Icon presets — tinted tiles + the bundled artwork. No custom-image import.
        r.addView(b.heading("Icon presets"))
        val iconRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            "Sakura" to 0xFFE88BA7.toInt(), "Winter" to 0xFF7AA7E8.toInt(),
            "Spooky" to 0xFFE8863A.toInt(), "Midnight" to 0xFF2B2140.toInt()
        ).forEach { (label, color) ->
            iconRow.addView(b.primaryButton(label) {
                pendingIcon = presetIcon(color)
                refreshPreview()
            })
        }
        r.addView(android.widget.HorizontalScrollView(this).apply { addView(iconRow) })

        r.addView(b.primaryButton("Apply") {
            AppIdentity.save(this, chosenName, pendingIcon)
            pendingIcon = null
            AppIdentity.applyTaskDescription(this)
            refreshPreview()
            toast("Saved. It now shows in recent apps and the long-press shortcut. Use \"Add to home screen\" to place it on your home screen.")
        })
        r.addView(b.primaryButton("Add to home screen") {
            AppIdentity.save(this, chosenName, pendingIcon); pendingIcon = null
            if (AppIdentity.requestPin(this)) toast("Confirm in the launcher to add the shortcut.")
            else toast("This launcher cannot add shortcuts. Your name and icon still apply in recent apps and the long-press shortcut.")
        })
        r.addView(b.primaryButton("Reset to default") {
            AppIdentity.reset(this)
            pendingIcon = null
            chosenName = ""
            refreshPreview()
            toast("Back to the original name and icon. Remove any pinned shortcut from your home screen.")
        })
        setContentView(b.finish())
        // Changing the name or icon needs its own puzzle (separate from the screen-capture one)
        com.lagradost.shiro.ui.settings.PuzzleUi.require(this, com.lagradost.shiro.utils.Puzzle.Kind.IDENTITY, "App name and icon", onCancel = { finish() }) { }
    }

    /** Seasonal tile: rounded square in [bg] with the default app icon drawn inset on top. */
    private fun presetIcon(bg: Int): Bitmap {
        val size = 192
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.color = bg
        canvas.drawRoundRect(android.graphics.RectF(0f, 0f, size.toFloat(), size.toFloat()), size * 0.22f, size * 0.22f, paint)
        val icon = packageManager.getApplicationIcon(packageName)
        val inset = (size * 0.14f).toInt()
        icon.setBounds(inset, inset, size - inset, size - inset)
        icon.draw(canvas)
        return AppIdentity.prepareIcon(out)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
