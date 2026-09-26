#!/usr/bin/env python3

"""Prepare local JWTs outside the measured interval without printing credentials or tokens."""

from __future__ import annotations

import concurrent.futures
import json
import os
import pathlib
import sys
import urllib.error
import urllib.request


def login(base_url: str, email: str, password: str) -> str:
    body = json.dumps({"email": email, "password": password}).encode()
    request = urllib.request.Request(
        f"{base_url}/api/auth/login",
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            payload = json.load(response)
    except urllib.error.HTTPError as error:
        raise RuntimeError(f"login failed for generated user with HTTP {error.code}") from error
    token = payload.get("accessToken")
    if not isinstance(token, str) or not token:
        raise RuntimeError("login response did not contain an access token")
    return token


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: prepare-tokens.py <runtime-directory> <token-count>", file=sys.stderr)
        return 2
    runtime_dir = pathlib.Path(sys.argv[1])
    token_count = int(sys.argv[2])
    fixture = json.loads((runtime_dir / "fixture.json").read_text())
    if token_count < 1 or token_count > int(fixture["userCount"]):
        raise ValueError("token count must be between 1 and prepared userCount")

    base_url = os.environ.get("BASE_URL", "http://127.0.0.1:8080")
    workers = min(int(os.environ.get("TOKEN_WORKERS", "16")), token_count)
    emails = [f"perf-user-{index:06d}@example.invalid" for index in range(1, token_count + 1)]
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as executor:
        tokens = list(executor.map(lambda email: login(base_url, email, fixture["password"]), emails))

    output = runtime_dir / "tokens.json"
    output.write_text(json.dumps({"tokens": tokens}, separators=(",", ":")))
    output.chmod(0o600)
    print(f"Prepared {len(tokens)} JWTs outside the measured interval using {workers} workers.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
