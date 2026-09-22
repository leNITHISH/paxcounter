# PaxCounter (Java)

A Wi-Fi crowd-density counter: an ESP32 in 802.11 promiscuous mode hops across channels 1-11 capturing Wi-Fi probe requests, hashes MAC addresses on-device for privacy, and streams them over serial to a Java service. The service links probes across MAC randomization using the 802.11 sequence number, deduplicates sightings with a TTL cache, logs metrics to CSV, and results are queried with DuckDB for density and trend analysis.

## Architecture

ESP32 (promiscuous mode, channel hopping 1-11)
   |  UART @ 115200 baud -- hashed_mac,rssi,channel,seq
   v
Java Service
   |  1. SerialReader     -- raw byte polling via jSerialComm, auto-reconnects on disconnect
   |  2. SequenceLinker    -- links MACs rotated by the same radio via 802.11 sequence number
   |  3. TtlCache          -- dedupes sightings within a 10s window
   |  4. MetricsLogger     -- appends counts/stats to CSV
   |  5. RawEventLogger    -- appends every individual sighting to CSV
   v
metrics.csv / raw_probes.csv -> DuckDB queries

## Stack
ESP32-C3 (Arduino/C++), Java (jSerialComm), DuckDB

## Run
1. Flash the ESP32: `./flash.sh` (defaults to /dev/ttyACM0, pass a different port as the first arg)
2. Run the server: `./run.sh`
3. Query results: duckdb, then CREATE TABLE probes AS SELECT * FROM read_csv_auto('pax-server/metrics.csv');

## Flashing notes
- Board: ESP32-C3 Dev Module (FQBN esp32:esp32:esp32c3)
- `flash.sh` builds with `CDCOnBoot=cdc` so USB CDC On Boot is enabled. This exposes early boot/panic output over the USB serial port instead of swallowing it, and in testing also resolved intermittent USB-Serial/JTAG disconnects while promiscuous mode was active. Flashing from the Arduino IDE instead needs the same setting done manually: Tools -> USB CDC On Boot -> Enabled.

## Design decisions
- TTL cache (10s window): mobile OSes randomize MAC addresses periodically as a privacy measure. Without deduplication, this randomization would be misread as new devices, wildly overcounting. A short-lived cache (ConcurrentHashMap<macHash, lastSeenTimestamp>) filters this noise while still counting genuinely new devices.
- Sequence-number linking (SequenceLinker): the TTL cache only catches repeats of the exact same hash, but a phone can rotate its MAC mid-session, well within seconds, which the TTL cache alone can't see. The 802.11 sequence-control field increments from the radio's own hardware counter and typically isn't reset when the MAC rotates, so a freshly-seen hash whose sequence number picks up within a small forward window of a recent sighting gets linked to that sighting's canonical device id instead of counted as brand new.
- DuckDB over Postgres/MySQL: this workload is read-heavy analytical querying (aggregates, trends) over a single growing log, not high-frequency transactional writes from concurrent users. DuckDB's columnar engine is fast for GROUP BY/COUNT DISTINCT-style queries and needs no running server, a better fit than a full OLTP database for this use case.
- Raw byte polling over BufferedReader.readLine(): encountered inconsistent behavior with jSerialComm's blocking read mode on Linux ttyACM devices; switched to semi-blocking readBytes() with manual line-splitting for reliable delivery.
- Auto-detected serial port instead of a hardcoded path: scans all available ports via jSerialComm and matches on the ESP32's USB descriptor (Espressif/JTAG/CDC), so the same code runs unmodified across Linux, macOS, and Windows without the user needing to know the device path in advance.
- Automatic serial reconnect: if the ESP32 drops off USB, a reset, a disconnect, a flaky USB-Serial/JTAG link, the service used to crash or spin. It now closes the stale port and polls every 2s until the device reappears, so a transient drop doesn't require restarting the whole process.

## Known limitations / future work
- No offline buffering: data seen during a USB drop is lost even though the service now reconnects automatically once the ESP32 is back. An SD card write-buffer with replay-on-reconnect would close this gap.
- No MQTT decoupling yet: ingestion and analytics currently run in the same process. Decoupling via MQTT (ESP32/host -> broker -> subscriber) would allow multiple downstream consumers (dashboard, alerting, storage) without coupling them to the ingestion service.
- Modern probe request suppression: newer iOS/Android versions send fewer probe requests by default for privacy, reducing detection rate for idle devices with screens off.
- Sequence-number linking is heuristic: the forward-gap threshold and time window are reasonable defaults but not validated against a large real-world dataset; long silences or heavy nearby traffic could misclassify devices in either direction.

## Results (test run, 18 Sep 2026)
- 178 total probe requests captured
- 40 unique devices identified
- 75.68% average duplicate-filter rate (MAC randomization noise caught by TTL cache)
- 6-minute live test, single ESP32-C3, fixed on channel 1

Note: this benchmark predates channel hopping and sequence-number linking, so it reflects single-channel, exact-hash-only detection and is not representative of current coverage or accuracy.

## Resume bullets
- Designed and built a Wi-Fi crowd-density counter in Java, implementing a custom TTL-based deduplication cache (ConcurrentHashMap, 10s window) that filtered 75.7% of MAC-randomization noise across 178 captured probe requests, correctly identifying 40 unique devices in a live 6-minute test.
- Logged real-time metrics to CSV and queried session data with DuckDB to surface peak device concurrency and detection trends.
- Implemented automatic serial port detection via USB descriptor matching, making the ingestion service portable across Linux, macOS, and Windows with no hardcoded configuration.
- Designed a heuristic linking layer using 802.11 sequence numbers to associate probe requests across MAC address rotations, improving unique-device accuracy beyond what exact-hash deduplication alone can provide.
- Diagnosed and fixed an out-of-bounds memory read in the ESP32 firmware's 802.11 frame parsing that was crashing the chip under real traffic, and added automatic serial reconnect handling so the ingestion service recovers from USB/device drops without manual intervention.
