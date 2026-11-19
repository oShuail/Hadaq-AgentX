// Copy to include/secrets.h (git-ignored) and fill in.
#pragma once

#define WIFI_SSID "your-wifi"
#define WIFI_PASS "your-password"

// The laptop running `docker compose up` (find its LAN IP with ipconfig / ifconfig).
#define MQTT_HOST "192.168.1.50"
#define MQTT_PORT 1883
#define MQTT_USER ""
#define MQTT_PASS ""
