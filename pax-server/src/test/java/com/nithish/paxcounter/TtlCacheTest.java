package com.nithish.paxcounter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class TtlCacheTest {

    @Test
    public void firstSightingOfAHashIsNew() {
        TtlCache cache = new TtlCache(10_000);
        assertTrue(cache.registerAndCheckNew("aaaa", 0));
    }

    @Test
    public void immediateRepeatWithinTtlIsNotNew() {
        TtlCache cache = new TtlCache(10_000);
        assertTrue(cache.registerAndCheckNew("aaaa", 0));
        assertFalse(cache.registerAndCheckNew("aaaa", 100));
    }

    @Test
    public void repeatAfterTtlExpiresCountsAsNewAgain() {
        TtlCache cache = new TtlCache(50);
        assertTrue(cache.registerAndCheckNew("aaaa", 0));
        assertTrue(cache.registerAndCheckNew("aaaa", 120));
    }

    @Test
    public void differentHashesAreIndependent() {
        TtlCache cache = new TtlCache(10_000);
        assertTrue(cache.registerAndCheckNew("aaaa", 0));
        assertTrue(cache.registerAndCheckNew("bbbb", 0));
        assertFalse(cache.registerAndCheckNew("aaaa", 10));
        assertFalse(cache.registerAndCheckNew("bbbb", 10));
    }

    @Test
    public void sizeTracksDistinctHashesSeen() {
        TtlCache cache = new TtlCache(10_000);
        cache.registerAndCheckNew("aaaa", 0);
        cache.registerAndCheckNew("bbbb", 0);
        cache.registerAndCheckNew("aaaa", 10); // repeat, shouldn't add a new entry
        assertEquals(2, cache.size());
    }

    @Test
    public void evictExpiredRemovesOnlyEntriesOlderThanThreeTimesTtl() {
        TtlCache cache = new TtlCache(50); // eviction threshold is 150ms
        cache.registerAndCheckNew("stale", 0);
        cache.registerAndCheckNew("fresh", 200);

        cache.evictExpired();

        assertEquals(1, cache.size());
        // "stale" is gone, so it's treated as never seen: a fresh registration is new.
        assertTrue(cache.registerAndCheckNew("stale", 200));
    }

    @Test
    public void deviceClockGoingBackwardsMeansARebootNotTimeTravel() {
        TtlCache cache = new TtlCache(10_000);
        cache.registerAndCheckNew("aaaa", 50_000); // long-running session
        assertEquals(1, cache.size());

        // Device rebooted: millis() resets to near zero. The old entry is no
        // longer comparable to the new clock, so it must be dropped, not
        // kept around looking "fresh" relative to a clock that no longer exists.
        assertTrue(cache.registerAndCheckNew("aaaa", 10));
        assertEquals(1, cache.size());
    }
}
