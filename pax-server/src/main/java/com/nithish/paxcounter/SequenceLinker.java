package com.nithish.paxcounter;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

// Randomized MACs mean the same physical radio can show up under a
// different hash on every probe. The 802.11 sequence number increments
// per-frame from the radio's own counter and typically isn't reset when the
// MAC rotates, so a freshly-seen hash whose sequence number picks up right
// where a recent hash left off is a candidate for being the same device.
//
// Two corroborating signals from the firmware sharpen that heuristic:
//  - isRandomized (the MAC's locally-administered bit): a real, burned-in
//    MAC never rotates, so it should never be searched as a link target --
//    doing so only risks merging an unrelated device into it.
//  - ieFingerprint: a signal derived from the probe request's IE tags, not
//    its MAC, that corroborates or contradicts a sequence-number match. A
//    mismatch blocks a link that sequence-number proximity alone would
//    allow. A match is required to link at all, but it is NOT strong
//    evidence on its own: it is 4 bytes of tag IDs and lengths, which
//    same-model phones share by construction, so in a crowded room with
//    several identical-model devices active, "same fingerprint" is common
//    rather than distinguishing. If more than one recent sighting plausibly
//    matches, that ambiguity is refused rather than resolved by picking the
//    nearest sequence gap.
//
// Once a raw hash is assigned a canonical id, that assignment is permanent
// for the lifetime of this process. Earlier versions re-derived the link
// from only the last few seconds of history every time a hash was seen,
// which meant the same raw hash could resolve to a different canonical id
// depending on what else happened to be nearby later, an instability a real
// capture exposed is not just theoretical.
//
// Timestamps passed in are the ESP32's own millis() at capture time, not
// host arrival time; see TtlCache for why that distinction matters here too
// (the ring buffer replaying a backlog after a USB reconnect).
public class SequenceLinker {
    private static final long WINDOW_MILLIS = 3000;
    // A fingerprint match is required to link at all (see class comment),
    // so this is the only gap threshold -- slightly wider than the old
    // fingerprint-less 20, since fingerprint corroboration earns the slack.
    private static final int MAX_FORWARD_GAP = 30;
    private static final int SEQ_MODULUS = 4096; // 12-bit sequence number

    private static final class Sighting {
        final String canonicalHash;
        final int seqNum;
        final byte[] ieFingerprint;
        final long timestamp;

        Sighting(String canonicalHash, int seqNum, byte[] ieFingerprint, long timestamp) {
            this.canonicalHash = canonicalHash;
            this.seqNum = seqNum;
            this.ieFingerprint = ieFingerprint;
            this.timestamp = timestamp;
        }
    }

    private final Deque<Sighting> recent = new ArrayDeque<>();
    // Permanent: once set, a raw hash's canonical id never changes again.
    private final Map<String, String> rawToCanonical = new HashMap<>();
    private long lastDeviceTimestamp = -1;

    // Returns the canonical device id to use for uniqueness counting.
    public String resolve(String rawHash, int seqNum, byte[] ieFingerprint, boolean isRandomized,
            long deviceTimestamp) {
        if (deviceTimestamp < lastDeviceTimestamp) {
            // The device's clock went backwards: it rebooted (millis() reset),
            // not that time ran backwards. Everything in the recent-sightings
            // window was captured under the old clock and is no longer
            // comparable to anything new.
            recent.clear();
        }
        lastDeviceTimestamp = Math.max(lastDeviceTimestamp, deviceTimestamp);
        evictOlderThan(deviceTimestamp - WINDOW_MILLIS);

        String existing = rawToCanonical.get(rawHash);
        if (existing != null) {
            // A non-randomized identity must never become a link target, on
            // the first sighting or any later one -- only track it in
            // `recent` when this sighting is itself randomized.
            if (isRandomized) {
                recent.addLast(new Sighting(existing, seqNum, ieFingerprint, deviceTimestamp));
            }
            return existing;
        }

        String canonical = isRandomized ? findUniqueCandidate(seqNum, ieFingerprint) : null;
        if (canonical == null) canonical = rawHash; // no match, ambiguous match, or not randomized

        rawToCanonical.put(rawHash, canonical);
        if (isRandomized) {
            recent.addLast(new Sighting(canonical, seqNum, ieFingerprint, deviceTimestamp));
        }
        return canonical;
    }

    // Returns the single canonical id that plausibly matches, or null if
    // nothing matches or more than one distinct canonical does (refuse
    // rather than guess which one is right).
    private String findUniqueCandidate(int seqNum, byte[] ieFingerprint) {
        String candidate = null;
        int bestGap = Integer.MAX_VALUE;
        for (Sighting s : recent) {
            int forwardGap = Math.floorMod(seqNum - s.seqNum, SEQ_MODULUS);
            if (forwardGap <= 0 || forwardGap > MAX_FORWARD_GAP) continue;
            if (!Arrays.equals(ieFingerprint, s.ieFingerprint)) continue;

            if (candidate == null) {
                candidate = s.canonicalHash;
                bestGap = forwardGap;
            } else if (!candidate.equals(s.canonicalHash)) {
                return null; // two distinct canonicals both plausible: ambiguous
            } else if (forwardGap < bestGap) {
                bestGap = forwardGap;
            }
        }
        return candidate;
    }

    private void evictOlderThan(long cutoff) {
        while (!recent.isEmpty() && recent.peekFirst().timestamp < cutoff) {
            recent.pollFirst();
        }
    }
}
