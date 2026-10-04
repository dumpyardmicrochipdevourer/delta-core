package com.microchip.delta_core.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "delta")
public record DeltaProperties(
        @DefaultValue("./data") String root,
        String out,
        @DefaultValue("5368709120") long budgetBytes,
        @DefaultValue("30m") Duration resultTtl,
        @DefaultValue("90m") Duration jobTimeout,
        @DefaultValue("40") int maxQueue,
        @DefaultValue Tier anon,
        @DefaultValue Tier user,
        @DefaultValue Bot bot,
        @DefaultValue Tools tools,
        @DefaultValue Auth auth) {

    public record Tier(
            @DefaultValue("536870912") long maxFileBytes,
            @DefaultValue("1") int maxActive,
            @DefaultValue("6") int perHour,
            @DefaultValue("0") int playlistItems) {
    }

    public record Bot(
            @DefaultValue("") String token,
            @DefaultValue("1900000000") long maxFileBytes,
            @DefaultValue("3") int maxActive,
            @DefaultValue("150m") Duration resultTtl) {
    }

    public record Tools(
            @DefaultValue("yt-dlp") String ytDlp,
            @DefaultValue("gallery-dl") String galleryDl,
            @DefaultValue("ffmpeg") String ffmpeg,
            @DefaultValue("ffprobe") String ffprobe,
            @DefaultValue("media-collage.py") String collage,
            @DefaultValue("false") boolean selfUpdate) {
    }

    public record Auth(
            @DefaultValue("http://localhost:8081/.well-known/jwks.json") String jwksUri,
            @DefaultValue("http://localhost:8081") String issuer) {
    }
}
