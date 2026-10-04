package com.microchip.delta_core.service;

import com.microchip.delta_core.config.DeltaProperties;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Sites change faster than releases of this service: a yt-dlp a month old already fails on some
@Component
public class ToolUpdater {

    private static final Logger log = LoggerFactory.getLogger(ToolUpdater.class);

    private final DeltaProperties.Tools tools;

    public ToolUpdater(DeltaProperties properties) {
        this.tools = properties.tools();
    }

    @Scheduled(initialDelayString = "PT20S", fixedDelayString = "PT24H")
    public void update() {
        if (!tools.selfUpdate()) {
            return;
        }
        try {
            StringBuilder out = new StringBuilder();
            int rc = Proc.run(List.of(tools.ytDlp(), "-U"), line -> out.append(line).append(' '), Duration.ofMinutes(3));
            log.info("yt-dlp -U ({}): {}", rc, out.toString().strip());
        } catch (IOException e) {
            log.warn("yt-dlp self-update failed: {}", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
