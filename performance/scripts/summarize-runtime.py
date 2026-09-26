#!/usr/bin/env python3

"""Summarize ignored JVM/container and PostgreSQL sampler CSVs without exposing credentials."""

from __future__ import annotations

import csv
import json
import pathlib
import sys


def rows(path: pathlib.Path) -> list[dict[str, float]]:
    with path.open(newline="") as handle:
        result = []
        for row in csv.DictReader(handle):
            result.append({key: float(value) for key, value in row.items() if key != "timestamp"})
        return result


def maximum(data: list[dict[str, float]], name: str) -> float:
    return max((row[name] for row in data), default=0.0)


if len(sys.argv) != 2:
    raise SystemExit("usage: summarize-runtime.py <run-name>")

root = pathlib.Path(__file__).resolve().parents[1] / "results" / "runtime"
name = sys.argv[1]
jvm = rows(root / f"{name}.csv")
postgres = rows(root / f"{name}-postgres.csv")
if not jvm or not postgres:
    raise SystemExit("both sampler files must contain data")

print(json.dumps({
    "run": name,
    "runtimeSamples": len(jvm),
    "postgresSamples": len(postgres),
    "peakCpuPercent": round(maximum(jvm, "app_cpu_percent"), 2),
    "peakRssMiB": round(maximum(jvm, "app_memory_mib"), 2),
    "heapUsedStartMiB": round(jvm[0]["heap_used_mib"], 2),
    "heapUsedEndMiB": round(jvm[-1]["heap_used_mib"], 2),
    "peakHeapUsedMiB": round(maximum(jvm, "heap_used_mib"), 2),
    "peakHeapCommittedMiB": round(maximum(jvm, "heap_committed_mib"), 2),
    "gcCountDelta": round(jvm[-1]["gc_count"] - jvm[0]["gc_count"], 2),
    "gcTimeDeltaSeconds": round(jvm[-1]["gc_time_seconds"] - jvm[0]["gc_time_seconds"], 4),
    "peakLiveThreads": round(maximum(jvm, "live_threads"), 2),
    "peakHikariActive": round(maximum(jvm, "hikari_active"), 2),
    "peakHikariPending": round(maximum(jvm, "hikari_pending"), 2),
    "hikariMax": round(maximum(jvm, "hikari_max"), 2),
    "peakDatabaseSessions": round(maximum(postgres, "sessions"), 2),
    "peakDatabaseActive": round(maximum(postgres, "active"), 2),
    "activeWaitingSamples": sum(1 for row in postgres if row["active_waiting"] > 0),
    "lockWaitingSamples": sum(1 for row in postgres if row["lock_waiting"] > 0),
    "peakUngrantedLocks": round(maximum(postgres, "ungranted_locks"), 2),
    "maxActiveTransactionMs": round(maximum(postgres, "max_active_transaction_ms"), 2),
}, separators=(",", ":")))
