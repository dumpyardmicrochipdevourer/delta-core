package com.microchip.delta_core.domain;

import java.util.List;
import java.util.Set;

// Everything here ends up as an argument of yt-dlp, so nothing is free text:
// each field is checked against a closed list before a job is accepted.
public record Options(
        Integer quality,
        String container,
        String codec,
        String audioFormat,
        Integer bitrate,
        Boolean metadata,
        Boolean subtitles) {

    public static final List<Integer> HEIGHTS = List.of(2160, 1440, 1080, 720, 480, 360);
    private static final Set<String> CONTAINERS = Set.of("mp4", "mkv", "webm");
    private static final Set<String> CODECS = Set.of("h264", "vp9", "av1", "any");
    private static final Set<String> AUDIO_FORMATS = Set.of("mp3", "m4a", "opus");
    private static final Set<Integer> BITRATES = Set.of(320, 256, 192, 128);

    public static final Options DEFAULT = new Options(1080, "mp4", "h264", "mp3", 320, true, false);

    public Options normalized() {
        Options o = new Options(
                quality != null ? quality : DEFAULT.quality,
                container != null ? container : DEFAULT.container,
                codec != null ? codec : DEFAULT.codec,
                audioFormat != null ? audioFormat : DEFAULT.audioFormat,
                bitrate != null ? bitrate : DEFAULT.bitrate,
                metadata != null ? metadata : DEFAULT.metadata,
                subtitles != null ? subtitles : DEFAULT.subtitles);
        if (!HEIGHTS.contains(o.quality) || !CONTAINERS.contains(o.container) || !CODECS.contains(o.codec)
                || !AUDIO_FORMATS.contains(o.audioFormat) || !BITRATES.contains(o.bitrate)) {
            throw new IllegalArgumentException("недопустимые настройки");
        }
        return o;
    }

    public List<Integer> ladder() {
        return HEIGHTS.stream().filter(h -> h <= quality).toList();
    }
}
