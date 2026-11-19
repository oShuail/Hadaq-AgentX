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

Press **Reset demo**. Fill the weather cache once (`make sim`). Browser full screen, 1080p, notifications off.

## Shots

| Time | Screen | Voice-over (short) |
| --- | --- | --- |
| 0:00 | Overview of Diriyah, slow fly to At-Turaif | "At-Turaif is one of the largest mud-brick districts in the world. Rain is its biggest short-term threat." |
| 0:15 | Click the focus building, panel opens | "Hadaq is a digital eye on each building: live sensors, a camera, and AI agents." |
| 0:25 | Sensor list: one REAL, others SIM. Cut to the water-pour clip | "This sensor is real, inside a mud brick we made. The others are simulated and labeled." |
| 0:40 | Press **Start storm** (phone panel in shot if possible) | "We replay a real past storm in Riyadh." |
| 0:50 | Activity: moisture anomaly, agent investigating; wall zones start animating | "No one typed anything. The agent noticed rising moisture and decided to run a simulation." |
| 1:05 | Wall colors, base row turns dark; switch to Risk layer | "The base stays wet for days; that is where mud walls fail. Risk uses the ABC method from conservation science." |
| 1:20 | Press **New crack**; camera frame shows a grown crack | "The camera sees the crack has grown, which feeds the risk score." |
| 1:30 | Chat: "What if we fix the drainage?" | "Ask in Arabic or English. Every number links to its evidence." |
| 1:45 | Click an evidence chip; recommendation "waiting for expert approval" | "Hadaq proposes, the conservator decides." |
| 1:55 | Overview, other buildings "No twin yet" | "One building today, the same pipeline scales to every building." |

## Honesty lines to keep

- Say "simulated" for simulated sensors and edited photos.
- Say "the model is not yet calibrated" once.
- Never say Hadaq predicts when a storm will come.
