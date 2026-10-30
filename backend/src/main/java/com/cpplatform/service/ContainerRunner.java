package com.cpplatform.service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Runs one command in an isolated sandbox and reports how it went. Extracted behind
 * an interface so {@link JudgeService}'s judging flow can be unit-tested with the
 * actual container execution mocked out.
 */
public interface ContainerRunner {

    /**
     * @param workDir        host directory bind-mounted into the sandbox as /box
     * @param command        the compile or run command to execute inside the image
     * @param timeoutSeconds wall-clock backstop; exceeding it flags {@code timedOut}
     * @param compileStep    true for a compile pass (no stdin); false pipes input.txt
     */
    RunResult run(String image, Path workDir, List<String> command,
                  int timeoutSeconds, boolean compileStep) throws IOException, InterruptedException;

    /**
     * Result of one container run. {@code outputExceeded} means the program wrote more
     * than the cap (we killed it); {@code outOfMemory} means the kernel OOM-killed it
     * (exit 137 with no wall-clock timeout).
     */
    record RunResult(String stdout, String stderr, int exitCode,
                     boolean timedOut, boolean outOfMemory, boolean outputExceeded, long wallMs) {}
}
