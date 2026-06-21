package com.watchemu.app.nes

class Controller {
    companion object {
        const val BUTTON_A = 0
        const val BUTTON_B = 1
        const val BUTTON_SELECT = 2
        const val BUTTON_START = 3
        const val BUTTON_UP = 4
        const val BUTTON_DOWN = 5
        const val BUTTON_LEFT = 6
        const val BUTTON_RIGHT = 7
    }

    private val buttons = BooleanArray(8)
    private var index = 0
    private var strobe = false

    fun setButton(button: Int, pressed: Boolean) {
        if (button in 0..7) buttons[button] = pressed
    }

    fun write(value: Int) {
        strobe = (value and 1) == 1
        if (strobe) index = 0
    }

    fun read(): Int {
        val v = if (index < 8 && buttons[index]) 1 else 0
        if (!strobe) {
            index++
            if (index > 23) index = 0 // wrap around like hardware
        }
        return v or 0x40
    }
}
