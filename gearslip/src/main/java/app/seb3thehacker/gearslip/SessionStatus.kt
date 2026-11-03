package app.seb3thehacker.gearslip

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the phone UI shows about the current car session.
 *
 * [GearslipRunner] reports progress here as it moves through the handshake, so the UI reads
 * a plain state instead of parsing log lines. A failure stays on screen until the next
 * attach starts, so the reason is still there when the user picks the phone back up.
 */
object SessionStatus {

    enum class Phase { IDLE, CONNECTING, PROJECTING, FAILED, DISCONNECTED }

    /** [stuckOn] is set when connecting has gone on too long: the cable or Wi-Fi, for what to try. */
    data class Snapshot(val phase: Phase, val headline: String, val detail: String, val stuckOn: Link? = null)

    enum class Link { CABLE, WIRELESS }

    private val flow = MutableStateFlow(
        Snapshot(
            Phase.IDLE,
            "Not connected",
            "Plug into the car's USB port, or connect wirelessly. Gearslip opens by itself when the car answers.",
        ),
    )

    val state: StateFlow<Snapshot> = flow

    fun connecting(stage: String) {
        flow.value = Snapshot(Phase.CONNECTING, "Connecting", stage)
    }

    /** Still connecting after [GearslipRunner]'s patience ran out: Home says what to try. */
    fun stillConnecting(link: Link) {
        val now = flow.value
        if (now.phase == Phase.CONNECTING) flow.value = now.copy(stuckOn = link)
    }

    fun projecting() {
        flow.value = Snapshot(Phase.PROJECTING, "Projecting", "Streaming to the head unit.")
    }

    fun failed(headline: String, detail: String) {
        flow.value = Snapshot(Phase.FAILED, headline, detail)
    }

    /** Keeps an earlier failure visible: a rejection is followed by a disconnect every time. */
    fun disconnected() {
        if (flow.value.phase == Phase.FAILED) return
        flow.value = Snapshot(Phase.DISCONNECTED, "Disconnected", "The head unit is no longer connected.")
    }
}
