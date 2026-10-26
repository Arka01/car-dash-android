# Car Dash (Android + Android Auto)

Android app that reads live engine data from an OBD-II Bluetooth adapter (vLinker MC)
and shows it on the phone and on the car's Android Auto screen.
Made for a 2023 Tata Tiago 1.2 petrol AMT (OBD protocol 6, CAN 11 bit 500 kbaud).

**Version 0.1 (current):** a connection test. It finds every sensor the car's computers
report and shows them all as plain text. Looks will come later.

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
| 4 | In the same Android Auto screen: **⋮ (top right) → Developer settings → Unknown sources: ON** — without this, the car screen hides apps not from the Play Store |

---

## Every new version

| Step | Where | What to do |
|---|---|---|
| 1 | WSL terminal | `cd ~/python_projects/car-dash-android && git push` |
| 2 | github.com → repo → **Actions** | Wait for the green tick (about 5 min). Red cross = build failed, see Troubleshooting |
| 3 | Phone browser | Open `github.com/Arka01/car-dash-android/releases` → newest **Build N** → tap `car-dash-N.apk` |
| 4 | Phone | Open the downloaded file → **Install** (or **Update**). If Play Protect warns about an unknown app: **More details → Install anyway** |

---

## Test checklist for version 0.1

**On the phone (ignition ON or engine running):**

1. Close **Car Scanner** first. Only one app can talk to the adapter at a time.
2. Open **Car Dash** → allow **Nearby devices** (needed for Bluetooth).
3. **Adapter:** should show the vLinker. If not, tap it and choose the vLinker.
4. **Connect.** The status goes *Connecting… → Setting up adapter… → Finding sensors… → Connected*.
5. Expected: `== ENGINE 7E8 ==` with about 50 values and `== AMT 7E9 ==` with 9
   (the AMT computer repeats a few engine values; it reports no gear).
   `Sweep N: x.x s` shows how long one round of all values takes.
6. Tap **Share** and send the text (e.g. to yourself by email). It contains every value plus the
   connection log, which is what's needed to fix problems.

**In the car (wired Android Auto):**

1. Plug the phone in. Open the app launcher on the car screen → **Car Dash**.
2. Main screen: the same text, green on black, drawn by the app itself.
3. Tap **Connect** (top right) if it isn't connected yet.
4. The **≡** button opens the fallback list view (Google's standard layout, fewer values).
   Note which of the two screens works.

---

## Troubleshooting

| What you see | What to do |
|---|---|
| Push asks for a password | Use the GitHub username and a personal access token (not the account password). The token needs the **workflow** permission, because this repo contains a build workflow |
| Push rejected: *refusing to allow … workflow … without `workflow` scope* | Make a new token with **repo** and **workflow** ticked (GitHub → Settings → Developer settings → Personal access tokens) |
| Actions: red cross, *Secret SIGNING_KEYSTORE_BASE64 is missing* | Do setup step A3/A4 again, then on the Actions page **Re-run jobs** |
| Actions: red cross, anything else | Open the failed run → **build** → the red step; copy the error lines |
| Phone: *App not installed* / *conflicts with an existing package* | The APK was signed with a different key. Uninstall Car Dash once, then install again |
| Car Dash missing on the car screen | Unknown sources (setup B4) is off, or the phone needs a re-plug after installing |
| *Connection lost, retrying in 5 s* | Another app (Car Scanner) holds the adapter, the adapter is unplugged, or Bluetooth is off |
| *No car computer answered (ignition on?)* | Turn the ignition on, then Stop → Connect |
| Values show `NO DATA` or `refused` | That computer didn't answer that value this round; share the text |
| Values show `(old)` | Not updated for 10 s or more: the link has stalled |

---

## Project files

| File | Purpose |
|---|---|
| `app/src/main/java/.../obd/Elm327.kt` | Sends text commands to the adapter, reads the answers |
| `app/src/main/java/.../obd/Pids.kt` | Names and formulas for each sensor value |
| `app/src/main/java/.../obd/ObdSession.kt` | The connection: connect, find sensors, read them in a loop, reconnect |
| `app/src/main/java/.../obd/Adapters.kt` | Remembers which paired Bluetooth device is the adapter |
| `app/src/main/java/.../Report.kt` | The text lines both screens show |
| `app/src/main/java/.../MainActivity.kt` | Phone screen |
| `app/src/main/java/.../car/` | Android Auto: service, main (drawn) screen, list screen |
| `app/src/main/AndroidManifest.xml` | Tells Android what the app is and what it may use (Bluetooth, Android Auto) |
| `app/build.gradle.kts` | App id, Android versions, libraries, signing |
| `.github/workflows/build.yml` | The GitHub Actions build that makes the APK and the release |
