package com.microchip.delta_core.service;

import com.microchip.delta_core.domain.Job;
import java.util.regex.Pattern;

// Every site words its refusals differently; the page only needs to know which of a few
// screens to show, and the detail keeps the tool's own last word.
public final class ErrorClassifier {

    private static final Pattern NO_VIDEO = Pattern.compile("no video|no media found|unsupported url",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern UNSUPPORTED = Pattern.compile("unsupported url", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNAVAILABLE = Pattern.compile(
            "private video|video unavailable|has been removed|video is not available|been terminated|members-only"
                    + "|login required|sign in to confirm your age|this video is private|removed by|does not exist"
                    + "|http error 404|page not found",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TOO_LARGE = Pattern.compile(
            "requested format is not available|larger than max-filesize", Pattern.CASE_INSENSITIVE);

    private ErrorClassifier() {}

    public static boolean noVideo(String log) {
        return NO_VIDEO.matcher(log).find();
    }

    public static boolean botCheck(String log) {
        return log.contains("not a bot");
    }

    public static boolean live(String log) {
        return log.contains("is_live");
    }

    public static Job.Failure classify(String log, int exitCode) {
        if (exitCode == Proc.KILLED) {
            return new Job.Failure("too_large", "файл больше допустимого");
        }
        if (UNSUPPORTED.matcher(log).find()) {
            return new Job.Failure("unsupported", lastError(log, exitCode));
        }
        if (botCheck(log)) {
            return new Job.Failure("blocked", "площадка не пускает сервер, попробуйте позже");
        }
        // before "unavailable": a format that is not available is about size, not about the video
        if (TOO_LARGE.matcher(log).find()) {
            return new Job.Failure("too_large", "ни одно качество не укладывается в лимит размера");
        }
        if (UNAVAILABLE.matcher(log).find()) {
            return new Job.Failure("unavailable", lastError(log, exitCode));
        }
        return new Job.Failure("failed", lastError(log, exitCode));
    }

    static String lastError(String log, int exitCode) {
        String found = null;
        for (String line : log.split("\n")) {
            if (line.startsWith("ERROR:")) {
                found = line.substring("ERROR:".length()).strip();
            }
        }
        if (found == null) {
            return "yt-dlp вышел с кодом " + exitCode;
        }
        // "[youtube] dQw4w9: Video unavailable" reads better without the extractor tag
        found = found.replaceFirst("^\\[[^\\]]+\\]\\s*[^:]{0,40}:\\s*", "");
        return found.length() > 200 ? found.substring(0, 200) : found;
    }
}
