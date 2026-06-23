package com.watchemu.app.nes

/**
 * Controller button indices, shared with the native core. The values are the
 * standard NES report order (A, B, Select, Start, Up, Down, Left, Right) and are
 * passed straight to [NativeBridge.setButton].
 */
object Controller {
    const val BUTTON_A = 0
    const val BUTTON_B = 1
    const val BUTTON_SELECT = 2
    const val BUTTON_START = 3
    const val BUTTON_UP = 4
    const val BUTTON_DOWN = 5
    const val BUTTON_LEFT = 6
    const val BUTTON_RIGHT = 7
}
