"""The timed site control must drive the real scenario event path in order."""

import asyncio

from hadaq_api.sensor_run import SensorRun


def test_sensor_run_schedule_and_replay():
    async def exercise():
        events = []
        run = SensorRun(duration=0.04, schedule=((0.01, "storm_ended"),
                                                  (0.02, "sensor_fault"), (0.03, "new_crack")))
        await run.start(events.append)
        assert run.status(True)["running"]
        await asyncio.sleep(0.05)
        assert events == ["demo_reset", "storm_started", "storm_ended", "sensor_fault", "new_crack"]
        assert not run.status(True)["running"]
        await run.start(events.append)
        await run.stop(events.append)
        assert events[-3:] == ["demo_reset", "storm_started", "demo_reset"]
        assert run.status(True)["elapsed"] == 0

    asyncio.run(exercise())
