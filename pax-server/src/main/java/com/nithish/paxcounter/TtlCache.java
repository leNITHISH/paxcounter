package com.nithish.paxcounter;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

// Timestamps passed in here are the ESP32's own millis() at the moment a
// frame was captured, not host arrival time. Arrival time is wrong for this:
// after a USB reconnect the on-device ring buffer replays its whole backlog
// in one burst, so sightings captured minutes apart could otherwise all land
// inside the same few milliseconds of host-received time.
public class TtlCache {
    private final ConcurrentHashMap<String, Long> seen = new ConcurrentHashMap<>();
    private final long ttlMillis;
    // Device millis() resets to near zero on a reboot. A newly-seen
    // timestamp smaller than the latest one we've recorded means that
    // happened, not that time ran backwards, so the old entries are no
    // longer comparable to anything new and get dropped wholesale.
    private final AtomicLong lastDeviceTimestamp = new AtomicLong(-1);

    public TtlCache(long ttlMillis) {
        this.ttlMillis = ttlMillis;
    }

    // returns true if this is a "new" sighting (not a duplicate within TTL)
    public boolean registerAndCheckNew(String macHash, long deviceTimestamp) {
        if (deviceTimestamp < lastDeviceTimestamp.get()) {
            seen.clear();
        }
        lastDeviceTimestamp.set(Math.max(lastDeviceTimestamp.get(), deviceTimestamp));

        Long last = seen.get(macHash);
        seen.put(macHash, deviceTimestamp);
        return (last == null) || (deviceTimestamp - last > ttlMillis);
    }

    public int size() {
        return seen.size();
    }

    public void evictExpired() {
        long now = lastDeviceTimestamp.get();
        if (now < 0) return; // nothing registered yet
        seen.entrySet().removeIf(e -> now - e.getValue() > ttlMillis * 3);
    }
}
