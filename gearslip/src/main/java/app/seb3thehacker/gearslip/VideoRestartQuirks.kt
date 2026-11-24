package app.seb3thehacker.gearslip

import app.seb3thehacker.gearslip.car.CarEnvironment

/**
 * Which cars hang up when the video restarts. When a car takes its screen (the reverse camera,
 * its own menus) Gearslip sends Stop, and when the screen comes back it sends Start again with a
 * fresh keyframe. A 2018 Uconnect needs that Stop, or it keeps showing the last frame. A 2021
 * Mazda 3 hangs up a few milliseconds after the new Start instead (issue #115). A car that hangs
 * up right after a restart is remembered by its head unit's certificate, and from then on its
 * stream just pauses and resumes, with no Stop or Start.
 */
object VideoRestartQuirks {

    private const val KEY = "video_no_restart_cars"

    fun pausesOnly(car: String?): Boolean = car != null && car in cars()

    /** The car hung up right after a restarted stream: pause and resume it from now on. */
    fun hungUpAfterRestart(car: String?) {
        val context = CarEnvironment.appContext ?: return
        car ?: return
        val now = cars()
        if (car in now) return
        AppSettings.putString(context, KEY, (now + car).joinToString(","))
        GearslipLog.i("video: the car hung up right after the stream restarted, so next time it only pauses and resumes", "PROTO")
    }

    private fun cars(): Set<String> {
        val context = CarEnvironment.appContext ?: return emptySet()
        return AppSettings.getString(context, KEY, "").split(",").filter { it.isNotEmpty() }.toSet()
    }
}
