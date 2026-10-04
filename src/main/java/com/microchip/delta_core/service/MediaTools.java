package com.microchip.delta_core.service;

import com.microchip.delta_core.config.DeltaProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class MediaTools {

    public record Probe(int width, int height, int duration, boolean hasAudio) {
    }

    private static final Duration SHORT = Duration.ofMinutes(2);
    private static final Duration LONG = Duration.ofMinutes(20);

    private final String ffmpeg;
    private final String ffprobe;
    private final String collage;
    private final JsonMapper json;

    public MediaTools(DeltaProperties properties, JsonMapper json) {
        this.ffmpeg = properties.tools().ffmpeg();
        this.ffprobe = properties.tools().ffprobe();
        this.collage = properties.tools().collage();
        this.json = json;
    }

    public Probe probe(Path file) {
        StringBuilder out = new StringBuilder();
        try {
            int rc = Proc.run(List.of(ffprobe, "-v", "error", "-show_entries",
                    "stream=codec_type,width,height", "-show_entries", "format=duration",
                    "-of", "json", file.toString()), out::append, SHORT);
            if (rc != 0) {
                return new Probe(0, 0, 0, false);
            }
            JsonNode root = json.readTree(out.toString());
            int width = 0, height = 0;
            boolean audio = false;
            for (JsonNode stream : root.path("streams")) {
                String type = stream.path("codec_type").asString("");
                if (type.equals("video") && width == 0) {
                    width = stream.path("width").asInt(0);
                    height = stream.path("height").asInt(0);
                } else if (type.equals("audio")) {
                    audio = true;
                }
            }
            int duration = (int) Math.floor(Double.parseDouble(root.path("format").path("duration").asString("0")));
            return new Probe(width, height, duration, audio);
        } catch (IOException | RuntimeException e) {
            return new Probe(0, 0, 0, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Probe(0, 0, 0, false);
        }
    }

    // A frame from a third of the way in: the first seconds are an intro or a black screen
    public boolean frameThumb(Path video, Path out, int duration) {
        int seek = duration > 3 ? duration / 3 : 1;
        return ffmpeg(SHORT, out, "-ss", String.valueOf(seek), "-i", video.toString(), "-frames:v", "1",
                "-vf", "scale=w=320:h=320:force_original_aspect_ratio=decrease", "-q:v", "4", out.toString());
    }

    public boolean scaledJpeg(Path image, Path out, int side, int quality) {
        return ffmpeg(SHORT, out, "-i", image.toString(), "-frames:v", "1",
                "-vf", "scale=w='min(%d,iw)':h='min(%d,ih)':force_original_aspect_ratio=decrease".formatted(side, side),
                "-q:v", String.valueOf(quality), out.toString());
    }

    // Sound over its artwork as a video: an audio message in Telegram can only carry a 320 px preview
    public boolean coverVideo(Path audio, Path cover, Path out) {
        return ffmpeg(LONG, out, "-loop", "1", "-framerate", "1", "-i", cover.toString(), "-i", audio.toString(),
                "-c:v", "libx264", "-preset", "veryfast", "-tune", "stillimage", "-pix_fmt", "yuv420p",
                "-vf", "scale=720:720:force_original_aspect_ratio=decrease,pad=720:720:-1:-1:color=black",
                "-c:a", "copy", "-shortest", "-movflags", "+faststart", out.toString());
    }

    public boolean withoutAudio(Path video, Path out) {
        return ffmpeg(LONG, out, "-i", video.toString(), "-map", "0:v", "-c", "copy", "-an", out.toString());
    }

    public boolean collage(Path out, List<Path> photos) {
        List<String> command = new ArrayList<>(List.of(collage, out.toString()));
        photos.forEach(p -> command.add(p.toString()));
        return run(command, SHORT) && nonEmpty(out);
    }

    private boolean ffmpeg(Duration timeout, Path out, String... args) {
        List<String> command = new ArrayList<>(List.of(ffmpeg, "-v", "error", "-y"));
        command.addAll(List.of(args));
        return run(command, timeout) && nonEmpty(out);
    }

    private static boolean run(List<String> command, Duration timeout) {
        try {
            return Proc.run(command, line -> { }, timeout) == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean nonEmpty(Path file) {
        try {
            return Files.exists(file) && Files.size(file) > 0;
        } catch (IOException e) {
            return false;
        }
    }
}
