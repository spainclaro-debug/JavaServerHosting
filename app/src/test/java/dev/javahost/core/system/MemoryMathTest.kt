package dev.javahost.core.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the child-JVM heap heuristics. No Android classes are
 * touched, so these run on any host without a device or Robolectric.
 */
class MemoryMathTest {

    @Test
    fun `slider maximum is device RAM minus one GB`() {
        // ActivityManager reports roughly these totals for 6/8/12 GB phones.
        assertEquals(5120, MemoryMath.safeMaxHeapMb(6144L, lowRamDevice = false))
        assertEquals(7168, MemoryMath.safeMaxHeapMb(8192L, lowRamDevice = false))
        assertEquals(11264, MemoryMath.safeMaxHeapMb(12288L, lowRamDevice = false))
        assertEquals(3072, MemoryMath.safeMaxHeapMb(4096L, lowRamDevice = false))
    }

    @Test
    fun `small and low-RAM devices still leave room for Android`() {
        assertEquals(2048, MemoryMath.safeMaxHeapMb(3072L, lowRamDevice = false))
        assertEquals(1024, MemoryMath.safeMaxHeapMb(2048L, lowRamDevice = true))
        assertEquals(384, MemoryMath.safeMaxHeapMb(1024L, lowRamDevice = true))
    }

    @Test
    fun `unknown total RAM falls back conservatively`() {
        assertEquals(1536, MemoryMath.safeMaxHeapMb(0L, lowRamDevice = false))
        assertEquals(768, MemoryMath.safeMaxHeapMb(0L, lowRamDevice = true))
    }

    @Test
    fun `safe heap always leaves about a gigabyte for the system`() {
        val cases = listOf(2048L, 3072L, 4096L, 6144L, 8192L, 12288L, 16384L)
        for (total in cases) {
            val safe = MemoryMath.safeMaxHeapMb(total, lowRamDevice = false)
            assertTrue("heap must be positive for $total", safe > 0)
            assertTrue("heap $safe must be >= MIN_HEAP_MB", safe >= MemoryMath.MIN_HEAP_MB)
            assertTrue(
                "heap $safe must leave at least 1 GB of $total for the system",
                safe <= total - MemoryMath.SYSTEM_RESERVE_MB
            )
            assertEquals("heap must be a 128 MB step", 0, safe % 128)
        }
    }

    @Test
    fun `safe heap is capped on very large devices`() {
        assertEquals(MemoryMath.MAX_HEAP_CAP_MB, MemoryMath.safeMaxHeapMb(100_000L, lowRamDevice = false))
    }

    @Test
    fun `auto heap prefers 1536 MB and never exceeds the safe maximum`() {
        assertEquals(1536, MemoryMath.autoHeapMb(2048, lowRamDevice = false))
        assertEquals(1536, MemoryMath.autoHeapMb(5120, lowRamDevice = false))
        assertEquals(1075, MemoryMath.autoHeapMb(1075, lowRamDevice = false))
    }

    @Test
    fun `auto heap is conservative on low-RAM devices`() {
        assertEquals(716, MemoryMath.autoHeapMb(716, lowRamDevice = true))
        assertEquals(384, MemoryMath.autoHeapMb(384, lowRamDevice = true))
    }

    @Test
    fun `isAboveSafe compares against the safe maximum`() {
        assertTrue(MemoryMath.isAboveSafe(5121, 5120))
        assertFalse(MemoryMath.isAboveSafe(5120, 5120))
        assertFalse(MemoryMath.isAboveSafe(1024, 5120))
    }
}
