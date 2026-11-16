package eu.kanade.tachiyomi.network.interceptor

import android.content.Context
import eu.kanade.tachiyomi.network.AndroidCookieJar
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Pass-through stand-in. Extensions reference this class by name, but Nekomo does not ship an
 * interactive Cloudflare solver, so challenged requests simply fail like any other HTTP error.
 */
class CloudflareInterceptor(
    @Suppress("UNUSED_PARAMETER") context: Context,
    @Suppress("UNUSED_PARAMETER") cookieManager: AndroidCookieJar,
    @Suppress("UNUSED_PARAMETER") defaultUserAgentProvider: () -> String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request())
}
