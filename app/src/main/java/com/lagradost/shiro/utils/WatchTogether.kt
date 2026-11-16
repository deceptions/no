package com.lagradost.shiro.utils

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Same-WiFi Watch Together. No server: one phone hosts a tiny TCP broadcast on port 47100 and
 * streams `slug|episodeIndex|positionMs` lines every 3s; guests type the host's IP and follow —
 * seeking whenever the guest drifts more than ~4s. Pure java.net + a thread, no extra deps.
 */
object WatchTogether {
    const val PORT = 47100
    const val DRIFT_MS = 4000L

    @Volatile
    var hosting = false
        private set

    @Volatile
    var following = false
        private set

    private var server: ServerSocket? = null
    private var clients = mutableListOf<Socket>()
    private var guest: Socket? = null

    /** Current playback snapshot the host broadcasts. */
    data class Snapshot(val slug: String, val episodeIndex: Int, val posMs: Long)

    /** Guest-facing state setter — the player supplies a "seek here" callback. */
    fun hostLoop(supplier: () -> Snapshot?) {
        stop()
        hosting = true
        thread(name = "wt-host") {
            try {
                ServerSocket(PORT).also { server = it }.use { srv ->
                    // Accept clients in the background.
                    thread(name = "wt-accept") {
                        while (hosting) {
                            runCatching { srv.accept() }?.getOrNull()?.let { synchronized(clients) { clients.add(it) } }
                        }
                    }
                    while (hosting) {
                        val snap = supplier()
                        if (snap != null) {
                            val line = "${snap.slug}|${snap.episodeIndex}|${snap.posMs}\n".toByteArray()
                            synchronized(clients) {
                                val dead = clients.filter { runCatching { it.getOutputStream().write(line); it.getOutputStream().flush(); false }.getOrElse { true } }
                                clients.removeAll(dead)
                            }
                        }
                        Thread.sleep(3000)
                    }
                }
            } catch (_: Exception) {
                hosting = false
            }
        }
    }

    /** Guest: connect to the host's IP; [apply] and [onError] run on the main thread. */
    fun join(ip: String, apply: (Snapshot) -> Unit, onError: (String) -> Unit) {
        stop()
        following = true
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        thread(name = "wt-guest") {
            try {
                Socket(ip, PORT).also { guest = it }.use { s ->
                    val r = BufferedReader(InputStreamReader(s.getInputStream()))
                    while (following) {
                        val line = r.readLine() ?: break
                        val p = line.split('|')
                        if (p.size == 3) {
                            val snap = Snapshot(p[0], p[1].toIntOrNull() ?: 0, p[2].toLongOrNull() ?: 0L)
                            main.post { apply(snap) }
                        }
                    }
                }
            } catch (e: Exception) {
                main.post { onError(e.message ?: "Couldn't reach $ip") }
            }
            following = false
        }
    }

    fun stop() {
        hosting = false
        following = false
        runCatching { server?.close() }
        server = null
        synchronized(clients) { clients.forEach { runCatching { it.close() } }; clients.clear() }
        runCatching { guest?.close() }
        guest = null
    }

    /** First IPv4 Wi-Fi-ish address, for the host UI. */
    fun localIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }?.hostAddress
    } catch (_: Exception) { null }
}
