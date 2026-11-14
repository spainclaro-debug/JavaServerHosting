package dev.javahost.core.net

import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException

/** Classification of one TCP reachability self-test to the LAN address. */
enum class LanProbeResult { REACHABLE, REFUSED, TIMEOUT, ERROR }

/**
 * Outcome of one [TcpProbe] attempt.
 *
 * @property latencyMs measured connect time in milliseconds, only for [LanProbeResult.REACHABLE].
 * @property detail short human-readable reason for the non-reachable outcomes, or null.
 */
data class LanProbeOutcome(
    val result: LanProbeResult,
    val latencyMs: Long? = null,
    val detail: String? = null
) {
    /** True when the server accepted the TCP connection. */
    val ok: Boolean get() = result == LanProbeResult.REACHABLE
}

/** Performs one TCP connect attempt; an interface so callers and tests can fake it. */
interface TcpProbe {
    fun probe(host: String, port: Int, timeoutMs: Int = 1500): LanProbeOutcome
}

/**
 * [TcpProbe] backed by a plain blocking [Socket] connect.
 *
 * This answers "can another device on the LAN reach this address?" without
 * requiring any protocol traffic: if the TCP handshake succeeds the server is
 * listening on that interface. Invalid input is reported as
 * [LanProbeResult.ERROR] instead of throwing, so it is safe to feed directly
 * from UI actions.
 */
class SocketTcpProbe : TcpProbe {

    /**
     * Connects to [host]:[port] and returns the outcome, never throwing.
     * Blank hosts and ports outside 1..65535 return [LanProbeResult.ERROR]
     * without touching the network, and the socket is always closed.
     */
    override fun probe(host: String, port: Int, timeoutMs: Int): LanProbeOutcome {
        val cleanHost = host.trim()
        if (cleanHost.isEmpty()) {
            return LanProbeOutcome(LanProbeResult.ERROR, detail = "Host is blank")
        }
        if (port !in LanAddressSelector.MIN_PORT..LanAddressSelector.MAX_PORT) {
            return LanProbeOutcome(
                LanProbeResult.ERROR,
                detail = "Port $port is outside ${LanAddressSelector.MIN_PORT}..${LanAddressSelector.MAX_PORT}"
            )
        }
        // A zero/negative connect timeout means "wait forever" for Socket; clamp it.
        val effectiveTimeout = timeoutMs.coerceAtLeast(1)
        val startedAt = System.nanoTime()
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress(cleanHost, port), effectiveTimeout)
            val latencyMs = (System.nanoTime() - startedAt) / 1_000_000L
            LanProbeOutcome(LanProbeResult.REACHABLE, latencyMs = latencyMs)
        } catch (e: SocketTimeoutException) {
            LanProbeOutcome(LanProbeResult.TIMEOUT, detail = "Timed out after $effectiveTimeout ms")
        } catch (e: ConnectException) {
            LanProbeOutcome(LanProbeResult.REFUSED, detail = "Connection refused")
        } catch (e: NoRouteToHostException) {
            LanProbeOutcome(LanProbeResult.REFUSED, detail = "No route to host")
        } catch (e: SecurityException) {
            LanProbeOutcome(LanProbeResult.ERROR, detail = shortMessage(e))
        } catch (e: IOException) {
            LanProbeOutcome(LanProbeResult.ERROR, detail = shortMessage(e))
        } finally {
            try {
                socket.close()
            } catch (_: IOException) {
                // Closing a socket must never mask the probe result.
            }
        }
    }

    /** Short reason for EXIT/ERROR results: the exception message, or its class name. */
    private fun shortMessage(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
}
