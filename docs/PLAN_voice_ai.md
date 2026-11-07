# Plan: voice-first AI ("ask about my drives, during and after")

Status: building (2026-11-07). Local commits only; the owner pushes everything at once.
Model: unchanged (Gemma 4 E2B on the phone, owner's choice 2026-11-07).

## Why

Talk in the car only got a snapshot of the current moment (LIVE NOW). On 2026-11-07 it answered
"what is the highest rpm" with the current rpm (1991; the real highest so far was 2958) and "what was the dip in battery
while starting" with the current voltage (13.5 V; that drive began with the engine already running).
The app had the data, but it never reached the AI:

- the start dip is measured live (BatteryMonitor, saved in battery_state.json) but not given to Talk;
- highest / lowest rpm exist only in the drive report, which is calculated once, after the drive.

## Idea: one drive memory, always up to date

The drive report is calculated by the same code during the drive (live) and after it (final), so
the two can never disagree. Every AI use reads from it.

## Steps (each its own local commit)

1. **Live drive report.** LIVE keeps the report's columns in memory (`DriveBuffer`, ~13 numbers per
   row); `DriveReport.calculate` works on that data as well as on a CSV. Talk gets "THIS DRIVE SO FAR"
   = the live report. The report gains the lowest steady rpm and a **timeline** (engine starts with
   their dip, engine stops, highest rpm with speed, top speed, longest idle, hottest coolant, lowest
   battery while driving), each with its time. Report VERSION 7 (old reports are recalculated).
2. **Context by situation.** In the car the AI hears the question itself (sound), so the code can't
   read the question first. Talk therefore picks its blocks by situation instead of by keyword:
   driving / just stopped = LIVE NOW + THIS DRIVE SO FAR + LAST ENGINE START + EARLIER DRIVES;
   not reading the car = the last finished drive + EARLIER DRIVES. The blocks are worked out while the
   driver is still speaking, so they add no waiting. Today's date is in LIVE NOW, so "yesterday" works.
3. **After the drive.** Talk keeps working after the engine is off (phone still connected): THIS
   DRIVE is then the complete drive. Drives → Ask about this drive also gets the timeline. The spoken
   summary does not (the small model would present timeline facts as findings).
4. **Car history.** EARLIER DRIVES: the newest 5 drives in one line each (date, minutes, km, kind,
   L/100 km, WATCH / CHECK), the last 7 days' totals and the last 5 engine starts' dips
   (battery_state.json). Built from the reports already saved per drive, so no new file is needed.
5. **Lag.** The first sentence of the answer is spoken as soon as it's written (the rest follows).
   The full prompt of the last Talk is saved (talk_last_prompt.txt, in the drive ZIP) and its size
   logged, so the time cost of the extra context can be measured.

## Not in this plan

- Battery rule fixes (false "below 12 V" and "charging low" alarms): after the highway drive of 2026-11-08.
- Bike (Hunter) fixes; the per-vehicle methods plan (docs/PLAN_vehicle_methods.md).
- A bigger or cloud model.

## Checking

No JDK on the development machine and CI only builds, so there is no unit test yet. Checks:
CI build; then a drive with Talk questions ("highest rpm so far", "start dip", "how was yesterday's
drive"), share the drive ZIP; talk_last_prompt.txt is compared with the CSV and the final report.
