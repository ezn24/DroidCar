package io.github.ezn24.droidcar.iap2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-head-unit persistent memory of the MFi challenge length.
 *
 * Background (2026-10-19 Audi MMI 3315 measured): that head unit stays silent for 6 seconds on a 20B challenge, and only answers the signature in
 * 0.64 seconds on 32B. Different receivers have opposite preferences (DiPlay only accepts 32B, MMI 6318 accepts 20B), so
 * the memory must be stored separately per head-unit identity, and a cold start still needs a reasonable baseline.
 */
class MfiLengthMemoryTest {
    private val baselineCar = listOf(20, 32, 16) // Real car family: 20B first
    private val baselineTestBench = listOf(32, 20, 16) // Test bench: 32B first

    @Test
    fun receiverKeyIsStableAndFilesystemFriendly() {
        // name + model + manufacturer of 0x1D01 concatenated (with space and case differences)
        val a = MfiLengthMemory.receiverKey("audi_mmi_3315 audi 5163 delphi")
        val b = MfiLengthMemory.receiverKey("Audi_MMI_3315  AUDI 5163 DELPHI")
        assertEquals(a, b)
        assertTrue(a.matches(Regex("[a-z0-9_]+")))
        // Different head units must map to different keys (3315 and 6318 must not pollute each other's memory)
        assertFalse(a == MfiLengthMemory.receiverKey("audi_mmi_6318 audi 3260 delphi"))
    }

    @Test
    fun emptyMemoryKeepsBaselineOrder() {
        assertEquals(baselineCar, MfiLengthMemory.orderAttempts(baselineCar, MfiLengthMemory.Entry()))
        assertEquals(
            baselineTestBench,
            MfiLengthMemory.orderAttempts(baselineTestBench, MfiLengthMemory.Entry()),
        )
    }

    @Test
    fun rememberedSuccessGoesFirst() {
        // MMI 3315 remembered 32B success -> next round goes straight to 32B, no longer hitting 20B's 6s silence first
        val entry = MfiLengthMemory.Entry(succeeded = 32)
        assertEquals(listOf(32, 20, 16), MfiLengthMemory.orderAttempts(baselineCar, entry))
        // Same for the test bench scenario (32B is already first, order unchanged)
        assertEquals(listOf(32, 20, 16), MfiLengthMemory.orderAttempts(baselineTestBench, entry))
    }

    @Test
    fun silentAndRejectedLengthsSinkButStayAsFallback() {
        val entry = MfiLengthMemory.Entry(silent = setOf(20), rejected = setOf(16))
        val order = MfiLengthMemory.orderAttempts(baselineCar, entry)
        // The successful profile has none; 20B silent and 16B rejected both go last, 32B moves up
        assertEquals(listOf(32, 20, 16), order)
        // No profile is dropped: memory only affects order, it never makes a length permanently untried
        assertEquals(baselineCar.toSet(), order.toSet())
    }

    @Test
    fun allRejectedFallsBackToTryingEverything() {
        // Memory contradicts reality (firmware changed / a different car with the same identity): even if all were rejected, try as usual to avoid a permanent deadlock
        val entry = MfiLengthMemory.Entry(rejected = setOf(20, 32, 16))
        val order = MfiLengthMemory.orderAttempts(baselineCar, entry)
        assertEquals(baselineCar, order)
    }

    @Test
    fun onlySucceededLengthIsDroppedFromDuplicateCandidates() {
        val entry = MfiLengthMemory.Entry(succeeded = 20)
        val order = MfiLengthMemory.orderAttempts(baselineCar, entry)
        assertEquals(20, order.first())
        assertEquals(order.size, order.distinct().size)
    }

    @Test
    fun succeededAndRejectedContradictionDemotesMemory() {
        // 2026-10-24 DiPlay measured: a late reply wrongly recorded 20B as success, and then 20B got the link cut every round.
        // In the contradictory state (success AND rejected) 20B must no longer be first -- demoted into normal ordering
        val entry = MfiLengthMemory.Entry(succeeded = 20, rejected = setOf(20))
        val order = MfiLengthMemory.orderAttempts(baselineCar, entry)
        assertEquals("A hard-rejected profile must no longer be first", 32, order.first())
        assertTrue("A contradictory profile stays among candidates (firmware may change)", 20 in order)
    }

    @Test
    fun describeShowsWhatWasRemembered() {
        assertEquals("none", MfiLengthMemory.describe(MfiLengthMemory.Entry()))
        val text =
            MfiLengthMemory.describe(
                MfiLengthMemory.Entry(succeeded = 32, silent = setOf(20), rejected = setOf(16)),
            )
        assertTrue(text.contains("succeeded=32B"))
        assertTrue(text.contains("silent=20B"))
        assertTrue(text.contains("rejected=16B"))
    }
}
