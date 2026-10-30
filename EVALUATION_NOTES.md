# Evaluation Notes — study guide

Plain-language notes on how this project works and what changed today, written so you can
talk through it confidently. Read top to bottom; the last section is a Q&A drill.

---

## 1. What happens when you click "Submit" (full lifecycle)

A submission's journey, with the exact classes/methods involved:

1. **Frontend** — `ProblemDetail.jsx` `submit()` POSTs `{ problemId, language, sourceCode }`
   to `/api/submissions`. The axios instance (`api/client.js`) attaches the JWT access token
   as `Authorization: Bearer <token>`.
2. **Security filter** — `JwtAuthFilter.doFilterInternal` reads the token, validates it
   (`JwtService`), loads the `User`, and puts an authenticated principal in the
   `SecurityContext`. Config lives in `SecurityConfig`.
3. **Controller** — `SubmissionController.submit` runs. `@Valid` checks the request
   (non-null problem/language, non-blank source ≤ 64 KB). `@AuthenticationPrincipal User`
   is the logged-in user.
4. **Service** — `SubmissionService.submit`:
   - `enforceRateLimit(userId)` — rejects with HTTP 429 if the user submitted < 5 s ago.
   - saves a `Submission` row with status `PENDING`,
   - records the submit time for the rate limiter,
   - calls `judgeService.judgeAsync(id)` which returns **immediately** (fire-and-forget),
   - returns a `SubmissionView` so the UI can navigate to the status page.
5. **Async judging** — `JudgeService.judgeAsync` runs on a background thread (`@Async`):
   - `JudgePersistence.beginJudging` (short transaction) marks the submission `RUNNING`
     and reads the source, time limit and all test cases into a detached carrier.
   - Writes the source into a per-submission work dir.
   - **Compile** (C++/Java, and a Python `py_compile` syntax check) via
     `ContainerRunner.run(...)`. Non-zero exit → `JudgePersistence.recordCompileError`,
     stop. The compiler output is stored and shown on the frontend.
   - **Run each test case** in its own sandbox; `JudgeService.mapVerdict` turns each run's
     outcome into a verdict (`OutputComparator.matches` decides AC vs WA).
   - `JudgePersistence.saveResults` (short transaction) writes per-test rows, the overall
     verdict, score and runtime, and updates `UserProgress`. If this was the user's **first**
     solve of the problem it returns their id.
   - On a first solve, `LeaderboardService.onFirstSolve` does a Redis `ZINCRBY` (outside any
     transaction).
   - A `finally` block deletes the work dir.
6. **Frontend polling** — `SubmissionStatus.jsx` polls `GET /api/submissions/{id}` every
   1.5 s until the status is terminal, then stops and renders the verdict, per-test table,
   and any compiler/error output.

**One-line version:** controller saves a PENDING row and fires an async job; the job runs
the code in throwaway Docker sandboxes, decides a verdict, saves it in a short transaction,
and the UI polls until the verdict appears.

---

## 2. JWT access + refresh tokens, step by step

- **On register/login** (`AuthService`) the server issues two tokens (`JwtService`):
  - **access token** — short-lived (15 min). Carries subject=email plus `role`, `userId`,
    `type=access` claims. Sent on every API call.
  - **refresh token** — long-lived (7 days). Carries only `type=refresh`. Used only to get
    a new access token.
  Both are signed with HMAC (`JWT_SECRET`). The server stores **no** session — it only
  verifies the signature and expiry, which is what "stateless" means.
- **Using the access token** — the browser stores both tokens in `localStorage`
  (`AuthContext.jsx`). Every request attaches the access token (`api/client.js`).
  `JwtAuthFilter` validates it and sets the authenticated user for that one request.
- **When the access token expires** — the next API call comes back `401`. The axios response
  interceptor catches the 401, POSTs the refresh token to `/api/auth/refresh`
  (`AuthService.refresh` checks it's a non-expired `type=refresh` token), stores the new
  tokens, and **retries the original request once**. If refresh also fails, it clears storage
  and redirects to `/login`.
- **Why two tokens?** The access token is used constantly, so it's short-lived to limit the
  damage if it leaks. The refresh token is used rarely (only to mint access tokens), so it
  can live longer without the same exposure. It's the standard "short key you use a lot,
  long key you guard" split.

---

## 3. The sandbox — what each Docker flag protects against

Each run is a fresh `docker run --rm` of a runner image, with (`DockerContainerRunner`):

| Flag | What it does | What it stops |
|---|---|---|
| `--network=none` | no network interface | data exfiltration, calling home, downloading payloads |
| `--memory` / `--memory-swap` (256m, equal) | hard memory cap, no swap | a memory bomb taking down the host; hitting it → OOM kill → **MLE** |
| `--cpus=1` | CPU quota | one submission starving others of CPU |
| `--pids-limit=64` | max processes | fork bombs |
| `--read-only` (root fs) + `--tmpfs /tmp` | nothing writable except a small scratch | tampering with the image, writing giant files to disk |
| non-root user (`sandboxuser`, built into the image) | code runs unprivileged | even if code escapes to the fs, it isn't root |
| `-v workdir:/box` | only the submission's dir is shared | code can't see other submissions or host files |
| unique `--name` + wall-clock timeout + `docker rm -f` | kills and removes a stuck container | **TLE**, and no orphaned containers left running |
| 64 KB stdout/stderr cap (app-level, drained on threads) | bounded capture, kill on flood | memory exhaustion from infinite printing; a full-pipe deadlock → **OLE** |

**Verdict decision order** (`JudgeService.mapVerdict`): timeout → output flood → OOM →
other non-zero exit (RE) → output comparison (AC/WA). Order matters because a killed
process can look like several things at once.

---

## 4. Everything changed today, and why

Starting point: the code compiled but had a few real weaknesses. Phase by phase:

- **Phase 1 — build & verify.** Confirmed `mvn clean package` compiles. Added a dev data
  seeder (admin + sample problems) and `scripts/smoke-test.sh` (register/login + 3 languages
  × {AC,WA,TLE,CE,RE}). Fixed `docker-compose` for this machine: parameterized the judge
  workspace path and moved Postgres to port 5433 (details in §Environment gotchas).
- **Phase 2 — the transaction fix (the important one).** `judgeAsync` was both `@Async` and
  `@Transactional`, so **one database transaction — and the pooled DB connection it holds —
  stayed open for the entire judging run** (compiling + running every test case, seconds to
  minutes). Under load that exhausts the connection pool. Fix: `judgeAsync` is now `@Async`
  only, and all DB writes go through short transactions in a **separate bean**
  (`JudgePersistence`): one tx to mark RUNNING + load inputs, then Docker work with **no tx
  held**, then one tx to save results.
  - **The self-invocation gotcha:** Spring's `@Transactional` works via a proxy that wraps
    the bean. If `judgeAsync` just called another `@Transactional` method *on the same
    object*, the call wouldn't go through the proxy, so the annotation would be **silently
    ignored** — no transaction at all. Putting those methods on a *different* bean means the
    call goes through that bean's proxy, so the transactions actually apply. That's why
    `JudgePersistence` is its own class.
  - Also: any unexpected failure now routes the submission to `INTERNAL_ERROR`, so it can
    never get stuck in `RUNNING`.
- **Phase 3 — judge robustness.** Unique container names + `docker rm -f` so timed-out
  containers are actually killed (verified `docker ps -a` is clean). 64 KB output cap drained
  on background threads (stops memory exhaustion *and* a classic full-pipe deadlock) → `OLE`.
  Full verdict set incl. `MLE` (OOM = exit 137) and `OLE`. Python now `py_compile`s first so
  syntax errors are `CE` like the compiled languages. Per-user rate limit → 429. Workspace
  cleanup. Compiler output shown on the frontend.
- **Phase 4 — Redis leaderboard.** Redis sorted set; `ZINCRBY` only on a user's *first*
  accept of a problem; `ZREVRANGE WITHSCORES` to read with one batched username lookup;
  rebuilt from Postgres on startup (Redis is a cache, Postgres is truth); **falls back to
  Postgres** if Redis is down (with bounded 800 ms timeouts so the fallback is fast).
- **Phase 5 — tests.** Extracted sandbox execution behind a `ContainerRunner` interface so
  the judging flow is unit-testable with Docker mocked. Added unit + `@SpringBootTest` (H2)
  tests; `mvn test` is green.
- **Phase 6 — polish.** One consistent JSON error shape; Spring Security exceptions mapped
  correctly (wrong password → 401, not 500); 64 KB max source-code validation; seeded admin
  credentials made env-overridable; confirmed JWT secret & DB password come from env.

### Environment gotchas worth knowing (they came up today)

- **snap-packaged Docker** can only bind-mount sources under `$HOME`. The default
  `/tmp/judge-workspace` silently mounts an *empty* dir (the compile step then fails with
  "solution.cpp: No such file or directory"). Fix: set `JUDGE_WORKSPACE` / `JUDGE_WORKSPACE_HOST`
  under your home directory. This is exactly the "paths must match what the host daemon sees"
  caveat the compose file warns about.
- **Hibernate enum CHECK constraints** (from `ddl-auto=update`) don't evolve when you add a
  new enum value (e.g. `OUTPUT_LIMIT_EXCEEDED`), so an *existing* DB rejects it. A fresh
  schema is fine. Production answer: Flyway migrations.

---

## 5. Design tradeoffs and how to defend them

- **Monolith vs microservices.** One Spring Boot app is far simpler to build, run, and
  reason about, and it's the right default at this scale. The judging logic is already
  isolated (`JudgeService` / `ContainerRunner`), so it could be peeled off into its own
  service later without a rewrite. "Start simple, keep the seam."
- **`@Async` vs Kafka.** `@Async` gives async judging in one line and is perfect for a
  single instance. A queue (Kafka) earns its complexity when you need *multiple judge
  workers*, backpressure, retries, or durability across restarts. I deliberately didn't pay
  that cost yet, but the submit→judge boundary is a clean place to drop a queue in.
- **Polling vs WebSockets.** A 1.5 s poll is trivial to implement and totally fine for a demo
  and modest traffic. WebSockets/SSE remove the latency and wasted requests but add
  connection state and infra. A reasonable "later, when it matters" upgrade.
- **Docker-socket mount risk.** Mounting the host Docker socket into the backend is the
  pragmatic way to launch sandboxes in dev, but it's effectively host-root — so it's dev-only,
  and production should use a dedicated judge VM or a purpose-built sandbox (gVisor / nsjail /
  `isolate`). I call this out rather than hiding it.
- **One container per test case vs per submission.** Per-test-case is simple and isolates
  each case, at the cost of container-startup overhead per test. Batching all cases into one
  container is the optimization when startup cost dominates.
- **In-memory rate limit.** Fine per-instance; a multi-instance deploy would move the window
  into Redis (already provisioned) so it's shared across nodes.

---

## 6. 15 likely questions, with short answers

1. **Walk me through a submission.** → §1. Controller saves PENDING + fires async job; job
   runs code in Docker sandboxes, decides a verdict via `mapVerdict`, saves in a short
   transaction; UI polls until terminal.

2. **What was the transaction bug and how did you fix it?** → `judgeAsync` was `@Async` +
   `@Transactional`, holding a DB transaction/connection open across all the Docker runs.
   Fixed by making judging hold no transaction and doing DB writes in short transactions in a
   separate `JudgePersistence` bean.

3. **What's the self-invocation gotcha?** → `@Transactional` is applied by a proxy; a method
   calling another method on the *same* bean bypasses the proxy, so the annotation is
   ignored. Moving the transactional methods to a different bean makes them actually work.

4. **How do access and refresh tokens differ?** → Access = short-lived, sent on every
   request; refresh = long-lived, only used to mint new access tokens. Limits exposure of the
   token you use most. On 401 the frontend refreshes once and retries.

5. **Where's the token validated and where's it stored?** → Validated server-side in
   `JwtAuthFilter`/`JwtService` (signature + expiry, no server session). Stored client-side in
   `localStorage`.

6. **How is code execution sandboxed?** → Throwaway Docker container per run with
   `--network=none`, memory/cpu/pids caps, read-only rootfs, non-root user, only the
   submission dir mounted, and a wall-clock timeout. See §3 for per-flag reasoning.

7. **How do you detect TLE / MLE / OLE?** → TLE: the process doesn't finish within the wall
   clock, so we kill + remove it. MLE: the kernel OOM-kills it (exit 137). OLE: it writes past
   the 64 KB output cap, so we kill it. `mapVerdict` applies these in priority order.

8. **How do you stop a runaway/infinite-output program from hurting the host?** → Memory cap
   (no swap), CPU quota, pids limit, a wall-clock timeout that force-removes the container,
   and a 64 KB output cap drained on background threads so a flood can't exhaust memory or
   deadlock the pipe.

9. **Why Redis for the leaderboard?** → A sorted set gives O(log n) updates and ranked reads
   vs scanning Postgres. `ZINCRBY` on first solve, `ZREVRANGE` to read. Postgres stays the
   source of truth; Redis is rebuilt from it on startup and falls back to it if down.

10. **What if Redis goes down?** → Reads log a warning and fall back to the Postgres ranking;
    timeouts are bounded to 800 ms so it fails fast. On restart the sorted set is rebuilt from
    Postgres.

11. **How does the output comparison work?** → `OutputComparator` normalizes line endings
    (CRLF/CR→LF), strips trailing spaces/tabs per line and surrounding blank space, but keeps
    whitespace *within* a line — the usual lenient-but-safe judge comparison.

12. **Why a monolith / why not Kafka / why polling?** → §5: simplest right choice at this
    scale, with clean seams to upgrade (queue at submit→judge, SSE for push) when the need is
    real.

13. **How do you prevent abuse / spam submissions?** → Per-user rate limit (1 per 5 s → HTTP
    429 with a clear message), 64 KB source cap, and admin-only problem writes.

14. **What did you test and how, without Docker?** → Extracted `ContainerRunner` so the judge
    flow is tested with Docker mocked (AC/WA/TLE/CE). Plus unit tests for comparison, verdict
    mapping, and first-solve-only scoring, and a `@SpringBootTest` H2 auth-flow test. `mvn test`.

15. **What would you do next / what are the known weaknesses?** → Queue-based judging for
    horizontal scale, SSE instead of polling, a safer sandbox than the host Docker socket
    (gVisor/nsjail/isolate or a dedicated judge VM), one-container-per-submission, and Flyway
    migrations (so enum/schema changes are safe in prod).

---

### Quick credentials / commands cheat-sheet

- Seeded admin: `admin@cp.local` / `admin12345`. Register your own USER to submit.
- Demo: build the 3 runner images → `docker compose up --build` → UI at :3000, API at :8080.
- Tests: `cd backend && mvn test`.
- Smoke: `BASE_URL=http://localhost:8080 scripts/smoke-test.sh`.
