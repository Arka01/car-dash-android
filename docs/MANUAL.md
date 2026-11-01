# Car Dash – User Manual

*This manual covers every feature up to Car Dash version 0.1.24 (1 November 2026).*

Car Dash turns your Android phone into a smart dashboard for your car. It reads live data from your car's diagnostic port, shows what your car's own dashboard doesn't, records every drive, writes a report for each one, and lets an AI on your phone explain your drives in plain words. It can read the summary aloud after you switch off the engine.

Car Dash only **reads** data from the car. It never changes, resets or clears anything in your car, and your data stays on your phone.

---

## 1. What you need

| | |
|---|---|
| **Android phone** | Android 8 or newer. For the AI, a recent phone with about 6 GB of memory (RAM) or more. |
| **OBD-II adapter** | A Bluetooth OBD-II adapter of the common **ELM327** type (classic Bluetooth). It plugs into your car's diagnostic socket, usually under the dashboard near the steering wheel. |
| **Your car** | A car with an OBD-II port that uses the modern **CAN** protocol (11-bit), which most petrol and diesel cars from about 2008 onwards do. Car Dash finds the protocol by itself. |
| **Android Auto** (optional) | To see Car Dash on your car's screen. Car Dash works on the phone alone as well. |
| **AI model** (optional) | A free file of about 2.6 GB, downloaded once, for the drive summaries and questions (see section 8). |

---

## 2. Getting started

1. **Install Car Dash** from Google Play, using the invitation link you received.
2. **Pair your adapter**: plug it into the car, switch the ignition on, then pair it in Android's **Settings → Bluetooth**. Some adapters ask for a PIN, often 1234 or 0000.
3. **Open Car Dash** and allow the two things it asks for:
   - **Nearby devices**, to talk to the adapter over Bluetooth.
   - **Notifications**, for the "Recording your drive" notice (see section 6).
4. **Choose your adapter**: ☰ → **Settings → Connection → Choose adapter**.
5. **Add your car**: tap the car at the top of the sidebar (☰) → **+ Add a new car**. With the ignition on (engine running is best), Car Dash connects, reads your car's chassis number (VIN) and what it supports, and then asks a few questions. All of them are optional:
   - the car's name
   - fuel type
   - fuel tank size
   - engine size
   - the RPM where you want a red warning (your own limit)
6. **Start driving**: open Car Dash on your car's screen (Android Auto), or tap ☰ → **Start LIVE** on the phone.

**Just want to look around first?** ☰ → **Settings → Demo → Try a demo drive** plays a recorded drive, so you can try every screen without a car.

---

## 3. Finding your way around

Tap **☰** (top left) to open the sidebar.

| In the sidebar | What it's for |
|---|---|
| **Your car (top)** | The car in use. Tap it to switch between your cars or to add a new one. |
| **Dash** | What's happening **now**: speed, gear, RPM and warnings at the top, details further down. |
| **Drives** | Your **past** drives, each with a report, an AI summary and questions you can ask about it. |
| **My car** | Your **car's** profile: gears, fuel, limits, a scan of its computers. |
| **Settings** | How the **app** works: connection, voice, the AI, the demo, updates. |
| **Support** | **Help** when something doesn't work: status, crash report, connection log. |
| **Start LIVE / Stop** | Start or stop reading your car from the phone. |
| **Update app** | Opens Car Dash in the Play Store, to check for a newer version. |

The top bar shows the screen's name, **● LIVE** (reading your car), **◆ DEMO**, or **○ OFF**, and the time. The line below shows what the car is doing (for example **ENGINE ON**).

**Back** (the back button or swipe gesture) returns to the previous screen. On the Dash, it closes the app.

### Colours
- **Green:** normal.
- **Amber:** worth watching.
- **Red:** needs attention.
- **Dim:** no data, or the value is older than 10 seconds.

---

## 4. The Dash

### Top: while you drive
- **Main card:** speed and gear in big numbers, engine RPM and an **RPM bar** (green → amber from your shift point → red above your limit). While the engine is still cold, a lower limit applies (for example "cold: max 2500"), so you go easy until it's warm. Below it: pedal, throttle and engine load.
- **Alert card:** **"✓ All normal"**, or the current warnings, such as **OVERHEATING**, **BATTERY LOW CHARGE**, **FUEL LOW** or **SHIFT**.
- **Value cards:**
  - coolant (with the warm-up stage while cold)
  - oil
  - intake air
  - battery (charging state)
  - fuel left and range
  - consumption
  - torque and power
  - trip

  Tap **Fuel left** after filling up to log the fill-up.

### Further down: details
Scroll down for the full picture:

| Card | Contents |
|---|---|
| **Engine** | Fault codes (check-engine codes), torque and power with their peaks, heat soak (how much hotter the intake air is than outside), boost |
| **Temperature trends** | How fast coolant, oil, intake air and catalyst temperatures are changing, per minute |
| **Fuel** | Litres left (with accuracy), range, consumption now / this trip / average, fuel flow, the tank sensor reading, and **Log a fill-up** |
| **Battery** | Charging state and voltage, the voltage at the last engine start, and a check that the two voltage readings agree |
| **Trip** | Distance, average speed, acceleration, best 0–60 and 0–100 km/h times, top speed and RPM, a check of the accelerator pedal sensors |
| **All sensors** (folded) | Every raw value your car's engine computer reports, as it reports it |

---

## 5. Driving with Android Auto

Open **Car Dash** on your car's screen. Reading starts **by itself**, with no buttons needed. The car screen shows a grid of tiles: status, RPM, temperatures, pressures, battery, speed, gear, load, throttle, pedal, fuel, torque, power and more, with warnings in amber or red. It scrolls, and your car decides how many tiles fit.

- **Reading stops by itself** when you unplug the phone. Switching to music or maps on the car screen does **not** stop it: your drive keeps recording.
- The car screen is **display only**: nothing on it can be tapped by accident.
- Android Auto refreshes the car screen about once or twice a second. The phone screen is faster.

**Without Android Auto:** tap ☰ → **Start LIVE** on the phone, and **Stop** when you're done.

---

## 6. What happens automatically

Most of Car Dash works without you doing anything.

### Every drive is recorded
Every time Car Dash reads your car, the drive is saved on the phone, several readings per second. While it records, Android shows a quiet notification, **"Recording your drive"**. That keeps Car Dash running even when it isn't on screen: phone locked, music or maps in front.

### Your car is recognised
At every connection, Car Dash reads your car's chassis number (VIN):
- If you have several cars set up, it **switches to the right one by itself**.
- If the car isn't set up yet, it stops instead of mixing the drive into another car's history.

### The car's state is detected
Car Dash knows whether the **engine is running**, **starting**, **stopping**, or whether the **ignition is on or off**. When the ignition is off, it stops asking the car for data (so your car's electronics can sleep and don't drain the battery). It only listens quietly and resumes by itself when you switch the ignition back on.

### A report for every drive
When a drive ends, Car Dash works out a **drive report** with a verdict for each check (see section 7).

### A spoken summary after you switch off
About **20 seconds after you switch off the engine**, while you're still in the car, the AI writes a short summary of the drive and **reads it aloud**. It plays through the car's speakers if Android Auto is still on, otherwise through the phone. If you start the engine again before it speaks (a quick stop), the summary waits for the real end of the drive. You can turn the voice off in **Settings → Voice**.

### Your gears are learned
Car Dash learns your gearbox by itself:
- In each gear, engine RPM divided by speed is a fixed number. After every drive, Car Dash looks at all your recorded drives, finds the steady moments in each gear, and measures that number.
- When it has learned something new, **My car** shows **"New gears learned"** with **Save** and **Not now**, and the sidebar shows a dot (**My car ●**).
- Gears you haven't driven enough yet are **estimated** until you do. For example, top gear usually needs a stretch of highway.
- Once saved, the Dash and the car screen show which gear you're in.

### Fuel left is tracked
Car Dash estimates how much fuel is left by combining:
- the fuel the engine reports using, counted litre by litre
- the tank's fuel gauge sensor, which is noisy, so only a one-minute average is used, and only when driving steadily
- your fill-ups, which are the most reliable of all

Like the gears, it then **learns by itself**, from your fill-ups and drives:
- **your tank's real size** to the first click (often a bit more than the official size), from fill-ups made when the tank was nearly empty
- **how accurate the engine's own fuel count is**, from every two full fill-ups in a row
- **what your tank gauge really means** at every level, from steady driving once the count is calibrated
- **drives made without Car Dash**: the car's own distance counter shows the km you drove, and the fuel they used is taken off

**All you do is log every refuel** (see section 9).

### The battery is watched
- **Charging:** while the engine runs, Car Dash checks that the alternator charges the battery. Many modern cars have a **smart alternator** that charges mostly while slowing down, so the voltage is often low while accelerating. For those cars, Car Dash judges charging by the highest voltage over the last few minutes, so you don't get false alarms.
- **Starting:** at every engine start, it measures how far the voltage drops while the starter turns. That's the classic battery health test, and it's kept as a history, so a slowly weakening battery shows up over weeks.
- **At rest:** after the engine is off, it estimates how charged the battery is.

### Warm-up advice
After a cold start, Car Dash tells you when the idle has settled and you can move off, when to drive gently, and when the engine is warm enough for full power. Each step is spoken, and the RPM limit on the Dash follows these steps.

### Reading stays fast and recovers by itself
Car Dash asks the car for several values at once, which makes the important values (RPM, speed) refresh several times a second. If the Bluetooth connection drops, it reconnects by itself every few seconds, and the drive continues in the same recording.

---

## 7. Drive reports

**Drives** lists your drives, newest first. Each card shows the date, duration, distance and consumption, plus the drive's overall verdict: **✓ OK**, **▲ watch** or **■ check**. Tap a drive to open its report:

- **Share:** sends the drive (recording, report and AI summary), for example by email.
- **Replay:** plays the drive again on the Dash.
- **AI summary:** the short spoken text and a longer explanation. Tap **Write again** to have it rewritten.
- **Ask about this drive:** see section 8.
- **Verdict:** every finding marked **watch** or **check** first, then the good news.
- **Sections:** Drive, Fuel, Warm-up (after a cold start), Battery, Engine, Data.

### What the report checks
| Check | OK | Watch | Check |
|---|---|---|---|
| Time above your RPM limit | under 1 s | up to 5 s | longer |
| RPM above 2,500 while the engine is cold | none | up to 10 s | longer |
| Fuel use vs your own average (after 3 drives) | within 15 % | more than 15 % above | |
| Battery charge before the start | 75 % or more | 25–75 % | below 25 % |
| Voltage drop while starting | 10.0 V or more | 9.6–10.0 V | below 9.6 V |
| Alternator charging | normal | low | not charging |
| Time below 12 V with the engine running | under 30 s | under 5 min | longer |
| Overcharging (above 15 V for 5 s or more) | no | above 15.0 V | above 15.5 V |
| Engine temperature (coolant / oil) | normal | hot | overheating |
| Fuel mixture correction | within ±10 % | ±10–20 % | beyond ±20 % |
| Fault codes | none | | any |
| Data gaps while the engine runs | none | any | |

These are general guidelines for petrol and diesel cars, and your own limits from **My car**. They're not a replacement for a workshop.

---

## 8. The AI on your phone

### What it is
Car Dash can use a small AI language model that runs **entirely on your phone**. It writes your drive summaries and answers your questions about a drive. Nothing is sent to the internet, so your drives stay private.

### Setting it up (once)
1. On your phone's browser, open Hugging Face (huggingface.co) and find **litert-community / gemma-4-E2B-it-litert-lm**. It's Google's free Gemma model, packaged for phones.
2. Under **Files and versions**, download **gemma-4-E2B-it.litertlm** (about 2.6 GB). Wi-Fi recommended.
3. In Car Dash: ☰ → **Settings → AI → Import model**, and pick the file from your Downloads. Car Dash copies it into its own storage. You can then delete the copy in Downloads.

It stays imported through app updates. **Replace model** swaps in another model file; **Remove** deletes it.

### How it works
A small model doesn't know your car, and it shouldn't guess what's normal. So Car Dash does the judging itself and gives the AI **facts**:

1. **The drive report**, with the app's verdicts (OK / watch / check), decided by fixed rules.
2. **Notes about your car** (for example "smart alternator: low voltage while accelerating is normal").
3. **One line per earlier drive**, so it can see trends.
4. **Your question**, or the request for a summary.

The AI is told to trust the verdicts, to report only this drive's findings, and to say so if the answer isn't in the report. Its job is to **explain** in plain words, not to measure.

### Using it
- **Drive summary:** written automatically after each drive, and read aloud.
- **Ask about this drive:** in **Drives**, open a drive → **Ask a question**. For example:
  - "How was my drive?"
  - "Why is the battery a watch?"
  - "Was my fuel use OK?"
- **Show prompt** (under an answer) shows exactly what the AI received, for the curious.

### Good to know
- Loading the model takes a few seconds the first time. A summary takes about 10–30 seconds, depending on the phone.
- The AI never runs while Car Dash reads your car during a drive, so the recording always comes first. It's switched off again a few minutes after the last question, to free memory.
- **It can make mistakes.** Small models sometimes phrase things oddly or get details wrong. The **report** is the reliable part; the AI explains it.
- **Settings → AI → Advanced settings** has the AI's technical settings (temperature, top-K, top-P, answer length, GPU or CPU, the system prompt). You don't need to change them. If the app closes while the AI loads, switch **Runs on** to **CPU**.

---

## 9. Fuel: logging fill-ups

Log every fill-up to get accurate **fuel left** and **range**:

1. Fill the tank, ideally **to the automatic cut-off** (the pump clicks off).
2. Tap **Fuel left** on the Dash, or **Log a fill-up** (Dash details or My car).
3. Enter the **litres from the receipt**, and tick **"Filled to the automatic cut-off"** if you did.

Log **every** refuel: full ones are the anchors, and partial ones (litres only, "full" unticked) keep the count right in between. A forgotten one is noticed from the tank gauge jumping, but less precisely, until the next full fill puts everything right.

Why the first click, not the brim: the litres are exact either way, but the first click gives the **same "full" every time**, which is what the app learns from. Filling to the brim can also push fuel into the car's vapour filter.

What Car Dash does with it:
- **Filled to the cut-off:** fuel left is set to a full tank, and Car Dash learns how your tank's gauge reads at different levels.
- **Two full fills in a row:** Car Dash compares the fuel you really used with what it counted, and corrects its counting.
- **Litres only:** they're added to the current estimate.
- **Forgot to log it?** Car Dash notices the tank gauge jump and adjusts, but less precisely.

If it's done during a drive, it's applied straight away; otherwise at the next start. Enter your **tank size** in **My car** as a starting point.

**My car → Fuel** shows how far the learning is ("learning: 1 of 2 full fills", then "calibrated · ±0.6 L"), the **real tank size** learned, and a **fill-up log**. After each full fill, the log shows the **true consumption** since the previous full fill and how far off the app's estimate was.

---

## 10. My car

| Card | What's in it |
|---|---|
| **This car** | Name, fuel type, chassis number (VIN), learned at the first connection |
| **New gears learned** | Appears when the app has learned new gear values: **Save** or **Not now** |
| **Gears** | Engine RPM per km/h in each gear, measured or (still) estimated |
| **Fuel** | Fuel accuracy and learning status, tank size (entered, tap to change) and **real tank size** (learned), average consumption, gauge points learned, **Log a fill-up**, and the **fill-up log** (litres, true consumption, accuracy check) |
| **Engine and limits** | Engine size, **shift light** RPM and **red line**, your own limit (tap to change) |
| **Car computers** | **Scan car computers:** a read-only check of what each computer in your car supports, and any stored fault codes |

**Several cars?** Each car has its own settings, drives, fuel history, battery history and AI notes. Switch with the car at the top of the sidebar; you can't switch while Car Dash is reading a car.

---

## 11. Settings

| Setting | What it does |
|---|---|
| **Connection** | Choose your Bluetooth OBD adapter |
| **Voice** | Spoken drive summaries and warm-up tips, on or off |
| **AI** | Import, replace or remove the AI model; advanced settings |
| **Demo** | Try a recorded demo drive, without a car |
| **App** | Version, check for updates |

---

## 12. Your data and privacy

- **Everything stays on your phone:** drives, reports, AI summaries and car settings, in the app's private storage. Uninstalling the app deletes them.
- **Nothing is sent anywhere.** Car Dash has no account, no ads and no analytics, and the AI runs on the phone. Files leave the phone only when **you** tap **Share**.
- **Read-only:** Car Dash only reads standard diagnostic data. It never writes to, resets or clears anything in your car (including fault codes).
- **Bluetooth** is used only to talk to the adapter you chose.

---

## 13. Troubleshooting

| What you see | What to do |
|---|---|
| **Car Dash doesn't appear on the car screen** | Car Dash must be installed from Google Play. In Android Auto's settings, check **Customize launcher**. |
| **"Set up on phone"** on the car screen | Open Car Dash on the phone once: allow **Nearby devices** and choose your adapter (**Settings → Connection**). |
| **"Connection lost, retrying in 5 s"** | Another app (another OBD app) may be using the adapter, so close it. Check the adapter is plugged in and Bluetooth is on. |
| **No car computer answers** | Switch the ignition on (or start the engine) and wait a few seconds. |
| **"VIN isn't … or any car set up"** | You're in a car that isn't set up yet: tap the car at the top of the sidebar → **+ Add a new car**. |
| **"Protocol N: only CAN 11-bit cars"** | Your car uses an older or different diagnostic protocol that Car Dash doesn't support yet. |
| **Gear shows "-"** | Your gears aren't learned yet. Just drive; check **My car** for "New gears learned". |
| **Fuel left shows "--"** | Set your tank size in **My car**, and log a fill-up. |
| **The app closed by itself** | Open it again and go to **Support**: share the crash report. If it happened while the AI was loading, set **Settings → AI → Advanced → Runs on → CPU**. |
| **Something else doesn't work** | **Support → Share log** and send it to the developer. |

---

## 14. Words used in this manual

| Word | Meaning |
|---|---|
| **OBD-II** | The standard diagnostic system in modern cars, with a socket under the dashboard. |
| **Adapter** | The small plug that goes into the OBD-II socket and talks to your phone over Bluetooth. |
| **RPM** | Engine revolutions per minute. |
| **Red line** | Your own RPM limit; above it, the Dash turns red. |
| **Fault codes** | Codes the car stores when something is wrong (the "check engine" light). |
| **Smart alternator** | An alternator that charges mostly while slowing down, to save fuel. Low voltage while accelerating is normal then. |
| **Start dip** | How far the battery voltage drops while starting the engine: a classic battery health check. |
| **Heat soak** | Intake air much hotter than outside air (common in slow traffic); it costs a little power. |
| **Verdict** | The report's rating of a check: OK, watch or check. |
| **On-phone AI** | A small language model that runs on your phone, without the internet. |
