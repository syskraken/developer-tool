package dev.franklin.devbridge.aoa

import java.io.Closeable

class AoaException(message: String) : Exception(message)

/** One USB control transfer to the target phone; returns the number of bytes moved, or a negative value on failure. */
fun interface ControlTransfer {
    fun transfer(requestType: Int, request: Int, value: Int, index: Int, data: ByteArray?): Int
}

/**
 * Android Open Accessory (AOA) 2.0 input. The plugged-in phone is told "a mouse and a keyboard are
 * connected" and then receives their reports, over the same USB cable, with no USB debugging and no
 * approved key. scrcpy's `--mouse=aoa --keyboard=aoa` and OTG mode use the same mechanism.
 */
object Aoa {
    const val REQUEST_GET_PROTOCOL = 51
    const val REQUEST_REGISTER_HID = 54
    const val REQUEST_UNREGISTER_HID = 55
    const val REQUEST_SET_HID_REPORT_DESC = 56
    const val REQUEST_SEND_HID_EVENT = 57

    /** Vendor request, host to device / device to host. */
    const val TYPE_OUT = 0x40
    const val TYPE_IN = 0xC0

    const val ID_MOUSE = 1
    const val ID_KEYBOARD = 2

    // Mouse buttons, as bits of the first report byte.
    const val BUTTON_LEFT = 1
    const val BUTTON_RIGHT = 2
    const val BUTTON_MIDDLE = 4

    /** USB HID boot-protocol keyboard (HID spec, appendix B.1): modifiers, reserved, six key slots. */
    val KEYBOARD_DESCRIPTOR: ByteArray = bytes(
        0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01,
        0x75, 0x01, 0x95, 0x08, 0x81, 0x02, 0x95, 0x01, 0x75, 0x08, 0x81, 0x01, 0x95, 0x05, 0x75, 0x01,
        0x05, 0x08, 0x19, 0x01, 0x29, 0x05, 0x91, 0x02, 0x95, 0x01, 0x75, 0x03, 0x91, 0x01, 0x95, 0x06,
        0x75, 0x08, 0x15, 0x00, 0x25, 0x65, 0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81, 0x00, 0xC0,
    )

    /** Relative mouse: 5 buttons, X, Y, wheel, horizontal wheel (AC Pan). Report is 5 bytes. */
    val MOUSE_DESCRIPTOR: ByteArray = bytes(
        0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x09, 0x01, 0xA1, 0x00, 0x05, 0x09, 0x19, 0x01, 0x29, 0x05,
        0x15, 0x00, 0x25, 0x01, 0x95, 0x05, 0x75, 0x01, 0x81, 0x02, 0x95, 0x01, 0x75, 0x03, 0x81, 0x01,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x03,
        0x81, 0x06, 0x05, 0x0C, 0x0A, 0x38, 0x02, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x01, 0x81,
        0x06, 0xC0, 0xC0,
    )

    fun mouseReport(buttons: Int, dx: Int, dy: Int, wheel: Int, pan: Int): ByteArray = byteArrayOf(
        buttons.toByte(), clamp(dx).toByte(), clamp(dy).toByte(), clamp(wheel).toByte(), clamp(pan).toByte(),
    )

    fun keyboardReport(modifiers: Int, keys: IntArray): ByteArray {
        val report = ByteArray(8)
        report[0] = modifiers.toByte()
        for (i in 0 until minOf(6, keys.size)) report[2 + i] = keys[i].toByte()
        return report
    }

    fun clamp(v: Int): Int = v.coerceIn(-127, 127)

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
}

/** A mouse and keyboard presented to the plugged-in phone. Not thread-safe by itself; calls are serialised. */
class AoaSession(private val usb: ControlTransfer) : Closeable {

    private var registered = false
    private var buttons = 0

    /** AOA protocol version the phone speaks; HID input needs 2. */
    fun protocolVersion(): Int {
        val reply = ByteArray(2)
        val n = usb.transfer(Aoa.TYPE_IN, Aoa.REQUEST_GET_PROTOCOL, 0, 0, reply)
        if (n < 2) throw AoaException("The phone did not answer the accessory request (is it connected as a USB device?)")
        return (reply[0].toInt() and 0xFF) or ((reply[1].toInt() and 0xFF) shl 8)
    }

    @Synchronized
    fun start() {
        val version = protocolVersion()
        if (version < 2) throw AoaException("This phone supports USB accessory version $version, but input needs version 2")
        register(Aoa.ID_MOUSE, Aoa.MOUSE_DESCRIPTOR)
        register(Aoa.ID_KEYBOARD, Aoa.KEYBOARD_DESCRIPTOR)
        registered = true
    }

    private fun register(id: Int, descriptor: ByteArray) {
        check(usb.transfer(Aoa.TYPE_OUT, Aoa.REQUEST_REGISTER_HID, id, descriptor.size, null), "register input device")
        check(usb.transfer(Aoa.TYPE_OUT, Aoa.REQUEST_SET_HID_REPORT_DESC, id, 0, descriptor), "send input descriptor")
    }

    private fun send(id: Int, report: ByteArray) =
        check(usb.transfer(Aoa.TYPE_OUT, Aoa.REQUEST_SEND_HID_EVENT, id, 0, report), "send input")

    private fun check(result: Int, what: String) {
        if (result < 0) throw AoaException("Could not $what (USB error $result)")
    }

    // --- mouse ---------------------------------------------------------------------------------------

    /** Moves the pointer; large distances are split because one report carries at most ±127. */
    @Synchronized
    fun moveMouse(dx: Int, dy: Int) {
        var x = dx
        var y = dy
        while (x != 0 || y != 0) {
            val sx = Aoa.clamp(x)
            val sy = Aoa.clamp(y)
            send(Aoa.ID_MOUSE, Aoa.mouseReport(buttons, sx, sy, 0, 0))
            x -= sx
            y -= sy
        }
    }

    @Synchronized
    fun setButtons(mask: Int) {
        buttons = mask
        send(Aoa.ID_MOUSE, Aoa.mouseReport(buttons, 0, 0, 0, 0))
    }

    @Synchronized
    fun click(button: Int = Aoa.BUTTON_LEFT) {
        setButtons(buttons or button)
        setButtons(buttons and button.inv())
    }

    @Synchronized
    fun scroll(vertical: Int, horizontal: Int = 0) {
        var v = vertical
        var h = horizontal
        while (v != 0 || h != 0) {
            val sv = Aoa.clamp(v)
            val sh = Aoa.clamp(h)
            send(Aoa.ID_MOUSE, Aoa.mouseReport(buttons, 0, 0, sv, sh))
            v -= sv
            h -= sh
        }
    }

    // --- keyboard ------------------------------------------------------------------------------------

    @Synchronized
    fun tapKey(usage: Int, modifiers: Int = 0) {
        send(Aoa.ID_KEYBOARD, Aoa.keyboardReport(modifiers, intArrayOf(usage)))
        send(Aoa.ID_KEYBOARD, Aoa.keyboardReport(0, IntArray(0)))
    }

    /** Types [text] on a US layout; characters with no key are skipped. Returns how many were skipped. */
    @Synchronized
    fun typeText(text: String): Int {
        var skipped = 0
        for (c in text) {
            val key = HidKeys.forChar(c)
            if (key == null) {
                skipped++
                continue
            }
            tapKey(key.usage, key.modifiers)
        }
        return skipped
    }

    @Synchronized
    override fun close() {
        if (!registered) return
        registered = false
        // Best effort: the phone forgets the devices when the cable goes anyway.
        try { usb.transfer(Aoa.TYPE_OUT, Aoa.REQUEST_UNREGISTER_HID, Aoa.ID_MOUSE, 0, null) } catch (e: Exception) { /* gone */ }
        try { usb.transfer(Aoa.TYPE_OUT, Aoa.REQUEST_UNREGISTER_HID, Aoa.ID_KEYBOARD, 0, null) } catch (e: Exception) { /* gone */ }
    }
}

/** USB HID keyboard usage codes (page 0x07) and a US-layout character map. */
object HidKeys {
    const val MOD_CTRL = 0x01
    const val MOD_SHIFT = 0x02
    const val MOD_ALT = 0x04
    const val MOD_GUI = 0x08

    const val ENTER = 0x28
    const val ESCAPE = 0x29
    const val BACKSPACE = 0x2A
    const val TAB = 0x2B
    const val SPACE = 0x2C
    const val RIGHT = 0x4F
    const val LEFT = 0x50
    const val DOWN = 0x51
    const val UP = 0x52
    const val HOME = 0x4A
    const val PAGE_UP = 0x4B
    const val DELETE = 0x4C
    const val END = 0x4D
    const val PAGE_DOWN = 0x4E

    class Key(val usage: Int, val modifiers: Int)

    private val SHIFT_DIGITS = ")!@#$%^&*("
    private val PLAIN = "-=[]" + Char(92) + ";'`,./"
    private const val PLAIN_USAGE_START = 0x2D
    private val SHIFTED = "_+{}|:\"~<>?"

    fun forChar(c: Char): Key? = when (c) {
        in 'a'..'z' -> Key(0x04 + (c - 'a'), 0)
        in 'A'..'Z' -> Key(0x04 + (c - 'A'), MOD_SHIFT)
        in '1'..'9' -> Key(0x1E + (c - '1'), 0)
        '0' -> Key(0x27, 0)
        ' ' -> Key(SPACE, 0)
        '\n' -> Key(ENTER, 0)
        '\t' -> Key(TAB, 0)
        else -> {
            val shiftedDigit = SHIFT_DIGITS.indexOf(c)
            val plain = PLAIN.indexOf(c)
            val shifted = SHIFTED.indexOf(c)
            when {
                shiftedDigit >= 0 -> Key(if (shiftedDigit == 0) 0x27 else 0x1E + shiftedDigit - 1, MOD_SHIFT)
                // '\' sits at 0x31, then ';' skips 0x32 (non-US hash key) to 0x33.
                plain >= 0 -> Key(usageForPunctuation(plain), 0)
                shifted >= 0 -> Key(usageForPunctuation(shifted), MOD_SHIFT)
                else -> null
            }
        }
    }

    private fun usageForPunctuation(index: Int): Int {
        // - = [ ] \  are 0x2D..0x31; ; ' ` , . /  are 0x33..0x38 (0x32 is the non-US hash key).
        return if (index <= 4) PLAIN_USAGE_START + index else PLAIN_USAGE_START + index + 1
    }
}
