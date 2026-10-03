// Host-side test for ie_fingerprint.h -- run with:
//   g++ -std=c++17 -fsanitize=address,undefined -o ie_fingerprint_test ie_fingerprint_test.cpp && ./ie_fingerprint_test
// Not part of the Arduino build; this file never ships to the device.
#include <cassert>
#include <cstdio>
#include <cstring>
#include <vector>

#include "ie_fingerprint.h"

static std::vector<uint8_t> ie(uint8_t tag, uint8_t len) {
    std::vector<uint8_t> v = {tag, len};
    v.resize(2 + len, 0xAA); // content doesn't matter, fingerprint ignores it
    return v;
}

static std::vector<uint8_t> concat(std::vector<std::vector<uint8_t>> ies) {
    std::vector<uint8_t> out;
    for (auto &v : ies) out.insert(out.end(), v.begin(), v.end());
    return out;
}

static void fp(const std::vector<uint8_t> &body, uint8_t out[4]) {
    paxIeFingerprint(body.data(), body.size(), out);
}

int main() {
    // Deterministic: same bytes in, same fingerprint out, every time.
    {
        auto body = concat({ie(1, 8), ie(50, 4), ie(45, 26)});
        uint8_t a[4], b[4];
        fp(body, a);
        fp(body, b);
        assert(memcmp(a, b, 4) == 0);
        printf("deterministic: PASS\n");
    }

    // Two different IE sets (different radios) produce different fingerprints.
    {
        auto bodyA = concat({ie(1, 8), ie(50, 4), ie(45, 26)});
        auto bodyB = concat({ie(1, 8), ie(50, 4), ie(191, 12)}); // has VHT instead of HT
        uint8_t a[4], b[4];
        fp(bodyA, a);
        fp(bodyB, b);
        assert(memcmp(a, b, 4) != 0);
        printf("different IE sets differ: PASS\n");
    }

    // Non-allowlisted IEs (e.g. SSID=0) and IE *content* don't affect the
    // fingerprint -- only allowlisted (tag, len) pairs matter.
    {
        auto bodyWithSsid = concat({ie(0, 5), ie(1, 8), ie(50, 4), ie(45, 26)});
        auto bodyWithoutSsid = concat({ie(1, 8), ie(50, 4), ie(45, 26)});
        uint8_t withSsid[4], withoutSsid[4];
        fp(bodyWithSsid, withSsid);
        fp(bodyWithoutSsid, withoutSsid);
        assert(memcmp(withSsid, withoutSsid, 4) == 0);
        printf("non-allowlisted IEs ignored: PASS\n");
    }

    // Truncated/malformed TLV chain: a claimed length that runs past the
    // buffer must not be read -- the walk stops instead of overrunning.
    // Run under ASan; a clean exit (no crash) is the actual assertion here.
    {
        std::vector<uint8_t> body = {1, 250, 0xAA, 0xAA}; // claims 250 bytes, only has 2
        uint8_t out[4];
        fp(body, out); // must not read past body.data()+body.size()
        printf("truncated IE doesn't overrun: PASS\n");
    }

    // Empty body: no crash, deterministic fixed output.
    {
        std::vector<uint8_t> body;
        uint8_t a[4], b[4];
        fp(body, a);
        fp(body, b);
        assert(memcmp(a, b, 4) == 0);
        printf("empty body: PASS\n");
    }

    printf("ALL IE FINGERPRINT CHECKS PASSED\n");
    return 0;
}
