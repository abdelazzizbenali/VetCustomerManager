package dev.parent.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * An animal belonging to a client.
 * <ul>
 *   <li>{@code category = "pet"}       &rarr; an individually registered animal
 *       (a dog named Rex, a cat, a horse...) with breed / gender / birth date.</li>
 *   <li>{@code category = "livestock"} &rarr; a counted group (45 sheep, 12 cows...).</li>
 * </ul>
 */
public record Animal(
        String uuid,
        String clientUuid,
        String category,
        String species,
        String name,
        String breed,
        String gender,
        LocalDate birthDate,
        int quantity,
        String notes,
        boolean deleted,
        Instant updatedAt
) {
    public static final String PET = "pet";
    public static final String LIVESTOCK = "livestock";

    public static final List<String> PET_SPECIES =
            List.of("Dog", "Cat", "Horse", "Donkey", "Rabbit", "Bird", "Other");
    public static final List<String> LIVESTOCK_SPECIES =
            List.of("Sheep", "Cow", "Goat", "Chicken", "Camel", "Horse", "Other");

    public boolean isPet() {
        return PET.equals(category);
    }

    /** Short display like "Rex (Dog)" or "Sheep x45". */
    public String shortLabel() {
        if (isPet()) {
            String n = name == null || name.isBlank() ? "-" : name;
            return n + " (" + species + ")";
        }
        return species + " x" + quantity;
    }
}
