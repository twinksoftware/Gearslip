package app.seb3thehacker.gearslip

import java.net.Socket
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLEngineResult.HandshakeStatus
import javax.net.ssl.X509ExtendedTrustManager

/**
 * Checks mutual TLS and an encrypted data round trip with an in-process head-unit peer. Test code
 * only: the peer trusts any certificate, which is the point here and must never ship in the app.
 */
object TlsSelfTest {
    private val log = GearslipLog.tagged("TLS")

    fun run(identity: CertProvider.Identity, withClientCertificate: Boolean = true): Boolean {
        log.i("=== TLS self-test starting ===")
        log.i("cert source: ${identity.source}")
        log.i("  subject = ${identity.certificate.subjectX500Principal}")
        log.i("  issuer  = ${identity.certificate.issuerX500Principal}")
        return try {
            val server = PhoneTls(identity.keyStore, identity.password)
            val client = Driver(clientEngine(withClientCertificate))

            var outbound = client.pump(null) // ClientHello
            var rounds = 0
            while (rounds++ < 20) {
                if (outbound.isEmpty()) break
                val fromServer = mutableListOf<ByteArray>()
                outbound.forEach { fromServer += server.pumpHandshake(it) }
                if (server.handshakeComplete && fromServer.isEmpty()) break
                val fromClient = mutableListOf<ByteArray>()
                fromServer.forEach { fromClient += client.pump(it) }
                outbound = fromClient
            }

            if (!server.handshakeComplete) {
                log.e("self-test FAILED: handshake did not complete after $rounds rounds")
                return false
            }

            // Exercise the same encrypt path used by ServiceDiscoveryRequest.
            val plaintext = "gearslip round trip".toByteArray()
            val decrypted = client.decrypt(server.encrypt(plaintext))
            if (!decrypted.contentEquals(plaintext)) {
                log.e("self-test FAILED: round trip mismatch (${decrypted.size} bytes back)")
                return false
            }

            log.i("=== TLS self-test PASSED - certificate, key manager and engine are sound ===")
            true
        } catch (t: Throwable) {
            log.e("self-test FAILED with an exception", t)
            false
        }
    }

    private fun clientEngine(withCertificate: Boolean): SSLEngine {
        val context = SSLContext.getInstance("TLSv1.2")
        val keyManagers = if (withCertificate) {
            val client = SelfSignedCert.generate(commonName = "Self-test head unit")
            KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(client.keyStore, SelfSignedCert.PASSWORD)
            }.keyManagers
        } else null
        context.init(keyManagers, arrayOf(TrustAll()), SecureRandom())
        return context.createSSLEngine().apply {
            useClientMode = true
            enabledProtocols = arrayOf("TLSv1.2")
            beginHandshake()
        }
    }

    /** Deliberately a second, independent implementation - it should not share PhoneTls's bugs. */
    private class Driver(private val engine: SSLEngine) {
        private var netIn = ByteArray(0)
        private var app = ByteBuffer.allocate(32 * 1024)
        private var net = ByteBuffer.allocate(32 * 1024)

        fun pump(incoming: ByteArray?): List<ByteArray> {
            if (incoming != null) netIn += incoming
            val out = mutableListOf<ByteArray>()
            val source = ByteBuffer.wrap(netIn)
            loop@ while (true) {
                when (engine.handshakeStatus) {
                    HandshakeStatus.NEED_TASK -> {
                        var task = engine.delegatedTask
                        while (task != null) { task.run(); task = engine.delegatedTask }
                    }
                    HandshakeStatus.NEED_WRAP -> {
                        net.clear()
                        val r = engine.wrap(ByteBuffer.allocate(0), net)
                        if (r.status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                            net = ByteBuffer.allocate(net.capacity() * 2); continue@loop
                        }
                        net.flip()
                        if (net.hasRemaining()) out += ByteArray(net.remaining()).also { net.get(it) }
                        if (r.status == SSLEngineResult.Status.CLOSED) break@loop
                    }
                    HandshakeStatus.NEED_UNWRAP -> {
                        app.clear()
                        val r = engine.unwrap(source, app)
                        when (r.status) {
                            SSLEngineResult.Status.BUFFER_UNDERFLOW -> break@loop
                            SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                                app = ByteBuffer.allocate(app.capacity() * 2); continue@loop
                            }
                            SSLEngineResult.Status.CLOSED -> break@loop
                            else -> Unit
                        }
                    }
                    else -> break@loop
                }
            }
            netIn = ByteArray(source.remaining()).also { source.get(it) }
            return out
        }

        fun decrypt(cipher: ByteArray): ByteArray {
            val source = ByteBuffer.wrap(cipher)
            val out = java.io.ByteArrayOutputStream()
            while (source.hasRemaining()) {
                app.clear()
                val r = engine.unwrap(source, app)
                if (r.status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                    app = ByteBuffer.allocate(app.capacity() * 2); continue
                }
                app.flip()
                out.write(ByteArray(app.remaining()).also { app.get(it) })
                if (r.bytesConsumed() == 0 && r.bytesProduced() == 0) break
            }
            return out.toByteArray()
        }
    }

    private class TrustAll : X509ExtendedTrustManager() {
        override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) = Unit
        override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?, s: Socket?) = Unit
        override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?, e: SSLEngine?) = Unit
        override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) = Unit
        override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?, s: Socket?) = Unit
        override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?, e: SSLEngine?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
