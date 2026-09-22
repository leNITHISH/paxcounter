-- Run with: duckdb -init /dev/null -c ".read analytics.sql" (from pax-server/)
-- or interactively: duckdb, then .read analytics.sql

CREATE OR REPLACE VIEW metrics AS
    SELECT * FROM read_csv_auto('metrics.csv');

CREATE OR REPLACE VIEW probes AS
    SELECT * FROM read_csv_auto('raw_probes.csv');

-- Peak concurrent devices and overall duplicate-filter rate, straight from
-- the periodic rollups MetricsLogger writes every 10s.
SELECT
    max(unique_devices) AS peak_unique_devices,
    max(total_probes) AS total_probes_seen,
    round(avg(filtered_pct), 2) AS avg_duplicate_filter_pct
FROM metrics;

-- Why canonical_hash exists: comparing distinct raw MAC hashes against
-- distinct canonical (sequence-linked) ids shows how much MAC randomization
-- would have inflated the count without SequenceLinker.
SELECT
    count(DISTINCT mac_hash) AS distinct_raw_hashes,
    count(DISTINCT canonical_hash) AS distinct_canonical_devices,
    count(DISTINCT mac_hash) - count(DISTINCT canonical_hash) AS hashes_merged_by_linking
FROM probes;

-- Signal strength distribution in 10dBm buckets, a rough proxy for how close
-- detected devices were to the sniffer.
SELECT
    CAST(floor(rssi / 10.0) * 10 AS INTEGER) AS rssi_bucket,
    count(*) AS sightings
FROM probes
GROUP BY rssi_bucket
ORDER BY rssi_bucket DESC;

-- Channel occupancy: which of the 1-11 hopped channels saw the most traffic.
SELECT
    channel,
    count(*) AS sightings,
    count(DISTINCT canonical_hash) AS distinct_devices
FROM probes
GROUP BY channel
ORDER BY channel;

-- Sightings per minute, a coarse activity-over-time trend line.
SELECT
    date_trunc('minute', timestamp) AS minute,
    count(*) AS sightings,
    count(DISTINCT canonical_hash) AS distinct_devices
FROM probes
GROUP BY minute
ORDER BY minute;
