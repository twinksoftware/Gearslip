package app.seb3thehacker.gearslip.notify

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.service.notification.StatusBarNotification
import androidx.car.app.model.Distance
import androidx.car.app.navigation.model.Maneuver
import androidx.core.graphics.drawable.toBitmap
import app.seb3thehacker.gearslip.GearslipLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The next turn from a map app guiding on the phone itself. Google Maps and Waze refuse to run on
 * Gearslip's car screen, but while one of them navigates on the phone it keeps a notification up
 * with the turn's arrow, the distance and the road. Gearslip reads it, through the notification
 * access it already has, and puts the turn in the nav bar and on the instrument cluster.
 */
object PhoneNavigation {

    /** One turn as the notification described it. [maneuver] is a guess from the words, English only. */
    class Turn(
        val key: String,
        val packageName: String,
        val arrow: Bitmap?,
        val distance: Distance?,
        val distanceText: String,
        val road: String,
        val maneuver: Int,
    )

    private val _turn = MutableStateFlow<Turn?>(null)
    val turn: StateFlow<Turn?> = _turn

    /** The apps whose turns are read; any other map app runs on the car screen itself. */
    val APPS = setOf("com.google.android.apps.maps", "com.waze")

    private val log = GearslipLog.tagged("nav")

    fun posted(context: Context, sbn: StatusBarNotification) {
        val turn = parse(context, sbn)
        val was = _turn.value
        if (turn == null) {
            if (was?.key == sbn.key) _turn.value = null
            return
        }
        if (was == null || was.road != turn.road || was.maneuver != turn.maneuver) {
            log.i("phone navigation (${sbn.packageName}): ${turn.distanceText} | ${turn.road} | maneuver=${turn.maneuver}")
        }
        _turn.value = turn
    }

    fun removed(key: String) {
        if (_turn.value?.key == key) {
            log.i("phone navigation ended")
            _turn.value = null
        }
    }

    /**
     * Google Maps puts the distance in the title and the instruction in the text; Waze has been
     * seen the other way round. Whichever field starts with a distance is the distance, and the
     * other is the instruction.
     */
    private fun parse(context: Context, sbn: StatusBarNotification): Turn? {
        val extras = sbn.notification.extras
        fun field(name: String) = extras.getCharSequence(name)?.toString()?.trim().orEmpty()
        val fields = listOf(field(Notification.EXTRA_TITLE), field(Notification.EXTRA_TEXT), field(Notification.EXTRA_BIG_TEXT))
            .filter { it.isNotEmpty() }
        if (fields.isEmpty()) return null
        val withDistance = fields.firstOrNull { DISTANCE.find(it)?.range?.first == 0 }
        val match = withDistance?.let { DISTANCE.find(it) }
        val rest = withDistance?.substring(match!!.range.last + 1)?.trimStart(' ', '·', '-', '•', ',')?.trim().orEmpty()
        val instruction = fields.firstOrNull { it != withDistance }?.takeIf { it.isNotEmpty() } ?: rest
        if (instruction.isEmpty() && match == null) return null
        return Turn(
            key = sbn.key,
            packageName = sbn.packageName,
            arrow = arrow(context, sbn.notification),
            distance = match?.let { distanceOf(it) },
            distanceText = match?.value?.trim().orEmpty(),
            road = roadOf(instruction),
            maneuver = maneuverOf(instruction),
        )
    }

    private fun arrow(context: Context, n: Notification): Bitmap? = runCatching {
        n.getLargeIcon()?.loadDrawable(context)?.toBitmap(ARROW_PX, ARROW_PX)
    }.getOrNull()

    private fun distanceOf(m: MatchResult): Distance? {
        val value = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val unit = when (m.groupValues[2].lowercase()) {
            "km" -> Distance.UNIT_KILOMETERS
            "mi" -> Distance.UNIT_MILES
            "ft" -> Distance.UNIT_FEET
            "yd" -> Distance.UNIT_YARDS
            else -> Distance.UNIT_METERS
        }
        val meters = value * when (unit) {
            Distance.UNIT_KILOMETERS -> 1000.0
            Distance.UNIT_MILES -> 1609.344
            Distance.UNIT_FEET -> 0.3048
            Distance.UNIT_YARDS -> 0.9144
            else -> 1.0
        }
        return Distance.create(meters, unit)
    }

    /** "Turn left onto Main St" shows as "Main St"; anything else shows as it came. */
    private fun roadOf(instruction: String): String {
        val lower = instruction.lowercase()
        for (word in ROAD_WORDS) {
            val at = lower.indexOf(word)
            if (at >= 0) return instruction.substring(at + word.length).trim()
        }
        return instruction
    }

    private fun maneuverOf(instruction: String): Int {
        val s = instruction.lowercase()
        val left = "left" in s
        val right = "right" in s
        fun side(l: Int, r: Int) = if (left) l else if (right) r else Maneuver.TYPE_UNKNOWN
        return when {
            "u-turn" in s || "make a u" in s -> side(Maneuver.TYPE_U_TURN_LEFT, Maneuver.TYPE_U_TURN_RIGHT)
            "roundabout" in s -> Maneuver.TYPE_UNKNOWN // the car's icon needs an exit number we don't have
            "destination" in s || "arrive" in s -> side(Maneuver.TYPE_DESTINATION_LEFT, Maneuver.TYPE_DESTINATION_RIGHT)
                .takeIf { it != Maneuver.TYPE_UNKNOWN } ?: Maneuver.TYPE_DESTINATION
            "exit" in s -> side(Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT, Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT)
            "merge" in s -> side(Maneuver.TYPE_MERGE_LEFT, Maneuver.TYPE_MERGE_RIGHT)
                .takeIf { it != Maneuver.TYPE_UNKNOWN } ?: Maneuver.TYPE_MERGE_SIDE_UNSPECIFIED
            "fork" in s -> side(Maneuver.TYPE_FORK_LEFT, Maneuver.TYPE_FORK_RIGHT)
            "keep" in s || "stay" in s -> side(Maneuver.TYPE_KEEP_LEFT, Maneuver.TYPE_KEEP_RIGHT)
            "slight" in s -> side(Maneuver.TYPE_TURN_SLIGHT_LEFT, Maneuver.TYPE_TURN_SLIGHT_RIGHT)
            "sharp" in s -> side(Maneuver.TYPE_TURN_SHARP_LEFT, Maneuver.TYPE_TURN_SHARP_RIGHT)
            "turn" in s -> side(Maneuver.TYPE_TURN_NORMAL_LEFT, Maneuver.TYPE_TURN_NORMAL_RIGHT)
            "continue" in s || "straight" in s || "head " in s -> Maneuver.TYPE_STRAIGHT
            else -> Maneuver.TYPE_UNKNOWN
        }
    }

    private const val ARROW_PX = 96
    private val DISTANCE = Regex("""^\s*(\d+(?:[.,]\d+)?)\s*(km|mi|ft|yd|m)\b""", RegexOption.IGNORE_CASE)
    private val ROAD_WORDS = listOf(" onto ", " on to ", " toward ", " towards ", " on ")
}
