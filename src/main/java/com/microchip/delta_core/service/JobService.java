package com.microchip.delta_core.service;

import com.microchip.delta_core.config.DeltaProperties;
import com.microchip.delta_core.domain.Job;
import com.microchip.delta_core.domain.JobState;
import com.microchip.delta_core.domain.Mode;
import com.microchip.delta_core.domain.Options;
import com.microchip.delta_core.domain.Owner;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class JobService {

    public record Tier(long maxFileBytes, int maxActive, int perHour, int playlistItems) {
    }

    private static final Duration HOUR = Duration.ofHours(1);

    private final DeltaProperties properties;
    private final JobStore store;
    private final StoragePaths paths;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Deque<Instant>> recent = new ConcurrentHashMap<>();

    public JobService(DeltaProperties properties, JobStore store, StoragePaths paths) {
        this.properties = properties;
        this.store = store;
        this.paths = paths;
    }

    public Tier tier(Owner owner) {
        return switch (owner.kind()) {
            case ANON -> tier(properties.anon());
            case USER -> tier(properties.user());
            case BOT -> new Tier(properties.bot().maxFileBytes(), properties.bot().maxActive(), Integer.MAX_VALUE, 0);
        };
    }

    private static Tier tier(DeltaProperties.Tier t) {
        return new Tier(t.maxFileBytes(), t.maxActive(), t.perHour(), t.playlistItems());
    }

    public synchronized Job create(Owner owner, String url, Mode mode, Options options, List<Integer> items) {
        String checked = UrlGuard.check(url);
        Options normalized = (options == null ? Options.DEFAULT : options).normalized();
        Tier tier = tier(owner);

        if (items != null && !items.isEmpty()) {
            if (tier.playlistItems() == 0) {
                throw new Refusal(403, "login_required", "плейлисты доступны после входа");
            }
            if (items.size() > tier.playlistItems() || items.stream().anyMatch(i -> i == null || i < 1 || i > 5000)) {
                throw new Refusal(400, "too_many", "не больше " + tier.playlistItems() + " видео за раз");
            }
            items = items.stream().distinct().sorted().toList();
        } else {
            items = null;
        }

        long active = store.all().stream()
                .filter(j -> j.getOwner().key().equals(owner.key()) && j.getState().active()).count();
        if (active >= tier.maxActive()) {
            throw new Refusal(429, "busy", "дождитесь текущего скачивания");
        }
        if (store.count(JobState.QUEUED) >= properties.maxQueue()) {
            throw new Refusal(429, "queue_full", "очередь заполнена, попробуйте позже");
        }
        throttle(owner, tier);

        byte[] id = new byte[16];
        random.nextBytes(id);
        Job job = new Job(HexFormat.of().formatHex(id), owner, checked, mode, normalized, items,
                owner.kind() == Owner.Kind.BOT, tier.maxFileBytes());
        store.save(job);
        return job;
    }

    public Job get(String id) {
        return store.find(id).orElseThrow(() -> new Refusal(404, "not_found", "задание не найдено"));
    }

    public int position(Job job) {
        if (job.getState() != JobState.QUEUED) {
            return 0;
        }
        return (int) store.all().stream()
                .filter(j -> j.getState() == JobState.RUNNING
                        || (j.getState() == JobState.QUEUED && j.getCreatedAt().isBefore(job.getCreatedAt())))
                .count() + 1;
    }

    // Stops a job that is still going, frees the files of one that is done
    public void discard(Job job) {
        switch (job.getState()) {
            case QUEUED -> {
                job.finish(JobState.CANCELED, null, new Job.Failure("canceled", "отменено"), null);
                store.save(job);
            }
            case RUNNING -> job.requestCancel();
            case DONE -> release(job);
            default -> { }
        }
    }

    public void release(Job job) {
        Dirs.delete(paths.out().resolve(job.getId()));
        job.dropFiles(JobState.RELEASED);
        store.save(job);
    }

    public Path file(Job job) {
        if (job.getState() != JobState.DONE || job.getResult() == null) {
            throw new Refusal(404, "not_found", "файл уже удалён");
        }
        job.countDownload();
        store.save(job);
        return Path.of(job.getResult().file());
    }

    public Duration resultTtl(Owner owner) {
        return owner.kind() == Owner.Kind.BOT ? properties.bot().resultTtl() : properties.resultTtl();
    }

    private void throttle(Owner owner, Tier tier) {
        Instant now = Instant.now();
        recent.values().removeIf(q -> q.isEmpty() || q.peekLast().isBefore(now.minus(HOUR)));
        Deque<Instant> times = recent.computeIfAbsent(owner.key(), k -> new ArrayDeque<>());
        while (!times.isEmpty() && times.peekFirst().isBefore(now.minus(HOUR))) {
            times.pollFirst();
        }
        if (times.size() >= tier.perHour()) {
            throw new Refusal(429, "rate_limited", "слишком часто, попробуйте позже");
        }
        times.addLast(now);
    }
}
