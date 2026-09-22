package com.nithish.paxcounter;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;

/**
 * Logs every individual probe sighting -- one row per event, including
 * duplicates the TtlCache filters out of the aggregate counts. This is the
 * raw event stream that real analytics (dwell time, RSSI buckets, channel
 * occupancy) needs; MetricsLogger's periodic rollups can't support that kind
 * of query since they throw the individual events away.
 */
public class RawEventLogger {
    private final PrintWriter writer;

    public RawEventLogger(String filePath) throws IOException {
        boolean isNewFile = !new java.io.File(filePath).exists();
        writer = new PrintWriter(new FileWriter(filePath, true));
        if (isNewFile) {
            writer.println("timestamp,mac_hash,canonical_hash,rssi,channel,seq");
            writer.flush();
        }
    }

    // canonicalHash is the id SequenceLinker resolved this sighting to for
    // uniqueness counting -- equal to macHash unless it got linked to an
    // earlier, differently-randomized MAC from the same radio.
    public void logEvent(String macHash, String canonicalHash, int rssi, int channel, int seq) {
        writer.printf("%s,%s,%s,%d,%d,%d%n", Instant.now(), macHash, canonicalHash, rssi, channel, seq);
        writer.flush();
    }

    public void close() {
        writer.close();
    }
}
