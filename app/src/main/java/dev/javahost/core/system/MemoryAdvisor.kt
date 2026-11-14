package dev.javahost.core.system

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import dev.javahost.core.model.DeviceInfo

/**
 * Read-only view of the device's memory characteristics plus the heap
 * heuristics JavaHost uses for the hosted child JVM.
 *
 * Why conservative: the server is a child process of a normal Android app. The
 * Android framework, the launcher, the runtime and the JVM's own native
 * allocations (metaspace, code cache, GC structures, mapped jars) all compete
 * with the Java heap. Handing the child JVM "all the RAM" is the fastest way to
 * be killed by the low-memory killer mid-session, so the heuristics below
 * deliberately keep roughly half of the device RAM available to the system.
 *
 * All of the maths lives in [MemoryMath] and is pure so it can be unit tested
 * on a plain JVM without any Android framework classes.
 */
class MemoryAdvisor(context: Context) {

    private val appContext: Context = context.applicationContext
    private val activityManager: ActivityManager? =
        appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

    /**
     * Snapshot used by the setup wizard and the device information card.
     * Reads `ActivityManager.MemoryInfo` once; never throws.
     */
    fun deviceInfo(): DeviceInfo {
        val memory = readMemoryInfo()
        val lowRam = isLowRamDevice()
        val totalMb = memory?.totalMem?.div(MemoryMath.MB) ?: 0L
        val availMb = memory?.availMem?.div(MemoryMath.MB) ?: 0L
        val safe = MemoryMath.safeMaxHeapMb(totalMb, lowRam)
        return DeviceInfo(
            abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
            androidSdk = Build.VERSION.SDK_INT,
            androidRelease = Build.VERSION.RELEASE?.takeIf { it.isNotBlank() } ?: "unknown",
            totalRamMb = totalMb,
            availRamMb = availMb,
            safeMaxHeapMb = safe,
            autoHeapMb = MemoryMath.autoHeapMb(safe, lowRam),
            lowRamDevice = lowRam,
            manufacturer = Build.MANUFACTURER ?: "",
            model = Build.MODEL ?: "",
            coreCount = Runtime.getRuntime().availableProcessors()
        )
    }

    /** Largest heap that [MemoryMath] considers safe to give the child JVM. */
    fun safeMaxHeapMb(): Int {
        val totalMb = readMemoryInfo()?.totalMem?.div(MemoryMath.MB) ?: 0L
        return MemoryMath.safeMaxHeapMb(totalMb, isLowRamDevice())
    }

    /** Conservative default for "auto memory" instances. */
    fun autoHeapMb(): Int = MemoryMath.autoHeapMb(safeMaxHeapMb(), isLowRamDevice())

    /** True when a user-picked heap is beyond what this device can sustain. */
    fun isAboveSafe(mb: Int): Boolean = MemoryMath.isAboveSafe(mb, safeMaxHeapMb())

    /** Clamp a requested heap into the device's safe window. */
    fun clampHeapMb(mb: Int): Int =
        mb.coerceIn(MemoryMath.MIN_HEAP_MB, safeMaxHeapMb().coerceAtLeast(MemoryMath.MIN_HEAP_MB))

    /** Current free system RAM in MiB (0 when unknown). */
    fun availRamMb(): Long = readMemoryInfo()?.availMem?.div(MemoryMath.MB) ?: 0L

    /** Total physical RAM in MiB (0 when unknown). */
    fun totalRamMb(): Long = readMemoryInfo()?.totalMem?.div(MemoryMath.MB) ?: 0L

    fun isLowRamDevice(): Boolean =
        runCatching { activityManager?.isLowRamDevice ?: false }.getOrDefault(false)

    /** Fresh [ActivityManager.MemoryInfo] or null when the service is unavailable. */
    fun readMemoryInfo(): ActivityManager.MemoryInfo? {
        val manager = activityManager ?: return null
        return runCatching { ActivityManager.MemoryInfo().also(manager::getMemoryInfo) }.getOrNull()
    }
}

/**
 * Pure heap-size heuristics, split out of [MemoryAdvisor] so they can be tested
 * without Robolectric or a device.
 */
object MemoryMath {

    const val MB: Long = 1024L * 1024L

    /** Never configure less than this: below it most Java servers fail to boot. */
    const val MIN_HEAP_MB: Int = 384

    /** Default "auto" heap on a comfortable device. */
    const val DEFAULT_HEAP_MB: Int = 1536

    /** Hard ceiling for the slider, even on a 16 GB phone. */
    const val MAX_HEAP_CAP_MB: Int = 12288

    /** RAM kept back for Android, the app process and native JVM memory. */
    const val SYSTEM_RESERVE_MB: Long = 1024L

    /** Round the slider/limits to a clean 128 MB step. */
    private const val HEAP_STEP_MB: Int = 128

    /**
     * Maximum heap the UI should offer: device RAM minus a 1 GB reserve for
     * Android and the app's own native memory. On a 6 GB phone that is 5 GB; on
     * an 8 GB phone it is 7 GB. Capped at [MAX_HEAP_CAP_MB] for very large
     * devices and floored at [MIN_HEAP_MB] for tiny ones.
     */
    fun safeMaxHeapMb(totalRamMb: Long, lowRamDevice: Boolean): Int {
        if (totalRamMb <= 0L) {
            return if (lowRamDevice) 768 else DEFAULT_HEAP_MB
        }
        val usable = (totalRamMb - SYSTEM_RESERVE_MB)
            .coerceAtMost(MAX_HEAP_CAP_MB.toLong())
            .coerceAtLeast(MIN_HEAP_MB.toLong())
        val rounded = (usable / HEAP_STEP_MB.toLong()) * HEAP_STEP_MB.toLong()
        return rounded.coerceAtLeast(MIN_HEAP_MB.toLong()).toInt()
    }

    /**
     * "Auto memory" remains deliberately conservative (1.5 GiB on normal
     * devices); users who know their workload can raise the slider up to
     * [safeMaxHeapMb].
     */
    fun autoHeapMb(safeMaxMb: Int, lowRamDevice: Boolean): Int {
        val preferred = if (lowRamDevice) 768 else DEFAULT_HEAP_MB
        val safe = safeMaxMb.coerceAtLeast(MIN_HEAP_MB)
        val floor = minOf(512, safe)
        return preferred.coerceAtMost(safe).coerceAtLeast(floor)
    }

    fun isAboveSafe(heapMb: Int, safeMaxMb: Int): Boolean = heapMb > safeMaxMb
}
