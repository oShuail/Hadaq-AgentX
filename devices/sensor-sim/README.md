# Sensor simulator (owner: Saleh)

```bash
make up && make sensors        # or: python devices/sensor-sim/sensor_sim.py --include-real
```

- Publishes `SensorReading` JSON to `hadaq/v1/B01/sensors/<sensor_id>` every 2 s (`--interval`).
- Time runs fast: `--speed 720` means 1 simulated day per 2 real minutes.
- Reacts to scenario events: `storm_started`, `storm_ended`, `sensor_fault`, `new_crack`, `demo_reset`.
- `--include-real` also fakes the brick node sensors (S01 to S03). Drop it as soon as the ESP32 is live, otherwise the "REAL" sensor is fake.
