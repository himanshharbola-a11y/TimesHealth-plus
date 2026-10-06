#!/usr/bin/env python3
"""
Write-path parity: run the same sequence of changes against the Node API and
the Kotlin API, each with its OWN fresh QA user, and compare every answer.

    python apps/server/scripts/parity_writes.py [NODE_BASE] [KOTLIN_BASE]

Creates throwaway users named qa_par_<time>_*; each sequence ends by deleting
its account, so nothing is left behind except detached test orders (none here).
Ids differ between the two users by nature and are ignored.
"""
import json
import sys
import time
import urllib.error
import urllib.request

NODE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:4000"
KOTLIN = sys.argv[2] if len(sys.argv) > 2 else "http://localhost:4100"
RUN = int(time.time())
IGNORED_KEYS = {"serverTime", "time", "id"}


def call(base, method, path, token, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(base + path, data=data, method=method)
    req.add_header("Accept", "application/json")
    if data is not None:
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw or b"null")
        except ValueError:
            return e.code, raw.decode(errors="replace")[:200]


def diff(a, b, path=""):
    if isinstance(a, dict) and isinstance(b, dict):
        for k in sorted(set(a) | set(b)):
            if k in IGNORED_KEYS:
                continue
            if k not in a or k not in b:
                yield f"{path}.{k}: node={json.dumps(a.get(k))[:100]} kotlin={json.dumps(b.get(k))[:100]}"
            else:
                yield from diff(a[k], b[k], f"{path}.{k}")
    elif isinstance(a, list) and isinstance(b, list):
        if len(a) != len(b):
            yield f"{path}: list length node={len(a)} kotlin={len(b)}"
        for i, (x, y) in enumerate(zip(a, b)):
            yield from diff(x, y, f"{path}[{i}]")
    elif a != b:
        yield f"{path}: node={json.dumps(a)[:100]} kotlin={json.dumps(b)[:100]}"


def user(tag):
    # Same email local part on both sides would collide in the shared DB, so the
    # side is part of the uid AND the email; the comparison ignores both.
    return lambda side: f"qa_par_{RUN}_{tag}_{side}|par{RUN}{tag}{side}@th.test|"


# (label, method, path, body) run in order for one user.
STEPS = [
    ("session (new user)", "GET", "/v1/session", None),
    ("onboarding step 1", "POST", "/v1/onboarding/step", {"step": 1, "name": "Asha Rao"}),
    ("onboarding step 2 bad phone", "POST", "/v1/onboarding/step", {"step": 2, "phone": "12345678"}),
    ("onboarding step 2", "POST", "/v1/onboarding/step", {"step": 2, "phone": "098765 43210"}),
    ("onboarding step 3", "POST", "/v1/onboarding/step", {"step": 3, "healthGoal": "CONSISTENCY"}),
    ("onboarding bad goal", "POST", "/v1/onboarding/step", {"step": 3, "healthGoal": "FLY"}),
    ("onboarding step 4", "POST", "/v1/onboarding/step", {"step": 4, "concern": "LOWER_BACK"}),
    ("profile: dob", "PATCH", "/v1/profile", {"dob": "1990-06-15T00:00:00.000Z"}),
    ("profile: future dob", "PATCH", "/v1/profile", {"dob": "2099-01-01T00:00:00.000Z"}),
    ("profile: gender + units", "PATCH", "/v1/profile", {"gender": "FEMALE", "units": "IMPERIAL"}),
    ("profile: change login email", "PATCH", "/v1/profile", {"email": "someone.else@example.com"}),
    ("profile: +91 typo phone", "PATCH", "/v1/profile", {"phone": "+91 58765 43210"}),
    ("profile: empty body", "PATCH", "/v1/profile", {}),
    ("session (after edits)", "GET", "/v1/session", None),
    ("onboarding skip", "POST", "/v1/onboarding/skip", {}),
    ("delete account", "DELETE", "/v1/account", None),
    ("session after delete", "GET", "/v1/session", None),
]

failures = 0
for tag in ["a"]:
    make = user(tag)
    node_tok, kt_tok = make("n"), make("k")
    for label, method, path, body in STEPS:
        sa, ja = call(NODE, method, path, node_tok, body)
        sb, jb = call(KOTLIN, method, path, kt_tok, body)
        problems = ([f"status node={sa} kotlin={sb}"] if sa != sb else []) + list(diff(ja, jb))
        # The name/email fields legitimately differ (n vs k side); drop those lines.
        problems = [p for p in problems if "par" not in p]
        if problems:
            failures += 1
            print(f"FAIL {label}")
            for p in problems[:10]:
                print("    " + p)
        else:
            print(f"OK   {label} - {sa}")
print(f"\n{len(STEPS) - failures}/{len(STEPS)} identical")
sys.exit(1 if failures else 0)
