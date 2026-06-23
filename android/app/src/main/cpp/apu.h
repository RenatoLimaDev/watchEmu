#pragma once
#include <cstdint>
#include <algorithm>
#include "serialize.h"

namespace nesemu {

static const int APU_LENGTH_TABLE[32] = {
    10, 254, 20, 2, 40, 4, 80, 6, 160, 8, 60, 10, 14, 12, 26, 14,
    12, 16, 24, 18, 48, 20, 96, 22, 192, 24, 72, 26, 16, 28, 32, 30
};
static const int APU_NOISE_PERIOD[16] = {
    4, 8, 16, 32, 64, 96, 128, 160, 202, 254, 380, 508, 762, 1016, 2034, 4068
};
static const float APU_DUTY[4][8] = {
    {-1, 1, -1, -1, -1, -1, -1, -1},
    {-1, 1, 1, -1, -1, -1, -1, -1},
    {-1, 1, 1, 1, 1, -1, -1, -1},
    { 1, -1, -1, 1, 1, 1, 1, 1}
};

class Apu {
public:
    static constexpr int SAMPLE_RATE = 44100;
    static constexpr double CPU_FREQ = 1789773.0;
    static constexpr double CYCLES_PER_SAMPLE = CPU_FREQ / SAMPLE_RATE; // ~40.58

    // Pulse 1
    int p1Duty = 0, p1Vol = 0, p1Period = 0, p1Len = 0;
    bool p1Halt = false, p1Const = false;
    int p1EnvD = 0, p1EnvC = 0; bool p1EnvS = false;
    bool p1SwEn = false; int p1SwP = 0; bool p1SwN = false; int p1SwS = 0; bool p1SwR = false; int p1SwC = 0;

    // Pulse 2
    int p2Duty = 0, p2Vol = 0, p2Period = 0, p2Len = 0;
    bool p2Halt = false, p2Const = false;
    int p2EnvD = 0, p2EnvC = 0; bool p2EnvS = false;
    bool p2SwEn = false; int p2SwP = 0; bool p2SwN = false; int p2SwS = 0; bool p2SwR = false; int p2SwC = 0;

    // Triangle
    int triPeriod = 0, triLen = 0, triLin = 0;
    bool triCtrl = false; int triLinLoad = 0; bool triLinR = false;

    // Noise
    int noVol = 0, noPeriod = 4, noLen = 0; bool noMode = false;
    bool noHalt = false, noConst = false;
    int noEnvD = 0, noEnvC = 0; bool noEnvS = false;

    // Enable flags
    bool enP1 = false, enP2 = false, enT = false, enN = false;

    // Frame counter
    int fcMode = 0, fcStep = 0;
    double frameAccum = 0.0;

    // Synthesis
    double p1Phase = 0.0, p2Phase = 0.0, triPhase = 0.0, noPhase = 0.0;
    int noShift = 1;

    // Filters
    float hpAccum1 = 0, hpAccum2 = 0, lpAccum = 0;

    float PULSE_TABLE[31];
    float TND_TABLE[203];

    Apu() {
        for (int n = 0; n < 31; n++)
            PULSE_TABLE[n] = (n == 0) ? 0.0f : 95.52f / (8128.0f / n + 100.0f);
        for (int n = 0; n < 203; n++)
            TND_TABLE[n] = (n == 0) ? 0.0f : 163.67f / (24329.0f / n + 100.0f);
        reset();
    }

    void reset() {
        p1Duty = 0; p1Vol = 0; p1Period = 0; p1Len = 0; p1Halt = false; p1Const = false;
        p1EnvD = 0; p1EnvC = 0; p1EnvS = false;
        p1SwEn = false; p1SwP = 0; p1SwN = false; p1SwS = 0; p1SwR = false; p1SwC = 0;
        p2Duty = 0; p2Vol = 0; p2Period = 0; p2Len = 0; p2Halt = false; p2Const = false;
        p2EnvD = 0; p2EnvC = 0; p2EnvS = false;
        p2SwEn = false; p2SwP = 0; p2SwN = false; p2SwS = 0; p2SwR = false; p2SwC = 0;
        triPeriod = 0; triLen = 0; triLin = 0; triCtrl = false; triLinLoad = 0; triLinR = false;
        noVol = 0; noPeriod = 4; noLen = 0; noMode = false; noHalt = false; noConst = false;
        noEnvD = 0; noEnvC = 0; noEnvS = false;
        enP1 = false; enP2 = false; enT = false; enN = false;
        fcMode = 0; fcStep = 0; frameAccum = 0.0;
        p1Phase = 0.0; p2Phase = 0.0; triPhase = 0.0; noPhase = 0.0; noShift = 1;
        hpAccum1 = 0; hpAccum2 = 0; lpAccum = 0;
    }

    void stepFrameCounter() {
        frameAccum++;
        if (frameAccum < 7457.5) return;
        frameAccum = 0.0;

        if (fcMode == 0) {
            switch (fcStep % 4) {
                case 0: envAll(); break;
                case 1: envAll(); lenAll(); sweepAll(); break;
                case 2: envAll(); break;
                case 3: envAll(); lenAll(); sweepAll(); break;
            }
            fcStep++;
        } else {
            switch (fcStep % 5) {
                case 0: envAll(); break;
                case 1: envAll(); lenAll(); sweepAll(); break;
                case 2: envAll(); break;
                case 3: break;
                case 4: envAll(); lenAll(); sweepAll(); break;
            }
            fcStep++;
        }
    }

    void envAll() {
        if (p1EnvS) { p1EnvS = false; p1EnvD = 15; p1EnvC = p1Vol; }
        else if (p1EnvC > 0) p1EnvC--;
        else { p1EnvC = p1Vol; if (p1EnvD > 0) p1EnvD--; else if (p1Halt) p1EnvD = 15; }

        if (p2EnvS) { p2EnvS = false; p2EnvD = 15; p2EnvC = p2Vol; }
        else if (p2EnvC > 0) p2EnvC--;
        else { p2EnvC = p2Vol; if (p2EnvD > 0) p2EnvD--; else if (p2Halt) p2EnvD = 15; }

        if (noEnvS) { noEnvS = false; noEnvD = 15; noEnvC = noVol; }
        else if (noEnvC > 0) noEnvC--;
        else { noEnvC = noVol; if (noEnvD > 0) noEnvD--; else if (noHalt) noEnvD = 15; }

        if (triLinR) triLin = triLinLoad;
        else if (triLin > 0) triLin--;
        if (!triCtrl) triLinR = false;
    }

    void lenAll() {
        if (!p1Halt && p1Len > 0) p1Len--;
        if (!p2Halt && p2Len > 0) p2Len--;
        if (!triCtrl && triLen > 0) triLen--;
        if (!noHalt && noLen > 0) noLen--;
    }

    void sweepAll() {
        if (p1SwR || p1SwC == 0) {
            if (p1SwEn && p1SwS > 0) { int d = p1Period >> p1SwS; p1Period += p1SwN ? -(d + 1) : d; }
            p1SwC = p1SwP + 1; p1SwR = false;
        } else p1SwC--;

        if (p2SwR || p2SwC == 0) {
            if (p2SwEn && p2SwS > 0) { int d = p2Period >> p2SwS; p2Period += p2SwN ? -d : d; }
            p2SwC = p2SwP + 1; p2SwR = false;
        } else p2SwC--;
    }

    void writeRegister(int addr, int v) {
        switch (addr) {
            case 0x4000: p1Duty = (v >> 6) & 3; p1Halt = (v & 0x20) != 0; p1Const = (v & 0x10) != 0; p1Vol = v & 0xF; break;
            case 0x4001: p1SwEn = (v & 0x80) != 0; p1SwP = (v >> 4) & 7; p1SwN = (v & 8) != 0; p1SwS = v & 7; p1SwR = true; break;
            case 0x4002: p1Period = (p1Period & 0x700) | v; break;
            case 0x4003: if (enP1) { p1Period = (p1Period & 0xFF) | ((v & 7) << 8); p1Len = APU_LENGTH_TABLE[(v >> 3) & 0x1F]; p1Phase = 0.0; p1EnvS = true; } break;
            case 0x4004: p2Duty = (v >> 6) & 3; p2Halt = (v & 0x20) != 0; p2Const = (v & 0x10) != 0; p2Vol = v & 0xF; break;
            case 0x4005: p2SwEn = (v & 0x80) != 0; p2SwP = (v >> 4) & 7; p2SwN = (v & 8) != 0; p2SwS = v & 7; p2SwR = true; break;
            case 0x4006: p2Period = (p2Period & 0x700) | v; break;
            case 0x4007: if (enP2) { p2Period = (p2Period & 0xFF) | ((v & 7) << 8); p2Len = APU_LENGTH_TABLE[(v >> 3) & 0x1F]; p2Phase = 0.0; p2EnvS = true; } break;
            case 0x4008: triCtrl = (v & 0x80) != 0; triLinLoad = v & 0x7F; break;
            case 0x400A: triPeriod = (triPeriod & 0x700) | v; break;
            case 0x400B: if (enT) { triPeriod = (triPeriod & 0xFF) | ((v & 7) << 8); triLen = APU_LENGTH_TABLE[(v >> 3) & 0x1F]; triLinR = true; } break;
            case 0x400C: noHalt = (v & 0x20) != 0; noConst = (v & 0x10) != 0; noVol = v & 0xF; break;
            case 0x400E: noMode = (v & 0x80) != 0; noPeriod = APU_NOISE_PERIOD[v & 0xF]; break;
            case 0x400F: if (enN) { noLen = APU_LENGTH_TABLE[(v >> 3) & 0x1F]; noEnvS = true; } break;
            case 0x4010: case 0x4011: case 0x4012: case 0x4013: break;
            case 0x4015:
                enP1 = (v & 1) != 0; enP2 = (v & 2) != 0; enT = (v & 4) != 0; enN = (v & 8) != 0;
                if (!enP1) p1Len = 0;
                if (!enP2) p2Len = 0;
                if (!enT) triLen = 0;
                if (!enN) noLen = 0;
                break;
            case 0x4017: fcMode = (v >> 7) & 1; fcStep = 0; if (fcMode == 1) { envAll(); lenAll(); sweepAll(); } break;
        }
    }

    int readStatus() {
        int r = 0;
        if (p1Len > 0) r |= 1;
        if (p2Len > 0) r |= 2;
        if (triLen > 0) r |= 4;
        if (noLen > 0) r |= 8;
        return r;
    }

    float mixOneSample() {
        int p1Out = 0;
        if (enP1 && p1Len > 0 && p1Period >= 8 && p1Period <= 0x7FF) {
            double freq = CPU_FREQ / (16.0 * (p1Period + 1));
            p1Phase += freq / SAMPLE_RATE;
            if (p1Phase >= 1.0) p1Phase -= 1.0;
            int step = int(p1Phase * 8) & 7;
            int vol = p1Const ? p1Vol : p1EnvD;
            if (APU_DUTY[p1Duty][step] > 0.0f) p1Out = vol;
        }

        int p2Out = 0;
        if (enP2 && p2Len > 0 && p2Period >= 8 && p2Period <= 0x7FF) {
            double freq = CPU_FREQ / (16.0 * (p2Period + 1));
            p2Phase += freq / SAMPLE_RATE;
            if (p2Phase >= 1.0) p2Phase -= 1.0;
            int step = int(p2Phase * 8) & 7;
            int vol = p2Const ? p2Vol : p2EnvD;
            if (APU_DUTY[p2Duty][step] > 0.0f) p2Out = vol;
        }

        int triOut = 0;
        if (enT && triLen > 0 && triLin > 0 && triPeriod >= 2) {
            double freq = CPU_FREQ / (32.0 * (triPeriod + 1));
            triPhase += freq / SAMPLE_RATE;
            if (triPhase >= 1.0) triPhase -= 1.0;
            int step = int(triPhase * 32) & 31;
            triOut = (step < 16) ? (15 - step) : (step - 16);
        }

        int noOut = 0;
        if (enN && noLen > 0) {
            double freq = CPU_FREQ / (2.0 * noPeriod);
            noPhase += freq / SAMPLE_RATE;
            while (noPhase >= 1.0) {
                noPhase -= 1.0;
                int bit = noMode ? 6 : 1;
                int fb = (noShift ^ (noShift >> bit)) & 1;
                noShift = (noShift >> 1) | (fb << 14);
            }
            int vol = noConst ? noVol : noEnvD;
            if ((noShift & 1) == 0) noOut = vol;
        }

        int pulseIdx = std::min(std::max(p1Out + p2Out, 0), 30);
        int tndIdx = std::min(std::max(3 * triOut + 2 * noOut, 0), 202);
        float sample = PULSE_TABLE[pulseIdx] + TND_TABLE[tndIdx];

        sample = sample * 2.0f - 1.0f;

        const float hp1Alpha = 0.99654f;
        hpAccum1 = hp1Alpha * hpAccum1 + sample;
        sample = sample - hpAccum1 * (1.0f - hp1Alpha);

        const float hp2Alpha = 0.97345f;
        float prevHp2 = hpAccum2;
        hpAccum2 = hp2Alpha * hpAccum2 + sample;
        sample = sample - prevHp2 * (1.0f - hp2Alpha);

        const float lpAlpha = 0.815f;
        lpAccum += lpAlpha * (sample - lpAccum);
        sample = lpAccum;

        sample = sample * 0.7f;
        if (sample > 1.0f) sample = 1.0f;
        if (sample < -1.0f) sample = -1.0f;
        return sample;
    }

    void saveState(ByteWriter& out) {
        out.i32(p1Duty); out.i32(p1Vol); out.i32(p1Period); out.i32(p1Len);
        out.boolean(p1Halt); out.boolean(p1Const);
        out.i32(p1EnvD); out.i32(p1EnvC); out.boolean(p1EnvS);
        out.boolean(p1SwEn); out.i32(p1SwP); out.boolean(p1SwN); out.i32(p1SwS); out.boolean(p1SwR); out.i32(p1SwC);

        out.i32(p2Duty); out.i32(p2Vol); out.i32(p2Period); out.i32(p2Len);
        out.boolean(p2Halt); out.boolean(p2Const);
        out.i32(p2EnvD); out.i32(p2EnvC); out.boolean(p2EnvS);
        out.boolean(p2SwEn); out.i32(p2SwP); out.boolean(p2SwN); out.i32(p2SwS); out.boolean(p2SwR); out.i32(p2SwC);

        out.i32(triPeriod); out.i32(triLen); out.i32(triLin);
        out.boolean(triCtrl); out.i32(triLinLoad); out.boolean(triLinR);

        out.i32(noVol); out.i32(noPeriod); out.i32(noLen); out.boolean(noMode);
        out.boolean(noHalt); out.boolean(noConst);
        out.i32(noEnvD); out.i32(noEnvC); out.boolean(noEnvS);

        out.boolean(enP1); out.boolean(enP2); out.boolean(enT); out.boolean(enN);
        out.i32(fcMode); out.i32(fcStep); out.f64(frameAccum);
    }

    void loadState(ByteReader& in) {
        p1Duty = in.i32(); p1Vol = in.i32(); p1Period = in.i32(); p1Len = in.i32();
        p1Halt = in.boolean(); p1Const = in.boolean();
        p1EnvD = in.i32(); p1EnvC = in.i32(); p1EnvS = in.boolean();
        p1SwEn = in.boolean(); p1SwP = in.i32(); p1SwN = in.boolean(); p1SwS = in.i32(); p1SwR = in.boolean(); p1SwC = in.i32();

        p2Duty = in.i32(); p2Vol = in.i32(); p2Period = in.i32(); p2Len = in.i32();
        p2Halt = in.boolean(); p2Const = in.boolean();
        p2EnvD = in.i32(); p2EnvC = in.i32(); p2EnvS = in.boolean();
        p2SwEn = in.boolean(); p2SwP = in.i32(); p2SwN = in.boolean(); p2SwS = in.i32(); p2SwR = in.boolean(); p2SwC = in.i32();

        triPeriod = in.i32(); triLen = in.i32(); triLin = in.i32();
        triCtrl = in.boolean(); triLinLoad = in.i32(); triLinR = in.boolean();

        noVol = in.i32(); noPeriod = in.i32(); noLen = in.i32(); noMode = in.boolean();
        noHalt = in.boolean(); noConst = in.boolean();
        noEnvD = in.i32(); noEnvC = in.i32(); noEnvS = in.boolean();

        enP1 = in.boolean(); enP2 = in.boolean(); enT = in.boolean(); enN = in.boolean();
        fcMode = in.i32(); fcStep = in.i32(); frameAccum = in.f64();
    }
};

} // namespace nesemu
