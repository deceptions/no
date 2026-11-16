package com.lagradost.shiro.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import java.security.MessageDigest

/**
 * Lightweight blur for Glide (API 24, no RenderScript): scales the bitmap down to ~1/8 and back up,
 * which reads as a soft blur at banner sizes, then darkens/desaturates it so overlay text stays
 * readable — the Kuro-style translucent banner treatment.
 */
class BlurTransform(private val darken: Float = 0.35f) : BitmapTransformation() {
    private val id = "nekomo.blur.$darken"

    override fun transform(pool: BitmapPool, toTransform: Bitmap, outWidth: Int, outHeight: Int): Bitmap {
        val w = (toTransform.width / 8).coerceAtLeast(1)
        val h = (toTransform.height / 8).coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(toTransform, w, h, true)
        val blurred = Bitmap.createScaledBitmap(small, toTransform.width, toTransform.height, true)
        if (blurred != toTransform) pool.put(toTransform)
        if (darken <= 0f) return blurred
        val out = Bitmap.createBitmap(blurred.width, blurred.height, blurred.config ?: Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val cm = ColorMatrix().apply { setSaturation(0.8f) }
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(blurred, 0f, 0f, paint)
        paint.colorFilter = null
        canvas.drawColor(Color.argb((255 * darken).toInt(), 0, 0, 0))
        pool.put(blurred)
        pool.put(small)
        return out
    }

    override fun updateDiskCacheKey(messageDigest: MessageDigest) =
        messageDigest.update(id.toByteArray())
}
