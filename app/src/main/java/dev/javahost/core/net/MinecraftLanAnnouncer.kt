package dev.javahost.core.net

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketAddress
import kotlin.coroutines.cancellation.CancellationException

/**
 * Broadcasts a running Minecraft server on the local network so it shows up in
 * the game's "scanning for LAN games" list.
 *
 * Minecraft's client discovers LAN worlds through a UDP multicast datagram on
 * `224.0.2.60:4445` with a `[MOTD]…[/MOTD][AD]port[/AD]` payload (the exact
 * format lives in the pure-JVM [MinecraftLanAnnouncement]). A dedicated server
 * never announces itself, hence this class.
 *
 * The announcer owns its socket, its multicast lock and a private
 * `SupervisorJob` on [Dispatchers.IO]; the loop keeps no Android context
 * reference. [start] and [stop] are idempotent and thread safe, per-tick socket
 * errors are tolerated (reported through [onError] at most once every 30 s) so
 * one flaky send cannot stop discovery.
 *
 * @param lan used for the Wi-Fi multicast lock and the preferred interface.
 * @param onError optional error sink, invoked from a background thread.
 */
class MinecraftLanAnnouncer(
    private val lan: AndroidLanInfo,
    private val onError: ((String) -> Unit)? = null
) {

    /** What to announce: a display name, the Minecraft port and the resend interval. */
    data class Announcement(
        val motd: String,
        val port: Int,
        val intervalMs: Long = MinecraftLanAnnouncement.INTERVAL_MS
    )

    private val stateLock = Any()

    private var scope: CoroutineScope? = null
    private var socket: MulticastSocket? = null
    private var running = false
    private var lastErrorAtNs: Long? = null

    /**
     * Starts announcing [announcement] on the LAN.
     *
     * Idempotent: returns true without doing anything when already running.
     * Acquires the Wi-Fi multicast lock and binds an ephemeral multicast socket
     * before returning, so false means "cannot announce on this device right
     * now" (no Wi-Fi multicast support, invalid announcement, socket failure).
     * Never throws.
     */
    fun start(announcement: Announcement): Boolean {
        synchronized(stateLock) {
            if (running) return true

            val payload = try {
                MinecraftLanAnnouncement.payload(announcement.motd, announcement.port)
            } catch (t: Throwable) {
                reportError("Cannot announce the server on the LAN: ${describe(t)}")
                return false
            }
            val group = try {
                InetAddress.getByName(MinecraftLanAnnouncement.MULTICAST_GROUP)
            } catch (t: Throwable) {
                reportError("Cannot announce the server on the LAN: ${describe(t)}")
                return false
            }

            // MulticastLock is a RECEIVE-side lock: sending multicast only needs
            // INTERNET. Take the lock when available (some devices behave better
            // with it held) but never disable discovery because of it.
            val multicastLock = runCatching { lan.multicastLock() }.getOrDefault(false)
            if (!multicastLock) {
                Log.w(
                    TAG,
                    "Wi-Fi multicast lock unavailable; announcing without it"
                )
            }

            val multicastSocket = try {
                openSocket()
            } catch (t: Throwable) {
                runCatching { lan.releaseMulticastLock() }
                reportError("Cannot announce the server on the LAN: ${describe(t)}")
                return false
            }

            val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            socket = multicastSocket
            scope = newScope
            running = true
            newScope.launch { announceLoop(multicastSocket, group, payload, announcement.intervalMs) }
            return true
        }
    }

    /**
     * Stops announcing and releases the socket, the coroutine and the multicast
     * lock. Idempotent and safe to call from any thread; blocking work is
     * negligible (the socket close is non-blocking).
     */
    fun stop() {
        val (scopeToCancel, socketToClose) = synchronized(stateLock) {
            val currentScope = scope
            val currentSocket = socket
            running = false
            scope = null
            socket = null
            currentScope to currentSocket
        }
        runCatching { scopeToCancel?.cancel() }
        socketToClose?.let { multicast -> runCatching { multicast.close() } }
        runCatching { lan.releaseMulticastLock() }
    }

    /** True between a successful [start] and the next [stop]. */
    fun isRunning(): Boolean = synchronized(stateLock) { running }

    /* ------------------------------------------------------------------ */
    /* Internals                                                          */
    /* ------------------------------------------------------------------ */

    /**
     * Creates the unbound multicast socket and binds it to an ephemeral port:
     * `reuseAddress` must be set before [MulticastSocket.bind], TTL 1 keeps the
     * datagrams on the local network. Selecting the sending interface is best
     * effort — when it fails the OS routing table decides.
     */
    private fun openSocket(): MulticastSocket {
        val multicast = MulticastSocket(null as SocketAddress?)
        try {
            multicast.reuseAddress = true
            multicast.bind(InetSocketAddress(0))
            multicast.timeToLive = 1
            runCatching { lan.preferredNetworkInterface() }.getOrNull()?.let { nic ->
                runCatching { multicast.networkInterface = nic }
            }
            return multicast
        } catch (t: Throwable) {
            runCatching { multicast.close() }
            throw t
        }
    }

    /** Sends one datagram per interval until the job is cancelled or [stop] closes the socket. */
    private suspend fun announceLoop(
        multicast: MulticastSocket,
        group: InetAddress,
        payload: ByteArray,
        intervalMs: Long
    ) {
        val effectiveIntervalMs = intervalMs.coerceAtLeast(MIN_INTERVAL_MS)
        while (true) {
            try {
                val datagram = DatagramPacket(
                    payload,
                    payload.size,
                    group,
                    MinecraftLanAnnouncement.DEFAULT_PORT
                )
                multicast.send(datagram)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // A closed socket (stop) or vanished interface must not be reported
                // as a failure, and a flaky send must not kill the loop.
                if (!isRunning()) return
                reportError("LAN announcement failed: ${describe(t)}")
            }
            delay(effectiveIntervalMs)
        }
    }

    /**
     * Reports one error, throttled to one message per [ERROR_THROTTLE_MS] so a
     * broken network cannot spam the UI. Called with or without [stateLock].
     */
    private fun reportError(message: String) {
        val now = System.nanoTime()
        synchronized(stateLock) {
            val previous = lastErrorAtNs
            if (previous != null && now - previous < ERROR_THROTTLE_NS) return
            lastErrorAtNs = now
        }
        Log.w(TAG, message)
        runCatching { onError?.invoke(message) }
    }

    private fun describe(t: Throwable): String =
        "${t.javaClass.simpleName}: ${t.message ?: "unknown error"}"

    companion object {
        private const val TAG = "MinecraftLanAnnouncer"

        /** Guards against a 0/negative interval busy-loop from a caller mistake. */
        private const val MIN_INTERVAL_MS = 250L

        /** Matches the contract: at most one onError call per 30 seconds. */
        private const val ERROR_THROTTLE_MS = 30_000L
        private const val ERROR_THROTTLE_NS = ERROR_THROTTLE_MS * 1_000_000L
    }
}
