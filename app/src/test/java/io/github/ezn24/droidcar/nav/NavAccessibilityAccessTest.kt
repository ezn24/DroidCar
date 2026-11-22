package io.github.ezn24.droidcar.nav

import org.junit.Assert.assertEquals
import org.junit.Test

class NavAccessibilityAccessTest {
    private val ours = "io.github.ezn24.droidcar/io.github.ezn24.droidcar.nav.NavAccessibilityService"
    private val others = "com.arlosoft.macrodroid/a.B:li.songe.gkd/c.D"

    @Test
    fun addKeepsOtherServicesAndDoesNotDuplicate() {
        assertEquals("$others:$ours", NavAccessibilityAccess.withEntry(others, ours))
        assertEquals("$others:$ours", NavAccessibilityAccess.withEntry("$others:$ours", ours))
        assertEquals(ours, NavAccessibilityAccess.withEntry("null", ours))
        assertEquals(ours, NavAccessibilityAccess.withEntry(null, ours))
    }

    @Test
    fun removeTouchesOnlyOurEntry() {
        assertEquals(others, NavAccessibilityAccess.withoutEntry("com.arlosoft.macrodroid/a.B:$ours:li.songe.gkd/c.D", ours))
        assertEquals("", NavAccessibilityAccess.withoutEntry(ours, ours))
    }
}
