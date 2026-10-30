# Competitive Programming Platform

Full-stack online judge: React frontend, Spring Boot backend, and Docker-per-submission
sandboxed code execution (C++, Java, Python). Users register, solve problems, submit code,
and get an automatic verdict; a Redis-backed leaderboard ranks solvers.

## Features

- **Auth** — register / login / refresh with JWT (short-lived access token + long-lived
  refresh token), BCrypt password hashing, role-based access (`USER` / `ADMIN`) via Spring
  Security. Stateless; 401 vs 403 handled distinctly.
- **Problems** — CRUD (admin-only writes), tags, difficulty, public + hidden test cases,
  pagination. Non-admins only ever see public sample cases.
- **Submissions & judging** — submit code → judged asynchronously, one locked-down Docker
  container per compile step and per test case → poll for the verdict. Verdicts:
  `ACCEPTED`, `WRONG_ANSWER`, `TIME_LIMIT_EXCEEDED`, `MEMORY_LIMIT_EXCEEDED`,
  `OUTPUT_LIMIT_EXCEEDED`, `RUNTIME_ERROR`, `COMPILE_ERROR`, `INTERNAL_ERROR`.
- **Leaderboard** — Redis sorted set (`ZINCRBY` on a user's first accept of a problem,
  `ZREVRANGE` to read), rebuilt from Postgres on startup, with a Postgres fallback if Redis
  is down.
- **Frontend** — login/register, problem list + detail with a Monaco editor, a submission
  page that polls every 1.5 s and shows per-test verdicts and compiler output, leaderboard.
- **Hardening** — per-user submission rate limit (HTTP 429), 64 KB output cap, container
  cleanup (no orphans), consistent JSON error envelope, request validation incl. a max
  source-code size.

## Architecture

```
React/Vite (Monaco)  ──►  Spring Boot monolith  ──►  Postgres  (source of truth)
   nginx :3000              :8080, JWT auth      └─►  Redis     (leaderboard cache)
                                   │
                                   ▼  one `docker run` per compile + per test case
                        judge-runner-{cpp,java,python}
                        --network=none, mem/cpu/pids caps,
                        --read-only rootfs, non-root user
```

Layering: `controller → service → repository → entity`. The judge is split into three
beans with clear responsibilities:

- `JudgeService` — async orchestration + verdict decision (no DB transaction held).
- `JudgePersistence` — short `@Transactional` DB writes (separate bean to avoid the
  self-invocation proxy problem).
- `ContainerRunner` / `DockerContainerRunner` — sandbox execution (mockable in tests).

See `EVALUATION_NOTES.md` for a deeper walkthrough and `docs/` for the original design doc.

## Run it locally

### Option A — Docker Compose (the whole stack)

```bash
# 1. build the three judge runner images (names are referenced by the backend)
docker build -t judge-runner-cpp    -f docker/runners/Dockerfile.cpp    docker/runners
docker build -t judge-runner-python -f docker/runners/Dockerfile.python docker/runners
docker build -t judge-runner-java   -f docker/runners/Dockerfile.java   docker/runners

# 2. (snap-packaged Docker only) point the judge workspace under your home dir.
#    Copy .env.example to .env and set JUDGE_WORKSPACE_HOST, e.g.:
#       JUDGE_WORKSPACE_HOST=/home/<you>/cp-judge-workspace
#    On a standard Docker install the default /tmp/judge-workspace works and no .env
#    is needed. (Why: a snap Docker daemon can only bind-mount sources under $HOME.)

# 3. up
docker compose up --build        # or: docker-compose up --build
```

- Frontend: http://localhost:3000
- Backend:  http://localhost:8080
- Postgres is published on **5433** (so it won't clash with a Postgres already on 5432).

A dev **admin** and three sample problems are seeded automatically on a fresh database:

```
admin@cp.local / admin12345        (role ADMIN — can create/edit/delete problems)
```

Register your own `USER` account from the UI to submit solutions. Seeding is idempotent
and can be turned off with `app.seed.enabled=false`; admin credentials are overridable via
`app.seed.admin-email` / `app.seed.admin-password`.

The backend container mounts the host Docker socket so it can launch judge sandboxes on the
host daemon — convenient for local dev, but understand the risk: a container with the host
Docker socket effectively controls the host. On a shared/production host use a dedicated
judge-worker machine instead (see **Future work**).

### Option B — backend/frontend on the host, infra in Docker (faster iteration)

```bash
# infra
docker run -d --name cp-pg    -e POSTGRES_DB=cpplatform -e POSTGRES_USER=cpplatform \
  -e POSTGRES_PASSWORD=cpplatform -p 5433:5432 postgres:16-alpine
docker run -d --name cp-redis -p 6379:6379 redis:7-alpine
# build the three runner images (as above)

# backend (DB on 5433; JUDGE_WORKSPACE under $HOME if Docker is snap-packaged)
cd backend
DB_PORT=5433 JUDGE_WORKSPACE=$HOME/cp-judge-workspace mvn spring-boot:run

# frontend
cd frontend && npm install && npm run dev   # http://localhost:5173, proxies /api to :8080
```

## Smoke test

`scripts/smoke-test.sh` drives the whole public API end to end: register / login / refresh,
then for one problem submits correct / wrong / infinite-loop / compile-error / runtime-error
solutions in **all three languages**, polls each to a terminal verdict and asserts it, and
checks the rate limit (429) and the leaderboard.

```bash
BASE_URL=http://localhost:8080 scripts/smoke-test.sh
# needs docker access for orphan cleanup; if your user is in the docker group just run it,
# otherwise:  sg docker -c 'scripts/smoke-test.sh'
```

## Tests

```bash
cd backend && mvn test
```

Unit tests cover output comparison and verdict mapping; a mocked-Docker `JudgeService` test
covers the AC/WA/TLE/CE flows; leaderboard tests confirm a re-solve doesn't raise the score;
and a `@SpringBootTest` (H2) integration test covers register → protected endpoint → refresh
→ unauthenticated rejection → login. No Docker needed to run the tests.

## Configuration

Secrets and environment-specific values are read from env vars (with dev defaults):

| Variable | Default | Purpose |
|---|---|---|
| `JWT_SECRET` | dev string | HMAC signing key — set a long random value in prod |
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` | localhost / 5432 / cpplatform… | Postgres |
| `REDIS_HOST` / `REDIS_PORT` | localhost / 6379 | Redis |
| `JUDGE_WORKSPACE` | `/tmp/judge` | per-submission work dir (set under `$HOME` for snap Docker) |
| `SUBMISSION_RATE_LIMIT_SECONDS` | 5 | min seconds between a user's submissions |

Schema is managed by Hibernate `ddl-auto=update` for dev convenience. Note that enum
`CHECK` constraints created this way don't auto-evolve when new enum values are added — a
production deployment should use versioned migrations (Flyway), see Future work.

## Deploying beyond localhost

- Compose on a single VM is the simplest real deployment; put nginx/Caddy in front for TLS.
- Or split: frontend as static files (Vercel/Netlify/S3+CloudFront); backend on a VM with
  Docker access (rules out most serverless for the backend); Postgres/Redis on managed
  services (RDS, Upstash).
- Whichever route: set a real `JWT_SECRET`, keep Postgres/Redis non-public and behind auth,
  and widen the CORS `allowedOriginPatterns` in `SecurityConfig` (currently `localhost:*`).

## Future work

- **Queue-based judging (Kafka)** — replace the in-process `@Async` call with a submission
  queue so multiple judge workers can scale horizontally and absorb backpressure.
- **Push instead of poll** — WebSockets/SSE to stream verdict updates to the submission page
  rather than polling every 1.5 s.
- **One container per submission** — run all test cases inside a single sandbox (copy inputs
  in, loop internally) instead of one `docker run` per test case, to cut per-test overhead.
- **Stronger / safer isolation** — replace the host Docker-socket mount with gVisor, nsjail,
  or `isolate` (the ICPC/IOI sandbox), or dedicated judge machines, removing the
  host-control risk of the socket mount.
- **Migrations** — adopt Flyway so schema (and enum constraints) evolve safely in production.
