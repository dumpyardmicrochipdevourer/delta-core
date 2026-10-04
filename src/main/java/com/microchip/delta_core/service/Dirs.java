package com.microchip.delta_core.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class Dirs {

    private static final Set<String> IMAGES = Set.of("jpg", "jpeg", "png", "webp");
    private static final Set<String> SIDE_FILES = Set.of("json", "part", "ytdl", "vtt", "srt", "ass", "lrc", "temp");

    private Dirs() {}

    public static long size(Path dir) {
        if (!Files.exists(dir)) {
            return 0;
        }
        try (var paths = Files.walk(dir)) {
            return paths.filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum();
        } catch (IOException | UncheckedIOException e) {
            // files come and go while yt-dlp merges; the next look will be right
            return 0;
        }
    }

    public static void delete(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException | UncheckedIOException e) {
            // leftovers are picked up by the janitor
        }
    }

    public static List<Path> files(Path dir) {
        try (var paths = Files.list(dir)) {
            return paths.filter(Files::isRegularFile).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    public static List<Path> images(Path dir) {
        return files(dir).stream().filter(p -> IMAGES.contains(ext(p))).toList();
    }

    // cover art and info json sit next to the media, so media is "everything that is not those"
    public static List<Path> media(Path dir) {
        return files(dir).stream().filter(p -> !IMAGES.contains(ext(p)) && !SIDE_FILES.contains(ext(p))).toList();
    }

    public static List<Path> infoJson(Path dir) {
        return files(dir).stream().filter(p -> p.getFileName().toString().endsWith(".info.json")).toList();
    }

    public static String ext(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
