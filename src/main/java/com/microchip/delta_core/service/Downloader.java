package com.microchip.delta_core.service;

import com.microchip.delta_core.config.DeltaProperties;
import com.microchip.delta_core.domain.Job;
import com.microchip.delta_core.domain.Mode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Turns one job into files in out/<id>. Returns a result or throws JobFailed; never leaves
// anything in work/.
@Component
public class Downloader {

    public static class JobFailed extends Exception {
        private final Job.Failure failure;

        public JobFailed(Job.Failure failure) {
            super(failure.code());
            this.failure = failure;
        }

        public Job.Failure failure() { return failure; }
    }

    private static final Logger log = LoggerFactory.getLogger(Downloader.class);
    private static final int LOG_TAIL = 200_000;
    // After a bot check the regular YouTube client does not just refuse, it hangs for minutes.
    // While this holds, YouTube goes straight through the android client.
    private static final Duration BOT_COOLDOWN = Duration.ofMinutes(30);
    private static final List<String> ANDROID = List.of("--extractor-args", "youtube:player_client=android");

    private final DeltaProperties properties;
    private final MediaTools tools;
    private final PhotoFetcher photos;
    private final JsonMapper json;
    private final Path work;
    private final Path out;
    private final AtomicReference<Instant> botCheckAt = new AtomicReference<>(Instant.EPOCH);

    public Downloader(DeltaProperties properties, MediaTools tools, PhotoFetcher photos, JsonMapper json,
            StoragePaths paths) {
        this.properties = properties;
        this.tools = tools;
        this.photos = photos;
        this.json = json;
        this.work = paths.work();
        this.out = paths.out();
    }

    public Job.Result run(Job job) throws JobFailed, IOException, InterruptedException {
        Path dir = work.resolve(job.getId());
        Path target = out.resolve(job.getId());
        try {
            return job.isPlaylist() ? playlist(job, dir, target) : single(job, dir, target);
        } catch (JobFailed | IOException | InterruptedException | RuntimeException e) {
            Dirs.delete(target);
            throw e;
        } finally {
            Dirs.delete(dir);
        }
    }

    private Job.Result single(Job job, Path dir, Path target) throws JobFailed, IOException, InterruptedException {
        // SoundCloud has no video stream at all. For Telegram "mp4" there means the sound over
        // its artwork, built here.
        boolean coverVideo = job.isTelegram() && job.getMode() == Mode.VIDEO && isSoundcloud(job.getUrl());
        boolean audio = job.getMode() == Mode.AUDIO || coverVideo;

        StringBuilder output = new StringBuilder();
        int rc = download(job, dir, audio, coverVideo, output);

        if (rc != 0 && job.getMode() == Mode.VIDEO && !coverVideo && ErrorClassifier.noVideo(output.toString())) {
            log.info("{}: no video in the post, trying its pictures", job.getId());
            return photos.fetch(job, dir, target);
        }
        if (rc != 0) {
            throw new JobFailed(ErrorClassifier.classify(output.toString(), rc));
        }

        String title = "", artist = "", uploader = "";
        int duration = 0;
        for (Path info : Dirs.infoJson(dir)) {
            JsonNode node = json.readTree(info.toFile());
            title = node.path("title").asString("");
            uploader = node.path("uploader").asString(node.path("channel").asString(""));
            // the artist where the site knows one, the account name otherwise
            artist = node.path("artist").asString(uploader);
            duration = node.path("duration").asInt(0);
            Files.delete(info);
        }

        List<Path> produced = Dirs.media(dir);
        if (produced.isEmpty()) {
            // usually the live filter: yt-dlp exits fine but writes nothing
            throw new JobFailed(ErrorClassifier.live(output.toString())
                    ? new Job.Failure("live", "прямые эфиры не скачиваются")
                    : new Job.Failure("failed", "по ссылке ничего не скачалось"));
        }
        Path media = produced.getFirst();
        if (Files.size(media) > job.getMaxFileBytes()) {
            throw new JobFailed(new Job.Failure("too_large", "файл больше допустимого"));
        }

        Files.createDirectories(target);
        String thumb = null, cover = null;
        if (audio) {
            List<Path> art = Dirs.images(dir);
            if (!art.isEmpty()) {
                if (tools.scaledJpeg(art.getFirst(), target.resolve("thumb.jpg"), 320, 4)) {
                    thumb = target.resolve("thumb.jpg").toString();
                }
                if (job.isTelegram() && tools.scaledJpeg(art.getFirst(), target.resolve("cover.jpg"), 1280, 3)) {
                    cover = target.resolve("cover.jpg").toString();
                }
            }
        }

        Path file = Files.move(media, target.resolve(media.getFileName()));
        String deliverAs = audio ? "audio" : "video";

        if (coverVideo && cover != null) {
            Path video = target.resolve("cover_video.mp4");
            if (tools.coverVideo(file, Path.of(cover), video)) {
                Files.delete(file);
                file = video;
                deliverAs = "video";
            }
        }

        MediaTools.Probe probe = new MediaTools.Probe(0, 0, duration, true);
        if (!deliverAs.equals("audio")) {
            probe = tools.probe(file);
            if (job.getMode() == Mode.MUTE && probe.hasAudio()) {
                Path silent = target.resolve("silent." + Dirs.ext(file));
                if (tools.withoutAudio(file, silent)) {
                    Files.move(silent, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            // a "video" without a frame is what a too-heavy stream leaves behind
            if (probe.width() == 0 || probe.height() == 0) {
                throw new JobFailed(new Job.Failure("too_large", "ни одно качество не укладывается в лимит размера"));
            }
            if (tools.frameThumb(file, target.resolve("thumb.jpg"), probe.duration())) {
                thumb = target.resolve("thumb.jpg").toString();
            }
        }

        if (title.isBlank()) {
            String name = file.getFileName().toString();
            title = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
        }
        job.setMeta(new Job.Meta(title, uploader, probe.duration() > 0 ? probe.duration() : duration));
        return new Job.Result(file.toString(), FileNames.display(title, Dirs.ext(file)), Files.size(file),
                probe.width(), probe.height(), probe.duration() > 0 ? probe.duration() : duration,
                thumb, artist.isBlank() ? null : artist, cover, deliverAs, List.of(), null, false);
    }

    private Job.Result playlist(Job job, Path dir, Path target) throws JobFailed, IOException, InterruptedException {
        StringBuilder output = new StringBuilder();
        long cap = properties.budgetBytes() / 2;
        ProgressTracker tracker = new ProgressTracker();
        List<String> args = job.getMode() == Mode.AUDIO
                ? YtDlpArgs.audio(job, dir, false)
                : YtDlpArgs.video(job, dir, job.getOptions().ladder());
        int rc = ytDlp(job, dir, args, output, tracker, cap);
        if (rc == Proc.KILLED) {
            throw new JobFailed(job.isCancelRequested()
                    ? new Job.Failure("canceled", "отменено")
                    : new Job.Failure("too_large", "плейлист больше допустимого размера архива"));
        }

        String title = "";
        for (Path info : Dirs.infoJson(dir)) {
            if (title.isBlank()) {
                title = json.readTree(info.toFile()).path("playlist_title").asString("");
            }
            Files.delete(info);
        }
        List<Path> produced = Dirs.media(dir);
        if (produced.isEmpty()) {
            throw new JobFailed(ErrorClassifier.classify(output.toString(), rc));
        }

        Files.createDirectories(target);
        List<String> files = new ArrayList<>();
        long size = 0;
        for (Path p : produced) {
            Path moved = Files.move(p, target.resolve(p.getFileName()));
            files.add(moved.toString());
            size += Files.size(moved);
        }
        if (title.isBlank()) {
            title = "playlist";
        }
        job.setMeta(new Job.Meta(title, "", 0));
        return new Job.Result(files.getFirst(), FileNames.display(title, "zip"), size, 0, 0, 0,
                null, null, null, "archive", List.copyOf(files), null, true);
    }

    // A ladder of heights rather than one selector: some sites do not tell the size before
    // the download starts, and stepping down a rung is simpler than guessing a bitrate.
    private int download(Job job, Path dir, boolean audio, boolean coverVideo, StringBuilder output)
            throws IOException, InterruptedException, JobFailed {
        ProgressTracker tracker = new ProgressTracker();
        long cap = job.getMaxFileBytes() * 2;
        if (audio) {
            return guarded(job, dir, YtDlpArgs.audio(job, dir, coverVideo), output, tracker, cap);
        }
        int rc = 1;
        for (int height : job.getOptions().ladder()) {
            log.info("{}: trying <={}p", job.getId(), height);
            tracker.reset();
            rc = guarded(job, dir, YtDlpArgs.video(job, dir, List.of(height)), output, tracker, cap);
            if (rc == 0) {
                return 0;
            }
            Dirs.files(dir).forEach(p -> p.toFile().delete());
            String text = output.toString();
            // no lower rung will find a video that is not there, or get past a refusal
            if (rc == Proc.KILLED || ErrorClassifier.noVideo(text)
                    || !ErrorClassifier.classify(text, rc).code().equals("too_large")) {
                return rc;
            }
        }
        return rc;
    }

    // Retries through the android client when YouTube asks to prove we are not a bot.
    // That client only has 360p, so it is strictly a fallback.
    private int guarded(Job job, Path dir, List<String> args, StringBuilder output, ProgressTracker tracker, long cap)
            throws IOException, InterruptedException, JobFailed {
        if (Duration.between(botCheckAt.get(), Instant.now()).compareTo(BOT_COOLDOWN) < 0) {
            return ytDlp(job, dir, with(ANDROID, args), output, tracker, cap);
        }
        int mark = output.length();
        int rc = ytDlp(job, dir, args, output, tracker, cap);
        if (rc != 0 && rc != Proc.KILLED && ErrorClassifier.botCheck(output.substring(mark))) {
            log.warn("{}: youtube bot check, retrying through the android client", job.getId());
            botCheckAt.set(Instant.now());
            output.setLength(mark);
            tracker.reset();
            rc = ytDlp(job, dir, with(ANDROID, args), output, tracker, cap);
        }
        return rc;
    }

    private int ytDlp(Job job, Path dir, List<String> args, StringBuilder output, ProgressTracker tracker, long cap)
            throws IOException, InterruptedException, JobFailed {
        Files.createDirectories(dir);
        List<String> command = with(List.of(properties.tools().ytDlp()), args);
        Instant deadline = Instant.now().plus(properties.jobTimeout());
        boolean[] metaRead = {false};

        int rc = Proc.run(command, dir, line -> {
            if (tracker.accept(line)) {
                job.setProgress(tracker.snapshot());
                return;
            }
            synchronized (output) {
                if (output.length() < LOG_TAIL) {
                    output.append(line).append('\n');
                }
            }
        }, () -> {
            if (!metaRead[0]) {
                metaRead[0] = readMeta(job, dir, tracker);
            }
            // --max-filesize trusts the size a site declares; streams and live recordings
            // declare none and would run until the disk is full
            return job.isCancelRequested() || Instant.now().isAfter(deadline) || Dirs.size(dir) > cap;
        });
        if (job.isCancelRequested()) {
            throw new JobFailed(new Job.Failure("canceled", "отменено"));
        }
        return rc;
    }

    // The info json lands before the first byte of media: enough to show what is coming
    private boolean readMeta(Job job, Path dir, ProgressTracker tracker) {
        List<Path> infos = Dirs.infoJson(dir);
        if (infos.isEmpty()) {
            return false;
        }
        try {
            JsonNode node = json.readTree(infos.getFirst().toFile());
            if (job.isPlaylist()) {
                job.setMeta(new Job.Meta(node.path("playlist_title").asString("playlist"), "", 0));
                return true;
            }
            long total = 0;
            for (JsonNode format : node.path("requested_formats")) {
                total += format.path("filesize").asLong(format.path("filesize_approx").asLong(0));
            }
            if (total == 0) {
                total = node.path("filesize").asLong(node.path("filesize_approx").asLong(0));
            }
            tracker.knownTotal(job.getMode() == Mode.AUDIO ? 0 : total);
            job.setMeta(new Job.Meta(node.path("title").asString(""),
                    node.path("uploader").asString(node.path("channel").asString("")),
                    node.path("duration").asInt(0)));
            return true;
        } catch (RuntimeException e) {
            // half-written, the next look will read it
            return false;
        }
    }

    // on.soundcloud.com short links reveal the real address only through a redirect
    private static boolean isSoundcloud(String url) {
        if (url.contains("soundcloud.com")) {
            return true;
        }
        try (HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(10)).build()) {
            HttpResponse<Void> response = client.send(
                    HttpRequest.newBuilder(URI.create(url)).method("HEAD", HttpRequest.BodyPublishers.noBody())
                            .timeout(Duration.ofSeconds(10)).build(),
                    HttpResponse.BodyHandlers.discarding());
            return response.uri().toString().contains("soundcloud.com");
        } catch (IOException | RuntimeException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static List<String> with(List<String> head, List<String> tail) {
        List<String> all = new ArrayList<>(head);
        all.addAll(tail);
        return all;
    }
}
