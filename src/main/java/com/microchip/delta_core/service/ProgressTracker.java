package com.microchip.delta_core.service;

import com.microchip.delta_core.domain.Job;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// yt-dlp reports each stream on its own: video goes 0 to 100, then audio starts from 0.
// This turns it into one number that only grows.
public class ProgressTracker {

    private static final Pattern ITEM = Pattern.compile("Downloading item (\\d+) of (\\d+)");

    private long base;
    private long lastDone;
    private long lastTotal;
    private long knownTotal;
    private long speed;
    private int item;
    private int items;

    public synchronized boolean accept(String line) {
        if (line.startsWith(YtDlpArgs.PROGRESS + " ")) {
            String[] p = line.split(" ");
            if (p.length < 5) {
                return false;
            }
            long done = number(p[1]);
            long total = number(p[2]) > 0 ? number(p[2]) : number(p[3]);
            if (done < lastDone) {
                base += Math.max(lastDone, lastTotal);
            }
            lastDone = done;
            lastTotal = total;
            speed = number(p[4]);
            return true;
        }
        Matcher m = ITEM.matcher(line);
        if (m.find()) {
            item = Integer.parseInt(m.group(1));
            items = Integer.parseInt(m.group(2));
            base = 0;
            lastDone = 0;
            lastTotal = 0;
            knownTotal = 0;
            return true;
        }
        return false;
    }

    public synchronized void knownTotal(long bytes) {
        this.knownTotal = bytes;
    }

    public synchronized void reset() {
        base = 0;
        lastDone = 0;
        lastTotal = 0;
        speed = 0;
    }

    public synchronized Job.Progress snapshot() {
        long done = base + lastDone;
        long total = Math.max(knownTotal, base + Math.max(lastTotal, lastDone));
        return new Job.Progress(done, total, speed, item, items);
    }

    private static long number(String s) {
        try {
            return (long) Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
