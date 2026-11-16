package com.lagradost.shiro.utils.auth

import ANILIST_TOKEN_KEY
import ANILIST_USER_KEY
import DataStore.getKey
import DataStore.getKeys
import DataStore.removeKey
import DataStore.setKey
import MAL_TOKEN_KEY
import MAL_USER_KEY
import android.content.Context
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.lagradost.shiro.utils.ANILIST_ACCOUNT_ID
import com.lagradost.shiro.utils.MAL_ACCOUNT_ID

const val KITSU_TOKEN_KEY = "kitsu_token"
const val KITSU_REFRESH_TOKEN_KEY = "kitsu_refresh_token"
const val KITSU_USER_KEY = "kitsu_user"
const val KITSU_ACCOUNT_ID = "0"
private const val RULES_ACCEPTED_KEY = "community_rules_accepted"
private const val SIGN_IN_SKIPPED_KEY = "sign_in_skipped"
private const val DMCA_SEEN_KEY = "dmca_notice_seen_v1"

/**
 * Sign-in gate state. A user counts as signed in only when a token AND a fetched
 * profile exist for at least one provider, so a half-finished login (token stored,
 * profile fetch failed) does not let anybody into the app.
 */
object AuthManager {
    val mapper: JsonMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    private fun Context.hasAniList() =
        getKey<String>(ANILIST_TOKEN_KEY, ANILIST_ACCOUNT_ID, null) != null &&
                getKeys(ANILIST_USER_KEY).isNotEmpty()

    private fun Context.hasMal() =
        getKey<String>(MAL_TOKEN_KEY, MAL_ACCOUNT_ID, null) != null &&
                getKeys(MAL_USER_KEY).isNotEmpty()

    private fun Context.hasKitsu() =
        getKey<String>(KITSU_TOKEN_KEY, KITSU_ACCOUNT_ID, null) != null &&
                getKeys(KITSU_USER_KEY).isNotEmpty()

    fun isSignedIn(context: Context): Boolean = try {
        context.hasAniList() || context.hasMal() || context.hasKitsu()
    } catch (e: Exception) {
        false
    }

    fun hasSkippedSignIn(context: Context): Boolean = try {
        context.getKey<Boolean>(SIGN_IN_SKIPPED_KEY, false) ?: false
    } catch (e: Exception) {
        false
    }

    fun setSignInSkipped(context: Context) = context.setKey(SIGN_IN_SKIPPED_KEY, true)

    /** Signed in or explicitly skipped; the community rules still have to be accepted. */
    fun canEnter(context: Context): Boolean =
        (isSignedIn(context) || hasSkippedSignIn(context)) && hasSeenDmca(context) && hasAcceptedRules(context) && hasAcceptedRepoWarning(context) && hasCompletedSetup(context)

    /** The DMCA notice is shown once, right after signing in or skipping, before the home screen. */
    fun hasSeenDmca(context: Context): Boolean = try {
        context.getKey<Boolean>(DMCA_SEEN_KEY, false) ?: false
    } catch (e: Exception) {
        false
    }

    fun setDmcaSeen(context: Context) = context.setKey(DMCA_SEEN_KEY, true)

    fun hasAcceptedRules(context: Context): Boolean = try {
        context.getKey<Boolean>(RULES_ACCEPTED_KEY, false) ?: false
    } catch (e: Exception) {
        false
    }

    fun setRulesAccepted(context: Context) = context.setKey(RULES_ACCEPTED_KEY, true)

    /** Last onboarding step: the third-party repository warning. Also shown once to people who finished the older three steps. */
    fun hasAcceptedRepoWarning(context: Context): Boolean = try {
        context.getKey<Boolean>("repo_warning_accepted_v1", false) ?: false
    } catch (e: Exception) {
        false
    }

    fun setRepoWarningAccepted(context: Context) = context.setKey("repo_warning_accepted_v1", true)

    /** Final onboarding step: the CloudStream-style quick setup (interface mode, content, DoH). Shown once. */
    fun hasCompletedSetup(context: Context): Boolean = try {
        context.getKey<Boolean>("setup_completed_v1", false) ?: false
    } catch (e: Exception) {
        false
    }

    fun setSetupCompleted(context: Context) = context.setKey("setup_completed_v1", true)

    fun logoutKitsu(context: Context) {
        context.removeKey(KITSU_TOKEN_KEY, KITSU_ACCOUNT_ID)
        context.removeKey(KITSU_REFRESH_TOKEN_KEY, KITSU_ACCOUNT_ID)
        context.removeKey(KITSU_USER_KEY, KITSU_ACCOUNT_ID)
    }

    fun isLoggedIntoKitsu(context: Context) = context.hasKitsu()
}
