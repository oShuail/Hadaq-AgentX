# Replays the sensor simulator's storm through the real monitoring rule, without MQTT, Docker or the API.
# Run from the project folder:  .venv\Scripts\python.exe notebooks\monitoring_check.py
from datetime import datetime, timedelta, timezone

from hadaq_sim.monitoring import ABS_THRESHOLD, RISE_PCT_PER_H, detect_moisture_anomaly

BASE_MOISTURE = 18.0   # same start value as devices/sensor-sim/sensor_sim.py
RISE = {"base (B01-S04, R1C6)": 14.0, "upper wall (B01-S05, R3C4)": 6.0}  # % per simulated hour while it rains
INTERVAL_S = 2.0       # real seconds between readings (sensor_sim default)
SPEED = 720.0          # simulated seconds per real second (sensor_sim default)
COOLDOWN_S = 600       # the API's cooldown between two anomalies of one sensor
dt_hours = INTERVAL_S * SPEED / 3600

print(f"rule: value >= {ABS_THRESHOLD} %rel, or rise >= {RISE_PCT_PER_H} %rel per hour")
start = datetime(2026, 1, 1, tzinfo=timezone.utc)
for name, rise in RISE.items():
    m = BASE_MOISTURE
    history = []
    last_fired = None
    fired = []
    for tick in range(300):  # 300 readings = 10 real minutes of storm
        m += rise * dt_hours * (1 - m / 100)
        ts = start + timedelta(seconds=tick * INTERVAL_S)
        history = (history + [(ts, m)])[-120:]  # the API looks at the last 120 readings
        anomaly = detect_moisture_anomaly(history)
        if anomaly and (last_fired is None or (ts - last_fired).total_seconds() > COOLDOWN_S):
            last_fired = ts
            fired.append((tick, anomaly["rule"], round(m, 1)))
    print(f"{name}: {len(fired)} anomaly event(s)", fired)