#include <WiFi.h>
extern "C" {
  #include "esp_wifi.h"
}

#define MAX_MACS 128
#define REFRESH_INTERVAL 100
#define MAC_TIMEOUT 30000
#define FILTER_RANDOM_MAC false

struct Device {
  uint8_t mac[6];
  int8_t rssi;
  uint8_t channel;
  unsigned long lastSeen;
};

Device devices[MAX_MACS];
int deviceCount = 0;

void IRAM_ATTR snifferCallback(void* buf, wifi_promiscuous_pkt_type_t type) {
  if (type != WIFI_PKT_MGMT) return;
  wifi_promiscuous_pkt_t *pkt = (wifi_promiscuous_pkt_t*)buf;
  if (pkt->rx_ctrl.sig_len < 24) return;
  uint8_t *payload = pkt->payload;

  uint16_t fc = payload[0] | (payload[1] << 8);
  uint8_t subtype = (fc >> 4) & 0xF;
  if (subtype != 0x04) return;  // probe requests only

  uint8_t *mac = payload + 10;
  if (FILTER_RANDOM_MAC && ((mac[0] & 0x01) || (mac[0] & 0x02))) return;

  int8_t rssi = pkt->rx_ctrl.rssi;
  uint8_t channel = pkt->rx_ctrl.channel;
  unsigned long now = millis();

  // Purge old devices (moved here = after every scan)
  for (int i = 0; i < deviceCount; ) {
    if (now - devices[i].lastSeen > MAC_TIMEOUT) {
      devices[i] = devices[deviceCount - 1];
      deviceCount--;
    } else {
      i++;
    }
  }

  // check if known
  for (int i = 0; i < deviceCount; i++) {
    if (memcmp(devices[i].mac, mac, 6) == 0) {
      devices[i].rssi = rssi;
      devices[i].channel = channel;
      devices[i].lastSeen = now;
      return;
    }
  }

  // new device
  if (deviceCount < MAX_MACS) {
    memcpy(devices[deviceCount].mac, mac, 6);
    devices[deviceCount].rssi = rssi;
    devices[deviceCount].channel = channel;
    devices[deviceCount].lastSeen = now;
    deviceCount++;
  }
}

void setup() {
  Serial.begin(115200);
  delay(1000);
  esp_log_level_set("*", ESP_LOG_NONE);
  Serial.println("Starting dynamic Wi-Fi sniffer...");

  wifi_init_config_t cfg = WIFI_INIT_CONFIG_DEFAULT();
  esp_wifi_init(&cfg);
  esp_wifi_set_storage(WIFI_STORAGE_RAM);
  esp_wifi_set_mode(WIFI_MODE_NULL);
  esp_wifi_start();

  wifi_promiscuous_filter_t filter = { .filter_mask = WIFI_PROMIS_FILTER_MASK_MGMT };
  esp_wifi_set_promiscuous_filter(&filter);
  esp_wifi_set_promiscuous_rx_cb(&snifferCallback);
  esp_wifi_set_promiscuous(true);
  esp_wifi_set_channel(1, WIFI_SECOND_CHAN_NONE);
}

// Your other code (includes, snifferCallback, setup) stays the same...

void loop() {
  static unsigned long lastRefresh = 0;
  unsigned long now = millis();
  if (now - lastRefresh < REFRESH_INTERVAL) return;
  lastRefresh = now;

  // --- Start of new JSON output ---
  Serial.print("{\"active\":");
  Serial.print(deviceCount);
  Serial.print(",\"devices\":[");

  for (int i = 0; i < deviceCount; i++) {
    char macStr[18];
    sprintf(macStr, "%02X:%02X:%02X:%02X:%02X:%02X",
            devices[i].mac[0], devices[i].mac[1], devices[i].mac[2],
            devices[i].mac[3], devices[i].mac[4], devices[i].mac[5]);
    
    Serial.print("{");
    Serial.print("\"mac\":\"");
    Serial.print(macStr);
    Serial.print("\",");
    Serial.print("\"rssi\":");
    Serial.print(devices[i].rssi);
    Serial.print(",");
    Serial.print("\"ch\":");
    Serial.print(devices[i].channel);
    Serial.print(",");
    Serial.print("\"lastSeen\":");
    Serial.print((now - devices[i].lastSeen) / 1000);
    Serial.print("}");
    
    if (i < deviceCount - 1) {
      Serial.print(",");
    }
  }
  
  Serial.println("]}"); // Use println to send newline, which our bridge will look for
  // --- End of new JSON output ---


  // Channel hopping
  static int ch = 1;
  ch = (ch % 11) + 1;
  esp_wifi_set_channel(ch, WIFI_SECOND_CHAN_NONE);
}
