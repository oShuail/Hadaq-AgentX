# Brick node (owner: Saleh)

One ESP32 inside a small mud brick we make ourselves. Never attach anything to the real heritage wall.

## Parts

| Part | Notes |
| --- | --- |
| ESP32 DevKit (esp32dev) | Any ESP32-WROOM board |
| Capacitive soil moisture sensor v1.2 | Analog out. Use capacitive, not the resistive fork type (that one corrodes in wet mud) |
| DHT22 (AM2302) | Temperature and humidity of the air around the brick |
| Mud brick | Mix soil, a little straw and water; mould about 20 x 10 x 7 cm; push the probe in while wet, let it dry 1 to 2 days (or sun-dry faster) |
| Jumper wires, USB cable, power bank | |

## Wiring

| Sensor pin | ESP32 pin |
| --- | --- |
| Moisture VCC | 3V3 |
| Moisture GND | GND |
| Moisture AOUT | GPIO34 (ADC1; ADC2 pins stop working when WiFi is on) |
| DHT22 VCC | 3V3 |
| DHT22 DATA | GPIO4 (add a 10k resistor to 3V3 if your DHT22 is a bare sensor, not a module) |
| DHT22 GND | GND |

## Flash

1. Install VS Code + PlatformIO extension.
2. `cp include/secrets.example.h include/secrets.h` and fill in WiFi and the laptop's LAN IP (the one running `docker compose up`).
3. `pio run -t upload` then `pio device monitor`. You should see `sent B01-S01 = ...` every 5 seconds.
4. Calibrate: note the raw value in the dry brick (RAW_DRY) and with the probe in water (RAW_WET); update `src/main.cpp`.

## Check it end to end

```bash
docker compose up -d mqtt
mosquitto_sub -h localhost -t 'hadaq/v1/#' -v   # or MQTT Explorer
```

Readings land in the API (`GET /buildings/B01/readings/latest`) and on the web panel tagged **REAL**.

The readings are relative (0 to 100 %rel between your dry and wet calibration points), not calibrated moisture content. Label them that way in the deck.
