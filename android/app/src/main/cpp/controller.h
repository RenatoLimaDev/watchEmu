#pragma once
#include <cstdint>

namespace nesemu {

class Controller {
public:
    bool buttons[8] = {false, false, false, false, false, false, false, false};
    int index = 0;
    bool strobe = false;

    void setButton(int button, bool pressed) {
        if (button >= 0 && button < 8) buttons[button] = pressed;
    }

    void write(int value) {
        strobe = (value & 1) == 1;
        if (strobe) index = 0;
    }

    int read() {
        int v = (index < 8 && buttons[index]) ? 1 : 0;
        if (!strobe) {
            index++;
            if (index > 23) index = 0; // wrap around like hardware
        }
        return v | 0x40;
    }
};

} // namespace nesemu
