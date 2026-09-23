package dev.parent.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.parent.model.SaleTransaction;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Data access for the daily usage / purchases journal. */
public final class TransactionDao {

    private TransactionDao() {
    }

    // ------------------------------------------------------------------- reads

    public static List<SaleTransaction> listToday() {
        return listByDate(LocalDate.now());
    }

    public static List<SaleTransaction> listByDate(LocalDate date) {
        return LocalDatabase.exec(c -> listWhere(c,
                "t.is_deleted = 0 AND t.created_date = ?", ps -> ps.setString(1, date.toString())));
    }

    /** Every transaction in [from, to] inclusive - the analytic range the dashboard queries. */
    public static List<SaleTransaction> listBetween(LocalDate from, LocalDate to) {
        return LocalDatabase.exec(c -> listWhere(c,
                "t.is_deleted = 0 AND t.created_date >= ? AND t.created_date <= ?",
                ps -> {
                    ps.setString(1, from.toString());
                    ps.setString(2, to.toString());
                }));
    }

    public static List<SaleTransaction> listByClient(String clientUuid) {
        return LocalDatabase.exec(c -> listWhere(c,
                "t.is_deleted = 0 AND t.client_uuid = ?", ps -> ps.setString(1, clientUuid)));
    }

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement ps) throws java.sql.SQLException;
    }

    private static List<SaleTransaction> listWhere(Connection c, String where, Binder binder)
            throws java.sql.SQLException {
        String sql = "SELECT t.*, c.name AS client_name, m.name AS med_name " +
                "FROM transactions t " +
                "LEFT JOIN clients c ON c.uuid = t.client_uuid " +
                "LEFT JOIN medicines m ON m.uuid = t.medicine_uuid " +
                "WHERE " + where + " ORDER BY t.created_at DESC";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                List<SaleTransaction> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(map(rs));
                }
                return out;
            }
        }
    }

    public record DaySummary(LocalDate date, long count, double totalAmount, double unpaidAmount) {
    }

    public static List<DaySummary> dailySummary() {
        return LocalDatabase.exec(c -> {
            String sql = "SELECT created_date, COUNT(*), COALESCE(SUM(amount),0), " +
                    "COALESCE(SUM(CASE WHEN paid = 0 THEN amount END),0) " +
                    "FROM transactions WHERE is_deleted = 0 AND created_date IS NOT NULL " +
                    "GROUP BY created_date ORDER BY created_date DESC LIMIT 500";
            try (PreparedStatement ps = c.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                List<DaySummary> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new DaySummary(LocalDate.parse(rs.getString(1)),
                            rs.getLong(2), rs.getDouble(3), rs.getDouble(4)));
                }
                return out;
            }
        });
    }

    public record DayTotals(long count, double total, double paid, double unpaid) {
    }

    public static DayTotals totalsFor(LocalDate date) {
        return LocalDatabase.exec(c -> {
            String sql = "SELECT COUNT(*), COALESCE(SUM(amount),0), " +
                    "COALESCE(SUM(CASE WHEN paid = 1 THEN amount END),0), " +
                    "COALESCE(SUM(CASE WHEN paid = 0 THEN amount END),0) " +
                    "FROM transactions WHERE is_deleted = 0 AND created_date = ?";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, date.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return new DayTotals(rs.getLong(1), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4));
                }
            }
        });
    }

    // ------------------------------------------------------------------ writes

    /**
     * Records one transaction. When a medicine + quantity are present the local
     * stock is updated immediately with the exact rules the server trigger applies.
     * The whole thing runs as one JDBC transaction: either everything is saved or nothing.
     */
    public static SaleTransaction insert(SaleTransaction t) {
        OffsetDateTime now = OffsetDateTime.now();
        SaleTransaction withId = new SaleTransaction(
                t.uuid() == null || t.uuid().isBlank() ? UUID.randomUUID().toString() : t.uuid(),
                t.clientUuid(), null, t.medicineUuid(), null,
                now, t.quantity(), t.amount(), t.type(), t.description(), t.paid(),
                false, Instant.now());
        LocalDatabase.exec(c -> {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                if (withId.medicineUuid() != null && !withId.medicineUuid().isBlank()
                        && withId.quantity() != 0) {
                    MedicineDao.applyLocalSale(c, withId.medicineUuid(), withId.quantity());
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO transactions(uuid, client_uuid, medicine_uuid, created_at," +
                        " created_date, quantity, amount, type, description, paid, updated_at," +
                        " is_deleted, _dirty) VALUES(?,?,?,?,?,?,?,?,?,?,?,0,1)")) {
                    ps.setString(1, withId.uuid());
                    ps.setString(2, withId.clientUuid());
                    ps.setString(3, emptyToNull(withId.medicineUuid()));
                    ps.setString(4, now.toInstant().toString());
                    ps.setString(5, LocalDate.now().toString());
                    ps.setDouble(6, withId.quantity());
                    ps.setDouble(7, withId.amount());
                    ps.setString(8, withId.type());
                    ps.setString(9, withId.description());
                    ps.setInt(10, withId.paid() ? 1 : 0);
                    ps.setString(11, Instant.now().toString());
                    ps.executeUpdate();
                }
                c.commit();
            } catch (RuntimeException | java.sql.SQLException e) {
                c.rollback();
                throw e instanceof RuntimeException re ? re : new LocalDatabase.DaoException(e.getMessage(), e);
            } finally {
                c.setAutoCommit(auto);
            }
            return null;
        });
        LocalDatabase.markChanged();
        return withId;
    }

    public static void setPaid(List<String> uuids, boolean paid) {
        if (uuids == null || uuids.isEmpty()) {
            return;
        }
        LocalDatabase.exec(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE transactions SET paid=?, updated_at=?, _dirty=1 WHERE uuid=?")) {
                for (String uuid : uuids) {
                    ps.setInt(1, paid ? 1 : 0);
                    ps.setString(2, Instant.now().toString());
                    ps.setString(3, uuid);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
        LocalDatabase.markChanged();
    }

    /**
     * Soft-delete (void) a transaction. Note: like the original app, this does
     * NOT put the sold quantity back into stock - do a negative-quantity
     * correction sale if you need that.
     */
    public static void softDelete(String uuid) {
        LocalDatabase.exec(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE transactions SET is_deleted=1, updated_at=?, _dirty=1 WHERE uuid=?")) {
                ps.setString(1, Instant.now().toString());
                ps.setString(2, uuid);
                ps.executeUpdate();
            }
            return null;
        });
        LocalDatabase.markChanged();
    }

    // -------------------------------------------------------------------- sync

    public record TxPush(String uuid, ObjectNode json) {
    }

    public static List<TxPush> dirtyJsonForPush(List<String> issues) {
        return LocalDatabase.exec(c -> {
            List<TxPush> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM transactions WHERE _dirty = 1");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    SaleTransaction t = map(rs);
                    Long clientId = LocalDatabase.serverId("clients", t.clientUuid());
                    if (clientId == null) {
                        issues.add("Sale for a not-yet-synced client skipped (will retry)");
                        continue;
                    }
                    ObjectNode o = Json.obj();
                    o.put("uuid", t.uuid());
                    o.put("client_id", clientId);
                    if (t.medicineUuid() != null && !t.medicineUuid().isBlank()) {
                        Long medId = LocalDatabase.serverId("medicines", t.medicineUuid());
                        if (medId == null) {
                            issues.add("Sale skipped: medicine not synced yet (will retry)");
                            continue;
                        }
                        o.put("medicine_id", medId);
                    } else {
                        o.putNull("medicine_id");
                    }
                    o.put("created_at", t.createdAt() == null
                            ? OffsetDateTime.now(ZoneOffset.UTC).toString()
                            : t.createdAt().toString());
                    o.put("quantity", t.quantity());
                    o.put("amount", t.amount());
                    Json.putNullableText(o, "type", t.type());
                    Json.putNullableText(o, "description", t.description());
                    o.put("paid", t.paid());
                    o.put("is_deleted", t.deleted());
                    out.add(new TxPush(t.uuid(), o));
                }
            }
            return out;
        });
    }

    public static void applyServerRows(List<JsonNode> rows) {
        LocalDatabase.exec(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO transactions(uuid, id, client_uuid, medicine_uuid, created_at," +
                    " created_date, quantity, amount, type, description, paid, updated_at," +
                    " is_deleted, _dirty) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,0) " +
                    "ON CONFLICT(uuid) DO UPDATE SET id=excluded.id," +
                    " client_uuid=excluded.client_uuid, medicine_uuid=excluded.medicine_uuid," +
                    " created_at=excluded.created_at, created_date=excluded.created_date," +
                    " quantity=excluded.quantity, amount=excluded.amount, type=excluded.type," +
                    " description=excluded.description, paid=excluded.paid," +
                    " updated_at=excluded.updated_at, is_deleted=excluded.is_deleted " +
                    "WHERE transactions._dirty = 0")) {
                for (JsonNode r : rows) {
                    String clientUuid = AnimalDao.exec0clientUuid(c, Json.lng(r, "client_id"));
                    long medId = Json.lng(r, "medicine_id");
                    String medUuid = medId == 0 ? null : medUuid(c, medId);
                    Instant created = Json.instant(r, "created_at");
                    Instant updated = Json.instant(r, "updated_at");
                    int i = 1;
                    ps.setString(i++, Json.text(r, "uuid"));
                    ps.setLong(i++, Json.lng(r, "id"));
                    ps.setString(i++, clientUuid);
                    ps.setString(i++, medUuid);
                    ps.setString(i++, created == null ? null : created.toString());
                    ps.setString(i++, created == null ? LocalDate.now().toString()
                            : created.atZone(ZoneId.systemDefault()).toLocalDate().toString());
                    ps.setDouble(i++, Json.dbl(r, "quantity"));
                    ps.setDouble(i++, Json.dbl(r, "amount"));
                    ps.setString(i++, Json.text(r, "type"));
                    ps.setString(i++, Json.text(r, "description"));
                    ps.setInt(i++, Json.bool(r, "paid") ? 1 : 0);
                    ps.setString(i++, updated == null ? null : updated.toString());
                    ps.setInt(i, Json.bool(r, "is_deleted") ? 1 : 0);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    private static String medUuid(Connection c, long medId) throws java.sql.SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT uuid FROM medicines WHERE id = ?")) {
            ps.setLong(1, medId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    // ------------------------------------------------------------------ mapping

    static SaleTransaction map(ResultSet rs) throws java.sql.SQLException {
        Instant created = Json.parseInstant(rs.getString("created_at"));
        OffsetDateTime createdAt = created == null
                ? OffsetDateTime.now()
                : created.atOffset(ZoneId.systemDefault().getRules().getOffset(created));
        String clientName = null;
        String medName = null;
        try {
            clientName = rs.getString("client_name");
        } catch (java.sql.SQLException ignored) {
        }
        try {
            medName = rs.getString("med_name");
        } catch (java.sql.SQLException ignored) {
        }
        return new SaleTransaction(
                rs.getString("uuid"),
                rs.getString("client_uuid"),
                clientName == null ? "[?]" : clientName,
                rs.getString("medicine_uuid"),
                medName,
                createdAt,
                rs.getDouble("quantity"),
                rs.getDouble("amount"),
                rs.getString("type"),
                rs.getString("description"),
                rs.getInt("paid") != 0,
                rs.getInt("is_deleted") != 0,
                Json.parseInstant(rs.getString("updated_at")));
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
