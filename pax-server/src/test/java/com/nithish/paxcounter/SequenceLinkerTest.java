package com.nithish.paxcounter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

public class SequenceLinkerTest {

    @Test
    public void firstSightingResolvesToItsOwnHash() {
        SequenceLinker linker = new SequenceLinker();
        assertEquals("macA", linker.resolve("macA", 100));
    }

    @Test
    public void rotatingMacWithCloseSequenceNumbersLinksToOneCanonicalId() {
        SequenceLinker linker = new SequenceLinker();
        String c1 = linker.resolve("macA", 100);
        String c2 = linker.resolve("macB", 103); // same radio, MAC rotated, seq kept incrementing
        String c3 = linker.resolve("macC", 107);
        assertEquals(c1, c2);
        assertEquals(c2, c3);
    }

    @Test
    public void unrelatedDeviceWithFarSequenceNumberIsNotLinked() {
        SequenceLinker linker = new SequenceLinker();
        String c1 = linker.resolve("macA", 100);
        String c2 = linker.resolve("macD", 3000); // far outside the forward-gap window
        assertNotEquals(c1, c2);
    }

    @Test
    public void repeatOfTheExactSameHashResolvesToItsOwnCanonicalId() {
        SequenceLinker linker = new SequenceLinker();
        String c1 = linker.resolve("macA", 100);
        String c1Again = linker.resolve("macA", 250); // seq jump irrelevant, exact hash match wins
        assertEquals(c1, c1Again);
    }

    @Test
    public void backwardSequenceGapDoesNotLinkTwoDifferentHashes() {
        SequenceLinker linker = new SequenceLinker();
        String c1 = linker.resolve("macA", 100);
        // A lower/equal seq number is not a plausible "picks up where the last one left off"
        // continuation, so this must not be treated as the same device.
        String c2 = linker.resolve("macB", 100);
        assertNotEquals(c1, c2);
    }

    @Test
    public void linkingWindowExpiresAfterAFewSeconds() throws InterruptedException {
        SequenceLinker linker = new SequenceLinker();
        String c1 = linker.resolve("macA", 100);
        Thread.sleep(3200); // longer than the linker's internal window
        String c2 = linker.resolve("macB", 103); // would've linked if seen promptly
        assertNotEquals(c1, c2);
    }
}
