package com.microchip.delta_core.service;

import com.microchip.delta_core.config.DeltaProperties;
import com.microchip.delta_core.domain.Job;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Looks at a link without downloading it: is it one video or a list, and what is in the list
@Service
public class ProbeService {

    public record Entry(int index, String title, int duration) {
    }

    public record Probe(String kind, String title, String uploader, int duration, int total, List<Entry> entries) {
    }

    private static final int MAX_ENTRIES = 200;
    private static final int MAX_OUTPUT = 30_000_000;
    private static final Duration TIMEOUT = Duration.ofSeconds(90);

    private final String ytDlp;
    private final JsonMapper json;

    public ProbeService(DeltaProperties properties, JsonMapper json) {
        this.ytDlp = properties.tools().ytDlp();
        this.json = json;
    }

    public Probe probe(String url) throws IOException, InterruptedException {
        String checked = UrlGuard.check(url);
        StringBuilder out = new StringBuilder();
        StringBuilder errors = new StringBuilder();
        int rc = Proc.run(List.of(ytDlp, "-J", "--flat-playlist", "--playlist-end", String.valueOf(MAX_ENTRIES),
                "--no-warnings", "--socket-timeout", "30", "--", checked), line -> {
                    if (line.startsWith("{") && out.length() < MAX_OUTPUT) {
                        out.append(line);
                    } else if (errors.length() < 20_000) {
                        errors.append(line).append('\n');
                    }
                }, TIMEOUT);
        if (rc != 0 || out.isEmpty()) {
            Job.Failure failure = ErrorClassifier.classify(errors.toString(), rc);
            throw new Refusal(422, failure.code(), failure.detail());
        }

        JsonNode root = json.readTree(out.toString());
        if (!root.path("_type").asString("video").equals("playlist")) {
            return new Probe("single", root.path("title").asString(""),
                    root.path("uploader").asString(root.path("channel").asString("")),
                    root.path("duration").asInt(0), 1, List.of());
        }
        List<Entry> entries = new ArrayList<>();
        int index = 0;
        for (JsonNode entry : root.path("entries")) {
            index++;
            if (entry.isNull()) {
                continue;
            }
            entries.add(new Entry(index, entry.path("title").asString("#" + index), entry.path("duration").asInt(0)));
        }
        if (entries.isEmpty()) {
            throw new Refusal(422, "unavailable", "плейлист пуст или недоступен");
        }
        return new Probe("playlist", root.path("title").asString(""),
                root.path("uploader").asString(root.path("channel").asString("")), 0,
                root.path("playlist_count").asInt(entries.size()), entries);
    }
}
