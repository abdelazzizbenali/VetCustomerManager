package dev.parent.model;

import java.time.Instant;
import java.time.LocalDate;

/** A medicine / product in stock. Mirrors the {@code medicines} Supabase table. */
public record Medicine(
        String uuid,
        String barcode,
        String name,
        String type,
        double size,          // content left in the currently opened unit
        double fullSize,      // content of a brand new unit
        double buyPrice,
        double sellPrice,
        LocalDate expiryDate,
        int stock,            // sealed units
        String seller,
        String description,
        int lowStockThreshold,
        boolean deleted,
        Instant updatedAt
) {
    public static final LocalDate NO_EXPIRY = LocalDate.of(9999, 12, 31);

    public boolean lowStock() {
        return stock <= lowStockThreshold;
    }

    public boolean expiringWithin(int days) {
        return expiryDate != null
                && !expiryDate.equals(NO_EXPIRY)
                && !expiryDate.isAfter(LocalDate.now().plusDays(days));
    }

    /** Total content units available (stock * full size + open size). */
    public double remainingContent() {
        return stock * fullSize + size;
    }

    public Medicine withStock(int newStock, double newSize) {
        return new Medicine(uuid, barcode, name, type, newSize, fullSize, buyPrice, sellPrice,
                expiryDate, newStock, seller, description, lowStockThreshold, deleted, updatedAt);
    }
}
