package app.seb3thehacker.gearslip

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A head unit kept sending encrypted frames from a connection Gearslip had closed without telling
 * it, and the new connection waited for a VersionRequest that never came, until the cable was
 * pulled. Gearslip should tell the car the old connection is over, once and in the clear, as the
 * old one's keys are gone, and carry on as normal when the car starts again.
 */
class StaleCarTest {

    private fun message(id: Int, body: ByteArray = byteArrayOf(), encrypted: Boolean = false) = Frames.build(
        Frames.CHANNEL_CONTROL, encrypted, byteArrayOf((id ushr 8).toByte(), id.toByte()) + body,
    )

    /** Frames from the old connection: encrypted, and meaningless to a new one. */
    private fun stale(count: Int) = (1..count).fold(byteArrayOf()) { all, i ->
        all + Frames.build(3, true, ByteArray(32) { (it + i).toByte() })
    }

    private val version = message(1, byteArrayOf(0, 1, 0, 3))

    private fun identity(): CertProvider.Identity {
        val generated = SelfSignedCert.generate()
        return CertProvider.Identity(generated.keyStore, generated.certificate, SelfSignedCert.PASSWORD, "test")
    }

    /** Runs a connection on [input] and returns the messages Gearslip sent, as (id, encrypted, body). */
    private fun sent(input: ByteArray, also: (GearslipRunner) -> Unit = {}): List<Triple<Int, Boolean, ByteArray>> {
        val output = ByteArrayOutputStream()
        val runner = GearslipRunner(ByteArrayInputStream(input), output, ::identity)
        // Let what was sent reach the output before the connection closes.
        GearslipRunner::class.java.getDeclaredField("drainWriterOnExit").apply { isAccessible = true }.setBoolean(runner, true)
        also(runner)
        runner.run()
        val parser = Frames.Parser()
        parser.append(output.toByteArray(), output.size())
        return generateSequence { parser.next() }.map { f ->
            val id = ((f.payload[0].toInt() and 0xFF) shl 8) or (f.payload[1].toInt() and 0xFF)
            Triple(id, f.encrypted, f.payload.copyOfRange(2, f.payload.size))
        }.toList()
    }

    @Test
    fun aCarStillInTheOldConnectionIsToldItIsOver() {
        val out = sent(stale(5))
        val bye = out.single { it.first == MSG_BYEBYE_REQUEST }
        assertFalse("sent in the clear: the old connection's keys are gone", bye.second)
        assertEquals(1L, Protobuf.readInt32Field(bye.third, 1)?.toLong())
    }

    @Test
    fun manyOldFramesGetOneGoodbyeNotOneEach() {
        val out = sent(stale(200))
        assertEquals(1, out.count { it.first == MSG_BYEBYE_REQUEST })
    }

    @Test
    fun theCarStartingOverAfterTheGoodbyeConnectsAsNormal() {
        val out = sent(stale(5) + version)
        assertTrue("goodbye first: $out", out.first().first == MSG_BYEBYE_REQUEST)
        assertTrue("then the version answer: ${out.map { it.first }}", out.any { it.first == MSG_VERSION_RESPONSE })
    }

    @Test
    fun aFreshCarGetsNoGoodbye() {
        val out = sent(version)
        assertTrue(out.none { it.first == MSG_BYEBYE_REQUEST })
    }

    @Test
    fun endingAConnectionTheCarNeverStartedSaysNoGoodbye() {
        // Before the secure session is up there is nothing for the car to close.
        val out = sent(byteArrayOf()) { it.stop("a test ended it") }
        assertTrue(out.none { it.first == MSG_BYEBYE_REQUEST })
    }

    private companion object {
        const val MSG_VERSION_RESPONSE = 2
        const val MSG_BYEBYE_REQUEST = 15
    }
}
