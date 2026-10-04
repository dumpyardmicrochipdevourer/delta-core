package com.microchip.delta_core.service;

public final class FileNames {

    private FileNames() {}

    // what the browser saves the file as: the real title, minus what a file system refuses
    public static String display(String title, String ext) {
        String clean = title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").replaceAll("\\s+", " ").strip();
        if (clean.isEmpty()) {
            clean = "delta";
        }
        if (clean.length() > 120) {
            clean = clean.substring(0, 120).strip();
        }
        return ext.isEmpty() ? clean : clean + "." + ext;
    }
}
