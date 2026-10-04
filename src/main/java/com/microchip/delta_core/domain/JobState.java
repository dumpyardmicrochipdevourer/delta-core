package com.microchip.delta_core.domain;

public enum JobState {
    QUEUED,
    RUNNING,
    DONE,
    FAILED,
    CANCELED,
    RELEASED;

    public boolean active() {
        return this == QUEUED || this == RUNNING;
    }
}
