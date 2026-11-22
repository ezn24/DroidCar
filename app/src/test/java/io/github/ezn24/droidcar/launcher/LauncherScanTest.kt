package io.github.ezn24.droidcar.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Dedup/filtering of the app scan (regression pin for the 2026-10-23 user report "some apps were not scanned").
 *
 * The scene: the old implementation `queryIntentActivities(...).map{...}.take(200)` -- the limit was applied **before** sorting,
 * taking the first 200 in system return order. On phones with many apps installed, those later in the order simply vanished,
 * and which ones got dropped depended on the system return order, looking like "a random few missing".
 *
 * Measured data (Redmi K40): the system returns 83 launcher activities, 82 packages after dedup
 * (com.android.contacts has 4 Launcher activities under one package).
 */
class LauncherScanTest {
    @Test
    fun duplicateLauncherActivitiesOfSamePackageCollapse() {
        // Keep only one of multiple launcher activities per package (otherwise duplicate icons appear on the desktop)
        val result =
            dedupeLauncherPackages(
                listOf("com.android.contacts", "com.android.contacts", "com.android.contacts", "com.android.contacts", "com.zhihu.android"),
                selfPackage = "io.github.ezn24.droidcar",
            )
        assertEquals(listOf("com.android.contacts", "com.zhihu.android"), result)
    }

    @Test
    fun selfPackageIsExcluded() {
        val result =
            dedupeLauncherPackages(
                listOf("com.other.app", "io.github.ezn24.droidcar", "com.another.app"),
                selfPackage = "io.github.ezn24.droidcar",
            )
        assertEquals(listOf("com.other.app", "com.another.app"), result)
    }

    @Test
    fun noTruncationBeyondTwoHundredApps() {
        // The old implementation's take(200) would drop 50 here -- the root cause of "some apps not scanned"
        val many = (1..250).map { "com.example.app$it" }
        val result = dedupeLauncherPackages(many, selfPackage = "io.github.ezn24.droidcar")
        assertEquals("The app list must not be truncated (the old take(200) dropped the tail)", 250, result.size)
        assertEquals("com.example.app250", result.last())
    }

    @Test
    fun originalOrderIsPreserved() {
        // Order is decided by the caller's sort; this function must not reorder (otherwise pinyin sort keys would be misaligned)
        val result = dedupeLauncherPackages(listOf("c", "a", "b", "a"), selfPackage = "self")
        assertEquals(listOf("c", "a", "b"), result)
    }

    @Test
    fun emptyInputIsHandled() {
        assertEquals(emptyList<String>(), dedupeLauncherPackages(emptyList(), selfPackage = "self"))
    }
}
