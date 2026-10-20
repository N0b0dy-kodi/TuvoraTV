package androidx.media3.exoplayer.upstream

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DefaultAllocatorNativeTest {

    @Test
    fun testAllocationAndReleaseLifecycle() {
        val size = 65536
        // Allocate native memory
        val allocation = DefaultAllocatorNative.createAllocation(size)

        // Assert allocation is successful and direct
        assertNotNull("Allocation should not be null", allocation)
        assertNotNull("Allocation buffer should not be null", allocation!!.buffer)
        assertTrue("Allocation buffer must be direct", allocation.buffer!!.isDirect)
        assertEquals("Allocation buffer capacity should match size", size, allocation.buffer!!.capacity())
        assertTrue("Allocation native handle should be non-zero", allocation.nativeHandle != 0L)

        // Free allocation
        DefaultAllocatorNative.freeAllocation(allocation)

        // Verify that java-side handle is cleared immediately to prevent double-free
        assertEquals("Allocation handle should be cleared immediately", 0L, allocation.nativeHandle)

        // Let's also check allocating and freeing multiple buffers
        val allocation2 = DefaultAllocatorNative.createAllocation(32768)
        assertNotNull(allocation2)
        assertTrue(allocation2!!.buffer!!.isDirect)
        DefaultAllocatorNative.freeAllocation(allocation2)
    }

    @Test
    fun testAllocationZeroAndNegativeSize() {
        // Size 0 should return null
        val allocationZero = DefaultAllocatorNative.createAllocation(0)
        assertNull("Allocation with size 0 should be null", allocationZero)

        // Negative size should return null
        val allocationNeg = DefaultAllocatorNative.createAllocation(-100)
        assertNull("Allocation with negative size should be null", allocationNeg)
    }

    @Test
    fun testArenaPoolExhaustionAndReplenishment() {
        val segmentSize = 65536
        
        // Fetch arena field addresses via reflection
        val fieldBase = DefaultAllocatorNative::class.java.getDeclaredField("arenaBaseAddress")
        fieldBase.isAccessible = true
        
        val fieldEnd = DefaultAllocatorNative::class.java.getDeclaredField("arenaEndAddress")
        fieldEnd.isAccessible = true

        // Trigger initialization of the Arena by making one allocation
        val firstAlloc = DefaultAllocatorNative.createAllocation(segmentSize)
        assertNotNull(firstAlloc)
        DefaultAllocatorNative.freeAllocation(firstAlloc!!)

        val arenaBaseAddress = fieldBase.get(null) as Long
        val arenaEndAddress = fieldEnd.get(null) as Long

        org.junit.Assume.assumeTrue("Native arena must be available", arenaBaseAddress != 0L)
        val poolField = DefaultAllocatorNative::class.java.getDeclaredField("arenaPool").apply { isAccessible = true }
        val pool = poolField.get(null) as java.util.Queue<*>
        // Other allocators may legitimately retain released chunks in their own pools, and the
        // native arena can shrink on low-memory devices. Exercise the available shared pool;
        // assuming all 512 chunks are free makes this depend on which test ran before it.
        val available = pool.size
        val capacity = ((arenaEndAddress - arenaBaseAddress) / segmentSize).toInt()
        assertTrue("Arena must have available chunks", available in 1..capacity)
        val owned = mutableListOf<Allocation>()
        fun allocate(): Allocation = DefaultAllocatorNative.createAllocation(segmentSize)!!.also { owned += it }
        try {
            val arenaAllocations = (1..available).map {
                allocate().also { allocation ->
                    assertTrue("Available chunk must belong to arena", allocation.nativeHandle in arenaBaseAddress until arenaEndAddress)
                }
            }
            val dynamicAlloc = allocate()
            assertTrue("Overflow must use dynamic allocation", dynamicAlloc.nativeHandle !in arenaBaseAddress until arenaEndAddress)
            DefaultAllocatorNative.freeAllocation(arenaAllocations.first())
            val recycledAlloc = allocate()
            assertTrue("Freed arena chunk must be reusable", recycledAlloc.nativeHandle in arenaBaseAddress until arenaEndAddress)
        } finally {
            // Also clean up when an assertion fails, so one failure cannot poison later tests.
            owned.forEach { DefaultAllocatorNative.freeAllocation(it) }
        }

    }
}
