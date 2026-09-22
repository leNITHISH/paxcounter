package com.nithish.paxcounter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class TtlCacheTest {

    @Test
    public void firstSightingOfAHashIsNew() {
        TtlCache cache = new TtlCache(10_000);
        assertTrue(cache.registerAndCheckNew("aaaa"));
    }

    @Test
    public void immediateRepeatWithinTtlIsNotNew() {
        TtlCache cache = new TtlCache(10_000);
        assertTrue(cache.registerAndCheckNew("aaaa"));
        assertFalse(cache.registerAndCheckNew("aaaa"));
    }

    @Test
    public void repeatAfterTtlExpiresCountsAsNewAgain() throws InterruptedException {
        TtlCache cache = new TtlCache(50);
        assertTrue(cache.registerAndCheckNew("aaaa"));
        Thread.sleep(120);
        assertTrue(cache.registerAndCheckNew("aaaa"));
    }

    @Test
    public void differentHashesAreIndependent() {
        TtlCache cache = new TtlCache(10_000);
        assertTrue(cache.registerAndCheckNew("aaaa"));
        assertTrue(cache.registerAndCheckNew("bbbb"));
        assertFalse(cache.registerAndCheckNew("aaaa"));
        assertFalse(cache.registerAndCheckNew("bbbb"));
    }

    @Test
    public void sizeTracksDistinctHashesSeen() {
        TtlCache cache = new TtlCache(10_000);
        cache.registerAndCheckNew("aaaa");
        cache.registerAndCheckNew("bbbb");
        cache.registerAndCheckNew("aaaa"); // repeat, shouldn't add a new entry
        assertEquals(2, cache.size());
    }

    @Test
    public void evictExpiredRemovesOnlyEntriesOlderThanThreeTimesTtl() throws InterruptedException {
        TtlCache cache = new TtlCache(50); // eviction threshold is 150ms
        cache.registerAndCheckNew("stale");
        Thread.sleep(200);
        cache.registerAndCheckNew("fresh");

        cache.evictExpired();

        assertEquals(1, cache.size());
        // "stale" is gone, so it's treated as never seen: a fresh registration is new.
        assertTrue(cache.registerAndCheckNew("stale"));
    }
}
