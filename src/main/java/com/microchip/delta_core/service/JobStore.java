package com.microchip.delta_core.service;

import com.microchip.delta_core.domain.Job;
import com.microchip.delta_core.domain.JobState;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

// The queue is a directory of json files. No broker and no database: there are a few jobs
// an hour, and the most reliable state is the one that survives a restart by itself.
@Component
public class JobStore {

    private static final Logger log = LoggerFactory.getLogger(JobStore.class);

    private final Path dir;
    private final JsonMapper json;
    private final ConcurrentHashMap<String, Job> jobs = new ConcurrentHashMap<>();

    public JobStore(StoragePaths paths, JsonMapper json) {
        this.dir = paths.jobs();
        this.json = json;
        for (Path file : Dirs.files(dir)) {
            if (!file.getFileName().toString().endsWith(".json")) {
                continue;
            }
            try {
                Job job = json.readValue(file.toFile(), Job.class);
                jobs.put(job.getId(), job);
            } catch (RuntimeException e) {
                log.warn("unreadable job file {}, dropping it", file, e);
                file.toFile().delete();
            }
        }
    }

    public void save(Job job) {
        jobs.put(job.getId(), job);
        try {
            // through a temp file: a reader must never see half a json
            Path tmp = dir.resolve("." + job.getId() + ".tmp");
            json.writeValue(tmp.toFile(), job);
            Files.move(tmp, dir.resolve(job.getId() + ".json"), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void remove(Job job) {
        jobs.remove(job.getId());
        dir.resolve(job.getId() + ".json").toFile().delete();
    }

    public Optional<Job> find(String id) {
        return Optional.ofNullable(jobs.get(id));
    }

    public Collection<Job> all() {
        return jobs.values();
    }

    public Optional<Job> nextQueued() {
        return jobs.values().stream()
                .filter(j -> j.getState() == JobState.QUEUED)
                .min(Comparator.comparing(Job::getCreatedAt));
    }

    public long count(JobState state) {
        return jobs.values().stream().filter(j -> j.getState() == state).count();
    }
}
