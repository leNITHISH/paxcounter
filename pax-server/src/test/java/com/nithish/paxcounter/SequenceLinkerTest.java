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
        assertEquals("macA", linker.resolve("macA", 100, FP_RADIO_A, true));
    }

    @Test
    public void rotatingMacWithCloseSequenceNumbersLinksToOneCanonicalId() {
        SequenceLinker linker = new SequenceLinker();
        String c1 = linker.resolve("macA", 100, FP_RADIO_A, true);
        String c2 = linker.resolve("macB", 103, FP_RADIO_A, true); // same radio, MAC rotated, seq kept incrementing
        String c3 = linker.resolve("macC", 107, FP_RADIO_A, true);
        assertEquals(c1, c2);
        assertEquals(c2, c3);
    }

    @Test
    public void unrelatedDeviceWithFarSequenceNumberIsNotLinked() {
        SequenceLinker linker = new SequenceLinker();
        String c1 = linker.resolve("macA", 100, FP_RADIO_A, true);
        String c2 = linker.resolve("macD", 3000, FP_RADIO_A, true); // far outside the forward-gap window
        assertNotEquals(c1, c2);
    }

    @Test
    public void repeatOfTheExactSameHashResolvesToItsOwnCanonicalId() {
        SequenceLinker linker = new SequenceLinker();
        String c1 = linker.resolve("macA", 100, FP_RADIO_A, true);
        String c1Again = linker.resolve("macA", 250, FP_RADIO_A, true); // seq jump irrelevant, exact hash match wins
        assertEquals(c1, c1Again);
    }

    @Test
    public void backwardSequenceGapDoesNotLinkTwoDifferentHashes() {
        SequenceLinker linker = new SequenceLinker();
        String c1 = linker.resolve("macA", 100, FP_RADIO_A, true);
        // A lower/equal seq number is not a plausible "picks up where the last one left off"
        // continuation, so this must not be treated as the same device.
        String c2 = linker.resolve("macB", 100, FP_RADIO_A, true);
        assertNotEquals(c1, c2);
    }

    @Test
    public void linkingWindowExpiresAfterAFewSeconds() throws InterruptedException {
        SequenceLinker linker = new SequenceLinker();
        String c1 = linker.resolve("macA", 100, FP_RADIO_A, true);
        Thread.sleep(3200); // longer than the linker's internal window
        String c2 = linker.resolve("macB", 103, FP_RADIO_A, true); // would've linked if seen promptly
        assertNotEquals(c1, c2);
    }

    @Test
    public void matchingFingerprintLinksDespiteBorderlineSeqGap() {
        SequenceLinker linker = new SequenceLinker();
        String c1 = linker.resolve("macA", 100, FP_RADIO_A, true);
        // Gap of 25 is beyond the old fingerprint-less threshold (20) but
        // within the wider one a matching fingerprint earns.
        String c2 = linker.resolve("macB", 125, FP_RADIO_A, true);
        assertEquals(c1, c2);
    }

    @Test
    public void mismatchedFingerprintBlocksLinkThatSeqGapAloneWouldAllow() {
        SequenceLinker linker = new SequenceLinker();
        String c1 = linker.resolve("macA", 100, FP_RADIO_A, true);
        // Gap of only 5 would clear any threshold on its own, but a
        // mismatched fingerprint has to block the link anyway -- this is
        // the regression case for two unrelated devices with adjacent
        // sequence numbers from overlapping airtime.
        String c2 = linker.resolve("macB", 105, FP_RADIO_B, true);
        assertNotEquals(c1, c2);
    }

    @Test
    public void nonRandomizedMacIsNeverAvailableAsALinkTarget() {
        SequenceLinker linker = new SequenceLinker();
        String c1 = linker.resolve("macA", 100, FP_RADIO_A, false); // real, non-randomized MAC
        // Perfect seq gap and matching fingerprint would link if macA were
        // searchable, but a non-randomized sighting is never stored as a
        // candidate in the first place.
        String c2 = linker.resolve("macB", 103, FP_RADIO_A, true);
        assertEquals("macA", c1);
        assertEquals("macB", c2);
        assertNotEquals(c1, c2);
    }
}
