package com.lagradost.shiro.utils

import android.app.Activity
import android.app.Dialog
import android.content.Context
import com.lagradost.shiro.AcraApplication
import android.view.View
import androidx.annotation.IdRes
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView

/*
 * Drop-in replacement for kotlinx.android.synthetic view lookups.
 * Resolves a view by id on the receiver, throwing on missing views like synthetics did.
 */
/** NavController.navigate that survives double-taps / stale destinations instead of crashing. */
fun androidx.navigation.NavController.safeNavigate(
    @IdRes id: Int,
    args: android.os.Bundle? = null
) {
    try {
        navigate(id, args)
    } catch (e: Exception) {
        com.lagradost.shiro.utils.mvvm.logError(e)
    }
}

/**
 * A view id missing from a layout (e.g. the compact vs. full search card) used to throw an NPE
 * from the non-null cast, although call sites use `?.`. Hand back a detached throwaway view of
 * the requested type instead so the update is a harmless no-op.
 */
@PublishedApi
internal fun <T : View> missingView(cls: Class<T>, ctx: Context): T = try {
    cls.getConstructor(Context::class.java).newInstance(ctx)
} catch (t: Throwable) {
    throw IllegalStateException("View of type ${cls.name} is not available", t)
}

inline fun <reified T : View> View.fv(@IdRes id: Int): T =
    (findViewById<View>(id) as? T) ?: missingView(T::class.java, context)

inline fun <reified T : View> Activity.fv(@IdRes id: Int): T =
    (findViewById<View>(id) as? T) ?: missingView(T::class.java, this)

/**
 * Callbacks can fire after a fragment's view is destroyed (back navigation, async results).
 * Instead of crashing in requireView(), hand back a detached throwaway view of the requested
 * type so the stray update is a harmless no-op.
 */
@PublishedApi
internal fun <T : View> detachedView(cls: Class<T>, fragment: Fragment): T? = try {
    val ctx = fragment.context ?: AcraApplication.context ?: throw IllegalStateException("No context")
    cls.getConstructor(Context::class.java).newInstance(ctx)
} catch (t: Throwable) {
    null
}

@Suppress("UNCHECKED_CAST")
inline fun <reified T : View> Fragment.fv(@IdRes id: Int): T =
    (view?.findViewById<View>(id) as? T) ?: detachedView(T::class.java, this)
    ?: throw IllegalStateException("View $id is not available (fragment view destroyed?)")

inline fun <reified T : View> Dialog.fv(@IdRes id: Int): T =
    (findViewById<View>(id) as? T) ?: missingView(T::class.java, context)

@Suppress("UNCHECKED_CAST")
inline fun <reified T : View> DialogFragment.fv(@IdRes id: Int): T =
    (dialog?.findViewById<View>(id) as? T) ?: detachedView(T::class.java, this)
    ?: throw IllegalStateException("View $id is not available (dialog destroyed?)")

@Suppress("UNCHECKED_CAST")
inline fun <reified T : View> RecyclerView.ViewHolder.fv(@IdRes id: Int): T =
    (itemView.findViewById<View>(id) as? T) ?: missingView(T::class.java, itemView.context)
