#include <WiFi.h>
#include "esp_wifi.h"
#include "mbedtls/md.h"

void promiscuous_callback(void *buf, wifi_promiscuous_pkt_type_t type) {
    if (type != WIFI_PKT_MGMT) return;

    wifi_promiscuous_pkt_t *pkt = (wifi_promiscuous_pkt_t *)buf;
    uint8_t *payload = pkt->payload;

    // Frame Control field: check if it's a probe request (subtype 0x40)
    uint8_t frameType = payload[0] & 0xFC;
    if (frameType != 0x40) return;

    // Source MAC is bytes 10-15 of the 802.11 header
    uint8_t *srcMac = &payload[10];

    // Simple hash (SHA-256, truncated) for privacy
    unsigned char hash[32];
    mbedtls_md_context_t ctx;
    mbedtls_md_init(&ctx);
    mbedtls_md_setup(&ctx, mbedtls_md_info_from_type(MBEDTLS_MD_SHA256), 0);
    mbedtls_md_starts(&ctx);
    mbedtls_md_update(&ctx, srcMac, 6);
    mbedtls_md_finish(&ctx, hash);
    mbedtls_md_free(&ctx);

    // Print first 8 bytes of hash as hex, plus RSSI
    Serial.printf("%02x%02x%02x%02x%02x%02x%02x%02x,%d\n",
        hash[0], hash[1], hash[2], hash[3], hash[4], hash[5], hash[6], hash[7],
        pkt->rx_ctrl.rssi);
}

void setup() {
    Serial.begin(115200);
    delay(3000);

    WiFi.mode(WIFI_STA);
    WiFi.disconnect();
    esp_wifi_set_promiscuous(true);
    esp_wifi_set_promiscuous_rx_cb(&promiscuous_callback);
    esp_wifi_set_channel(1, WIFI_SECOND_CHAN_NONE);

    Serial.println("Sniffer started"); // sanity check line
}

void loop() {
    //Serial.println("dis werks");
    static uint8_t channel = 1;
    esp_wifi_set_channel(channel, WIFI_SECOND_CHAN_NONE);
    channel = (channel % 11) + 1;
    delay(300);
}