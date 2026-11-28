package io.github.ezn24.droidcar.carlife

import io.github.ezn24.droidcar.iap2.RouteGuidanceUpdate
import io.github.ezn24.droidcar.nav.NavInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarLifeNavigationTest {
    private fun info(
        meters: Long,
        type: Int = RouteGuidanceUpdate.TYPE_LEFT,
        road: String = "Main St",
    ) = NavInfo(
        source = "test",
        toManeuver = RouteGuidanceUpdate.Distance(meters, meters.toString(), RouteGuidanceUpdate.UNIT_METERS),
        maneuverType = type,
        afterRoad = road,
    )

    @Test fun startsUpdatesAndEndsWithModuleStatus() {
        val sent = ArrayList<ProtoReader>()
        val module = ArrayList<Boolean>()
        val nav = CarLifeNavigation({ sent += ProtoReader(it) }, { module += it })
        nav.subscribe(true)
        nav.update(info(300))
        nav.update(info(120))
        nav.update(null)
        assertEquals(listOf(1, 2, 3), sent.map { it.int(1, 0) })
        assertEquals(7, sent[0].int(2, 0))
        assertEquals("Main St", sent[0].string(3))
        assertEquals(300, sent[1].int(4, 0)) // total stays the distance the maneuver started at
        assertEquals(120, sent[1].int(5, 0))
        assertEquals(listOf(true, false), module)
    }

    @Test fun nothingIsSentWithoutSubscription() {
        val sent = ArrayList<ByteArray>()
        val nav = CarLifeNavigation({ sent += it }, {})
        nav.update(info(300))
        assertTrue(sent.isEmpty())
    }

    @Test fun turnCodesFollowBaiduTable() {
        assertEquals(3, CarLifeNavigation.turnCode(RouteGuidanceUpdate.TYPE_RIGHT, null))
        assertEquals(5, CarLifeNavigation.turnCode(RouteGuidanceUpdate.TYPE_U_TURN, null))
        assertEquals(24, CarLifeNavigation.turnCode(RouteGuidanceUpdate.TYPE_ARRIVE, null))
        assertEquals(9, CarLifeNavigation.turnCode(null, RouteGuidanceUpdate.JUNCTION_ROUNDABOUT))
    }
}
