package app.seb3thehacker.gearslip.car

import androidx.car.app.model.CarText
import androidx.car.app.model.Distance
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import app.seb3thehacker.gearslip.GearslipLog
import app.seb3thehacker.gearslip.Protobuf
import app.seb3thehacker.gearslip.notify.PhoneNavigation

/**
 * The next turn on the car's instrument cluster, over its NavigationStatusService (Service
 * field 8), the way Android Auto feeds it:
 *
 *   -> NavigationStatus         { status = 1 (1 active, 2 inactive, 3 rerouting) }
 *   -> NavigationNextTurnEvent  { road = 1; turn_side = 2; event = 3; turn_number = 5; turn_angle = 6 }
 *   -> NavigationNextTurnDistanceEvent { distance_meters = 1; time_to_turn_seconds = 2;
 *                                        display_distance_e3 = 3; display_distance_unit = 4 }
 *
 * Newer cars also take a NavigationState message; the deprecated pair above is what a 2018
 * Uconnect understands. The turn comes from the map app's trip (what the Car App Library hands
 * to clusters), or from its routing card when it sends no trip.
 */
class ClusterNav(
    val channelId: Int,
    minimumIntervalMs: Long = 0,
    private val sendOnChannel: (messageId: Int, body: ByteArray) -> Unit,
) {
    private val updateIntervalMs = maxOf(TICK_MS, minimumIntervalMs)
    @Volatile private var running = false
    private var worker: Thread? = null
    private var updatesRequested = true
    private var sentStatus = -1
    private var sentTurn: String? = null
    private var sentDistance: String? = null

    @Synchronized
    fun onMessage(messageId: Int, body: ByteArray) {
        when (messageId) {
            MSG_CHANNEL_OPEN_RESPONSE -> {
                val status = Protobuf.readInt32Field(body, 1)
                GearslipLog.i("cluster: <- ChannelOpenResponse: status=$status")
                if (status == 0) start()
            }
            MSG_START -> {
                updatesRequested = true
                sentStatus = -1
                sentTurn = null
                sentDistance = null
                GearslipLog.i("cluster: <- start")
            }
            MSG_STOP -> {
                updatesRequested = false
                GearslipLog.i("cluster: <- stop")
            }
            else -> GearslipLog.i("cluster: <- unhandled message id=$messageId (${body.size} bytes)")
        }
    }

    private fun start() {
        if (running) return
        running = true
        worker = Thread({
            while (running) {
                runCatching { tick() }.onFailure { GearslipLog.w("cluster: ${it.message}") }
                try {
                    Thread.sleep(updateIntervalMs)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, "gearslip-cluster").also { it.start() }
    }

    @Synchronized
    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
    }

    @Synchronized
    private fun tick() {
        if (!running || !updatesRequested) return
        val turn = NavTurn.current()
        val status = when {
            turn == null -> STATUS_INACTIVE
            turn.rerouting -> STATUS_REROUTING
            else -> STATUS_ACTIVE
        }
        if (status != sentStatus) {
            sendOnChannel(MSG_STATUS, Protobuf.varintField(1, status.toLong()))
            GearslipLog.i("cluster: -> ${STATUS_NAMES[status]}")
            sentStatus = status
            if (status == STATUS_INACTIVE) { sentTurn = null; sentDistance = null }
        }
        if (turn == null || status == STATUS_REROUTING) return
        sendTurn(turn.step)
        turn.distance?.let { sendDistance(it, turn.seconds) }
    }

    private fun sendTurn(step: Step) {
        val maneuver = step.maneuver
        val (event, side) = maneuver?.let { eventFor(it.type) } ?: (EVENT_UNKNOWN to SIDE_UNSPECIFIED)
        val road = step.road.text().ifBlank { step.cue.text() }
        val key = "$road|$event|$side|${maneuver?.roundaboutExitNumber}|${maneuver?.roundaboutExitAngle}"
        if (key == sentTurn) return
        sentTurn = key
        var body = Protobuf.stringField(1, road) +
            Protobuf.varintField(2, side.toLong()) +
            Protobuf.varintField(3, event.toLong())
        maneuver?.roundaboutExitNumber?.takeIf { it > 0 }?.let { body += Protobuf.varintField(5, it.toLong()) }
        maneuver?.roundaboutExitAngle?.takeIf { it > 0 }?.let { body += Protobuf.varintField(6, it.toLong()) }
        sendOnChannel(MSG_TURN, body)
        GearslipLog.i("cluster: -> turn event=$event side=$side road=\"$road\"")
    }

    /**
     * The Car App Library's distance units use the same numbers as the protocol's (1 metres ...
     * 7 yards), so the app's own display choice passes straight through.
     */
    private fun sendDistance(distance: Distance, seconds: Long?) {
        val meters = NavTurn.toMeters(distance)
        val key = "${distance.displayDistance}|${distance.displayUnit}|${seconds ?: -1}"
        if (key == sentDistance) return
        sentDistance = key
        sendOnChannel(
            MSG_DISTANCE,
            Protobuf.varintField(1, meters.toLong()) +
                Protobuf.varintField(2, (seconds ?: 0L).coerceAtLeast(0)) +
                Protobuf.varintField(3, (distance.displayDistance * 1000).toLong()) +
                Protobuf.varintField(4, distance.displayUnit.toLong()),
        )
    }

    private fun CarText?.text() = this?.toString().orEmpty()

    companion object {
        private const val TICK_MS = 500L
        private const val MSG_CHANNEL_OPEN_RESPONSE = 8
        private const val MSG_START = 32769
        private const val MSG_STOP = 32770
        private const val MSG_STATUS = 32771
        private const val MSG_TURN = 32772
        private const val MSG_DISTANCE = 32773

        private const val STATUS_ACTIVE = 1
        private const val STATUS_INACTIVE = 2
        private const val STATUS_REROUTING = 3
        private val STATUS_NAMES = mapOf(1 to "navigating", 2 to "not navigating", 3 to "rerouting")

        private const val SIDE_LEFT = 1
        private const val SIDE_RIGHT = 2
        private const val SIDE_UNSPECIFIED = 3

        private const val EVENT_UNKNOWN = 0

        /** The car's turn icon and side for a Car App Library manoeuvre. */
        fun eventFor(type: Int): Pair<Int, Int> = when (type) {
            Maneuver.TYPE_DEPART -> 1 to SIDE_UNSPECIFIED
            Maneuver.TYPE_NAME_CHANGE -> 2 to SIDE_UNSPECIFIED
            Maneuver.TYPE_KEEP_LEFT, Maneuver.TYPE_TURN_SLIGHT_LEFT -> 3 to SIDE_LEFT
            Maneuver.TYPE_KEEP_RIGHT, Maneuver.TYPE_TURN_SLIGHT_RIGHT -> 3 to SIDE_RIGHT
            Maneuver.TYPE_TURN_NORMAL_LEFT -> 4 to SIDE_LEFT
            Maneuver.TYPE_TURN_NORMAL_RIGHT -> 4 to SIDE_RIGHT
            Maneuver.TYPE_TURN_SHARP_LEFT -> 5 to SIDE_LEFT
            Maneuver.TYPE_TURN_SHARP_RIGHT -> 5 to SIDE_RIGHT
            Maneuver.TYPE_U_TURN_LEFT -> 6 to SIDE_LEFT
            Maneuver.TYPE_U_TURN_RIGHT -> 6 to SIDE_RIGHT
            Maneuver.TYPE_ON_RAMP_SLIGHT_LEFT, Maneuver.TYPE_ON_RAMP_NORMAL_LEFT, Maneuver.TYPE_ON_RAMP_SHARP_LEFT,
            Maneuver.TYPE_ON_RAMP_U_TURN_LEFT -> 7 to SIDE_LEFT
            Maneuver.TYPE_ON_RAMP_SLIGHT_RIGHT, Maneuver.TYPE_ON_RAMP_NORMAL_RIGHT, Maneuver.TYPE_ON_RAMP_SHARP_RIGHT,
            Maneuver.TYPE_ON_RAMP_U_TURN_RIGHT -> 7 to SIDE_RIGHT
            Maneuver.TYPE_OFF_RAMP_SLIGHT_LEFT, Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT -> 8 to SIDE_LEFT
            Maneuver.TYPE_OFF_RAMP_SLIGHT_RIGHT, Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT -> 8 to SIDE_RIGHT
            Maneuver.TYPE_FORK_LEFT -> 9 to SIDE_LEFT
            Maneuver.TYPE_FORK_RIGHT -> 9 to SIDE_RIGHT
            Maneuver.TYPE_MERGE_LEFT -> 10 to SIDE_LEFT
            Maneuver.TYPE_MERGE_RIGHT -> 10 to SIDE_RIGHT
            Maneuver.TYPE_MERGE_SIDE_UNSPECIFIED -> 10 to SIDE_UNSPECIFIED
            Maneuver.TYPE_ROUNDABOUT_ENTER_CW, Maneuver.TYPE_ROUNDABOUT_ENTER_CCW -> 11 to SIDE_UNSPECIFIED
            Maneuver.TYPE_ROUNDABOUT_EXIT_CW, Maneuver.TYPE_ROUNDABOUT_EXIT_CCW -> 12 to SIDE_UNSPECIFIED
            Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW, Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW_WITH_ANGLE,
            Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW, Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE -> 13 to SIDE_UNSPECIFIED
            Maneuver.TYPE_STRAIGHT -> 14 to SIDE_UNSPECIFIED
            Maneuver.TYPE_FERRY_BOAT, Maneuver.TYPE_FERRY_BOAT_LEFT, Maneuver.TYPE_FERRY_BOAT_RIGHT -> 16 to SIDE_UNSPECIFIED
            Maneuver.TYPE_FERRY_TRAIN, Maneuver.TYPE_FERRY_TRAIN_LEFT, Maneuver.TYPE_FERRY_TRAIN_RIGHT -> 17 to SIDE_UNSPECIFIED
            Maneuver.TYPE_DESTINATION, Maneuver.TYPE_DESTINATION_STRAIGHT -> 19 to SIDE_UNSPECIFIED
            Maneuver.TYPE_DESTINATION_LEFT -> 19 to SIDE_LEFT
            Maneuver.TYPE_DESTINATION_RIGHT -> 19 to SIDE_RIGHT
            else -> EVENT_UNKNOWN to SIDE_UNSPECIFIED
        }
    }
}

/** One turn as the car wants it, from whichever source the map app fills. */
internal class NavTurn(val step: Step, val distance: Distance?, val seconds: Long?, val rerouting: Boolean) {
    companion object {
        /** The map app's next turn: from its trip (what clusters get), or its routing card. */
        fun current(): NavTurn? {
            val nav = CarServices.navOrNull() ?: return null
            hosted(nav)?.let { return it }
            // Google Maps or Waze guiding on the phone, when no map in the car is.
            val phone = PhoneNavigation.turn.value ?: return null
            val step = Step.Builder(phone.road).setManeuver(Maneuver.Builder(phone.maneuver).build()).build()
            return NavTurn(step, phone.distance, null, false)
        }

        private fun hosted(nav: app.seb3thehacker.gearslip.host.CarAppConnection): NavTurn? {
            nav.trip.value?.let { trip ->
                val step = trip.steps.firstOrNull() ?: return@let
                val estimate = trip.stepTravelEstimates.firstOrNull()
                return NavTurn(step, estimate?.remainingDistance, estimate?.remainingTimeSeconds?.takeIf { it >= 0 }, trip.isLoading)
            }
            val info = (nav.template.value as? NavigationTemplate)?.navigationInfo as? RoutingInfo ?: return null
            val step = info.currentStep ?: return null
            return NavTurn(step, info.currentDistance, null, info.isLoading)
        }

        fun toMeters(d: Distance): Int = (d.displayDistance * when (d.displayUnit) {
            Distance.UNIT_KILOMETERS, Distance.UNIT_KILOMETERS_P1 -> 1000.0
            Distance.UNIT_MILES, Distance.UNIT_MILES_P1 -> 1609.344
            Distance.UNIT_FEET -> 0.3048
            Distance.UNIT_YARDS -> 0.9144
            else -> 1.0
        }).toInt()
    }
}
