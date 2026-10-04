package com.microchip.delta_core.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.microchip.delta_core.domain.Job;
import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobView(
        String id,
        String state,
        String mode,
        String title,
        String uploader,
        int duration,
        int percent,
        long doneBytes,
        long totalBytes,
        long speed,
        int item,
        int items,
        int position,
        String fileName,
        long sizeBytes,
        boolean thumb,
        boolean archive,
        String kind,
        Job.Failure error,
        Instant expiresAt,
        Paths paths) {

    // Absolute paths on the server. Only the bot gets them: it hands a path, not bytes,
    // to the local Telegram Bot API server.
    public record Paths(String file, String thumb, String cover, List<String> files, String collage,
            int width, int height, String artist) {
    }

    public static JobView of(Job job, int position, boolean withPaths) {
        Job.Meta meta = job.getMeta();
        Job.Result result = job.getResult();
        Job.Progress progress = job.getProgress();
        return new JobView(
                job.getId(),
                job.getState().name().toLowerCase(),
                job.getMode().name().toLowerCase(),
                meta != null ? meta.title() : null,
                meta != null ? meta.uploader() : null,
                meta != null ? meta.duration() : 0,
                result != null ? 100 : progress.percent(),
                progress.doneBytes(),
                progress.totalBytes(),
                progress.speed(),
                progress.item(),
                job.isPlaylist() ? job.getItems().size() : progress.items(),
                position,
                result != null ? result.fileName() : null,
                result != null ? result.sizeBytes() : 0,
                result != null && result.thumb() != null,
                result != null && result.archive(),
                result != null ? result.deliverAs() : null,
                job.getFailure(),
                job.getExpiresAt(),
                withPaths && result != null
                        ? new Paths(result.file(), result.thumb(), result.cover(), result.files(), result.collage(),
                                result.width(), result.height(), result.artist())
                        : null);
    }
}
