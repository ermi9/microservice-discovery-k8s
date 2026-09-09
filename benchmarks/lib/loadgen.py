#!/usr/bin/env python3
"""
Concurrent HTTP load generator — standard library only (no pip installs).

Fires a fixed number of requests at a URL across N worker threads for a set
duration (or request count) and reports throughput and latency percentiles.

Usage:
    loadgen.py --url http://localhost:8080/services --concurrency 50 --duration 20
    loadgen.py --url http://localhost:8080/services --concurrency 50 --requests 20000

Output: a single JSON object on stdout, e.g.
    {"url": "...", "concurrency": 50, "wall_seconds": 20.0, "requests": 90114,
     "errors": 0, "throughput_rps": 4505.7,
     "latency_ms": {"p50": 9.8, "p95": 21.4, "p99": 33.1, "max": 88.0}}
"""
import argparse
import json
import statistics
import sys
import threading
import time
import urllib.request

_stop = threading.Event()


def _percentile(sorted_vals, pct):
    if not sorted_vals:
        return 0.0
    k = (len(sorted_vals) - 1) * (pct / 100.0)
    lo = int(k)
    hi = min(lo + 1, len(sorted_vals) - 1)
    frac = k - lo
    return sorted_vals[lo] + (sorted_vals[hi] - sorted_vals[lo]) * frac


def _worker(url, timeout, max_requests, counters, latencies, lock):
    while not _stop.is_set():
        if max_requests is not None and counters["sent"] >= max_requests:
            return
        with lock:
            if max_requests is not None and counters["sent"] >= max_requests:
                return
            counters["sent"] += 1
        start = time.perf_counter()
        try:
            with urllib.request.urlopen(url, timeout=timeout) as resp:
                resp.read()
                ok = 200 <= resp.status < 400
        except Exception:
            ok = False
        elapsed_ms = (time.perf_counter() - start) * 1000.0
        with lock:
            if ok:
                counters["ok"] += 1
                latencies.append(elapsed_ms)
            else:
                counters["errors"] += 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", required=True)
    ap.add_argument("--concurrency", type=int, default=50)
    ap.add_argument("--duration", type=float, default=None, help="seconds")
    ap.add_argument("--requests", type=int, default=None, help="total request cap")
    ap.add_argument("--timeout", type=float, default=5.0)
    args = ap.parse_args()

    if args.duration is None and args.requests is None:
        args.duration = 20.0

    counters = {"sent": 0, "ok": 0, "errors": 0}
    latencies = []
    lock = threading.Lock()
    _stop.clear()

    threads = [
        threading.Thread(
            target=_worker,
            args=(args.url, args.timeout, args.requests, counters, latencies, lock),
            daemon=True,
        )
        for _ in range(args.concurrency)
    ]

    wall_start = time.perf_counter()
    for t in threads:
        t.start()

    if args.duration is not None:
        time.sleep(args.duration)
        _stop.set()
    for t in threads:
        t.join()
    wall = time.perf_counter() - wall_start

    latencies.sort()
    ok = counters["ok"]
    result = {
        "url": args.url,
        "concurrency": args.concurrency,
        "wall_seconds": round(wall, 3),
        "requests": ok,
        "errors": counters["errors"],
        "throughput_rps": round(ok / wall, 1) if wall > 0 else 0.0,
        "latency_ms": {
            "p50": round(_percentile(latencies, 50), 2),
            "p95": round(_percentile(latencies, 95), 2),
            "p99": round(_percentile(latencies, 99), 2),
            "mean": round(statistics.fmean(latencies), 2) if latencies else 0.0,
            "max": round(latencies[-1], 2) if latencies else 0.0,
        },
    }
    json.dump(result, sys.stdout)
    sys.stdout.write("\n")


if __name__ == "__main__":
    main()
