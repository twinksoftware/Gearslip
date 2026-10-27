package app.seb3thehacker.gearslip.wireless

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Handler
import android.os.Looper
import app.seb3thehacker.gearslip.GearslipLog
import app.seb3thehacker.gearslip.GearslipActivity

/**
 * Starts wireless Android Auto by itself when a car Gearslip has connected to before comes up
 * over Bluetooth.
 *
 * Android only lets an app wake up and open its screen from the background for a device it
 * manages as a companion, so the first wireless connection to a car also asks Android to make
 * that car Gearslip's companion. Android shows its own one-time "Allow" for it; after that it
 * tells [CarPresenceService] whenever the car connects, and Gearslip connects to it.
 */
object WirelessAuto {

    /** Action on the activity's intent when the car came up on its own. */
    const val ACTION_CAR_NEARBY = "app.seb3thehacker.gearslip.CAR_NEARBY"
    const val EXTRA_ADDRESS = "address"

    private const val PREFS = "gearslip_wireless_auto"

    private val log = GearslipLog.tagged("AAW")

    /** Set by the activity: shows Android's companion dialog, which only an activity can do. */
    @Volatile var showApproval: ((IntentSender, String) -> Unit)? = null

    /**
     * Called whenever the driver connects to [device] by hand. Asks Android once per car; a car
     * already approved only has its presence watched again, in case Android forgot. A car only
     * counts as asked once the driver has answered the dialog: if it never showed, the next
     * connection by hand asks again.
     */
    @SuppressLint("MissingPermission")
    fun connectedByHand(context: Context, device: BluetoothDevice) {
        val manager = context.getSystemService(CompanionDeviceManager::class.java) ?: return
        val address = device.address
        if (isCompanion(manager, address)) {
            watch(context, address)
            return
        }
        if (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(address, false)) {
            log.i("auto-connect: already asked about this car and it was declined; connect by hand")
            return
        }

        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().setAddress(address).build())
            .setSingleDevice(true)
            .build()
        log.i("auto-connect: asking Android to let Gearslip watch for ${WirelessLink.name(device)}")
        @Suppress("DEPRECATION")
        manager.associate(request, object : CompanionDeviceManager.Callback() {
            @Deprecated("Deprecated in Java")
            override fun onDeviceFound(chooser: IntentSender) {
                showApproval?.invoke(chooser, address) ?: log.w("auto-connect: no screen to ask on")
            }

            override fun onFailure(error: CharSequence?) {
                log.w("auto-connect: Android wouldn't ask ($error)")
            }
        }, Handler(Looper.getMainLooper()))
    }

    /** True when the driver said no to this car connecting by itself, and it still can't. */
    fun declinedEarlier(context: Context, device: BluetoothDevice): Boolean {
        val manager = context.getSystemService(CompanionDeviceManager::class.java) ?: return false
        return !isCompanion(manager, device.address) &&
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(device.address, false)
    }

    /** Forgets the earlier no and shows Android's dialog for [device] again. */
    fun askAgain(context: Context, device: BluetoothDevice) {
        log.i("auto-connect: asking again about ${WirelessLink.name(device)}")
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(device.address).apply()
        connectedByHand(context, device)
    }

    /** The driver allowed it in Android's dialog. */
    fun approved(context: Context, address: String) {
        log.i("auto-connect: allowed")
        answered(context, address)
        watch(context, address)
    }

    /** The driver said no in Android's dialog: this car isn't asked about again. */
    fun declined(context: Context, address: String) {
        log.w("auto-connect: declined in Android's dialog")
        answered(context, address)
    }

    private fun answered(context: Context, address: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(address, true).apply()
    }

    @SuppressLint("MissingPermission")
    private fun watch(context: Context, address: String) {
        val manager = context.getSystemService(CompanionDeviceManager::class.java) ?: return
        @Suppress("DEPRECATION")
        runCatching { manager.startObservingDevicePresence(address) }
            .onFailure { log.w("auto-connect: couldn't watch for the car (${it.message})") }
    }

    @Suppress("DEPRECATION")
    private fun isCompanion(manager: CompanionDeviceManager, address: String): Boolean =
        runCatching { manager.associations.any { it.equals(address, ignoreCase = true) } }.getOrDefault(false)

    /** From [CarPresenceService]: the car is here, so bring Gearslip up to connect to it. */
    fun carAppeared(context: Context, address: String) {
        log.i("auto-connect: a car Gearslip knows connected over Bluetooth")
        runCatching {
            context.startActivity(
                Intent(context, GearslipActivity::class.java)
                    .setAction(ACTION_CAR_NEARBY)
                    .putExtra(EXTRA_ADDRESS, address)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { log.w("auto-connect: couldn't open Gearslip (${it.message})") }
    }
}
