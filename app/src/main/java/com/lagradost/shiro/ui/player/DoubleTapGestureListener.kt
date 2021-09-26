package com.lagradost.shiro.ui.player

import android.os.Handler
import android.view.GestureDetector
import android.view.MotionEvent

abstract class DoubleTapGestureListener(private val ctx: PlayerFragment) :
    GestureDetector.SimpleOnGestureListener() {
    abstract fun onDoubleClickRight(clicks: Int, posX: Float, posY: Float)
    abstract fun onDoubleClickLeft(clicks: Int, posX: Float, posY: Float)
    abstract fun onSingleClick()

    private var clicksLeft = 0
    private var clicksRight = 0
    private var isDoubleTapping = false
    private val doubleTapDelay: Long = 500

    private val handler = Handler()
    private val cancelDoubleTapEvent = Runnable {
        isDoubleTapping = false
        clicksLeft = 0
        clicksRight = 0
    }

    private fun keepInDoubleTapMode() {
        isDoubleTapping = true
        handler.removeCallbacks(cancelDoubleTapEvent)
        handler.postDelayed(cancelDoubleTapEvent, doubleTapDelay)
    }

    /*private fun cancelInDoubleTapMode() {
        handler.removeCallbacks(cancelDoubleTapEvent)
        isDoubleTapping = false
    }*/

    override fun onDown(e: MotionEvent): Boolean {
        if (isDoubleTapping) return true
        return super.onDown(e)
    }

    override fun onSingleTapUp(e: MotionEvent): Boolean {
        if (!ctx.doubleTapEnabled || ctx.isLocked) {
            onSingleClick()
        } else if (isDoubleTapping) {
            processDoubleTap(e)
        }
        return true//super.onSingleTapUp(e)
    }

    private fun processDoubleTap(event: MotionEvent) {
        if (ctx.doubleTapEnabled && !ctx.isLocked) {
            keepInDoubleTapMode()
            if (event.rawX >= ctx.width / 2) {
                //println("${event.rawX} ${ctx.width}")
                clicksRight++
                onDoubleClickRight(clicksRight, event.x, event.y)
            } else {
                clicksLeft++
                onDoubleClickLeft(clicksLeft, event.x, event.y)
            }
        }
    }

    override fun onDoubleTap(event: MotionEvent): Boolean {
        if (!ctx.doubleTapEnabled || ctx.isLocked) {
            onSingleClick()
        } else {
            processDoubleTap(event)
        }
        return true
    }

    override fun onSingleTapConfirmed(e: MotionEvent?): Boolean {
        if (isDoubleTapping) return true
        // When disabled it uses onSingleTapUp instead
        if (ctx.doubleTapEnabled && !ctx.isLocked) {
            onSingleClick()
        }
        return true//super.onSingleTapConfirmed(e)
    }
}