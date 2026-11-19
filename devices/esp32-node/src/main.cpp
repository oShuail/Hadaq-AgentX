// Hadaq brick node (owner: Saleh)
// ESP32 + capacitive soil moisture sensor v1.2 (inside a mud brick) + DHT22.
// Publishes SensorReading JSON every PUBLISH_MS to hadaq/v1/B01/sensors/<sensor_id>.
// Names below MUST match contracts/building_b01.json.

#include <Arduino.h>
#include <ArduinoJson.h>
#include <DHT.h>
#include <PubSubClient.h>
#include <WiFi.h>
#include <time.h>

#include "secrets.h"

// ---------- naming (contract) ----------
static const char* BUILDING_ID = "B01";
static const char* DEVICE_ID = "esp32-01";
static const char* ZONE_ID = "B01-W01-R1C3";
static const char* SENSOR_MOISTURE = "B01-S01";
static const char* SENSOR_TEMP = "B01-S02";
static const char* SENSOR_HUM = "B01-S03";

// ---------- wiring ----------
static const int PIN_SOIL = 34;  // sensor AOUT -> GPIO34 (ADC1; ADC2 pins do not work while WiFi is on)
static const int PIN_DHT = 4;    // DHT22 data -> GPIO4 (10k pull-up to 3.3V if your module has none)
#define DHTTYPE DHT22

// ---------- calibration: measure YOUR sensor and brick ----------
static const int RAW_DRY = 3000;  // reading with the probe in the dry brick
static const int RAW_WET = 1300;  // reading with the probe in a glass of water
static const uint32_t PUBLISH_MS = 5000;

WiFiClient wifi;
PubSubClient mqtt(wifi);
DHT dht(PIN_DHT, DHTTYPE);
String statusTopic;
uint32_t lastPublish = 0;

void connectWifi() {
  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASS);
  Serial.print("WiFi");
  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }
  Serial.printf(" connected, IP %s\n", WiFi.localIP().toString().c_str());
}

void connectMqtt() {
  const char* user = strlen(MQTT_USER) ? MQTT_USER : nullptr;
  const char* pass = strlen(MQTT_PASS) ? MQTT_PASS : nullptr;
  while (!mqtt.connected()) {
    Serial.print("MQTT...");
    // Last will: the broker publishes "offline" (retained) if the node disappears.
    if (mqtt.connect(DEVICE_ID, user, pass, statusTopic.c_str(), 1, true, "offline")) {
      mqtt.publish(statusTopic.c_str(), "online", true);
      Serial.println(" connected");
    } else {
      Serial.printf(" failed rc=%d, retry in 2 s\n", mqtt.state());
      delay(2000);
    }
  }
}

String isoNow() {
  time_t now = time(nullptr);
  struct tm t;
  localtime_r(&now, &t);
  char buf[32];
  strftime(buf, sizeof(buf), "%Y-%m-%dT%H:%M:%S+03:00", &t);
  return String(buf);
}

float moisturePercent(int raw) {
  float pct = 100.0f * float(RAW_DRY - raw) / float(RAW_DRY - RAW_WET);
  return constrain(pct, 0.0f, 100.0f);
}

int readSoilRaw() {
  long sum = 0;
  for (int i = 0; i < 10; i++) {
    sum += analogRead(PIN_SOIL);
    delay(5);
  }
  return int(sum / 10);
}

void publishReading(const char* sensorId, const char* kind, float value, const char* unit, const char* quality) {
  JsonDocument doc;
  doc["sensor_id"] = sensorId;
  doc["building_id"] = BUILDING_ID;
  doc["zone_id"] = ZONE_ID;
  doc["kind"] = kind;
  doc["value"] = value;
  doc["unit"] = unit;
  doc["ts"] = isoNow();
  doc["provenance"] = "measured";
  doc["device_id"] = DEVICE_ID;
  doc["quality"] = quality;
  char payload[384];
  size_t n = serializeJson(doc, payload, sizeof(payload));
  String topic = String("hadaq/v1/") + BUILDING_ID + "/sensors/" + sensorId;
  bool ok = mqtt.publish(topic.c_str(), (const uint8_t*)payload, n, false);
  Serial.printf("%s %s = %.2f %s (%s)\n", ok ? "sent" : "FAILED", sensorId, value, unit, quality);
}

void setup() {
  Serial.begin(115200);
  analogReadResolution(12);
  dht.begin();
  connectWifi();
  configTime(0, 0, "pool.ntp.org", "time.google.com");
  setenv("TZ", "<+03>-3", 1);  // Riyadh, UTC+3, no daylight saving
  tzset();
  statusTopic = String("hadaq/v1/") + BUILDING_ID + "/devices/" + DEVICE_ID + "/status";
  mqtt.setServer(MQTT_HOST, MQTT_PORT);
  mqtt.setBufferSize(512);  // default 256 bytes is too small for our JSON
  connectMqtt();
}

void loop() {
  if (WiFi.status() != WL_CONNECTED) connectWifi();
  if (!mqtt.connected()) connectMqtt();
  mqtt.loop();

  if (millis() - lastPublish < PUBLISH_MS) return;
  lastPublish = millis();

  int raw = readSoilRaw();
  publishReading(SENSOR_MOISTURE, "moisture", moisturePercent(raw), "%rel", "ok");

  float t = dht.readTemperature();
  float h = dht.readHumidity();
  if (!isnan(t)) publishReading(SENSOR_TEMP, "temperature", t, "degC", "ok");
  if (!isnan(h)) publishReading(SENSOR_HUM, "humidity", h, "%RH", "ok");
  if (isnan(t) || isnan(h)) Serial.println("DHT22 read failed: check wiring and pull-up");
}
