package io.github.ezn24.cardeck.neo.iap2

/**
 * iAP2 turn-by-turn data for the head unit's instrument cluster / HUD: the accessory subscribes with
 * 0x5200 StartRouteGuidanceUpdates (param 0 = its RouteGuidanceDisplayComponent IDs), the phone pushes
 * 0x5202 RouteGuidanceManeuverUpdate for each maneuver and 0x5201 RouteGuidanceUpdate for the route state, and the
 * accessory stops with 0x5203.
 *
 * Parameter IDs and value types follow a car-side parser of real iPhone traffic (WaylandAce/mib2q-carplay-rgi,
 * read for facts only):
 *   0x5201: 0 component IDs (u16 list), 1 RouteGuidanceState (u8), 2 ManeuverState (u8), 3 current road (utf8),
 *     4 destination (utf8), 5 ETA (u64 UTC epoch seconds), 6 time remaining (u64 s), 7 distance remaining (u32 m),
 *     8 its display string, 9 its display unit (u8), 10 distance to maneuver (u32 m), 11 string, 12 unit,
 *     13 maneuver list (u16 indexes), 14 maneuver count (u16), 15 visible in app (u8 bool)
 *   0x5202: 0 component IDs, 1 index (u16), 2 description (utf8), 3 ManeuverType (u8), 4 road after the maneuver
 *     (utf8), 5 distance between maneuvers (u32 m), 6 string, 7 unit, 8 driving side (u8, 0 = right-hand traffic),
 *     9 junction type (u8), 10 branch angles (repeated i16), 11 exit angle (i16 clockwise from straight)
 * Units: 0 km, 1 miles, 2 m, 3 yards, 4 feet. Fields we do not know are left out rather than guessed (a missing
 * maneuver type is better than a wrong arrow). (unverified on a real car)
 */
object RouteGuidanceUpdate {
    const val ROUTE_NO_ROUTE = 0
    const val ROUTE_SET = 1
    const val ROUTE_ARRIVED = 2
    const val ROUTE_REROUTING = 5

    const val MANEUVER_CONTINUE = 0
    const val MANEUVER_PREPARE = 2
    const val MANEUVER_EXECUTE = 3

    const val UNIT_KM = 0
    const val UNIT_MILES = 1
    const val UNIT_METERS = 2
    const val UNIT_YARDS = 3
    const val UNIT_FEET = 4

    const val JUNCTION_INTERSECTION = 0
    const val JUNCTION_ROUNDABOUT = 1

    // ManeuverType values used by navigation sources (subset of the iAP2 enum)
    const val TYPE_LEFT = 1
    const val TYPE_RIGHT = 2
    const val TYPE_STRAIGHT = 3
    const val TYPE_U_TURN = 4
    const val TYPE_ENTER_ROUNDABOUT = 6
    const val TYPE_EXIT_ROUNDABOUT = 7
    const val TYPE_OFF_RAMP = 8
    const val TYPE_ON_RAMP = 9
    const val TYPE_ARRIVE = 12
    const val TYPE_KEEP_LEFT = 13
    const val TYPE_KEEP_RIGHT = 14
    const val TYPE_ENTER_FERRY = 15
    const val TYPE_EXIT_FERRY = 16
    const val TYPE_CHANGE_FERRY = 17
    const val TYPE_HIGHWAY_OFF_RAMP_LEFT = 22
    const val TYPE_HIGHWAY_OFF_RAMP_RIGHT = 23
    const val TYPE_ROUNDABOUT_EXIT_1 = 28
    const val TYPE_ROUNDABOUT_EXIT_19 = 46
    const val TYPE_SHARP_LEFT = 47
    const val TYPE_SHARP_RIGHT = 48
    const val TYPE_SLIGHT_LEFT = 49
    const val TYPE_SLIGHT_RIGHT = 50
    const val TYPE_CHANGE_HIGHWAY = 51
    const val TYPE_CHANGE_HIGHWAY_LEFT = 52
    const val TYPE_CHANGE_HIGHWAY_RIGHT = 53

    fun junctionFor(type: Int?): Int? =
        when (type) {
            TYPE_ENTER_ROUNDABOUT, TYPE_EXIT_ROUNDABOUT, in TYPE_ROUNDABOUT_EXIT_1..TYPE_ROUNDABOUT_EXIT_19 -> JUNCTION_ROUNDABOUT
            TYPE_LEFT, TYPE_RIGHT, TYPE_SHARP_LEFT, TYPE_SHARP_RIGHT, TYPE_SLIGHT_LEFT, TYPE_SLIGHT_RIGHT,
            TYPE_KEEP_LEFT, TYPE_KEEP_RIGHT, TYPE_OFF_RAMP, TYPE_ON_RAMP, TYPE_HIGHWAY_OFF_RAMP_LEFT,
            TYPE_HIGHWAY_OFF_RAMP_RIGHT, TYPE_CHANGE_HIGHWAY, TYPE_CHANGE_HIGHWAY_LEFT, TYPE_CHANGE_HIGHWAY_RIGHT,
            -> JUNCTION_INTERSECTION
            else -> null
        }

    /** Direction for the receiver's generic arrow, without claiming a measured junction shape. */
    fun exitAngleFor(type: Int?): Int? =
        when (type) {
            TYPE_LEFT -> -90
            TYPE_RIGHT -> 90
            TYPE_SLIGHT_LEFT, TYPE_KEEP_LEFT, TYPE_HIGHWAY_OFF_RAMP_LEFT, TYPE_CHANGE_HIGHWAY_LEFT -> -45
            TYPE_SLIGHT_RIGHT, TYPE_KEEP_RIGHT, TYPE_HIGHWAY_OFF_RAMP_RIGHT, TYPE_CHANGE_HIGHWAY_RIGHT -> 45
            TYPE_SHARP_LEFT -> -135
            TYPE_SHARP_RIGHT -> 135
            else -> null
        }

    /** A distance as the navigation app shows it: meters for the value, plus its display string and unit. */
    data class Distance(
        val meters: Long,
        val text: String,
        val unit: Int,
    )

    data class Maneuver(
        val index: Int,
        val description: String = "",
        /** iAP2 ManeuverType, null = unknown (not sent). */
        val type: Int? = null,
        val afterRoad: String = "",
        /** iAP2 JunctionType (0 intersection, 1 roundabout), null = not sent. */
        val junction: Int? = null,
        val exitAngle: Int? = null,
        val junctionAngles: List<Int> = emptyList(),
    )

    /** Angles start at straight (0°). A null recommendation leaves the lane unhighlighted. */
    data class Lane(
        val directions: List<Int>,
        val preferredDirection: Int? = null,
    )

    data class Route(
        val state: Int,
        val maneuverState: Int = MANEUVER_CONTINUE,
        val currentRoad: String = "",
        val destination: String = "",
        val etaEpochSeconds: Long? = null,
        val timeRemainingSeconds: Long? = null,
        val remaining: Distance? = null,
        val toManeuver: Distance? = null,
        val maneuverIndexes: List<Int> = emptyList(),
        val laneIndex: Int? = null,
        val laneGuidanceShowing: Boolean? = null,
    )

    /** Display component IDs from 0x5200 param 0 (empty: the accessory named none, e.g. WinPlay). */
    fun parseComponents(body: ByteArray): List<Int> =
        runCatching { Iap2Csm.parseParams(body) }
            .getOrDefault(emptyList())
            .filter { it.id == 0 && it.value.size >= 2 && it.value.size % 2 == 0 }
            .flatMap { it.asU16List() }

    fun routeFrame(
        route: Route,
        components: List<Int>,
    ): ByteArray {
        val parts = ArrayList<ByteArray>()
        if (components.isNotEmpty()) parts += Iap2Csm.u16List(0, components.toIntArray())
        parts += Iap2Csm.u8(1, route.state)
        if (route.state == ROUTE_SET) {
            parts += Iap2Csm.u8(2, route.maneuverState)
            if (route.currentRoad.isNotEmpty()) parts += Iap2Csm.string(3, clean(route.currentRoad))
            if (route.destination.isNotEmpty()) parts += Iap2Csm.string(4, clean(route.destination))
            route.etaEpochSeconds?.let { parts += Iap2Csm.param(5, u64(it)) }
            route.timeRemainingSeconds?.let { parts += Iap2Csm.param(6, u64(it)) }
            route.remaining?.let {
                parts += Iap2Csm.u32(7, u32(it.meters))
                parts += Iap2Csm.string(8, clean(it.text))
                parts += Iap2Csm.u8(9, it.unit)
            }
            route.toManeuver?.let {
                parts += Iap2Csm.u32(10, u32(it.meters))
                parts += Iap2Csm.string(11, clean(it.text))
                parts += Iap2Csm.u8(12, it.unit)
            }
            if (route.maneuverIndexes.isNotEmpty()) parts += Iap2Csm.u16List(13, route.maneuverIndexes.toIntArray())
            parts += Iap2Csm.u16(14, route.maneuverIndexes.size)
            parts += Iap2Csm.u8(15, 1)
        }
        route.laneIndex?.let { parts += Iap2Csm.u16(0x10, it) }
        route.laneGuidanceShowing?.let { showing ->
            parts += Iap2Csm.u16(0x11, if (showing) 1 else 0)
            parts += Iap2Csm.u8(0x12, if (showing) 1 else 0)
        }
        return Iap2Csm.encode(Iap2MessageIds.ROUTE_GUIDANCE_UPDATE, parts.reduce { a, b -> a + b })
    }

    fun maneuverFrame(
        maneuver: Maneuver,
        components: List<Int>,
        toManeuver: Distance?,
    ): ByteArray {
        val parts = ArrayList<ByteArray>()
        if (components.isNotEmpty()) parts += Iap2Csm.u16List(0, components.toIntArray())
        parts += Iap2Csm.u16(1, maneuver.index)
        if (maneuver.description.isNotEmpty()) parts += Iap2Csm.string(2, clean(maneuver.description))
        maneuver.type?.let { parts += Iap2Csm.u8(3, it) }
        if (maneuver.afterRoad.isNotEmpty()) parts += Iap2Csm.string(4, clean(maneuver.afterRoad))
        toManeuver?.let {
            parts += Iap2Csm.u32(5, u32(it.meters))
            parts += Iap2Csm.string(6, clean(it.text))
            parts += Iap2Csm.u8(7, it.unit)
        }
        maneuver.junction?.let { parts += Iap2Csm.u8(9, it) }
        maneuver.junctionAngles.forEach { parts += Iap2Csm.u16(10, it) }
        maneuver.exitAngle?.let { parts += Iap2Csm.u16(11, it) }
        return Iap2Csm.encode(Iap2MessageIds.ROUTE_GUIDANCE_MANEUVER_UPDATE, parts.reduce { a, b -> a + b })
    }

    /** 0x5204 contains repeated lane groups in physical left-to-right order. */
    fun laneFrame(
        index: Int,
        lanes: List<Lane>,
        components: List<Int>,
    ): ByteArray {
        val parts = ArrayList<ByteArray>()
        if (components.isNotEmpty()) parts += Iap2Csm.u16List(0, components.toIntArray())
        parts += Iap2Csm.u16(1, index)
        lanes.forEachIndexed { position, lane ->
            val fields = ArrayList<ByteArray>()
            fields += Iap2Csm.u16(0, position)
            fields += Iap2Csm.u8(1, if (lane.preferredDirection != null) 2 else 0)
            for (angle in lane.directions) fields += Iap2Csm.u16(2, angle)
            fields += Iap2Csm.u16(3, lane.preferredDirection ?: 1000)
            parts += Iap2Csm.param(2, fields.reduce { a, b -> a + b })
        }
        return Iap2Csm.encode(Iap2MessageIds.ROUTE_GUIDANCE_LANE_GUIDANCE, parts.reduce { a, b -> a + b })
    }

    private fun clean(value: String): String {
        val noNul = value.replace(0.toChar(), ' ')
        var cut = noNul.take(MAX_STRING_BYTES)
        while (cut.encodeToByteArray().size > MAX_STRING_BYTES) cut = cut.dropLast(1)
        return cut
    }

    private fun u32(value: Long): Long = value.coerceIn(0L, 0xFFFF_FFFFL)

    private fun u64(value: Long): ByteArray = ByteArray(8) { (value ushr (56 - 8 * it)).toByte() }

    private const val MAX_STRING_BYTES = 255
}
