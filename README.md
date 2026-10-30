# Car Dash (Android + Android Auto)

Android app that reads live engine data from an OBD-II Bluetooth adapter (vLinker MC)
and shows it on the phone and on the car's Android Auto screen.
Made for a 2023 Tata Tiago 1.2 petrol AMT (OBD protocol 6, CAN 11 bit 500 kbaud); other cars
can be set up with **[NEW CAR]** (any car on CAN 11 bit, protocol 6 or 8).

It does everything the Python dashboard (`car-obd-dashboard`) calculates: torque and power,
fuel left and range, battery and charging (smart-alternator aware), warm-up advice with a voice,
trip computer, temperature trends, pedal sensor check, trouble codes, VIN check, gear calibration,
module scan and replay of recorded drives.

**Current version:** a green-on-black terminal dashboard on the phone (portrait and landscape),
a **DEMO** mode that replays a real recorded drive (no car needed), and fast reading
(RPM and speed several times a second). The Android Auto screen is a simple list and
can't be shown yet (see "Android Auto" below).

---

## How a new version reaches the phone

```
code pushed to GitHub ──▶ GitHub Actions builds the APK (~5 min) ──▶ Releases page ──▶ phone installs it
```

Nothing is built on the laptop. Every push to `main` creates a new release called
**Build N** with `car-dash-N.apk` (install directly) and `car-dash-N.aab` (upload to Google Play,
see "Google Play").

---

## One-time setup

### A. GitHub repository

| Step | Where | What to do |
|---|---|---|
| 1 | github.com | Sign in with the personal account |
| 2 | **+** (top right) → **New repository** | Name `car-dash-android`, **Public**, leave *Add README*, *.gitignore* and *license* **off** → **Create repository** |
| 3 | repo → **Settings** → **Secrets and variables** → **Actions** → **New repository secret** | Name `SIGNING_KEYSTORE_BASE64`, value = the whole content of `~/car-dash-signing/SIGNING_KEYSTORE_BASE64.txt` (one long line) |
| 4 | same page, **New repository secret** again | Name `SIGNING_PASSWORD`, value = content of `~/car-dash-signing/SIGNING_PASSWORD.txt` |

To show the values in the WSL terminal for copying:

```bash
cat ~/car-dash-signing/SIGNING_KEYSTORE_BASE64.txt; echo
cat ~/car-dash-signing/SIGNING_PASSWORD.txt; echo
```

> The signing key proves each new APK comes from the same author, so it installs
> over the old one. Keep `~/car-dash-signing/` safe (back it up). If it is lost, a new key
> works too, but the app must be uninstalled once.

### B. Phone (Poco, Android 16)

| Step | What to do |
|---|---|
| 1 | Pair the vLinker in Android **Settings → Bluetooth** (already done if Car Scanner works) |
| 2 | The first time an APK is opened from the browser, Android asks to **allow installs from this source** → allow |
| 3 | Android Auto developer mode: **Settings → Connected devices → Connection preferences → Android Auto** (the path can differ on Poco; search Settings for "Android Auto") → scroll down → tap **Version** 10 times → OK |
| 4 | (Optional) Same screen: **⋮ → Developer settings → Unknown sources: ON**. Note: this does not make Car Dash appear in the car (see "Android Auto") |

---

## Every new version

| Step | Where | What to do |
|---|---|---|
| 1 | WSL terminal | `cd ~/python_projects/car-dash-android && env -u GIT_ASKPASS -u SSH_ASKPASS git -c core.askPass= push` → username `Arka01`, password = the token (see Troubleshooting). Nothing shows while pasting; press Enter |
| 2 | github.com → repo → **Actions** | Wait for the green tick (about 5 min). Red cross = build failed, see Troubleshooting |
| 3 | Phone browser | Open `github.com/Arka01/car-dash-android/releases` → newest **Build N** → tap `car-dash-N.apk` |
| 4 | Phone | Open the downloaded file → **Install** (or **Update**). If Play Protect warns about an unknown app: **More details → Install anyway** |

---

## Using the app

The screen is a terminal: 30 characters per line, one column in portrait, two in landscape
(just rotate the phone). The text size adjusts so the whole DASH page fits the screen.
The app opens on **DASH**. Tap **[≡]** (top-left) to open the **sidebar** with the screens and
LIVE / STOP; tap an entry, or tap the dimmed screen or press Back to close it.

| Sidebar entry | What it does |
|---|---|
| **[DASH]** | Big speed and gear, RPM and its bar, pedal and throttle bars, temperatures, warnings, then ENGINE / FUEL / AIR / CAR details |
| **[INFO]** | Everything calculated: car, VIN, trouble codes, engine state, torque/power, fuel left and range, battery, trip (see "INFO tab") |
| **[ALL]** | Every value the engine computer reports (about 50), by PID number |
| **DEV** | Car tools (**[CAR] [NEW CAR] [PROBE] [GEARS] [REFUEL] [VOICE]**), **[DEMO] [REPLAY]**, **[ADAPTER]**, the **KEY TEST**, recording, **[SHARE LOG]** and the log |
| **[LIVE]** | Connect to the car (live data) |
| **[STOP]** | Stop whatever runs (LIVE, DEMO, REPLAY, TEST, scan) |

**Top line:** `[≡]`, the screen's name, then `● LIVE` (green) = real data from the car, `◆ DEMO` (amber) = the recorded drive,
`◆ REPLAY` (amber) = a recorded drive of the chosen car, `◇ TEST` (amber) = KEY TEST running,
`◇ SCAN` (amber) = PROBE / NEW CAR scan, `○ OFF` = nothing running. The second line shows the status, how many rounds per second
RPM/speed refresh, and `fast` or `slow` reading.

**Car state** (start of the second line, while LIVE):

| Shows | Means | How the app knows (KEY TEST, 26 Oct 2026) |
|---|---|---|
| `ENGINE ON` (green) | Engine running | RPM 500 or more |
| `STARTING` (amber) | Cranking | RPM above 0 but below 500 |
| `IGNITION ON` (amber) | Key at ON, engine off | RPM 0 and the AMT computer answers |
| `IGNITION OFF` (dim) | Key at LOCK or ACC | AMT and the car's network silent |

- LOCK and ACC can't be told apart from the OBD port (the music system isn't on it).
- After the key is turned off, the engine computer still answers for ~30 s; the app ignores
  that and shows `IGNITION OFF` straight away.
- While `IGNITION OFF`, the app sends nothing to the car (so its modules aren't kept awake),
  only listens, and records the battery voltage every ~2 s. It resumes by itself when the key
  is turned to ON. Connecting with the key at LOCK simply waits.

**Colours:** green = fine, **amber** = warning, **red** = alarm, dim = missing or older than 10 s.
The `>` line under the temperatures lists active warnings.

| Warning | When (same rules as the Python dashboard) |
|---|---|
| SHIFT / REDLINE | RPM above the car's shift / redline RPM (Tiago: 3500 / 4000, the owner's limits) |
| KEEP <2500: COLD | RPM above the warm-up advisor's limit while the engine is cold (`max 2500` next to RPM) |
| COLD START / READY TO MOVE <2500 / WARMING <3500 | Warm-up stage after a cold start (see "Warm-up advisor") |
| WARMING UP / COOLANT HOT / OVERHEATING | coolant below 60 / above 105 / above 115 °C |
| OIL HOT / OIL OVERHEATING | oil above 120 / 135 °C |
| BATTERY LOW CHARGE / NOT CHARGING / OVERCHARGING / RESTING | battery monitor (see "Battery") |
| BATTERY WEAK | voltage dip at the last engine start below 9.6 V |
| FUEL LOW / FUEL RESERVE | fuel left below 8 / 4 litres |
| PEDAL SENSORS DISAGREE | the two accelerator sensors disagree by more than 10 / 20 points for 1.5 s |
| HOT AIR / HOT INTAKE | intake air above 60 °C / more than 25 °C above outside air |
| CAT TOO HOT / FUEL TRIM CHECK | catalyst above 900 °C / long-term fuel trim beyond ±20 % |

**GEAR:** worked out from RPM ÷ speed with the car's measured ratios (Tiago:
149.2 / 78.8 / 51.1 / 37.8 / 29.1 rpm per km/h; `-` = not known yet, run **[GEARS]**). `STOP` = standing (0 km/h), `~` = between
gears (about 0.35 s per gear change). Below 10 km/h, while the AMT's clutch is still closing,
it shows `1`. Reverse can't be told apart from 1st gear (OBD speed is never negative).

**Fast reading (always on):** RPM, speed, load, throttle, pedal and torque are read every round;
other values every 3rd, 10th or 50th round. Up to 6 values are asked for in one question. At
connect each batch is checked once: a batch whose answer doesn't contain exactly its values is
read one value at a time instead (`slow`); while driving, a batch that answers garbled 3 times in
a row does the same. **DEV** shows the result (`fast read: on: …`).

---

## Test checklist

**At your desk (no car):** **[≡] → DEV → [DEMO]** → the values should move like a real drive: speed, gear
changes, the RPM bar turning amber/red near 3500/4000. Try portrait and landscape: everything
on DASH must be visible without scrolling.

**In the car (ignition ON or engine running):**

1. Close **Car Scanner** first. Only one app can talk to the adapter at a time.
2. Open **Car Dash** → allow **Nearby devices** (needed for Bluetooth).
3. **[≡] → DEV → [ADAPTER]:** should show the vLinker. If not, tap it and choose the vLinker.
4. **[≡] → LIVE.** The status goes *Connecting… → Setting up adapter… → Finding sensors… →
   Reading all values once… → Testing fast read… → Connected*.
5. **DEV:** `fast read: on: …` is the goal. The second line on top should show several
   rounds per second (e.g. `8.0/s`).
6. **[≡] → DEV → [SHARE LOG]** and send the text. It contains every value plus the connection log.

The AMT computer (7E9) is listed in the log but not read: it refuses direct requests, and its
9 values only repeat the engine's (no gear).

---

## Cars

Each car has its own settings and files on the phone (`files/cars/<car>/`), like the Python
dashboard's `cars/` folder: `car.json` (settings), `vin.txt`, `battery_state.json`,
`fuel_state.json`, `probe.json` and `recordings/`. The Tiago and the XUV700 come with the app
(their `car.json` from the Python project); a `car.json` on the phone is never overwritten.

| Key (DEV tab) | What it does |
|---|---|
| **[CAR]** | Choose the car whose settings and folder are used |
| **[NEW CAR]** | With the adapter in the new car (ignition ON, engine running is best): finds the protocol by itself, reads the VIN (maker from its first 3 characters) and fuel type, scans every computer, then asks name, fuel, tank, engine size and RPM limit (all optional) and sets the car up. A car set up before is recognised by its VIN instead |
| **[PROBE]** | Read-only scan of every computer of the chosen car (what each supports, gear PID A4, stored and pending trouble codes, names) → `recordings/probe_<date>_<time>.json`, summary in the log |
| **[GEARS]** | Measures RPM per km/h in each gear from the car's recorded drives and offers to save them (turns the gear display on). Drive through every gear first |
| **[REFUEL]** | After a fill-up: litres from the receipt, tick **full** if filled to the automatic cut-off. Goes into the running LIVE drive, or into the next one |
| **[VOICE ON/OFF]** | The warm-up advisor's spoken messages |

**VIN check at every LIVE connect** (as in the Python dashboard): a car with a saved VIN is
switched to automatically; the chosen car learns its VIN the first time; a VIN that belongs to
no car set up stops the run, so another car's drive never lands in the wrong folder.
Recording starts only after this check.

Only read requests are ever sent (services 01, 03, 07, 09 and adapter commands); nothing on the
car is changed or cleared.

---

## INFO tab

| Part | What it shows |
|---|---|
| CAR | Name, VIN, stored trouble codes (codes only, no descriptions), gear ratios, protocol |
| ENGINE | State (TOO HOT / HOT, or the warm-up stage), torque (Nm) and power (bhp) with peaks, heat soak, boost, temperature trends per minute |
| FUEL | Litres left ± uncertainty, range, L/100 km now / this trip / average, fuel flow and its source, float sensor reading, notes |
| BATTERY | Charging state and volts, engine computer vs port voltage, the dip at the last engine start |
| TRIP | Distance, average speed, acceleration (g), RPM per km/h, best 0-60 / 0-100 km/h, top speed and RPM, pedal sensor check, km driven with the check-engine light on |

**Torque:** the Tiago's own torque % is mapped to Nm with its `torque_calibration`
(12 % = 0 Nm at idle, 56 % = 100 Nm at full throttle, from the 24 Oct drive), ±10 %.

**Fuel left:** combines the engine's fuel rate, an estimate from the air the engine takes in,
the tank's float sensor (one-minute median) and fill-up receipts (**[REFUEL]**). The first LIVE
run starts from the float sensor; the estimate improves after fill-ups to full.

**Battery:** while running: CHARGING / LOW CHARGE / NOT CHARGING / OVERCHARGING. The Tiago has a
smart alternator (voltage drops to ~12.2 V while accelerating, by design), so it's judged by the
highest voltage in the last 5 minutes (below 13.2 V = LOW CHARGE, below 12.8 V = NOT CHARGING).
Engine off: SETTLING (20 min), then RESTING with the estimated charge. At each start the lowest
voltage is kept (`battery_state.json`): 10.0 V or more is good, below 9.6 V weak. While the
engine is off the app reads only RPM and voltage, several times a second, to catch that dip.

**Warm-up advisor:** after a cold start (coolant below 70 °C): COLD START (wait for the idle to
settle, up to 60 s, max 2000 rpm) → READY TO MOVE (< 2500 rpm) → WARMING (coolant 70 °C, < 3500 rpm)
→ READY TO PUSH (coolant 80 °C and oil 85 °C for a minute). Each change is spoken (phone voice).

---

## Drive recordings

**Every LIVE run is recorded**, there is no switch. The header shows `REC` while recording.

| File | Contents |
|---|---|
| `drive_<date>_<time>.csv` | One row per reading round (several per second): `t` (seconds), `time`, then every value. Same column names as the Python dashboard's recordings (incl. the calculated `Torque` and `Power`), so its analysis and `--replay` work on them. Extra columns at the end: `Car state`, `Gear (estimated)`, `O2 S1 lambda`, `Torque demanded %`, `Coolant (sensors)`, `Manifold pressure fine`, `Exhaust flow (?)`, then `PID xx` for anything else the car reports |
| `drive_<date>_<time>_profile.json` | Car, protocol, trouble codes, which values the car supports, the fast-read result, one reading of everything |

- A reconnect during the drive continues the same file. A run that never connected leaves no file.
- Files are kept per car in `files/cars/<car>/recordings/` (drives from builds 5-6 were moved
  to the Tiago's folder).
- **[≡] → DEV → [SHARE DRIVE]** sends the chosen car's newest drive (both files), e.g. by email or to Drive.
- **[≡] → DEV → [REPLAY]** plays one of the chosen car's drives again, with all calculations (nothing is saved).
  About 1 MB per 5 minutes.
- The screen stays on while LIVE / DEMO / TEST runs: a locked phone may pause the app,
  which would leave gaps in the recording.
- To analyse on the laptop, copy the files into the Python project's
  `cars/<car>/recordings/` folder.

---

## KEY TEST (DEV tab)

Records, about once a second, what the OBD port shows at each key position, so the app can
later tell LOCK / ACC / ON / START / RUN apart. Each row: battery voltage at the port
(3 readings), which car computers answer, the engine's RPM, and how many messages the car's
network carries (the adapter only listens).

1. Key at **LOCK**, Car Scanner closed. **[≡] → DEV → [START TEST]**, wait for rows to appear.
2. Tap the position **every time you turn the key**, wait ~20 s at each:
   **[LOCK]** → **[ACC]** → **[ON]** → **[START]** (tap as you crank) → **[RUN]** (idle ~30 s)
   → turn the engine off and tap where the key ends up (**[LOCK]** or **[ACC]**), wait ~2 minutes
   (the engine computer stays awake for a while after the engine stops).
3. **[STOP TEST]**, then **[SHARE TEST]** to send the CSV file.

Files are kept in the app (`files/dev/keytest_<date>_<time>.csv`); **[SHARE TEST]** sends the newest.

---

## Google Play (internal testing)

Needed for Android Auto: it only shows Car Dash when installed from Google Play. The app stays
private (internal testing, only your own Google account). Privacy policy: `PRIVACY.md`
(link: `https://github.com/Arka01/car-dash-android/blob/main/PRIVACY.md`).

**Each new version:** Play Console → Car Dash → **Test and release → Testing → Internal testing**
→ **Create new release** → upload `car-dash-N.aab` from the GitHub release → **Next** →
**Save and publish**. The phone updates from the Play Store.

The Play version is signed by Google, so it can't install over an APK from GitHub (and the
other way round): uninstall first, which deletes the app's data (share drives first).

---

## Android Auto

Car Dash shows on the car screen only when installed from **Google Play** (internal testing, see
"Google Play"); Android Auto hides apps of this kind installed from GitHub (works since build 8,
29 Oct 2026). The car screen uses Google's list layout (Android Auto draws it in its own style);
the Tiago's screen showed about 3 list rows (build 9), so build 10 uses a grid of tiles.

- **No buttons, nothing tappable** (the Tiago's touch screen makes random ghost taps). Reading
  starts by itself when Car Dash opens in the car, and stops when the phone is unplugged, but only
  if the car started it (a LIVE run started on the phone keeps going).
- It needs two one-time steps on the phone: allow **Nearby devices**, and choose the adapter
  (**[≡] → DEV → [ADAPTER]**). Until then the Status tile says `Set up on phone`.
- **Grid of tiles** (since build 10), one value each, scrolls: Status, RPM, Coolant, Oil, Intake air,
  Catalyst, Manifold, Baro, Battery, Speed, Gear, Load, Throttle, Pedal, Fuel rate, Torque, Power,
  Timing, Fuel trim, Fuel left, Range. Android Auto decides the columns and the most tiles allowed
  (cut from the bottom). Values in the phone's terminal green, amber / red when they warn, `--`
  when missing or older than 10 s.
- Refresh: asked for 4 times a second; Android Auto decides how many it shows (about 1–2 per second
  for this kind of screen).

---

## Troubleshooting

| What you see | What to do |
|---|---|
| Push fails without asking for a password (*Invalid username or token*) | VS Code's terminal answers for git. Use the long push command from "Every new version"; it makes git ask you |
| Push asks for a password | Paste a **fine-grained personal access token**, not the account password: github.com/settings/personal-access-tokens → only `car-dash-android`, permissions **Contents** and **Workflows** = *Read and write*. GitHub shows the token once; keep it in a password manager |
| Push: *403 Permission denied* | The token lacks access: check **Repository access** includes this repo and **Contents** + **Workflows** are *Read and write* |
| Actions: red cross, *Secret SIGNING_KEYSTORE_BASE64 is missing* | Do setup step A3/A4 again, then on the Actions page **Re-run jobs** |
| Actions: red cross, anything else | Open the failed run → **build** → the red step; copy the error lines |
| Phone: *App not installed* / *conflicts with an existing package* | The APK was signed with a different key. Uninstall Car Dash once, then install again |
| Car Dash missing on the car screen | Expected for now: see "Android Auto" |
| *Connection lost, retrying in 5 s* | Another app (Car Scanner) holds the adapter, the adapter is unplugged, or Bluetooth is off |
| *No car computer answered (ignition on?)* | Turn the ignition on, then Stop → Connect |
| *VIN isn't the TIAGO's or any car set up* | You're in another car: **[NEW CAR]**, or **[CAR]** to choose it |
| *Protocol N: the app only reads CAN 11-bit cars* | This car uses another OBD protocol (older or 29-bit CAN); not supported by the app yet |
| Gear shows `-` | The car's gears aren't known: drive through every gear, then **[≡] → DEV → [GEARS]** |
| Values show `NO DATA` or `refused` | That computer didn't answer that value this round; share the text |
| Values are dim | Not updated for 10 s or more (or never answered): the link has stalled |
| **DEV** says `fast read: off: …` | Batching didn't pass the self-test; values are read one at a time (slower, still correct). Share the log |

---

## Project files

| File | Purpose |
|---|---|
| `app/src/main/java/.../Dash.kt` | The hub: live, demo or replay data in, the run's car / calculations / recording, one snapshot out to both screens |
| `app/src/main/java/.../ui/Terminal.kt` | The phone's terminal look (sidebar with DASH / INFO / ALL / DEV, portrait and landscape) |
| `app/src/main/java/.../cars/` + `assets/cars/` | Car settings (`car.json`, same format as the Python project), per-car folders, VIN, NEW CAR names |
| `app/src/main/java/.../logic/` | The Python dashboard's calculations: `Pipeline` (runs them all each round), `Rules` (warning levels), `Calculations` (gear, torque/power, heat soak, boost, hot state), `TripComputer`, `PedalCheck`, `Trends`, `BatteryMonitor`, `FuelTracker`, `WarmUpAdvisor`, `GearCalibration` |
| `app/src/main/java/.../Speech.kt` | Says the warm-up messages (phone voice) |
| `app/src/main/java/.../DriveRecorder.kt` | Records every LIVE run to CSV + profile JSON (Python dashboard format) |
| `app/src/main/java/.../demo/DemoPlayer.kt` + `assets/demo_drive.csv` | DEMO (a Tiago drive) and REPLAY (the app's own recordings) |
| `app/src/main/java/.../obd/Probe.kt` | Questions to every computer: supported values, VIN, trouble codes, fuel type, module scan |
| `app/src/main/java/.../obd/Elm327.kt` | Sends text commands to the adapter, joins multi-part answers |
| `app/src/main/java/.../obd/Pids.kt` | Names and formulas for each sensor value |
| `app/src/main/java/.../obd/ObdSession.kt` | The connection: protocol, find sensors, VIN check, fast reading with self-test, engine-off fast voltage, reconnect; KEY TEST; PROBE / NEW CAR |
| `app/src/main/java/.../obd/Adapters.kt` | Remembers which paired Bluetooth device is the adapter |
| `app/src/main/java/.../Report.kt` | The plain text sent by **[SHARE]** |
| `app/src/main/java/.../MainActivity.kt` | Starts the phone screen; Bluetooth permission, adapter choice, sharing, dialogs (car, NEW CAR, REFUEL, GEARS, REPLAY) |
| `app/src/main/java/.../car/` | Android Auto: service, the grid screen (`DashGridScreen.kt`), auto-start (`CarControls.kt`); tile icons in `res/drawable/ic_tile_*.xml` |
| `app/src/main/AndroidManifest.xml` | Tells Android what the app is and what it may use (Bluetooth, Android Auto) |
| `app/build.gradle.kts` | App id, Android versions, libraries, signing |
| `.github/workflows/build.yml` | The GitHub Actions build that makes the APK and the release |
