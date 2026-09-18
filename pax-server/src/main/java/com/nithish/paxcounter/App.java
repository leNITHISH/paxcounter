package com.nithish.paxcounter;

import com.fazecast.jSerialComm.SerialPort;

public class App {
    public static void main(String[] args) throws Exception {
        SerialPort port = SerialPort.getCommPort("/dev/ttyACM1");
        port.setBaudRate(115200);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 1000, 0);

        boolean opened = port.openPort();
        if (!opened) {
            System.err.println("Failed to open port");
            System.exit(1);
        }
        System.out.println("Port opened, polling...");

        TtlCache cache = new TtlCache(10_000); // 10s TTL
        MetricsLogger logger = new MetricsLogger("metrics.csv");

        long totalProbes = 0;
        long uniqueDevices = 0;
        long lastLogTime = System.currentTimeMillis();
        long lastEvictTime = System.currentTimeMillis();

        StringBuilder lineBuffer = new StringBuilder();
        byte[] buffer = new byte[1024];

        while (true) {
            int numRead = port.readBytes(buffer, buffer.length);
            if (numRead > 0) {
                for (int i = 0; i < numRead; i++) {
                    char c = (char) buffer[i];
                    if (c == '\n') {
                        String line = lineBuffer.toString().trim();
                        lineBuffer.setLength(0);
                        if (line.isEmpty()) continue;

                        // expected format: <hash>,<rssi>
                        String[] parts = line.split(",");
                        if (parts.length < 1) continue;

                        String hash = parts[0].trim();
                        if (hash.equalsIgnoreCase("Sniffer started")) {
                            System.out.println(line);
                            continue;
                        }

                        totalProbes++;
                        boolean isNew = cache.registerAndCheckNew(hash);
                        if (isNew) uniqueDevices++;

                        System.out.printf("Received: %s | new=%b | unique=%d | total=%d%n",
                                line, isNew, uniqueDevices, totalProbes);
                    } else if (c != '\r') {
                        lineBuffer.append(c);
                    }
                }
            }

            long now = System.currentTimeMillis();
            if (now - lastLogTime >= 10_000) {
                logger.logRow(uniqueDevices, totalProbes, cache.size());
                lastLogTime = now;
            }
            if (now - lastEvictTime >= 30_000) {
                cache.evictExpired();
                lastEvictTime = now;
            }
        }
    }
}
