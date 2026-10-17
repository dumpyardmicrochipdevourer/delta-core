package com.microchip.delta_core.service;

import com.microchip.delta_core.domain.FeedbackMessage;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class FeedbackService {

    static final int MAX_CHARS = 8192;
    private static final int PER_HOUR = 5;
    private static final int LIST_LIMIT = 200;
    private static final Duration WINDOW = Duration.ofHours(1);

    private final FeedbackStore store;
    private final Map<String, Deque<Instant>> recent = new ConcurrentHashMap<>();

    public FeedbackService(FeedbackStore store, MeterRegistry metrics) {
        this.store = store;
        Gauge.builder("delta_feedback_unread", store, FeedbackStore::unread)
                .description("feedback notes nobody has read yet").register(metrics);
    }

    public void submit(String sender, String message, String contact) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("сообщение пустое");
        }
        String body = message.strip() + "\n\n-- \n" + (contact == null || contact.isBlank() ? "—" : contact.strip());
        if (body.length() > MAX_CHARS) {
            throw new IllegalArgumentException("сообщение длиннее " + MAX_CHARS + " символов");
        }
        throttle(sender);
        store.add(body);
    }

    public List<FeedbackMessage> list() {
        return store.latest(LIST_LIMIT);
    }

    public void markRead(long id) {
        store.markRead(id);
    }

    private void throttle(String sender) {
        Instant now = Instant.now();
        recent.values().removeIf(q -> { synchronized (q) { return !q.isEmpty() && q.peekLast().isBefore(now.minus(WINDOW)); } });
        Deque<Instant> times = recent.computeIfAbsent(sender, s -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && times.peekFirst().isBefore(now.minus(WINDOW))) {
                times.pollFirst();
            }
            if (times.size() >= PER_HOUR) {
                throw new Refusal(429, "rate_limited", "слишком часто, попробуйте позже");
            }
            times.addLast(now);
        }
    }
}
