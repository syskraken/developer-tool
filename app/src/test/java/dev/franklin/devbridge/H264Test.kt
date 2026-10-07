package dev.franklin.devbridge

import dev.franklin.devbridge.adb.H264Framer
import dev.franklin.devbridge.adb.H264Splitter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class H264Test {

    private val sc4 = byteArrayOf(0, 0, 0, 1)
    private val sc3 = byteArrayOf(0, 0, 1)
    private fun nal(type: Int, vararg body: Int, start: ByteArray = sc4) =
        start + byteArrayOf(type.toByte()) + ByteArray(body.size) { body[it].toByte() }

    private val sps = nal(0x67, 1, 2, 3)
    private val pps = nal(0x68, 4)
    private val idr = nal(0x65, 9, 9, 9, 9)
    private val p1 = nal(0x41, 7, 7)
    private val p2 = nal(0x41, 8, 8, 8, start = sc3)

    @Test
    fun splitsWholeStreamHoldingBackTheLastUnit() {
        val s = H264Splitter()
        val out = s.feed(sps + pps + idr + p1)
        assertEquals(3, out.size)
        assertArrayEquals(sps, out[0])
        assertArrayEquals(pps, out[1])
        assertArrayEquals(idr, out[2])
        assertArrayEquals(p1, s.flush())
    }

    @Test
    fun reassemblesUnitsSplitAtEveryByteBoundary() {
        val stream = sps + pps + idr + p1 + p2
        val s = H264Splitter()
        val units = ArrayList<ByteArray>()
        for (b in stream) units += s.feed(byteArrayOf(b))
        s.flush()?.let { units += it }
        assertEquals(5, units.size)
        assertArrayEquals(sps + pps + idr + p1 + p2, units.fold(ByteArray(0)) { a, b -> a + b })
        assertArrayEquals(p2, units.last())
    }

    @Test
    fun handlesThreeByteStartCodesAndGarbageBeforeFirstUnit() {
        val s = H264Splitter()
        val out = s.feed(byteArrayOf(5, 5, 5) + p2 + p1)
        assertEquals(1, out.size)
        assertArrayEquals(p2, out[0])
        assertEquals(1, s.nalType(p1) and 1)
    }

    @Test
    fun flushReturnsNothingWhenEmpty() {
        assertNull(H264Splitter().flush())
    }

    @Test
    fun framerAttachesParameterSetsToTheKeyFrameAndFlagsIt() {
        val f = H264Framer()
        val units = f.feed(sps + pps + idr + p1 + p2)
        assertEquals(2, units.size)
        assertTrue(units[0].keyFrame)
        assertArrayEquals(sps + pps + idr, units[0].data)
        assertFalse(units[1].keyFrame)
        assertArrayEquals(p1, units[1].data)
        val last = f.flush()!!
        assertArrayEquals(p2, last.data)
    }
}
