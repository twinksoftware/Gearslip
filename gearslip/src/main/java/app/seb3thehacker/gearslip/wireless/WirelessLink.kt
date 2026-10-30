package app.seb3thehacker.gearslip.wireless

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.net.ConnectivityManager
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.provider.Settings
import app.seb3thehacker.gearslip.GearslipLog
import app.seb3thehacker.gearslip.Protobuf
import app.seb3thehacker.gearslip.SessionStatus
import app.seb3thehacker.gearslip.Wire
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.Socket
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Wireless Android Auto, phone side: the start-up that hands Gearslip a socket instead of a USB
 * accessory. Everything after it - versions, TLS, the certificate, channels, video - is the same
 * protocol [app.seb3thehacker.gearslip.GearslipRunner] already speaks over USB.
 *
 *   1. Bluetooth: open RFCOMM to the car's Android Auto wireless service. The car must already be
 *      paired with the phone, as it usually is for calls.
 *   2. Over that channel, framed as [u16 length][u16 message id][protobuf]:
 *        <- VersionRequest            -> VersionResponse
 *        <- StartRequest {ip, port}   -> InfoRequest
 *        <- InfoResponse {ssid, password, bssid, security}
 *   3. Join the car's Wi-Fi as a local-only network, so the phone's internet stays on mobile data,
 *      and say so over Bluetooth before connecting:
 *        -> StartResponse {phone ip, status}    -> ConnectionStatus {status}
 *   4. TCP to {ip, port} over that network, and hand the socket to [onConnected].
 *
 * The Bluetooth channel stays open for the whole drive: the car pings over it, and some cars end
 * the session when it closes. Message layout from LIVI's head-unit side (livi-aa/src/wpp.rs) and
 * aasdk's aaw protos.
 */
object WirelessLink {

    private val log = GearslipLog.tagged("AAW")

    /** The Android Auto wireless service every head unit publishes over Bluetooth. */
    val AA_UUID: UUID = UUID.fromString("4de17a00-52cb-11e6-bdf4-0800200c9a66")

    private const val MSG_START_REQUEST = 1
    private const val MSG_INFO_REQUEST = 2
    private const val MSG_INFO_RESPONSE = 3
    /**
     * The wireless setup's status for success, shared by every message that carries one.
     * aa-proxy-rs sends 0 to real cars and they carry on; a Renault Media Nav given 1 went quiet
     * and closed the channel. (open-android-auto's table, which says 1, is marked unverified.)
     */
    private const val STATUS_SUCCESS = 0L
    private const val MSG_VERSION_REQUEST = 4
    private const val MSG_VERSION_RESPONSE = 5
    private const val MSG_CONNECTION_STATUS = 6
    private const val MSG_START_RESPONSE = 7
    private const val MSG_PING = 8
    private const val MSG_PONG = 9

    private const val LISTEN_MS = 120_000 // time to reconnect the phone from the car's Bluetooth menu
    private const val TCP_TRIES = 12 // about a minute: a car's projection listener can start well after its Wi-Fi
    private const val WIFI_WAIT_MS = 60_000
    /** How long an automatic start keeps calling a car whose Android Auto isn't up yet. */
    private const val AUTO_CALL_MS = 60_000L
    private const val AUTO_CALL_GAP_MS = 3_000L
    /** Per car, by Bluetooth address: true when its music channel takes only AAC. */
    private const val PREFS = "gearslip_wireless_cars"

    /** Set by the activity: starts a session on the car's socket. */
    @Volatile var onConnected: ((Socket) -> Unit)? = null

    @Volatile private var rfcomm: BluetoothSocket? = null
    @Volatile private var listener: BluetoothServerSocket? = null
    @Volatile private var tcp: Socket? = null
    @Volatile private var network: ConnectivityManager.NetworkCallback? = null
    @Volatile private var worker: Thread? = null
    /** The car of the latest start, so what its session learns is kept for it. */
    @Volatile private var carAddress: String? = null
    @Volatile private var appContext: Context? = null

    /** A start-up or a session's Bluetooth channel is under way. */
    val busy: Boolean get() = worker?.isAlive == true
    /** Set when Android drops the car's network mid-try, so the rest don't bind to a dead one. */
    @Volatile private var lostNetwork: Network? = null
    /** The car network asked for or joined, until Android says it's gone or never came. */
    @Volatile private var activeSsid: String? = null
    /** Bumped on each Wi-Fi join and on stop, so an older run of TCP tries ends instead of racing the new one. */
    @Volatile private var tcpRun = 0
    private val writeLock = Any()

    /** Paired Bluetooth devices, cars or not: the driver picks theirs. Needs BLUETOOTH_CONNECT. */
    @SuppressLint("MissingPermission")
    fun pairedDevices(context: Context): List<BluetoothDevice> =
        context.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices
            ?.sortedBy { it.name ?: it.address }
            .orEmpty()

    @SuppressLint("MissingPermission")
    fun name(device: BluetoothDevice): String = device.name ?: device.address

    /**
     * [automatic] is a start from [WirelessAuto]: the phone has only just reached the car over
     * Bluetooth, and a head unit that is still booting brings Android Auto up some seconds after
     * its Bluetooth. Most cars call the phone once they're up, so they get one call and are then
     * waited for. A car whose music channel takes only AAC is called again for a while instead:
     * the one such car seen so far, an AHU (GalReceiver 2014), never calls the phone itself.
     */
    fun start(context: Context, device: BluetoothDevice, automatic: Boolean = false) {
        stop(context)
        val app = context.applicationContext
        appContext = app
        carAddress = device.address
        worker = Thread({
            // A test feature: anything unexpected ends the attempt, not the app.
            try {
                run(app, device, automatic)
            } catch (e: Exception) {
                log.e("wireless start-up failed", e)
                SessionStatus.failed("Wireless start-up failed", "${e.javaClass.simpleName}: ${e.message}")
            }
        }, "gearslip-wireless").apply { isDaemon = true; start() }
    }

    /** Closes the Bluetooth channel, the socket and the Wi-Fi request. Safe to call any time. */
    fun stop(context: Context) {
        tcpRun++
        val starting = worker != null && SessionStatus.state.value.phase == SessionStatus.Phase.CONNECTING
        worker?.interrupt()
        worker = null
        runCatching { listener?.close() }
        listener = null
        runCatching { rfcomm?.close() }
        rfcomm = null
        runCatching { tcp?.close() }
        tcp = null
        network?.let { cb ->
            runCatching { context.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) }
        }
        network = null
        activeSsid = null
        // Stopped before the car's session began, so nothing else resets Home's card.
        if (starting) {
            log.i("wireless start-up stopped")
            SessionStatus.disconnected()
        }
    }

    /**
     * From the session, once the car has listed its audio sinks: whether its music channel takes
     * only AAC. Kept per car for the next automatic start; see [start].
     */
    fun carTakesOnlyAac(onlyAac: Boolean) {
        val context = appContext ?: return
        val address = carAddress ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(address, onlyAac).apply()
    }

    private fun takesOnlyAac(context: Context, address: String) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(address, false)

    /** Set to the car's name when a VPN kept the phone from reaching it; the phone app shows a dialog. */
    val vpnWarning: StateFlow<String?> get() = _vpnWarning
    private val _vpnWarning = MutableStateFlow<String?>(null)

    fun dismissVpnWarning() {
        _vpnWarning.value = null
    }

    /** A stable id for this phone as this app sees it, standing in for the serial a real phone sends. */
    @SuppressLint("HardwareIds")
    private fun deviceId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "gearslip"

    @SuppressLint("MissingPermission")
    private fun run(context: Context, device: BluetoothDevice, automatic: Boolean) {
        val name = name(device)
        log.i("--- wireless start-up with $name (${device.address}) ---")
        SessionStatus.connecting("Bluetooth: reaching $name")
        val keepCalling = automatic && takesOnlyAac(context, device.address)
        if (automatic && !keepCalling) log.i("Bluetooth: $name isn't known to take only AAC - calling once, then waiting for it")
        val socket = try {
            // A running scan slows the connect, but stopping one needs BLUETOOTH_SCAN on Android
            // 12+, which Gearslip doesn't ask for. Gearslip never scans, so skipping it is fine.
            runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter?.cancelDiscovery() }
            if (keepCalling) callUntilUp(device, name) else device.createRfcommSocketToServiceRecord(AA_UUID).also { it.connect() }
        } catch (e: SecurityException) {
            log.e("Bluetooth: permission refused while opening the channel on $name", e)
            SessionStatus.failed(
                "Bluetooth permission missing",
                "Gearslip needs the Nearby devices permission to reach $name. Allow it in the app's " +
                    "settings, then try again.",
            )
            return
        } catch (e: IOException) {
            // Most factory head units publish no service of their own: they look for the phone's
            // and connect to it, so wait for the car instead.
            log.i("Bluetooth: $name has no Android Auto service to call (${e.message}); waiting for it to call us")
            waitForCar(context, name) ?: return
        }
        rfcomm = socket
        log.i("Bluetooth: channel open")
        SessionStatus.connecting("Bluetooth: talking to $name")

        val input = DataInputStream(socket.inputStream)
        val output = socket.outputStream
        var target: Pair<String, Int>? = null
        // Wi-Fi details that came before the address, joined once the StartRequest brings it.
        var waitingJoin: ((String, Int) -> Unit)? = null
        try {
            while (!Thread.currentThread().isInterrupted) {
                val length = input.readUnsignedShort()
                val id = input.readUnsignedShort()
                val body = ByteArray(length).also { input.readFully(it) }
                when (id) {
                    MSG_VERSION_REQUEST -> {
                        log.i("<- VersionRequest")
                        log.hex("   version", body)
                        // Laid out the way a real phone's reply is (aa-proxy-rs and LIVI both
                        // parse it from real phones): 1 and 2 the version, echoing the car's;
                        // 3 a serial; 4 the status; 6 the device's ids.
                        val f = Wire.fields(body)
                        val serial = deviceId(context)
                        val ids = Protobuf.stringField(1, serial) + Protobuf.stringField(2, UUID.randomUUID().toString())
                        val reply = Protobuf.varintField(1, Wire.varint(f, 1) ?: 1) +
                            Protobuf.varintField(2, Wire.varint(f, 2) ?: 0) +
                            Protobuf.stringField(3, serial) +
                            Protobuf.varintField(4, STATUS_SUCCESS) +
                            Protobuf.varint(((6 shl 3) or 2).toLong()) + Protobuf.varint(ids.size.toLong()) + ids
                        send(output, MSG_VERSION_RESPONSE, reply)
                        log.i("-> VersionResponse")
                        log.hex("   reply", reply)
                    }
                    MSG_START_REQUEST -> {
                        val f = Wire.fields(body)
                        val ip = f.firstOrNull { it.number == 1 && it.wireType == 2 }?.bytes?.decodeToString().orEmpty()
                        val port = Wire.varint(f, 2)?.toInt() ?: 0
                        log.i("<- StartRequest: projection at $ip:$port")
                        target = ip to port
                        val join = waitingJoin
                        if (join != null) {
                            waitingJoin = null
                            join(ip, port)
                        } else {
                            send(output, MSG_INFO_REQUEST, ByteArray(0))
                            log.i("-> InfoRequest")
                        }
                    }
                    MSG_INFO_RESPONSE -> {
                        val f = Wire.fields(body)
                        fun text(n: Int) = f.firstOrNull { it.number == n && it.wireType == 2 }?.bytes?.decodeToString().orEmpty()
                        val ssid = text(1)
                        val password = text(2)
                        val bssid = text(3)
                        val security = Wire.varint(f, 4) ?: -1
                        log.i("<- InfoResponse: ssid=\"$ssid\" bssid=$bssid security=$security password=${if (password.isEmpty()) "none" else "(${password.length} chars)"}")
                        // ZLink head units send the Wi-Fi details again once the stream is up.
                        // Asking Android for the network again releases the one carrying the
                        // stream, which aborts its socket; a phone running Android Auto just
                        // carries on, so the repeat is ignored while that network is in use.
                        if (ssid == activeSsid) {
                            log.i("InfoResponse again for \"$ssid\", which the phone is already on - ignoring it")
                            continue
                        }
                        val join = { ip: String, port: Int -> joinWifi(context, output, name, ssid, password, bssid, ip, port) }
                        val known = target
                        if (known != null) {
                            join(known.first, known.second)
                        } else {
                            // Some dongles send the Wi-Fi details first; the address follows a moment later.
                            log.i("InfoResponse before StartRequest: waiting for the address")
                            waitingJoin = join
                        }
                    }
                    MSG_PING -> send(output, MSG_PONG, body)
                    MSG_CONNECTION_STATUS, MSG_START_RESPONSE -> {
                        log.i("<- message $id: status=${Wire.varint(Wire.fields(body), if (id == MSG_START_RESPONSE) 3 else 1)}")
                    }
                    else -> {
                        log.i("<- unknown message $id (${body.size} bytes)")
                        log.hex("   msg", body)
                    }
                }
            }
        } catch (e: IOException) {
            if (rfcomm != null) log.w("Bluetooth: channel closed (${e.message})")
        }
    }

    /**
     * Calls the car's Android Auto service until it answers or [AUTO_CALL_MS] runs out, then
     * throws the last failure like a single call would.
     */
    @SuppressLint("MissingPermission")
    private fun callUntilUp(device: BluetoothDevice, name: String): BluetoothSocket {
        val until = System.currentTimeMillis() + AUTO_CALL_MS
        var tries = 0
        while (true) {
            tries++
            try {
                return device.createRfcommSocketToServiceRecord(AA_UUID).also {
                    rfcomm = it
                    it.connect()
                    if (tries > 1) log.i("Bluetooth: $name answered on try $tries")
                }
            } catch (e: IOException) {
                rfcomm = null
                if (Thread.currentThread().isInterrupted || System.currentTimeMillis() + AUTO_CALL_GAP_MS > until) throw e
                if (tries == 1) {
                    log.i("Bluetooth: $name didn't answer yet (${e.message}); trying again for ${AUTO_CALL_MS / 1000}s while it starts up")
                    SessionStatus.connecting("Bluetooth: waiting for $name to start up")
                }
                try {
                    Thread.sleep(AUTO_CALL_GAP_MS)
                } catch (_: InterruptedException) {
                    throw e
                }
            }
        }
    }

    /**
     * Publishes the Android Auto service on the phone, the way Android Auto itself does, and waits
     * for the car to connect to it. A car looks for the service when the phone connects over
     * Bluetooth, so the driver may have to reconnect the phone from the car's menu.
     */
    @SuppressLint("MissingPermission")
    private fun waitForCar(context: Context, name: String): BluetoothSocket? {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        val server = try {
            adapter.listenUsingRfcommWithServiceRecord("Android Auto", AA_UUID)
        } catch (e: Exception) {
            log.e("Bluetooth: couldn't publish the Android Auto service", e)
            SessionStatus.failed("Couldn't publish the Android Auto service", "${e.javaClass.simpleName}: ${e.message}")
            return null
        }
        listener = server
        SessionStatus.connecting("Waiting for $name. If nothing happens, disconnect the phone in the car's Bluetooth menu and connect it again")
        log.i("Bluetooth: Android Auto service published, waiting ${LISTEN_MS / 1000}s for $name")
        return try {
            server.accept(LISTEN_MS).also {
                val from = runCatching { it.remoteDevice.name }.getOrNull() ?: "a device"
                log.i("Bluetooth: $from connected to the Android Auto service")
            }
        } catch (e: IOException) {
            if (listener == null) return null // stopped
            log.w("Bluetooth: no car connected within ${LISTEN_MS / 1000}s (${e.message})")
            SessionStatus.failed(
                "Car didn't connect over Bluetooth",
                "$name didn't open Android Auto with the phone. Check that wireless Android Auto is " +
                    "on in the car's settings, then reconnect the phone from the car's Bluetooth menu.",
            )
            null
        } finally {
            runCatching { server.close() }
            listener = null
        }
    }

    private fun send(output: OutputStream, id: Int, body: ByteArray) {
        val frame = ByteArray(4 + body.size)
        frame[0] = (body.size shr 8).toByte(); frame[1] = body.size.toByte()
        frame[2] = (id shr 8).toByte(); frame[3] = id.toByte()
        body.copyInto(frame, 4)
        synchronized(writeLock) { output.write(frame); output.flush() }
    }

    /**
     * Asks Android for the car's network. The first time, Android shows a "Connect to device"
     * prompt on the phone; the driver has to accept it.
     */
    private fun joinWifi(
        context: Context, output: OutputStream, name: String,
        ssid: String, password: String, bssid: String, ip: String, port: Int,
    ) {
        SessionStatus.connecting("Wi-Fi: joining $name's network")
        val spec = WifiNetworkSpecifier.Builder().setSsid(ssid).apply {
            runCatching { MacAddress.fromString(bssid) }.getOrNull()
                ?.takeIf { it != MacAddress.fromString("00:00:00:00:00:00") }
                ?.let { setBssid(it) }
            if (password.isNotEmpty()) setWpa2Passphrase(password)
        }.build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec)
            .build()
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(net: Network) {
                log.i("Wi-Fi: joined \"$ssid\"")
                // Real phones report the join over Bluetooth before opening TCP, and some cars
                // only start listening once they hear it.
                val phoneIp = manager.getLinkProperties(net)?.linkAddresses
                    ?.firstOrNull { it.address is java.net.Inet4Address }?.address?.hostAddress.orEmpty()
                runCatching {
                    send(output, MSG_START_RESPONSE, Protobuf.stringField(1, phoneIp) + Protobuf.varintField(3, STATUS_SUCCESS))
                    send(output, MSG_CONNECTION_STATUS, Protobuf.varintField(1, STATUS_SUCCESS))
                    log.i("-> StartResponse (phone at $phoneIp), ConnectionStatus: success")
                }.onFailure { log.w("Bluetooth: couldn't report the Wi-Fi join (${it.message})") }
                val run = ++tcpRun
                Thread({ connectTcp(context, output, name, net, ip, port, run) }, "gearslip-wireless-tcp").start()
            }

            override fun onUnavailable() {
                if (activeSsid == ssid) activeSsid = null
                log.w("Wi-Fi: couldn't join \"$ssid\" (declined, out of range, or wrong password)")
                runCatching { send(output, MSG_CONNECTION_STATUS, Protobuf.varintField(1, -1)) }
                SessionStatus.failed(
                    "Couldn't join the car's Wi-Fi",
                    "Android didn't connect to $name's network. If a \"Connect to device\" prompt " +
                        "appeared, it has to be accepted.",
                )
            }

            // Android can hold an app's traffic back on a network it has joined (battery or data
            // limits, local-network rules); the packets are then dropped without an error.
            override fun onBlockedStatusChanged(net: Network, blocked: Boolean) {
                log.i("Wi-Fi: Android ${if (blocked) "BLOCKS" else "allows"} Gearslip's traffic on \"$ssid\"")
            }

            private var capsLogged = false
            override fun onCapabilitiesChanged(net: Network, caps: NetworkCapabilities) {
                if (capsLogged) return
                capsLogged = true
                log.i("Wi-Fi: \"$ssid\" capabilities $caps")
            }

            override fun onLost(net: Network) {
                lostNetwork = net
                if (activeSsid == ssid) activeSsid = null
                log.w(
                    "Wi-Fi: lost \"$ssid\" - Android dropped the car's network. Picking it by hand " +
                        "in the Wi-Fi list does this; the remaining tries go over the phone's own Wi-Fi",
                )
            }
        }
        network?.let { runCatching { manager.unregisterNetworkCallback(it) } }
        network = callback
        activeSsid = ssid
        manager.requestNetwork(request, callback, WIFI_WAIT_MS)
    }

    /**
     * Run once when the first TCP try fails, to tell "the car ignores us" from "wrong port". A
     * refused port answers at once, so it proves the car is reachable; a timeout on every port
     * means the car drops the phone's traffic, usually because it is still waiting for a step.
     */
    @SuppressLint("MissingPermission")
    private fun probe(context: Context, net: Network, ip: String, port: Int) {
        runCatching {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val link = manager?.getLinkProperties(net)
            log.i("probe: phone ${link?.linkAddresses?.joinToString()} on ${link?.interfaceName}, routes ${link?.routes?.joinToString()}")
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            val calls = adapter?.getProfileConnectionState(BluetoothProfile.HEADSET)
            val audio = adapter?.getProfileConnectionState(BluetoothProfile.A2DP)
            log.i("probe: Bluetooth (any device) calls ${profileState(calls)}, media audio ${profileState(audio)}")
            log.i(
                "probe: background data ${manager?.restrictBackgroundStatus} (1 off, 2 allowed, 3 restricted), " +
                    "active network ${manager?.activeNetwork}, car network $net, VPN ${if (vpnActive(context)) "on" else "off"}",
            )
        }.onFailure { log.w("probe: couldn't read the link (${it.message})") }
        for (p in listOf(port, 5288, 5277, 80, 443).distinct()) {
            val result = try {
                net.socketFactory.createSocket().use { it.connect(InetSocketAddress(ip, p), 1_500) }
                "open"
            } catch (e: SocketTimeoutException) {
                "no answer"
            } catch (e: ConnectException) {
                if (e.message?.contains("ECONNREFUSED") == true) "refused (car is reachable)" else "failed: ${e.message}"
            } catch (e: Exception) {
                "failed: ${e.javaClass.simpleName} ${e.message}"
            }
            log.i("probe: $ip:$p $result")
        }
    }

    /** Whether a VPN carries this app's traffic. Android then refuses to bind sockets to the car's network (EPERM). */
    private fun vpnActive(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        manager.getNetworkCapabilities(manager.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }.getOrDefault(false)

    private fun profileState(state: Int?) = when (state) {
        BluetoothProfile.STATE_CONNECTED -> "connected"
        BluetoothProfile.STATE_CONNECTING -> "connecting"
        BluetoothProfile.STATE_DISCONNECTED -> "not connected"
        else -> "unknown"
    }

    /** Another network the phone has with an address in the car's /24, or null. */
    private fun mainOnCarNetwork(context: Context, ip: String, except: Network): Network? {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val prefix = ip.substringBeforeLast('.') + "."
        return manager.allNetworks.firstOrNull { candidate ->
            candidate != except && manager.getLinkProperties(candidate)?.linkAddresses
                ?.any { it.address is java.net.Inet4Address && it.address.hostAddress?.startsWith(prefix) == true } == true
        }
    }

    private fun connectTcp(context: Context, output: OutputStream, name: String, net: Network, ip: String, port: Int, run: Int) {
        SessionStatus.connecting("Wi-Fi: reaching $name")
        lostNetwork = null
        var last: Exception? = null
        // Set once Android refuses to bind to the car's network, which a VPN causes. Plain sockets
        // then go through the VPN, and VPNs that let local traffic past still reach the car.
        var bindRefused = false
        repeat(TCP_TRIES) { attempt ->
            if (run != tcpRun) {
                log.i("TCP: stopping the tries on the old network - the car started over")
                return
            }
            try {
                // A network Android has dropped refuses every bind (EPERM). If the driver joined the
                // car's Wi-Fi by hand, the phone's ordinary route reaches it, so use that instead.
                // The phone's main Wi-Fi on the car's network wins: that is how Android Auto itself
                // connects, and a Pixel 6 passed pings but no TCP over the side connection Android
                // opens for Gearslip's request.
                val main = mainOnCarNetwork(context, ip, net)
                if (main != null && attempt == 0) log.i("TCP: the phone's main Wi-Fi is on the car's network, using it")
                val socket = when {
                    main != null -> main.socketFactory.createSocket()
                    lostNetwork == net || bindRefused -> Socket()
                    else -> net.socketFactory.createSocket()
                }
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(ip, port), 5_000)
                tcp = socket
                log.i("TCP: connected to $ip:$port (try ${attempt + 1})")
                val start = onConnected
                if (start == null) {
                    log.w("TCP: connected, but nothing is waiting for the socket")
                    socket.close()
                } else {
                    start(socket)
                }
                return
            } catch (e: Exception) {
                last = e
                log.w("TCP: try ${attempt + 1} to $ip:$port failed: ${e.message}")
                if (!bindRefused && e.message?.contains("EPERM") == true) {
                    bindRefused = true
                    log.i("TCP: Android won't let Gearslip use the car's network directly; trying the phone's ordinary route")
                }
                if (attempt == 0) probe(context, net, ip, port)
                Thread.sleep(1_000)
            }
        }
        log.e("TCP: gave up on $ip:$port", last)
        if (vpnActive(context)) {
            _vpnWarning.value = name
            SessionStatus.failed(
                "A VPN is blocking the car",
                "Gearslip joined $name's network, but a VPN on this phone won't let it reach the car. " +
                    "Turn the VPN off, or exclude Gearslip in its settings, then try again.",
            )
            return
        }
        SessionStatus.failed(
            "Car's Wi-Fi joined, but no connection",
            "Gearslip joined $name's network but couldn't reach Android Auto at $ip:$port.",
        )
    }
}
