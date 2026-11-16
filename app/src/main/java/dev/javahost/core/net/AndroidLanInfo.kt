package dev.javahost.core.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import java.net.Inet4Address
import java.net.InterfaceAddress
import java.net.NetworkInterface

/**
 * Best-effort snapshot of the phone's connectivity from the LAN feature's point
 * of view: which radios are connected and whether a VPN is in the way.
 */
data class LanNetworkState(
    val wifiConnected: Boolean = false,
    val ethernetConnected: Boolean = false,
    val mobileDataConnected: Boolean = false,
    val vpnActive: Boolean = false
)

/**
 * Android side of the LAN feature.
 *
 * [interfaces] enumerates the real network interfaces through
 * [NetworkInterface] and [endpoints] hands them to the pure-JVM
 * [LanAddressSelector], which drops cellular/VPN/virtual interfaces. The Wi-Fi
 * multicast lock and the preferred [NetworkInterface] are what lets
 * [MinecraftLanAnnouncer] broadcast on the right interface.
 *
 * Everything here is best effort: missing system services, a revoked permission
 * or a NetworkInterface that disappears mid-enumeration are reported as "not
 * found" instead of throwing. Only the application context is retained, so an
 * instance may safely outlive the activity that created it.
 */
class AndroidLanInfo(context: Context) {

    private val appContext: Context = context.applicationContext

    private val connectivityManager: ConnectivityManager? =
        runCatching { appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager }
            .getOrNull()

    private val wifiManager: WifiManager? =
        runCatching { appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager }
            .getOrNull()

    /** Wi-Fi multicast lock, created on first use and reference-counted by the callers. */
    private var wifiMulticastLock: WifiManager.MulticastLock? = null

    /** Number of sessions currently relying on the multicast lock. */
    private var multicastLockHolders: Int = 0

    /**
     * The phone's usable LAN interfaces: up, non-loopback, non-point-to-point
     * interfaces that carry a private (site-local) IPv4 address.
     *
     * Each [LanInterface] address is the plain dotted-quad from
     * [Inet4Address.hostAddress], so it can be pasted into Minecraft directly.
     * Interfaces that vanish or report their flags inconsistently are skipped.
     */
    fun interfaces(): List<LanInterface> = runCatching {
        val nics = NetworkInterface.getNetworkInterfaces() ?: return@runCatching emptyList<LanInterface>()
        val found = ArrayList<LanInterface>()
        while (nics.hasMoreElements()) {
            val nic = nics.nextElement() ?: continue
            val name = runCatching { nic.name }.getOrNull()
            if (name.isNullOrBlank()) continue
            // A NetworkInterface whose state cannot be read is treated as unusable.
            val up = runCatching { nic.isUp }.getOrDefault(false)
            val loopback = runCatching { nic.isLoopback }.getOrDefault(true)
            val pointToPoint = runCatching { nic.isPointToPoint }.getOrDefault(true)
            if (!up || loopback || pointToPoint) continue

            val addresses = runCatching { nic.inetAddresses }.getOrNull() ?: continue
            // CIDR prefixes live on InterfaceAddress; an unreadable list just means /24.
            val interfaceAddresses = runCatching { nic.interfaceAddresses }
                .getOrDefault(emptyList<InterfaceAddress>())
            while (addresses.hasMoreElements()) {
                val ipv4 = addresses.nextElement() as? Inet4Address ?: continue
                val siteLocal = runCatching { ipv4.isSiteLocalAddress }.getOrDefault(false)
                if (!siteLocal) continue
                val host = runCatching { ipv4.hostAddress }
                    .getOrNull()
                    ?.substringBefore('%')
                    ?.trim()
                    .orEmpty()
                if (host.isEmpty()) continue
                found.add(
                    LanInterface(
                        name = name,
                        address = host,
                        isUp = true,
                        isLoopback = false,
                        isPointToPoint = false,
                        prefixLength = ipv4PrefixLength(interfaceAddresses, host)
                    )
                )
            }
        }
        found
    }.getOrDefault(emptyList())

    /** Usable LAN endpoints, best Wi-Fi/ethernet address first, never cellular/VPN. */
    fun endpoints(): List<LanEndpoint> =
        runCatching { LanAddressSelector.select(interfaces()) }.getOrDefault(emptyList())

    /**
     * IPv4 prefix length (`/24` -> 24) that [NetworkInterface.interfaceAddresses]
     * reports for [host], or [DEFAULT_IPV4_PREFIX_LENGTH] when the matching entry
     * cannot be found or read. Used by the Bedrock LAN beacon to derive the
     * subnet broadcast address, so a wrong value only widens/narrows the beacon
     * target and never breaks the Java LAN card.
     */
    private fun ipv4PrefixLength(
        interfaceAddresses: List<InterfaceAddress>,
        host: String
    ): Int = runCatching {
        interfaceAddresses
            .firstOrNull { candidate ->
                runCatching { candidate.address.hostAddress }
                    .getOrNull()
                    ?.substringBefore('%')
                    ?.trim() == host
            }
            ?.networkPrefixLength
            ?.toInt()
            ?.takeIf { prefix -> prefix in 1..32 }
    }.getOrNull() ?: DEFAULT_IPV4_PREFIX_LENGTH

    /**
     * Reads the phone's connectivity: one flag per transport carrying a network.
     *
     * Uses [ConnectivityManager.allNetworks] + [NetworkCapabilities] (API 26+);
     * a [SecurityException] on a single network skips that network only. The
     * result drives the LAN card's "you are on mobile data / behind a VPN"
     * warnings, so a false flag is cosmetic at worst.
     */
    @Suppress("DEPRECATION")
    fun networkState(): LanNetworkState = runCatching {
        val manager = connectivityManager ?: return@runCatching LanNetworkState()
        val networks: Array<Network> = runCatching { manager.allNetworks }.getOrDefault(emptyArray())
        var wifi = false
        var ethernet = false
        var cellular = false
        var vpn = false
        for (network in networks) {
            val capabilities = runCatching { manager.getNetworkCapabilities(network) }.getOrNull()
                ?: continue
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) ethernet = true
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) cellular = true
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) vpn = true
        }
        LanNetworkState(
            wifiConnected = wifi,
            ethernetConnected = ethernet,
            mobileDataConnected = cellular,
            vpnActive = vpn
        )
    }.getOrDefault(LanNetworkState())

    /**
     * Acquires a non-reference-counted Wi-Fi multicast lock, which some devices
     * require before multicast packets actually leave the radio. Idempotent:
     * returns true when the lock is held, false when Wi-Fi (or the permission)
     * is unavailable. Never throws.
     */
    @Synchronized
    fun multicastLock(): Boolean {
        val manager = wifiManager ?: return false
        return try {
            val lock = wifiMulticastLock ?: run {
                val created = manager
                    .createMulticastLock(MULTICAST_LOCK_TAG)
                    .apply { setReferenceCounted(false) }
                wifiMulticastLock = created
                created
            }
            if (!lock.isHeld) lock.acquire()
            multicastLockHolders++
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Could not acquire Wi-Fi multicast lock", t)
            wifiMulticastLock = null
            false
        }
    }

    /**
     * Releases one reference to the multicast lock. The underlying Wi-Fi lock is
     * only released when the last session stops using it (up to
     * [dev.javahost.core.api.ServerController.MAX_RUNNING_SERVERS] sessions can
     * announce their Minecraft server at the same time). Safe to call repeatedly.
     */
    @Synchronized
    fun releaseMulticastLock() {
        if (multicastLockHolders > 0) multicastLockHolders--
        if (multicastLockHolders > 0) return
        val lock = wifiMulticastLock
        wifiMulticastLock = null
        if (lock == null) return
        runCatching { if (lock.isHeld) lock.release() }
            .onFailure { Log.w(TAG, "Could not release Wi-Fi multicast lock", it) }
    }

    /**
     * The interface [MinecraftLanAnnouncer] should send multicast on: the
     * interface of the first usable endpoint, or null when there is none (the
     * announcer then lets the OS pick).
     */
    fun preferredNetworkInterface(): NetworkInterface? = runCatching {
        val endpoint = endpoints().firstOrNull() ?: return@runCatching null
        NetworkInterface.getByName(endpoint.interfaceName)
    }.getOrNull()

    companion object {
        /** Tag of the Wi-Fi multicast lock; matches the contract. */
        const val MULTICAST_LOCK_TAG = "JavaHost:lan"

        /** Prefix assumed when [NetworkInterface.interfaceAddresses] cannot provide one. */
        private const val DEFAULT_IPV4_PREFIX_LENGTH = 24

        private const val TAG = "AndroidLanInfo"
    }
}
