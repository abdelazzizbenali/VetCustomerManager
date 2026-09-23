package dev.parent.model;

import java.time.Instant;

/** A client of the veterinary practice. Mirrors the {@code clients} table. */
public record Client(
        String uuid,
        String name,
        String phone,
        String address,
        String description,
        boolean deleted,
        Instant updatedAt
) {
}
