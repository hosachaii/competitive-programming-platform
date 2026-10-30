#!/usr/bin/env bash
#
# End-to-end smoke test for the competitive programming platform.
#
# Exercises the full public API: register -> login -> fetch problems -> submit.
# For the "Sum of Two Numbers" problem it submits, in C++, Java and Python:
#   (a) a correct solution      -> ACCEPTED
#   (b) a wrong answer          -> WRONG_ANSWER
#   (c) an infinite loop        -> TIME_LIMIT_EXCEEDED
#   (d) a compile error         -> COMPILE_ERROR  (Python: CE once py_compile is wired, else RUNTIME_ERROR)
#   (e) a runtime error         -> RUNTIME_ERROR
# then polls each submission to a terminal verdict and asserts it is correct.
#
# Usage:   BASE_URL=http://localhost:8080 scripts/smoke-test.sh
#   BASE_URL defaults to http://localhost:8080 (host-run backend). For the
#   docker-compose stack the backend is also published on :8080.
#
# Needs: python3, and (for orphan cleanup only) docker access. Run under the
# docker group if the backend left any sandbox containers running, e.g.
#   sg docker -c 'scripts/smoke-test.sh'
#
# Exits 0 if every verdict matches, 1 otherwise.

set -u
export BASE_URL="${BASE_URL:-http://localhost:8080}"

python3 - <<'PYEOF'
import json, os, sys, time, urllib.request, urllib.error, random, subprocess

BASE = os.environ["BASE_URL"].rstrip("/")
SLUG = "sum-of-two-numbers"
TERMINAL = {"ACCEPTED","WRONG_ANSWER","TIME_LIMIT_EXCEEDED","MEMORY_LIMIT_EXCEEDED",
            "OUTPUT_LIMIT_EXCEEDED","RUNTIME_ERROR","COMPILE_ERROR","INTERNAL_ERROR"}

def http(method, path, body=None, token=None):
    url = BASE + path
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            raw = r.read().decode()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try: parsed = json.loads(raw)
        except Exception: parsed = raw
        return e.code, parsed

# ---- Solutions keyed by (language, scenario) --------------------------------
CPP = {
 "correct": "#include <iostream>\nint main(){long long a,b;std::cin>>a>>b;std::cout<<a+b<<std::endl;return 0;}\n",
 "wrong":   "#include <iostream>\nint main(){long long a,b;std::cin>>a>>b;std::cout<<(a+b+1)<<std::endl;return 0;}\n",
 "loop":    "int main(){volatile long long x=0;while(true){x++;}return 0;}\n",
 "ce":      "int main(){ return 0 }\n",
 "re":      "#include <cstdlib>\nint main(){std::abort();return 0;}\n",
}
PYTHON = {
 "correct": "a,b=map(int,input().split())\nprint(a+b)\n",
 "wrong":   "a,b=map(int,input().split())\nprint(a+b+1)\n",
 "loop":    "x=0\nwhile True:\n    x+=1\n",
 "ce":      "def f(:\n    pass\n",
 "re":      "a,b=map(int,input().split())\nprint(a//0)\n",
}
JAVA = {
 "correct": "import java.util.*;\npublic class Main{public static void main(String[] a){Scanner s=new Scanner(System.in);long x=s.nextLong(),y=s.nextLong();System.out.println(x+y);}}\n",
 "wrong":   "import java.util.*;\npublic class Main{public static void main(String[] a){Scanner s=new Scanner(System.in);long x=s.nextLong(),y=s.nextLong();System.out.println(x+y+1);}}\n",
 "loop":    "public class Main{public static void main(String[] a){long i=0;while(true){i++;}}}\n",
 "ce":      "public class Main{public static void main(String[] a){ int x = ; }}\n",
 "re":      "public class Main{public static void main(String[] a){throw new RuntimeException(\"boom\");}}\n",
}
SRC = {"CPP": CPP, "PYTHON": PYTHON, "JAVA": JAVA}

# Expected verdict per scenario. Python has no real compile phase until the
# py_compile step is added (Phase 3), so a syntax error surfaces as RUNTIME_ERROR
# there; accept either so the test is stable across phases.
def expected(lang, scenario):
    base = {
        "correct": {"ACCEPTED"},
        "wrong":   {"WRONG_ANSWER"},
        "loop":    {"TIME_LIMIT_EXCEEDED"},
        "ce":      {"COMPILE_ERROR"},
        "re":      {"RUNTIME_ERROR"},
    }[scenario]
    if lang == "PYTHON" and scenario == "ce":
        return {"COMPILE_ERROR", "RUNTIME_ERROR"}
    return base

def poll(sid, token, timeout=90):
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        st, body = http("GET", f"/api/submissions/{sid}", token=token)
        if st == 200 and isinstance(body, dict):
            last = body.get("status")
            if last in TERMINAL:
                return last
        time.sleep(1.0)
    return last or "TIMEOUT_POLLING"

def cleanup_orphans():
    # The pre-Phase-3 judge leaves infinite-loop sandbox containers running on
    # timeout. Best-effort reap so repeated runs don't pile up CPU load.
    for img in ("judge-runner-cpp","judge-runner-python","judge-runner-java"):
        try:
            out = subprocess.run(["docker","ps","-q","--filter",f"ancestor={img}"],
                                  capture_output=True, text=True, timeout=15).stdout.split()
            for cid in out:
                subprocess.run(["docker","rm","-f",cid], capture_output=True, timeout=15)
        except Exception:
            pass

results = []
def check(name, ok, detail=""):
    results.append((name, ok, detail))
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f" -- {detail}" if detail else ""))

print(f"== Smoke test against {BASE} ==")

# 1) Register
email = f"smoke_{int(time.time())}_{random.randint(1000,9999)}@test.local"
st, body = http("POST", "/api/auth/register",
                {"email": email, "username": f"sm{random.randint(1000,99999)}", "password": "password123"})
check("register", st == 200 and isinstance(body, dict) and "accessToken" in body, f"HTTP {st}")
if st != 200:
    print("register failed, aborting:", body); sys.exit(1)
token = body["accessToken"]
refresh = body["refreshToken"]

# 2) Login
st, body = http("POST", "/api/auth/login", {"email": email, "password": "password123"})
check("login", st == 200 and "accessToken" in (body or {}), f"HTTP {st}")
if st == 200: token = body["accessToken"]

# 3) Refresh token
st, body = http("POST", "/api/auth/refresh", {"refreshToken": refresh})
check("refresh-token", st == 200 and "accessToken" in (body or {}), f"HTTP {st}")

# 4) Fetch problems + resolve the target problem id
st, body = http("GET", "/api/problems")
have = st == 200 and isinstance(body, dict) and body.get("totalElements", 0) >= 1
check("list-problems", have, f"HTTP {st}, total={body.get('totalElements') if isinstance(body,dict) else '?'}")
st, detail = http("GET", f"/api/problems/{SLUG}")
check("get-problem", st == 200 and isinstance(detail, dict) and "id" in detail, f"HTTP {st}")
if st != 200:
    print("cannot load target problem, aborting"); sys.exit(1)
pid = detail["id"]

# 5) Submit the matrix of (language x scenario), one at a time.
# Sequential submit+poll keeps results deterministic and stops infinite-loop
# sandbox containers from piling up and starving concurrent judging. Orphans
# are reaped after each case (see cleanup_orphans).
scenarios = ["correct","wrong","loop","ce","re"]
langs = ["CPP","PYTHON","JAVA"]
print("-- submitting + polling each case sequentially (loop cases take a few seconds) --")
for lang in langs:
    for sc in scenarios:
        exp = expected(lang, sc)
        st, body = http("POST", "/api/submissions",
                        {"problemId": pid, "language": lang, "sourceCode": SRC[lang][sc]}, token=token)
        if st != 200 or not isinstance(body, dict):
            check(f"{lang}/{sc} -> verdict", False, f"submit HTTP {st}: {body}")
            continue
        verdict = poll(body["id"], token)
        check(f"{lang}/{sc} -> verdict", verdict in exp, f"got {verdict}, expected {sorted(exp)}")
        cleanup_orphans()

npass = sum(1 for _,ok,_ in results if ok)
ntot = len(results)
print(f"\n== {npass}/{ntot} checks passed ==")
sys.exit(0 if npass == ntot else 1)
PYEOF
