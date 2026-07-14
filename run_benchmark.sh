#!/usr/bin/env bash
# Runs the instrumented memory benchmark on the connected device/emulator,
# pulls the raw CSV, and prints a markdown results table.
#
# Usage: ./run_benchmark.sh [--skip-run]   (--skip-run only pulls + formats)
set -uo pipefail
cd "$(dirname "$0")"

ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
RESULTS_LOCAL="benchmark/build/benchmark-results.csv"

# Results are collected from logcat rather than the app's files dir: AGP
# uninstalls the test APK after the run, which deletes the app's storage.
if [[ "${1:-}" != "--skip-run" ]]; then
  "$ADB" logcat -c
  # The task is EXPECTED to fail when DOM strategies OOM on stress sizes — the
  # orchestrator records the crash and keeps going, so don't stop on failure.
  ./gradlew :benchmark:connectedDebugAndroidTest || true
fi

mkdir -p "$(dirname "$RESULTS_LOCAL")"
"$ADB" logcat -d -s TaperBenchmark \
  | grep -o 'TaperBenchmark: .*' | sed 's/TaperBenchmark: //' > "$RESULTS_LOCAL"
if [[ ! -s "$RESULTS_LOCAL" ]]; then
  echo "No benchmark rows in logcat — did the benchmark run?" >&2
  exit 1
fi

python3 - "$RESULTS_LOCAL" <<'EOF'
import csv, statistics, sys
from collections import defaultdict

STRATEGIES = ["dom-orgjson", "dom-gson-tree", "taper-streaming"]
HEADS = {"dom-orgjson": "DOM org.json", "dom-gson-tree": "DOM Gson tree", "taper-streaming": "Taper streaming"}
SIZE_ORDER = ["100KB", "500KB", "1MB", "2MB", "5MB", "10MB", "25MB-stress", "50MB-stress"]
ITERATIONS = 3

rows = defaultdict(list)  # (strategy, label) -> [(heapKB, pssKB, ms, oom)]
sizes = {}
for r in csv.reader(open(sys.argv[1])):
    strategy, label, actual, iteration, heap, pss, ms, oom = r
    sizes[label] = int(actual)
    rows[(strategy, label)].append((heap, pss, ms, int(oom)))

def fmt_mb(kb_values):
    return f"{statistics.median(kb_values)/1024:.1f} MB"

def cell(strategy, label):
    data = rows.get((strategy, label), [])
    ooms = sum(d[3] for d in data)
    completed = [d for d in data if d[3] == 0]
    if not data:
        return "OOM-killed (process died)", "1"
    if not completed:
        return "OOM", str(max(ooms, 1))
    heap = fmt_mb([float(d[0]) for d in completed])
    ms = statistics.median([float(d[2]) for d in completed])
    note = f", {ooms} OOM" if ooms else ""
    return f"{heap} / {ms:.0f} ms{note}", str(ooms)

print("| Payload | " + " | ".join(f"{HEADS[s]} peak heap / time" for s in STRATEGIES) +
      " | OOM count (" + "/".join(HEADS[s] for s in STRATEGIES) + ") |")
print("|---" * (len(STRATEGIES) + 2) + "|")
for label in SIZE_ORDER:
    if label not in sizes and not any((s, label) in rows for s in STRATEGIES):
        continue
    cells, ooms = [], []
    for s in STRATEGIES:
        c, o = cell(s, label)
        cells.append(c)
        ooms.append(o)
    actual = sizes.get(label)
    shown = f"{label} ({actual/1024/1024:.1f} MB actual)" if actual else label
    print(f"| {shown} | " + " | ".join(cells) + " | " + " / ".join(ooms) + " |")

print()
print("Peak heap = median over completed iterations of (peak Java heap during parse − settled baseline).")
print("Raw CSV:", sys.argv[1])
EOF
