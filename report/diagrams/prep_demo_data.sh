#!/usr/bin/env bash
# Seeds realistic demo data for the report screenshots: a few named users with
# solves (so the leaderboard is non-empty) plus three showcase submissions by one
# user (Accepted, Compile Error, Time Limit Exceeded). Writes demo_data.json with
# the tokens and submission ids the screenshot script needs.
#
# Assumes the stack is running (backend :8080) on a freshly seeded database.
set -u
cd "$(dirname "$0")"
python3 - <<'PY'
import json, time, urllib.request, urllib.error

API = "http://localhost:8080"
def http(method, path, body=None, token=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(API+path, data=data, method=method)
    req.add_header("Content-Type","application/json")
    if token: req.add_header("Authorization","Bearer "+token)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status, json.loads(r.read().decode() or "null")
    except urllib.error.HTTPError as e:
        try: return e.code, json.loads(e.read().decode())
        except Exception: return e.code, None

def register(email, username, pw="password123"):
    st, b = http("POST","/api/auth/register",{"email":email,"username":username,"password":pw})
    if st != 200:
        st, b = http("POST","/api/auth/login",{"email":email,"password":pw})
    return {"token": b["accessToken"], "refresh": b["refreshToken"],
            "user": {"id": b["userId"], "username": b["username"], "role": b["role"]}}

def login(email, pw):
    st, b = http("POST","/api/auth/login",{"email":email,"password":pw})
    return {"token": b["accessToken"], "refresh": b["refreshToken"],
            "user": {"id": b["userId"], "username": b["username"], "role": b["role"]}}

TERMINAL = {"ACCEPTED","WRONG_ANSWER","TIME_LIMIT_EXCEEDED","MEMORY_LIMIT_EXCEEDED",
            "OUTPUT_LIMIT_EXCEEDED","RUNTIME_ERROR","COMPILE_ERROR","INTERNAL_ERROR"}
def submit_wait(token, pid, lang, src, timeout=60):
    st, b = http("POST","/api/submissions",{"problemId":pid,"language":lang,"sourceCode":src}, token=token)
    if st != 200:
        raise SystemExit(f"submit failed {st}: {b}")
    sid = b["id"]
    deadline = time.time()+timeout
    v=None
    while time.time()<deadline:
        _, s = http("GET", f"/api/submissions/{sid}", token=token)
        if isinstance(s, dict):
            v = s.get("status")
            if v in TERMINAL: break
        time.sleep(1)
    return sid, v

SUM = "#include <iostream>\nusing namespace std;\n\nint main() {\n    long long a, b;\n    cin >> a >> b;\n    cout << a + b << endl;\n    return 0;\n}\n"
REV = "#include <iostream>\n#include <algorithm>\n#include <string>\nusing namespace std;\n\nint main() {\n    string s;\n    getline(cin, s);\n    reverse(s.begin(), s.end());\n    cout << s << endl;\n    return 0;\n}\n"
MAX3 = "#include <iostream>\n#include <algorithm>\nusing namespace std;\n\nint main() {\n    long long a, b, c;\n    cin >> a >> b >> c;\n    cout << max(a, max(b, c)) << endl;\n    return 0;\n}\n"
CE  = "#include <iostream>\nusing namespace std;\n\nint main() {\n    cout << \"Hello\"   // missing semicolon\n    return 0;\n}\n"
TLE = "int main() {\n    volatile long long x = 0;\n    while (true) { x++; }\n    return 0;\n}\n"

admin = login("admin@cp.local","admin12345")
alice = register("alice@demo.local","alice")
bob   = register("bob@demo.local","bob")
charlie = register("charlie@demo.local","charlie")

# leaderboard data (respect the 5s per-user rate limit by spacing same-user submits)
print("alice solves Sum (AC) ..."); acId, v = submit_wait(alice["token"], 1, "CPP", SUM); print("  ", v)
time.sleep(6)
print("alice solves Reverse (AC) ..."); _, v = submit_wait(alice["token"], 2, "CPP", REV); print("  ", v)
print("bob solves Sum (AC) ...");   _, v = submit_wait(bob["token"], 1, "CPP", SUM); print("  ", v)
print("charlie solves Max3 (AC) ..."); _, v = submit_wait(charlie["token"], 3, "CPP", MAX3); print("  ", v)

time.sleep(6)
print("alice Compile Error ..."); ceId, v = submit_wait(alice["token"], 1, "CPP", CE); print("  ", v)
time.sleep(6)
print("alice Time Limit Exceeded ..."); tleId, v = submit_wait(alice["token"], 1, "CPP", TLE); print("  ", v)

data = {
    "base": "http://localhost:3000",
    "problemSlug": "sum-of-two-numbers",
    "editProblemId": 1,
    "admin": admin,
    "alice": alice,
    "acId": acId,
    "ceId": ceId,
    "tleId": tleId,
    "aceCode": SUM,
}
with open("demo_data.json","w") as f:
    json.dump(data, f, indent=2)
print("\nwrote demo_data.json:", {"acId":acId,"ceId":ceId,"tleId":tleId})
PY
