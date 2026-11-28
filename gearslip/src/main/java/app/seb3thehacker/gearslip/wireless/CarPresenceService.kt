package app.seb3thehacker.gearslip.wireless

import android.companion.CompanionDeviceService

/** Android calls this when a car approved in [WirelessAuto] connects over Bluetooth. */
class CarPresenceService : CompanionDeviceService() {

    @Deprecated("Deprecated in Java")
    override fun onDeviceAppeared(address: String) {
        WirelessAuto.carAppeared(applicationContext, address)
    }

    @Deprecated("Deprecated in Java")
    override fun onDeviceDisappeared(address: String) {
        // The car's Bluetooth went with it, so it isn't coming back in a moment: see CarAudio.carLeft.
        app.seb3thehacker.gearslip.audio.CarAudio.carLeft("the car's Bluetooth went")
    }
}
