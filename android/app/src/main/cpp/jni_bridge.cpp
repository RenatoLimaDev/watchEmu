#include <jni.h>
#include <mutex>
#include <cstring>
#include <vector>
#include "nes.h"

using namespace nesemu;

namespace {
Nes g_nes;
std::mutex g_mutex;
double g_cycleAccum = 0.0;
uint32_t g_present[Ppu::WIDTH * Ppu::HEIGHT] = {0};
constexpr double CYCLES_PER_SAMPLE = Apu::CYCLES_PER_SAMPLE;
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_watchemu_app_nes_NativeBridge_loadRom(JNIEnv* env, jclass, jbyteArray data) {
    std::lock_guard<std::mutex> lock(g_mutex);
    jsize len = env->GetArrayLength(data);
    jbyte* p = env->GetByteArrayElements(data, nullptr);
    bool ok = g_nes.loadRom(reinterpret_cast<const uint8_t*>(p), static_cast<size_t>(len));
    env->ReleaseByteArrayElements(data, p, JNI_ABORT);
    g_cycleAccum = 0.0;
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_watchemu_app_nes_NativeBridge_reset(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_nes.romLoaded) g_nes.reset();
    g_cycleAccum = 0.0;
}

JNIEXPORT void JNICALL
Java_com_watchemu_app_nes_NativeBridge_setButton(JNIEnv*, jclass, jint button, jboolean pressed) {
    if (pressed) g_nes.buttonDown(button);
    else g_nes.buttonUp(button);
}

JNIEXPORT jboolean JNICALL
Java_com_watchemu_app_nes_NativeBridge_isRomLoaded(JNIEnv*, jclass) {
    return g_nes.romLoaded ? JNI_TRUE : JNI_FALSE;
}

// Runs the emulation to produce `count` audio samples into `out`. Video frames
// completed during the call are snapshotted into g_present; returns how many.
JNIEXPORT jint JNICALL
Java_com_watchemu_app_nes_NativeBridge_renderAudio(JNIEnv* env, jclass, jfloatArray outArr, jint count) {
    std::lock_guard<std::mutex> lock(g_mutex);
    jfloat* out = env->GetFloatArrayElements(outArr, nullptr);

    if (!g_nes.romLoaded) {
        for (int i = 0; i < count; i++) out[i] = 0.0f;
        env->ReleaseFloatArrayElements(outArr, out, 0);
        return 0;
    }

    int frames = 0;
    for (int i = 0; i < count; i++) {
        while (g_cycleAccum < CYCLES_PER_SAMPLE) {
            bool was = g_nes.ppu.frameComplete;
            int cyc = g_nes.stepInstruction();
            g_cycleAccum += cyc;
            if (g_nes.ppu.frameComplete && !was) {
                g_nes.ppu.frameComplete = false;
                std::memcpy(g_present, g_nes.ppu.frameBuffer, sizeof(g_present));
                frames++;
            }
        }
        g_cycleAccum -= CYCLES_PER_SAMPLE;
        out[i] = g_nes.apu.mixOneSample();
    }

    env->ReleaseFloatArrayElements(outArr, out, 0);
    return frames;
}

JNIEXPORT void JNICALL
Java_com_watchemu_app_nes_NativeBridge_getFrameBuffer(JNIEnv* env, jclass, jintArray dst) {
    std::lock_guard<std::mutex> lock(g_mutex);
    env->SetIntArrayRegion(dst, 0, Ppu::WIDTH * Ppu::HEIGHT,
                           reinterpret_cast<const jint*>(g_present));
}

JNIEXPORT jbyteArray JNICALL
Java_com_watchemu_app_nes_NativeBridge_saveState(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    std::vector<uint8_t> data = g_nes.saveState();
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(data.size()));
    env->SetByteArrayRegion(arr, 0, static_cast<jsize>(data.size()),
                            reinterpret_cast<const jbyte*>(data.data()));
    return arr;
}

JNIEXPORT jboolean JNICALL
Java_com_watchemu_app_nes_NativeBridge_loadState(JNIEnv* env, jclass, jbyteArray data) {
    std::lock_guard<std::mutex> lock(g_mutex);
    jsize len = env->GetArrayLength(data);
    jbyte* p = env->GetByteArrayElements(data, nullptr);
    bool ok = g_nes.loadState(reinterpret_cast<const uint8_t*>(p), static_cast<size_t>(len));
    env->ReleaseByteArrayElements(data, p, JNI_ABORT);
    g_cycleAccum = 0.0;
    return ok ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
