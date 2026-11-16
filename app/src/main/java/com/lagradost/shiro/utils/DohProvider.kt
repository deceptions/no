package com.lagradost.shiro.utils

import android.content.Context
import androidx.preference.PreferenceManager
import com.lagradost.shiro.AcraApplication
import com.lagradost.shiro.R
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress

const val DNS_PROVIDER_KEY = "dns_provider"

/**
 * DNS-over-HTTPS provider list for Settings -> General -> "DNS over HTTPS", mirroring the
 * option CloudStream/Aniyomi expose. This bypasses ISP/local DNS blocking or interception
 * for the app's own network requests - see [khttp.KHttp] which every provider/API client
 * in the app (NekomoApi, LiveApi, MALApi, AniListApi, extractors, ...) goes through.
 */
object DohProvider {
    private data class Provider(val url: String, val bootstrap: List<String>)

    private val providers = linkedMapOf(
        "Cloudflare" to Provider(
            "https://cloudflare-dns.com/dns-query",
            listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001")
        ),
        "Google" to Provider(
            "https://dns.google/dns-query",
            listOf("8.8.8.8", "8.8.4.4", "2001:4860:4860::8888", "2001:4860:4860::8844")
        ),
        "AdGuard" to Provider(
            "https://dns.adguard-dns.com/dns-query",
            listOf("94.140.14.14", "94.140.15.15")
        ),
        "AdGuard Family" to Provider(
            "https://family.adguard-dns.com/dns-query",
            listOf("94.140.14.15", "94.140.15.16")
        ),
        "Quad9" to Provider(
            "https://dns.quad9.net/dns-query",
            listOf("9.9.9.9", "149.112.112.112")
        ),
    )

    private fun disabledLabel(context: Context?): String {
        val ctx = context ?: AcraApplication.getAppContext()
        return ctx?.getString(R.string.dns_provider_disabled) ?: "Disabled (system default)"
    }

    private fun currentSelection(context: Context?): String {
        val ctx = context ?: AcraApplication.getAppContext() ?: return disabledLabel(context)
        return PreferenceManager.getDefaultSharedPreferences(ctx)
            .getString(DNS_PROVIDER_KEY, disabledLabel(ctx)) ?: disabledLabel(ctx)
    }

    /** A cheap key describing the current DNS setting, so callers know when to rebuild a client. */
    fun currentKey(context: Context? = null): String = currentSelection(context)

    /**
     * Builds an OkHttp [Dns] for the currently selected provider, or the system default
     * resolver ([Dns.SYSTEM]) if DNS-over-HTTPS is disabled, unset, or fails to initialize.
     */
    fun buildDns(context: Context? = null): Dns {
        val provider = providers[currentSelection(context)] ?: return Dns.SYSTEM
        return try {
            val bootstrapIps = provider.bootstrap.mapNotNull {
                try {
                    // These are all literal IPs, so this never performs an actual DNS lookup
                    // (which would otherwise be a chicken-and-egg problem).
                    InetAddress.getByName(it)
                } catch (e: Exception) {
                    null
                }
            }
            if (bootstrapIps.isEmpty()) return Dns.SYSTEM
            val bootstrapClient = OkHttpClient.Builder().build()
            DnsOverHttps.Builder()
                .client(bootstrapClient)
                .url(provider.url.toHttpUrl())
                .bootstrapDnsHosts(bootstrapIps)
                .build()
        } catch (e: Exception) {
            Dns.SYSTEM
        }
    }
}
