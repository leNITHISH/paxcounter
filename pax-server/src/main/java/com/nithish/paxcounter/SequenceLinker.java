package com.nithish.paxcounter;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

// Randomized MACs mean the same physical radio can show up under a
// different hash on every probe. The 802.11 sequence number increments
// per-frame from the radio's own counter and typically isn't reset when the
// MAC rotates, so a freshly-seen hash whose sequence number picks up right
// where a recent hash left off is almost certainly the same device.
//
// Two corroborating signals from the firmware sharpen that heuristic:
//  - isRandomized (the MAC's locally-administered bit): a real, burned-in
//    MAC never rotates, so it should never be searched as a link target --
//    doing so only risks merging an unrelated device into it.
//  - ieFingerprint: a stable-per-radio signal (derived from the probe
//    request's IE tags, not its MAC) that corroborates or contradicts a
//    sequence-number match. A fingerprint mismatch blocks a link that
//    sequence-number proximity alone would allow (the main known false-
//    positive mode: two unrelated devices with adjacent sequence numbers
//    from overlapping airtime); a fingerprint match earns a wider gap
//    tolerance than sequence number alone would be trusted with.
public class SequenceLinker {
    private static final long WINDOW_MILLIS = 3000;
    // A fingerprint match is now required to link at all (see class comment),
    // so this is the only gap threshold -- slightly wider than the old
    // fingerprint-less 20, since fingerprint corroboration earns the slack.
    private static final int MAX_FORWARD_GAP = 30;
    private static final int SEQ_MODULUS = 4096; // 12-bit sequence number

    private static final class Sighting {
        final String rawHash;
        final String canonicalHash;
        final int seqNum;
        final byte[] ieFingerprint;
        final long timestamp;

        Sighting(String rawHash, String canonicalHash, int seqNum, byte[] ieFingerprint, long timestamp) {
            this.rawHash = rawHash;
            this.canonicalHash = canonicalHash;
            this.seqNum = seqNum;
            this.ieFingerprint = ieFingerprint;
            this.timestamp = timestamp;
        }
    }

    private final Deque<Sighting> recent = new ArrayDeque<>();

    // Returns the canonical device id to use for uniqueness counting: the
    // canonical hash of a recent sighting this probe links to, or the raw
    // hash itself if it looks like a genuinely new device. A non-randomized
    // MAC always resolves to itself and is never tracked as a link target.
    public String resolve(String rawHash, int seqNum, byte[] ieFingerprint, boolean isRandomized) {
        long now = System.currentTimeMillis();
        evictOlderThan(now - WINDOW_MILLIS);

        if (!isRandomized) {
            return rawHash;
        }

        String canonical = null;
        int bestGap = Integer.MAX_VALUE;
        for (Sighting s : recent) {
            if (s.rawHash.equals(rawHash)) {
                canonical = s.canonicalHash;
                break;
            }

            int forwardGap = Math.floorMod(seqNum - s.seqNum, SEQ_MODULUS);
            if (forwardGap <= 0) continue;
            if (!Arrays.equals(ieFingerprint, s.ieFingerprint)) continue; // mismatch blocks the link

            if (forwardGap <= MAX_FORWARD_GAP && forwardGap < bestGap) {
                bestGap = forwardGap;
                canonical = s.canonicalHash;
            }
        }
        if (canonical == null) canonical = rawHash;

        recent.addLast(new Sighting(rawHash, canonical, seqNum, ieFingerprint, now));
        return canonical;
    }

    private void evictOlderThan(long cutoff) {
        while (!recent.isEmpty() && recent.peekFirst().timestamp < cutoff) {
            recent.pollFirst();
        }
    }
}
