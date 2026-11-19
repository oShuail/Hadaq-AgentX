# Saleh: brick sensor node, sensor simulator, scenario panel, iOS later

**Mission:** you own Hadaq's connection to the physical world. One ESP32 inside a mud brick we make streams real readings; the simulator fills in the rest of the wall; the scenario panel lets us trigger a storm, a crack or a sensor fault on cue during the demo.

## What you own

| Path | What |
| --- | --- |
| `devices/esp32-node/` | PlatformIO firmware for the brick node |
| `devices/sensor-sim/sensor_sim.py` | Simulated sensors on the same MQTT topics |
| `apps/web/src/demo/ScenarioPanel.tsx` | Scenario buttons (Activity tab and phone page) |
| `docker-compose.yml` `mqtt` service (with Turky) | The broker |
| `apps/ios/` (Round 2) | Capture app |

## Setup

```bash
make setup && make up            # Mosquitto on 1883 (and 9001 websockets)
make api                         # terminal 1
make sensors                     # terminal 2
```

Watch the traffic with MQTT Explorer (GUI) or:

```bash
docker compose exec mqtt mosquitto_sub -t 'hadaq/v1/#' -v
```

## Tasks for Round 1, in order

### 1. Simulator live (today)

`make sensors` publishes B01-S01 to S06 every 2 seconds. Check `GET http://localhost:8000/buildings/B01/readings/latest` and the sensor list in the web panel. Press **Start storm**: base moisture (S04) should climb past 60 %rel within about a minute and the Activity feed shows "Moisture anomaly". Press **Sensor fault**: S04 sends garbage with quality `fault` for 45 seconds (shown in red). Press **Reset demo** to go back.

### 2. Build the brick node

Parts, wiring, flashing and calibration are in `devices/esp32-node/README.md`. Key points:

- Capacitive moisture probe (not the resistive fork type) on GPIO34, DHT22 on GPIO4.
- `include/secrets.h` holds WiFi and the laptop's LAN IP (git-ignored).
- Calibrate `RAW_DRY` (dry brick) and `RAW_WET` (probe in water) in `src/main.cpp`.
- The node publishes with `provenance: "measured"`; the panel shows it as **REAL**.

When the node is live, stop faking it: run the simulator **without** `--include-real`:

```bash
.venv/bin/python devices/sensor-sim/sensor_sim.py
```

### 3. The water-pour shot

Film pouring a little water on the brick next to the laptop: the REAL moisture value jumps on screen and, if it crosses the rule, the agent starts investigating. Give the clip to Abdurhmn for the video.

### 4. Scenario panel on a phone

The buttons are in the web app's **Activity** tab. For the phone version open `http://<laptop-LAN-IP>:5173/?panel=1` (Vite listens on the network). Polish ideas, in order of value:

```tsx
// apps/web/src/demo/ScenarioPanel.tsx: storm intensity before "Start storm"
const [peak, setPeak] = useState(12);
<input type="range" min={4} max={40} value={peak} onChange={(e) => setPeak(Number(e.target.value))} />
// then send payload { peak_rain_mm_h: peak, wind_ms: 8, wind_dir_deg: 270 }
```

Any new event type must be added to `contracts/` first (PR), then to the simulator's `World.on_event`.

### 5. React Native and iOS (decision)

- The **main app stays web** (React + three.js). The 3D twin needs a browser with WebGL, and judges watch a laptop screen or video. React Native would only show the map inside a WebView.
- The **capture app** for inspectors is Round 2. If you prefer one language with the team, use Expo (React Native) with `expo-camera` to take guided photos and `POST /photos` them. For LiDAR scanning you need native Swift (ARKit / Object Capture), which Expo cannot do alone.
- For Round 1, the phone panel page covers the "mobile" story.

## Naming rules

- Device IDs lowercase: `esp32-01`, `sim-01`. Sensor IDs `B01-S01`.... Zone IDs `B01-W01-R1C3`.
- Topics only as in `contracts/README.md`; never publish outside `hadaq/v1/`.
- Units exactly: `%rel`, `degC`, `%RH`, `mm`.

## Contracts

- Produces: `SensorReading` on `hadaq/v1/B01/sensors/{sensor_id}`, device status (retained, last will), scenario `Event`s through `POST /events`.
- Consumes: events on `hadaq/v1/B01/events/#`.

## Definition of done (21 Nov)

- [ ] Simulator runs, storm leads to one anomaly, fault shows red, reset works
- [ ] Brick node streams REAL readings (or the deck says it is in progress)
- [ ] Water-pour clip delivered
- [ ] Phone panel works over the LAN

## Round 2

More nodes (LoRaWAN for range and battery), a weather mast, the iOS capture app with guided framing and LiDAR, and DGDA permission for non-invasive on-site sensors.
