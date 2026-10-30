# Competitive Programming Platform

Full-stack judge platform: React frontend, Spring Boot backend, Docker-per-submission
sandboxed code execution. See `architecture.md` (shared earlier) for the design rationale.

## What's implemented

- **Auth**: register/login/refresh with JWT (access + refresh tokens), BCrypt password hashing, role-based access (`USER`/`ADMIN`) via Spring Security.
- **Problem management**: CRUD (admin-only writes), tags, public + hidden test cases, difficulty levels, pagination.
- **Submissions**: submit code → async judged in an isolated Docker container per test case → poll for verdict.
- **Judge engine**: `JudgeService.java` — writes source to a per-submission workspace, compiles (C++/Java) or skips (Python), runs each test case in a locked-down container (`--network=none`, memory/cpu/pids caps, read-only root fs, non-root user), compares output, stores per-test-case verdicts.
- **Leaderboard**: solved-count ranking (simple Postgres scan for now — architecture doc notes the Redis sorted-set upgrade path for scale).
- **Frontend**: login/register, problem list + detail with Monaco code editor, submission status page (polls every 1.5s), leaderboard.

## What's simplified / deferred vs. the full architecture doc

- **No Kafka** — submission→judge is a direct async call (`@Async`), not a queue. Fine for a single-instance deploy; add Kafka if you need multiple judge workers or backpressure control.
- **No separate microservices** — one Spring Boot monolith. The judge logic is isolated in its own service class so it could be extracted into a standalone service later without much rework.
- **Leaderboard** is a full-table scan, not Redis-backed yet.
- **No WebSocket/SSE** — frontend polls. Fine for a demo; swap in SSE if you want push updates.

## ⚠️ I could not compile-check this end to end

This sandbox's network allowlist doesn't include Maven Central, so `mvn compile` couldn't
actually run here. I did a careful manual read-through and fixed one real bug I found this
way (`SecurityConfig` used `requestMatchers("GET", ...)` — that overload takes `HttpMethod`,
not `String`; fixed to `requestMatchers(HttpMethod.GET, ...)`). **Please run `mvn compile`
yourself as the first step** — there may be other issues a compiler will catch that manual
review won't.

## Run it locally

### Option A — Docker Compose (everything at once)

```bash
cd cp-platform
docker build -t judge-runner-cpp -f docker/runners/Dockerfile.cpp docker/runners
docker build -t judge-runner-python -f docker/runners/Dockerfile.python docker/runners
docker build -t judge-runner-java -f docker/runners/Dockerfile.java docker/runners
docker compose up --build
```
- Frontend: http://localhost:3000
- Backend: http://localhost:8080

The backend container needs `/var/run/docker.sock` mounted (already set up in
`docker-compose.yml`) so it can launch judge sandbox containers on your host's Docker
daemon. This is a real privilege-escalation surface worth understanding, not just
copy-pasting — a container with access to the host's Docker socket can effectively
control the host. Fine for local dev; on a shared/production host you'd want a dedicated
judge-worker VM instead (see architecture.md §7).

### Option B — Run backend/frontend directly, infra in Docker

```bash
# infra only
docker run -d --name pg -e POSTGRES_DB=cpplatform -e POSTGRES_USER=cpplatform \
  -e POSTGRES_PASSWORD=cpplatform -p 5432:5432 postgres:16-alpine
docker run -d --name redis -p 6379:6379 redis:7-alpine

# build judge runner images (same as above)

# backend
cd backend
mvn spring-boot:run

# frontend (separate terminal)
cd frontend
npm install
npm run dev   # http://localhost:5173, proxies /api to :8080
```

## First things to try

1. `POST /api/auth/register` → creates a USER account.
2. To create problems you need an ADMIN — easiest path for local dev: register normally,
   then manually update that row: `UPDATE users SET role='ADMIN' WHERE email='you@x.com';`
3. `POST /api/problems` (as ADMIN) with a title, statement, difficulty, and at least one
   test case.
4. Submit code for that problem from the frontend, watch the submission page poll until
   a verdict lands.

## Deploying beyond localhost

- **Compose on a single VM** (DigitalOcean droplet, EC2 instance, etc.) is the simplest
  real deployment — same `docker-compose.yml`, just needs Docker installed on the box.
  Point a domain at it, put Nginx or Caddy in front for TLS.
- **Split services**: frontend as static files on Vercel/Netlify/S+CloudFront; backend on
  a VM (needs Docker access for the judge — this rules out most serverless/Fargate
  options for the backend specifically); Postgres/Redis on managed services (RDS,
  Upstash) to avoid running your own.
- **Whichever route**: change `JWT_SECRET` to a real random value, put Postgres/Redis
  behind auth + not publicly exposed, and don't skip the CORS `allowedOriginPatterns`
  update in `SecurityConfig.java` (currently `localhost:*` only).
