package io.github.ezn24.droidcar.nav

import io.github.ezn24.droidcar.iap2.Iap2Csm
import io.github.ezn24.droidcar.iap2.Iap2MessageIds
import io.github.ezn24.droidcar.iap2.RouteGuidanceUpdate
import io.github.ezn24.droidcar.iap2.asString
import io.github.ezn24.droidcar.iap2.asU16List
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class NavTextTest {
    @Test
    fun distancesInBothLanguages() {
        assertEquals(60L, NavText.distance("60 m")!!.meters)
        assertEquals(RouteGuidanceUpdate.UNIT_METERS, NavText.distance("60 m")!!.unit)
        assertEquals(1200L, NavText.distance("1.2 km")!!.meters)
        // Number only: the unit travels in its own field and receivers append it
        assertEquals("1.2", NavText.distance("1.2 km")!!.text)
        assertEquals(RouteGuidanceUpdate.UNIT_KM, NavText.distance("1,2 km")!!.unit)
        assertEquals(200L, NavText.distance("200米后")!!.meters)
        assertEquals(1500L, NavText.distance("剩余1.5公里")!!.meters)
        assertEquals(RouteGuidanceUpdate.UNIT_MILES, NavText.distance("0.3 mi")!!.unit)
        assertEquals(152L, NavText.distance("500 ft")!!.meters)
        // "min" is a duration, not miles / meters
        assertNull(NavText.distance("4 min"))
    }

    @Test
    fun durations() {
        assertEquals(240L, NavText.duration("4 min"))
        assertEquals(3900L, NavText.duration("1 hr 5 min"))
        assertEquals(1500L, NavText.duration("25分钟"))
        assertEquals(3900L, NavText.duration("1小時5分鐘"))
        assertNull(NavText.duration("960 m"))
    }

    @Test
    fun clockIsTodayOrTomorrow() {
        val now = Calendar.getInstance().apply { set(2026, 10, 21, 16, 49, 0) }.timeInMillis
        val eta = NavText.clock("16:53", now)!!
        assertEquals(4 * 60L, eta - now / 1000 + 0) // 4 minutes ahead (seconds truncated to the minute)
        val pm = NavText.clock("下午4:53", now)!!
        assertEquals(eta, pm)
        val tomorrow = NavText.clock("00:10", now)!!
        assertTrue(tomorrow > now / 1000)
    }

    @Test
    fun maneuverWords() {
        assertEquals(RouteGuidanceUpdate.TYPE_LEFT, NavText.maneuver("Turn left onto Bourke St"))
        assertEquals(RouteGuidanceUpdate.TYPE_RIGHT, NavText.maneuver("右转进入中山路"))
        assertEquals(RouteGuidanceUpdate.TYPE_SLIGHT_RIGHT, NavText.maneuver("向右前方行驶"))
        assertEquals(RouteGuidanceUpdate.TYPE_U_TURN, NavText.maneuver("前方掉頭"))
        assertEquals(RouteGuidanceUpdate.TYPE_ROUNDABOUT_EXIT_1 + 1, NavText.maneuver("进入环岛，第2出口"))
        assertEquals(RouteGuidanceUpdate.TYPE_KEEP_LEFT, NavText.maneuver("Keep left"))
        assertEquals(RouteGuidanceUpdate.TYPE_HIGHWAY_OFF_RAMP_RIGHT, NavText.maneuver("Keep right to take the exit"))
        assertEquals(RouteGuidanceUpdate.TYPE_ON_RAMP, NavText.maneuver("向右前方駛入高速"))
        assertEquals(RouteGuidanceUpdate.TYPE_ENTER_FERRY, NavText.maneuver("搭乘渡輪"))
        assertEquals(RouteGuidanceUpdate.TYPE_EXIT_ROUNDABOUT, NavText.maneuver("Exit the roundabout"))
        assertNull(NavText.maneuver("Bourke St"))
        // Google Maps (zh-TW): head-straight phrasing
        assertEquals(RouteGuidanceUpdate.TYPE_STRAIGHT, NavText.maneuver("朝Lonsdale St前進"))
        assertEquals("Lonsdale St", NavText.afterRoad("朝Lonsdale St前進"))
    }

    @Test
    fun genericScreenTexts() {
        val now = System.currentTimeMillis()
        val info =
            NavText.fromTexts(
                "com.autonavi.minimap",
                listOf("200米", "右转进入中山路", "剩余 12.3公里 25分钟", "预计 16:20 到达"),
                now,
            )!!
        assertEquals(200L, info.toManeuver!!.meters)
        assertEquals(RouteGuidanceUpdate.TYPE_RIGHT, info.maneuverType)
        assertEquals("中山路", info.afterRoad)
        assertEquals(12300L, info.remaining!!.meters)
        assertEquals(1500L, info.timeRemainingSeconds)
        assertNotNull(info.etaEpochSeconds)
    }

    @Test
    fun nothingNavigationLikeGivesNull() {
        assertNull(NavText.fromTexts("x", listOf("Search", "Settings"), 0))
    }

    @Test
    fun routeAndManeuverFramesCarryTheFields() {
        val distance = RouteGuidanceUpdate.Distance(60, "60", RouteGuidanceUpdate.UNIT_METERS)
        val maneuver =
            RouteGuidanceUpdate.maneuverFrame(
                RouteGuidanceUpdate.Maneuver(
                    7,
                    "Turn left",
                    RouteGuidanceUpdate.TYPE_LEFT,
                    "Bourke St",
                    RouteGuidanceUpdate.junctionFor(RouteGuidanceUpdate.TYPE_LEFT),
                    RouteGuidanceUpdate.exitAngleFor(RouteGuidanceUpdate.TYPE_LEFT),
                ),
                listOf(42),
                distance,
            )
        assertEquals(
            Iap2MessageIds.ROUTE_GUIDANCE_MANEUVER_UPDATE,
            ((maneuver[4].toInt() and 0xFF) shl 8) or (maneuver[5].toInt() and 0xFF),
        )
        val m = Iap2Csm.parseParams(maneuver.copyOfRange(6, maneuver.size)).associateBy { it.id }
        assertEquals(listOf(42), m[0]!!.asU16List())
        assertEquals(listOf(7), m[1]!!.asU16List())
        assertEquals(RouteGuidanceUpdate.TYPE_LEFT, m[3]!!.value.single().toInt())
        assertEquals("Bourke St", m[4]!!.asString())
        assertEquals(RouteGuidanceUpdate.JUNCTION_INTERSECTION, m[9]!!.value.single().toInt())
        assertEquals(listOf(0xFFA6), m[11]!!.asU16List())
        val roundabout = RouteGuidanceUpdate.TYPE_ROUNDABOUT_EXIT_1
        assertEquals(RouteGuidanceUpdate.JUNCTION_ROUNDABOUT, RouteGuidanceUpdate.junctionFor(roundabout))
        assertNull(RouteGuidanceUpdate.exitAngleFor(roundabout))

        val route =
            RouteGuidanceUpdate.routeFrame(
                RouteGuidanceUpdate.Route(
                    state = RouteGuidanceUpdate.ROUTE_SET,
                    etaEpochSeconds = 1_800_000_000L,
                    toManeuver = distance,
                    maneuverIndexes = listOf(7),
                ),
                emptyList(),
            )
        val r = Iap2Csm.parseParams(route.copyOfRange(6, route.size)).associateBy { it.id }
        assertNull("no components when the accessory named none", r[0])
        assertEquals(RouteGuidanceUpdate.ROUTE_SET, r[1]!!.value.single().toInt())
        assertEquals(8, r[5]!!.value.size)
        assertEquals("60", r[11]!!.asString())
        assertEquals(listOf(7), r[13]!!.asU16List())

        val none = RouteGuidanceUpdate.routeFrame(RouteGuidanceUpdate.Route(RouteGuidanceUpdate.ROUTE_NO_ROUTE), emptyList())
        assertEquals(listOf(1), Iap2Csm.parseParams(none.copyOfRange(6, none.size)).map { it.id })
    }

    @Test
    fun uTurnExitAngleSurvivesTheSourceMergeAndWireEncoding() {
        val source = NavInfo("map", maneuverType = RouteGuidanceUpdate.TYPE_U_TURN, afterRoad = "Main St")
        val reading = source.withIcon(ManeuverIcon.Result(RouteGuidanceUpdate.TYPE_U_TURN, 0, 179, exitAngle = -180))
        val merged = CarRouteGuidance.merge(reading, source)
        assertEquals(-180, merged.exitAngle)
        val maneuver = RouteGuidanceUpdate.Maneuver(7, type = merged.maneuverType, exitAngle = merged.exitAngle)
        val frame = RouteGuidanceUpdate.maneuverFrame(maneuver, emptyList(), null)
        val exit = Iap2Csm.parseParams(frame.copyOfRange(6, frame.size)).first { it.id == 11 }
        assertEquals(listOf(0xFF4C), exit.asU16List())
        assertNull(CarRouteGuidance.merge(merged, source.copy(afterRoad = "Second St")).exitAngle)
    }

    @Test
    fun forkSendsBothBranchesAndTheSelectedExit() {
        val maneuver =
            RouteGuidanceUpdate.Maneuver(
                7,
                type = RouteGuidanceUpdate.TYPE_KEEP_LEFT,
                exitAngle = -30,
                junctionAngles = listOf(-30, 30),
            )
        val frame = RouteGuidanceUpdate.maneuverFrame(maneuver, emptyList(), null)
        val fields = Iap2Csm.parseParams(frame.copyOfRange(6, frame.size))
        assertEquals(listOf(0xFFE2, 30), fields.filter { it.id == 10 }.flatMap { it.asU16List() })
        assertEquals(listOf(0xFFE2), fields.first { it.id == 11 }.asU16List())
    }

    @Test
    fun laneFramesPreserveOrderAndSeparateAllowedFromPreferredDirections() {
        val lanes =
            listOf(
                RouteGuidanceUpdate.Lane(listOf(0, -90), preferredDirection = -90),
                RouteGuidanceUpdate.Lane(listOf(0)),
                RouteGuidanceUpdate.Lane(listOf(90)),
            )
        val frame = RouteGuidanceUpdate.laneFrame(9, lanes, listOf(42))
        assertEquals(0x04, frame[5].toInt())
        val params = Iap2Csm.parseParams(frame.copyOfRange(6, frame.size))
        assertEquals(listOf(42), params.first { it.id == 0 }.asU16List())
        assertEquals(listOf(9), params.first { it.id == 1 }.asU16List())
        val groups = params.filter { it.id == 2 }.map { Iap2Csm.parseParams(it.value) }
        assertEquals(listOf(0, 1, 2), groups.map { group -> group.first { it.id == 0 }.asU16List().single() })
        assertEquals(listOf(0, 0xFFA6), groups[0].filter { it.id == 2 }.flatMap { it.asU16List() })
        assertEquals(listOf(0xFFA6), groups[0].first { it.id == 3 }.asU16List())
        assertEquals(listOf(1000), groups[1].first { it.id == 3 }.asU16List())

        val active = RouteGuidanceUpdate.Route(RouteGuidanceUpdate.ROUTE_SET, laneIndex = 9, laneGuidanceShowing = true)
        val waiting = active.copy(laneIndex = null, laneGuidanceShowing = false)
        for ((state, showing) in listOf(active to 1, waiting to 0)) {
            val route = RouteGuidanceUpdate.routeFrame(state, listOf(42))
            val fields = Iap2Csm.parseParams(route.copyOfRange(6, route.size)).associateBy { it.id }
            assertEquals(state.laneIndex, fields[0x10]?.asU16List()?.single())
            assertEquals(showing, fields[0x11]!!.asU16List().single())
            assertEquals(showing, fields[0x12]!!.value.single().toInt())
        }

        val current = NavInfo("map", lanes = lanes)
        assertTrue(CarRouteGuidance.merge(current, current.copy(lanes = emptyList())).lanes.isEmpty())
    }

    @Test
    fun alternatingMapsNotificationKeepsOneManeuver() {
        val now = System.currentTimeMillis()
        val src = "com.google.android.apps.maps"
        val withDistance = NavText.fromTexts(src, listOf("140 公尺", "向左轉進入Lonsdale St"), now)!!
        val withEta = NavText.fromTexts(src, listOf("向左轉進入Lonsdale St", "04:13 抵達"), now)!!
        val merged = CarRouteGuidance.merge(withDistance, withEta)
        assertTrue(CarRouteGuidance.sameManeuver(withDistance, withEta))
        assertEquals(140L, merged.toManeuver!!.meters)
        assertNotNull(merged.etaEpochSeconds)
        assertEquals("Lonsdale St", merged.afterRoad)
        // A different road is a new maneuver: its distance is not inherited
        val next = NavText.fromTexts(src, listOf("向右轉進入Collins St"), now)!!
        assertNull(CarRouteGuidance.merge(merged, next).toManeuver)
        // The arrow picture fills the type in later: still the same maneuver, and a reading without it keeps it
        val amap = NavInfo(source = "com.autonavi.minimap", afterRoad = "Flinders St")
        val typed = amap.copy(maneuverType = RouteGuidanceUpdate.TYPE_LEFT)
        assertTrue(CarRouteGuidance.sameManeuver(amap, typed))
        assertEquals(RouteGuidanceUpdate.TYPE_LEFT, CarRouteGuidance.merge(typed, amap).maneuverType)
    }

    @Test
    fun amapCarProjectionScreen() {
        // Boxes and texts from a real NAV-DUMP of Amap on the CarPlayMap display (2026-11-21)
        val texts =
            listOf(
                ScreenText("40", 796, 644, 834, 670),
                ScreenText("Spencer St", 840, 645, 948, 670),
                ScreenText("避開前路", 219, 678, 292, 688),
                ScreenText("<font size='42' font-weight='medium' face='AmapNumber-Medium'>15.8</font><img src='km'/>", 216, 599, 319, 629),
                ScreenText("<font size='42'>28</font><img src='richtext_space.png'/><font size='24'>分鐘</font>", 121, 599, 204, 629),
                // Traffic-light count: a bare number, must not be read as minutes
                ScreenText("58", 355, 599, 388, 629),
                ScreenText("04:39", 196, 629, 264, 659),
                ScreenText("到達", 266, 629, 314, 659),
                ScreenText("22", 93, 35, 153, 99),
                ScreenText("m", 155, 48, 186, 98),
                ScreenText("Lonsdale St", 202, 55, 345, 93),
                ScreenText("手機衛星訊號弱", 347, 33, 460, 56),
            )
        val info = NavText.fromAmapScreen("com.autonavi.minimap", texts, System.currentTimeMillis())!!
        assertEquals(22L, info.toManeuver!!.meters)
        assertEquals("Lonsdale St", info.afterRoad)
        assertEquals("Spencer St", info.currentRoad)
        assertEquals(15800L, info.remaining!!.meters)
        assertEquals(28 * 60L, info.timeRemainingSeconds)
        assertNotNull(info.etaEpochSeconds)
        assertNull(info.maneuverType)
    }

    @Test
    fun componentsFromStartRequest() {
        assertEquals(listOf(42), RouteGuidanceUpdate.parseComponents(Iap2Csm.u16(0, 42) + Iap2Csm.param(1, ByteArray(0))))
        assertEquals(emptyList<Int>(), RouteGuidanceUpdate.parseComponents(ByteArray(0)))
    }

    @Test
    fun googleLiveUpdateFieldsFillDistanceRoadAndRemaining() {
        // Title from the 2026-11-21 drive omits the distance on "head toward" legs
        val parsed = NavText.fromTexts("com.google.android.apps.maps", listOf("朝Lonsdale St前進", "17:51 抵達"), System.currentTimeMillis())!!
        assertNull(parsed.toManeuver)
        val live = NavText.withLiveUpdate(parsed, "3.7 公里", "E4 號出口 Toorak Rd", 14_914L)
        assertEquals(3700L, live.toManeuver!!.meters)
        assertEquals("Lonsdale St", live.afterRoad)
        assertEquals(14_914L, live.remaining!!.meters)
        assertEquals("14.9", live.remaining!!.text)
        // Without a road in the title, Samsung's secondary info is used
        val bare = NavText.withLiveUpdate(parsed.copy(afterRoad = ""), null, "E4 號出口 Toorak Rd", null)
        assertEquals("E4 號出口 Toorak Rd", bare.afterRoad)
        assertNull(bare.remaining)
    }
}
