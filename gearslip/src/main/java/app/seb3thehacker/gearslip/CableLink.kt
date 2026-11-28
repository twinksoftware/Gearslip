package app.seb3thehacker.gearslip

import android.content.Context
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager

/**
 * The cable, seen from outside the activity. Android hands the car's USB connection to Gearslip
 * when it's plugged in; this lets Home ask for it again by hand, for instance after the driver
 * dismissed the first try or wireless came up first.
 */
object CableLink {

    /** Set by the activity: opens the attached car's USB connection, asking Android if it must. */
    @Volatile var connect: (() -> Unit)? = null

    /** The car on the cable, if one is plugged in and talking Android Auto. */
    fun attached(context: Context): UsbAccessory? =
        runCatching { context.getSystemService(UsbManager::class.java)?.accessoryList?.firstOrNull() }.getOrNull()
}
