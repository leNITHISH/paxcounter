#ifndef PAXCOUNTER_IE_FINGERPRINT_H
#define PAXCOUNTER_IE_FINGERPRINT_H

#include <stddef.h>
#include <stdint.h>

// Walks the TLV-encoded information elements in a probe request body and
// derives a small, stable fingerprint from the (tag, length) of a fixed
// allowlist of IEs whose presence reflects the radio's chipset/driver
// capabilities, not its (possibly randomized) MAC -- so the same physical
// device should produce the same fingerprint across a MAC rotation.
// Deliberately ignores IE *content* (e.g. directed-probe SSID lists) and
// any non-allowlisted tag: fingerprinting what a device CAN do, not what
// network it's looking for, keeps this from leaking more about the user
// than the hashing scheme's own privacy goal already draws the line at.
//
// Non-cryptographic (FNV-1a): this is a matching/stability signal, not a
// secret, so there's no reason to spend a SHA-256 context on it.
//
// `body`/`bodyLen` is the probe request frame body (everything after the
// 24-byte MAC header), not the whole captured frame. Bounds-safe against a
// truncated or malformed TLV chain: stops at the first (tag, len) pair
// that would read past bodyLen rather than trusting len blindly, the same
// class of out-of-bounds read that previously crashed this firmware
// (see the sig_len check in promiscuous_callback).
//
// Shared verbatim between the firmware (paxcounter.ino.ino) and its host
// test (ie_fingerprint_test.cpp), not a mirrored reimplementation, so
// there's no chance of the two drifting apart.
inline void paxIeFingerprint(const uint8_t *body, size_t bodyLen, uint8_t out[4]) {
    static const uint8_t ALLOWLIST[] = {1, 50, 45, 127, 191, 221};
    const size_t ALLOWLIST_LEN = sizeof(ALLOWLIST);

    uint32_t hash = 2166136261u; // FNV-1a 32-bit offset basis
    size_t i = 0;
    while (i + 2 <= bodyLen) {
        uint8_t tag = body[i];
        uint8_t len = body[i + 1];
        if (i + 2 + len > bodyLen) break; // truncated IE: stop rather than overrun

        bool allowed = false;
        for (size_t a = 0; a < ALLOWLIST_LEN; a++) {
            if (ALLOWLIST[a] == tag) {
                allowed = true;
                break;
            }
        }
        if (allowed) {
            hash ^= tag;
            hash *= 16777619u; // FNV prime
            hash ^= len;
            hash *= 16777619u;
        }

        i += 2 + len;
    }

    out[0] = (uint8_t)(hash >> 24);
    out[1] = (uint8_t)(hash >> 16);
    out[2] = (uint8_t)(hash >> 8);
    out[3] = (uint8_t)(hash);
}

#endif
