#pragma once
#include <cstdint>
#include <cstring>
#include <vector>

// Binary (de)serialization that matches Java's DataOutputStream/DataInputStream
// byte layout, so save states produced/consumed here stay compatible with the
// original Kotlin format: big-endian int32, 1-byte booleans, big-endian IEEE-754
// doubles, raw byte blocks.
namespace nesemu {

struct ByteWriter {
    std::vector<uint8_t> buf;

    void u8(int v) { buf.push_back(static_cast<uint8_t>(v & 0xFF)); }
    void boolean(bool b) { buf.push_back(b ? 1 : 0); }
    void i32(int32_t v) {
        buf.push_back((v >> 24) & 0xFF);
        buf.push_back((v >> 16) & 0xFF);
        buf.push_back((v >> 8) & 0xFF);
        buf.push_back(v & 0xFF);
    }
    void f64(double d) {
        uint64_t bits;
        std::memcpy(&bits, &d, sizeof(bits));
        for (int s = 56; s >= 0; s -= 8) buf.push_back((bits >> s) & 0xFF);
    }
    void bytes(const uint8_t* p, size_t n) { buf.insert(buf.end(), p, p + n); }
};

struct ByteReader {
    const uint8_t* p;
    size_t n;
    size_t pos = 0;
    bool ok = true;

    ByteReader(const uint8_t* data, size_t len) : p(data), n(len) {}

    int32_t i32() {
        if (pos + 4 > n) { ok = false; return 0; }
        int32_t v = (int32_t(p[pos]) << 24) | (int32_t(p[pos + 1]) << 16) |
                    (int32_t(p[pos + 2]) << 8) | int32_t(p[pos + 3]);
        pos += 4;
        return v;
    }
    bool boolean() {
        if (pos + 1 > n) { ok = false; return false; }
        return p[pos++] != 0;
    }
    double f64() {
        if (pos + 8 > n) { ok = false; return 0.0; }
        uint64_t bits = 0;
        for (int i = 0; i < 8; i++) bits = (bits << 8) | p[pos++];
        double d;
        std::memcpy(&d, &bits, sizeof(d));
        return d;
    }
    void bytes(uint8_t* dst, size_t cnt) {
        if (pos + cnt > n) { ok = false; return; }
        std::memcpy(dst, p + pos, cnt);
        pos += cnt;
    }
};

} // namespace nesemu
