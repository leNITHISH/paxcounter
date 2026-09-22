#!/bin/bash
# Compiles and flashes paxcounter.ino to the ESP32-C3.
# USB CDC On Boot must stay enabled: without it, early boot/panic output
# gets swallowed instead of showing up over serial, and it also fixed
# intermittent USB-Serial/JTAG disconnects seen during testing.
set -e
PORT="${1:-/dev/ttyACM0}"
FQBN="esp32:esp32:esp32c3:CDCOnBoot=cdc"

cd "$(dirname "$0")"
arduino-cli compile --fqbn "$FQBN" paxcounter.ino
arduino-cli upload --fqbn "$FQBN" -p "$PORT" paxcounter.ino
