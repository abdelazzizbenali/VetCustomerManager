package dev.parent.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.parent.model.Appointment;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Data access for appointments. */
public final class AppointmentDao {

    public enum Filter {TODAY, THIS_WEEK, PENDING, ALL}

    private AppointmentDao() {
    }

    // ------------------------------------------------------------------- reads

    public static List<Appointment> list(Filter filter) {
        LocalDate today = LocalDate.now();
        return LocalDatabase.exec(c -> {
            String where = switch (filter) {
                case TODAY -> "a.appointment_date = '" + today + "'";
                case THIS_WEEK -> "a.appointment_date BETWEEN '" + today + "' AND '" + today.plusDays(7) + "'";
                case PENDING -> "a.is_done = 0";
                case ALL -> "1 = 1";
            };
            String sql = "SELECT a.*, c.name AS client_name FROM appointments a " +
                    "LEFT JOIN clients c ON c.uuid = a.client_uuid " +
                    "WHERE a.is_deleted = 0 AND " + where +
                    " ORDER BY a.appointment_date DESC, a.created_at DESC LIMIT 400";
            try (PreparedStatement ps = c.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                List<Appointment> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(map(rs));
                }
                return out;
            }
        });
    }

    public static int countPendingFor(LocalDate date) {
        return LocalDatabase.exec(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM appointments WHERE is_deleted = 0 AND is_done = 0 " +
                    "AND appointment_date = ?")) {
                ps.setString(1, date.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    public static List<Appointment> pendingFor(LocalDate date) {
        return LocalDatabase.exec(c -> {
            String sql = "SELECT a.*, c.name AS client_name FROM appointments a " +
                    "LEFT JOIN clients c ON c.uuid = a.client_uuid " +
                    "WHERE a.is_deleted = 0 AND a.is_done = 0 AND a.appointment_date = ? " +
                    "ORDER BY a.created_at";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, date.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    List<Appointment> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(map(rs));
                    }
                    return out;
                }
            }
        });
    }

    // ------------------------------------------------------------------ writes

    public static Appointment insert(Appointment a) {
        Appointment withId = new Appointment(
                a.uuid() == null || a.uuid().isBlank() ? UUID.randomUUID().toString() : a.uuid(),
                a.clientUuid(), null, a.date(), a.done(), a.description(), false, Instant.now());
        LocalDatabase.exec(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO appointments(uuid, client_uuid, appointment_date, is_done," +
                    " description, created_at, updated_at, is_deleted, _dirty)" +
                    " VALUES(?,?,?,?,?,?,?,0,1)")) {
                ps.setString(1, withId.uuid());
                ps.setString(2, withId.clientUuid());
                ps.setString(3, withId.date().toString());
                ps.setInt(4, withId.done() ? 1 : 0);
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

    public static void setDone(String uuid, boolean done) {
        LocalDatabase.exec(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE appointments SET is_done=?, updated_at=?, _dirty=1 WHERE uuid=?")) {
                ps.setInt(1, done ? 1 : 0);
                ps.setString(2, Instant.now().toString());
                ps.setString(3, uuid);
                ps.executeUpdate();
            }
            return null;
        });
        LocalDatabase.markChanged();
    }

    public static void softDelete(String uuid) {
        LocalDatabase.exec(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE appointments SET is_deleted=1, updated_at=?, _dirty=1 WHERE uuid=?")) {
                ps.setString(1, Instant.now().toString());
                ps.setString(2, uuid);
                ps.executeUpdate();
            }
            return null;
        });
        LocalDatabase.markChanged();
    }

    // -------------------------------------------------------------------- sync

    public record AppointmentPush(String uuid, ObjectNode json) {
    }

    public static List<AppointmentPush> dirtyJsonForPush(List<String> issues) {
        return LocalDatabase.exec(c -> {
            List<AppointmentPush> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM appointments WHERE _dirty = 1");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Appointment a = map(rs);
                    Long clientId = LocalDatabase.serverId("clients", a.clientUuid());
                    if (clientId == null) {
                        issues.add("Appointment skipped: client not synced yet (will retry)");
                        continue;
                    }
                    ObjectNode o = Json.obj();
                    o.put("uuid", a.uuid());
                    o.put("client_id", clientId);
                    o.put("appointment_date", a.date().toString());
                    o.put("is_done", a.done());
                    Json.putNullableText(o, "description", a.description());
                    o.put("is_deleted", a.deleted());
                    out.add(new AppointmentPush(a.uuid(), o));
                }
            }
            return out;
        });
    }

    public static void applyServerRows(List<JsonNode> rows) {
        LocalDatabase.exec(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO appointments(uuid, id, client_uuid, appointment_date, is_done," +
                    " description, created_at, updated_at, is_deleted, _dirty)" +
                    " VALUES(?,?,?,?,?,?,?,?,?,0) " +
                    "ON CONFLICT(uuid) DO UPDATE SET id=excluded.id," +
                    " client_uuid=excluded.client_uuid, appointment_date=excluded.appointment_date," +
                    " is_done=excluded.is_done, description=excluded.description," +
                    " created_at=excluded.created_at, updated_at=excluded.updated_at," +
                    " is_deleted=excluded.is_deleted WHERE appointments._dirty = 0")) {
                for (JsonNode r : rows) {
                    String clientUuid = AnimalDao.exec0clientUuid(c, Json.lng(r, "client_id"));
                    Instant created = Json.instant(r, "created_at");
                    Instant updated = Json.instant(r, "updated_at");
                    int i = 1;
                    ps.setString(i++, Json.text(r, "uuid"));
                    ps.setLong(i++, Json.lng(r, "id"));
                    ps.setString(i++, clientUuid);
                    ps.setString(i++, Json.text(r, "appointment_date"));
                    ps.setInt(i++, Json.bool(r, "is_done") ? 1 : 0);
                    ps.setString(i++, Json.text(r, "description"));
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

    static Appointment map(ResultSet rs) throws java.sql.SQLException {
        String raw = rs.getString("appointment_date");
        LocalDate date = raw == null ? LocalDate.now()
                : LocalDate.parse(raw.substring(0, Math.min(10, raw.length())));
        String clientName;
        try {
            clientName = rs.getString("client_name");
        } catch (java.sql.SQLException e) {
            clientName = "[?]";
        }
        return new Appointment(
                rs.getString("uuid"),
                rs.getString("client_uuid"),
                clientName == null ? "[?]" : clientName,
                date,
                rs.getInt("is_done") != 0,
                rs.getString("description"),
                rs.getInt("is_deleted") != 0,
                Json.parseInstant(rs.getString("updated_at")));
    }
}
