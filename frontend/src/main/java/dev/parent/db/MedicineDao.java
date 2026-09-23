package dev.parent.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.parent.model.Medicine;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Data access for medicines/products against the local SQLite cache. */
public final class MedicineDao {

    private MedicineDao() {
    }

    // ------------------------------------------------------------------- reads

    public static List<Medicine> listAll(String search) {
        return LocalDatabase.exec(c -> {
            String pattern = "%" + (search == null ? "" : search.trim().toLowerCase()) + "%";
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM medicines WHERE is_deleted = 0 " +
                    "AND (lower(name) LIKE ? OR lower(barcode) LIKE ?) ORDER BY lower(name)")) {
                ps.setString(1, pattern);
                ps.setString(2, pattern);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Medicine> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(map(rs));
                    }
                    return out;
                }
            }
        });
    }

    public static Optional<Medicine> byUuid(String uuid) {
        return LocalDatabase.exec(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM medicines WHERE uuid = ?")) {
                ps.setString(1, uuid);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                }
            }
        });
    }

    public static Optional<Medicine> byBarcode(String barcode) {
        return LocalDatabase.exec(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM medicines WHERE barcode = ? AND is_deleted = 0 " +
                    "ORDER BY lower(name) LIMIT 1")) {
                ps.setString(1, barcode);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                }
            }
        });
    }

    public static List<Medicine> lowStock() {
        return LocalDatabase.exec(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM medicines WHERE is_deleted = 0 AND stock <= low_stock_threshold " +
                    "ORDER BY stock ASC, lower(name)")) {
                try (ResultSet rs = ps.executeQuery()) {
                    List<Medicine> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(map(rs));
                    }
                    return out;
                }
            }
        });
    }

    public static List<Medicine> expiring(int days) {
        return LocalDatabase.exec(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM medicines WHERE is_deleted = 0 " +
                    "AND expiry_date IS NOT NULL AND expiry_date != '9999-12-31' " +
                    "AND expiry_date <= date('now','localtime','+' || ? || ' days') " +
                    "ORDER BY expiry_date")) {
                ps.setInt(1, days);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Medicine> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(map(rs));
                    }
                    return out;
                }
            }
        });
    }

    public record Totals(int products, int stockUnits, double buyValue, double sellValue) {
        public double potentialProfit() {
            return sellValue - buyValue;
        }
    }

    public static Totals totals() {
        return LocalDatabase.exec(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*), COALESCE(SUM(stock),0), COALESCE(SUM(stock*buy_price),0)," +
                    " COALESCE(SUM(stock*sell_price),0) FROM medicines WHERE is_deleted = 0");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new Totals(rs.getInt(1), rs.getInt(2), rs.getDouble(3), rs.getDouble(4));
            }
        });
    }

    // ------------------------------------------------------------------ writes

    public static Medicine insert(Medicine m) {
        Medicine withId = new Medicine(
                m.uuid() == null || m.uuid().isBlank() ? UUID.randomUUID().toString() : m.uuid(),
                m.barcode(), m.name(), m.type(), m.size(), m.fullSize(), m.buyPrice(), m.sellPrice(),
                m.expiryDate(), m.stock(), m.seller(), m.description(), m.lowStockThreshold(),
                false, Instant.now());
        LocalDatabase.exec(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO medicines(uuid, barcode, name, type, size, full_size, buy_price," +
                    " sell_price, expiry_date, stock, seller, description, low_stock_threshold," +
                    " created_at, updated_at, is_deleted, _dirty)" +
                    " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,1)")) {
                bindMedicine(ps, withId);
                ps.setString(15, withId.updatedAt().toString());
                ps.executeUpdate();
            }
            return null;
        });
        LocalDatabase.markChanged();
        return withId;
    }

    public static void update(Medicine m) {
        LocalDatabase.exec(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE medicines SET barcode=?, name=?, type=?, size=?, full_size=?," +
                    " buy_price=?, sell_price=?, expiry_date=?, stock=?, seller=?, description=?," +
                    " low_stock_threshold=?, updated_at=?, _dirty=1 WHERE uuid=?")) {
                ps.setString(1, m.barcode());
                ps.setString(2, m.name());
                ps.setString(3, m.type());
                ps.setDouble(4, m.size());
                ps.setDouble(5, m.fullSize());
                ps.setDouble(6, m.buyPrice());
                ps.setDouble(7, m.sellPrice());
                ps.setString(8, m.expiryDate() == null ? null : m.expiryDate().toString());
                ps.setInt(9, m.stock());
                ps.setString(10, m.seller());
                ps.setString(11, m.description());
                ps.setInt(12, m.lowStockThreshold());
                ps.setString(13, Instant.now().toString());
                ps.setString(14, m.uuid());
                ps.executeUpdate();
            }
            return null;
        });
        LocalDatabase.markChanged();
    }

    public static void softDelete(String uuid) {
        LocalDatabase.exec(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE medicines SET is_deleted=1, updated_at=?, _dirty=1 WHERE uuid=?")) {
                ps.setString(1, Instant.now().toString());
                ps.setString(2, uuid);
                ps.executeUpdate();
            }
            return null;
        });
        LocalDatabase.markChanged();
    }

    /**
     * Mirrors the server-side stock deduction for a sale recorded while offline
     * (same rules as the {@code apply_stock_deduction} trigger in schema.sql).
     * Does NOT mark the medicine dirty: the server trigger applies the
     * deduction there when the transaction is pushed.
     */
    static void applyLocalSale(Connection c, String medUuid, double quantity) {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM medicines WHERE uuid = ? AND is_deleted = 0")) {
            ps.setString(1, medUuid);
            Medicine m;
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new LocalDatabase.DaoException("Medicine not found for stock update");
                }
                m = map(rs);
            }
            int stock = m.stock();
            double size = m.size();
            if (quantity > 0 && m.remainingContent() + 1e-9 < quantity && m.fullSize() > 0) {
                throw new LocalDatabase.NotEnoughStockException(m.name(), m.remainingContent());
            }
            double remaining = size - quantity;
            if (m.fullSize() <= 0) {
                int units = (int) Math.ceil(Math.abs(quantity)) * (quantity >= 0 ? 1 : -1);
                if (quantity > 0 && stock < units) {
                    throw new LocalDatabase.NotEnoughStockException(m.name(), stock);
                }
                stock -= units;
            } else {
                while (remaining < -1e-9) {
                    stock--;
                    remaining += m.fullSize();
                }
                while (remaining >= m.fullSize()) {
                    stock++;
                    remaining -= m.fullSize();
                }
                if (stock < 0) {
                    throw new LocalDatabase.NotEnoughStockException(m.name(), m.remainingContent());
                }
            }
            try (PreparedStatement up = c.prepareStatement(
                    "UPDATE medicines SET stock=?, size=? WHERE uuid=?")) {
                up.setInt(1, stock);
                up.setDouble(2, stock > 0 ? Math.max(remaining, 0) : 0);
                up.setString(3, medUuid);
                up.executeUpdate();
            }
        } catch (java.sql.SQLException e) {
            throw new LocalDatabase.DaoException(e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------- sync

    /** Server json for one medicine. Stock fields stripped when pending transactions exist. */
    static ObjectNode toServerJson(Medicine m, boolean stripStock) {
        ObjectNode o = Json.obj();
        o.put("uuid", m.uuid());
        Json.putNullableText(o, "barcode", m.barcode());
        Json.putNullableText(o, "name", m.name());
        Json.putNullableText(o, "type", m.type());
        o.put("buy_price", m.buyPrice());
        o.put("sell_price", m.sellPrice());
        Json.putNullableText(o, "expiry_date",
                m.expiryDate() == null ? null : m.expiryDate().toString());
        Json.putNullableText(o, "seller", m.seller());
        Json.putNullableText(o, "description", m.description());
        o.put("low_stock_threshold", m.lowStockThreshold());
        o.put("is_deleted", m.deleted());
        if (!stripStock) {
            o.put("stock", m.stock());
            o.put("size", m.size());
            o.put("full_size", m.fullSize());
        } else {
            o.put("full_size", m.fullSize()); // setup info only - no current quantities
        }
        return o;
    }

    public record MedicinePush(String uuid, ObjectNode json) {
    }

    /** Builds upsert payloads for all dirty medicines (including soft-deleted rows). */
    public static List<MedicinePush> dirtyJsonForPush() {
        return LocalDatabase.exec(c -> {
            List<MedicinePush> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM medicines WHERE _dirty = 1");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Medicine m = map(rs);
                    boolean hasPendingTx;
                    try (PreparedStatement chk = c.prepareStatement(
                            "SELECT COUNT(*) FROM transactions WHERE _dirty = 1 AND medicine_uuid = ?")) {
                        chk.setString(1, m.uuid());
                        try (ResultSet cr = chk.executeQuery()) {
                            hasPendingTx = cr.next() && cr.getInt(1) > 0;
                        }
                    }
                    out.add(new MedicinePush(m.uuid(), toServerJson(m, hasPendingTx)));
                }
            }
            return out;
        });
    }

    /** Merges rows downloaded from Supabase. Dirty local rows win (last-write-wins, local priority). */
    public static void applyServerRows(List<JsonNode> rows) {
        LocalDatabase.exec(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO medicines(uuid, id, barcode, name, type, size, full_size, buy_price," +
                    " sell_price, expiry_date, stock, seller, description, low_stock_threshold," +
                    " created_at, updated_at, is_deleted, _dirty)" +
                    " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0) " +
                    "ON CONFLICT(uuid) DO UPDATE SET id=excluded.id, barcode=excluded.barcode," +
                    " name=excluded.name, type=excluded.type, size=excluded.size," +
                    " full_size=excluded.full_size, buy_price=excluded.buy_price," +
                    " sell_price=excluded.sell_price, expiry_date=excluded.expiry_date," +
                    " stock=excluded.stock, seller=excluded.seller, description=excluded.description," +
                    " low_stock_threshold=excluded.low_stock_threshold," +
                    " created_at=excluded.created_at, updated_at=excluded.updated_at," +
                    " is_deleted=excluded.is_deleted WHERE medicines._dirty = 0")) {
                for (JsonNode r : rows) {
                    int i = 1;
                    ps.setString(i++, Json.text(r, "uuid"));
                    ps.setLong(i++, Json.lng(r, "id"));
                    ps.setString(i++, Json.text(r, "barcode"));
                    ps.setString(i++, Json.text(r, "name"));
                    ps.setString(i++, Json.text(r, "type"));
                    ps.setDouble(i++, Json.dbl(r, "size"));
                    ps.setDouble(i++, Json.dbl(r, "full_size"));
                    ps.setDouble(i++, Json.dbl(r, "buy_price"));
                    ps.setDouble(i++, Json.dbl(r, "sell_price"));
                    ps.setString(i++, Json.text(r, "expiry_date"));
                    ps.setInt(i++, Json.integer(r, "stock"));
                    ps.setString(i++, Json.text(r, "seller"));
                    ps.setString(i++, Json.text(r, "description"));
                    ps.setInt(i++, Json.integer(r, "low_stock_threshold"));
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

    private static void bindMedicine(PreparedStatement ps, Medicine m) throws java.sql.SQLException {
        ps.setString(1, m.uuid());
        ps.setString(2, nv(m.barcode(), "[UNKNOWN BARCODE]"));
        ps.setString(3, nv(m.name(), "[UNKNOWN NAME]"));
        ps.setString(4, nv(m.type(), "[UNKNOWN TYPE]"));
        ps.setDouble(5, m.size());
        ps.setDouble(6, m.fullSize());
        ps.setDouble(7, m.buyPrice());
        ps.setDouble(8, m.sellPrice());
        ps.setString(9, m.expiryDate() == null ? Medicine.NO_EXPIRY.toString() : m.expiryDate().toString());
        ps.setInt(10, m.stock());
        ps.setString(11, nv(m.seller(), "[UNKNOWN SELLER]"));
        ps.setString(12, nv(m.description(), "[NO DESCRIPTION]"));
        ps.setInt(13, m.lowStockThreshold());
        ps.setString(14, Instant.now().toString());
    }

    private static String nv(String value, String def) {
        return value == null || value.isBlank() ? def : value;
    }

    static Medicine map(ResultSet rs) throws java.sql.SQLException {
        String expiry = rs.getString("expiry_date");
        LocalDate expiryDate = null;
        if (expiry != null && !expiry.isBlank()) {
            try {
                expiryDate = LocalDate.parse(expiry.substring(0, Math.min(10, expiry.length())));
            } catch (Exception ignored) {
            }
        }
        return new Medicine(
                rs.getString("uuid"),
                rs.getString("barcode"),
                rs.getString("name"),
                rs.getString("type"),
                rs.getDouble("size"),
                rs.getDouble("full_size"),
                rs.getDouble("buy_price"),
                rs.getDouble("sell_price"),
                expiryDate,
                rs.getInt("stock"),
                rs.getString("seller"),
                rs.getString("description"),
                rs.getInt("low_stock_threshold"),
                rs.getInt("is_deleted") != 0,
                Json.parseInstant(rs.getString("updated_at")));
    }
}
