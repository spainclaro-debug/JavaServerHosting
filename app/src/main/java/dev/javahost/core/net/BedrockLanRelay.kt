package dev.javahost.core.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

/**
 * Point-in-time status of a [BedrockLanRelay], read by the UI and by tests.
 *
 * @property running whether the listening socket is bound and the loops are live.
 * @property clients number of active per-client relay sessions (to Geyser).
 * @property packetsFromClients datagrams forwarded from Bedrock clients to Geyser.
 * @property packetsFromServer datagrams forwarded from Geyser back to clients.
 * @property pingsAnswered pongs rewritten and returned to clients.
 * @property lastServerInfo MOTD/protocol/version parsed from the latest pong.
 */
data class BedrockRelayStats(
    val running: Boolean = false,
    val clients: Int = 0,
    val packetsFromClients: Long = 0,
    val packetsFromServer: Long = 0,
    val pingsAnswered: Long = 0,
    val lastServerInfo: BedrockRakNet.ServerInfo? = null
)

/**
 * Phantom-style UDP bridge that lets Bedrock Edition clients discover and join a
 * Geyser-backed server over the LAN.
 *
 * Bedrock clients only list a LAN server when something answers their broadcast
 * Unconnected Ping on UDP 19132 with an Unconnected Pong **sent from the very
 * address/port the client should connect to**. Geyser owns 19132 normally, so
 * this bridge owns 19132 instead and Geyser is moved to 19133:
 *
 * - The single listening [DatagramSocket] binds `0.0.0.0:[listenPort]` with
 *   `SO_BROADCAST` only; [boundPort] exposes the actual port (the OS-assigned
 *   one when `listenPort == 0`, which keeps tests race-free). `SO_REUSEADDR` is
 *   deliberately **not** set: if Geyser still owns 19132 the bind must fail
 *   loudly instead of silently splitting datagrams between two sockets.
 * - Client datagrams (pings included) are relayed to `[upstreamHost]:[upstreamPort]`
 *   through one connected `DatagramSocket` per client, so Geyser sees a stable
 *   peer address for every client.
 * - Server datagrams are sent back to the client **through the listening
 *   socket**, so Bedrock sees the pong/connection coming from 19132. Unconnected
 *   Pongs (id 0x1c) are rewritten first through [BedrockRakNet.rewritePong]: the
 *   advertised port is [boundPort] and the GUID is a per-instance relay GUID.
 * - When [beaconIntervalMs] is positive, the latest rewritten pong is also
 *   sent every interval to each address returned by [broadcastProvider] (e.g.
 *   `255.255.255.255` and each LAN interface's subnet broadcast) at
 *   [boundPort]. A non-positive interval disables the unsolicited beacon
 *   entirely: the relay then only answers client pings (Phantom-style).
 * - Sessions idle for [idleTimeoutMs] are closed; when more than [maxClients]
 *   exist the oldest session is closed first.
 *
 * [start] and [stop] are idempotent and thread safe. A failed bind makes
 * [start] return false (and invokes [onError]) instead of throwing, and every
 * socket/coroutine is released by [stop]. All asynchronous failures are routed
 * to [onError] at most once per 30 s (bind failures are always reported).
 *
 * Pure JVM: only `java.net` + coroutines, no Android imports.
 *
 * @param listenPort UDP port to own on all interfaces; 0 lets the OS choose.
 * @param upstreamHost Geyser host, normally loopback.
 * @param upstreamPort Geyser's UDP port, normally [BedrockRakNet.BEDROCK_GEYSER_PORT].
 * @param broadcastProvider supplies subnet/broadcast addresses to beacon; may
 *   throw, and is called from a background thread.
 * @param beaconIntervalMs interval between unsolicited pong beacons; `<= 0`
 *   disables them (reply-only mode).
 * @param idleTimeoutMs idle time after which a client session is closed.
 * @param maxClients maximum concurrent client sessions (oldest is evicted).
 * @param onError optional error sink invoked from a background thread.
 */
class BedrockLanRelay(
    private val listenPort: Int = BedrockRakNet.BEDROCK_LAN_PORT,
    private val upstreamHost: String = "127.0.0.1",
    private val upstreamPort: Int = BedrockRakNet.BEDROCK_GEYSER_PORT,
    private val broadcastProvider: () -> List<String> = { emptyList() },
    private val beaconIntervalMs: Long = 2_000L,
    private val idleTimeoutMs: Long = 120_000L,
    private val maxClients: Int = 32,
    private val onError: ((String) -> Unit)? = null
) {

    /** Guards [listener], [scope], [running] and the lifecycle transitions. */
    private val stateLock = Any()

    /** Guards [sessions]; always acquired after [stateLock], never before it. */
    private val sessionLock = Any()

    @Volatile
    private var running = false

    @Volatile
    private var listener: DatagramSocket? = null

    @Volatile
    private var scope: CoroutineScope? = null

    @Volatile
    private var listenerBoundPort: Int = listenPort

    /** Stable identity advertised to Bedrock clients for this relay instance. */
    private val relayGuid: Long = randomGuid()

    /** Latest pong seen from Geyser; null until Geyser answers something. */
    @Volatile
    private var cachedPong: BedrockRakNet.Pong? = null

    @Volatile
    private var lastServerInfo: BedrockRakNet.ServerInfo? = null

    private val sessions = ConcurrentHashMap<InetSocketAddress, Session>()

    private val packetsFromClients = AtomicLong()
    private val packetsFromServer = AtomicLong()
    private val pingsAnswered = AtomicLong()
    private val lastErrorAtNs = AtomicLong()

    /**
     * Actual UDP port of the listening socket: [listenPort] before the first
     * successful [start], the OS-assigned port when it was 0, and the last
     * bound port after [stop].
     */
    val boundPort: Int
        get() = listenerBoundPort

    /**
     * Binds the listening socket and starts the reader/beacon/maintenance loops.
     *
     * Idempotent: returns true immediately when already running. Returns false
     * (without throwing) when the port cannot be bound and reports the reason
     * through [onError].
     */
    fun start(): Boolean {
        synchronized(stateLock) {
            if (running) return true
            val newListener = try {
                openListener()
            } catch (t: Throwable) {
                reportError(
                    "Cannot start the Bedrock LAN bridge on UDP port $listenPort: ${describe(t)}",
                    force = true
                )
                return false
            }
            val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            listener = newListener
            scope = newScope
            listenerBoundPort = newListener.localPort
            running = true
            cachedPong = null
            lastServerInfo = null
            packetsFromClients.set(0L)
            packetsFromServer.set(0L)
            pingsAnswered.set(0L)
            closeAllSessions()
            newScope.launch { readerLoop(newListener) }
            if (beaconIntervalMs > 0L) {
                newScope.launch { beaconLoop(newListener) }
            }
            if (idleTimeoutMs > 0L) {
                newScope.launch { maintenanceLoop() }
            }
            return true
        }
    }

    /**
     * Closes the listening socket, every client session socket and the
     * coroutine scope. Idempotent; safe to call from any thread.
     */
    fun stop() {
        val scopeToCancel: CoroutineScope?
        val listenerToClose: DatagramSocket?
        synchronized(stateLock) {
            scopeToCancel = scope
            listenerToClose = listener
            running = false
            scope = null
            listener = null
            closeAllSessions()
        }
        runCatching { scopeToCancel?.cancel() }
        runCatching { listenerToClose?.close() }
    }

    /** True between a successful [start] and the next [stop]. */
    fun isRunning(): Boolean = running

    /** Snapshot of the relay counters and the latest Geyser server info. */
    fun stats(): BedrockRelayStats = BedrockRelayStats(
        running = running,
        clients = sessions.size,
        packetsFromClients = packetsFromClients.get(),
        packetsFromServer = packetsFromServer.get(),
        pingsAnswered = pingsAnswered.get(),
        lastServerInfo = lastServerInfo
    )

    /* ------------------------------------------------------------------ */
    /* Lifecycle internals                                                */
    /* ------------------------------------------------------------------ */

    /**
     * Creates and binds the single listening socket.
     *
     * `broadcast` is required for the unsolicited beacon. `reuseAddress` is
     * intentionally left off: with `SO_REUSEADDR` a second socket could bind
     * 19132 and the OS would split client datagrams between this bridge and
     * Geyser, which is exactly the "ghost server" failure this must report.
     */
    private fun openListener(): DatagramSocket {
        val newSocket = DatagramSocket(null)
        try {
            newSocket.broadcast = true
            newSocket.bind(InetSocketAddress(listenPort))
            return newSocket
        } catch (t: Throwable) {
            runCatching { newSocket.close() }
            throw t
        }
    }

    /** True while [candidate] is the listener of the current run. */
    private fun isActiveSocket(candidate: DatagramSocket): Boolean =
        synchronized(stateLock) { running && listener === candidate }

    /* ------------------------------------------------------------------ */
    /* Listening socket: client -> Geyser                                 */
    /* ------------------------------------------------------------------ */

    /** Receives client datagrams until the socket is closed or the relay stops. */
    private suspend fun readerLoop(listeningSocket: DatagramSocket) {
        val buffer = ByteArray(MAX_DATAGRAM_BYTES)
        while (isActiveSocket(listeningSocket)) {
            val packet = try {
                val incoming = DatagramPacket(buffer, buffer.size)
                listeningSocket.receive(incoming)
                incoming
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (!isActiveSocket(listeningSocket)) return
                reportError("Bedrock LAN bridge receive failed: ${describe(t)}")
                delay(RECEIVE_RETRY_MS)
                continue
            }
            val data = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
            val client = try {
                InetSocketAddress(packet.address, packet.port)
            } catch (t: Throwable) {
                continue
            }
            handleClientDatagram(client, data)
        }
    }

    /** Forwards one client datagram to Geyser, creating/evicting a session as needed. */
    private fun handleClientDatagram(client: InetSocketAddress, data: ByteArray) {
        if (!running) return
        val session = sessionFor(client) ?: return
        packetsFromClients.incrementAndGet()
        session.touch()
        try {
            session.socket.send(DatagramPacket(data, data.size))
        } catch (t: Throwable) {
            if (!running) return
            reportError("Bedrock LAN bridge cannot send to Geyser: ${describe(t)}")
            closeSession(session)
        }
    }

    /**
     * Returns the session for [client], creating one when needed. Serialised on
     * [stateLock] so start/stop cannot race a half-created session; evicts the
     * least recently active session when [maxClients] is reached.
     */
    private fun sessionFor(client: InetSocketAddress): Session? {
        sessions[client]?.let { return it }
        synchronized(stateLock) {
            sessions[client]?.let { return it }
            if (!running) return null
            val activeScope = scope ?: return null
            val upstream = try {
                InetSocketAddress(upstreamHost, upstreamPort)
            } catch (t: Throwable) {
                reportError(
                    "Bedrock LAN bridge has an invalid Geyser address " +
                        "$upstreamHost:$upstreamPort: ${describe(t)}"
                )
                return null
            }
            val sessionSocket = try {
                DatagramSocket().apply { connect(upstream) }
            } catch (t: Throwable) {
                reportError("Bedrock LAN bridge cannot open a Geyser socket: ${describe(t)}")
                return null
            }
            val evicted = ArrayList<Session>(1)
            val session = Session(client, sessionSocket)
            synchronized(sessionLock) {
                val limit = maxClients.coerceAtLeast(1)
                while (sessions.size >= limit) {
                    val oldest = sessions.values.minByOrNull { it.lastActivityNs } ?: break
                    sessions.remove(oldest.client)
                    evicted.add(oldest)
                }
                sessions[client] = session
            }
            evicted.forEach { it.close() }
            session.job = activeScope.launch { sessionReaderLoop(session) }
            return session
        }
    }

    /* ------------------------------------------------------------------ */
    /* Per-client session: Geyser -> client                               */
    /* ------------------------------------------------------------------ */

    /** Receives datagrams from Geyser for one client until the session closes. */
    private suspend fun sessionReaderLoop(session: Session) {
        val buffer = ByteArray(MAX_DATAGRAM_BYTES)
        while (running) {
            val datagram = try {
                val incoming = DatagramPacket(buffer, buffer.size)
                session.socket.receive(incoming)
                incoming
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (!running) return
                if (sessions[session.client] !== session) return
                reportError("Bedrock LAN bridge lost the Geyser connection: ${describe(t)}")
                closeSession(session)
                return
            }
            session.touch()
            val data = datagram.data.copyOfRange(datagram.offset, datagram.offset + datagram.length)
            handleServerDatagram(session, data)
        }
    }

    /** Rewrites pongs and forwards every server datagram to the owning client. */
    private fun handleServerDatagram(session: Session, data: ByteArray) {
        packetsFromServer.incrementAndGet()
        val pong = BedrockRakNet.parsePong(data)
        val payload = if (pong != null) {
            cachedPong = pong
            lastServerInfo = BedrockRakNet.parseServerId(pong.serverId)
            pingsAnswered.incrementAndGet()
            BedrockRakNet.rewritePong(pong, listenerBoundPort, relayGuid)
        } else {
            data
        }
        sendToClient(session.client, payload)
    }

    /**
     * Sends [payload] to [client] from the listening socket so the client sees
     * the pong/gameplay traffic coming from the advertised address and port.
     */
    private fun sendToClient(client: InetSocketAddress, payload: ByteArray) {
        val listeningSocket = synchronized(stateLock) { listener } ?: return
        try {
            listeningSocket.send(
                DatagramPacket(payload, payload.size, client.address, client.port)
            )
        } catch (t: Throwable) {
            if (!running) return
            reportError("Bedrock LAN bridge cannot send to the client: ${describe(t)}")
        }
    }

    /* ------------------------------------------------------------------ */
    /* Beacon and session maintenance                                     */
    /* ------------------------------------------------------------------ */

    /**
     * Re-sends the latest rewritten pong every [beaconIntervalMs] to every
     * address supplied by [broadcastProvider]. Only started for a positive
     * interval, and does nothing until Geyser has answered at least once.
     */
    private suspend fun beaconLoop(listeningSocket: DatagramSocket) {
        val interval = beaconIntervalMs.coerceAtLeast(MIN_BEACON_INTERVAL_MS)
        while (isActiveSocket(listeningSocket)) {
            delay(interval)
            if (!isActiveSocket(listeningSocket)) return
            val pong = cachedPong ?: continue
            val payload = BedrockRakNet.rewritePong(pong, listenerBoundPort, relayGuid)
            val addresses = try {
                broadcastProvider()
            } catch (t: Throwable) {
                reportError("Bedrock LAN bridge broadcast lookup failed: ${describe(t)}")
                emptyList()
            }
            for (rawAddress in addresses) {
                val addressText = rawAddress.trim()
                if (addressText.isEmpty()) continue
                val target = try {
                    InetAddress.getByName(addressText)
                } catch (t: Throwable) {
                    reportError(
                        "Bedrock LAN bridge cannot resolve '$addressText': ${describe(t)}"
                    )
                    continue
                }
                try {
                    listeningSocket.send(
                        DatagramPacket(payload, payload.size, target, listenerBoundPort)
                    )
                } catch (t: Throwable) {
                    if (!isActiveSocket(listeningSocket)) return
                    reportError("Bedrock LAN bridge beacon failed: ${describe(t)}")
                }
            }
        }
    }

    /** Periodically closes sessions that have been idle for [idleTimeoutMs]. */
    private suspend fun maintenanceLoop() {
        val interval = (idleTimeoutMs / 4).coerceIn(MIN_MAINTENANCE_MS, MAX_MAINTENANCE_MS)
        while (running) {
            delay(interval)
            if (!running) return
            pruneIdleSessions()
        }
    }

    /** Closes every session whose last activity is older than [idleTimeoutMs]. */
    private fun pruneIdleSessions() {
        if (idleTimeoutMs <= 0L) return
        val cutoffNs = System.nanoTime() - idleTimeoutMs * 1_000_000L
        val stale = synchronized(sessionLock) {
            sessions.values.filter { it.lastActivityNs < cutoffNs }
        }
        stale.forEach { closeSession(it) }
    }

    /** Removes and closes one session; safe to call repeatedly/concurrently. */
    private fun closeSession(session: Session) {
        sessions.remove(session.client, session)
        session.close()
    }

    /** Removes and closes every session. */
    private fun closeAllSessions() {
        val snapshot = synchronized(sessionLock) {
            val copy = sessions.values.toList()
            sessions.clear()
            copy
        }
        snapshot.forEach { it.close() }
    }

    /* ------------------------------------------------------------------ */
    /* Errors and helpers                                                 */
    /* ------------------------------------------------------------------ */

    /** Reports [message] through [onError], throttled unless [force] is set. */
    private fun reportError(message: String, force: Boolean = false) {
        if (!force) {
            val now = System.nanoTime()
            val previous = lastErrorAtNs.get()
            if (previous != 0L && now - previous < ERROR_THROTTLE_NS) return
            lastErrorAtNs.set(now)
        }
        runCatching { onError?.invoke(message) }
    }

    /** Short "Exception: message" description used in error strings. */
    private fun describe(t: Throwable): String =
        "${t.javaClass.simpleName}: ${t.message ?: "unknown error"}"

    /** One relay session: a client address plus its socket connected to Geyser. */
    private class Session(
        val client: InetSocketAddress,
        val socket: DatagramSocket
    ) {
        @Volatile
        var job: Job? = null

        @Volatile
        var lastActivityNs: Long = System.nanoTime()

        /** Marks the session as active now (for idle cleanup and eviction order). */
        fun touch() {
            lastActivityNs = System.nanoTime()
        }

        /** Closes the upstream socket and cancels the reader; idempotent. */
        fun close() {
            runCatching { socket.close() }
            runCatching { job?.cancel() }
            job = null
        }
    }

    companion object {
        /** Large enough for any UDP payload; RakNet datagrams are far smaller. */
        private const val MAX_DATAGRAM_BYTES = 64 * 1024

        /** Guards against a busy-loop if a caller passes a tiny beacon interval. */
        private const val MIN_BEACON_INTERVAL_MS = 100L

        /** Lower bound for the idle-cleanup tick. */
        private const val MIN_MAINTENANCE_MS = 100L

        /** Upper bound for the idle-cleanup tick. */
        private const val MAX_MAINTENANCE_MS = 30_000L

        /** Pause after a receive error so a broken socket cannot busy-spin. */
        private const val RECEIVE_RETRY_MS = 50L

        /** At most one non-fatal onError call per 30 seconds. */
        private const val ERROR_THROTTLE_NS = 30_000L * 1_000_000L

        /** Any non-zero random 64-bit identity for this relay instance. */
        private fun randomGuid(): Long {
            val guid = ThreadLocalRandom.current().nextLong()
            return if (guid == 0L) 1L else guid
        }
    }
}
