package dev.javahost.core.system

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log

/**
 * Holds the two locks a hosted Java server needs to survive with the screen
 * off and the Wi-Fi radio idle:
 *
 *  - a [PowerManager.PARTIAL_WAKE_LOCK] so the CPU keeps scheduling the child
 *    JVM's threads (ticking, networking, GC);
 *  - a [WifiManager.WifiLock] in high-performance mode so the Wi-Fi radio is not
 *    dropped into a low-power state between packets.
 *
 * Usage contract for the foreground service:
 *  - [acquire] only once the server is actually being started;
 *  - [release] in `finally`/`onDestroy`, and whenever the runtime leaves the
 *    active states.
 *
 * Both locks are non-reference-counted and every operation is idempotent, so a
 * duplicated start command or a crash/restart path cannot leak a lock. Locks
 * are per-process: if the app process is killed the OS releases them, so a
 * hard crash cannot leave the device awake forever either.
 */
class WakeLocks(context: Context) {

    private val appContext: Context = context.applicationContext
    private val powerManager: PowerManager? =
        runCatching { appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager }.getOrNull()
    private val wifiManager: WifiManager? =
        runCatching { appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager }.getOrNull()

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /** True when at least one of the two locks is currently held. */
    @get:Synchronized
    val isHeld: Boolean
        get() = (wakeLock?.isHeld == true) || (wifiLock?.isHeld == true)

    /** Acquires both locks (best effort per lock). Returns true when any is held. */
    @Synchronized
    fun acquire(): Boolean {
        acquireWakeLock()
        acquireWifiLock()
        return isHeld
    }

    /** Releases both locks. Safe to call repeatedly and from any thread. */
    @Synchronized
    fun release() {
        runCatching {
            wakeLock?.takeIf { it.isHeld }?.release()
        }.onFailure { Log.w(TAG, "Could not release partial wakelock", it) }
        wakeLock = null

        runCatching {
            wifiLock?.takeIf { it.isHeld }?.release()
        }.onFailure { Log.w(TAG, "Could not release wifi lock", it) }
        wifiLock = null
    }

    private fun acquireWakeLock() {
        try {
            val manager = powerManager ?: return
            val lock = wakeLock ?: manager
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                .apply { setReferenceCounted(false) }
                .also { wakeLock = it }
            if (!lock.isHeld) lock.acquire()
        } catch (t: Throwable) {
            Log.w(TAG, "Could not acquire partial wakelock", t)
        }
    }

    private fun acquireWifiLock() {
        try {
            val manager = wifiManager ?: return
            val lock = wifiLock ?: createWifiLock(manager)
                .also { wifiLock = it }
            if (!lock.isHeld) lock.acquire()
        } catch (t: Throwable) {
            Log.w(TAG, "Could not acquire wifi lock", t)
        }
    }

    private fun createWifiLock(manager: WifiManager): WifiManager.WifiLock {
        // WIFI_MODE_FULL_HIGH_PERF still exists and is the documented way to keep
        // the radio in a high-throughput state; fall back to FULL then to the
        // legacy mode on ROMs that removed the constant's implementation.
        val modes = buildList {
            add(WifiManager.WIFI_MODE_FULL_HIGH_PERF)
            add(WifiManager.WIFI_MODE_FULL)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(WifiManager.WIFI_MODE_FULL_LOW_LATENCY)
            }
        }
        var lastError: Throwable? = null
        for (mode in modes) {
            try {
                return manager.createWifiLock(mode, WIFI_LOCK_TAG).apply { setReferenceCounted(false) }
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw IllegalStateException("No usable Wi-Fi lock mode", lastError)
    }

    companion object {
        const val WAKE_LOCK_TAG = "JavaHost:server"
        const val WIFI_LOCK_TAG = "JavaHost:wifi"
        private const val TAG = "WakeLocks"
    }
}
