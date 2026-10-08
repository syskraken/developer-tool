package dev.franklin.devbridge

import dev.franklin.devbridge.aoa.Aoa
import dev.franklin.devbridge.aoa.AoaException
import dev.franklin.devbridge.aoa.AoaSession
import dev.franklin.devbridge.aoa.ControlTransfer
import dev.franklin.devbridge.aoa.HidKeys
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AoaTest {

    private class Call(val type: Int, val request: Int, val value: Int, val index: Int, val data: ByteArray?)

    /** Stands in for the phone: records every transfer and answers the protocol query. */
    private class FakePhone(private val version: Int = 2, private val failSends: Boolean = false) : ControlTransfer {
        val calls = ArrayList<Call>()
        override fun transfer(requestType: Int, request: Int, value: Int, index: Int, data: ByteArray?): Int {
            calls += Call(requestType, request, value, index, data?.copyOf())
            if (request == Aoa.REQUEST_GET_PROTOCOL) {
                data!![0] = (version and 0xFF).toByte()
                data[1] = (version shr 8).toByte()
                return 2
            }
            if (failSends && request == Aoa.REQUEST_SEND_HID_EVENT) return -1
            return data?.size ?: 0
        }
        fun events(id: Int) = calls.filter { it.request == Aoa.REQUEST_SEND_HID_EVENT && it.value == id }.map { it.data!! }
    }

    @Test
    fun startChecksVersionThenRegistersBothDevicesWithTheirDescriptors() {
        val phone = FakePhone()
        AoaSession(phone).start()
        val c = phone.calls
        assertEquals(Aoa.REQUEST_GET_PROTOCOL, c[0].request)
        assertEquals(Aoa.TYPE_IN, c[0].type)

        assertEquals(Aoa.REQUEST_REGISTER_HID, c[1].request)
        assertEquals(Aoa.ID_MOUSE, c[1].value)
        assertEquals(Aoa.MOUSE_DESCRIPTOR.size, c[1].index)
        assertEquals(Aoa.REQUEST_SET_HID_REPORT_DESC, c[2].request)
        assertArrayEquals(Aoa.MOUSE_DESCRIPTOR, c[2].data)

        assertEquals(Aoa.REQUEST_REGISTER_HID, c[3].request)
        assertEquals(Aoa.ID_KEYBOARD, c[3].value)
        assertEquals(Aoa.KEYBOARD_DESCRIPTOR.size, c[3].index)
        assertArrayEquals(Aoa.KEYBOARD_DESCRIPTOR, c[4].data)
        assertTrue(c.drop(1).all { it.type == Aoa.TYPE_OUT })
    }

    @Test
    fun oldAccessoryVersionIsRefusedWithAClearMessage() {
        try {
            AoaSession(FakePhone(version = 1)).start()
            fail()
        } catch (e: AoaException) {
            assertTrue(e.message!!.contains("version 2"))
        }
    }

    @Test
    fun descriptorsHaveTheExpectedShape() {
        // Keyboard: boot-protocol descriptor ends the collection; mouse closes two collections.
        assertEquals(0xC0.toByte(), Aoa.KEYBOARD_DESCRIPTOR.last())
        assertEquals(63, Aoa.KEYBOARD_DESCRIPTOR.size)
        assertEquals(0xC0.toByte(), Aoa.MOUSE_DESCRIPTOR[Aoa.MOUSE_DESCRIPTOR.size - 1])
        assertEquals(0xC0.toByte(), Aoa.MOUSE_DESCRIPTOR[Aoa.MOUSE_DESCRIPTOR.size - 2])
        assertEquals(5, Aoa.mouseReport(0, 0, 0, 0, 0).size)
        assertEquals(8, Aoa.keyboardReport(0, IntArray(0)).size)
    }

    @Test
    fun longMouseMovesAreSplitIntoReportsOfAtMost127() {
        val phone = FakePhone()
        val session = AoaSession(phone).also { it.start() }
        session.moveMouse(300, -200)
        val moves = phone.events(Aoa.ID_MOUSE).map { (it[1].toInt()) to (it[2].toInt()) }
        assertEquals(300, moves.sumOf { it.first })
        assertEquals(-200, moves.sumOf { it.second })
        assertTrue(moves.all { it.first in -127..127 && it.second in -127..127 })
        assertEquals(3, moves.size)
    }

    @Test
    fun clickPressesThenReleasesAndMovesWhileHeldKeepTheButton() {
        val phone = FakePhone()
        val s = AoaSession(phone).also { it.start() }
        s.click()
        val mouse = phone.events(Aoa.ID_MOUSE)
        assertEquals(Aoa.BUTTON_LEFT, mouse[0][0].toInt())
        assertEquals(0, mouse[1][0].toInt())

        s.setButtons(Aoa.BUTTON_LEFT)
        s.moveMouse(5, 0)
        assertEquals(Aoa.BUTTON_LEFT, phone.events(Aoa.ID_MOUSE).last()[0].toInt())
    }

    @Test
    fun scrollSendsWheelAndPanSeparately() {
        val phone = FakePhone()
        val s = AoaSession(phone).also { it.start() }
        s.scroll(-3, 2)
        val r = phone.events(Aoa.ID_MOUSE).last()
        assertEquals(-3, r[3].toInt())
        assertEquals(2, r[4].toInt())
    }

    @Test
    fun typingSendsKeyDownThenAllKeysUpForEachCharacter() {
        val phone = FakePhone()
        val s = AoaSession(phone).also { it.start() }
        val skipped = s.typeText("Aa1\n€")
        assertEquals(1, skipped)
        val kb = phone.events(Aoa.ID_KEYBOARD)
        assertEquals(8, kb.size)                       // 4 typeable characters x (down, up)
        assertEquals(HidKeys.MOD_SHIFT, kb[0][0].toInt())
        assertEquals(0x04, kb[0][2].toInt())           // A
        assertEquals(0, kb[1][0].toInt())
        assertEquals(0, kb[1][2].toInt())
        assertEquals(0x04, kb[2][2].toInt())           // a, no shift
        assertEquals(0, kb[2][0].toInt())
        assertEquals(0x1E, kb[4][2].toInt())           // 1
        assertEquals(HidKeys.ENTER, kb[6][2].toInt())
    }

    @Test
    fun characterMapMatchesTheUsLayout() {
        assertEquals(0x27, HidKeys.forChar('0')!!.usage)
        assertEquals(0x1E, HidKeys.forChar('1')!!.usage)
        assertEquals(HidKeys.MOD_SHIFT, HidKeys.forChar('!')!!.modifiers)
        assertEquals(0x1E, HidKeys.forChar('!')!!.usage)
        assertEquals(0x27, HidKeys.forChar(')')!!.usage)
        assertEquals(0x2D, HidKeys.forChar('-')!!.usage)
        assertEquals(0x2E, HidKeys.forChar('=')!!.usage)
        assertEquals(0x31, HidKeys.forChar('\\')!!.usage)
        assertEquals(0x33, HidKeys.forChar(';')!!.usage)     // 0x32 is skipped
        assertEquals(0x34, HidKeys.forChar('\'')!!.usage)
        assertEquals(0x36, HidKeys.forChar(',')!!.usage)
        assertEquals(0x38, HidKeys.forChar('/')!!.usage)
        assertEquals(HidKeys.MOD_SHIFT, HidKeys.forChar('?')!!.modifiers)
        assertEquals(0x38, HidKeys.forChar('?')!!.usage)
        assertEquals(0x2D, HidKeys.forChar('_')!!.usage)
        assertEquals(0x33, HidKeys.forChar(':')!!.usage)
        assertEquals(0x34, HidKeys.forChar('"')!!.usage)
        assertEquals(0x35, HidKeys.forChar('~')!!.usage)
        assertNull(HidKeys.forChar('é'))
        assertNotNull(HidKeys.forChar('z'))
        assertEquals(0x1D, HidKeys.forChar('z')!!.usage)
    }

    @Test
    fun aFailedTransferSurfacesAsAnError() {
        val s = AoaSession(FakePhone(failSends = true)).also { it.start() }
        try { s.click(); fail() } catch (e: AoaException) { assertTrue(e.message!!.contains("-1")) }
    }

    @Test
    fun closeUnregistersBothDevices() {
        val phone = FakePhone()
        val s = AoaSession(phone).also { it.start() }
        s.close()
        val unregister = phone.calls.filter { it.request == Aoa.REQUEST_UNREGISTER_HID }.map { it.value }
        assertEquals(listOf(Aoa.ID_MOUSE, Aoa.ID_KEYBOARD), unregister)
        s.close()                                       // second close is harmless
        assertEquals(2, phone.calls.count { it.request == Aoa.REQUEST_UNREGISTER_HID })
    }
}
