package com.microchip.delta_core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microchip.delta_core.domain.Job;
import com.microchip.delta_core.domain.Mode;
import com.microchip.delta_core.domain.Options;
import com.microchip.delta_core.domain.Owner;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class UnitsTest {

    @Test
    void progressOnlyGrowsAcrossStreams() {
        ProgressTracker tracker = new ProgressTracker();
        tracker.knownTotal(400);

        tracker.accept("DELTA-P 150 300 NA 10");
        assertEquals(37, tracker.snapshot().percent());
        tracker.accept("DELTA-P 300 300 NA 10");
        tracker.accept("DELTA-P 50 100 NA 10");
        assertEquals(350, tracker.snapshot().doneBytes());
        assertEquals(87, tracker.snapshot().percent());
        assertFalse(tracker.accept("[Merger] Merging formats"));
    }

    @Test
    void progressUsesEstimateWhenTotalIsUnknown() {
        ProgressTracker tracker = new ProgressTracker();

        tracker.accept("DELTA-P 10 NA 200.5 NA");

        assertEquals(5, tracker.snapshot().percent());
    }

    @Test
    void playlistProgressCountsItems() {
        ProgressTracker tracker = new ProgressTracker();

        tracker.accept("[download] Downloading item 3 of 4");
        tracker.accept("DELTA-P 50 100 NA 10");

        assertEquals(63, tracker.snapshot().percent());
    }

    @Test
    void videoArgsCarryTheChoicesAndNothingElse() {
        Job job = job(Mode.VIDEO, new Options(1440, "mkv", "vp9", "mp3", 320, true, true), null, false);

        List<String> args = YtDlpArgs.video(job, Path.of("/w"), List.of(1440));

        assertTrue(args.contains("res,vcodec:vp9"));
        assertTrue(args.containsAll(List.of("--merge-output-format", "mkv", "--embed-subs", "--embed-metadata", "--no-playlist")));
        assertTrue(args.get(args.indexOf("-f") + 1).startsWith("bv*[height<=1440]"));
        assertEquals("https://example.com/v", args.getLast());
        assertEquals("--", args.get(args.size() - 2));
    }

    @Test
    void muteSelectorTakesNoAudioStream() {
        assertFalse(YtDlpArgs.selector(720, 1000, true).contains("+ba"));
        assertTrue(YtDlpArgs.selector(720, 1000, false).contains("+ba"));
    }

    @Test
    void telegramJobsKeepTheOldRecipe() {
        Job job = job(Mode.VIDEO, Options.DEFAULT, null, true);

        List<String> args = YtDlpArgs.video(job, Path.of("/w"), List.of(1080));

        assertFalse(args.contains("-S"));
        assertTrue(args.containsAll(List.of("--merge-output-format", "mp4")));
        assertTrue(YtDlpArgs.audio(job, Path.of("/w"), false).containsAll(List.of("--audio-format", "mp3", "--audio-quality", "0")));
    }

    @Test
    void playlistArgsChainTheLadder() {
        Job job = job(Mode.VIDEO, Options.DEFAULT, List.of(2, 5), false);

        List<String> args = YtDlpArgs.video(job, Path.of("/w"), Options.DEFAULT.ladder());

        assertTrue(args.containsAll(List.of("--yes-playlist", "--playlist-items", "2,5")));
        assertEquals(4, args.get(args.indexOf("-f") + 1).split("vcodec!=none").length - 1);
    }

    @Test
    void optionsAreAClosedList() {
        assertThrows(IllegalArgumentException.class, () -> new Options(1081, null, null, null, null, null, null).normalized());
        assertThrows(IllegalArgumentException.class, () -> new Options(null, "avi", null, null, null, null, null).normalized());
        assertThrows(IllegalArgumentException.class, () -> new Options(null, null, null, "wav", null, null, null).normalized());
        assertEquals(Options.DEFAULT, new Options(null, null, null, null, null, null, null).normalized());
        assertEquals(List.of(720, 480, 360), new Options(720, null, null, null, null, null, null).normalized().ladder());
    }

    @Test
    void errorsKeepTheToolsOwnWords() {
        assertEquals("unavailable", ErrorClassifier.classify("ERROR: [youtube] abc: Video unavailable", 1).code());
        assertEquals("Video unavailable", ErrorClassifier.classify("ERROR: [youtube] abc: Video unavailable", 1).detail());
        assertEquals("yt-dlp вышел с кодом 2", ErrorClassifier.classify("something odd", 2).detail());
        assertEquals("too_large", ErrorClassifier.classify("", Proc.KILLED).code());
        assertEquals("blocked", ErrorClassifier.classify("ERROR: Sign in to confirm you're not a bot", 1).code());
    }

    @Test
    void privateNetworksAreInternal() throws Exception {
        for (String ip : new String[] {"127.0.0.1", "10.1.2.3", "192.168.1.150", "172.20.0.5", "30.0.0.3",
                "169.254.1.1", "100.64.0.1", "::1", "fd00::1"}) {
            assertTrue(UrlGuard.internal(InetAddress.getByName(ip)), ip);
        }
        assertFalse(UrlGuard.internal(InetAddress.getByName("93.184.216.34")));
    }

    @Test
    void savedNameIsTheTitleMinusWhatFileSystemsRefuse() {
        assertEquals("a b c.mp4", FileNames.display("a/b:c", "mp4"));
        assertEquals("delta.mp3", FileNames.display("???", "mp3"));
    }

    private static Job job(Mode mode, Options options, List<Integer> items, boolean telegram) {
        return new Job("id", new Owner(Owner.Kind.USER, "u"), "https://example.com/v", mode, options, items,
                telegram, 1_900_000_000L);
    }
}
