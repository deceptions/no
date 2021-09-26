package com.lagradost.shiro.ui.player

/**
 * MIT License
 *
 * Copyright (c) 2019 Viktor Krez
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.animation.doOnEnd
import androidx.core.animation.doOnStart
import com.lagradost.shiro.R

/**
 * Layout group which handles the icon animation while forwarding and rewinding.
 *
 * Since it's based on view's alpha the fading effect is more fluid (more YouTube-like) than
 * using static drawables, especially when [cycleDuration] is low.
 *
 * From https://github.com/vkay94/DoubleTapPlayerView/blob/master/doubletapplayerview/src/main/java/com/github/vkay94/dtpv/youtube/views/YouTubeSecondsView.kt
 */

class SecondsView(context: Context, attrs: AttributeSet?) :
    ConstraintLayout(context, attrs) {

    private var trianglesContainer: LinearLayout
    private var secondsTextView: TextView
    private var icon1: ImageView
    private var icon2: ImageView
    private var icon3: ImageView

    init {
        LayoutInflater.from(context).inflate(R.layout.yt_seconds_view, this, true)

        trianglesContainer = findViewById(R.id.triangle_container)
        secondsTextView = findViewById(R.id.tv_seconds)
        icon1 = findViewById(R.id.icon_1)
        icon2 = findViewById(R.id.icon_2)
        icon3 = findViewById(R.id.icon_3)
    }

    /**
     * Defines the duration for a full cycle of the triangle animation.
     * Each animation step takes 20% of it.
     */
    var cycleDuration: Long = 750L
        set(value) {
            firstAnimator.duration = value / 5
            secondAnimator.duration = value / 5
            thirdAnimator.duration = value / 5
            fourthAnimator.duration = value / 5
            fifthAnimator.duration = value / 5
            field = value
        }

    /**
     * Sets the `TextView`'s seconds text according to the device`s language.
     */
    var seconds: Int = 0
        set(value) {
            secondsTextView.text = context.resources.getQuantityString(
                R.plurals.quick_seek_x_second, value, value
            )
            field = value
        }

    /**
     * Mirrors the triangles depending on what kind of type should be used (forward/rewind).
     */
    var isForward: Boolean = true
        set(value) {
            trianglesContainer.rotation = if (value) 0f else 180f
            field = value
        }

    val textView: TextView
        get() = secondsTextView

    @DrawableRes
    var icon: Int = R.drawable.ic_play_triangle
        set(value) {
            if (value > 0) {
                icon1.setImageResource(value)
                icon2.setImageResource(value)
                icon3.setImageResource(value)
            }
            field = value
        }

    /**
     * Starts the triangle animation
     */
    fun start() {
        stop()
        firstAnimator.start()
    }

    /**
     * Stops the triangle animation
     */
    fun stop() {
        firstAnimator.cancel()
        secondAnimator.cancel()
        thirdAnimator.cancel()
        fourthAnimator.cancel()
        fifthAnimator.cancel()
        reset()
    }

    private fun reset() {
        icon1.alpha = 0f
        icon2.alpha = 0f
        icon3.alpha = 0f
    }

    private val firstAnimator: ValueAnimator = CustomValueAnimator(
        {
            icon1.alpha = 0f
            icon2.alpha = 0f
            icon3.alpha = 0f
        }, {
            icon1.alpha = it
        }, {
            secondAnimator.start()
        }
    )

    private val secondAnimator: ValueAnimator = CustomValueAnimator(
        {
            icon1.alpha = 1f
            icon2.alpha = 0f
            icon3.alpha = 0f
        }, {
            icon2.alpha = it
        }, {
            thirdAnimator.start()
        }
    )

    private val thirdAnimator: ValueAnimator = CustomValueAnimator(
        {
            icon1.alpha = 1f
            icon2.alpha = 1f
            icon3.alpha = 0f
        }, {
            icon1.alpha = 1f - icon3.alpha // or 1f - it (t3.alpha => all three stay a little longer together)
            icon3.alpha = it
        }, {
            fourthAnimator.start()
        }
    )

    private val fourthAnimator: ValueAnimator = CustomValueAnimator(
        {
            icon1.alpha = 0f
            icon2.alpha = 1f
            icon3.alpha = 1f
        }, {
            icon2.alpha = 1f - it
        }, {
            fifthAnimator.start()
        }
    )

    private val fifthAnimator: ValueAnimator = CustomValueAnimator(
        {
            icon1.alpha = 0f
            icon2.alpha = 0f
            icon3.alpha = 1f
        }, {
            icon3.alpha = 1f - it
        }, {
            firstAnimator.start()
        }
    )

    private inner class CustomValueAnimator(
        start: () -> Unit, update: (value: Float) -> Unit, end: () -> Unit
    ) : ValueAnimator() {

        init {
            duration = cycleDuration / 5
            setFloatValues(0f, 1f)

            addUpdateListener { update(it.animatedValue as Float) }
            doOnStart { start() }
            doOnEnd { end() }
        }
    }

    fun changeConstraints(forward: Boolean, root_constraint_layout: ConstraintLayout?, seconds_view: SecondsView?) {
        val constraintSet = ConstraintSet()
        root_constraint_layout?.let { rootLayout ->
            seconds_view?.let { secondsView ->
                with(constraintSet) {
                    clone(rootLayout)
                    if (forward) {
                        clear(secondsView.id, ConstraintSet.START)
                        connect(
                            secondsView.id, ConstraintSet.END,
                            ConstraintSet.PARENT_ID, ConstraintSet.END
                        )
                    } else {
                        clear(secondsView.id, ConstraintSet.END)
                        connect(
                            secondsView.id, ConstraintSet.START,
                            ConstraintSet.PARENT_ID, ConstraintSet.START
                        )
                    }
                    secondsView.start()
                    applyTo(rootLayout)
                }
            }
        }
    }
}