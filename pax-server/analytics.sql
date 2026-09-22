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

-- Visit reconstruction: group each device's raw sightings into contiguous
-- "visits" using canonical_hash (survives MAC rotation via SequenceLinker).
-- A gap of more than 2 minutes between consecutive sightings from the same
-- device means it left and came back (or never came back) -- a new visit.
-- 2 minutes is chosen deliberately larger than the 10s TtlCache dedup window
-- and the ~3s SequenceLinker window, both of which are about filtering noise
-- within a single visit, not about detecting a real departure; it's short
-- enough to still separate genuinely distinct visits, long enough to
-- tolerate normal gaps between probe requests from an idle device.
-- Note: a device's last visit in the output may still be "open" if the
-- capture window ended before it actually left -- visit_end there just
-- means "last seen," not "confirmed departed."
WITH ordered AS (
    SELECT
        canonical_hash,
        timestamp,
        rssi,
        LAG(timestamp) OVER (
            PARTITION BY canonical_hash ORDER BY timestamp
        ) AS prev_timestamp
    FROM probes
),
flagged AS (
    SELECT
        *,
        CASE
            WHEN prev_timestamp IS NULL
                OR timestamp - prev_timestamp > INTERVAL '2 minutes'
            THEN 1 ELSE 0
        END AS is_new_visit
    FROM ordered
),
visits AS (
    SELECT
        *,
        SUM(is_new_visit) OVER (
            PARTITION BY canonical_hash ORDER BY timestamp
        ) AS visit_id
    FROM flagged
)
SELECT
    canonical_hash,
    visit_id,
    min(timestamp) AS visit_start,
    max(timestamp) AS visit_end,
    max(timestamp) - min(timestamp) AS duration,
    count(*) AS sighting_count,
    round(avg(rssi), 1) AS avg_rssi,
    max(rssi) AS max_rssi
FROM visits
GROUP BY canonical_hash, visit_id
ORDER BY visit_start;

-- Distribution of visit durations: how many visits are brief "passerby"
-- sightings (<1 min, likely someone walking past) vs. longer "lingerer"
-- visits (>=1 min, likely someone stopping in the area). Uses the same
-- 2-minute gap threshold as the visit-reconstruction query above.
WITH ordered AS (
    SELECT
        canonical_hash,
        timestamp,
        LAG(timestamp) OVER (
            PARTITION BY canonical_hash ORDER BY timestamp
        ) AS prev_timestamp
    FROM probes
),
flagged AS (
    SELECT
        *,
        CASE
            WHEN prev_timestamp IS NULL
                OR timestamp - prev_timestamp > INTERVAL '2 minutes'
            THEN 1 ELSE 0
        END AS is_new_visit
    FROM ordered
),
visits AS (
    SELECT
        *,
        SUM(is_new_visit) OVER (
            PARTITION BY canonical_hash ORDER BY timestamp
        ) AS visit_id
    FROM flagged
),
per_visit AS (
    SELECT
        canonical_hash,
        visit_id,
        max(timestamp) - min(timestamp) AS duration
    FROM visits
    GROUP BY canonical_hash, visit_id
)
SELECT
    CASE
        WHEN duration < INTERVAL '1 minute' THEN 'passerby (<1min)'
        ELSE 'lingerer (>=1min)'
    END AS visit_category,
    count(*) AS visit_count
FROM per_visit
GROUP BY visit_category
ORDER BY visit_category;
