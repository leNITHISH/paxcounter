package com.nithish.paxcounter;

import java.util.ArrayDeque;
import java.util.Deque;

// Randomized MACs mean the same physical radio can show up under a
// different hash on every probe. The 802.11 sequence number increments
// per-frame from the radio's own counter and typically isn't reset when the
// MAC rotates, so a freshly-seen hash whose sequence number picks up right
// where a recent hash left off is almost certainly the same device.
public class SequenceLinker {
    private static final long WINDOW_MILLIS = 3000;
    private static final int MAX_FORWARD_GAP = 20; // allows for other mgmt frames in between
    private static final int SEQ_MODULUS = 4096;   // 12-bit sequence number

    private static final class Sighting {
        final String rawHash;
        final String canonicalHash;
        final int seqNum;
        final long timestamp;

        Sighting(String rawHash, String canonicalHash, int seqNum, long timestamp) {
            this.rawHash = rawHash;
            this.canonicalHash = canonicalHash;
            this.seqNum = seqNum;
            this.timestamp = timestamp;
        }
    }

    private final Deque<Sighting> recent = new ArrayDeque<>();

    // Returns the canonical device id to use for uniqueness counting: the
    // canonical hash of a recent sighting this probe links to, or the raw
    // hash itself if it looks like a genuinely new device.
    public String resolve(String rawHash, int seqNum) {
        long now = System.currentTimeMillis();
        evictOlderThan(now - WINDOW_MILLIS);

        String canonical = null;
        int bestGap = Integer.MAX_VALUE;
        for (Sighting s : recent) {
            if (s.rawHash.equals(rawHash)) {
                canonical = s.canonicalHash;
                break;
            }
            int forwardGap = Math.floorMod(seqNum - s.seqNum, SEQ_MODULUS);
            if (forwardGap > 0 && forwardGap <= MAX_FORWARD_GAP && forwardGap < bestGap) {
                bestGap = forwardGap;
                canonical = s.canonicalHash;
            }
        }
        if (canonical == null) canonical = rawHash;

        recent.addLast(new Sighting(rawHash, canonical, seqNum, now));
        return canonical;
    }

    private void evictOlderThan(long cutoff) {
        while (!recent.isEmpty() && recent.peekFirst().timestamp < cutoff) {
            recent.pollFirst();
        }
    }
}
