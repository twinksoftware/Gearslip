package app.seb3thehacker.gearslip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A SOOEMAKA single-DIN unit's one video configuration, field for field as it sent it:
 * 800x480 at 30fps, margins 0x88, density 170, decoder depth 0, viewing distance 950,
 * pixel aspect 14000, real density 140.
 */
class ServiceDiscoveryPixelShapeTest {

    private fun varintField(field: Int, value: Long) = Protobuf.varintField(field, value)

    private fun lenField(field: Int, body: ByteArray): ByteArray =
        Protobuf.varint(((field shl 3) or 2).toLong()) + Protobuf.varint(body.size.toLong()) + body

    private fun response(config: ByteArray): ByteArray {
        val sink = varintField(1, 3L) + lenField(4, config)
        return lenField(1, varintField(1, 1L) + lenField(3, sink))
    }

    private val sooemaka = listOf(1L, 2L, 0L, 88L, 170L, 0L, 950L, 14_000L, 140L)
        .mapIndexed { i, v -> varintField(i + 1, v) }
        .reduce(ByteArray::plus)

    @Test
    fun readsThePixelAspect() {
        val config = ServiceDiscovery.findVideoService(response(sooemaka))!!.configs.single()
        assertEquals(14_000, config.pixelAspectE4)
        assertEquals(88, config.heightMargin)
        assertEquals(170, config.density)
    }

    @Test
    fun widensTheFrameByThePixelAspect() {
        val config = ServiceDiscovery.findVideoService(response(sooemaka))!!.configs.single()
        assertEquals(1120 to 480, config.pixelShapeSize())
    }

    @Test
    fun squarePixelsDrawTheFrameAsItIs() {
        val plain = varintField(1, 1L) + varintField(2, 2L) + varintField(5, 160L)
        val config = ServiceDiscovery.findVideoService(response(plain))!!.configs.single()
        assertEquals(0, config.pixelAspectE4)
        assertNull(config.pixelShapeSize())
    }

    @Test
    fun ignoresARatioNoScreenHas() {
        val config = ServiceDiscovery.VideoConfig(resolution = 1, frameRate = 2, density = 160, pixelAspectE4 = 1)
        assertNull(config.pixelShapeSize())
    }
}
