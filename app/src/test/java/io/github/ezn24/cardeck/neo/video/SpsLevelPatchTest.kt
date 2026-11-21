package io.github.ezn24.cardeck.neo.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SPS level_idc patch (the fix for 2026-10-22 Audi 0628 "streaming fine but head unit screen black").
 *
 * Background: MediaFormat.KEY_LEVEL was measured to be silently ignored (the MagicOS encoder neither throws nor takes effect),
 * at the default 30fps 1412x654 legally falls into level 3.1/3.2 -- the Audi DELPHI decoder silently does not render for that level.
 * Historically every successful real-car session was level 4.0 (same as a real iPhone).
 */
class SpsLevelPatchTest {
    /** Builds a minimal SPS: NAL header (1B) + profile (1B) + compat (1B) + level (1B) + tail. */
    private fun spsOf(level: Int) = byteArrayOf(0x67, 0x64, 0x00, level.toByte(), 0x11, 0x22)

    @Test
    fun levelBelow40IsRaised() {
        // Both 3.1 (31) and 3.2 (32) should be patched to 40
        for (level in listOf(31, 32)) {
            val patched = patchSpsLevel(spsOf(level))
            assertEquals(40, patched[3].toInt() and 0xFF)
            // Other bytes are untouched
            assertEquals(0x64, patched[1].toInt() and 0xFF)
            assertEquals(6, patched.size)
        }
    }

    @Test
    fun levelAtOrAbove40IsUntouched() {
        // 4.0 (40) and 4.2 (42) keep their original value -- no downgrade
        for (level in listOf(40, 41, 42)) {
            val sps = spsOf(level)
            assertTrue(patchSpsLevel(sps).contentEquals(sps))
        }
    }

    @Test
    fun malformedSpsIsPassedThrough() {
        // A too-short SPS (missing the level byte) is returned as-is, no throw
        assertTrue(patchSpsLevel(byteArrayOf(0x67)).size == 1)
    }

    @Test
    fun nonSpsPayloadUntouched() {
        // Already 40 is left unchanged (even if other bytes look like an SPS)
        val sps = spsOf(40)
        val out = patchSpsLevel(sps)
        assertEquals(sps[3], out[3])
    }
}
