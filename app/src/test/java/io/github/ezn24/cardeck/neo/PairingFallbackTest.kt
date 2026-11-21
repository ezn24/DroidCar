package io.github.ezn24.cardeck.neo

import io.github.ezn24.cardeck.neo.airplay.pairingRetryBackoffMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pairing-failure fallback path decision tests: all scenarios come from real-device sysdiagnose comparison
 * (2026-10-21/17, docs/PROTOCOL-NOTES.md section 13).
 */
class PairingFallbackTest {
    @Test
    fun `pairing success means no fallback`() {
        val f =
            pairingFallback(
                true,
                noPairing = false,
                pairSetupRejected = false,
                pairingForbidden = false,
                attemptedPairing = true,
                pairingUnsupported = false,
            )
        assertFalse(f.useBtRetireHack)
        assertFalse(f.usePlainSession)
    }

    @Test
    fun `Audi first-connect 403 - verify phase rejected - forbid BT drop and plaintext - back off in place and wait for re-invite`() {
        // Real-device capture: 403 in the auth phase after pair-verify passes (seen by the client as a verify/auth IOException)
        val f =
            pairingFallback(
                false,
                noPairing = false,
                pairSetupRejected = false,
                pairingForbidden = true,
                attemptedPairing = true,
                pairingUnsupported = false,
            )
        assertFalse("403 = head unit not ready; dropping BT would miss the automatic re-invite 18~20s later", f.useBtRetireHack)
        assertFalse("403 = trust already established, plaintext is certain to be rejected", f.usePlainSession)
    }

    @Test
    fun `pair-setup 403 - still fails after the fast path - likewise forbid BT drop`() {
        val f =
            pairingFallback(
                false,
                noPairing = false,
                pairSetupRejected = true,
                pairingForbidden = true,
                attemptedPairing = true,
                pairingUnsupported = false,
            )
        assertFalse(f.useBtRetireHack)
        assertFalse(f.usePlainSession)
    }

    @Test
    fun `DiPlay link-drop form - no 403 - keep the second-stage hack`() {
        val f =
            pairingFallback(
                false,
                noPairing = false,
                pairSetupRejected = false,
                pairingForbidden = false,
                attemptedPairing = true,
                pairingUnsupported = false,
            )
        assertTrue(f.useBtRetireHack)
        assertTrue(f.usePlainSession)
    }

    @Test
    fun `pre-HomeKit old head unit - pairing unsupported - plaintext fallback`() {
        val f =
            pairingFallback(
                false,
                noPairing = true,
                pairSetupRejected = false,
                pairingForbidden = false,
                attemptedPairing = false,
                pairingUnsupported = true,
            )
        assertFalse(f.useBtRetireHack)
        assertTrue(f.usePlainSession)
    }

    @Test
    fun `endpoint unresolved - RTSP not connected - neither fallback enabled`() {
        val f =
            pairingFallback(
                false,
                noPairing = false,
                pairSetupRejected = false,
                pairingForbidden = false,
                attemptedPairing = false,
                pairingUnsupported = false,
            )
        assertFalse(f.useBtRetireHack)
        assertFalse(f.usePlainSession)
    }

    @Test
    fun `retry backoff - long for 403 - short for the rest`() {
        assertEquals(5_000L, pairingRetryBackoffMs("RTSP/1.0 403 Forbidden"))
        assertEquals(5_000L, pairingRetryBackoffMs("HTTP/1.1 403"))
        assertEquals(500L, pairingRetryBackoffMs("RTSP/1.0 500 Internal"))
        assertEquals(500L, pairingRetryBackoffMs("Connection reset"))
    }
}
