"""Tiredness study, phase 1 (2026-11-07): does tiredness show in a Car Dash drive recording?

Read-only, offline. For each 10-minute block of a drive it prints:
  - A  driving time since the last real break (engine off or standing still for 15 min or more)
  - B  time-of-day risk (body's low points: 2-6 AM high, 1-4 PM mild)
  - C  monotony: share of the block spent cruising steadily, gear changes per minute
  - D  how the driver holds the accelerator while cruising (the candidate tiredness signal):
       pedal reversals per minute (direction changes), average pedal correction size,
       share of time the pedal is held still, speed drift (spread of speed in 30 s windows)
D is compared with the driver's own first 20 minutes of driving ("baseline"); a change late in a
long drive could be tiredness, or just different roads. That's what this study is meant to find out.

Usage: python tools/fatigue_study.py drive_<date>_<time>.csv [more.csv ...]
Needs: pandas, numpy.
"""
import sys

import numpy as np
import pandas as pd

BLOCK_MIN = 10
BASELINE_MIN = 20
BREAK_MIN = 15          # standing still or engine off this long = a real break
CRUISE_KMH = 30         # cruising: at least this fast...
CRUISE_ACCEL = 0.5      # ...and speed changing by less than this many km/h per second
PEDAL_DEADBAND = 0.8    # % of pedal travel: smaller moves are sensor noise (steps are ~0.4 %)


def load(path):
    d = pd.read_csv(path, low_memory=False)
    d["ts"] = pd.to_datetime(d["time"])
    d = d.set_index("ts").sort_index()
    d["secs"] = (d.index - d.index[0]).total_seconds()
    return d


def pedal_reversals(p):
    """Direction changes of the pedal, ignoring moves smaller than the deadband."""
    p = p.dropna().values
    if len(p) < 3:
        return 0, 0.0, 0.0
    anchor, direction, flips, sizes, still = p[0], 0, 0, [], 0
    for v in p[1:]:
        move = v - anchor
        if abs(move) < PEDAL_DEADBAND:
            still += 1
            continue
        d = 1 if move > 0 else -1
        if direction and d != direction:
            flips += 1
        sizes.append(abs(move))
        direction, anchor = d, v
    return flips, (np.mean(sizes) if sizes else 0.0), still / (len(p) - 1)


def block_stats(b):
    """Tiredness candidates for one block of rows."""
    secs = b["secs"].values
    minutes = (secs[-1] - secs[0]) / 60 if len(b) > 1 else 0
    speed = b["Speed"]
    accel = speed.diff() / b["secs"].diff()
    cruise = b[(speed >= CRUISE_KMH) & (accel.abs() < CRUISE_ACCEL)]
    cruise_min = len(cruise) / max(len(b), 1) * minutes
    gear = b["Gear (estimated)"].replace(0, np.nan).ffill()
    gear_changes = ((gear != gear.shift()) & gear.shift().notna()).sum()
    flips, size, still = pedal_reversals(cruise["Accelerator pedal"])
    # Speed drift: spread of speed inside 30 s windows while cruising.
    drift = cruise["Speed"].groupby(cruise["secs"] // 30).std().median() if len(cruise) else np.nan
    return {
        "cruise %": round(100 * cruise_min / minutes) if minutes else 0,
        "gears/min": round(gear_changes / minutes, 1) if minutes else 0,
        "pedal flips/min": round(flips / cruise_min, 1) if cruise_min >= 1 else np.nan,
        "pedal move %": round(size, 2) if cruise_min >= 1 else np.nan,
        "pedal still %": round(100 * still) if cruise_min >= 1 else np.nan,
        "speed drift km/h": round(drift, 2) if cruise_min >= 1 else np.nan,
        "cruise min": round(cruise_min, 1),
    }


def time_of_day_risk(ts):
    h = ts.hour
    if 2 <= h < 6:
        return "high"
    if 13 <= h < 16:
        return "mild"
    return "low"


def driving_since_break(d):
    """Minutes of driving since the last real break, for every row."""
    stopped = (d["RPM"].fillna(0) < 400) | (d["Speed"].fillna(0) < 1)
    out = np.zeros(len(d))
    since, still_from = 0.0, None
    secs = d["secs"].values
    for i in range(1, len(d)):
        dt = min(secs[i] - secs[i - 1], 5)
        if stopped.iloc[i]:
            still_from = secs[i] if still_from is None else still_from
            if secs[i] - still_from >= BREAK_MIN * 60:
                since = 0.0
        else:
            still_from = None
        since += dt
        out[i] = since / 60
    return out


def study(path):
    d = load(path)
    d["since break"] = driving_since_break(d)
    driving = d[d["RPM"].fillna(0) >= 400]
    if driving.empty:
        print(path, "no engine running")
        return
    rows = []
    start = driving.index[0]
    for k, b in driving.groupby(((driving.index - start).total_seconds() // (BLOCK_MIN * 60)).astype(int)):
        if len(b) < 100:
            continue
        s = block_stats(b)
        s = {"from min": k * BLOCK_MIN, "clock": b.index[0].strftime("%H:%M"),
             "A since break": round(b["since break"].iloc[-1]), "B time of day": time_of_day_risk(b.index[0]), **s}
        rows.append(s)
    t = pd.DataFrame(rows).set_index("from min")
    print(f"\n=== {path.split('/')[-1]}  ({d.index[0]:%d %b %H:%M}, {d['secs'].iloc[-1] / 60:.0f} min)")
    print(t.to_string())
    base = t[t.index < BASELINE_MIN]
    late = t[t.index >= 60]
    cols = ["pedal flips/min", "pedal move %", "pedal still %", "speed drift km/h"]
    if base["cruise min"].sum() >= 3 and late["cruise min"].sum() >= 3:
        print("D vs your first 20 min (cruise-weighted):")
        for c in cols:
            w0, w1 = base["cruise min"], late["cruise min"]
            b0 = np.nansum(base[c] * w0) / w0[base[c].notna()].sum()
            b1 = np.nansum(late[c] * w1) / w1[late[c].notna()].sum()
            print(f"  {c:18s} first 20 min {b0:6.2f}   after 60 min {b1:6.2f}   change {100 * (b1 - b0) / b0:+5.0f} %")
    else:
        print("D: not enough steady cruising early and late to compare (city drive?)")


if __name__ == "__main__":
    for p in sys.argv[1:]:
        study(p)
