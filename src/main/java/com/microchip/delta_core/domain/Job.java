package com.microchip.delta_core.domain;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;

// One download. Written to disk as json on every state change, so a restart loses nothing
// that matters: queued jobs run, finished files are still handed out and cleaned up.
@JsonAutoDetect(
        fieldVisibility = JsonAutoDetect.Visibility.ANY,
        getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Job {

    public record Progress(long doneBytes, long totalBytes, long speed, int item, int items) {
        public static final Progress NONE = new Progress(0, 0, 0, 0, 0);

        public int percent() {
            if (items > 1) {
                double current = totalBytes > 0 ? Math.min(1.0, (double) doneBytes / totalBytes) : 0;
                return (int) Math.min(100, Math.round((Math.max(1, item) - 1 + current) * 100 / items));
            }
            return totalBytes > 0 ? (int) Math.min(100, doneBytes * 100 / totalBytes) : 0;
        }
    }

    public record Meta(String title, String uploader, int duration) {
    }

    public record Result(
            String file,
            String fileName,
            long sizeBytes,
            int width,
            int height,
            int duration,
            String thumb,
            String artist,
            String cover,
            String deliverAs,
            List<String> files,
            String collage,
            boolean archive) {
    }

    public record Failure(String code, String detail) {
    }

    private String id;
    private Owner owner;
    private String url;
    private Mode mode;
    private Options options;
    private List<Integer> items;
    private boolean telegram;
    private long maxFileBytes;
    private Instant createdAt;

    private volatile JobState state = JobState.QUEUED;
    private volatile Meta meta;
    private volatile Result result;
    private volatile Failure failure;
    private volatile Instant finishedAt;
    private volatile Instant expiresAt;
    private volatile int downloads;

    @JsonIgnore private volatile Progress progress = Progress.NONE;
    @JsonIgnore private volatile boolean cancelRequested;

    protected Job() {}

    public Job(String id, Owner owner, String url, Mode mode, Options options, List<Integer> items,
            boolean telegram, long maxFileBytes) {
        this.id = id;
        this.owner = owner;
        this.url = url;
        this.mode = mode;
        this.options = options;
        this.items = items;
        this.telegram = telegram;
        this.maxFileBytes = maxFileBytes;
        this.createdAt = Instant.now();
    }

    public String getId() { return id; }
    public Owner getOwner() { return owner; }
    public String getUrl() { return url; }
    public Mode getMode() { return mode; }
    public Options getOptions() { return options; }
    public List<Integer> getItems() { return items; }
    public boolean isPlaylist() { return items != null && !items.isEmpty(); }
    public boolean isTelegram() { return telegram; }
    public long getMaxFileBytes() { return maxFileBytes; }
    public Instant getCreatedAt() { return createdAt; }
    public JobState getState() { return state; }
    public Meta getMeta() { return meta; }
    public Result getResult() { return result; }
    public Failure getFailure() { return failure; }
    public Instant getFinishedAt() { return finishedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public int getDownloads() { return downloads; }
    public Progress getProgress() { return progress; }
    public boolean isCancelRequested() { return cancelRequested; }

    public void setState(JobState state) { this.state = state; }
    public void setMeta(Meta meta) { this.meta = meta; }
    public void setProgress(Progress progress) { this.progress = progress; }
    public void requestCancel() { this.cancelRequested = true; }
    public void countDownload() { this.downloads++; }

    public void finish(JobState state, Result result, Failure failure, Instant expiresAt) {
        this.result = result;
        this.failure = failure;
        this.finishedAt = Instant.now();
        this.expiresAt = expiresAt;
        this.state = state;
    }

    public void dropFiles(JobState state) {
        this.result = null;
        this.state = state;
    }
}
