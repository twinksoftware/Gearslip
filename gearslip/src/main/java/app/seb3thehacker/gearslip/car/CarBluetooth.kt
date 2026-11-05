package app.seb3thehacker.gearslip.car

import androidx.annotation.StringRes
import app.seb3thehacker.gearslip.R
import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.car.app.CarToast
import app.seb3thehacker.gearslip.AppSettings
import app.seb3thehacker.gearslip.GearslipLog
import app.seb3thehacker.gearslip.Protobuf
import app.seb3thehacker.gearslip.ServiceDiscovery
import app.seb3thehacker.gearslip.Wire
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.MessageDigest

/**
 * Pairs the phone with the car over Bluetooth the first time they meet, so calls (and music, when
 * the driver chose Bluetooth audio) reach the car's speakers. Android Auto does this on its own;
 * Gearslip asks first, on the car screen.
 *
 * The car names its Bluetooth address in its service list. When the driver taps Pair, Gearslip
 * opens the car's Bluetooth channel and sends what Android Auto sends:
 *
 *   -> CHANNEL_OPEN_REQUEST                 <- CHANNEL_OPEN_RESPONSE
 *   -> PairingRequest { phone_address, method }
 *   <- PairingResponse { status, already_paired }
 *
 * then starts pairing from the phone. Two things Android Auto has that Gearslip can't: the
 * phone's own Bluetooth address (apps get a placeholder), and confirming the pairing code without
 * asking. So a car may turn the request down, and the phone always shows its pairing dialog. The
 * channel stays closed unless the driver taps Pair: every new channel is a risk to cars that work.
 *
 * The one exception is a driver who typed the phone's real address into Settings. A VW MIB2 drops
 * an already paired phone's Bluetooth when it connects over USB, because nothing tells it the two
 * are the same phone (issue #28). With the address, Gearslip opens the channel on every connection
 * and sends the same request, which is how Android Auto says so.
 */
object CarBluetooth {

    enum class Prompt { NONE, ASK, PAIRING }

    private val _prompt = MutableStateFlow(Prompt.NONE)
    val prompt: StateFlow<Prompt> = _prompt

    private val log = GearslipLog.tagged("bluetooth")
    private val main = Handler(Looper.getMainLooper())
    private var appContext: Context? = null

    /** This session's car: its channel, and how to open and write to it. */
    private class Car(
        val service: ServiceDiscovery.BluetoothService,
        val open: () -> Unit,
        val send: (messageId: Int, body: ByteArray) -> Unit,
    )

    @Volatile private var car: Car? = null
    @Volatile private var bondStarted = false
    /** From the Pair tap until it succeeds or fails, whether or not the card still shows. */
    @Volatile private var pairing = false
    /** Already paired: the channel is open only to tell the car which phone this is. */
    @Volatile private var announcing = false
    private var receiver: BroadcastReceiver? = null

    /** The car's Bluetooth address, when it named one in its service list. */
    val carAddress: String? get() = car?.service?.carAddress

    /** The car's Bluetooth channel once the driver has asked to pair, -1 otherwise. */
    val channelId: Int get() = car?.takeIf { pairing || announcing }?.service?.serviceId ?: -1

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** Called once the car lists its services. Asks on the car screen only if there's pairing to do. */
    fun onCarConnected(
        service: ServiceDiscovery.BluetoothService,
        open: () -> Unit,
        send: (messageId: Int, body: ByteArray) -> Unit,
    ) {
        car = Car(service, open, send)
        bondStarted = false
        announcing = false
        log.i("the car offers Bluetooth pairing, methods=${service.pairingMethods}")
        val device = device() ?: return
        when {
            device.bondState == BluetoothDevice.BOND_BONDED && typedAddress() != null -> {
                log.i("already paired with this car - telling it which phone this is")
                announcing = true
                open()
            }
            device.bondState == BluetoothDevice.BOND_BONDED -> log.i("already paired with this car")
            declined(service.carAddress) -> log.i("not paired, and the driver said not now for this car")
            else -> _prompt.value = Prompt.ASK
        }
    }

    /**
     * Opens the car's Bluetooth channel though there's no pairing to do, as a real phone does on
     * every connection. Issue #18: a 2021 Bolt hangs up about 20 seconds in, and this is one of
     * the two channels it offers that Gearslip never opened. Without an address the driver typed,
     * it only opens the channel: a made-up address could set the car pairing with the wrong phone.
     */
    fun announce(why: String) {
        val c = car ?: return
        if (pairing || announcing) return
        announcing = true
        log.i("-> ChannelOpenRequest on channel ${c.service.serviceId} ($why)")
        c.open()
    }

    fun onCarDisconnected() {
        car = null
        pairing = false
        announcing = false
        main.post {
            stopWatching()
            _prompt.value = Prompt.NONE
        }
    }

    /** The driver tapped Pair. */
    fun pair() {
        val c = car ?: return
        val device = device() ?: return
        pairing = true
        _prompt.value = Prompt.PAIRING
        watchBonding(device)
        c.open()
        log.i("-> ChannelOpenRequest on channel ${c.service.serviceId}")
        // If the car never answers on its channel, pair from the phone anyway.
        main.postDelayed({ startBond("the car didn't answer the pairing request") }, CAR_REPLY_WAIT_MS)
        main.postDelayed({
            if (pairing) finish(R.string.bt_pair_unfinished)
        }, GIVE_UP_MS)
    }

    /** The driver tapped Not now: this car isn't asked about again. */
    fun notNow() {
        car?.service?.carAddress?.let { address ->
            val context = appContext ?: return@let
            val list = AppSettings.getString(context, KEY_DECLINED, "").split(",").filter { it.isNotEmpty() }
            AppSettings.putString(context, KEY_DECLINED, (list + hash(address)).distinct().joinToString(","))
        }
        _prompt.value = Prompt.NONE
    }

    /** Hides the pairing card. The phone's own dialog carries on. */
    fun hide() {
        _prompt.value = Prompt.NONE
    }

    fun onMessage(messageId: Int, body: ByteArray) {
        val c = car ?: return
        when (messageId) {
            MSG_CHANNEL_OPEN_RESPONSE -> {
                val status = Protobuf.readInt32Field(body, 1) ?: 0
                log.i("<- ChannelOpenResponse: status=$status")
                if (status != 0) {
                    if (announcing) log.i("the car wouldn't open its Bluetooth channel")
                    else main.post { startBond("the car wouldn't open its Bluetooth channel") }
                    return
                }
                if (announcing && typedAddress() == null) {
                    log.i("Bluetooth channel open; no address typed in Settings, so no PairingRequest")
                    return
                }
                val method = c.service.pairingMethods.firstOrNull { it == METHOD_NUMERIC_COMPARISON }
                    ?: c.service.pairingMethods.firstOrNull() ?: METHOD_NUMERIC_COMPARISON
                c.send(
                    MSG_PAIRING_REQUEST,
                    Protobuf.stringField(1, phoneAddress()) + Protobuf.varintField(2, method.toLong()),
                )
                log.i("-> PairingRequest(method=$method)")
            }
            MSG_PAIRING_RESPONSE -> {
                val fields = Wire.fields(body)
                log.i("<- PairingResponse status=${Wire.varint(fields, 1)} already_paired=${Wire.varint(fields, 2) == 1L}")
                if (!pairing) return
                // Some cars start pairing themselves once they answer; give them a moment first.
                main.postDelayed({ startBond("the car answered") }, CAR_BOND_WAIT_MS)
            }
            // Carries the pairing code. Android shows it to the driver, so it stays out of the log.
            MSG_AUTHENTICATION_DATA -> log.i("<- AuthenticationData (${body.size} bytes)")
            else -> log.i("<- unhandled message id=$messageId (${body.size} bytes)")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBond(why: String) {
        if (bondStarted || !pairing) return
        val device = device() ?: return
        if (device.bondState != BluetoothDevice.BOND_NONE) return
        bondStarted = true
        val started = runCatching { device.createBond() }.getOrDefault(false)
        log.i("pairing from the phone ($why): ${if (started) "started" else "refused"}")
        if (!started) finish(R.string.bt_pair_not_started)
    }

    private fun watchBonding(device: BluetoothDevice) {
        val context = appContext ?: return
        stopWatching()
        val r = object : BroadcastReceiver() {
            @Suppress("DEPRECATION")
            override fun onReceive(context: Context, intent: Intent) {
                val changed = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                if (changed.address != device.address) return
                val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                val previous = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE)
                log.i("bond state $previous -> $state")
                when {
                    state == BluetoothDevice.BOND_BONDING -> bondStarted = true
                    state == BluetoothDevice.BOND_BONDED -> finish(R.string.bt_paired)
                    previous == BluetoothDevice.BOND_BONDING -> finish(R.string.bt_pair_unfinished)
                }
            }
        }
        context.registerReceiver(r, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), Context.RECEIVER_EXPORTED)
        receiver = r
    }

    private fun finish(@StringRes message: Int) {
        pairing = false
        stopWatching()
        main.removeCallbacksAndMessages(null)
        _prompt.value = Prompt.NONE
        CarToasts.show(this, appContext?.getString(message) ?: return, CarToast.LENGTH_LONG)
    }

    private fun stopWatching() {
        val r = receiver ?: return
        receiver = null
        runCatching { appContext?.unregisterReceiver(r) }
    }

    /** The car as a Bluetooth device, or null (and logged) when Gearslip can't reach Bluetooth. */
    private fun device(): BluetoothDevice? {
        val context = appContext ?: return null
        val address = car?.service?.carAddress ?: return null
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            log.i("can't check pairing: the Bluetooth permission isn't granted")
            return null
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            log.i("can't check pairing: Bluetooth is off")
            return null
        }
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            log.w("the car sent an address that isn't one")
            return null
        }
        return adapter.getRemoteDevice(address)
    }

    /**
     * Apps get 02:00:00:00:00:00 here, not the real address, so the one the driver typed in wins.
     * Sent either way, as the field is required. Never logged: it identifies the phone.
     */
    @SuppressLint("MissingPermission", "HardwareIds")
    private fun phoneAddress(): String =
        typedAddress()
            ?: runCatching { appContext?.getSystemService(BluetoothManager::class.java)?.adapter?.address }.getOrNull()
            ?: "02:00:00:00:00:00"

    private fun typedAddress(): String? = appContext?.let { typedAddress(it) }

    /** The phone's own address as the driver typed it in Settings, or null. */
    fun typedAddress(context: Context): String? =
        AppSettings.getString(context, KEY_PHONE_ADDRESS, "").takeIf { BluetoothAdapter.checkBluetoothAddress(it) }

    /** Saves [address] after tidying it up; false when it isn't one. Empty clears it. */
    fun setTypedAddress(context: Context, address: String): Boolean {
        val tidy = address.trim().uppercase().replace('-', ':')
        if (tidy.isNotEmpty() && !BluetoothAdapter.checkBluetoothAddress(tidy)) return false
        AppSettings.putString(context, KEY_PHONE_ADDRESS, tidy)
        return true
    }

    private fun declined(address: String): Boolean {
        val context = appContext ?: return false
        return hash(address) in AppSettings.getString(context, KEY_DECLINED, "").split(",")
    }

    /** Settings keep a short hash, not the car's address. */
    private fun hash(address: String): String =
        MessageDigest.getInstance("SHA-256").digest(address.uppercase().toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }

    private const val KEY_DECLINED = "bluetooth_pair_declined"
    private const val KEY_PHONE_ADDRESS = "phone_bluetooth_address"
    private const val CAR_REPLY_WAIT_MS = 5_000L
    private const val CAR_BOND_WAIT_MS = 3_000L
    private const val GIVE_UP_MS = 90_000L
    private const val METHOD_NUMERIC_COMPARISON = 2
    private const val MSG_CHANNEL_OPEN_RESPONSE = 8
    private const val MSG_PAIRING_REQUEST = 32769
    private const val MSG_PAIRING_RESPONSE = 32770
    private const val MSG_AUTHENTICATION_DATA = 32771
}
