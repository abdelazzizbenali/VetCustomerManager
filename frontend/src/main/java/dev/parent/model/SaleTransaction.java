package dev.parent.model;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * One line of the daily usage / purchase journal.
 * {@code clientName} and {@code medicineName} are filled from joins for display.
 */
public record SaleTransaction(
        String uuid,
        String clientUuid,
        String clientName,
        String medicineUuid,
        String medicineName,
        OffsetDateTime createdAt,
        double quantity,
        double amount,
        String type,
        String description,
        boolean paid,
        boolean deleted,
        Instant updatedAt
) {
    public static final List<String> TYPES =
            List.of("Consultation", "Treatment", "Product", "Vaccination", "Surgery", "Visit", "Other");
}
