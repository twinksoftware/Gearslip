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
    private const val KNOCK_MS = 8_000L // some cars publish their service only a while after Bluetooth connects
    private const val START_RETRIES = 3
    private const val RETRY_PAUSE_MS = 2_000L

    /** Set by the activity: starts a session on the car's socket. */
    @Volatile var onConnected: ((Socket) -> Unit)? = null

    /** Set by the activity: whether the session on the socket still waits for the car's first message. */
    @Volatile var sessionWaiting: (() -> Boolean)? = null

    @Volatile private var rfcomm: BluetoothSocket? = null
    @Volatile private var listener: BluetoothServerSocket? = null
    @Volatile private var tcp: Socket? = null
    @Volatile private var network: ConnectivityManager.NetworkCallback? = null
    @Volatile private var worker: Thread? = null

    /** A start-up or a session's Bluetooth channel is under way. */
    val busy: Boolean get() = worker?.isAlive == true
    /** Set when Android drops the car's network mid-try, so the rest don't bind to a dead one. */
    @Volatile private var lostNetwork: Network? = null
    /** The car network asked for or joined, until Android says it's gone or never came. */
    @Volatile private var activeSsid: String? = null
    /** The car's network once joined, for connecting again on it. */
    @Volatile private var joinedNet: Network? = null
    /** Bumped on each Wi-Fi join and on stop, so an older run of TCP tries ends instead of racing the new one. */
    @Volatile private var tcpRun = 0
    /** The socket reached the car's projection: whatever ends the start-up now, it isn't retried. */
    @Volatile private var handedOver = false
    /** A failure starting over can't fix, such as a VPN in the way. */
    @Volatile private var noRetry = false
    private val writeLock = Any()

    /** Paired Bluetooth devices, cars or not: the driver picks theirs. Needs BLUETOOTH_CONNECT. */
    @SuppressLint("MissingPermission")
    fun pairedDevices(context: Context): List<BluetoothDevice> =
        context.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices
            ?.sortedBy { name(it) }
            .orEmpty()

    @SuppressLint("MissingPermission")
    fun name(device: BluetoothDevice): String = device.alias ?: device.name ?: device.address

    fun start(context: Context, device: BluetoothDevice) {
        stop(context)
        val app = context.applicationContext
        worker = Thread({
            // A test feature: anything unexpected ends the attempt, not the app.
            try {
                startUp(app, device)
            } catch (e: Exception) {
                log.e("wireless start-up failed", e)
                SessionStatus.failed("Wireless start-up failed", "${e.javaClass.simpleName}: ${e.message}")
            }
        }, "gearslip-wireless").apply { isDaemon = true; start() }
    }

    /**
     * A step that fails after the car's Bluetooth channel opened (the channel closing, the Wi-Fi
     * join, TCP) often works the second time, so the start-up begins again, up to
     * [START_RETRIES] times. Waiting for a car that never called isn't repeated: that wait is
     * two minutes already.
     */
    private fun startUp(context: Context, device: BluetoothDevice) {
        val me = Thread.currentThread()
        try {
            tryAndRetry(context, device, me)
        } finally {
            // A newer start-up has the service now; stop() already ended this one's.
            if (worker === me) WirelessKeepAlive.stop(context)
        }
    }

    private fun tryAndRetry(context: Context, device: BluetoothDevice, me: Thread) {
        var tries = 0
        while (true) {
            handedOver = false
            noRetry = false
            val reached = run(context, device)
            if (worker !== me || handedOver || noRetry || !reached) return
            if (++tries > START_RETRIES) {
                log.w("start-up failed ${tries} times; giving up")
                return
            }
            log.i("start-up failed before the car's screen came up; starting over ($tries of $START_RETRIES)")
            release(context)
            SessionStatus.connecting("Starting over with ${name(device)} ($tries of $START_RETRIES)")
            try {
                Thread.sleep(RETRY_PAUSE_MS)
            } catch (_: InterruptedException) {
                return
            }
            if (worker !== me) return
        }
    }

    /** Ends this try: the Bluetooth read loop stops, and [startUp] decides whether to go again. */
    private fun endTry() {
        runCatching { rfcomm?.close() }
    }

    /** Closes the Bluetooth channel, the socket and the Wi-Fi request. Safe to call any time. */
    fun stop(context: Context) {
        val starting = worker != null && SessionStatus.state.value.phase == SessionStatus.Phase.CONNECTING
        worker?.interrupt()
        worker = null
        release(context)
        WirelessKeepAlive.stop(context)
        // Stopped before the car's session began, so nothing else resets Home's card.
        if (starting) {
            log.i("wireless start-up stopped")
            SessionStatus.disconnected()
        }
    }

    private fun release(context: Context) {
        tcpRun++
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
        joinedNet = null
    }

    /** A stable id for this phone as this app sees it, standing in for the serial a real phone sends. */
    @SuppressLint("HardwareIds")
    private fun deviceId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "gearslip"

    /** One try. True if the car's Bluetooth channel opened, so that a failure after it is worth retrying. */
    @SuppressLint("MissingPermission")
    private fun run(context: Context, device: BluetoothDevice): Boolean {
        val name = name(device)
        log.i("--- wireless start-up with $name (${device.address}) ---")
        SessionStatus.connecting("Bluetooth: reaching $name")
        val socket = try {
            // A running scan slows the connect, but stopping one needs BLUETOOTH_SCAN on Android
            // 12+, which Gearslip doesn't ask for. Gearslip never scans, so skipping it is fine.
            runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter?.cancelDiscovery() }
            device.createRfcommSocketToServiceRecord(AA_UUID).also { it.connect() }
        } catch (e: SecurityException) {
            log.e("Bluetooth: permission refused while opening the channel on $name", e)
            SessionStatus.failed(
                "Bluetooth permission missing",
                "Gearslip needs the Nearby devices permission to reach $name. Allow it in the app's " +
                    "settings, then try again.",
            )
            return false
        } catch (e: IOException) {
            // Most factory head units publish no service of their own: they look for the phone's
            // and connect to it, so wait for the car instead.
            log.i("Bluetooth: $name has no Android Auto service to call (${e.message}); waiting for it to call us")
            waitForCar(context, device, name) ?: return false
        }
        rfcomm = socket
        log.i("Bluetooth: channel open")
        // Before the Wi-Fi request: Android may refuse one from an app it sees as in the background.
        WirelessKeepAlive.start(context)
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
                        // 3 a serial; 4 the status; 5 the Wi-Fi channel type the car asked for;
                        // 6 the device's ids. Strict head units, such as Toyota's, hold back the
                        // StartRequest until the reply carries more than the status.
                        val f = Wire.fields(body)
                        val serial = deviceId(context)
                        val ids = Protobuf.stringField(1, serial) + Protobuf.stringField(2, UUID.randomUUID().toString())
                        val channelType = Wire.varint(f, 3)
                        val reply = Protobuf.varintField(1, Wire.varint(f, 1) ?: 1) +
                            Protobuf.varintField(2, Wire.varint(f, 2) ?: 0) +
                            Protobuf.stringField(3, serial) +
                            Protobuf.varintField(4, STATUS_SUCCESS) +
                            (channelType?.let { Protobuf.varintField(5, it) } ?: ByteArray(0)) +
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
                        // The car asks again while the connection it got still hasn't heard from
                        // it: it started its side over, and waits on a fresh connection. Issue #18:
                        // a Bolt left Gearslip waiting 88 seconds on the old one, then hung up.
                        // A session already under way is left alone.
                        val net = joinedNet
                        if (net != null && activeSsid != null && handedOver && sessionWaiting?.invoke() == true) {
                            log.w("the car asked again while the connection still waits for it - connecting again")
                            val run = ++tcpRun
                            Thread({ connectTcp(context, output, name, net, ip, port, run) }, "gearslip-wireless-tcp").start()
                            continue
                        }
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
        return true
    }

    /**
     * Publishes the Android Auto service on the phone, the way Android Auto itself does, and waits
     * for the car to connect to it. A car looks for the service when the phone connects over
     * Bluetooth, so the driver may have to reconnect the phone from the car's menu.
     */
    @SuppressLint("MissingPermission")
    private fun waitForCar(context: Context, device: BluetoothDevice, name: String): BluetoothSocket? {
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
        // Meanwhile, keep calling the car's own service: a car that publishes it late answers one
        // of these instead of calling us. Whichever connects first is the channel.
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val knocked = java.util.concurrent.atomic.AtomicReference<BluetoothSocket?>(null)
        val knocker = Thread({
            while (!done.get()) {
                try {
                    Thread.sleep(KNOCK_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (done.get()) return@Thread
                val socket = runCatching { device.createRfcommSocketToServiceRecord(AA_UUID).also { it.connect() } }
                    .getOrNull() ?: continue
                if (done.compareAndSet(false, true)) {
                    log.i("Bluetooth: $name's own Android Auto service answered")
                    knocked.set(socket)
                    runCatching { server.close() }
                } else {
                    runCatching { socket.close() }
                }
                return@Thread
            }
        }, "gearslip-wireless-knock").apply { isDaemon = true; start() }
        return try {
            server.accept(LISTEN_MS).also {
                if (!done.compareAndSet(false, true)) runCatching { knocked.get()?.close() }
                val from = runCatching { it.remoteDevice.name }.getOrNull() ?: "a device"
                log.i("Bluetooth: $from connected to the Android Auto service")
            }
        } catch (e: IOException) {
            knocker.join(500)
            knocked.get()?.let { return it }
            done.set(true)
            if (listener == null) return null // stopped
            log.w("Bluetooth: no car connected within ${LISTEN_MS / 1000}s (${e.message})")
            SessionStatus.failed(
                "Car didn't connect over Bluetooth",
                "$name didn't open Android Auto with the phone. Check that wireless Android Auto is " +
                    "on in the car's settings, then reconnect the phone from the car's Bluetooth menu.",
            )
            null
        } finally {
            done.set(true)
            knocker.interrupt()
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
                joinedNet = net
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
                endTry()
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
                if (joinedNet == net) joinedNet = null
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
                handedOver = true
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
            SessionStatus.failed(
                "A VPN is blocking the car",
                "Gearslip joined $name's network, but a VPN on this phone won't let it reach the car. " +
                    "Turn the VPN off, or exclude Gearslip in its settings, then try again.",
            )
            noRetry = true
            endTry()
            return
        }
        SessionStatus.failed(
            "Car's Wi-Fi joined, but no connection",
            "Gearslip joined $name's network but couldn't reach Android Auto at $ip:$port.",
        )
        endTry()
    }
}
