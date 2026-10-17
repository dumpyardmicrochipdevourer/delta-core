package com.microchip.delta_core.service;

import com.microchip.delta_core.domain.FeedbackMessage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

// Notes are json files in a directory, like jobs: a few a week, and they must survive a restart.
@Component
public class FeedbackStore {

    private static final Logger log = LoggerFactory.getLogger(FeedbackStore.class);

    private final Path dir;
    private final JsonMapper json;
    private final ConcurrentNavigableMap<Long, FeedbackMessage> messages = new ConcurrentSkipListMap<>();

    public FeedbackStore(StoragePaths paths, JsonMapper json) {
        this.dir = paths.feedback();
        this.json = json;
        for (Path file : Dirs.files(dir)) {
            if (!file.getFileName().toString().endsWith(".json")) {
                continue;
            }
            try {
                FeedbackMessage message = json.readValue(file.toFile(), FeedbackMessage.class);
                messages.put(message.id(), message);
            } catch (RuntimeException e) {
                // a note is not worth failing the start for; the file stays for a human to look at
                log.warn("unreadable feedback file {}, skipping it", file, e);
            }
        }
    }

    public synchronized FeedbackMessage add(String body) {
        long id = messages.isEmpty() ? 1 : messages.lastKey() + 1;
        FeedbackMessage message = new FeedbackMessage(id, body, Instant.now(), null);
        save(message);
        return message;
    }

    public synchronized void markRead(long id) {
        FeedbackMessage message = messages.get(id);
        if (message != null && message.readAt() == null) {
            save(message.read(Instant.now()));
        }
    }

    // newest first
    public List<FeedbackMessage> latest(int limit) {
        return messages.descendingMap().values().stream().limit(limit).toList();
    }

    public long unread() {
        return messages.values().stream().filter(m -> m.readAt() == null).count();
    }

    private void save(FeedbackMessage message) {
        try {
            // through a temp file: a reader must never see half a json
            Path tmp = dir.resolve("." + message.id() + ".tmp");
            json.writeValue(tmp.toFile(), message);
            Files.move(tmp, dir.resolve(message.id() + ".json"), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            messages.put(message.id(), message);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
