package com.lagradost.shiro.utils

import android.content.Context
import androidx.preference.PreferenceManager
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Optional PIN app lock (Settings -> History and privacy). The hash (not the PIN) is stored;
 * `unlocked` resets with the process, so the code is asked once per cold start.
 */
object AppLock {
    private const val KEY_HASH = "app_lock_hash_v1"
    private const val KEY_SALT = "app_lock_salt_v1"

    /** True once the PIN has been entered this session. Not persisted: every cold start asks again. */
    var unlocked = false

    private fun prefs(ctx: Context) = PreferenceManager.getDefaultSharedPreferences(ctx)

    fun enabled(ctx: Context): Boolean = !prefs(ctx).getString(KEY_HASH, null).isNullOrEmpty()

    fun set(ctx: Context, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs(ctx).edit()
            .putString(KEY_SALT, salt.toHex())
            .putString(KEY_HASH, hash(pin, salt))
            .apply()
        unlocked = true
    }

    fun check(ctx: Context, pin: String): Boolean {
        val saltHex = prefs(ctx).getString(KEY_SALT, null) ?: return false
        val expected = prefs(ctx).getString(KEY_HASH, null) ?: return false
        val ok = hash(pin, saltHex.hexToBytes()) == expected
        if (ok) unlocked = true
        return ok
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().remove(KEY_HASH).remove(KEY_SALT).apply()
        unlocked = true
    }

    private fun hash(pin: String, salt: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt)
        return md.digest(pin.toByteArray(Charsets.UTF_8)).toHex()
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    private fun String.hexToBytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
