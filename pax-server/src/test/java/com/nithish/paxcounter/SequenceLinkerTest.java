package com.nithish.paxcounter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

public class SequenceLinkerTest {

    private static final byte[] FP_RADIO_A = {1, 2, 3, 4};
    private static final byte[] FP_RADIO_B = {9, 9, 9, 9};

    @Test
    public void firstSightingResolvesToItsOwnHash() {
        SequenceLinker linker = new SequenceLinker();
        assertEquals("macA", linker.resolve("macA", 100, FP_RADIO_A, true, 0));
    }

    @Test
    public void rotatingMacWithCloseSequenceNumbersLinksToOneCanonicalId() {
        SequenceLinker linker = new SequenceLinker();
        String c1 = linker.resolve("macA", 100, FP_RADIO_A, true, 0);
        String c2 = linker.resolve("macB", 103, FP_RADIO_A, true, 100); // same radio, MAC rotated
        String c3 = linker.resolve("macC", 107, FP_RADIO_A, true, 200);
        assertEquals(c1, c2);
        assertEquals(c2, c3);
    }

    @Test
    public void unrelatedDeviceWithFarSequenceNumberIsNotLinked() {
        SequenceLinker linker = new SequenceLinker();
        String c1 = linker.resolve("macA", 100, FP_RADIO_A, true, 0);
        String c2 = linker.resolve("macD", 3000, FP_RADIO_A, true, 100); // far outside the forward-gap window
        assertNotEquals(c1, c2);
    }

    @Test
    public void repeatOfTheExactSameHashResolvesToItsOwnCanonicalIdEvenAfterTheLinkingWindowExpires() {
        SequenceLinker linker = new SequenceLinker();
        String c1 = linker.resolve("macA", 100, FP_RADIO_A, true, 0);
        // 10 seconds later, long past the 3s linking window. The old,
        // window-only design would have lost this and risked re-deriving a
        // different answer; the permanent raw-hash-to-canonical map must not.
        String c1Again = linker.resolve("macA", 250, FP_RADIO_A, true, 10_000);
        assertEquals(c1, c1Again);
    }

    @Test
    public void backwardSequenceGapDoesNotLinkTwoDifferentHashes() {
        SequenceLinker linker = new SequenceLinker();
        String c1 = linker.resolve("macA", 100, FP_RADIO_A, true, 0);
        // A lower/equal seq number is not a plausible "picks up where the last one left off"
        // continuation, so this must not be treated as the same device.
        String c2 = linker.resolve("macB", 100, FP_RADIO_A, true, 50);
        assertNotEquals(c1, c2);
    }

    @Test
    public void linkingWindowExpiresAfterAFewSeconds() {
        SequenceLinker linker = new SequenceLinker();
        String c1 = linker.resolve("macA", 100, FP_RADIO_A, true, 0);
        // 3.2 device-seconds later, longer than the linker's internal window.
        String c2 = linker.resolve("macB", 103, FP_RADIO_A, true, 3200); // would've linked if seen promptly
        assertNotEquals(c1, c2);
    }

    @Test
    public void matchingFingerprintLinksDespiteBorderlineSeqGap() {
        SequenceLinker linker = new SequenceLinker();
        String c1 = linker.resolve("macA", 100, FP_RADIO_A, true, 0);
        // Gap of 25 is beyond the old fingerprint-less threshold (20) but
        // within the wider one a matching fingerprint earns.
        String c2 = linker.resolve("macB", 125, FP_RADIO_A, true, 100);
        assertEquals(c1, c2);
    }

    @Test
    public void mismatchedFingerprintBlocksLinkThatSeqGapAloneWouldAllow() {
        SequenceLinker linker = new SequenceLinker();
        String c1 = linker.resolve("macA", 100, FP_RADIO_A, true, 0);
        // Gap of only 5 would clear any threshold on its own, but a
        // mismatched fingerprint has to block the link anyway -- this is
        // the regression case for two unrelated devices with adjacent
        // sequence numbers from overlapping airtime.
        String c2 = linker.resolve("macB", 105, FP_RADIO_B, true, 50);
        assertNotEquals(c1, c2);
    }

    @Test
    public void nonRandomizedMacIsNeverAvailableAsALinkTarget() {
        SequenceLinker linker = new SequenceLinker();
        String c1 = linker.resolve("macA", 100, FP_RADIO_A, false, 0); // real, non-randomized MAC
        // Perfect seq gap and matching fingerprint would link if macA were
        // searchable, but a non-randomized sighting is never stored as a
        // candidate in the first place.
        String c2 = linker.resolve("macB", 103, FP_RADIO_A, true, 100);
        assertEquals("macA", c1);
        assertEquals("macB", c2);
        assertNotEquals(c1, c2);
    }

    @Test
    public void nonRandomizedMacIsStillNotALinkTargetOnARepeatSighting() {
        SequenceLinker linker = new SequenceLinker();
        linker.resolve("macA", 100, FP_RADIO_A, false, 0); // first sighting, non-randomized
        linker.resolve("macA", 101, FP_RADIO_A, false, 50); // repeat sighting, still non-randomized
        // A later randomized probe with a seq gap and fingerprint that would
        // match macA's most recent sighting must still not link to it.
        String c3 = linker.resolve("macB", 104, FP_RADIO_A, true, 100);
        assertNotEquals("macA", c3);
        assertEquals("macB", c3);
    }

    @Test
    public void twoEquallyPlausibleCandidatesAreRefusedRatherThanGuessed() {
        SequenceLinker linker = new SequenceLinker();
        // Two different devices, same fingerprint (same phone model), same
        // sequence number as each other (a zero gap doesn't link them to
        // each other, so both establish their own separate identity).
        String c1 = linker.resolve("macA", 100, FP_RADIO_A, true, 0);
        String c2 = linker.resolve("macB", 100, FP_RADIO_A, true, 10);
        assertNotEquals(c1, c2);

        // A third raw hash lands with a forward gap of 10 from both: both
        // macA and macB are equally plausible. Picking either one would be
        // a guess, so this must refuse to link rather than pick the nearer
        // (there is no nearer, they're tied) or arbitrarily pick one.
        String c3 = linker.resolve("macC", 110, FP_RADIO_A, true, 20);
        assertNotEquals(c1, c3);
        assertNotEquals(c2, c3);
        assertEquals("macC", c3);
    }

    @Test
    public void deviceClockGoingBackwardsClearsTheLinkingWindowButNotPermanentIdentities() {
        SequenceLinker linker = new SequenceLinker();
        String c1 = linker.resolve("macA", 100, FP_RADIO_A, true, 50_000); // long-running session
        // Device rebooted: millis() resets to near zero.
        String c2 = linker.resolve("macB", 103, FP_RADIO_A, true, 10); // would've linked under the old clock
        assertNotEquals(c1, c2); // the stale recent-window entry must not be used

        // macA's own permanent identity must still survive the reboot.
        String c1Again = linker.resolve("macA", 999, FP_RADIO_B, true, 20);
        assertEquals(c1, c1Again);
    }
}
