package com.lagradost.shiro.utils

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import java.io.File

/**
 * Custom app name and icon. Android does not let an app rename or re-icon its own launcher entry, so the custom identity is
 * applied everywhere an app may legitimately set it: a pinned home-screen shortcut (phones and tablets), the long-press
 * shortcut, and the recent-apps card. Android TV launchers do not support pinned shortcuts, so there it applies to the
 * long-press shortcut (where offered) and the recent-apps card. Stored privately per app, survives updates.
 */
object AppIdentity {
    private const val PREFS = "app_identity"
    private const val SHORTCUT_ID = "custom_identity"
    private const val ICON_PX = 192

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun iconFile(c: Context) = File(c.filesDir, "custom_app_icon.png")

    fun defaultName(c: Context): String = c.applicationInfo.loadLabel(c.packageManager).toString()
    fun name(c: Context): String? = prefs(c).getString("name", null)?.takeIf { it.isNotBlank() }
    fun icon(c: Context): Bitmap? = iconFile(c).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
    fun isCustom(c: Context) = name(c) != null || iconFile(c).exists()
    fun isTv(c: Context) = (c.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    fun pinSupported(c: Context) = ShortcutManagerCompat.isRequestPinShortcutSupported(c)

    /** Center-crops to a square and rounds the corners, so any uploaded picture makes a clean icon. */
    fun prepareIcon(src: Bitmap): Bitmap {
        val side = minOf(src.width, src.height)
        val sq = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val out = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = RectF(0f, 0f, ICON_PX.toFloat(), ICON_PX.toFloat())
        canvas.drawRoundRect(rect, ICON_PX * 0.22f, ICON_PX * 0.22f, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(Bitmap.createScaledBitmap(sq, ICON_PX, ICON_PX, true), null, rect, paint)
        return out
    }

    fun load(c: Context, uri: Uri): Bitmap? = runCatching {
        c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }?.let { prepareIcon(it) }
    }.getOrNull()

    /** Saves the identity and refreshes the long-press shortcut. [iconBitmap] null keeps the current icon. */
    fun save(c: Context, name: String?, iconBitmap: Bitmap?) {
        prefs(c).edit().apply { if (name.isNullOrBlank()) remove("name") else putString("name", name.trim().take(40)) }.apply()
        if (iconBitmap != null) iconFile(c).outputStream().use { iconBitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        refreshDynamic(c)
    }

    private fun launchIntent(c: Context): Intent? =
        (c.packageManager.getLaunchIntentForPackage(c.packageName) ?: c.packageManager.getLeanbackLaunchIntentForPackage(c.packageName))?.setAction(Intent.ACTION_MAIN)

    private fun defaultIcon(c: Context): Bitmap {
        val d = c.packageManager.getApplicationIcon(c.packageName)
        val bmp = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, ICON_PX, ICON_PX)
        d.draw(Canvas(bmp))
        return bmp
    }

    private fun shortcut(c: Context): ShortcutInfoCompat? {
        val intent = launchIntent(c) ?: return null
        val label = name(c) ?: defaultName(c)
        val b = ShortcutInfoCompat.Builder(c, SHORTCUT_ID).setShortLabel(label).setLongLabel(label).setIntent(intent)
        // Launchers crash building the preview of an icon-less shortcut, so fall back to the app's own icon
        b.setIcon(IconCompat.createWithBitmap(icon(c) ?: defaultIcon(c)))
        return b.build()
    }

    fun refreshDynamic(c: Context) {
        runCatching {
            if (isCustom(c)) shortcut(c)?.let { ShortcutManagerCompat.setDynamicShortcuts(c, listOf(it)) }
            else ShortcutManagerCompat.removeAllDynamicShortcuts(c)
        }
    }

    /** Asks the launcher to pin the custom shortcut (the system shows its own confirmation). False when unsupported. */
    fun requestPin(c: Context): Boolean {
        if (!pinSupported(c)) return false
        val s = shortcut(c) ?: return false
        return runCatching { ShortcutManagerCompat.requestPinShortcut(c, s, null) }.getOrDefault(false)
    }

    // ---- Launcher aliases (real name+icon swap) ----

    /** Manifest activity-alias suffixes in `.ui.*` -> the label the launcher shows. */
    val aliases = linkedMapOf(
        "AliasDefault" to "Nekomo",
        "AliasNeko" to "Neko",
        "AliasNekoHub" to "NekoHub",
        "AliasAnimeNeko" to "AnimeNeko",
        "AliasNekoStream" to "Neko Stream",
        "AliasNekoClub" to "Neko Club",
        "AliasSakura" to "Neko Sakura",
        "AliasWinter" to "Neko Winter",
        "AliasSpooky" to "Neko Spooky",
        "AliasMidnight" to "Neko Midnight",
        "AliasNekoChan" to "Neko-chan",
    )

    fun activeAlias(c: Context): String = prefs(c).getString("launcher_alias", "AliasDefault") ?: "AliasDefault"

    /**
     * Enables exactly one launcher alias and disables the rest (never the default unless another is
     * on — that would remove the app from the launcher). Launchers pick the change up within seconds.
     */
    fun applyAlias(c: Context, aliasId: String) {
        if (!aliases.containsKey(aliasId)) return
        val pm = c.packageManager
        for (id in aliases.keys) {
            val comp = android.content.ComponentName(c.packageName, "${c.packageName}.ui.$id")
            runCatching {
                pm.setComponentEnabledSetting(
                    comp,
                    if (id == aliasId) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    android.content.pm.PackageManager.DONT_KILL_APP
                )
            }
        }
        prefs(c).edit().putString("launcher_alias", aliasId).apply()
    }

    /** Recent-apps card: shows the custom name and icon. */
    fun applyTaskDescription(a: Activity) {
        if (!isCustom(a)) return
        runCatching {
            @Suppress("DEPRECATION")
            a.setTaskDescription(ActivityManager.TaskDescription(name(a) ?: defaultName(a), icon(a)))
        }
    }

    fun reset(c: Context) {
        prefs(c).edit().clear().apply()
        iconFile(c).delete()
        runCatching {
            ShortcutManagerCompat.removeAllDynamicShortcuts(c)
            ShortcutManagerCompat.disableShortcuts(c, listOf(SHORTCUT_ID), "Reset to the default name and icon. Remove this shortcut.")
        }
    }
}
