#include <WiFi.h>
#include "esp_wifi.h"
#include "mbedtls/md.h"

// Decoded probe sightings, buffered here instead of printed straight from
// the callback, so a temporary USB disconnect doesn't just drop them. The
// callback runs in the WiFi driver's own task (producer); loop() drains it
// (consumer) -- a single-producer/single-consumer ring, so plain volatile
// indices are enough without a lock. One slot is always left empty to tell
// full apart from empty, so RING_CAPACITY holds RING_CAPACITY - 1 usable
// entries.
#define RING_CAPACITY 201

struct ProbeSighting {
    uint8_t hash[8];
    int8_t rssi;
    uint8_t channel;
    uint16_t seq;
};

static ProbeSighting ringBuffer[RING_CAPACITY];
static volatile uint16_t ringHead = 0; // next write slot (producer owns this)
static volatile uint16_t ringTail = 0; // next read slot (consumer owns this)
static volatile uint32_t ringDropped = 0;

static void ringPush(const ProbeSighting &s) {
    uint16_t nextHead = (ringHead + 1) % RING_CAPACITY;
    if (nextHead == ringTail) {
        ringDropped++; // buffer full: drop the newest instead of the oldest
        return;
    }
    ringBuffer[ringHead] = s;
    ringHead = nextHead;
}

void promiscuous_callback(void *buf, wifi_promiscuous_pkt_type_t type) {
    if (type != WIFI_PKT_MGMT) return;

    wifi_promiscuous_pkt_t *pkt = (wifi_promiscuous_pkt_t *)buf;

    // sig_len is the actual captured length; frames shorter than a full
    // header (24 bytes: 2 frame control + 2 duration + 3x6 address + 2 seq
    // control) don't have a sequence control field, and reading payload[22]
    // /[23] past the end of a short capture is an out-of-bounds read that
    // was crashing the chip and causing the USB-JTAG serial to keep dropping.
    if (pkt->rx_ctrl.sig_len < 24) return;

    uint8_t *payload = pkt->payload;

    // Frame Control field: check if it's a probe request (subtype 0x40)
    uint8_t frameType = payload[0] & 0xFC;
    if (frameType != 0x40) return;

    // Source MAC is bytes 10-15 of the 802.11 header
    uint8_t *srcMac = &payload[10];

    // Sequence Control is bytes 22-23 (little-endian): low 4 bits are the
    // fragment number, top 12 bits are the sequence number. The radio's
    // sequence counter keeps incrementing across MAC address rotations, so
    // this lets the server link probes from the same physical device even
    // when its randomized MAC changes.
    uint16_t seqControl = payload[22] | (payload[23] << 8);
    uint16_t seqNum = seqControl >> 4;

    // Simple hash (SHA-256, truncated) for privacy
    unsigned char hash[32];
    mbedtls_md_context_t ctx;
    mbedtls_md_init(&ctx);
    mbedtls_md_setup(&ctx, mbedtls_md_info_from_type(MBEDTLS_MD_SHA256), 0);
    mbedtls_md_starts(&ctx);
    mbedtls_md_update(&ctx, srcMac, 6);
    mbedtls_md_finish(&ctx, hash);
    mbedtls_md_free(&ctx);

    // pkt->rx_ctrl.channel is the channel this packet was actually received on,
    // reported by the radio itself. That's more accurate than reading back
    // whatever channel loop() last requested, which can be mid-hop by the
    // time a packet lands -- so we log the hardware's value, not our own state.
    ProbeSighting sighting;
    memcpy(sighting.hash, hash, 8);
    sighting.rssi = pkt->rx_ctrl.rssi;
    sighting.channel = pkt->rx_ctrl.channel;
    sighting.seq = seqNum;
    ringPush(sighting);
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
    // Only drain into Serial when a host is actually listening -- printing
    // to a disconnected USB CDC link is exactly how sightings used to get
    // lost, so leave them queued in the ring until isConnected() is true.
    if (Serial.isConnected()) {
        ProbeSighting s;
        while (ringTail != ringHead) {
            s = ringBuffer[ringTail];
            Serial.printf("%02x%02x%02x%02x%02x%02x%02x%02x,%d,%d,%d\n",
                s.hash[0], s.hash[1], s.hash[2], s.hash[3],
                s.hash[4], s.hash[5], s.hash[6], s.hash[7],
                s.rssi, s.channel, s.seq);
            ringTail = (ringTail + 1) % RING_CAPACITY;
        }
        if (ringDropped > 0) {
            Serial.printf("WARN dropped %u probes, ring buffer was full\n", ringDropped);
            ringDropped = 0;
        }
    }

    // Hop across the three non-overlapping 2.4GHz channels for broader coverage.
    // The channel actually stamped on each packet is read from rx_ctrl above,
    // not tracked separately here.
    static uint8_t channel = 1;
    esp_wifi_set_channel(channel, WIFI_SECOND_CHAN_NONE);
    channel = (channel % 11) + 1;
    delay(300);
}