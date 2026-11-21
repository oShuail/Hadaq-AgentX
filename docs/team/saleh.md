# Saleh: brick sensor node, sensor simulator, scenario panel, iOS later

**Mission:** you own Hadaq's connection to the physical world. One ESP32 inside a mud brick we make streams real readings; the simulator fills in the rest of the wall; the scenario panel lets us trigger a storm, a crack or a sensor fault on cue during the demo.

## What you own

| Path | What |
| --- | --- |
| `devices/esp32-node/` | PlatformIO firmware for the brick node |
| `devices/sensor-sim/sensor_sim.py` | Simulated sensors: MQTT like the real node, or HTTP when there is no broker |
| `devices/sensor-sim/wall_model.py` | The wall the simulated sensors measure (rain, ponding at the foot, drying, crack, tilt, faults) |
| `services/api/hadaq_api/sensors.py` (with Turky) | Sensor health (ok, suspect, fault, stale) and trends the UI and the agents read |
| `apps/web/src/demo/ScenarioPanel.tsx` | Scenario buttons (Activity tab and phone page) |
| `docker-compose.yml` `mqtt` service (with Turky) | The broker |
| `apps/ios/` (Round 2) | Capture app |

## Setup

```bash
make setup && make up            # Mosquitto on 1883 (and 9001 websockets)
make api                         # terminal 1
make sensors                     # terminal 2
```

No Docker on your laptop? Skip `make up`. `make sensors` tries MQTT first and falls back to HTTP by itself (it posts to `POST /readings` and picks up the scenario events from `GET /buildings/B01/events`). Force one with `--transport mqtt` or `--transport http`.

### The sensor network on B01 (`contracts/building_b01.json`)

| Sensor | Kind | Zone | What it tells us |
| --- | --- | --- | --- |
| S01, S02, S03 | moisture, temperature, humidity | R1C3 | The brick node (real when it is built; `--include-real` simulates it until then) |
| S04 | moisture | R1C6 | Base, east side |
| S07 | moisture | R1C1 | Base, north corner (C1 is the north end: the facade faces west) |
| S08 | moisture | R2C3 | Mid-wall, right above the brick node: with S01 and S05 it gives the vertical profile |
| S05 | moisture | R3C4 | Upper wall |
| S06 | crack_width (mm) | R2C5 | Crack gauge |
| S09 | tilt (deg) | R4C5 | Inclinometer at the top of the wall |

### How the simulated wall behaves (`wall_model.py`)

- Rain wets the base row most, the middle rows least. Water ponds at the wall foot and keeps feeding the base for hours after the rain stops, so the base peaks late and dries about three times slower than the upper wall.
- Temperature and humidity follow a Riyadh November day and change during rain. The crack gauge widens slowly while the wall is wet and breathes a little with the day. The tilt creeps only once the base is soaked.
- It runs on a compressed clock: `--speed 720` (default) is one simulated day every two minutes, one reading per sensor every 2 s. With the default storm (12 mm/h) the base passes 60 %rel after about 30 s.
- The equations are deliberately not the bucket_v0 simulation, so readings and simulation are not the same maths.
- Tune the constants at the top of `wall_model.py` (`K_RAIN`, `K_POND`, `K_DRY`, `ROW_WETTING`) and check with `pytest devices/sensor-sim/tests`.

### Scenario events it understands

| Event | Payload (all optional) | Effect |
| --- | --- | --- |
| `storm_started` | `peak_rain_mm_h` (default 12), `hours` (ends on its own) | Rain builds up over about 1.5 simulated hours |
| `storm_ended` | | Rain eases off; the ponded water keeps the base wet |
| `sensor_fault` | `sensor_id` (default B01-S04), `mode`: `spike`, `stuck` or `dropout`, `duration_s` (default 45) | spike: garbage with quality `fault`; stuck: repeats one value and still says `ok`; dropout: sends nothing |
| `new_crack` | `mm` (default 0.35) | The crack gauge jumps |
| `demo_reset` | | Back to the start state |

### Sensor health (what the API makes of the readings)

`GET /buildings/B01/sensors` gives every sensor a status, and the agents ignore anything that is not `ok`:

| Status | When |
| --- | --- |
| `fault` | The device said so (`quality: "fault"`) |
| `suspect` | The device said `suspect`, the value is outside the plausible range for its kind, or it is **stuck**: the same value 12 times in a row while another sensor of the same kind is moving (a calm wall where everything is flat is not flagged) |
| `stale` | Nothing for 30 s (`SENSOR_STALE_S`) |
| `no_data` | Never reported |

A faulty, implausible or stuck moisture sensor never raises a `moisture_anomaly`. The web app shows the status next to the value (fault, check, stale) and a small trend line per sensor.

Watch the traffic with MQTT Explorer (GUI) or:

```bash
docker compose exec mqtt mosquitto_sub -t 'hadaq/v1/#' -v
```

## Tasks for Round 1, in order

### 1. Simulator live (today)

`make sensors` publishes B01-S01 to S09 every 2 seconds. Check `GET http://localhost:8000/buildings/B01/sensors` (status and trend per sensor) and the sensor list in the web panel. Press **Start storm**: base moisture (S01, S04, S07) climbs past 60 %rel in well under a minute and the Activity feed shows "Moisture anomaly", then "Agent investigated". Press **Sensor fault**: S04 sends garbage with quality `fault` for 45 seconds (shown in red, "fault"). During the storm press **Stuck sensor**: S05 freezes but still says `ok`; after about 24 s the API marks it `suspect` ("check") because the other moisture sensors keep moving. Press **Reset demo** to go back.

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

- [x] Simulator runs, storm leads to an anomaly, fault shows red, stuck sensor is caught, reset works (also without a broker)
- [ ] Brick node streams REAL readings (or the deck says it is in progress)
- [ ] Water-pour clip delivered
- [ ] Phone panel works over the LAN

## Round 2

More nodes (LoRaWAN for range and battery), a weather mast, the iOS capture app with guided framing and LiDAR, and DGDA permission for non-invasive on-site sensors.
