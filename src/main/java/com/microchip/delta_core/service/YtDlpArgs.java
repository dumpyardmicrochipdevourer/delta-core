package com.microchip.delta_core.service;

import com.microchip.delta_core.domain.Job;
import com.microchip.delta_core.domain.Mode;
import com.microchip.delta_core.domain.Options;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public final class YtDlpArgs {

    public static final String PROGRESS = "DELTA-P";
    // the selector filters only the video stream; this much is left for the audio track
    private static final long AUDIO_ROOM = 209_715_200L;

    private YtDlpArgs() {}

    static List<String> common(Job job, Path dir) {
        List<String> a = new ArrayList<>(List.of(
                "--newline",
                "--progress-template", "download:" + PROGRESS
                        + " %(progress.downloaded_bytes)s %(progress.total_bytes)s"
                        + " %(progress.total_bytes_estimate)s %(progress.speed)s",
                "--retries", "3",
                "--socket-timeout", "30",
                // hammering a site is exactly what trips its bot check
                "--sleep-requests", "1",
                // a live stream has no end
                "--match-filter", "!is_live",
                "--no-warnings",
                "--restrict-filenames",
                // the real title comes from here, the file name on disk is mangled on purpose
                "--write-info-json",
                "--max-filesize", String.valueOf(job.getMaxFileBytes()),
                "-P", dir.toString()));
        if (job.isPlaylist()) {
            a.addAll(List.of(
                    "--yes-playlist",
                    "--playlist-items", job.getItems().stream().map(String::valueOf).collect(Collectors.joining(",")),
                    "--no-write-playlist-metafiles",
                    // one dead item must not cost the whole archive
                    "--ignore-errors",
                    "-o", "%(playlist_index)02d-%(title).120B.%(ext)s"));
        } else {
            a.addAll(List.of("--no-playlist", "-o", "%(title).150B.%(ext)s"));
        }
        return a;
    }

    public static List<String> audio(Job job, Path dir, boolean asMp3ForCover) {
        Options o = job.getOptions();
        List<String> a = common(job, dir);
        a.add("-x");
        if (job.isTelegram() || asMp3ForCover) {
            a.addAll(List.of("--audio-format", "mp3", "--audio-quality", "0"));
        } else {
            a.addAll(List.of("--audio-format", o.audioFormat(), "--audio-quality", o.bitrate() + "K"));
            if (o.metadata()) {
                a.add("--embed-metadata");
            }
        }
        // the embedded cover is for players, the separate file becomes the preview
        a.addAll(List.of("--embed-thumbnail", "--write-thumbnail", "--convert-thumbnails", "jpg"));
        a.addAll(List.of("--", job.getUrl()));
        return a;
    }

    // One rung of the ladder for a single link, the whole ladder chained for a playlist:
    // a rung that fails at run time can only be retried when there is one file at stake.
    public static List<String> video(Job job, Path dir, List<Integer> heights) {
        Options o = job.getOptions();
        long budget = job.getMaxFileBytes() > AUDIO_ROOM * 2 ? job.getMaxFileBytes() - AUDIO_ROOM : job.getMaxFileBytes();
        boolean mute = job.getMode() == Mode.MUTE;

        List<String> a = common(job, dir);
        a.add("-f");
        a.add(heights.stream().map(h -> selector(h, budget, mute)).collect(Collectors.joining("/")));

        String container = job.isTelegram() ? "mp4" : o.container();
        if (!job.isTelegram()) {
            String sort = sort(o);
            if (sort != null) {
                a.addAll(List.of("-S", sort));
            }
            a.addAll(List.of("--remux-video", container));
            if (o.metadata()) {
                a.add("--embed-metadata");
            }
            if (o.subtitles()) {
                a.addAll(List.of("--embed-subs", "--sub-langs", "ru.*,en.*"));
            }
        }
        a.addAll(List.of("--merge-output-format", container));
        if (container.equals("mp4")) {
            a.addAll(List.of("--postprocessor-args", "Merger:-movflags +faststart"));
        }
        a.addAll(List.of("--", job.getUrl()));
        return a;
    }

    // The size is filtered while choosing the format, not by --max-filesize: that one drops
    // an oversized video stream, keeps the audio and reports success with a file without a frame.
    // "<?" lets formats of unknown size through, the checks after the download catch those.
    static String selector(int height, long budget, boolean mute) {
        String video = "bv*[height<=%d][filesize_approx<?%d]".formatted(height, budget);
        String single = "b[height<=%d][vcodec!=none][filesize_approx<?%d]".formatted(height, budget);
        return mute ? video + "/" + single : video + "+ba/" + single;
    }

    // resolution stays first, the codec only breaks ties: h264 does not exist above 1080p
    static String sort(Options o) {
        if (o.container().equals("webm")) {
            return "res,vcodec:" + (o.codec().equals("av1") ? "av01" : "vp9") + ",acodec:opus";
        }
        return switch (o.codec()) {
            case "h264" -> "res,vcodec:h264" + (o.container().equals("mp4") ? ",acodec:m4a" : "");
            case "vp9" -> "res,vcodec:vp9";
            case "av1" -> "res,vcodec:av01";
            default -> null;
        };
    }
}
