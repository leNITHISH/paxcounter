# Findings: live run, 2026-10-03

## What happened before any real data showed up

First capture attempt (2 min) produced garbage: raw serial showed a continuous stream of plain decimal lines like `174,362,236,232`, no hex hash, no `Sniffer started` banner, nothing matching paxcounter's format. `esptool chip-id` confirmed it was still the same physical ESP32-C3 (same MAC, `14:63:93:70:63:84`), so the board had been reflashed with different firmware (looked like a factory-style ADC/sensor demo) sometime since the last session, not caused by anything in this run. `App.java`'s hash-format regex check correctly rejected all of it, `metrics.csv`/`raw_probes.csv` stayed clean (just empty), no bad data got recorded despite ~762KB of garbage hitting the serial port.

Reflashed `paxcounter.ino` via `./flash.sh`, confirmed clean silence on raw serial afterward, then re-ran. First two capture windows (2 min, then another 60s start) were genuinely quiet, zero probes, consistent with the known pattern that idle/already-connected devices mostly don't send probe requests. A third window caught real traffic once Wi-Fi was toggled nearby.

## Real capture results

One device (`a0973f1fee64fffe`), 9 probe sightings, single visit lasting ~1.5s, channel-hopped across 9 -> 1 -> 2 -> 3 as the ESP32 cycled channels while the device kept probing, RSSI -85 to -89 (consistently weak signal, device likely not close to the sniffer). 8 of the 9 sightings were exact-hash repeats within the same visit, caught by `TtlCache`; no MAC rotation occurred so `SequenceLinker` had nothing to link (`distinct_raw_hashes == distinct_canonical_devices == 1`, as expected for a single short visit).

All 8 `analytics.sql` queries ran cleanly against this data:
- Peak unique devices: 1. Total probes: 9.
- `avg_duplicate_filter_pct` from the periodic-rollup query came out to 5.56%, which looks low but is an artifact of averaging across mostly-empty 10s rollup windows (11 of 12 rollups were `0,0,0,0.00` during the quiet stretch, only the last one had the actual traffic at 88.89%). The real dedup rate for the window that had traffic is 88.89%, not 5.56%, worth remembering when reading that top-line number on a sparse dataset.
- RSSI bucket: all 9 sightings fell in the -90 to -81 bucket, consistent with a weak/distant signal.
- Channel occupancy: split 3/3/2/1 across channels 1/2/3/9, following the hop sequence.
- Visit reconstruction: one visit, ~1.5s duration, categorized as "passerby" in the duration-distribution query.
- Occupancy over time: 1 concurrent device in the one minute bucket that had activity.
- Linking effectiveness over time: 0 hashes merged (nothing to merge, single visit, no rotation).

## Takeaways

1. The parser hardening added last session earned its keep immediately: a real firmware mixup produced a flood of malformed data and none of it leaked into the CSVs or corrupted a count.
2. The analytics queries (including the two added this session, occupancy-over-time and linking-effectiveness-over-time) all run correctly against real data, not just the synthetic test cases they were validated against earlier.
3. This dataset is too small and too single-device to say anything meaningful about accuracy or density, it's a smoke test, not a benchmark. The visit-reconstruction and occupancy queries in particular need a longer, multi-device capture before their output means much.
4. The ESP32-C3's USB-Serial/JTAG link dropped once more during this session (handled gracefully by the reconnect logic, no data lost), consistent with the known hardware flakiness noted earlier, still unresolved, still not blocking.

## Findings: hour-long capture, 2026-10-03

Ran `./run.sh` unattended for the full planned hour (59m40s of actual capture, 12:00:04 to 12:59:44) against the firmware with sequence-number plus IE-fingerprint linking and the HMAC pepper, both landed earlier this session. This is the first dataset big enough to make the visit-reconstruction and occupancy queries say something real.

**Headline numbers**: 205 total probes, 59 distinct raw MAC hashes, 54 distinct canonical devices, meaning the linker merged 5 raw hashes into earlier devices across the hour, each one a MAC rotation caught in the act, not a synthetic test. 64 visits total: 61 "passerby" (under a minute) and 3 "lingerer" (a minute or more).

**The standout visit**: canonical device `a7e3bd8b8cf7bffd` (a real, non-randomized MAC per the locally-administered bit) stuck around for 5 minutes 28 seconds, 33 sightings, from 12:02:36 to 12:08:04, average RSSI -42.4 dBm, by far the strongest and most consistent signal of anything captured, consistent with a device sitting close to the sniffer for an extended stretch. This same device also reappeared in six later, much shorter visits (visit ids 6 through 10) after going quiet for more than the 2-minute gap threshold each time, exactly the "device left and came back" pattern the visit-reconstruction query is designed to catch.

**Linking caught real rotations, not just the one from the earlier smoke test**: 5 merges total, visible in the per-minute linking-effectiveness query at 12:02, 12:14, 12:32, 12:40, and 12:57. Each is a different raw hash whose sequence number picked up within the gap threshold of a recent sighting with a matching IE fingerprint, the exact mechanism designed and tested earlier this session, now showing up repeatedly over a full hour instead of as a single lucky catch.

**RSSI and channel distributions are no longer degenerate**: sightings spread across all seven 10dBm buckets from -40 to -100 (most sightings in the -50 and -90 buckets, a rough bimodal split between close and far devices) and across all 11 channels fairly evenly (15 to 27 sightings each), confirming the channel hop is giving real coverage over a long run rather than just catching whatever happened to be on channel 1.

**Caveat carried over from the live status checks during the capture**: `unique_devices` in `metrics.csv` (and `peak_unique_devices` in the first analytics query) counts every TtlCache re-registration after a gap longer than 10 seconds, not distinct physical devices ever seen. The real non-randomized device above alone re-registered as "new" at least 6 times across the hour as it went quiet and came back. The visit-reconstruction query's `canonical_hash` count is the more honest "how many distinct devices" number; `unique_devices` is closer to "how many presence events."

### Takeaways
1. An hour is enough to get a real, if still small, multi-device dataset, a few minutes consistently was not.
2. The sequence-number plus IE-fingerprint linking is no longer a one-off anecdote: it fired 5 times independently over the hour, each a plausible real MAC rotation.
3. The gap-threshold visit logic correctly distinguished one device's long, continuous presence from its own later, separate, shorter visits, exactly the behavior it was designed for.
4. `unique_devices` and `peak_unique_devices` are not "distinct devices" metrics on their own; read them alongside the visit-reconstruction query's canonical device count, not instead of it.
