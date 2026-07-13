#!/usr/bin/env python3
"""
Aggregate a file of newline-delimited numeric samples (milliseconds) into
percentiles. Used by the timing-based scenarios (propagation, failover,
detection, onboarding).

Usage:
    collector.py --label "Route propagation" --unit ms samples.txt
    collector.py --label "Failover" --unit ms --json samples.txt

Prints a one-line Markdown table row by default, or a JSON object with --json.
"""
import argparse
import json
import statistics
import sys


def percentile(vals, pct):
    if not vals:
        return 0.0
    s = sorted(vals)
    k = (len(s) - 1) * (pct / 100.0)
    lo = int(k)
    hi = min(lo + 1, len(s) - 1)
    return s[lo] + (s[hi] - s[lo]) * (k - lo)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("file")
    ap.add_argument("--label", default="metric")
    ap.add_argument("--unit", default="ms")
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args()

    vals = []
    with open(args.file) as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            try:
                vals.append(float(line))
            except ValueError:
                pass

    if not vals:
        sys.exit(f"no numeric samples in {args.file}")

    stats = {
        "label": args.label,
        "unit": args.unit,
        "n": len(vals),
        "min": round(min(vals), 2),
        "p50": round(percentile(vals, 50), 2),
        "p95": round(percentile(vals, 95), 2),
        "p99": round(percentile(vals, 99), 2),
        "mean": round(statistics.fmean(vals), 2),
        "max": round(max(vals), 2),
    }

    if args.json:
        json.dump(stats, sys.stdout)
        sys.stdout.write("\n")
    else:
        print(
            f"| {stats['label']} | {stats['n']} | {stats['p50']} | "
            f"{stats['p95']} | {stats['p99']} | {stats['max']} | {args.unit} |"
        )


if __name__ == "__main__":
    main()
