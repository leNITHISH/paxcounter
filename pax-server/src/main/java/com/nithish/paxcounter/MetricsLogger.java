package com.nithish.paxcounter;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;

public class MetricsLogger {
    private final PrintWriter writer;

    public MetricsLogger(String filePath) throws IOException {
        boolean isNewFile = !new java.io.File(filePath).exists();
        writer = new PrintWriter(new FileWriter(filePath, true));
        if (isNewFile) {
            writer.println("timestamp,unique_devices,total_probes,cache_size,filtered_pct");
            writer.flush();
        }
    }

    public void logRow(long uniqueDevices, long totalProbes, int cacheSize) {
        double filteredPct = totalProbes == 0 ? 0.0
                : ((double) (totalProbes - uniqueDevices) / totalProbes) * 100.0;
        writer.printf("%s,%d,%d,%d,%.2f%n",
                Instant.now(), uniqueDevices, totalProbes, cacheSize, filteredPct);
        writer.flush();
    }

    public void close() {
        writer.close();
    }
}
