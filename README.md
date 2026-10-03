# PaxCounter

A Wi-Fi crowd-density counter: an ESP32 in 802.11 promiscuous mode hops across channels 1-11 capturing Wi-Fi probe requests, hashes MAC addresses on-device for privacy, and streams them over serial to a Java service. The service links probes across MAC randomization using the 802.11 sequence number, deduplicates sightings with a TTL cache, logs metrics to CSV, and results are queried with DuckDB for density and trend analysis.

## Architecture

```
ESP32 (promiscuous mode, channel hopping 1-11)
   |  RAM ring buffer: queues sightings while USB is disconnected, drains once reconnected
   |  UART @ 115200 baud: hashed_mac,rssi,channel,seq,ieFingerprint,isRandomized
   v
Java Service
   |  1. SerialReader: raw byte polling via jSerialComm, auto-reconnects on disconnect
   |  2. SequenceLinker: links MACs rotated by the same radio using sequence number and IE fingerprint
   |  3. TtlCache: dedupes sightings within a 10s window
   |  4. MetricsLogger: appends counts/stats to CSV
   |  5. RawEventLogger: appends every individual sighting to CSV
   v
metrics.csv / raw_probes.csv feed DuckDB queries
```

## Stack
ESP32-C3 (Arduino/C++), Java (jSerialComm), DuckDB

## Run
1. Flash the ESP32: `./flash.sh` (defaults to /dev/ttyACM0, pass a different port as the first arg)
2. Run the server: `./run.sh`
3. Query results: `cd pax-server && duckdb -c ".read analytics.sql"` (peak concurrent devices, RSSI/channel breakdowns, visit/dwell-time reconstruction, overlap-based occupancy over time, sequence-linking effectiveness over time)

## Testing
- `cd pax-server && mvn test` runs the unit tests. `TtlCacheTest` and `SequenceLinkerTest` cover the dedup and linking layers directly: TTL expiry, sequence-number plus IE-fingerprint linking across rotated MACs, fingerprint-mismatch rejection, non-randomized MACs never becoming link targets, window expiry. None of it needs real hardware.
- `cd paxcounter.ino && g++ -std=c++17 -fsanitize=address,undefined -o ie_fingerprint_test ie_fingerprint_test.cpp && ./ie_fingerprint_test` runs a host-side test of the IE-fingerprint TLV walk under AddressSanitizer. The test file shares the walk's logic verbatim with the firmware rather than reimplementing it, so there's no chance of the two drifting apart. It covers determinism, distinguishing different IE sets, ignoring non-allowlisted tags and content, and a truncated or malformed TLV chain that doesn't overrun the buffer.

## Flashing notes
- First time only: `cp paxcounter.ino/secrets.h.example paxcounter.ino/secrets.h`, then replace the placeholder with your own generated pepper (`python3 -c "import secrets; print(secrets.token_hex(32))"`). `secrets.h` is gitignored; the sketch won't compile without it.
- Board: ESP32-C3 Dev Module (FQBN esp32:esp32:esp32c3).
- `flash.sh` builds with `CDCOnBoot=cdc` so USB CDC On Boot is enabled. This exposes early boot and panic output over the USB serial port instead of swallowing it, and in testing it also resolved intermittent USB-Serial/JTAG disconnects while promiscuous mode was active. Flashing from the Arduino IDE instead needs the same setting done manually: Tools -> USB CDC On Boot -> Enabled.

## Design decisions
- HMAC-SHA256 with a fixed pepper instead of plain SHA256. Captured MACs are hashed and never stored raw, but an unsalted hash of a real, non-randomized MAC is brute-forceable by anyone who gets only the CSV output and guesses a vendor OUI (2^24 candidates per OUI, trivial to search). Keying the hash with a secret pepper (`paxcounter.ino/secrets.h`, gitignored, see `secrets.h.example`) defeats that specific attack. It does not protect against physical or firmware access to the device, since the pepper is readable from flash, and it does not hide anything from an RF-capable attacker, since probe requests are unencrypted management frames with the real MAC already in the clear over the air. This is privacy hardening against a CSV-only attacker, not general security.
- TTL cache, 10-second window. Mobile OSes randomize MAC addresses periodically as a privacy measure. Without deduplication, that randomization would be misread as new devices, wildly overcounting. A short-lived cache (`ConcurrentHashMap<macHash, lastSeenTimestamp>`) filters this noise while still counting genuinely new devices.
- Sequence-number linking, corroborated by an IE fingerprint (`SequenceLinker`). The TTL cache only catches repeats of the exact same hash, but a phone can rotate its MAC mid-session, well within seconds, and the TTL cache alone can't see that. The 802.11 sequence-control field increments from the radio's own hardware counter and typically isn't reset when the MAC rotates, so a freshly-seen hash whose sequence number picks up within a small forward window of a recent sighting becomes a candidate link. Two more signals sharpen that check. The MAC's locally administered bit (`srcMac[0] & 0x02`) flags whether it's actually randomized at all; a real burned-in MAC never rotates, so it's never stored as a link target in the first place. A small fingerprint derived from the probe request's IE tags (Supported Rates, HT/VHT Capabilities, Extended Capabilities, Vendor Specific, tag IDs and lengths only, not content) reflects the radio's chipset and driver rather than its MAC, so it should stay stable across a rotation. A fingerprint match earns a wider sequence-gap tolerance, 30 instead of 20; a mismatch blocks a link that sequence-gap proximity alone would have allowed, which is the main defense against the known false-positive mode: two unrelated devices with adjacent sequence numbers from overlapping airtime. This has been confirmed against a real, live MAC rotation (toggling Wi-Fi mid-capture), not just synthetic tests.
- The IE fingerprint is tag-and-length only, never content. Fingerprinting what a device can do, its capability IEs, instead of what network it's looking for, such as a directed probe's SSID list, keeps this signal from leaking more about the user than the hashing scheme's own privacy goal already allows. It's also intentionally non-cryptographic (FNV-1a, not SHA-256): it's a matching and stability signal, not a secret, so spending a hash context on it would be unnecessary weight on-device.
- DuckDB over Postgres or MySQL. This workload is read-heavy analytical querying, aggregates and trends, over a single growing log, not high-frequency transactional writes from concurrent users. DuckDB's columnar engine is fast for GROUP BY and COUNT DISTINCT style queries and needs no running server, a better fit than a full OLTP database here.
- Raw byte polling over `BufferedReader.readLine()`. jSerialComm's blocking read mode behaved inconsistently on Linux ttyACM devices, so the code switched to semi-blocking `readBytes()` with manual line-splitting for reliable delivery.
- Auto-detected serial port instead of a hardcoded path. The code scans all available ports via jSerialComm and matches on the ESP32's USB descriptor (Espressif, JTAG, CDC), so it runs unmodified across Linux, macOS, and Windows without the user needing to know the device path in advance.
- Automatic serial reconnect. If the ESP32 drops off USB, whether from a reset, a disconnect, or a flaky USB-Serial/JTAG link, the service used to crash or spin. Now it closes the stale port and polls every 2 seconds until the device reappears, so a transient drop doesn't require restarting the whole process.
- An on-device ring buffer instead of printing straight from the promiscuous callback. Probe sightings are queued into a fixed-size RAM ring buffer and only drained over serial once `Serial.isConnected()` is true, so a USB comms drop (the chip stays powered, the link just blips) no longer silently loses whatever was captured during the gap. This doesn't survive an actual power loss, since unplugging the board's only USB cable cuts power too and wipes RAM; testing it for real means powering the board from a separate source and only pulling the data connection.
- Visit reconstruction via a 2-minute gap threshold. Raw sightings alone don't answer how long a device actually lingered. `analytics.sql` groups each device's sightings, by `canonical_hash`, into contiguous "visits" with a gaps-and-islands window-function query: `LAG` to find the previous sighting, a new visit whenever the gap exceeds 2 minutes, a running `SUM` to assign visit ids. Two minutes is deliberately larger than the TtlCache's 10-second window and SequenceLinker's roughly 3-second window, both of which filter noise within a visit rather than detect a real departure, and short enough to still separate genuinely distinct visits while tolerating normal idle-phone probe gaps.
- Occupancy over time via visit overlap, not raw per-minute sightings. A naive count of distinct hashes with a sighting in a given minute misses a device mid-visit that just hasn't probed in the last minute or so, undercounting a long dwell. Instead, each minute bucket is treated as a `[bucket_start, bucket_start + 1 minute)` interval and checked for overlap against each visit's `[visit_start, visit_end]` range, so a device counts as present in every bucket its visit actually spans. This was verified against a synthetic case where a device has no raw sighting in the middle minute of its visit but still correctly shows as present there, unlike the naive count.

## Known limitations / future work
- Offline buffering only covers comms drops, not power loss. The on-device ring buffer queues sightings through a USB link blip and replays them once reconnected, but it lives in RAM, so it can't survive the board actually losing power, as happens when unplugging its only USB cable cuts power and data at once. Powering the board separately and only disconnecting the data line avoids that; true survival across a full power cycle would need flash or SD storage instead.
- No MQTT decoupling yet. Ingestion and analytics currently run in the same process. Decoupling via MQTT, ESP32 or host to broker to subscriber, would let multiple downstream consumers (dashboard, alerting, storage) read the stream without coupling them to the ingestion service.
- Modern probe request suppression. Newer iOS and Android versions send fewer probe requests by default for privacy, reducing detection rate for idle devices with screens off.
- Connected devices go quiet. Probe requests are for network discovery, so once a device associates with an access point it largely stops sending them. This tool mostly measures devices actively searching for a network, just arrived, screen freshly woken, Wi-Fi picker open, not every device present in the room. That's a real gap between the "crowd-density counter" framing and what actually gets counted.
- Sequence-number plus IE-fingerprint linking is still heuristic. The forward-gap thresholds and time window are reasonable defaults, corroborated by a fingerprint match or mismatch rather than trusted alone, and confirmed correct on one real captured MAC rotation, but that's one data point, not a validated large real-world dataset. Long silences, heavy nearby traffic, or two devices that happen to share both a close sequence number and an identical capability fingerprint could still misclassify in either direction.

## Results (test run, 18 Sep 2026)
- 178 total probe requests captured
- 40 unique devices identified
- 75.68% average duplicate-filter rate (MAC randomization noise caught by TTL cache)
- 6-minute live test, single ESP32-C3, fixed on channel 1

This benchmark predates channel hopping and sequence-number linking, so it reflects single-channel, exact-hash-only detection and isn't representative of current coverage or accuracy.

## Resume bullets
- Designed and built a Wi-Fi crowd-density counter in Java, implementing a custom TTL-based deduplication cache (ConcurrentHashMap, 10s window) that filtered 75.7% of MAC-randomization noise across 178 captured probe requests, correctly identifying 40 unique devices in a live 6-minute test.
- Logged real-time metrics to CSV and queried session data with DuckDB to surface peak device concurrency and detection trends.
- Implemented automatic serial port detection via USB descriptor matching, making the ingestion service portable across Linux, macOS, and Windows with no hardcoded configuration.
- Designed a heuristic linking layer using 802.11 sequence numbers to associate probe requests across MAC address rotations, improving unique-device accuracy beyond what exact-hash deduplication alone can provide.
- Diagnosed and fixed an out-of-bounds memory read in the ESP32 firmware's 802.11 frame parsing that was crashing the chip under real traffic, and added automatic serial reconnect handling so the ingestion service recovers from USB and device drops without manual intervention.
- Built a single-producer, single-consumer RAM ring buffer on the ESP32 to queue captured sightings through USB comms drops, verified correct (FIFO order, wraparound, overflow handling) with a standalone unit test isolated from hardware before flashing.
- Wrote gaps-and-islands DuckDB window-function queries to reconstruct per-device visits and dwell time from raw sighting logs, validated against both real captured data and a hand-built synthetic dataset crafted to exercise the gap-splitting logic directly.
- Researched real-world MAC-randomization countermeasures, including IE fingerprinting and the locally administered MAC bit, and implemented an IE-tag/length fingerprint on the ESP32 that corroborates the existing sequence-number heuristic, both catching more rotations and rejecting false-positive links. Extracted the TLV-parsing logic into a shared, host-testable function verified under AddressSanitizer before it touched hardware, then confirmed it correctly linked a real live MAC rotation captured during testing.
