# Car Dash (Android + Android Auto)

Android app that reads live engine data from an OBD-II Bluetooth adapter (vLinker MC)
and shows it on the phone and on the car's Android Auto screen.
Made for a 2023 Tata Tiago 1.2 petrol AMT (OBD protocol 6, CAN 11 bit 500 kbaud).

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
**Build N** with the file `car-dash-N.apk`.

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
The bottom line has the keys:

| Key | What it does |
|---|---|
| **[DASH]** | Big speed and gear, RPM and its bar, pedal and throttle bars, temperatures, warnings, then ENGINE / FUEL / AIR / CAR details |
| **[ALL]** | Every value the engine computer reports (about 50), by PID number |
| **[LOG]** | Connection log, fast-read status, **[ADAPTER]** (choose the vLinker) and **[SHARE]** |
| **[LIVE]** | Connect to the car (live data) |
| **[DEMO]** | Replay 8 minutes of a real Tiago drive (24 Oct 2026), on loop. No car or adapter needed |
| **[STOP] / [STOP DEMO]** | Stop |

**Top line:** `● LIVE` (green) = real data from the car, `◆ DEMO` (amber) = the recorded drive,
`○ OFF` = nothing running. The second line shows the status, how many rounds per second
RPM/speed refresh, and `fast` or `slow` reading.

**Colours:** green = fine, **amber** = warning, **red** = alarm, dim = missing or older than 10 s.
The `>` line under the temperatures lists active warnings.

| Warning | When (same rules as the Python dashboard) |
|---|---|
| SHIFT / REDLINE | RPM above 3500 / 4000 (the owner's limits) |
| WARMING UP / COOLANT HOT / OVERHEATING | coolant below 60 / above 105 / above 115 °C |
| OIL HOT / OIL OVERHEATING | oil above 120 / 135 °C |
| BATTERY | engine computer's voltage below 12.0 V (amber), below 11.5 or above 15.0 V (red) |

**GEAR:** worked out from RPM ÷ speed with the Tiago's measured ratios
(149.2 / 78.8 / 51.1 / 37.8 / 29.1 rpm per km/h). `STOP` = standing, `~` = between gears.

**Fast reading:** RPM, speed, load, throttle, pedal and torque are read every round; other values
every 3rd, 10th or 50th round. Up to 6 values are asked for in one question. This is
self-tested at connect; if it doesn't work, the app reads one value at a time (`slow`), which
always works. **[LOG]** shows the result (`fast read: on: …` or `off: reason`).

---

## Test checklist

**At your desk (no car):** **[DEMO]** → the values should move like a real drive: speed, gear
changes, the RPM bar turning amber/red near 3500/4000. Try portrait and landscape: everything
on DASH must be visible without scrolling.

**In the car (ignition ON or engine running):**

1. Close **Car Scanner** first. Only one app can talk to the adapter at a time.
2. Open **Car Dash** → allow **Nearby devices** (needed for Bluetooth).
3. **[LOG] → [ADAPTER]:** should show the vLinker. If not, tap it and choose the vLinker.
4. **[LIVE].** The status goes *Connecting… → Setting up adapter… → Finding sensors… →
   Reading all values once… → Testing fast read… → Connected*.
5. **[LOG]:** `fast read: on: …` is the goal. The second line on top should show several
   rounds per second (e.g. `8.0/s`).
6. **[LOG] → [SHARE]** and send the text. It contains every value plus the connection log.

The AMT computer (7E9) is listed in the log but not read: it refuses direct requests, and its
9 values only repeat the engine's (no gear).

---

## Android Auto

Android Auto's **Unknown sources** setting does **not** apply to apps built with Google's
Car App Library (Google: developer.android.com/training/cars/testing), which Car Dash is. So
the car screen hides Car Dash when it is installed from GitHub. To test in a real car it must
come from **Google Play** (internal app sharing or an internal test track, no review needed),
which needs a Play developer account. Until then, only the phone screen is usable.

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
| Values show `NO DATA` or `refused` | That computer didn't answer that value this round; share the text |
| Values are dim | Not updated for 10 s or more (or never answered): the link has stalled |
| **[LOG]** says `fast read: off: …` | Batching didn't pass the self-test; values are read one at a time (slower, still correct). Share the log |

---

## Project files

| File | Purpose |
|---|---|
| `app/src/main/java/.../Dash.kt` | The hub: live or demo data in, one snapshot out to both screens |
| `app/src/main/java/.../ui/Terminal.kt` | The phone's terminal look (DASH / ALL / LOG, portrait and landscape) |
| `app/src/main/java/.../Tiago.kt` | Gear calculation and warning levels |
| `app/src/main/java/.../demo/DemoPlayer.kt` + `assets/demo_drive.csv` | Demo mode: replays a recorded drive |
| `app/src/main/java/.../obd/Elm327.kt` | Sends text commands to the adapter, joins multi-part answers |
| `app/src/main/java/.../obd/Pids.kt` | Names and formulas for each sensor value |
| `app/src/main/java/.../obd/ObdSession.kt` | The live connection: connect, find sensors, fast reading with self-test, reconnect |
| `app/src/main/java/.../obd/Adapters.kt` | Remembers which paired Bluetooth device is the adapter |
| `app/src/main/java/.../Report.kt` | The plain text sent by **[SHARE]** |
| `app/src/main/java/.../MainActivity.kt` | Starts the phone screen; Bluetooth permission, adapter choice, sharing |
| `app/src/main/java/.../car/` | Android Auto: service, the list screen, Connect/Stop |
| `app/src/main/AndroidManifest.xml` | Tells Android what the app is and what it may use (Bluetooth, Android Auto) |
| `app/build.gradle.kts` | App id, Android versions, libraries, signing |
| `.github/workflows/build.yml` | The GitHub Actions build that makes the APK and the release |
