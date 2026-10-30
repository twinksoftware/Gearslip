package app.seb3thehacker.gearslip

import android.view.Surface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class ConnectionLifecycleTest {
    private val version = message(1, byteArrayOf(0, 1, 0, 3))

    private fun message(id: Int, body: ByteArray = byteArrayOf()) = Frames.build(
        Frames.CHANNEL_CONTROL, false, byteArrayOf((id ushr 8).toByte(), id.toByte()) + body,
    )

    private fun hello(): ByteArray {
        val client = SSLContext.getDefault().createSSLEngine().apply {
            useClientMode = true
            enabledProtocols = arrayOf("TLSv1.2")
            beginHandshake()
        }
        val packet = ByteBuffer.allocate(client.session.packetBufferSize)
        client.wrap(ByteBuffer.allocate(0), packet)
        return message(3, packet.array().copyOf(packet.position()))
    }

    private fun identity(): CertProvider.Identity {
        val generated = SelfSignedCert.generate()
        return CertProvider.Identity(
            generated.keyStore, generated.certificate, SelfSignedCert.PASSWORD, "generated test identity",
        )
    }

    private fun runner(input: InputStream) = GearslipRunner(input, ByteArrayOutputStream(), ::identity)

    @Test fun `rejected authentication ignores subsequent success and service discovery in the same read`() {
        for (status in listOf(-2, -3, -23, -24)) {
            runner(ByteArrayInputStream(
                version + hello() + message(4, Protobuf.varintField(1, status.toLong())) +
                    message(4, Protobuf.varintField(1, 0)) + message(6) + version,
            )).run()
            assertEquals(
                if (status == -3) SessionReport.Category.AUTH else SessionReport.Category.CERTIFICATE,
                SessionReport.category,
            )
            assertEquals(SessionStatus.Phase.FAILED, SessionStatus.state.value.phase)
            assertEquals("Head unit rejected the certificate", SessionStatus.state.value.headline)
            assertTrue(SessionReport.stage < SessionReport.Stage.ACCEPTED)
        }
    }

    @Test fun `protocol error terminates setup and ignores later messages`() {
        for (error in listOf(255, 65535)) {
            val runner = runner(ByteArrayInputStream(version + message(error) + version + message(6)))
            runner.run()
            assertFalse(getField(runner, "running") as Boolean)
            assertEquals(SessionReport.Category.UNKNOWN, SessionReport.category)
            assertEquals("Protocol error", SessionStatus.state.value.headline)
            assertEquals(SessionReport.Stage.VERSIONS, SessionReport.stage)
        }
    }

    @Test fun `head unit can end the session before authentication`() {
        val output = ByteArrayOutputStream()
        val runner = GearslipRunner(ByteArrayInputStream(version + message(15, Protobuf.varintField(1, 7))), output, ::identity)
        runner.run()
        val parser = Frames.Parser()
        parser.append(output.toByteArray(), output.size())
        assertEquals(2, requireNotNull(parser.next()).payload[1].toInt())
        val reply = requireNotNull(parser.next())
        assertFalse(reply.encrypted)
        assertArrayEquals(byteArrayOf(0, 16), reply.payload)
        assertEquals(SessionReport.Category.BYEBYE, SessionReport.category)
        assertEquals(SessionStatus.Phase.DISCONNECTED, SessionStatus.state.value.phase)
    }

    @Test fun `TLS failure ignores later protocol messages`() {
        val fatalAlert = message(3, byteArrayOf(21, 3, 3, 0, 2, 2, 42))
        runner(ByteArrayInputStream(version + hello() + fatalAlert + version + message(6))).run()
        assertEquals(SessionReport.Category.TLS, SessionReport.category)
        assertEquals("Secure connection failed", SessionStatus.state.value.headline)
        assertTrue(SessionReport.stage < SessionReport.Stage.ACCEPTED)
    }

    @Test fun `local TLS failure ends the session without waiting for peer EOF`() {
        val input = CountingInput(version + hello())
        val runner = GearslipRunner(input, ByteArrayOutputStream(), { error("identity unavailable") })
        runner.run()
        assertEquals(1, input.reads)
        assertEquals(SessionReport.Category.TLS, SessionReport.category)
        assertEquals("Secure connection failed", SessionStatus.state.value.headline)
        assertTrue((getField(runner, "writer") as ExecutorService).isShutdown)
    }

    @Test fun `unsupported version sends rejection and ends without waiting for peer EOF`() {
        val input = CountingInput(message(1, byteArrayOf(0, 0, 0, 9)))
        val output = ByteArrayOutputStream()
        val runner = GearslipRunner(input, output, ::identity)
        runner.run()
        assertEquals(1, input.reads)
        val parser = Frames.Parser()
        parser.append(output.toByteArray(), output.size())
        val response = requireNotNull(parser.next())
        assertArrayEquals(byteArrayOf(0, 2) + ProtocolVersion.respond(0, 9).bytes, response.payload)
        assertEquals("Unsupported protocol", SessionStatus.state.value.headline)
    }

    private class CountingInput(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var reads = 0
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            reads++
            return super.read(bytes, offset, length)
        }
    }

    @Test fun `authentication success before TLS completes is ignored`() {
        runner(ByteArrayInputStream(version + message(4, Protobuf.varintField(1, 0)))).run()
        assertEquals(SessionReport.Category.TLS, SessionReport.category)
        assertEquals(SessionReport.Stage.VERSIONS, SessionReport.stage)
    }

    @Test fun `stop followed by EOF does not report a handshake failure`() {
        stopDuringRead { -1 }
    }

    @Test fun `stop followed by a read exception does not report a USB failure`() {
        stopDuringRead { throw IOException("transport closed by owner") }
    }

    @Test fun `stop ignores bytes returned by an in-flight read`() {
        stopDuringRead { bytes -> version.copyInto(bytes); version.size }
    }

    private fun stopDuringRead(result: (ByteArray) -> Int) {
        lateinit var runner: GearslipRunner
        val input = object : InputStream() {
            override fun read(): Int = error("bulk reads only")
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                runner.stop()
                return result(bytes)
            }
        }
        runner = runner(input)
        runner.run()
        assertEquals(SessionReport.Category.NONE, SessionReport.category)
        assertEquals(SessionStatus.Phase.DISCONNECTED, SessionStatus.state.value.phase)
    }

    @Test fun `stop while preparing TLS leaves the session disconnected`() {
        val releaseIdentity = CountDownLatch(1)
        val runner = GearslipRunner(ByteArrayInputStream(version + hello()), ByteArrayOutputStream(), {
            check(releaseIdentity.await(5, TimeUnit.SECONDS))
            identity()
        })
        val reader = Thread { runner.run() }
        reader.start()
        try {
            // The reader waits on FutureTask.get while the identity provider is held above.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (reader.state != Thread.State.WAITING && reader.isAlive && System.nanoTime() < deadline) {
                reader.join(1)
            }
            assertEquals(Thread.State.WAITING, reader.state)
            runner.stop()
        } finally {
            releaseIdentity.countDown()
            reader.join(5_000)
        }
        assertTrue("reader must finish", !reader.isAlive)
        assertEquals(SessionReport.Category.NONE, SessionReport.category)
        assertEquals(SessionStatus.Phase.DISCONNECTED, SessionStatus.state.value.phase)
    }

    @Test fun `EOF and transport failure tell the owner the car is gone`() {
        val failing = object : InputStream() {
            override fun read(): Int = throw IOException("test disconnect")
        }
        for (input in listOf(ByteArrayInputStream(version), failing)) {
            var gone = 0
            GearslipRunner(input, ByteArrayOutputStream(), ::identity, onCarGone = { gone++ }).run()
            assertEquals(1, gone)
        }
    }

    @Test fun `a stop from the phone is not the car leaving`() {
        var gone = 0
        lateinit var runner: GearslipRunner
        val input = object : InputStream() {
            override fun read(): Int = error("bulk reads only")
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                runner.stop()
                return -1
            }
        }
        runner = GearslipRunner(input, ByteArrayOutputStream(), ::identity, onCarGone = { gone++ })
        runner.run()
        assertEquals(0, gone)
    }

    @Test fun `EOF and transport failure shut down the writer`() {
        val failing = object : InputStream() {
            override fun read(): Int = throw IOException("test disconnect")
        }
        for (input in listOf(ByteArrayInputStream(version), failing)) {
            val runner = runner(input)
            runner.run()
            val field = GearslipRunner::class.java.getDeclaredField("writer").apply { isAccessible = true }
            assertTrue((field.get(runner) as ExecutorService).isShutdown)
        }
    }
    @Test fun `ping echoes the full timestamp and optional payload`() {
        for (payload in listOf(null, byteArrayOf(), byteArrayOf(0, -1, 42))) {
            val output = ByteArrayOutputStream()
            val runner = GearslipRunner(ByteArrayInputStream(byteArrayOf()), output, ::identity)
            val identity = identity()
            val tls = FutureTask { PhoneTls(identity.keyStore, identity.password) }.apply { run() }
            setField(runner, "identityTask", tls)
            val timestamp = 0x1234_5678_9abcL
            val request = Protobuf.varintField(1, timestamp) + Protobuf.varintField(2, 1) +
                (payload?.let { Protobuf.bytesField(3, it) } ?: byteArrayOf())
            val ping = GearslipRunner::class.java.getDeclaredMethod("onPingRequest", ByteArray::class.java)
                .apply { isAccessible = true }
            try {
                ping.invoke(runner, request)
                val writer = getField(runner, "writer") as ExecutorService
                writer.shutdown()
                assertTrue(writer.awaitTermination(2, TimeUnit.SECONDS))
                val parser = Frames.Parser()
                parser.append(output.toByteArray(), output.size())
                val response = requireNotNull(parser.next()).payload
                assertEquals(12, response[1].toInt())
                val fields = Wire.fields(response.copyOfRange(2, response.size))
                assertEquals(timestamp, Wire.varint(fields, 1))
                assertArrayEquals(payload, Wire.bytes(fields, 2))
            } finally {
                runner.stop()
            }
        }
    }

    private fun getField(runner: GearslipRunner, name: String): Any? =
        GearslipRunner::class.java.getDeclaredField(name).apply { isAccessible = true }.get(runner)

    private fun setField(runner: GearslipRunner, name: String, value: Any) {
        GearslipRunner::class.java.getDeclaredField(name).apply { isAccessible = true }.set(runner, value)
    }


    @Test fun `touchpad reports are not injected as touchscreen taps`() {
        var touches = 0
        val projection = object : Projection {
            override fun onSurfaceReady(surface: Surface, width: Int, height: Int, densityDpi: Int) = Unit
            override fun onTouch(action: Int, actionIndex: Int, points: List<TouchPoint>) { touches++ }
            override fun onProjectionStopped() = Unit
        }
        val runner = GearslipRunner(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream(), ::identity, projection)
        val input = GearslipRunner::class.java.getDeclaredMethod("onInputReport", ByteArray::class.java)
            .apply { isAccessible = true }
        val touch = Protobuf.bytesField(1, Protobuf.varintField(1, 100) +
            Protobuf.varintField(2, 200) + Protobuf.varintField(3, 0))
        try {
            input.invoke(runner, Protobuf.varintField(1, 1000) + Protobuf.bytesField(7, touch))
            assertEquals(0, touches)
            input.invoke(runner, Protobuf.varintField(1, 1000) + Protobuf.bytesField(3, touch))
            assertEquals(1, touches)
        } finally {
            runner.stop()
        }
    }

}
