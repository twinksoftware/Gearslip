package app.seb3thehacker.gearslip

import app.seb3thehacker.gearslip.car.CarEnvironment

/**
 * Which cars want the short service discovery request. Since 0.3.39 Gearslip sends what a real
 * phone does: its icon at three sizes and a session ID. A 2017 Hyundai on protocol 1.0 ignored
 * the request without them (issue #47), but a 2019 Fiat 500's Uconnect hangs up on it within a
 * second (issue #91). A car that hangs up after one kind is sent the other next time, remembered
 * by its head unit's certificate, so each car settles on whichever it answers.
 */
object ServiceDiscoveryQuirks {

    private const val KEY = "sdr_plain_cars"

    fun wantsPlain(car: String?): Boolean = car != null && car in cars()

    /** The car hung up after [plain] (or the full) request: switch it to the other one. */
    fun droppedAfter(car: String?, plain: Boolean) {
        val context = CarEnvironment.appContext ?: return
        car ?: return
        val now = cars()
        val next = if (plain) now - car else now + car
        if (next == now) return
        AppSettings.putString(context, KEY, next.joinToString(","))
        GearslipLog.i("service discovery: the car hung up after the ${if (plain) "short" else "full"} request, so next time it gets the ${if (plain) "full" else "short"} one", "PROTO")
    }

    private fun cars(): Set<String> {
        val context = CarEnvironment.appContext ?: return emptySet()
        return AppSettings.getString(context, KEY, "").split(",").filter { it.isNotEmpty() }.toSet()
    }
}
