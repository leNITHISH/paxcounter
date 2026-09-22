package com.nithish.paxcounter;

import com.fazecast.jSerialComm.SerialPort;

public class App {
	private static SerialPort findEspPort() {
    	SerialPort[] ports = SerialPort.getCommPorts();
    	for (SerialPort p : ports) {
        	String desc = p.getPortDescription().toLowerCase();
        	String product = p.getDescriptivePortName().toLowerCase();
      	  if (desc.contains("espressif") || product.contains("espressif")
                	|| desc.contains("jtag") || desc.contains("cdc")) {
            	return p;
        	}
    	}
    	return null;
	}

	private static SerialPort openEspPort(SerialPort port) {
		port.setBaudRate(115200);
		port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 1000, 0);
		return port.openPort() ? port : null;
	}

	// Blocks until the ESP32 shows up and opens cleanly, retrying every 2s.
	// Used to reconnect after the device is unplugged/reset mid-run, so the
	// server doesn't just die on the next disconnect.
	private static SerialPort waitForEspReconnect() throws InterruptedException {
		System.err.println("ESP32 disconnected, waiting for it to come back...");
		while (true) {
			SerialPort port = findEspPort();
			if (port != null && openEspPort(port) != null) {
				System.out.println("Reconnected on: " + port.getSystemPortName());
				return port;
			}
			Thread.sleep(2000);
		}
	}

    public static void main(String[] args) throws Exception {
		SerialPort port = findEspPort();
		if (port == null || openEspPort(port) == null) {
    		System.err.println("No ESP32 found. Available ports:");
    		for (SerialPort p : SerialPort.getCommPorts()) {
        		System.err.println("  " + p.getSystemPortName() + " - " + p.getDescriptivePortName());
    		}
   		 System.exit(1);
	}
	System.out.println("Found ESP32 on: " + port.getSystemPortName());
        System.out.println("Port opened, polling...");

        TtlCache cache = new TtlCache(10_000); // 10s TTL
        SequenceLinker seqLinker = new SequenceLinker();
        MetricsLogger logger = new MetricsLogger("metrics.csv");
        RawEventLogger rawLogger = new RawEventLogger("raw_probes.csv");

        // flush and close both CSVs cleanly on Ctrl+C instead of losing
        // whatever's still buffered when the process is killed
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.close();
            rawLogger.close();
        }));

        long totalProbes = 0;
        long uniqueDevices = 0;
        long lastLogTime = System.currentTimeMillis();
        long lastEvictTime = System.currentTimeMillis();

        StringBuilder lineBuffer = new StringBuilder();
        byte[] buffer = new byte[1024];

        while (true) {
            int numRead = port.readBytes(buffer, buffer.length);
            if (numRead < 0) {
                port.closePort();
                port = waitForEspReconnect();
                lineBuffer.setLength(0);
                continue;
            }
            if (numRead > 0) {
                for (int i = 0; i < numRead; i++) {
                    char c = (char) buffer[i];
                    if (c == '\n') {
                        String line = lineBuffer.toString().trim();
                        lineBuffer.setLength(0);
                        if (line.isEmpty()) continue;

                        // expected format: <hash>,<rssi>,<channel>,<seq>
                        String[] parts = line.split(",");
                        if (parts.length < 1) continue;

                        String hash = parts[0].trim();
                        // Anything that isn't a 16-hex-char mac hash is a
                        // status/diagnostic line (e.g. "Sniffer started", or
                        // the ring buffer's "WARN dropped N probes..."), not
                        // a probe sighting -- echo it but don't count it.
                        if (!hash.matches("[0-9a-fA-F]{16}")) {
                            System.out.println(line);
                            continue;
                        }

                        int rssi = 0;
                        int channel = 0;
                        int seq = 0;
                        if (parts.length >= 2) {
                            try {
                                rssi = Integer.parseInt(parts[1].trim());
                            } catch (NumberFormatException ignored) {
                            }
                        }
                        if (parts.length >= 3) {
                            try {
                                channel = Integer.parseInt(parts[2].trim());
                            } catch (NumberFormatException ignored) {
                            }
                        }
                        if (parts.length >= 4) {
                            try {
                                seq = Integer.parseInt(parts[3].trim());
                            } catch (NumberFormatException ignored) {
                            }
                        }

                        totalProbes++;
                        String canonicalHash = seqLinker.resolve(hash, seq);
                        boolean isNew = cache.registerAndCheckNew(canonicalHash);
                        if (isNew) uniqueDevices++;

                        rawLogger.logEvent(hash, canonicalHash, rssi, channel, seq);

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
