package app.seb3thehacker.gearslip

import java.io.ByteArrayOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLEngineResult.HandshakeStatus
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLException
import javax.net.ssl.X509ExtendedTrustManager

/** TLS server for projection. Handshake records travel in control message 3, not a TLS socket. */
class PhoneTls(keyStore: KeyStore, password: CharArray) {
    private val log = GearslipLog.tagged("TLS")

    private val engine: SSLEngine
    private var netIn = ByteArray(0)
    private var appBuffer = ByteBuffer.allocate(32 * 1024)
    private var netBuffer = ByteBuffer.allocate(32 * 1024)

    @Volatile var handshakeComplete: Boolean = false

    /**
     * A short fingerprint of the head unit's certificate, known once TLS completes: the first
     * thing that tells one car model from another, before it has described itself.
     */
    @Volatile var headUnitKey: String? = null
        private set

    init {
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(keyStore, password) }
            .keyManagers

        val context = SSLContext.getInstance("TLSv1.2").apply {
            init(keyManagers, arrayOf(LoggingTrustManager()), SecureRandom())
        }

        engine = context.createSSLEngine().apply {
            useClientMode = false
            // want, not need: we ask the head unit for its certificate so the run also tells
            // us what real units present, but we never make the session depend on getting one.
            // Homemade head units may have none.
            wantClientAuth = true
            enabledProtocols = arrayOf("TLSv1.2") // aasdk pins TLS 1.2
        }
        engine.beginHandshake()
    }

    /**
     * Feeds received handshake bytes in and returns whatever must be sent back, each element
     * becoming one MESSAGE_ENCAPSULATED_SSL message.
     */
    fun pumpHandshake(incoming: ByteArray?): List<ByteArray> {
        if (incoming != null) netIn += incoming
        val outgoing = mutableListOf<ByteArray>()
        var source = ByteBuffer.wrap(netIn)

        loop@ while (true) {
            when (engine.handshakeStatus) {
                HandshakeStatus.NEED_TASK -> {
                    var task = engine.delegatedTask
                    while (task != null) {
                        task.run()
                        task = engine.delegatedTask
                    }
                }

                HandshakeStatus.NEED_WRAP -> {
                    netBuffer.clear()
                    val result = engine.wrap(EMPTY, netBuffer)
                    if (result.status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                        netBuffer = ByteBuffer.allocate(netBuffer.capacity() * 2)
                        continue@loop
                    }
                    netBuffer.flip()
                    if (netBuffer.hasRemaining()) {
                        outgoing += ByteArray(netBuffer.remaining()).also { netBuffer.get(it) }
                    }
                    if (result.status == SSLEngineResult.Status.CLOSED) throw SSLException("TLS closed during handshake")
                }

                HandshakeStatus.NEED_UNWRAP -> {
                    appBuffer.clear()
                    val result = engine.unwrap(source, appBuffer)
                    when (result.status) {
                        SSLEngineResult.Status.BUFFER_UNDERFLOW -> break@loop
                        SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                            appBuffer = ByteBuffer.allocate(appBuffer.capacity() * 2)
                            continue@loop
                        }
                        SSLEngineResult.Status.CLOSED -> throw SSLException("TLS closed during handshake")
                        else -> Unit
                    }
                }

                HandshakeStatus.FINISHED, HandshakeStatus.NOT_HANDSHAKING -> break@loop
                else -> break@loop
            }
        }

        netIn = ByteArray(source.remaining()).also { source.get(it) }

        if (!handshakeComplete && engine.handshakeStatus == HandshakeStatus.NOT_HANDSHAKING &&
            engine.session.cipherSuite != "SSL_NULL_WITH_NULL_NULL"
        ) {
            handshakeComplete = true
            describeSession()
        }
        return outgoing
    }

    /** Wraps a control-channel payload for an ENCRYPTED frame. */
    fun encrypt(plain: ByteArray): ByteArray {
        val source = ByteBuffer.wrap(plain)
        val out = ByteArrayOutputStream()
        while (source.hasRemaining()) {
            netBuffer.clear()
            val result = engine.wrap(source, netBuffer)
            if (result.status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                netBuffer = ByteBuffer.allocate(netBuffer.capacity() * 2)
                continue
            }
            if (result.status != SSLEngineResult.Status.OK || result.bytesConsumed() == 0) {
                throw SSLException("TLS could not encrypt application data: ${result.status}")
            }
            netBuffer.flip()
            val chunk = ByteArray(netBuffer.remaining())
            netBuffer.get(chunk)
            out.write(chunk)
        }
        return out.toByteArray()
    }

    /** Each encrypted projection frame contains complete TLS records. */
    fun decrypt(cipher: ByteArray): ByteArray {
        val source = ByteBuffer.wrap(cipher)
        val out = ByteArrayOutputStream()
        while (source.hasRemaining()) {
            appBuffer.clear()
            val result = engine.unwrap(source, appBuffer)
            when (result.status) {
                SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                    appBuffer = ByteBuffer.allocate(appBuffer.capacity() * 2)
                    continue
                }
                SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                    throw SSLException("Incomplete TLS record in an encrypted frame")
                }
                SSLEngineResult.Status.CLOSED -> throw SSLException("TLS peer closed the connection")
                else -> Unit
            }
            appBuffer.flip()
            val chunk = ByteArray(appBuffer.remaining())
            appBuffer.get(chunk)
            out.write(chunk)
            if (result.bytesConsumed() == 0 && result.bytesProduced() == 0) break
        }
        return out.toByteArray()
    }

    private fun describeSession() {
        val session = engine.session
        log.i("TLS handshake COMPLETE - protocol=${session.protocol} cipher=${session.cipherSuite}")
        SessionReport.tls(session.protocol, session.cipherSuite)
        try {
            val peers = session.peerCertificates
            log.i("head unit presented ${peers.size} certificate(s):")
            headUnitKey = peers.firstOrNull()?.encoded?.let { bytes ->
                java.security.MessageDigest.getInstance("SHA-256").digest(bytes).take(8).joinToString("") { "%02x".format(it) }
            }
            peers.forEachIndexed { index, certificate ->
                if (certificate is X509Certificate) {
                    log.i("  [$index] subject=${certificate.subjectX500Principal}")
                    log.i("       issuer =${certificate.issuerX500Principal}")
                    log.i("       serial =${certificate.serialNumber} valid=${certificate.notBefore}..${certificate.notAfter}")
                }
            }
        } catch (_: SSLPeerUnverifiedException) {
            log.i("head unit presented NO certificate (we asked but did not require one)")
        }
    }

    /** Accepts everything and reports what it saw. We want the data, not a verdict. */
    private class LoggingTrustManager : X509ExtendedTrustManager() {
        private val log = GearslipLog.tagged("TLS")

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            log.i("peer certificate offered (authType=$authType, chain=${chain?.size ?: 0}) - accepting")
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) =
            checkClientTrusted(chain, authType)

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) =
            checkClientTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            checkClientTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) =
            checkClientTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) =
            checkClientTrusted(chain, authType)

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    companion object {
        private val EMPTY: ByteBuffer = ByteBuffer.allocate(0)
    }
}
