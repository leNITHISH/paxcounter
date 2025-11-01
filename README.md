arduino-cli compile --fqbn esp32:esp32:esp32 croudtrack.ino
arduino-cli upload -p /dev/ttyUSB0 --fqbn esp32:esp32:esp32 croudtrack.ino
screen /dev/ttyUSB0 115200
