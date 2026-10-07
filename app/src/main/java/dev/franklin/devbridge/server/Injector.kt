package dev.franklin.devbridge.server

import android.os.IBinder
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import java.lang.reflect.Method

/**
 * Pushes events into Android's input system through its hidden
 * `injectInputEvent`, found by reflection because it is not in the public SDK.
 * It works here because this process runs as the `shell` user, the same way
 * `adb shell input` does.
 */
class Injector {

    private val target: Any
    private val inject: Method
    private val setDisplayId: Method? = try {
        InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
    } catch (e: Exception) {
        null
    }

    private class Pointer(val id: Int, var x: Int, var y: Int)

    private val pointers = ArrayList<Pointer>()
    private var downTime = 0L

    init {
        val (obj, method) = findInputManager()
        target = obj
        inject = method
    }

    private fun findInputManager(): Pair<Any, Method> {
        val errors = ArrayList<String>()

        // Android 14+ moved the client side into InputManagerGlobal; earlier it is InputManager.
        for (name in listOf("android.hardware.input.InputManagerGlobal", "android.hardware.input.InputManager")) {
            try {
                val cls = Class.forName(name)
                val instance = cls.getDeclaredMethod("getInstance").apply { isAccessible = true }.invoke(null)
                if (instance != null) {
                    val method = cls.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
                    return instance to method
                }
            } catch (t: Throwable) {
                errors += "$name: ${t.javaClass.simpleName}"
            }
        }

        // Last resort: talk to the input service directly.
        try {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, "input") as IBinder
            val service = Class.forName("android.hardware.input.IInputManager\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
            val method = service.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
            return service to method
        } catch (t: Throwable) {
            errors += "IInputManager: ${t.javaClass.simpleName}"
        }
        throw IllegalStateException(errors.joinToString("; "))
    }

    private fun send(event: InputEvent, displayId: Int) {
        if (displayId != 0) setDisplayId?.invoke(event, displayId)
        inject.invoke(target, event, 0)       // 0 = asynchronous
    }

    @Synchronized
    fun touch(action: Int, pointerId: Int, x: Int, y: Int, displayId: Int) {
        val now = SystemClock.uptimeMillis()
        val existing = pointers.indexOfFirst { it.id == pointerId }
        val motionAction: Int
        when (action) {
            ControlProtocol.DOWN -> {
                if (existing >= 0) pointers.removeAt(existing)
                pointers.add(Pointer(pointerId, x, y))
                if (pointers.size == 1) downTime = now
                motionAction = if (pointers.size == 1) MotionEvent.ACTION_DOWN
                else MotionEvent.ACTION_POINTER_DOWN or ((pointers.size - 1) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            }
            ControlProtocol.UP -> {
                if (existing < 0) return
                pointers[existing].x = x
                pointers[existing].y = y
                motionAction = if (pointers.size == 1) MotionEvent.ACTION_UP
                else MotionEvent.ACTION_POINTER_UP or (existing shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            }
            else -> {
                if (existing < 0) return
                pointers[existing].x = x
                pointers[existing].y = y
                motionAction = MotionEvent.ACTION_MOVE
            }
        }

        val count = pointers.size
        val properties = Array(count) { i ->
            MotionEvent.PointerProperties().apply { id = pointers[i].id; toolType = MotionEvent.TOOL_TYPE_FINGER }
        }
        val coords = Array(count) { i ->
            MotionEvent.PointerCoords().apply {
                this.x = pointers[i].x.toFloat()
                this.y = pointers[i].y.toFloat()
                pressure = 1f
                size = 1f
            }
        }
        val event = MotionEvent.obtain(
            downTime, now, motionAction, count, properties, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        try {
            send(event, displayId)
        } finally {
            event.recycle()
        }

        if (motionAction == MotionEvent.ACTION_UP) pointers.clear()
        else if ((motionAction and MotionEvent.ACTION_MASK) == MotionEvent.ACTION_POINTER_UP) pointers.removeAt(existing)
    }

    fun key(action: Int, keyCode: Int, displayId: Int) {
        val now = SystemClock.uptimeMillis()
        val event = KeyEvent(
            now, now, if (action == ControlProtocol.UP) KeyEvent.ACTION_UP else KeyEvent.ACTION_DOWN,
            keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD,
        )
        send(event, displayId)
    }

    fun text(value: String, displayId: Int) {
        val events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(value.toCharArray()) ?: return
        for (event in events) send(event, displayId)
    }

    fun scroll(x: Int, y: Int, horizontal: Float, vertical: Float, displayId: Int) {
        val now = SystemClock.uptimeMillis()
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                this.x = x.toFloat()
                this.y = y.toFloat()
                setAxisValue(MotionEvent.AXIS_HSCROLL, horizontal)
                setAxisValue(MotionEvent.AXIS_VSCROLL, vertical)
            },
        )
        val event = MotionEvent.obtain(
            now, now, MotionEvent.ACTION_SCROLL, 1, properties, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0,
        )
        try {
            send(event, displayId)
        } finally {
            event.recycle()
        }
    }
}
