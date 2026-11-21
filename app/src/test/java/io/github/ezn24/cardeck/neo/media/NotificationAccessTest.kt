package io.github.ezn24.cardeck.neo.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Notification access determination (pure function): the system joins multiple listeners with `:` into one long string,
 * entries must be compared exactly -- `contains(packageName)` would be fooled by other apps with the "same prefix".
 */
class NotificationAccessTest {
    private val spec = "io.github.ezn24.cardeck.neo/io.github.ezn24.cardeck.neo.media.MediaListenerService"

    @Test
    fun detectsOwnComponentAmongOthers() {
        val enabled =
            "com.google.android.projection.gearhead/GearheadListenerService:" +
                "$spec:com.hihonor.health/HnNotificationListenerService"
        assertTrue(NotificationAccess.isGrantedIn(enabled, spec))
    }

    @Test
    fun detectsWhenOnlyEntry() {
        assertTrue(NotificationAccess.isGrantedIn(spec, spec))
    }

    @Test
    fun rejectsWhenAbsent() {
        assertFalse(NotificationAccess.isGrantedIn("com.other.app/OtherService", spec))
        assertFalse(NotificationAccess.isGrantedIn("", spec))
        assertFalse(NotificationAccess.isGrantedIn(null, spec))
    }

    @Test
    fun doesNotMatchByPrefixOnly() {
        // The old package name is another grant with the same prefix: it must not be judged granted just because the string contains it
        // (the trap most easily hit after renaming the package -- the old entry is still in the list, while the new package is actually not granted)
        val staleOldPackage =
            "io.github.ezn24.cardeck.neo/io.github.ezn24.cardeck.neo.media.OtherService"
        assertFalse(NotificationAccess.isGrantedIn(staleOldPackage, spec))
    }

    /**
     * "In the grant list" does not equal "bound by the system": after reinstall the enabled list still has this component,
     * but the Live section is missing -> metadata is always null and the card shows "cannot read playback info"
     * (measured 2026-10-19). Only the Live section may be trusted.
     */
    @Test
    fun liveSectionDecidesWhetherMetadataIsReadable() {
        val dump =
            """
            Notification listeners:
              Allowed notification listeners:
                $spec (user: 0 isPrimary: true)
              All notification listeners (2) enabled for current profiles:
                ComponentInfo{$spec}
              Live notification listeners (1):
                ComponentInfo{android/com.android.server.SystemNotificationListener} (user -1)
              Snoozed notification listeners (0):
            """.trimIndent()
        // In the grant list but not in the Live section -> still unbound (must trigger a rebind)
        assertFalse(NotificationAccess.isLiveIn(dump, spec))
    }

    @Test
    fun liveSectionIsDetectedOnceBound() {
        val dump =
            """
            Live notification listeners (2):
              ComponentInfo{android/com.android.server.SystemNotificationListener} (user -1)
              ComponentInfo{$spec} (user 0): android.service.notification.INotificationListener${'$'}Stub${'$'}Proxy@abc
            Snoozed notification listeners (0):
            """.trimIndent()
        assertTrue(NotificationAccess.isLiveIn(dump, spec))
    }

    @Test
    fun missingLiveSectionIsNotTreatedAsBound() {
        assertFalse(NotificationAccess.isLiveIn("no such section", spec))
    }
}
