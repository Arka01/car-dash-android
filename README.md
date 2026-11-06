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

**User manual (for customers):** [`docs/MANUAL.md`](docs/MANUAL.md), PDF: [`docs/Car-Dash-Manual.pdf`](docs/Car-Dash-Manual.pdf)
(rebuild the PDF with `python docs/make_pdf.py docs/MANUAL.md docs/Car-Dash-Manual.pdf`).

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

**Look (since build 19):** a modern layout in the terminal colours (green on black, amber / red
for warnings): cards, readable labels, real buttons, big numbers in monospace with a subtle glow.
All screens have it since build 20 (Dash and Drives since 19). Buttons that were `[NAME]` keys are
now real buttons with the same names (e.g. `[DEMO]` → **Demo**). The sidebar shows the **car in use** at
the top (tap it to switch cars or **+ Add a new car**; not while LIVE runs), then **Dash, Info,
Drives, My car, Settings, Support**, then **Start LIVE / Stop** and **Update app**. The top bar shows ☰ (sidebar),
the screen, the mode (● LIVE / ◆ DEMO / ○ OFF) and the time; below it the car state, car and rate.

**Dash:** a main card (speed, gear, RPM and its bar: green, amber from the shift point or near the
cold-engine limit, red above the redline), one alert card (✓ All normal, or the warnings), then
value cards two per row: coolant (warm-up stage while cold), oil, intake air (trend per minute),
battery (monitor state), fuel left (range), consumption now (trip), torque (power), trip (average).
Landscape: main + alerts left, value cards right.
The app opens on **Dash**. **Back** (button or swipe) returns to the previous screen (a drive's report
→ the drive list, an open sidebar closes first); on Dash with nothing before, it closes the app. Tap **[≡]** (top-left) to open the **sidebar** with the screens and
LIVE / STOP; tap an entry, or tap the dimmed screen or press Back to close it.

| Sidebar entry | What it does |
|---|---|
| **Car (top)** | The car in use; tap it to switch cars or **+ Add a new car** |
| **Dash** | Speed, gear, RPM bar, warnings, value cards (tap **Fuel left** to log a fill-up); scroll down for **Details**: engine (fault codes, torque, power, heat soak, boost), temperature trends, fuel (with **Log a fill-up**), battery, trip, then **All sensors** (raw values, folded) |
| **Drives** | Every recorded drive with its report: AI summary, **Ask about this drive**, **Share**, **Replay** (see "Drive reports"); **Ask AI** (or long-press) = select mode for asking about up to 5 drives together |
| **My car** | This car: details and VIN, **gears learned automatically**, fuel (tank size, average, fill-ups), engine limits (editable), **Scan car computers** |
| **Settings** | Connection (choose the adapter), voice on/off, the on-phone AI (model, advanced settings), demo drive, version and updates |
| **Support** | Connection status, crash report (if the app closed unexpectedly), connection log and **Share log** |
| **Start LIVE / Stop** | Connect to the car (live data) / stop whatever runs |
| **Update app** | Opens Car Dash in the Play Store |

**Top line:** `[≡]`, the screen's name, then `● LIVE` (green) = real data from the car, `◆ DEMO` (amber) = the recorded drive,
`◆ REPLAY` (amber) = a recorded drive of the chosen car, `◇ TEST` (amber) = key test running (no longer reachable),
`◇ SCAN` (amber) = PROBE / NEW CAR scan, `○ OFF` = nothing running. The second line shows the status, how many rounds per second
RPM/speed refresh, and `fast` or `slow` reading.

**Car state** (start of the second line, while LIVE):

| Shows | Means | How the app knows (KEY TEST, 26 Oct 2026) |
|---|---|---|
| `ENGINE ON` (green) | Engine running | RPM 500 or more |
| `STARTING` (amber) | Cranking | RPM above 0 but below 500, coming from standstill |
| `STOPPING` (amber) | Engine switched off, spinning down | RPM below 500 after the engine ran |
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
149.2 / 78.8 / 51.1 / 37.8 / 29.1 rpm per km/h; `-` = not known yet: the app learns them by itself from your drives). `STOP` = standing (0 km/h), `~` = between
gears (about 0.35 s per gear change). Below 10 km/h, while the AMT's clutch is still closing,
it shows `1`. Reverse can't be told apart from 1st gear (OBD speed is never negative).

**Fast reading (always on):** RPM, speed, load, throttle, pedal and torque are read every round;
other values every 3rd, 10th or 50th round. Up to 6 values are asked for in one question. At
connect each batch is checked once: a batch whose answer doesn't contain exactly its values is
read one value at a time instead (`slow`); while driving, a batch that answers garbled 3 times in
a row does the same. **Support** shows the result (`Fast reading: on: …`).

---

## Test checklist

**At your desk (no car):** **☰ → Settings → Try a demo drive** → the values should move like a real drive: speed, gear
changes, the RPM bar turning amber/red near 3500/4000. Try portrait and landscape: everything
on DASH must be visible without scrolling.

**In the car (ignition ON or engine running):**

1. Close **Car Scanner** first. Only one app can talk to the adapter at a time.
2. Open **Car Dash** → allow **Nearby devices** (needed for Bluetooth).
3. **☰ → Settings → Connection:** should show the vLinker. If not, tap **Choose adapter**.
4. **[≡] → LIVE.** The status goes *Connecting… → Setting up adapter… → Finding sensors… →
   Reading all values once… → Testing fast read… → Connected*.
5. **☰ → Support:** `Fast reading: on: …` is the goal. The second line on top should show several
   rounds per second (e.g. `8.0/s`).
6. **☰ → Support → Share log** and send the text. It contains every value plus the connection log.

The AMT computer (7E9) is listed in the log but not read: it refuses direct requests, and its
9 values only repeat the engine's (no gear).

---

## Recording in the background

While LIVE runs, Car Dash shows a quiet notification **"Recording your drive"**. Android then
treats the app as active and doesn't pause or close it when it isn't on screen (phone locked,
music or maps in front), and the phone's processor keeps reading the car with the screen off.
The notification goes away when LIVE stops (Stop, or unplugging when Android Auto started it).
Allow **notifications** when asked (Android 13+), or the recording runs without the notification
showing. If Android refuses the background recording, the log (Support) says so.

---

## Drive reports

After every LIVE drive the app works out a report from the recording (no AI; the checks done by
hand on the Tiago's drives, 24–30 Oct 2026) and saves it next to the drive as
`drive_<date>_<time>_report.json`. **[≡] → DRIVES** lists the chosen car's drives, newest first, with
their worst verdict (`✓ OK`, `▲ WATCH`, `■ CHECK`); tap one for its report, **[BACK]** or Back returns.
Older recordings get a report the first time DRIVES is opened. Recordings with fewer than 10 rows
show `too short`.

| Part | Contents | Verdicts |
|---|---|---|
| VERDICT | All WATCH / CHECK findings first, then key OKs | |
| DRIVE | Distance, average and top speed, idling %, max rpm, time over the redline | over redline: 0 s OK, ≤5 s WATCH, more CHECK |
| FUEL | Litres used, L/100 km, vs this car's average | more than 15 % above the average of earlier drives (≥2 km, needs 3) = WATCH |
| WARM-UP (cold start only, coolant < 70 °C) | Start coolant, time to coolant 70 / 80 °C and oil 85 °C, max rpm while cold | above 2500 rpm while cold: 0 s OK, ≤10 s WATCH, more CHECK |
| BATTERY | Resting voltage before the start (charge %), start dip (+ readings), charging, time below 12 V, highest, at end | resting ≥75 % OK, ≥25 % WATCH; dip ≥10.0 V OK, ≥9.6 V WATCH (needs 5 readings in 2 s); smart alternator: lowest 5-min peak ≥13.2 V OK, ≥12.8 V WATCH; below 12 V while running (30 s after a start): <30 s OK, <5 min WATCH; above 15.0 V for 5 s WATCH, 15.5 V CHECK |
| ENGINE | Peak torque / power, coolant and oil max, intake range, long fuel trim, catalyst, trouble codes | coolant >105 / oil >120 °C WATCH (CHECK >115 / >135); trim ±10 % WATCH, ±20 % CHECK; catalyst >900 °C CHECK; any trouble code CHECK |
| DATA | Rows and rows per second, gaps over 2 s while the engine runs (with the ignition off the app writes a row every ~2 s on purpose) | a gap = WATCH |

---

## Spoken drive summary (on-phone AI)

With a model imported (see "On-phone AI"), the AI summarises each drive and **reads it aloud**:

1. Get in, plug in the phone (Android Auto opens Car Dash, reading starts by itself), drive.
2. Switch the engine off and wait in the car. After **20 s with the ignition off** the app works out the
   drive report, the AI writes the summary (~30 s on CPU) and the short version is spoken through the
   car speakers, **only while the phone is still connected to the car** (Android Auto). Unplugged =
   in a hurry or a break: the summary is saved but not spoken, and the log says so (owner's rule,
   2026-11-02). **[WRITE AGAIN]** in Drives still reads it aloud. Starting the engine again stops it.
   It starts with a greeting made by the app ("Good evening! Your drive is complete: 15 minutes and
   3.5 kilometres."), then the AI's witty line about the kind of drive (standing still / traffic jam /
   city / highway / mixed, + spirited; from the report), then the summary. Only when the range is below
   **50 km**, the AI also gets **FUEL NOW** (litres left, range) and writes a separate **REFUEL** part
   (its own words, with the km left), spoken right after the witty line and shown in amber; if it
   skips it, the app says "Fuel is getting low: about N kilometres left." Kept separate because inside
   the summary the reminder got glued onto the battery ("get the battery topped up"). The spoken part ends with the AI's own goodbye,
   fitting the time of day (morning / afternoon / evening / night).
3. If the phone was unplugged first, the summary is written (silently) when LIVE stops instead.

The summary is in **Drives → the drive → AI summary** (spoken part, then the longer one).
**[AI SUMMARY]** / **[WRITE AGAIN]** there writes one for any drive (also older ones) and reads it
aloud. Voice follows **Settings → Voice**. Saved as `drive_<date>_<time>_summary.txt`.

The AI gets the report **with the app's verdicts** (it mustn't judge numbers itself, and only the
WATCH / CHECK lines of that drive count as findings), short notes
about the car (`car_notes.txt` in the car's folder: smart alternator, battery history, normal
temperatures, unreliable outside-air reading, the owner's limits) and one line per earlier drive.

---

## On-phone AI

**Talk** (`ai/Talk.kt`, `ai/Mic.kt`): **Talk** = first tile on the car screen (top left; car mic via `CarAudioRecord`, car app
API level 5; redraws paused 2 s after a tap; every tap, permission result and error in `talk_log.txt`). Car screen only: the phone Dash's **🎤 Talk** (`Talk.startPhone`) was removed 2026-11-05 (owner:
not for use while driving; on the phone, Drives → 🎤 Ask a question instead). Needs `androidx.car.app:app-projected`
(`CarAudioRecord.create` loads `ProjectedCarAudioRecord` by reflection; without it: IllegalStateException). 2026-11-06: the car mic gives speech at ~3 % of full scale and all recognizers said "no words": the
recording is boosted (peak → 24000, at most ×20) and kept as the car's `talk_last.wav` (last one only; in the drive ZIP). Needs Android 13+ (speech recognition from recorded sound),
`RECORD_AUDIO` (asked on first use) and the model. Steps: listen (16 kHz PCM; background noise from the first 200 ms,
speech = 2.5× that and > 300 for 100 ms; stops after 1.2 s quiet or 12 s; nothing in 5 s = quiet stop, e.g. a ghost tap;
music paused with audio focus) → **the model hears the WAV itself** (since 0.1.45: `Content.AudioBytes` + `EngineConfig.audioBackend` = CPU, loaded only for Talk; it writes `HEARD:` and `ANSWER:`, both logged; reason: the recognizers below said "no words" for every recording although `talk_last.wav` was clear speech). Only if the model fails with sound (a file without its audio part): speech to text (`SpeechRecognizer` with `EXTRA_AUDIO_SOURCE`: the standard service en-IN (may go
online), then on-device en-IN, then on-device default; "no words" moves on to the next), then the model with the text → while LIVE, `DriveService` adds the
`microphone` foreground-service type while listening (Android 14 "while in use"; result logged) → the model (preloaded while listening) gets
**LIVE NOW** (`Talk.live`: speed/rpm/gear, temperatures with `Rules` levels, engine state, battery, fuel/range,
consumption, trip, alerts, trouble codes) + the question, answers in ≤ 120 tokens, greedy (topK 1) → `ai/NumberCheck`: numbers not in the prompt (rounding, D tags, clock
times and small counts allowed) → those sentences aren't spoken → `Speech.reply` (even with the voice off). Ask and
several-drives answers are greedy too and show ⚠ for such numbers (no retry: no extra delay). **Talk runs while LIVE reads the car** (the only AI use that does). Each step goes to the car's
`talk_log.txt` (24-hour times, which recognizer, timings, reading rate before/after), included in drive ZIPs, so its
effect on the reading rate can be checked against the drive CSV.

A language model that runs **on the phone itself** (Google's LiteRT-LM engine, the one inside the
AI Edge Gallery app). Nothing leaves the phone; Car Dash still has no internet access.

**One-time setup:**
1. On the phone's browser: `huggingface.co/litert-community/gemma-4-E2B-it-litert-lm` → **Files and
   versions** → `gemma-4-E2B-it.litertlm` (2.59 GB, the standard Android CPU/GPU version; licence
   Apache-2.0, no login needed) → download (Wi-Fi). Not the `-gpu`, `-web`, `.task` or NPU files.
2. **☰ → Settings → AI → Import model** → pick the file in Downloads. Car Dash copies it into its own storage
   (about a minute); afterwards the copy in Downloads can be deleted. **[REMOVE]** deletes it again.

**Use:** in **Drives → a drive → Ask about this drive → 🎤 Ask a question**, speak the question (Android's own "Speak now" window,
`RecognizerIntent`, en-IN; since 2026-11-05 instead of typing). That drive's report, the
car notes, earlier drives and the fuel left right now ("FUEL NOW") go with it, so "How was my drive?" or "Why is the battery a WATCH?"
work (it only knows what's in that report). **Several drives:** **Drives → Ask AI** (or long-press a card) → tick up to 5
(**Last 5 / Clear / ✕**, back gesture leaves) → **Ask →** → **🎤 Ask a question** (spoken). The prompt (`DriveSummary.askSeveral`) has the
car notes, FUEL NOW, **TOTALS AND TRENDS** calculated in code (`logic/DriveTotals`: totals, overall L/100 km = litres ÷ km,
best/worst drive ≥ 2 km, trends earlier half vs later half: fuel ±10 %, resting battery ±0.15 V, start dip ±0.3 V; warm-up
average; highest speed/rpm/coolant; each WATCH/CHECK with the drives it came up on), then each drive's report without DATA (every heading tagged `D5 DRIVE`, `D5 BATTERY` …, `(oldest)` / `(newest)` on the
first line, an empty VERDICT written as "all OK, nothing to watch", dates without the year: 2026-11-05 the model gave D5 D4's
numbers and wrote "202064"),
tagged D1 (oldest) … D5; one line per drive instead if the message would pass 8,500 characters. Answer up to 700 tokens.
**Share** there = one ZIP `CarDash_<n>_drives_from_<date>_<time>.zip`: each chosen drive's CSV, profile, report, summary, the
car's `fuel_state.json` and, after a question, `ai_answer.txt` (D tags → drives, question, answer, time taken, system instruction, message).
All times shown are 12-hour (`logic/TimeFormat`); recordings and logs keep 24-hour. With a model imported, **Import model** reads
**Replace model**; the AI's settings are under **Settings → AI → Advanced settings**. **Show prompt** (under an answer) shows exactly what the
model got: the system instruction and the whole message (for Ask: car notes, earlier drives, the
drive report, FUEL NOW and the question; for drive summaries: the drive report, FUEL NOW only when the range is under 50 km, plus the car notes only when there's a WATCH/CHECK to explain, no earlier drives, because the small model repeated their findings as this drive's). The answer appears word by word, then the time it took.
The model is loaded when needed (~10 s the first time, ~2–3 GB of memory) and unloaded after
3 minutes without questions. It doesn't run while LIVE reading runs.

Settings are under **Model → Advanced settings ▾** (closed by default):

| Setting | What it does | Default |
|---|---|---|
| temperature | 0 = factual and repeatable … 1+ = more varied | 0.30 |
| top-K / top-P | How many candidate words it considers at each step | 40 / 0.95 |
| max answer | Longest answer, in tokens (~¾ word each) | 1024 |
| runs on | GPU (usually faster) or CPU; falls back to CPU if the GPU fails | GPU |
| system prompt | Fixed instructions given before every question | "experienced car mechanic…" |

**If the app closes by itself** (crash): reopen Car Dash and go to **☰ → Support**. A **The app closed unexpectedly** box
shows why Android closed it (native crash, out of memory, …), the last AI step reached and the
phone's memory; **Share report** sends the full report as text, **Clear** hides it. Reports are
also kept in the app (`files/dev/crash_<date>_<time>.txt`). Android 11+ needed for the native details.

---

## Cars

Each car has its own settings and files on the phone (`files/cars/<car>/`), like the Python
dashboard's `cars/` folder: `car.json` (settings), `vin.txt`, `battery_state.json`,
`fuel_state.json`, `probe.json` and `recordings/`. The Tiago and the XUV700 come with the app
(their `car.json` from the Python project); a `car.json` on the phone is never overwritten.

| Where | What it does |
|---|---|
| **Car at the top of the sidebar** | Tap it: the cars set up on this phone (tap one to switch; screens, drives and settings follow) and **+ Add a new car**. Not while LIVE runs |
| **+ Add a new car** (sidebar) | A short what-you-need window, then **Scan**. With the adapter in the new car (ignition ON, engine running is best): finds the protocol by itself, reads the VIN (maker from its first 3 characters) and fuel type, scans every computer, then asks name, fuel, tank, engine size and RPM limit (all optional) and sets the car up. A car set up before is recognised by its VIN instead. After **Set up**, LIVE starts by itself. The engine computer is found by itself: 7E8 as in most cars, else (e.g. a motorbike) the computer that has RPM and the most values; a computer without a gearbox computer (7E9) counts the engine answering as ignition on. A scan that ends without the questions (can't reach the adapter, no answer, other protocol) says why in a window |
| **My car → Scan car computers** | Read-only scan of every computer of the chosen car (what each supports, gear PID A4, stored and pending trouble codes, names) → `recordings/probe_<date>_<time>.json`, summary in the log |
| **My car → Gears** (automatic) | After each drive (and when Car opens) the app measures RPM per km/h in each gear from all recorded drives. When it has something new (no gears yet, a gear measured for the first time from 150+ steady readings, or a change over 2 %) **My car** shows **New gears learned** with **Save / Not now**, and the sidebar shows **My car ●**. Unmeasured gears are estimated from the specs |
| **My car → How well Car Dash knows your car** (automatic) | Self-checks per car, kept in `fuel_state.json`: **fuel left** (learning until 2 full fills, then ±σ; last fill's error; learned tank), **fuel gauge** (every 10 % the gauge drops with the engine running, gauge litres vs counted litres in `gauge_checks`; median of the last 3 outside 0.75–1.33 = off → gauge variance ×4 while the fuel rate is OK; fill-up curve learning clears it; drives without the app are estimated into the check, which is skipped if over 30 % estimated), **fuel use reading** (PID 5E: idle L/h per litre of engine 0.2–2.0 and engine figure / air-intake estimate 0.5–2.0 after 300 readings; else counting switches to the air estimate), **gears**. Dash: with FUEL LOW/RESERVE and the tank not learned yet, a line asks to fill to the click and log it. **Refuel during a run** (2026-11-06): the 1-min gauge median 15 % above the run's lowest (after the first 2 min) = filling: gauge updates stop and the level before the rise is kept for the receipt (or the next run's refuel detection); no gauge-curve point from the plateau (≥ 97 %). `repair()` once removes such a point and that fill's error / learned tank (the 2026-11-05 fill was logged 2 min after the gauge rose) |
| **My car → Fuel / Engine and limits** | Tank size, engine size, shift light and red line can be changed (tap the value). Fuel shows the average consumption and fill-ups learned |
| **Log a fill-up** (Dash fuel card, Dash details, My car) | Litres from the receipt, tick **full** if filled to the automatic cut-off. Goes into the running LIVE drive, or into the next one |
| **Settings → Voice** | Spoken drive summaries and warm-up tips |

**VIN check at every LIVE connect** (as in the Python dashboard): a car with a saved VIN is
switched to automatically; the chosen car learns its VIN the first time; a VIN that belongs to
no car set up stops the run, so another car's drive never lands in the wrong folder.
Recording starts only after this check.

Only read requests are ever sent (services 01, 03, 07, 09 and adapter commands); nothing on the
car is changed or cleared.

---

## Dash details (scroll down on Dash)

| Part | What it shows |
|---|---|
| CAR | Name, VIN, stored trouble codes (codes only, no descriptions), gear ratios, protocol |
| ENGINE | State (TOO HOT / HOT, or the warm-up stage), torque (Nm) and power (bhp) with peaks, heat soak, boost, temperature trends per minute |
| FUEL | Litres left ± uncertainty, range (litres ÷ this trip blended with the last ~100 km of driving; runs under 0.5 km, e.g. parked idling, left out; kept per run in `fuel_state.json` → `recent`, first filled from the drive reports), L/100 km now / this trip / average, fuel flow and its source, float sensor reading, notes |
| BATTERY | Charging state and volts, engine computer vs port voltage, the dip at the last engine start |
| TRIP | Distance, average speed, acceleration (g), RPM per km/h, best 0-60 / 0-100 km/h, top speed and RPM, pedal sensor check, km driven with the check-engine light on |

**Torque:** the Tiago's own torque % is mapped to Nm with its `torque_calibration`
(12 % = 0 Nm at idle, 56 % = 100 Nm at full throttle, from the 24 Oct drive), ±10 %.

**Fuel left:** combines the engine's fuel rate, an estimate from the air the engine takes in,
the tank's float sensor (one-minute median) and fill-up receipts (**Log a fill-up**). The first LIVE
run starts from the float sensor; the estimate improves after fill-ups to full.

**Battery:** while running: CHARGING / LOW CHARGE / NOT CHARGING / OVERCHARGING. The Tiago has a
smart alternator (voltage drops to ~12.2 V while accelerating, by design), so it's judged by the
highest voltage in the last 5 minutes (below 13.2 V = LOW CHARGE, below 12.8 V = NOT CHARGING).
OVERCHARGING only counts after 5 s above 15.0 V (red above 15.5 V): the smart alternator briefly
reaches ~15.1 V while braking, which is normal.
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
| `drive_<date>_<time>_profile.json` | Car, protocol, VIN (`null` if the car doesn't share it), trouble codes, which values the car supports, the fast-read result, one reading of everything |

- A reconnect during the drive continues the same file. A run that never connected leaves no file.
- Files are kept per car in `files/cars/<car>/recordings/` (drives from builds 5-6 were moved
  to the Tiago's folder).
- **Drives → a drive → Share** sends that drive as one ZIP `CarDash_drive_<date>_<time>.zip` (recording, profile, report, AI summary + the car's `fuel_state.json` for checking the fuel numbers), e.g. by email. A ZIP because some apps convert a loose CSV on the way; the ZIP also has a checksum per file, so a damaged copy fails to unzip instead of giving wrong data.
- **Drives → a drive → Replay** plays it again, with all calculations (nothing is saved).
  About 1 MB per 5 minutes.
- The screen stays on while LIVE / DEMO / TEST runs: a locked phone may pause the app,
  which would leave gaps in the recording.
- To analyse on the laptop, copy the files into the Python project's
  `cars/<car>/recordings/` folder.

---

## KEY TEST (removed from the app, build 22)

It recorded what the OBD port shows at each key position (26 Oct 2026); that's how the app tells
ENGINE ON / IGNITION ON / IGNITION OFF apart. Its button is gone; old files stay in `files/dev/`.

---

## Google Play (internal testing)

Needed for Android Auto: it only shows Car Dash when installed from Google Play. The app stays
private (internal testing, only your own Google account). Privacy policy: `PRIVACY.md`
(link: `https://github.com/Arka01/car-dash-android/blob/main/PRIVACY.md`).

**Each new version (automatic since build 11):** every push to `main` builds the app and the
GitHub Actions step **Upload to Google Play** puts `car-dash-N.aab` on internal testing by itself.
On the phone: **[≡] → UPDATE** (opens Car Dash in the Play Store) → **Update** (or open
`https://play.google.com/store/apps/details?id=io.github.arka01.cardash`); a new version can take
10–15 minutes to show up.

The upload uses a Google Cloud **service account** (`github-play-upload@radiant-entry-245409.iam.gserviceaccount.com`,
project "My First Project") whose key is the GitHub secret `PLAY_SERVICE_ACCOUNT_JSON`. In Play Console it
may only *view app information* and *release to testing tracks*, so it can't publish publicly.
If the upload step fails, the build is still on the GitHub Releases page: upload the `.aab` by hand
(Play Console → Car Dash → **Test and release → Testing → Internal testing → Create new release**).

The Play version is signed by Google, so it can't install over an APK from GitHub (and the
other way round): uninstall first, which deletes the app's data (share drives first).

---

## Android Auto

Car Dash shows on the car screen only when installed from **Google Play** (internal testing, see
"Google Play"); Android Auto hides apps of this kind installed from GitHub (works since build 8,
29 Oct 2026). The car screen uses Google's list layout (Android Auto draws it in its own style);
the Tiago's screen showed about 3 list rows (build 9), so build 10 uses a grid of tiles.

- **No buttons, nothing tappable** (the Tiago's touch screen makes random ghost taps). Reading
  starts by itself when Car Dash opens in the car, and stops only when the phone is **really
  disconnected** from Android Auto (unplugged), if the car started it; a LIVE run started on the
  phone keeps going. Switching to another app on the car screen (music, maps) keeps reading.
- It needs two one-time steps on the phone: allow **Nearby devices**, and choose the adapter
  (**☰ → Settings → Choose adapter**). Until then the Status tile says `Set up on phone`.
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
| Gear shows `-` | The car's gears aren't known: drive through every gear, the app learns them by itself; check **Car** for **New gears learned** |
| Values show `NO DATA` or `refused` | That computer didn't answer that value this round; share the text |
| Values are dim | Not updated for 10 s or more (or never answered): the link has stalled |
| **Support** says `Fast reading: off: …` | Batching didn't pass the self-test; values are read one at a time (slower, still correct). Share the log |

---

## Project files

| File | Purpose |
|---|---|
| `app/src/main/java/.../Dash.kt` | The hub: live, demo or replay data in, the run's car / calculations / recording, one snapshot out to both screens |
| `app/src/main/java/.../ui/App.kt` | The app frame: top bar, the chosen screen, the sidebar; the Actions the screens can trigger |
| `app/src/main/java/.../ui/Modern.kt` | The modern look's building blocks (cards, glow numbers, buttons), top bar, Dash, Drives |
| `app/src/main/java/.../ui/Screens.kt` | Live details, Car, Settings and Support |
| `app/src/main/java/.../GearLearner.kt` | Learns the gears from the drives by itself and offers new values on the Car screen |
| `app/src/main/java/.../cars/` + `assets/cars/` | Car settings (`car.json`, same format as the Python project), per-car folders, VIN, NEW CAR names |
| `app/src/main/java/.../logic/` | The Python dashboard's calculations: `Pipeline` (runs them all each round), `Rules` (warning levels), `Calculations` (gear, torque/power, heat soak, boost, hot state), `TripComputer`, `PedalCheck`, `Trends`, `BatteryMonitor`, `FuelTracker`, `WarmUpAdvisor`, `GearCalibration` |
| `app/src/main/java/.../Speech.kt` | Says the warm-up messages (phone voice) |
| `app/src/main/java/.../logic/DriveReport.kt` + `DriveReports.kt` | Drive report calculation; storing and calculating reports in the background |
| `app/src/main/java/.../CrashReport.kt` | Why the app last closed (Android's exit record, Java stack traces, the AI's last step) |
| `app/src/main/java/.../ai/LocalLlm.kt` | On-phone language model: import, settings, load / unload, streaming answers (LiteRT-LM 0.16.1) |
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
