package com.lagradost.shiro.utils

import com.lagradost.shiro.utils.mvvm.logError
import java.util.Collections

class Event<T> {
    // Backed by a synchronized set since observers are frequently added/removed from
    // fragment lifecycle callbacks while invoke() may be iterating from another thread
    // (e.g. network callbacks), which previously risked ConcurrentModificationException.
    private val observers = Collections.synchronizedSet(mutableSetOf<(T) -> Unit>())

    operator fun plusAssign(observer: (T) -> Unit) {
        observers.add(observer)
    }

    operator fun minusAssign(observer: (T) -> Unit) {
        observers.remove(observer)
    }

    operator fun invoke(value: T) {
        // Snapshot before iterating so concurrent add/remove during dispatch can't crash us,
        // and guard each observer so one throwing doesn't stop the rest / crash the caller.
        val snapshot = synchronized(observers) { observers.toList() }
        for (observer in snapshot) {
            try {
                observer(value)
            } catch (e: Exception) {
                logError(e)
            }
        }
    }
}