# Judge runner images

These are the sandboxed execution environments JudgeService.java shells out to via
`docker run`. Each image just has the compiler/interpreter installed and runs as a
non-root user — no server, no long-running process. Build them once:

    docker build -t judge-runner-cpp -f Dockerfile.cpp .
    docker build -t judge-runner-python -f Dockerfile.python .
    docker build -t judge-runner-java -f Dockerfile.java .

JudgeService controls isolation at `docker run` time (--network=none, --memory,
--cpus, --pids-limit, --read-only), not in these Dockerfiles — that's intentional:
the isolation policy lives in one place (the judge), not duplicated per image.

Compile/run commands actually invoked (see RunnerConfig.java):
- C++:    compile: g++ -O2 -o sol solution.cpp   |   run: ./sol
- Python: run only: python3 solution.py
- Java:   compile: javac Main.java               |   run: java -cp . Main
