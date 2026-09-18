package com.nithish.paxcounter;

import java.util.concurrent.ConcurrentHashMap;

public class TtlCache {
    private final ConcurrentHashMap<String, Long> seen = new ConcurrentHashMap<>();
    private final long ttlMillis;

    public TtlCache(long ttlMillis) {
        this.ttlMillis = ttlMillis;
    }

    // returns true if this is a "new" sighting (not a duplicate within TTL)
    public boolean registerAndCheckNew(String macHash) {
        long now = System.currentTimeMillis();
        Long last = seen.get(macHash);
        seen.put(macHash, now);
        return (last == null) || (now - last > ttlMillis);
    }

    public int size() {
        return seen.size();
    }

    public void evictExpired() {
        long now = System.currentTimeMillis();
        seen.entrySet().removeIf(e -> now - e.getValue() > ttlMillis * 3);
    }
}
