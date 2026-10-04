package com.microchip.delta_core.service;

import com.microchip.delta_core.config.DeltaProperties;
import com.microchip.delta_core.domain.Job;
import com.microchip.delta_core.domain.JobState;
import com.microchip.delta_core.domain.Owner;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

// Strictly one job at a time: the whole disk budget goes to the job in flight
// instead of being split between several.
@Component
public class Worker {

    private static final Logger log = LoggerFactory.getLogger(Worker.class);

    private final DeltaProperties properties;
    private final JobStore store;
    private final JobService jobs;
    private final Downloader downloader;
    private final StoragePaths paths;
    private final MeterRegistry registry;
    private volatile Thread thread;

    public Worker(DeltaProperties properties, JobStore store, JobService jobs, Downloader downloader,
            StoragePaths paths, MeterRegistry registry) {
        this.properties = properties;
        this.store = store;
        this.jobs = jobs;
        this.downloader = downloader;
        this.paths = paths;
        this.registry = registry;
    }

    @PostConstruct
    void start() {
        // whatever was running when the service stopped is gone with its process
        for (Job job : store.all()) {
            if (job.getState() == JobState.RUNNING) {
                Dirs.delete(paths.work().resolve(job.getId()));
                Dirs.delete(paths.out().resolve(job.getId()));
                job.finish(JobState.FAILED, null, new Job.Failure("failed", "прервано перезапуском сервиса"), null);
                store.save(job);
            }
        }
        for (JobState state : JobState.values()) {
            Gauge.builder("delta.jobs", store, s -> s.count(state)).tag("state", state.name().toLowerCase())
                    .register(registry);
        }
        Gauge.builder("delta.disk.used", paths, StoragePaths::used).baseUnit("bytes").register(registry);
        Gauge.builder("delta.disk.budget", properties, DeltaProperties::budgetBytes).baseUnit("bytes")
                .register(registry);

        thread = Thread.ofPlatform().name("delta-worker").daemon(true).start(this::loop);
    }

    @PreDestroy
    void stop() {
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
    }

    private void loop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Optional<Job> next = store.nextQueued();
                if (next.isEmpty()) {
                    Thread.sleep(1000);
                    continue;
                }
                if (!makeRoom()) {
                    Thread.sleep(10_000);
                    continue;
                }
                run(next.get());
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                // a bad job must never stop the queue
                log.error("worker tick failed", e);
            }
        }
    }

    private void run(Job job) throws InterruptedException {
        log.info("{} ({}, {}): {}", job.getId(), job.getMode(), job.getOwner().kind(), job.getUrl());
        job.setState(JobState.RUNNING);
        store.save(job);
        try {
            Job.Result result = downloader.run(job);
            job.finish(JobState.DONE, result, null, Instant.now().plus(jobs.resultTtl(job.getOwner())));
            log.info("{}: done, {} bytes", job.getId(), result.sizeBytes());
        } catch (Downloader.JobFailed e) {
            boolean canceled = e.failure().code().equals("canceled");
            job.finish(canceled ? JobState.CANCELED : JobState.FAILED, null, e.failure(), null);
            log.info("{}: {} ({})", job.getId(), e.failure().code(), e.failure().detail());
        } catch (InterruptedException e) {
            job.finish(JobState.FAILED, null, new Job.Failure("failed", "прервано перезапуском сервиса"), null);
            store.save(job);
            throw e;
        } catch (Exception e) {
            log.error("{}: blew up", job.getId(), e);
            job.finish(JobState.FAILED, null, new Job.Failure("failed", "внутренняя ошибка"), null);
        }
        job.setProgress(Job.Progress.NONE);
        store.save(job);
        Counter.builder("delta.finished").tag("state", job.getState().name().toLowerCase())
                .tag("owner", job.getOwner().kind().name().toLowerCase()).register(registry).increment();
    }

    // Nothing starts above the budget. Files saved at least once go first, then the oldest
    // ones of the page; what the bot has not collected yet is never touched.
    private boolean makeRoom() {
        while (paths.used() >= properties.budgetBytes()) {
            Optional<Job> victim = store.all().stream()
                    .filter(j -> j.getState() == JobState.DONE && j.getOwner().kind() != Owner.Kind.BOT)
                    .min(Comparator.comparing((Job j) -> j.getDownloads() == 0).thenComparing(Job::getFinishedAt));
            if (victim.isEmpty()) {
                return false;
            }
            log.info("{}: released early to make room", victim.get().getId());
            jobs.release(victim.get());
        }
        return true;
    }
}
