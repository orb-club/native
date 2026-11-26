import argparse
import json
import re
import sys
from pathlib import Path

parser = argparse.ArgumentParser(description="Assert a completed native reference probe")
parser.add_argument("log", type=Path)
parser.add_argument("--mode", required=True, choices=("sdk", "engine", "registration", "survivor", "first-use"))
parser.add_argument("--expect-leak", action="store_true")
args = parser.parse_args()
log = args.log.read_text(errors="replace")
if "PROBE_FAILED" in log or "SNAPSHOT_UNAVAILABLE" in log:
    sys.exit("Probe reported a failure; incomplete runs are not passing evidence.")
marker = {"sdk": "PROBE_COMPLETED", "engine": "ENGINE_PROBE_COMPLETED",
          "registration": "ENGINE_PROBE_COMPLETED", "survivor": "SURVIVOR_PROBE_COMPLETED",
          "first-use": "FIRST_USE_PROBE_COMPLETED"}[args.mode]
if marker not in log:
    sys.exit("Missing completion marker: " + marker)
snapshots = []
current = None
strong_global = False
for line in log.splitlines():
    begin = re.search(r"SNAPSHOT_BEGIN (\S+)", line)
    if begin:
        current = {"phase": begin.group(1), "global_refs": None, "classes": []}
        strong_global = False
        continue
    if "SNAPSHOT_END" in line and current is not None:
        snapshots.append(current)
        current = None
    if current is None:
        continue
    if "table dump:" in line.lower() and re.search(r"(?:^|:\s)(?:global|global reference) table dump:", line, re.I):
        strong_global = True
    elif "table dump:" in line:
        strong_global = False
    if strong_global:
        total = re.search(r"Last\s+\d+\s+entries\s+\(of\s+(\d+)\):", line)
        if total:
            current["global_refs"] = int(total.group(1))
        elif "(empty)" in line:
            current["global_refs"] = 0
        summary = re.search(r"\b(\d+) of (.+)$", line)
        if summary:
            current["classes"].append({"count": int(summary.group(1)), "class": summary.group(2)})
print(json.dumps(snapshots, indent=2))
if not snapshots or any(row["global_refs"] is None for row in snapshots):
    sys.exit("Missing global-reference measurements; this is not a passing experiment.")
by_phase = {row["phase"]: row["global_refs"] for row in snapshots}
if all(p in by_phase for p in ("control_before", "control_held_32", "control_released")):
    rise = by_phase["control_held_32"] - by_phase["control_before"]
    fall = by_phase["control_held_32"] - by_phase["control_released"]
    print(f"Control rise={rise}, fall={fall}; expected 32 each.", file=sys.stderr)
    if rise != 32 or fall != 32:
        sys.exit("Control was noisy or failed; inspect before inferring reference balance.")

if args.mode == "sdk":
    required = ["control_before", "control_held_32", "control_released", "cycle_1"]
    required += [f"cycle_{n}" for n in range(10, 101, 10)]
    samples = [by_phase.get(key) for key in required if key.startswith("cycle_")]
elif args.mode == "first-use":
    required = ["first_use_0", "first_use_1"]
    samples = [by_phase.get(key) for key in required]
else:
    prefix = "survivor" if args.mode == "survivor" else "engine"
    required = [f"{prefix}_{n}" for n in range(1, 31)]
    samples = [by_phase.get(key) for key in required]
if any(key not in by_phase for key in required):
    sys.exit("Missing required snapshots; a partial run cannot pass.")
if len(by_phase) != len(snapshots):
    sys.exit("Duplicate phases; use logcat scoped to one fresh process.")
if args.mode == "registration" and "MODE registrationOnly=true" not in log:
    sys.exit("Registration-only mode was not confirmed.")
if args.mode == "first-use":
    if by_phase.get("first_use_0", 0) - by_phase.get("engine_baseline", 0) != 6:
        sys.exit("First use did not allocate exactly one set of six process-owned references.")
if args.mode == "engine" and "MODE registrationOnly=false" not in log:
    sys.exit("Real Sentry engine mode was not confirmed.")
growth = samples[-1] - samples[0]
if args.expect_leak:
    if args.mode not in ("engine", "registration") or growth < 6 * (len(samples) - 1):
        sys.exit(f"Expected at least six leaked references per engine, measured {growth}.")
else:
    # Allow a small bounded amount of startup/background noise, not a per-cycle slope.
    if growth > 2 or samples[-1] - min(samples) > 2:
        sys.exit(f"Global references did not plateau: net growth {growth}.")
print(f"PASS: {args.mode}, {len(samples)} samples, net growth {growth}", file=sys.stderr)
