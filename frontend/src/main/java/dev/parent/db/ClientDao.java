package dev.parent.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.parent.model.Animal;
import dev.parent.model.Client;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.UUID;

/** Data access for clients against the local SQLite cache. */
public final class ClientDao {

    private ClientDao() {
    }

    /** A client row plus its computed aggregates for the list view. */
    public record ClientSummary(Client client, double totalPaid, double totalUnpaid,
                                String animalsSummary) {
    }

    // ------------------------------------------------------------------- reads

    public static List<ClientSummary> listSummaries(String search) {
        return LocalDatabase.exec(c -> {
            // One pass over ALL animals, grouped in Java - avoids one query per client row.
            java.util.Map<String, List<Animal>> animalsByClient = allAnimalsByClient(c);
            String pattern = "%" + (search == null ? "" : search.trim().toLowerCase()) + "%";
            String sql = "SELECT c.*, " +
                    "(SELECT COALESCE(SUM(t.amount),0) FROM transactions t " +
                    "  WHERE t.client_uuid = c.uuid AND t.is_deleted = 0 AND t.paid = 1) AS total_paid, " +
                    "(SELECT COALESCE(SUM(t.amount),0) FROM transactions t " +
                    "  WHERE t.client_uuid = c.uuid AND t.is_deleted = 0 AND t.paid = 0) AS total_unpaid " +
                    "FROM clients c WHERE c.is_deleted = 0 " +
                    "AND (lower(c.name) LIKE ? OR lower(c.phone) LIKE ?) ORDER BY lower(c.name)";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, pattern);
                ps.setString(2, pattern);
                try (ResultSet rs = ps.executeQuery()) {
                    List<ClientSummary> out = new ArrayList<>();
                    while (rs.next()) {
                        Client client = map(rs);
                        out.add(new ClientSummary(client,
                                rs.getDouble("total_paid"),
                                rs.getDouble("total_unpaid"),
                                summarizeAnimals(animalsByClient.get(client.uuid()))));
                    }
                    return out;
                }
            }
        });
    }

    /** Every visible animal in a single SELECT, keyed by client uuid. */
    private static java.util.Map<String, List<Animal>> allAnimalsByClient(java.sql.Connection c)
            throws java.sql.SQLException {
        java.util.Map<String, List<Animal>> out = new java.util.LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM client_animals WHERE is_deleted = 0 " +
                "ORDER BY category DESC, species, name");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.computeIfAbsent(rs.getString("client_uuid"), k -> new ArrayList<>())
                        .add(AnimalDao.map(rs));
            }
        }
        return out;
    }

    /** "Rex (Dog), Milou (Cat)  |  Sheep x45, Cow x12" - or empty. */
    private static String summarizeAnimals(List<Animal> animals) {
        if (animals == null || animals.isEmpty()) {
            return "";
        }
        StringJoiner pets = new StringJoiner(", ");
        StringJoiner stock = new StringJoiner(", ");
        for (Animal a : animals) {
            if (a.isPet()) {
                pets.add(a.shortLabel());
            } else {
                stock.add(a.shortLabel());
            }
        }
        if (pets.toString().isEmpty()) {
            return stock.toString();
        }
        if (stock.toString().isEmpty()) {
            return pets.toString();
        }
        return pets + "  |  " + stock;
    }

    public static Optional<Client> byUuid(String uuid) {
        return LocalDatabase.exec(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM clients WHERE uuid = ?")) {
                ps.setString(1, uuid);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                }
            }
        });
    }

    public static List<Client> listAll() {
        return LocalDatabase.exec(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM clients WHERE is_deleted = 0 ORDER BY lower(name)");
                 ResultSet rs = ps.executeQuery()) {
                List<Client> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(map(rs));
                }
                return out;
            }
        });
    }

    public static int countAll() {
        return LocalDatabase.exec(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM clients WHERE is_deleted = 0");
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
    }

    /** "Rex (Dog), Milou (Cat) | Sheep x45, Cow x12" - or empty when no animals. */
    static String animalsSummary(java.sql.Connection c, String clientUuid) throws java.sql.SQLException {
        List<Animal> animals = AnimalDao.listByClient(c, clientUuid);
        StringJoiner pets = new StringJoiner(", ");
        StringJoiner stock = new StringJoiner(", ");
        for (Animal a : animals) {
            if (a.isPet()) {
                pets.add(a.shortLabel());
            } else {
                stock.add(a.shortLabel());
            }
        }
        if (pets.toString().isEmpty()) {
            return stock.toString();
        }
        if (stock.toString().isEmpty()) {
            return pets.toString();
        }
        return pets + "  |  " + stock;
    }

    public static record MoneyTotals(double paid, double unpaid) {
    }

    public static MoneyTotals moneyTotals(String clientUuid) {
        return LocalDatabase.exec(c -> {
            String sql = "SELECT " +
                    "COALESCE(SUM(CASE WHEN paid = 1 THEN amount END),0), " +
                    "COALESCE(SUM(CASE WHEN paid = 0 THEN amount END),0) " +
                    "FROM transactions WHERE client_uuid = ? AND is_deleted = 0";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, clientUuid);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return new MoneyTotals(rs.getDouble(1), rs.getDouble(2));
                }
            }
        });
    }

    public static MoneyTotals globalUnpaidTotals() {
        return LocalDatabase.exec(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COALESCE(SUM(CASE WHEN paid = 1 THEN amount END),0), " +
                    "COALESCE(SUM(CASE WHEN paid = 0 THEN amount END),0) " +
                    "FROM transactions WHERE is_deleted = 0");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new MoneyTotals(rs.getDouble(1), rs.getDouble(2));
            }
        });
    }

    // ------------------------------------------------------------------ writes

    public static Client insert(Client cl) {
        Client withId = new Client(
                cl.uuid() == null || cl.uuid().isBlank() ? UUID.randomUUID().toString() : cl.uuid(),
                nv(cl.name(), "[UNKNOWN NAME]"), nv(cl.phone(), ""), nv(cl.address(), ""),
                nv(cl.description(), "[NO DESCRIPTION]"), false, Instant.now());
        LocalDatabase.exec(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO clients(uuid, name, phone, address, description, created_at," +
                    " updated_at, is_deleted, _dirty) VALUES(?,?,?,?,?,?,?,0,1)")) {
                ps.setString(1, withId.uuid());
                ps.setString(2, withId.name());
                ps.setString(3, withId.phone());
                ps.setString(4, withId.address());
                ps.setString(5, withId.description());
                ps.setString(6, withId.updatedAt().toString());
                ps.setString(7, withId.updatedAt().toString());
                ps.executeUpdate();
            }
            return null;
        });
        LocalDatabase.markChanged();
        return withId;
    }

    public static void update(Client cl) {
        LocalDatabase.exec(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE clients SET name=?, phone=?, address=?, description=?, updated_at=?, " +
                    "_dirty=1 WHERE uuid=?")) {
                ps.setString(1, cl.name());
                ps.setString(2, cl.phone());
                ps.setString(3, cl.address());
                ps.setString(4, cl.description());
                ps.setString(5, Instant.now().toString());
                ps.setString(6, cl.uuid());
                ps.executeUpdate();
            }
            return null;
        });
        LocalDatabase.markChanged();
    }

    /** Soft-deletes the client and all of its animals (transactions are kept for accounting). */
    public static void softDelete(String uuid) {
        LocalDatabase.exec(c -> {
            String now = Instant.now().toString();
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE clients SET is_deleted=1, updated_at=?, _dirty=1 WHERE uuid=?")) {
                ps.setString(1, now);
                ps.setString(2, uuid);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE client_animals SET is_deleted=1, updated_at=?, _dirty=1 WHERE client_uuid=?")) {
                ps.setString(1, now);
                ps.setString(2, uuid);
                ps.executeUpdate();
            }
            return null;
        });
        LocalDatabase.markChanged();
    }

    // -------------------------------------------------------------------- sync

    public record ClientPush(String uuid, ObjectNode json) {
    }

    public static List<ClientPush> dirtyJsonForPush() {
        return LocalDatabase.exec(c -> {
            List<ClientPush> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM clients WHERE _dirty = 1");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Client cl = map(rs);
                    ObjectNode o = Json.obj();
                    o.put("uuid", cl.uuid());
                    o.put("name", cl.name());
                    Json.putNullableText(o, "phone", cl.phone());
                    Json.putNullableText(o, "address", cl.address());
                    Json.putNullableText(o, "description", cl.description());
                    o.put("is_deleted", cl.deleted());
                    out.add(new ClientPush(cl.uuid(), o));
                }
            }
            return out;
        });
    }

    public static void applyServerRows(List<JsonNode> rows) {
        LocalDatabase.exec(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO clients(uuid, id, name, phone, address, description, created_at," +
                    " updated_at, is_deleted, _dirty) VALUES(?,?,?,?,?,?,?,?,?,0) " +
                    "ON CONFLICT(uuid) DO UPDATE SET id=excluded.id, name=excluded.name," +
                    " phone=excluded.phone, address=excluded.address," +
                    " description=excluded.description, created_at=excluded.created_at," +
                    " updated_at=excluded.updated_at, is_deleted=excluded.is_deleted " +
                    "WHERE clients._dirty = 0")) {
                for (JsonNode r : rows) {
                    int i = 1;
                    ps.setString(i++, Json.text(r, "uuid"));
                    ps.setLong(i++, Json.lng(r, "id"));
                    ps.setString(i++, Json.text(r, "name"));
                    ps.setString(i++, Json.text(r, "phone"));
                    ps.setString(i++, Json.text(r, "address"));
                    ps.setString(i++, Json.text(r, "description"));
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

    // ------------------------------------------------------------------ mapping

    static Client map(ResultSet rs) throws java.sql.SQLException {
        return new Client(
                rs.getString("uuid"),
                rs.getString("name"),
                rs.getString("phone"),
                rs.getString("address"),
                rs.getString("description"),
                rs.getInt("is_deleted") != 0,
                Json.parseInstant(rs.getString("updated_at")));
    }

    private static String nv(String v, String def) {
        return v == null || v.isBlank() ? def : v;
    }
}
