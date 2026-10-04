package com.microchip.delta_core.service;

import com.microchip.delta_core.domain.Job;
import com.microchip.delta_core.domain.JobState;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class Janitor {

    private static final Duration KEEP_RECORD = Duration.ofHours(24);

    private final JobStore store;
    private final JobService jobs;
    private final StoragePaths paths;

    public Janitor(JobStore store, JobService jobs, StoragePaths paths) {
        this.store = store;
        this.jobs = jobs;
        this.paths = paths;
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    public void sweep() {
        Instant now = Instant.now();
        for (Job job : List.copyOf(store.all())) {
            if (job.getState() == JobState.DONE && job.getExpiresAt() != null && job.getExpiresAt().isBefore(now)) {
                jobs.release(job);
            } else if (!job.getState().active() && job.getState() != JobState.DONE
                    && job.getFinishedAt() != null && job.getFinishedAt().plus(KEEP_RECORD).isBefore(now)) {
                store.remove(job);
            } else if (job.getState() == JobState.RELEASED && job.getFinishedAt() == null) {
                store.remove(job);
            }
        }
        // directories nobody owns: a crash between writing files and the job record
        for (Path root : List.of(paths.out(), paths.work())) {
            try (var dirs = java.nio.file.Files.list(root)) {
                // only names this service could have made: the directory may be shared
                dirs.filter(java.nio.file.Files::isDirectory)
                        .filter(d -> d.getFileName().toString().matches("[0-9a-f]{32}"))
                        .filter(d -> store.find(d.getFileName().toString())
                                .map(j -> !j.getState().active() && j.getState() != JobState.DONE).orElse(true))
                        .forEach(Dirs::delete);
            } catch (java.io.IOException e) {
                // next minute
            }
        }
    }
}
