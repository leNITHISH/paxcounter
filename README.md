# PaxCounter (Java)

A Wi-Fi crowd-density counter: an ESP32 in 802.11 promiscuous mode captures Wi-Fi probe requests, hashes MAC addresses on-device for privacy, and streams them over serial to a Java service. The service deduplicates sightings with a TTL cache to filter MAC randomization noise, logs metrics to CSV, and results are queried with DuckDB for density and trend analysis.

## Architecture

ESP32 (promiscuous mode)
   |  UART @ 115200 baud -- hashed_mac,rssi
   v
Java Service
   |  1. SerialReader   -- raw byte polling via jSerialComm
   |  2. TtlCache        -- dedupes sightings within a 10s window
   |  3. MetricsLogger   -- appends counts/stats to CSV
   v
metrics.csv -> DuckDB queries

## Stack
ESP32 (Arduino/C++), Java (jSerialComm), DuckDB

## Run
1. Flash paxcounter.ino to ESP32
2. mvn compile exec:java -Dexec.mainClass="com.nithish.paxcounter.App"
3. Query results: duckdb, then CREATE TABLE probes AS SELECT * FROM read_csv_auto('metrics.csv');

## Design decisions
- TTL cache (10s window): mobile OSes randomize MAC addresses periodically as a privacy measure. Without deduplication, this randomization would be misread as new devices, wildly overcounting. A short-lived cache (ConcurrentHashMap<macHash, lastSeenTimestamp>) filters this noise while still counting genuinely new devices.
- DuckDB over Postgres/MySQL: this workload is read-heavy analytical querying (aggregates, trends) over a single growing log, not high-frequency transactional writes from concurrent users. DuckDB's columnar engine is fast for GROUP BY/COUNT DISTINCT-style queries and needs no running server -- a better fit than a full OLTP database for this use case.
- Raw byte polling over BufferedReader.readLine(): encountered inconsistent behavior with jSerialComm's blocking read mode on Linux ttyACM devices; switched to semi-blocking readBytes() with manual line-splitting for reliable delivery.

## Results (test run, 18 Sep 2026)
- 178 total probe requests captured
- 40 unique devices identified
- 75.68% average duplicate-filter rate (MAC randomization noise caught by TTL cache)
- 6-minute live test, single ESP32-C3, fixed on channel 1

## Known limitations / future work
- Single-channel listening: currently fixed on channel 1; real deployments would need channel hopping (1/6/11, the non-overlapping 2.4GHz channels) for full coverage.
- No offline buffering: if the serial/USB connection drops, in-flight data is lost. An SD card write-buffer with replay-on-reconnect would add resilience.
- No MQTT decoupling yet: ingestion and analytics currently run in the same process. Decoupling via MQTT (ESP32/host -> broker -> subscriber) would allow multiple downstream consumers (dashboard, alerting, storage) without coupling them to the ingestion service.
- Modern probe request suppression: newer iOS/Android versions send fewer probe requests by default for privacy, reducing detection rate for idle devices with screens off.

## Resume bullets
- Designed and built a Wi-Fi crowd-density counter in Java, implementing a custom TTL-based deduplication cache (ConcurrentHashMap, 10s window) that filtered 75.7% of MAC-randomization noise across 178 captured probe requests, correctly identifying 40 unique devices in a live 6-minute test.
- Logged real-time metrics to CSV and queried session data with DuckDB to surface peak device concurrency and detection trends.
