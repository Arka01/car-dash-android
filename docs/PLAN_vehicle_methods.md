# Plan: per-vehicle calculation methods ("the app does the R&D")

Status: plan only, nothing built yet (2026-11-07). Nothing here is pushed until the owner says so.

## Why

Car Dash's derived values (engine load, fuel used, battery rules, which sensors to show) were written
around the Tata Tiago and applied to every vehicle. The Royal Enfield Hunter 350 (drive 2026-11-07
10:46) showed that this is wrong for other vehicles:

- "Engine load" read 68 % at idle and 100 % with only ~22 % throttle (its computer uses its own scale).
- A raw FF ("no value") long fuel trim became +99.2 % and raised a false CHECK.
- Oil temperature is listed as supported but always reads 0 °C.
- No fuel figure at all, although the bike reports air flow (MAF).

The same can happen between two cars from different makers.

## Goal

When a customer adds a vehicle, the app works out by itself **how to calculate each value for that
vehicle**, using data from connecting and the first ride. The customer only presses Set up and drives.

## Rules

1. **Nothing old breaks.** Existing cars keep exactly today's methods (pinned). Proven by a replay
   test: saved Tiago drives through old and new code must give identical reports.
2. **Per vehicle.** Every decision is saved in the vehicle's folder (`methods.json`), like
   `fuel_state.json` and `battery_state.json`.
3. **Each decision has a reason.** The app saves the method, the check result and the evidence, and
   shows it in plain words on My car ("How well Car Dash knows your car").
4. **Unsure = keep the default.** A check without enough data says "undecided", never guesses.
5. **One value at a time.** Each phase is its own local commit, replay-tested, pushed only on request.

## How it works

For each derived value there is a **ranked list of methods**, each with a **check**. The app uses the
first method whose check passes and saves the choice in `methods.json`:

```json
{
  "version": 1,
  "pinned": false,
  "engine_load": {"method": "from_manifold", "why": "load 2.1x filling, 100 % at part throttle 15 % of the time", "decided": "2026-11-07", "drives": 1},
  "fuel":        {"method": "maf", "why": "no fuel-rate sensor; MAF idle 0.25 L/h, plausible"},
  "hidden":      {"Oil": "always 0 while the engine ran 7 min", "Commanded throttle actuator": "always 100 %"},
  "alternator":  {"method": "normal", "why": "voltage 13.7 off-throttle vs 13.5 on-throttle"}
}
```

When checks run:

| When | What can be checked |
|---|---|
| Set up (connecting, seconds) | supported sensor list, FF values, vehicle type (asked once: car or bike) |
| First ride, engine running | dead sensors, load vs manifold pressure, fuel plausibility at idle, voltage pattern |
| Every 10th drive | re-check the decisions (a sensor that was dead once may have been a fluke) |

## Checks tested on the saved drives (2026-11-07)

Data: 23 Tiago recordings (2026-10-13 to 2026-11-07, 22 long enough to judge), 3 Mahindra XUV700 diesel drives (2026-10-15),
1 Hunter 350 ride (2026-11-07). Script: candidate checks in Python, read-only, on the recorded CSVs.

### 1. FF values ("no value")

Raw FF decoded with the standard formula (fuel trim +99.2 %, temperature 215 °C, pressure 255 kPa).

| Vehicle | Rows with FF |
|---|---|
| Tiago, all 22 drives | 0 |
| XUV700 | 0 |
| Hunter | 24 (long fuel trim, at high rpm / fuel cut) |

**Method:** an FF value counts as "no reading" (the row keeps the previous value). Never changes the Tiago.

### 2. Dead sensors (listed as supported, never change)

| Vehicle | Constant during the whole drive |
|---|---|
| Hunter | Oil 0 °C, Commanded throttle 100 %, Absolute load 0 % |
| XUV700 drive 10-15 09:35 | Engine torque % = -107 while driving at up to 35 km/h, 3000 rpm |
| Tiago 11-05 10:08 and 11-06 17:39 | Accelerator pedal 0 % **(false alarm: parked, pedal never pressed)** |

The Tiago result shows that a plain "never changes" rule is not enough. **Method:** a sensor only counts
as dead in a drive where it should have moved:
- temperatures: engine ran at least 5 minutes and coolant rose,
- pedal / throttle / load / torque: the vehicle drove (speed > 20 km/h) and rpm varied by > 1000,
- everything else (fuel level, counters, barometric pressure) is never judged.
Hidden sensors are left out of the Dash, the report and the AI prompt; re-checked every 10th drive.

### 3. Engine load

Compared the engine computer's load (PID 04) with the cylinder filling = manifold pressure / outside
pressure, engine warm.

| Vehicle | load ÷ filling (median) | 100 % with filling < 60 % |
|---|---|---|
| Tiago, every drive | 0.62 – 0.67 | 0.0 % |
| Hunter | **2.14** | **14.7 %** |
| XUV700 (diesel) | not applicable (no throttle; manifold pressure is boost) | |

**Method ladder:** engine's load (if ratio 0.4–1.5 and < 2 % saturated) → load from manifold
pressure (filling %) → hidden. Diesel: engine's load only. The Tiago passes on all drives.

### 4. Fuel used

Warm idle (standing, coolant > 70 °C), litres per hour by each method:

| Vehicle | Fuel-rate sensor (5E) | From air flow (MAF) | From manifold pressure (speed-density, VE 0.85) |
|---|---|---|---|
| Tiago | 0.60 – 1.00 | (no MAF) | 0.93 – 1.30 (app already learns the 0.73 scale) |
| Hunter | (none) | **0.25** | 0.28 (engine 0.349 L) |
| XUV700 diesel | (none on these drives) | 2.86 (petrol formula, **wrong** for diesel) | not valid for diesel |

**Method ladder:** fuel-rate sensor (if plausible vs air, as today) → MAF (petrol only) →
speed-density (petrol, needs engine size) → no fuel figure. The Hunter would get a fuel figure from MAF;
the two air methods agree within 12 %. Diesel: fuel-rate sensor or nothing.

### 5. Battery: smart alternator

Median voltage while running, on-throttle (> 15 %) vs off-throttle (≤ 2 %):

| Vehicle | off − on |
|---|---|
| Tiago | 0.8 – 1.7 V on 10 drives; 0.0 – 0.2 V on 3 drives (10-31, 11-01, 11-06: maybe charging a low battery, assumption); rest no data |
| Hunter | 0.2 V |
| XUV700 | undecided (idle only) |

**Method:** smart alternator when off − on ≥ 0.5 V on at least 2 drives; normal when < 0.3 V on at least
2 drives; else undecided (keep the current setting). Tiago is detected as smart (as set by hand in
`car.json`); the Hunter would need a second ride to be decided.

## Phases (each = one local commit + replay test)

| # | What | Visible to the customer |
|---|---|---|
| 0 | `methods.json` + method registry with today's behaviour as default; existing cars pinned; replay test | nothing |
| 1 | FF values ignored; decode PIDs 12, 24, 5D | no false fuel-trim alarm; readable values |
| 2 | Dead sensors learned on the first ride, hidden | no "Oil 0 °C" |
| 3 | Engine load ladder | bike load ~30 % at idle instead of 68 % |
| 4 | Fuel ladder (MAF added) | bike gets fuel used / consumption |
| 5 | Smart alternator auto-detect | no false battery warnings on new cars |
| 6 | Car or bike asked at Set up; bike template (rev limits, battery thresholds) | sensible defaults for bikes |
| 7 | My car card: "What Car Dash learned about this vehicle" | the reasons in plain words |

Later (separate, R&D): optional Deep scan for manufacturer data (Mode 22), results only into the
shared ZIP first.

## Open decisions for the owner

1. **Replay test data:** the repo is public. Commit a few trimmed Tiago drives as test data (VIN removed),
   or keep test data out of the repo and run the replay only locally/CI from a private place?
2. **Car or bike at Set up:** ask once (simple, reliable) or try to guess from the VIN?
3. **Dead sensors:** hide completely, or show greyed out under "All sensors" with the reason?
