#!/bin/bash
# Builds and runs the pax-server ingestion service against an already-flashed
# ESP32. The ESP32 itself is flashed separately with flash.sh.
set -e
cd "$(dirname "$0")/pax-server"
mvn -q compile exec:java -Dexec.mainClass=com.nithish.paxcounter.App
