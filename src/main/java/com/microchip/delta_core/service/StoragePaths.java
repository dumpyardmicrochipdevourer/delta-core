package com.microchip.delta_core.service;

import com.microchip.delta_core.config.DeltaProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

@Component
public class StoragePaths {

    private final Path jobs;
    private final Path work;
    private final Path out;

    public StoragePaths(DeltaProperties properties) {
        Path root = Path.of(properties.root()).toAbsolutePath().normalize();
        this.jobs = root.resolve("jobs");
        this.work = root.resolve("work");
        this.out = properties.out() == null || properties.out().isBlank()
                ? root.resolve("out")
                : Path.of(properties.out()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(jobs);
            Files.createDirectories(work);
            Files.createDirectories(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Path jobs() { return jobs; }
    public Path work() { return work; }
    public Path out() { return out; }

    public long used() {
        return Dirs.size(work) + Dirs.size(out);
    }
}
