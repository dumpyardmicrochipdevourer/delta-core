package com.microchip.delta_core.domain;

public record Owner(Kind kind, String name) {

    public enum Kind { ANON, USER, BOT }

    public String key() {
        return kind + ":" + name;
    }
}
