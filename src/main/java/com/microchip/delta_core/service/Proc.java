package com.microchip.delta_core.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

// Runs an external tool, feeds its output line by line and lets a guard stop it:
// yt-dlp has no reliable size limit of its own, and a cancelled job must die at once.
public final class Proc {

    public static final int KILLED = 99;
    private static final Duration GUARD_EVERY = Duration.ofSeconds(2);

    private Proc() {}

    public static int run(List<String> command, Path dir, Consumer<String> onLine, Supplier<Boolean> mustStop)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        if (dir != null) {
            builder.directory(dir.toFile());
        }
        Process process = builder.start();
        process.getOutputStream().close();

        Thread reader = Thread.ofVirtual().start(() -> {
            try (BufferedReader out = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = out.readLine()) != null) {
                    onLine.accept(line);
                }
            } catch (IOException e) {
                // the stream closes when the process is killed
            }
        });

        boolean killed = false;
        try {
            while (!process.waitFor(GUARD_EVERY.toMillis(), TimeUnit.MILLISECONDS)) {
                if (mustStop.get()) {
                    kill(process);
                    killed = true;
                }
            }
        } catch (InterruptedException e) {
            kill(process);
            throw e;
        }
        reader.join(5000);
        return killed ? KILLED : process.exitValue();
    }

    public static int run(List<String> command, Consumer<String> onLine, Duration timeout)
            throws IOException, InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        return run(command, null, onLine, () -> System.nanoTime() > deadline);
    }

    // ffmpeg is a child of yt-dlp; killing only the parent would leave it writing
    private static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }
}
