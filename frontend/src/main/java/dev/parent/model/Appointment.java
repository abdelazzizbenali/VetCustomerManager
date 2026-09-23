package dev.parent.model;

import java.time.Instant;
import java.time.LocalDate;

/** A client appointment. {@code clientName} is filled from a join for display. */
public record Appointment(
        String uuid,
        String clientUuid,
        String clientName,
        LocalDate date,
        boolean done,
        String description,
        boolean deleted,
        Instant updatedAt
) {
}
