package io.github.ezn24.cardeck.neo.nav

import io.github.ezn24.cardeck.neo.iap2.RouteGuidanceUpdate
import io.github.ezn24.cardeck.neo.iap2.RouteGuidanceUpdate.Distance
import java.util.Calendar

/** One visible text of a navigation screen with its on-screen box (pixels of its display). */
data class ScreenText(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val height: Int get() = bottom - top
    val centerY: Int get() = (top + bottom) / 2
}

/** What a navigation app currently shows, independent of where it was read from (screen text or notification). */
data class NavInfo(
    val source: String,
    val toManeuver: Distance? = null,
    val instruction: String = "",
    /** iAP2 ManeuverType; null = could not tell (Waze draws the arrow as an image only). */
    val maneuverType: Int? = null,
    /** iAP2 JunctionType (0 intersection, 1 roundabout); null = unknown. */
    val junction: Int? = null,
    val afterRoad: String = "",
    val currentRoad: String = "",
    val remaining: Distance? = null,
    val timeRemainingSeconds: Long? = null,
    val etaEpochSeconds: Long? = null,
    val lanes: List<RouteGuidanceUpdate.Lane> = emptyList(),
    val exitAngle: Int? = null,
    val junctionAngles: List<Int> = emptyList(),
)

/**
 * Pure parsing of navigation text in English and Chinese (simplified and traditional). Map apps word things
 * differently and change wording between versions, so everything here is tolerant: a field that does not parse is
 * left null and simply not sent to the head unit.
 */
object NavText {
    private val distanceRegex =
        Regex(
            """(\d+(?:[.,]\d+)?)\s*(km|公里|千米|公尺|米|m|miles?|mi|ft|feet|yd|yards?)(?![a-zA-Z])""",
            RegexOption.IGNORE_CASE,
        )

    /** First distance in [text], e.g. "60 m", "1.2 km", "200米", "1.5公里", "0.3 mi", "500 ft". */
    fun distance(text: String): Distance? {
        val match = distanceRegex.find(text) ?: return null
        val value = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val (meters, unit) =
            when (match.groupValues[2].lowercase()) {
                "km", "公里", "千米" -> value * 1000 to RouteGuidanceUpdate.UNIT_KM
                "mi", "mile", "miles" -> value * 1609.344 to RouteGuidanceUpdate.UNIT_MILES
                "ft", "feet" -> value * 0.3048 to RouteGuidanceUpdate.UNIT_FEET
                "yd", "yard", "yards" -> value * 0.9144 to RouteGuidanceUpdate.UNIT_YARDS
                else -> value to RouteGuidanceUpdate.UNIT_METERS
            }
        // The display string is the number only, as an iPhone sends it: receivers append the unit field themselves
        // (ChairPlay's HUD showed "809 m m" while the unit was part of the string).
        return Distance(meters.toLong(), match.groupValues[1], unit)
    }

    /** True when [text] is essentially only a distance (the big "60 m" / "200米" label of a maneuver card). */
    fun isBareDistance(text: String): Boolean {
        val match = distanceRegex.find(text.trim()) ?: return false
        val rest =
            text
                .trim()
                .removeRange(match.range)
                .trim()
                .trim('·', '|', ',')
        return rest.isEmpty() || rest in BARE_DISTANCE_PREFIXES
    }

    private val hoursRegex = Regex("""(\d+)\s*(?:hours?|hrs?|h|小时|小時|時)(?![a-zA-Z])""", RegexOption.IGNORE_CASE)
    private val minutesRegex = Regex("""(\d+)\s*(?:minutes?|mins?|分钟|分鐘|分)(?![a-zA-Z])""", RegexOption.IGNORE_CASE)

    /** "4 min", "1 hr 5 min", "1 h 5 min", "25分钟", "1小时5分钟" -> seconds; null when no duration is present. */
    fun duration(text: String): Long? {
        val hours =
            hoursRegex
                .find(text)
                ?.groupValues
                ?.get(1)
                ?.toLongOrNull()
        val minutes =
            minutesRegex
                .find(text)
                ?.groupValues
                ?.get(1)
                ?.toLongOrNull()
        if (hours == null && minutes == null) return null
        return (hours ?: 0) * 3600 + (minutes ?: 0) * 60
    }

    private val clockRegex = Regex("""(上午|下午|凌晨|晚上|中午)?\s*(\d{1,2}):(\d{2})\s*([AaPp][Mm])?""")

    /**
     * A clock time such as "16:53", "4:53 PM", "下午4:53" as UTC epoch seconds of its next occurrence (an arrival is
     * never far in the past, so a time more than 10 minutes behind [nowMillis] means tomorrow).
     */
    fun clock(
        text: String,
        nowMillis: Long,
    ): Long? {
        val match = clockRegex.find(text) ?: return null
        var hour = match.groupValues[2].toInt()
        val minute = match.groupValues[3].toInt()
        if (hour > 23 || minute > 59) return null
        val marker = match.groupValues[4].lowercase()
        val prefix = match.groupValues[1]
        val afternoon = marker == "pm" || prefix == "下午" || prefix == "晚上"
        val morning = marker == "am" || prefix == "上午" || prefix == "凌晨"
        if (afternoon && hour < 12) hour += 12
        if (morning && hour == 12) hour = 0
        val calendar = Calendar.getInstance().apply { timeInMillis = nowMillis }
        calendar.set(Calendar.HOUR_OF_DAY, hour)
        calendar.set(Calendar.MINUTE, minute)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        if (calendar.timeInMillis < nowMillis - 10 * 60_000L) calendar.add(Calendar.DAY_OF_MONTH, 1)
        return calendar.timeInMillis / 1000
    }

    private val roundaboutExitRegex = Regex("""(?:第\s*(\d+)\s*(?:个|個)?\s*出口|(\d+)(?:st|nd|rd|th)\s+exit)""", RegexOption.IGNORE_CASE)

    /** iAP2 ManeuverType from an instruction ("Turn left onto…", "右转进入…", "靠左行駛"); null when unknown. */
    fun maneuver(text: String): Int? {
        val t = text.lowercase()

        fun has(vararg words: String) = words.any { it in t }
        if (has("roundabout", "环岛", "環島", "圆环", "圓環")) {
            val exit = roundaboutExitRegex.find(t)?.let { m -> (m.groupValues[1].ifEmpty { m.groupValues[2] }).toIntOrNull() }
            return when {
                exit != null && exit in 1..19 -> RouteGuidanceUpdate.TYPE_ROUNDABOUT_EXIT_1 + exit - 1
                has("exit the roundabout", "leave the roundabout", "驶出环岛", "駛出環島", "離開圓環") ->
                    RouteGuidanceUpdate.TYPE_EXIT_ROUNDABOUT
                else -> RouteGuidanceUpdate.TYPE_ENTER_ROUNDABOUT
            }
        }
        val left = has("keep left", "left exit", "left off-ramp", "exit on the left", "left-hand exit", "靠左", "左側", "左侧")
        val right = has("keep right", "right exit", "right off-ramp", "exit on the right", "right-hand exit", "靠右", "右側", "右侧")
        return when {
            has("exit the ferry", "leave the ferry", "disembark", "驶离渡轮", "駛離渡輪", "下渡輪", "下渡轮") ->
                RouteGuidanceUpdate.TYPE_EXIT_FERRY
            has("change ferries", "transfer to the ferry", "换乘渡轮", "轉乘渡輪") ->
                RouteGuidanceUpdate.TYPE_CHANGE_FERRY
            has("take the ferry", "take a ferry", "board the ferry", "乘渡轮", "搭乘渡輪", "上渡轮", "上渡輪") ->
                RouteGuidanceUpdate.TYPE_ENTER_FERRY
            has("change highway", "switch highway", "轉換高速公路", "切換高速公路", "切换高速公路") ->
                when {
                    left && !right -> RouteGuidanceUpdate.TYPE_CHANGE_HIGHWAY_LEFT
                    right && !left -> RouteGuidanceUpdate.TYPE_CHANGE_HIGHWAY_RIGHT
                    else -> RouteGuidanceUpdate.TYPE_CHANGE_HIGHWAY
                }
            has(
                "take the exit",
                "take exit",
                "exit ramp",
                "off-ramp",
                "off ramp",
                "下匝道",
                "驶出",
                "駛出",
                "从出口",
                "從出口",
                "出口驶离",
                "出口駛離",
            ) ->
                when {
                    left && !right -> RouteGuidanceUpdate.TYPE_HIGHWAY_OFF_RAMP_LEFT
                    right && !left -> RouteGuidanceUpdate.TYPE_HIGHWAY_OFF_RAMP_RIGHT
                    else -> RouteGuidanceUpdate.TYPE_OFF_RAMP
                }
            has("on-ramp", "on ramp", "entrance ramp", "上匝道", "驶入高速", "駛入高速") ->
                RouteGuidanceUpdate.TYPE_ON_RAMP
            has("u-turn", "u turn", "make a u", "掉头", "掉頭", "迴轉", "回转", "調頭", "调头") -> RouteGuidanceUpdate.TYPE_U_TURN
            has("sharp left", "急左转", "急左轉") -> RouteGuidanceUpdate.TYPE_SHARP_LEFT
            has("sharp right", "急右转", "急右轉") -> RouteGuidanceUpdate.TYPE_SHARP_RIGHT
            has("slight left", "向左前方", "左前方", "稍向左", "微左") -> RouteGuidanceUpdate.TYPE_SLIGHT_LEFT
            has("slight right", "向右前方", "右前方", "稍向右", "微右") -> RouteGuidanceUpdate.TYPE_SLIGHT_RIGHT
            has("keep left", "stay left", "靠左") -> RouteGuidanceUpdate.TYPE_KEEP_LEFT
            has("keep right", "stay right", "靠右") -> RouteGuidanceUpdate.TYPE_KEEP_RIGHT
            has("turn left", "left onto", "左转", "左轉") -> RouteGuidanceUpdate.TYPE_LEFT
            has("turn right", "right onto", "右转", "右轉") -> RouteGuidanceUpdate.TYPE_RIGHT
            has("arrive", "destination", "到达目的地", "到達目的地", "抵达", "抵達") -> RouteGuidanceUpdate.TYPE_ARRIVE
            has("continue", "straight", "head ", "直行", "继续", "繼續", "沿", "前進", "前进") -> RouteGuidanceUpdate.TYPE_STRAIGHT
            else -> null
        }
    }

    private val afterRoadPrefixes =
        listOf("进入", "進入", "驶入", "駛入", "沿", "onto ", "toward ", "towards ", "on ", "往", "朝")

    /** Road name after the maneuver from "Turn left onto Bourke St" / "右转进入中山路" / "進入 XX 路". */
    fun afterRoad(text: String): String {
        val lower = text.lowercase()
        for (prefix in afterRoadPrefixes) {
            val at = lower.indexOf(prefix)
            if (at >= 0 && (prefix.first().code > 0x7F || at == 0 || lower[at - 1] == ' ')) {
                var rest = text.substring(at + prefix.length).trim()
                // "朝Lonsdale St前進" / "沿中山路行驶": drop the trailing verb
                for (suffix in AFTER_ROAD_SUFFIXES) rest = rest.removeSuffix(suffix).trim()
                if (rest.isNotEmpty()) return rest
            }
        }
        return ""
    }

    private val remainingWords = listOf("剩余", "剩餘", "remaining", "全程")
    private val arrivalWords = listOf("到达", "到達", "eta", "arrival", "抵达", "抵達", "预计", "預計")

    /**
     * Generic reading of a navigation screen or notification as a list of visible texts in reading order. Works for
     * layouts that keep the maneuver card (distance + instruction) apart from the trip summary (remaining distance /
     * time / arrival), which is how Waze, Amap and Google Maps notifications are built.
     */
    fun fromTexts(
        source: String,
        texts: List<String>,
        nowMillis: Long,
    ): NavInfo? {
        var toManeuver: Distance? = null
        var instruction = ""
        var remaining: Distance? = null
        var timeRemaining: Long? = null
        var eta: Long? = null
        for (raw in texts) {
            val text = raw.trim()
            if (text.isEmpty()) continue
            val lower = text.lowercase()
            val summary = remainingWords.any { it in lower } || arrivalWords.any { it in lower }
            if (summary || (duration(text) != null && toManeuver != null)) {
                if (remaining == null) remaining = distance(text)
                if (timeRemaining == null) timeRemaining = duration(text)
                if (eta == null && arrivalWords.any { it in lower }) eta = clock(text, nowMillis)
                continue
            }
            if (toManeuver == null && isBareDistance(text)) {
                toManeuver = distance(text)
                continue
            }
            if (instruction.isEmpty() && maneuver(text) != null) {
                instruction = text
                if (toManeuver == null) toManeuver = distance(text)
                continue
            }
            if (instruction.isEmpty() && afterRoad(text).isNotEmpty()) instruction = text
        }
        if (toManeuver == null && instruction.isEmpty()) return null
        return NavInfo(
            source = source,
            toManeuver = toManeuver,
            instruction = instruction,
            maneuverType = maneuver(instruction),
            afterRoad = afterRoad(instruction),
            remaining = remaining,
            timeRemainingSeconds = timeRemaining,
            etaEpochSeconds = eta,
        )
    }

    private val markupTag = Regex("<[^>]*>")
    private val numberOnly = Regex("""^\d+(?:[.,]\d+)?$""")
    private val unitOnly = Regex("""^(km|m|mi|ft|公里|千米|米|公尺)$""", RegexOption.IGNORE_CASE)
    private val clockOnly = Regex("""^\d{1,2}:\d{2}$""")
    private val whitespace = Regex("""\s+""")
    private val amapNoise = listOf("訊號", "信号", "信號", "GPS")

    /** Amap draws some numbers as `<font …>15.8</font><img …>` rich text: keep only the visible characters. */
    fun stripMarkup(text: String): String = whitespace.replace(markupTag.replace(text, " "), " ").trim()

    /**
     * Amap's car-projection navigation screen (the layout it uses on the CarPlayMap display, 2026-11):
     * top-left maneuver card = number + unit as separate texts ("22", "m") followed by the road after the maneuver;
     * bottom bar = remaining distance (rich text, unit drawn as an image) and minutes, then "04:39" + "到達", and the
     * current road at the bottom center next to the speed limit. The maneuver arrow is an image (type unknown).
     * Texts shorter than [MIN_VISIBLE_PX] are collapsed menu rows and ignored. (layout mapped from one NAV-DUMP)
     */
    fun fromAmapScreen(
        source: String,
        texts: List<ScreenText>,
        nowMillis: Long,
    ): NavInfo? {
        val items =
            texts
                .map { it.copy(text = stripMarkup(it.text)) }
                .filter { it.text.isNotEmpty() && it.height >= MIN_VISIBLE_PX }
        if (items.isEmpty()) return null
        val height = items.maxOf { it.bottom }
        val width = items.maxOf { it.right }
        val top = items.filter { it.bottom < height / 3 }.sortedBy { it.left }
        var toManeuver: Distance? = null
        var afterRoad = ""
        for (number in top.filter { numberOnly.matches(it.text) }) {
            val unit =
                top.firstOrNull {
                    unitOnly.matches(it.text) &&
                        it.left >= number.right - UNIT_GAP_PX &&
                        it.left - number.right < number.height &&
                        kotlin.math.abs(it.centerY - number.centerY) < number.height
                } ?: continue
            toManeuver = distance("${number.text} ${unit.text}")
            afterRoad =
                top
                    .filter {
                        it.left > unit.right &&
                            kotlin.math.abs(it.centerY - number.centerY) < number.height &&
                            !numberOnly.matches(it.text) &&
                            amapNoise.none { n -> n in it.text }
                    }.minByOrNull { it.left }
                    ?.text
                    .orEmpty()
            break
        }
        if (toManeuver == null) return null
        val bottom = items.filter { it.top > height * 2 / 3 }.sortedBy { it.left }
        val arrival = bottom.firstOrNull { it.text == "到達" || it.text == "到达" }
        val eta =
            arrival
                ?.let { a ->
                    bottom.filter { clockOnly.matches(it.text) && it.right <= a.left + UNIT_GAP_PX }.maxByOrNull { it.right }
                }?.let { clock(it.text, nowMillis) }
        // The distance / minutes row sits right above the arrival row
        val summaryRow = arrival?.let { a -> bottom.filter { it.bottom <= a.top + UNIT_GAP_PX } }.orEmpty()
        val joined = summaryRow.joinToString(" ") { it.text }
        // Distance with a visible unit, else Amap's decimal kilometers ("15.8"). Minutes only with their visible unit:
        // the bare integer in this row is the traffic-light count (Amap 2026-11: "28分 · 15.8公里 · 58")
        val remaining =
            distance(joined) ?: summaryRow
                .firstOrNull { numberOnly.matches(it.text) && '.' in it.text }
                ?.let { distance("${it.text} km") }
        val minutes = duration(joined)
        val currentRoad =
            bottom
                .filter {
                    it.left > width / 2 &&
                        !numberOnly.matches(it.text) &&
                        it.text != arrival?.text &&
                        it.text.any { c -> c.isLetter() }
                }.minByOrNull { it.left }
                ?.text
                .orEmpty()
        return NavInfo(
            source = source,
            toManeuver = toManeuver,
            instruction = afterRoad,
            maneuverType = null,
            afterRoad = afterRoad,
            currentRoad = currentRoad,
            remaining = remaining,
            timeRemainingSeconds = minutes,
            etaEpochSeconds = eta,
        )
    }

    /**
     * Applies the structured fields of a live-update navigation notification (see MapsNotificationSource): they
     * win over values guessed from the free-text title. [remainingMeters] comes from the progress bar.
     */
    fun withLiveUpdate(
        info: NavInfo,
        distanceText: String?,
        road: String,
        remainingMeters: Long?,
    ): NavInfo {
        val toManeuver = distanceText?.let { distance(it) } ?: info.toManeuver
        val remaining =
            info.remaining ?: remainingMeters?.let { meters ->
                if (meters >= 1000) {
                    RouteGuidanceUpdate.Distance(
                        meters,
                        "%.1f".format(java.util.Locale.ROOT, meters / 1000.0),
                        RouteGuidanceUpdate.UNIT_KM,
                    )
                } else {
                    RouteGuidanceUpdate.Distance(meters, "$meters", RouteGuidanceUpdate.UNIT_METERS)
                }
            }
        return info.copy(
            toManeuver = toManeuver,
            afterRoad = info.afterRoad.ifEmpty { road },
            remaining = remaining,
        )
    }

    private const val MIN_VISIBLE_PX = 12
    private const val UNIT_GAP_PX = 16

    private val BARE_DISTANCE_PREFIXES = setOf("后", "後", "in", "前方", "after")
    private val AFTER_ROAD_SUFFIXES = listOf("前進", "前进", "行駛", "行驶", "直行", "方向")
}
