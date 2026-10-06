#!/usr/bin/env python3
"""
Parity check: send the same requests to the Node API and the Kotlin API (both
pointed at the same database) and diff the JSON answers.

    python apps/server/scripts/parity.py [NODE_BASE] [KOTLIN_BASE]

Defaults: http://localhost:4000 and http://localhost:4100. Read-only requests
only, so it is safe to run against any environment. Timestamps that differ by
nature (serverTime, time) are ignored.
"""
import json
import sys
import urllib.error
import urllib.request

NODE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:4000"
KOTLIN = sys.argv[2] if len(sys.argv) > 2 else "http://localhost:4100"

PERSONAS = {
    "free": "qa_free|free@th.test|+919000000001",
    "yoga": "qa_yoga|yoga@th.test|+919000000002",
    "marathon": "qa_marathon|marathon@th.test|+919000000003",
    "both": "qa_both|both@th.test|+919000000004",
    "expired": "qa_expired|expired@th.test|+919000000005",
    "finisher": "qa_finisher|finisher@th.test|+919000000006",
}

# (label, path, token) — extend as more routes are ported to Kotlin.
CASES = [("health", "/health", None), ("config", "/v1/config", None)]
CASES += [(f"session.{p}", "/v1/session", t) for p, t in PERSONAS.items()]
CASES += [
    ("no token", "/v1/session", None),
    ("persona with a real email", "/v1/session", "qa_x|someone@gmail.com|"),
    ("forged jwt", "/v1/session", "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ4In0.ZmFrZQ"),
    ("unknown route", "/v1/no-such-route", PERSONAS["free"]),
]

IGNORED_KEYS = {"serverTime", "time"}


def fetch(base, path, token):
    req = urllib.request.Request(base + path, headers={"Accept": "application/json"})
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e:
        body = e.read()
        try:
            return e.code, json.loads(body or b"null")
        except ValueError:
            return e.code, body.decode(errors="replace")[:200]


def diff(a, b, path=""):
    """Yield human-readable differences between two JSON values."""
    if isinstance(a, dict) and isinstance(b, dict):
        for k in sorted(set(a) | set(b)):
            if k in IGNORED_KEYS:
                continue
            if k not in a:
                yield f"{path}.{k}: only in kotlin = {json.dumps(b[k])[:120]}"
            elif k not in b:
                yield f"{path}.{k}: only in node = {json.dumps(a[k])[:120]}"
            else:
                yield from diff(a[k], b[k], f"{path}.{k}")
    elif isinstance(a, list) and isinstance(b, list):
        if len(a) != len(b):
            yield f"{path}: list length node={len(a)} kotlin={len(b)}"
        for i, (x, y) in enumerate(zip(a, b)):
            yield from diff(x, y, f"{path}[{i}]")
    elif a != b:
        yield f"{path}: node={json.dumps(a)[:120]} kotlin={json.dumps(b)[:120]}"


failures = 0
for label, path, token in CASES:
    (sa, ja), (sb, jb) = fetch(NODE, path, token), fetch(KOTLIN, path, token)
    problems = ([f"status node={sa} kotlin={sb}"] if sa != sb else []) + list(diff(ja, jb))
    if problems:
        failures += 1
        print(f"FAIL {label} ({path})")
        for p in problems[:12]:
            print("    " + p)
    else:
        print(f"OK   {label} ({path}) - {sa}")
print(f"\n{len(CASES) - failures}/{len(CASES)} identical")
sys.exit(1 if failures else 0)
