package app.seb3thehacker.gearslip.wireless

import android.companion.CompanionDeviceService

/** Android calls this when a car approved in [WirelessAuto] connects over Bluetooth. */
class CarPresenceService : CompanionDeviceService() {

    /**
     * Android binds this while the car is here, and binds it again after Gearslip's process dies,
     * but tells it the car appeared only once: see [WirelessAuto.restartedMidDrive].
     */
    override fun onCreate() {
        super.onCreate()
        WirelessAuto.restartedMidDrive(applicationContext)
    }

    @Deprecated("Deprecated in Java")
    override fun onDeviceAppeared(address: String) {
        WirelessAuto.carAppeared(applicationContext, address)
    }

    @Deprecated("Deprecated in Java")
    override fun onDeviceDisappeared(address: String) {}
}
