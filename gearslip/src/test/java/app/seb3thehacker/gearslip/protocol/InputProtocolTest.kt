package app.seb3thehacker.gearslip.protocol

import app.seb3thehacker.gearslip.Protobuf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InputProtocolTest {
    private fun pointer(id: Int) = Protobuf.bytesField(1,
        Protobuf.varintField(1, 100) + Protobuf.varintField(2, 200) + Protobuf.varintField(3, id.toLong()))

    @Test fun `multitouch preserves pointer IDs and action index`() {
        val body = pointer(7) + pointer(3) + Protobuf.varintField(2, 1) + Protobuf.varintField(3, 5)
        val touch = requireNotNull(InputProtocol.touch(body, 800 to 480))
        assertEquals(5, touch.action)
        assertEquals(1, touch.actionIndex)
        assertEquals(listOf(7, 3), touch.points.map { it.id })
        assertEquals(100f, touch.points[1].x)
    }

    @Test fun `invalid pointer index is rejected instead of targeting a different finger`() {
        assertNull(InputProtocol.touch(pointer(0) + Protobuf.varintField(2, 1), null))
        assertNull(InputProtocol.touch(pointer(0) + Protobuf.varintField(2, -1), null))
    }

    @Test fun `missing required pointer fields and duplicate IDs are rejected`() {
        assertNull(InputProtocol.touch(Protobuf.bytesField(1, Protobuf.varintField(1, 20)), null))
        assertNull(InputProtocol.touch(pointer(1) + pointer(1), null))
    }

    @Test fun `pointer IDs must fit the Android motion event range`() {
        for (id in listOf(-1L, 32L, 0x1_0000_0000L)) {
            val body = Protobuf.bytesField(1, Protobuf.varintField(1, 100) +
                Protobuf.varintField(2, 200) + Protobuf.varintField(3, id))
            assertNull(InputProtocol.touch(body, null))
        }
        assertEquals(31, requireNotNull(InputProtocol.touch(pointer(31), null)).points.single().id)
    }

    @Test fun `oversized pointer sets are rejected before constructing motion events`() {
        val body = (0..32).fold(byteArrayOf()) { bytes, id -> bytes + pointer(id) }
        assertNull(InputProtocol.touch(body, null))
    }

    @Test fun `optional touch fields use down and first pointer defaults`() {
        val touch = requireNotNull(InputProtocol.touch(pointer(0), 50 to 60))
        assertEquals(0, touch.action)
        assertEquals(0, touch.actionIndex)
        assertEquals(49f, touch.points.single().x)
        assertEquals(59f, touch.points.single().y)
    }

    @Test fun `a tap in touch panel size is scaled onto the picture before the margin shift`() {
        // 1920x1080 panel over a 1280x720 picture: the panel's 100,200 is the picture's 67,133.
        val touch = requireNotNull(InputProtocol.touch(pointer(0), 1280 to 720, shiftX = 10, scaleX = 2f / 3, scaleY = 2f / 3))
        assertEquals(77f, touch.points.single().x)
        assertEquals(133f, touch.points.single().y)
    }

    @Test fun `extent is the furthest point the car sent, before any clamping`() {
        val far = Protobuf.bytesField(1,
            Protobuf.varintField(1, 1900) + Protobuf.varintField(2, 50) + Protobuf.varintField(3, 1))
        assertEquals(1900 to 200, InputProtocol.extent(pointer(0) + far))
        assertNull(InputProtocol.extent(Protobuf.varintField(3, 0)))
    }
}
