package com.cpplatform.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Real {@link ContainerRunner}: launches {@code docker run} with hard isolation
 * flags under a unique container name.
 *
 * Isolation: --network=none, memory/cpu/pids caps, read-only root fs, a non-root
 * user inside the image, and a wall-clock timeout backstop. stdout/stderr are drained
 * on their own threads (so a large writer can't deadlock against a full pipe buffer)
 * and capped at maxOutputBytes; exceeding the cap kills and flags OLE. On any forced
 * kill (timeout or flood) the container is force-removed so nothing is orphaned.
 */
@Component
@Slf4j
public class DockerContainerRunner implements ContainerRunner {

    @Value("${judge.docker-memory}")
    private String dockerMemory;

    @Value("${judge.docker-cpus}")
    private String dockerCpus;

    @Value("${judge.docker-pids-limit}")
    private String dockerPidsLimit;

    @Value("${judge.max-output-bytes}")
    private int maxOutputBytes;

    @Override
    public RunResult run(String image, Path workDir, List<String> command,
                         int timeoutSeconds, boolean compileStep) throws IOException, InterruptedException {

        String containerName = "judge-" + UUID.randomUUID();
        List<String> cmd = new ArrayList<>(List.of(
                "docker", "run", "--rm",
                "--name", containerName,
                "-i",
                "--network=none",
                "--memory=" + dockerMemory,
                "--memory-swap=" + dockerMemory,
                "--cpus=" + dockerCpus,
                "--pids-limit=" + dockerPidsLimit,
                "--read-only",
                "--tmpfs", "/tmp:rw,size=32m",
                "-v", workDir.toAbsolutePath() + ":/box:rw",
                "-w", "/box",
                image
        ));
        cmd.addAll(command);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        Process process = pb.start();

        // Drain both streams concurrently with a hard cap; kill the container if a
        // stream floods past the cap.
        StreamGobbler out = new StreamGobbler(process.getInputStream(), maxOutputBytes,
                () -> forceKill(process, containerName));
        StreamGobbler err = new StreamGobbler(process.getErrorStream(), maxOutputBytes,
                () -> forceKill(process, containerName));
        out.start();
        err.start();

        // Feed stdin (test input) for run steps; compile steps get none.
        if (!compileStep) {
            Path input = workDir.resolve("input.txt");
            if (Files.exists(input)) {
                try (var os = process.getOutputStream()) {
                    Files.copy(input, os);
                } catch (IOException ignored) {
                    // program may have exited/closed stdin before reading it all; fine
                }
            } else {
                process.getOutputStream().close();
            }
        } else {
            process.getOutputStream().close();
        }

        long start = System.currentTimeMillis();
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        long wallMs = System.currentTimeMillis() - start;

        if (!finished) {
            forceKill(process, containerName);   // kill CLI + force-remove the container
            out.join(2000);
            err.join(2000);
            return new RunResult("", "timed out", -1, true, false, false, wallMs);
        }

        out.join(2000);
        err.join(2000);
        int exitCode = process.exitValue();
        boolean exceeded = out.capExceeded() || err.capExceeded();
        if (exceeded) {
            forceRemove(containerName); // we killed it for flooding; make sure it's gone
        }
        boolean oom = !exceeded && exitCode == 137; // 128 + SIGKILL, i.e. cgroup OOM kill

        return new RunResult(out.text(), err.text(), exitCode, false, oom, exceeded, wallMs);
    }

    /** Kill the docker CLI process and force-remove the container it launched. */
    private void forceKill(Process process, String containerName) {
        process.destroyForcibly();
        forceRemove(containerName);
    }

    /** `docker rm -f` the named container; best-effort, so already-gone is fine. */
    private void forceRemove(String containerName) {
        try {
            new ProcessBuilder("docker", "rm", "-f", containerName)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                    .waitFor(5, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException ignored) {
            if (Thread.currentThread().isInterrupted()) Thread.currentThread().interrupt();
        }
    }

    /**
     * Reads a stream to completion, keeping at most {@code cap} bytes. If the stream
     * exceeds the cap it flags {@code capExceeded} and invokes {@code onExceed} once
     * (used to kill the container), then keeps draining so the writer never blocks.
     */
    private static final class StreamGobbler extends Thread {
        private final InputStream in;
        private final int cap;
        private final Runnable onExceed;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        private volatile boolean exceeded = false;

        StreamGobbler(InputStream in, int cap, Runnable onExceed) {
            this.in = in;
            this.cap = cap;
            this.onExceed = onExceed;
            setDaemon(true);
        }

        @Override
        public void run() {
            byte[] chunk = new byte[8192];
            int n;
            try {
                while ((n = in.read(chunk)) != -1) {
                    int remaining = cap - buf.size();
                    if (remaining > 0) {
                        buf.write(chunk, 0, Math.min(n, remaining));
                    }
                    if (!exceeded && buf.size() >= cap) {
                        exceeded = true;
                        try { onExceed.run(); } catch (Exception ignored) {}
                    }
                    // keep reading (and discarding beyond the cap) so the writer can't block
                }
            } catch (IOException ignored) {
                // stream closed as the process died; nothing more to read
            }
        }

        boolean capExceeded() { return exceeded; }

        String text() { return buf.toString(StandardCharsets.UTF_8); }
    }
}
