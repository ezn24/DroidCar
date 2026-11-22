package io.github.ezn24.droidcar.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Default map resolution regression test.
 *
 * The focus is the "default map not set" path: in the old implementation `resolve()` called back in that branch to
 * `VirtualLauncher.defaultMapComponent()`, which in turn called `resolve()` again, forming infinite recursion
 * -> StackOverflowError. The symptom was the map card not rendering after uninstall and reinstall (triggered only when preferences were cleared), and
 * picking it again in settings recovered it -- easily misjudged as a virtual display/rendering issue (measured 2026-10-18).
 *
 * Package-name strings stand in for ComponentName here: `resolveDefaultMap` is a generic pure function,
 * and in the unit-test environment the Android class (ComponentName) methods are unimplemented empty stubs.
 */
class DefaultMapPrefsTest {
    private val priority = listOf("car.map", "phone.map", "baidu.map")

    /** Strings stand in for "installed app -> launchable component". */
    private fun pick(
        manual: String?,
        installed: Set<String>,
    ): String? =
        resolveDefaultMap(
            manual = manual,
            priority = priority,
            componentFor = { pkg -> if (pkg in installed) pkg else null },
        )

    /** Default map not set: take the first "installed" one in priority order (the old implementation overflowed the stack here). */
    @Test
    fun unsetFallsBackToPriorityOrder() {
        assertEquals("phone.map", pick(manual = null, installed = setOf("phone.map", "baidu.map")))
    }

    /** An explicit setting takes priority over the priority order. */
    @Test
    fun manualChoiceWins() {
        assertEquals("baidu.map", pick(manual = "baidu.map", installed = setOf("car.map", "baidu.map")))
    }

    /** The explicitly set app was uninstalled: fall back to the priority order, rather than returning null/crashing. */
    @Test
    fun uninstalledManualFallsBack() {
        assertEquals("car.map", pick(manual = "gone.map", installed = setOf("car.map")))
    }

    /** No map at all: return null (the caller shows the "map tile empty" hint). */
    @Test
    fun noneInstalledReturnsNull() {
        assertNull(pick(manual = null, installed = emptySet()))
    }

    /** Repeated resolution must terminate every time (the old implementation hit StackOverflowError). */
    @Test
    fun resolveTerminatesWithoutRecursion() {
        repeat(100) { assertNull(pick(manual = null, installed = emptySet())) }
    }
}

/**
 * Candidate order and fallback of the car-edition map launch entries (Amap/Baidu).
 *
 * Background: the LAUNCHER intent of car-edition maps often lands on the splash page rather than the main map UI, so the main Activity must be
 * specified explicitly; but **an explicit entry is not guaranteed to exist in all firmware/versions** (Baidu's car edition has both
 * NaviAutoActivity / SlashActivity across different customized firmware). Hence a candidate list is required:
 * explicit main UI first, falling back to the launcher resolution result on failure.
 */
class MapLaunchCandidatesTest {
    @Test
    fun gaodeCarEditionPrefersMainMapActivity() {
        assertEquals(
            listOf("com.autonavi.amapauto/.MainMapActivity", "com.autonavi.amapauto/.Launcher"),
            mapLaunchCandidates("com.autonavi.amapauto", "com.autonavi.amapauto/.Launcher"),
        )
    }

    @Test
    fun baiduCarEditionUsesLauncherEntryOnly() {
        // Baidu car edition (com.baidu.naviauto) has no explicit main-UI entry: measured 22.0.1 has
        // no map main-UI Activity in the package, the map is rendered by mapautosdk inside the launcher's DefaultActivity.
        // It was once wrongly written as .NaviAutoActivity (nonexistent), and am start reported directly
        // "Activity class does not exist" -- that candidate is no longer kept.
        assertEquals(
            listOf("com.baidu.naviauto/.SlashActivity.DefaultActivity"),
            mapLaunchCandidates("com.baidu.naviauto", "com.baidu.naviauto/.SlashActivity.DefaultActivity"),
        )
    }

    @Test
    fun phoneMapsFallBackToLauncherComponentOnly() {
        // The phone edition has no known dedicated main UI: keep only the launcher resolution result
        listOf("com.autonavi.minimap", "com.baidu.BaiduMap", "com.tencent.map").forEach { pkg ->
            assertEquals(listOf("$pkg/.Launcher"), mapLaunchCandidates(pkg, "$pkg/.Launcher"))
        }
    }

    @Test
    fun explicitEntryIsNotDuplicatedWhenItIsTheLauncher() {
        // When the launcher itself already points to the main UI, do not retry the same component
        assertEquals(
            listOf("com.baidu.naviauto/.NaviAutoActivity"),
            mapLaunchCandidates("com.baidu.naviauto", "com.baidu.naviauto/.NaviAutoActivity"),
        )
    }

    @Test
    fun baiduCarEditionIsWhitelistedAheadOfPhoneMaps() {
        val priority = VirtualLauncher.MAP_PACKAGES_PRIORITY
        val auto = priority.indexOf("com.baidu.naviauto")
        assertTrue("The Baidu car edition must be in the whitelist", auto >= 0)
        assertTrue(
            "The car edition should rank before the phone edition (on a head unit prefer the landscape-adapted car edition)",
            auto < priority.indexOf("com.baidu.BaiduMap"),
        )
        assertTrue(auto < priority.indexOf("com.autonavi.minimap"))
    }
}
