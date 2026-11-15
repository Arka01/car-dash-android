"""Cut a short, anonymised piece of a Car Dash drive recording for the replay test.

Usage: python tools/make_replay_snippet.py drive.csv out.csv.gz --from 07:05 --to 07:25 --date 2026-01-05

Keeps the rows between the two clock times, moves them to --date (time of day stays: the report's
timeline and cold-start logic use it) and restarts the "t" column at 0. Recordings hold no VIN, but
the output is checked for anything that looks like one (17 VIN characters) and refused if found.
Plain Python, no extra packages.
"""
import argparse, csv, gzip, re, sys

VIN = re.compile(r"\b[A-HJ-NPR-Z0-9]{17}\b")

a = argparse.ArgumentParser()
a.add_argument("csv"); a.add_argument("out")
a.add_argument("--from", dest="start", required=True); a.add_argument("--to", dest="end", required=True)
a.add_argument("--date", required=True)
args = a.parse_args()

def clock(s):  # "07:05" or "07:05:30" -> "07:05:00.000" style prefix for comparing
    return (s + ":00")[:8] if len(s) == 5 else s

with open(args.csv, newline="") as f:
    rows = list(csv.reader(f))
header, body = rows[0], rows[1:]
ti, tt = header.index("time"), header.index("t")
keep = [r for r in body if len(r) > ti and clock(args.start) <= r[ti][11:19] < clock(args.end)]
if not keep:
    sys.exit("no rows in that time range")
t0 = float(keep[0][tt])
for r in keep:
    r[ti] = args.date + r[ti][10:]
    r[tt] = f"{float(r[tt]) - t0:.2f}"
lines = [header] + keep
text = "\n".join(",".join(f'"{c}"' if ("," in c or '"' in c) else c for c in r) for r in lines) + "\n"
if VIN.search(text):
    sys.exit("refused: something that looks like a VIN is in the output")
with gzip.open(args.out, "wt", newline="") as g:
    g.write(text)
print(f"{args.out}: {len(keep)} rows, {keep[0][ti]} .. {keep[-1][ti]}")
