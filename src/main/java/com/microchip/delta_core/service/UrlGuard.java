package com.microchip.delta_core.service;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

// yt-dlp will fetch whatever it is given, and the server sits inside a private network:
// a link to a neighbour's admin page must not be "downloaded" and handed to a stranger.
public final class UrlGuard {

    private static final int MAX_LENGTH = 2000;

    private UrlGuard() {}

    public static String check(String url) {
        if (url == null || url.length() > MAX_LENGTH || !url.matches("^https?://\\S+$")) {
            throw new IllegalArgumentException("нужна ссылка http(s)");
        }
        String host;
        try {
            host = URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("нужна ссылка http(s)");
        }
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("нужна ссылка http(s)");
        }
        if (host.matches("^[0-9.]+$") || host.contains(":") || host.equalsIgnoreCase("localhost")
                || !host.contains(".")) {
            throw new IllegalArgumentException("ссылка должна вести на сайт, а не на адрес");
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (internal(address)) {
                    throw new IllegalArgumentException("ссылка должна вести на сайт, а не на адрес");
                }
            }
        } catch (UnknownHostException e) {
            // not ours to judge: yt-dlp will say the same in its own words
        }
        return url;
    }

    static boolean internal(InetAddress address) {
        if (address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()
                || address.isAnyLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] b = address.getAddress();
        if (b.length != 4) {
            // unique local fc00::/7
            return (b[0] & 0xfe) == 0xfc;
        }
        int first = b[0] & 0xff, second = b[1] & 0xff;
        // the mesh lives in 30.0.0.0/8; 100.64/10 is carrier-grade NAT
        return first == 30 || first == 0 || (first == 100 && second >= 64 && second < 128);
    }
}
