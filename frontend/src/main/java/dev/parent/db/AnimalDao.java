package dev.parent.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.parent.model.Animal;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Data access for client animals (registered pets + livestock counters). */
public final class AnimalDao {

    private AnimalDao() {
    }

    // ------------------------------------------------------------------- reads

    public static List<Animal> listByClient(String clientUuid) {
        return LocalDatabase.exec(c -> listByClient(c, clientUuid));
    }

    static List<Animal> listByClient(Connection c, String clientUuid) throws java.sql.SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM client_animals WHERE client_uuid = ? AND is_deleted = 0 " +
                "ORDER BY category DESC, species, name")) {
            ps.setString(1, clientUuid);
            try (ResultSet rs = ps.executeQuery()) {
                List<Animal> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(map(rs));
                }
                return out;
            }
        }
    }

    public static int countForClient(String clientUuid) {
        return LocalDatabase.exec(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM client_animals WHERE client_uuid = ? AND is_deleted = 0")) {
                ps.setString(1, clientUuid);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    // ------------------------------------------------------------------ writes

    public static Animal insert(Animal a) {
        Animal withId = new Animal(
                a.uuid() == null || a.uuid().isBlank() ? UUID.randomUUID().toString() : a.uuid(),
                a.clientUuid(), a.category(), a.species(), nv(a.name(), ""), nv(a.breed(), ""),
                nv(a.gender(), "unknown"), a.birthDate(), Math.max(1, a.quantity()),
                nv(a.notes(), ""), false, Instant.now());
        LocalDatabase.exec(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO client_animals(uuid, client_uuid, category, species, name, breed," +
                    " gender, birth_date, quantity, notes, created_at, updated_at, is_deleted, _dirty)" +
                    " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,0,1)")) {
                ps.setString(1, withId.uuid());
                ps.setString(2, withId.clientUuid());
                ps.setString(3, withId.category());
                ps.setString(4, withId.species());
                ps.setString(5, withId.name());
                ps.setString(6, withId.breed());
                ps.setString(7, withId.gender());
                ps.setString(8, withId.birthDate() == null ? null : withId.birthDate().toString());
                ps.setInt(9, withId.quantity());
                ps.setString(10, withId.notes());
                ps.setString(11, withId.updatedAt().toString());
                ps.setString(12, withId.updatedAt().toString());
                ps.executeUpdate();
            }
            return null;
        });
        LocalDatabase.markChanged();
        return withId;
    }

    public static void update(Animal a) {
        LocalDatabase.exec(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE client_animals SET category=?, species=?, name=?, breed=?, gender=?," +
                    " birth_date=?, quantity=?, notes=?, updated_at=?, _dirty=1 WHERE uuid=?")) {
                ps.setString(1, a.category());
                ps.setString(2, a.species());
                ps.setString(3, a.name());
                ps.setString(4, a.breed());
                ps.setString(5, a.gender());
                ps.setString(6, a.birthDate() == null ? null : a.birthDate().toString());
                ps.setInt(7, a.quantity());
                ps.setString(8, a.notes());
                ps.setString(9, Instant.now().toString());
                ps.setString(10, a.uuid());
                ps.executeUpdate();
            }
            return null;
        });
        LocalDatabase.markChanged();
    }

    public static void softDelete(String uuid) {
        LocalDatabase.exec(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE client_animals SET is_deleted=1, updated_at=?, _dirty=1 WHERE uuid=?")) {
                ps.setString(1, Instant.now().toString());
                ps.setString(2, uuid);
                ps.executeUpdate();
            }
            return null;
        });
        LocalDatabase.markChanged();
    }

    // -------------------------------------------------------------------- sync

    public record AnimalPush(String uuid, ObjectNode json) {
    }

    /** Builds payloads; rows whose owning client has no server id yet are skipped (next cycle will push them). */
    public static List<AnimalPush> dirtyJsonForPush(List<String> issues) {
        return LocalDatabase.exec(c -> {
            List<AnimalPush> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM client_animals WHERE _dirty = 1");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Animal a = map(rs);
                    Long clientId = LocalDatabase.serverId("clients", a.clientUuid());
                    if (clientId == null) {
                        issues.add("Animal \"" + a.shortLabel() + "\" skipped: owner not synced yet");
                        continue;
                    }
                    ObjectNode o = Json.obj();
                    o.put("uuid", a.uuid());
                    o.put("client_id", clientId);
                    o.put("category", a.category());
                    o.put("species", a.species());
                    Json.putNullableText(o, "name", a.name());
                    Json.putNullableText(o, "breed", a.breed());
                    Json.putNullableText(o, "gender", a.gender());
                    Json.putNullableText(o, "birth_date",
                            a.birthDate() == null ? null : a.birthDate().toString());
                    o.put("quantity", a.quantity());
                    Json.putNullableText(o, "notes", a.notes());
                    o.put("is_deleted", a.deleted());
                    out.add(new AnimalPush(a.uuid(), o));
                }
            }
            return out;
        });
    }

    public static void applyServerRows(List<JsonNode> rows) {
        LocalDatabase.exec(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO client_animals(uuid, id, client_uuid, category, species, name," +
                    " breed, gender, birth_date, quantity, notes, created_at, updated_at," +
                    " is_deleted, _dirty) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,0) " +
                    "ON CONFLICT(uuid) DO UPDATE SET id=excluded.id," +
                    " client_uuid=excluded.client_uuid, category=excluded.category," +
                    " species=excluded.species, name=excluded.name, breed=excluded.breed," +
                    " gender=excluded.gender, birth_date=excluded.birth_date," +
                    " quantity=excluded.quantity, notes=excluded.notes," +
                    " created_at=excluded.created_at, updated_at=excluded.updated_at," +
                    " is_deleted=excluded.is_deleted WHERE client_animals._dirty = 0")) {
                for (JsonNode r : rows) {
                    long clientId = Json.lng(r, "client_id");
                    String clientUuid = exec0clientUuid(c, clientId);
                    int i = 1;
                    ps.setString(i++, Json.text(r, "uuid"));
                    ps.setLong(i++, Json.lng(r, "id"));
                    ps.setString(i++, clientUuid);
                    ps.setString(i++, Json.text(r, "category", "livestock"));
                    ps.setString(i++, Json.text(r, "species", "Other"));
                    ps.setString(i++, Json.text(r, "name"));
                    ps.setString(i++, Json.text(r, "breed"));
                    ps.setString(i++, Json.text(r, "gender", "unknown"));
                    LocalDate bd = Json.date(r, "birth_date");
                    ps.setString(i++, bd == null ? null : bd.toString());
                    ps.setInt(i++, Json.integer(r, "quantity"));
                    ps.setString(i++, Json.text(r, "notes"));
                    Instant created = Json.instant(r, "created_at");
                    Instant updated = Json.instant(r, "updated_at");
                    ps.setString(i++, created == null ? null : created.toString());
                    ps.setString(i++, updated == null ? null : updated.toString());
                    ps.setInt(i, Json.bool(r, "is_deleted") ? 1 : 0);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    static String exec0clientUuid(Connection c, long clientId) throws java.sql.SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT uuid FROM clients WHERE id = ?")) {
            ps.setLong(1, clientId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : "";
            }
        }
    }

    // ------------------------------------------------------------------ mapping

    static Animal map(ResultSet rs) throws java.sql.SQLException {
        String bd = rs.getString("birth_date");
        LocalDate birthDate = null;
        if (bd != null && !bd.isBlank()) {
            try {
                birthDate = LocalDate.parse(bd.substring(0, Math.min(10, bd.length())));
            } catch (Exception ignored) {
            }
        }
        return new Animal(
                rs.getString("uuid"),
                rs.getString("client_uuid"),
                rs.getString("category"),
                rs.getString("species"),
                rs.getString("name"),
                rs.getString("breed"),
                rs.getString("gender"),
                birthDate,
                rs.getInt("quantity"),
                rs.getString("notes"),
                rs.getInt("is_deleted") != 0,
                Json.parseInstant(rs.getString("updated_at")));
    }

    private static String nv(String v, String def) {
        return v == null || v.isBlank() ? def : v;
    }
}
