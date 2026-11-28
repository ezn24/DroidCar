package io.github.ezn24.droidcar.carlife

import io.github.ezn24.droidcar.iap2.RouteGuidanceUpdate
import io.github.ezn24.droidcar.nav.NavInfo

/**
 * Turn-by-turn guidance for the CarLife cluster / HUD (`NAVI_NEXT_TURN_INFO`), fed from the same readings as the
 * iAP2 route guidance ([io.github.ezn24.droidcar.nav.CarRouteGuidance]). The car must have subscribed to navigation
 * (data subscription module 0). action 1 = start showing, 2 = update, 3 = navigation ended.
 * Field layout: action, nextTurn, roadName, totalDistance, remainDistance, turnIconData (Baidu's
 * CarlifeNaviNextTurnInfo; the same as FT's relay).
 */
class CarLifeNavigation(
    private val send: (ByteArray) -> Unit,
    private val module: (active: Boolean) -> Unit,
    private val icon: (NavInfo) -> ByteArray = { ByteArray(0) },
) {
    private var subscribed = false
    private var latest: NavInfo? = null
    private var shown: Key? = null
    private var total = 0
    private var active = false
    private var iconBytes: ByteArray? = null

    /** What identifies one maneuver on the HUD; distance and time changes are updates of the same one. */
    private data class Key(
        val turn: Int,
        val road: String,
        val remain: Int,
    )

    @Synchronized
    fun subscribe(on: Boolean) {
        subscribed = on
        publish()
    }

    @Synchronized
    fun update(info: NavInfo?) {
        latest = info
        publish()
    }

    @Synchronized
    fun reset() {
        subscribed = false
        latest = null
        shown = null
        total = 0
        active = false
        iconBytes = null
    }

    private fun publish() {
        val info = latest?.takeIf { subscribed }
        val remain = info?.toManeuver?.meters?.toInt()
        if (info == null || remain == null) {
            if (shown != null) send(payload(ACTION_END, TURN_ARRIVE, "", 0, 0, ByteArray(0)))
            shown = null
            total = 0
            iconBytes = null
            setActive(false)
            return
        }
        setActive(true)
        val turn = turnCode(info.maneuverType, info.junction)
        val road = info.afterRoad.ifBlank { info.instruction }
        val previous = shown
        val sameManeuver = previous != null && previous.turn == turn && previous.road == road
        if (!sameManeuver || remain > total) total = remain
        val key = Key(turn, road, remain)
        if (key == previous) return
        // The icon goes with every update (a receiver may clear it otherwise) but is drawn once per maneuver
        if (!sameManeuver || iconBytes == null) iconBytes = icon(info)
        send(payload(if (previous == null) ACTION_START else ACTION_UPDATE, turn, road, total, remain, iconBytes ?: ByteArray(0)))
        shown = key
    }

    private fun setActive(on: Boolean) {
        if (on == active) return
        active = on
        module(on)
    }

    companion object {
        const val ACTION_START = 1
        const val ACTION_UPDATE = 2
        const val ACTION_END = 3
        const val TURN_STRAIGHT = 1
        const val TURN_ARRIVE = 24

        fun payload(
            action: Int,
            turn: Int,
            road: String,
            total: Int,
            remain: Int,
            icon: ByteArray,
        ): ByteArray =
            ProtoWriter()
                .int32(1, action)
                .int32(2, turn)
                .string(3, road)
                .int32(4, total.coerceAtLeast(0))
                .int32(5, remain.coerceAtLeast(0))
                .bytes(6, icon)
                .toByteArray()

        /**
         * iAP2 ManeuverType -> Baidu turn code (1 straight, 2 right-front, 3 right, 4 right-back, 5 U-turn,
         * 6 left-back, 7 left, 8 left-front, 9 enter roundabout, 10 leave roundabout, 11 / 12 keep left / right,
         * 13 / 15 merge left / right into the main road, 18 / 19 left / right onto the side road, 23 start,
         * 24 arrive, 29 / 30 enter / leave ferry).
         */
        fun turnCode(
            type: Int?,
            junction: Int?,
        ): Int =
            when (type) {
                RouteGuidanceUpdate.TYPE_LEFT -> 7
                RouteGuidanceUpdate.TYPE_RIGHT -> 3
                RouteGuidanceUpdate.TYPE_STRAIGHT -> 1
                RouteGuidanceUpdate.TYPE_U_TURN -> 5
                RouteGuidanceUpdate.TYPE_ENTER_ROUNDABOUT -> 9
                RouteGuidanceUpdate.TYPE_EXIT_ROUNDABOUT -> 10
                RouteGuidanceUpdate.TYPE_OFF_RAMP, RouteGuidanceUpdate.TYPE_HIGHWAY_OFF_RAMP_RIGHT -> 19
                RouteGuidanceUpdate.TYPE_HIGHWAY_OFF_RAMP_LEFT -> 18
                RouteGuidanceUpdate.TYPE_ON_RAMP -> 15
                RouteGuidanceUpdate.TYPE_ARRIVE -> 24
                RouteGuidanceUpdate.TYPE_KEEP_LEFT, RouteGuidanceUpdate.TYPE_CHANGE_HIGHWAY_LEFT -> 11
                RouteGuidanceUpdate.TYPE_KEEP_RIGHT, RouteGuidanceUpdate.TYPE_CHANGE_HIGHWAY_RIGHT -> 12
                RouteGuidanceUpdate.TYPE_ENTER_FERRY -> 29
                RouteGuidanceUpdate.TYPE_EXIT_FERRY -> 30
                RouteGuidanceUpdate.TYPE_SHARP_LEFT -> 6
                RouteGuidanceUpdate.TYPE_SHARP_RIGHT -> 4
                RouteGuidanceUpdate.TYPE_SLIGHT_LEFT -> 8
                RouteGuidanceUpdate.TYPE_SLIGHT_RIGHT -> 2
                in RouteGuidanceUpdate.TYPE_ROUNDABOUT_EXIT_1..RouteGuidanceUpdate.TYPE_ROUNDABOUT_EXIT_19 -> 9
                else -> if (junction == RouteGuidanceUpdate.JUNCTION_ROUNDABOUT) 9 else TURN_STRAIGHT
            }
    }
}
