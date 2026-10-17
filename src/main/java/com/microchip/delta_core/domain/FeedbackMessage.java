package com.microchip.delta_core.domain;

import java.time.Instant;

// One note from the feedback sheet. Numbered, so an admin page can mark it read.
public record FeedbackMessage(long id, String body, Instant createdAt, Instant readAt) {

    public FeedbackMessage read(Instant when) {
        return new FeedbackMessage(id, body, createdAt, when);
    }
}
