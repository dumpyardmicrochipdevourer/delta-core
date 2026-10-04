package com.microchip.delta_core.service;

import com.microchip.delta_core.config.DeltaProperties;
import com.microchip.delta_core.domain.Job;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Pictures of a post that has no video. yt-dlp does not fetch images at all, gallery-dl does.
@Component
public class PhotoFetcher {

    // one Telegram album holds no more
    private static final int MAX_PHOTOS = 10;
    private static final Duration TIMEOUT = Duration.ofMinutes(5);

    private final String galleryDl;
    private final MediaTools tools;
    private final JsonMapper json;

    public PhotoFetcher(DeltaProperties properties, MediaTools tools, JsonMapper json) {
        this.galleryDl = properties.tools().galleryDl();
        this.tools = tools;
        this.json = json;
    }

    public Job.Result fetch(Job job, Path dir, Path target)
            throws Downloader.JobFailed, IOException, InterruptedException {
        Dirs.delete(dir);
        Files.createDirectories(dir);

        // --range is not cosmetic: a profile link means "everything" to gallery-dl.
        // --no-mtime: post order is restored below from the time of writing.
        // The exit code is ignored, it is non-zero even when some files arrived.
        Proc.run(List.of(galleryDl, "--no-mtime", "--range", "1-" + MAX_PHOTOS,
                "--filter", "extension in ('jpg', 'jpeg', 'png', 'webp')",
                "--filesize-max", "30M", "--write-metadata", "-D", dir.toString(), "--", job.getUrl()),
                line -> { }, TIMEOUT);

        List<Path> sources = Dirs.images(dir).stream()
                .sorted(Comparator.comparingLong(p -> p.toFile().lastModified()))
                .limit(MAX_PHOTOS).toList();
        if (sources.isEmpty()) {
            throw new Downloader.JobFailed(new Job.Failure("unsupported", "по ссылке нет ни видео, ни картинок"));
        }

        // the caption is the post's text, and every site keeps it under its own key
        String title = "";
        for (Path meta : Dirs.files(dir)) {
            if (Dirs.ext(meta).equals("json")) {
                JsonNode node = json.readTree(meta.toFile());
                title = node.path("content").asString(node.path("description").asString(node.path("title").asString("")))
                        .replaceAll("\\s*https?://t\\.co/\\S+", "");
                break;
            }
        }

        // all to jpeg no larger than 2560 px, which is what Telegram keeps of a photo anyway
        Files.createDirectories(target);
        List<Path> photos = new ArrayList<>();
        long size = 0;
        for (Path source : sources) {
            Path photo = target.resolve("%02d.jpg".formatted(photos.size() + 1));
            if (tools.scaledJpeg(source, photo, 2560, 2)) {
                photos.add(photo);
                size += Files.size(photo);
            }
        }
        if (photos.isEmpty()) {
            throw new Downloader.JobFailed(new Job.Failure("failed", "не удалось обработать картинки"));
        }

        // an inline message in Telegram holds a single medium, so an album cannot go there
        String collage = null;
        if (job.isTelegram() && photos.size() > 1 && tools.collage(target.resolve("collage.jpg"), photos)) {
            collage = target.resolve("collage.jpg").toString();
            size += Files.size(Path.of(collage));
        }

        job.setMeta(new Job.Meta(title, "", 0));
        boolean archive = photos.size() > 1;
        String name = FileNames.display(title.isBlank() ? "photos" : title, archive ? "zip" : "jpg");
        return new Job.Result(photos.getFirst().toString(), name, size, 0, 0, 0, null, null, null, "photos",
                photos.stream().map(Path::toString).toList(), collage, archive);
    }
}
