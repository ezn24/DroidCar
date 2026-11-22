package io.github.ezn24.droidcar.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualDeviceRouteTest {
    @Test
    fun trustedCandidatesTryPublicFirst() {
        val candidates = VirtualDeviceRoute.trustedFlagCandidates
        // 1354 = PRESENTATION | OWN_CONTENT_ONLY | SUPPORTS_TOUCH | DESTROY_CONTENT_ON_REMOVAL | TRUSTED
        assertEquals(1354, candidates[1])
        assertEquals(1354 or VirtualDeviceRoute.FLAG_PUBLIC, candidates[0])
    }

    @Test
    fun dockJoinsTheDeviceGroupFirst() {
        val primary = VirtualDeviceRoute.dockFlagCandidates.first()
        assertTrue(primary and VirtualDeviceRoute.FLAG_TRUSTED != 0)
        assertEquals(0, primary and VirtualDeviceRoute.FLAG_PUBLIC)
        val fallback = VirtualDeviceRoute.dockFlagCandidates.last()
        assertEquals(0, fallback and VirtualDeviceRoute.FLAG_TRUSTED)
        assertTrue(fallback and VirtualDeviceRoute.FLAG_ALWAYS_UNLOCKED != 0)
        assertTrue(fallback and VirtualDeviceRoute.FLAG_OWN_DISPLAY_GROUP != 0)
    }

    @Test
    fun gateNeedsSdkAddressAndNoFailure() {
        assertTrue(VirtualDeviceRoute.shouldAttempt(34, "AA:BB:CC:DD:EE:FF", failedThisSession = false))
        assertFalse(VirtualDeviceRoute.shouldAttempt(33, "AA:BB:CC:DD:EE:FF", failedThisSession = false))
        assertFalse(VirtualDeviceRoute.shouldAttempt(37, null, failedThisSession = false))
        assertFalse(VirtualDeviceRoute.shouldAttempt(37, " ", failedThisSession = false))
        assertFalse(VirtualDeviceRoute.shouldAttempt(37, "AA:BB:CC:DD:EE:FF", failedThisSession = true))
    }

    @Test
    fun roleHoldersMatchWholeTokens() {
        assertTrue(VirtualDeviceRoute.roleHoldersContain("com.android.shell\n", "com.android.shell"))
        assertFalse(VirtualDeviceRoute.roleHoldersContain("com.android.shellx", "com.android.shell"))
        assertFalse(VirtualDeviceRoute.roleHoldersContain("", "com.android.shell"))
        // Samsung S24 prints the holders separated by semicolons
        assertTrue(VirtualDeviceRoute.roleHoldersContain("com.samsung.android.mdx;com.android.shell", "com.android.shell"))
    }
}
