package com.nithish.paxcounter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

// Monte Carlo validation of SequenceLinker against synthetic probe streams
// with known ground truth, since a real controlled multi-device test isn't
// affordable right now. This is only meaningful if the generator stresses
// the mechanism instead of confirming it: every device here shares one IE
// fingerprint (same phone model) and sequence numbers are drawn uniformly
// at random, matching the collision-probability model the original
// critique used (~0.7% per unrelated candidate on a 12-bit sequence
// number), not the gap-1, single-device shape the one real hour of data
// happened to show.
public class LinkingSimulationTest {

    private static final int SEQ_MODULUS = 4096;
    private static final int WINDOW_MILLIS = 3000;
    private static final int TRIALS_PER_K = 100;

    private static final class Pair {
        long tp, fp, fn;
    }

    @Test
    public void precisionAndRecallAcrossConcurrentSameFingerprintDeviceCounts() {
        int[] kValues = {1, 5, 10, 30, 100};
        System.out.println("k\tprecision\trecall");

        double precisionAtK1 = -1;
        for (int k : kValues) {
            Pair totals = new Pair();
            Random rng = new Random(42 + k); // fixed seed per k, deterministic run-to-run
            for (int trial = 0; trial < TRIALS_PER_K; trial++) {
                runTrial(k, rng, totals);
            }

            double precision = totals.tp + totals.fp == 0 ? Double.NaN : (double) totals.tp / (totals.tp + totals.fp);
            double recall = totals.tp + totals.fn == 0 ? Double.NaN : (double) totals.tp / (totals.tp + totals.fn);
            System.out.printf("%d\t%.4f\t%.4f%n", k, precision, recall);

            if (k == 1) precisionAtK1 = precision;
            assertTrue(!Double.isNaN(precision) && !Double.isNaN(recall),
                    "k=" + k + " produced no positive or negative pairs; generator needs tuning");
        }

        // No distractor devices exist at k=1, so a false merge is structurally
        // impossible; this should be at or extremely close to 1.0.
        assertTrue(precisionAtK1 > 0.99, "precision at k=1 should be ~1.0, was " + precisionAtK1);
    }

    // One trial: k ground-truth devices, all sharing one IE fingerprint,
    // each rotating its MAC 1-3 times within one 3-second window, fed
    // through a single fresh SequenceLinker in capture-time order.
    private void runTrial(int k, Random rng, Pair totals) {
        SequenceLinker linker = new SequenceLinker();
        byte[] sharedFingerprint = {1, 2, 3, 4}; // "same phone model" for every device this trial

        Map<String, Integer> groundTruthDevice = new HashMap<>();
        List<long[]> events = new ArrayList<>(); // {deviceId, seq, timestamp} ordered by generation

        for (int deviceId = 0; deviceId < k; deviceId++) {
            int seq = rng.nextInt(SEQ_MODULUS); // uniform random start, matching the uncorrelated-seq model
            long timestamp = rng.nextInt(WINDOW_MILLIS);
            int rotations = 1 + rng.nextInt(3); // 1-3 raw hashes per device

            for (int r = 0; r < rotations; r++) {
                events.add(new long[] {deviceId, seq, timestamp});
                // Gap mostly within MAX_FORWARD_GAP (30), sometimes deliberately
                // beyond it, to measure recall lost at the boundary rather than
                // only ever exercising the easy gap-1 case real data showed.
                int gap = 1 + rng.nextInt(35);
                seq = (seq + gap) % SEQ_MODULUS;
                timestamp += 100 + rng.nextInt(700);
            }
        }

        events.sort((a, b) -> Long.compare(a[2], b[2])); // feed in capture-time order, as App.java would

        Map<String, String> predictedCanonical = new HashMap<>();
        int counter = 0;
        List<String> rawHashesInOrder = new ArrayList<>();
        for (long[] event : events) {
            String rawHash = "h" + (counter++);
            groundTruthDevice.put(rawHash, (int) event[0]);
            rawHashesInOrder.add(rawHash);
            String canonical = linker.resolve(rawHash, (int) event[1], sharedFingerprint, true, event[2]);
            predictedCanonical.put(rawHash, canonical);
        }

        for (int i = 0; i < rawHashesInOrder.size(); i++) {
            for (int j = i + 1; j < rawHashesInOrder.size(); j++) {
                String hi = rawHashesInOrder.get(i);
                String hj = rawHashesInOrder.get(j);
                boolean sameGroundTruth = groundTruthDevice.get(hi).equals(groundTruthDevice.get(hj));
                boolean sameCanonical = predictedCanonical.get(hi).equals(predictedCanonical.get(hj));
                if (sameGroundTruth && sameCanonical) totals.tp++;
                else if (!sameGroundTruth && sameCanonical) totals.fp++;
                else if (sameGroundTruth && !sameCanonical) totals.fn++;
            }
        }
    }

    @Test
    public void replayBurstDoesNotArtificiallyLinkSightingsMinutesApartOnDevice() {
        SequenceLinker linker = new SequenceLinker();
        byte[] fp = {1, 2, 3, 4};

        // Two sightings whose DEVICE timestamps are 70 seconds apart (the
        // ring buffer replaying a backlog after a USB reconnect), submitted
        // to resolve() back-to-back with no real elapsed wall time between
        // calls. If the fix works, the device-time gap governs, not how
        // fast these two calls were actually made.
        String c1 = linker.resolve("macA", 100, fp, true, 0);
        String c2 = linker.resolve("macB", 105, fp, true, 70_000);
        assertNotEquals(c1, c2, "a 70s device-time gap must not link, regardless of host processing speed");
    }

    @Test
    public void sameDeviceTimeGapLinksRegardlessOfWhenTheCallsActuallyHappen() {
        SequenceLinker linker = new SequenceLinker();
        byte[] fp = {1, 2, 3, 4};

        // Same small device-time gap as countless real captures this session,
        // just submitted long after the linker was constructed (simulating
        // "these are old buffered events, not freshly captured ones").
        String c1 = linker.resolve("macA", 100, fp, true, 70_000);
        String c2 = linker.resolve("macB", 105, fp, true, 70_050);
        assertEquals(c1, c2, "a 50ms device-time gap should link, regardless of wall-clock context");
    }
}
