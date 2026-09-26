#!/usr/bin/env python3

"""Print a compact, sanitized row from a k6 summary export."""

from __future__ import annotations

import json
import pathlib
import sys


def value(metrics: dict, metric: str, key: str, default: float = 0.0) -> float:
    data = metrics.get(metric, {})
    return data.get("values", data).get(key, default)


path = pathlib.Path(sys.argv[1])
document = json.loads(path.read_text())
metrics = document["metrics"]
requests = value(metrics, "http_reqs", "count")
rate = value(metrics, "http_reqs", "rate")
print(
    json.dumps(
        {
            "run": path.name.removesuffix("-summary.json"),
            "requests": int(requests),
            "requestsPerSecond": round(rate, 2),
            "p50Ms": round(value(metrics, "http_req_duration", "med"), 2),
            "p95Ms": round(value(metrics, "http_req_duration", "p(95)"), 2),
            "p99Ms": round(value(metrics, "http_req_duration", "p(99)"), 2),
            "maxMs": round(value(metrics, "http_req_duration", "max"), 2),
            "httpFailures": int(value(metrics, "http_req_failed", "passes")),
            "expectedBusinessResults": int(value(metrics, "expected_business_results", "count")),
            "unexpectedResponses": int(value(metrics, "unexpected_responses", "count")),
            "serverFailures": int(value(metrics, "server_failures", "count")),
            "clientFailures": int(value(metrics, "client_failures", "count")),
            "timeouts": int(value(metrics, "timeouts", "count")),
            "droppedIterations": int(value(metrics, "dropped_iterations", "count")),
        },
        separators=(",", ":"),
    )
)
