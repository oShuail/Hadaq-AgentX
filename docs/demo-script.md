# Demo video script (about 2 minutes)

## Before recording

```bash
make up
make api          # terminal 1
make agents       # terminal 2 (real LLM in .env)
make web          # terminal 3
make sensors      # terminal 4 (without --include-real if the brick node is live)
make vision       # terminal 5
make camera       # terminal 6
```

Open the **Activity** tab and press **Reset demo**. Fill the weather cache once (`make sim`). Browser full screen, 1080p, notifications off. The clock starts at 16:45 (golden hour); keep it there for the shots.

## Shots

| Time | Screen | Voice-over (short) |
| --- | --- | --- |
| 0:00 | Views (bottom left): **Overview**, then **At-Turaif** (the camera flies) | "At-Turaif is one of the largest mud-brick districts in the world. Rain is its biggest short-term threat." |
| 0:15 | **Qasr Salwa** view: the readings appear pinned to the facade next to their wall zones. Then click the building: its own page opens with the model, wall condition grid, sensors and camera | "Hadaq is a digital eye on each building: live sensors, a camera, and AI agents." |
| 0:25 | Sensor list: one REAL, others SIM. Cut to the water-pour clip | "This sensor is real, inside a mud brick we made. The others are simulated and labeled." |
| 0:40 | **Activity** tab: press **Start storm** (or the phone panel, in shot if possible) | "We replay a real past storm in Riyadh." |
| 0:50 | Activity: moisture anomaly, agent investigating; wall zones start animating | "No one typed anything. The agent noticed rising moisture and decided to run a simulation." |
| 1:05 | Wall colors, base row turns dark; switch to Risk layer | "The base stays wet for days; that is where mud walls fail. Risk uses the ABC method from conservation science." |
| 1:20 | **Activity**: press **New crack**; back on **Building twin** the camera frame shows a grown crack and a box appears on the wall | "The camera sees the crack has grown, which feeds the risk score." |
| 1:30 | **Agent** tab: "What if we fix the drainage?" | "Ask in Arabic or English. Every number links to its evidence." |
| 1:45 | Click an evidence chip; recommendation "waiting for expert approval" | "Hadaq proposes, the conservator decides." |
| 1:55 | Click any other building ("No twin yet"), then drag the sun on the clock to night: the floodlights come on | "One building today, the same pipeline scales to every building." |

## Honesty lines to keep

- Say "simulated" for simulated sensors and edited photos.
- Say "the model is not yet calibrated" once.
- Never say Hadaq predicts when a storm will come.
- The city around At-Turaif is a context model from open data: heights are estimates and the ruins filling At-Turaif are illustrative.
