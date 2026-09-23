package dev.parent.db;

import com.fasterxml.jackson.databind.JsonNode;
import dev.parent.config.AppConfig;
import dev.parent.config.Log;
import javafx.application.Platform;

import java.io.IOException;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Background synchronisation between the local SQLite cache and Supabase.
 *
 * Push order matters: parents first so foreign keys resolve:
 *   clients -> medicines -> animals -> transactions -> appointments
 * Then every table is pulled back (rows changed elsewhere since last pull),
 * which is how several PCs (and later the mobile apps) stay in sync.
 *
 * Offline behaviour: any network error just marks the service OFFLINE and the
 * next scheduled tick retries - the UI keeps working against the local cache.
 */
public final class SyncService {

    public enum State {OFFLINE, SYNCING, ONLINE, ERROR}

    public record Status(State state, Instant lastSync, int pending, String message) {
    }

    private static final List<Consumer<Status>> LISTENERS = new CopyOnWriteArrayList<>();
    private static final AtomicBoolean SYNCING = new AtomicBoolean(false);
    private static final AtomicBoolean SOON_REQUESTED = new AtomicBoolean(false);

    private static volatile SupabaseClient client;
    private static ScheduledExecutorService executor;
    private static volatile Status lastStatus = new Status(State.OFFLINE, null, 0, "Not connected");
    private static volatile List<String> issues = List.of();

    private SyncService() {
    }

    // ------------------------------------------------------------------ public

    public static synchronized void configure(SupabaseClient supabase) {
        client = supabase;
    }

    public static boolean isConfigured() {
        return client != null;
    }

    public static void addListener(Consumer<Status> listener) {
        LISTENERS.add(listener);
        Status s = lastStatus;
        Platform.runLater(() -> listener.accept(s));
    }

    public static Status status() {
        return lastStatus;
    }

    public static boolean isOnline() {
        return lastStatus.state() == State.ONLINE;
    }

    public static List<String> lastIssues() {
        return issues;
    }

    /** Starts the scheduler (initial sync after 1s, then every configured interval). */
    public static synchronized void start() {
        stop();
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "vetms-sync");
            t.setDaemon(true);
            return t;
        });
        LocalDatabase.onLocalChange(SyncService::requestSoon);
        executor.scheduleWithFixedDelay(SyncService::safeSync,
                1, AppConfig.get().syncIntervalSec(), TimeUnit.SECONDS);
        Log.info("Sync service started (every " + AppConfig.get().syncIntervalSec() + "s)");
    }

    public static synchronized void restart() {
        start();
    }

    public static synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    /** Ask for a sync as soon as possible (used after local edits). Debounced. */
    public static void requestSoon() {
        ScheduledExecutorService ex = executor;
        if (ex == null || SOON_REQUESTED.getAndSet(true)) {
            return;
        }
        ex.schedule(() -> {
            SOON_REQUESTED.set(false);
            safeSync();
        }, 1500, TimeUnit.MILLISECONDS);
    }

    /** Immediate explicit sync (the Refresh button). */
    public static void syncNow() {
        ScheduledExecutorService ex = executor;
        if (ex != null) {
            ex.execute(SyncService::safeSync);
        }
    }

    /** Wipes the local cache and re-downloads everything from Supabase. */
    public static void fullResync() {
        ScheduledExecutorService ex = executor;
        if (ex == null) {
            return;
        }
        ex.execute(() -> {
            LocalDatabase.exec(c -> {
                for (String t : new String[]{"transactions", "client_animals", "appointments",
                        "medicines", "clients"}) {
                    try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + t)) {
                        ps.executeUpdate();
                    }
                    LocalDatabase.setLastPull(t, null);
                }
                return null;
            });
            safeSync();
        });
    }

    // ------------------------------------------------------------------- cycle

    private static void safeSync() {
        if (!SYNCING.compareAndSet(false, true)) {
            return;
        }
        try {
            doSync();
        } catch (Throwable t) {
            Log.error("Sync failed", t);
            publish(new Status(State.ERROR, lastStatus.lastSync(), LocalDatabase.pendingCount(),
                    "Sync error: " + t.getMessage()));
        } finally {
            SYNCING.set(false);
        }
    }

    private static void doSync() throws IOException, InterruptedException {
        SupabaseClient sc = client;
        if (sc == null || !LocalDatabase.isOpen()) {
            publish(new Status(State.OFFLINE, lastStatus.lastSync(), LocalDatabase.pendingCount(),
                    "Supabase is not configured yet"));
            return;
        }
        publish(new Status(State.SYNCING, lastStatus.lastSync(),
                LocalDatabase.pendingCount(), "Synchronizing..."));
        List<String> sessionIssues = new ArrayList<>();
        try {
            sc.ping();
        } catch (SupabaseClient.ApiException e) {
            // The server answered: credentials/schema problem, NOT a network problem.
            issues = sessionIssues;
            publish(new Status(State.ERROR, lastStatus.lastSync(), LocalDatabase.pendingCount(),
                    e.getMessage()));
            return;
        } catch (IOException e) {
            publish(new Status(State.OFFLINE, lastStatus.lastSync(), LocalDatabase.pendingCount(),
                    "No internet / Supabase unreachable"));
            return;
        }

        pushClients(sc, sessionIssues);
        pushMedicines(sc, sessionIssues);
        pushAnimals(sc, sessionIssues);
        pushTransactions(sc, sessionIssues);
        pushAppointments(sc, sessionIssues);

        pull(sc, "clients", ClientDao::applyServerRows);
        pull(sc, "medicines", MedicineDao::applyServerRows);
        pull(sc, "client_animals", AnimalDao::applyServerRows);
        pull(sc, "transactions", TransactionDao::applyServerRows);
        pull(sc, "appointments", AppointmentDao::applyServerRows);

        issues = List.copyOf(sessionIssues);
        publish(new Status(State.ONLINE, Instant.now(), LocalDatabase.pendingCount(),
                sessionIssues.isEmpty() ? "Up to date"
                        : sessionIssues.size() + " item(s) need attention"));
    }

    // -------------------------------------------------------------------- push

    private static void push(SupabaseClient sc, String table,
                             List<? extends Object> items, List<String> sessionIssues)
            throws IOException, InterruptedException {
        for (Object item : items) {
            String uuid;
            JsonNode json;
            switch (item) {
                case ClientDao.ClientPush p -> {
                    uuid = p.uuid();
                    json = p.json();
                }
                case MedicineDao.MedicinePush p -> {
                    uuid = p.uuid();
                    json = p.json();
                }
                case AnimalDao.AnimalPush p -> {
                    uuid = p.uuid();
                    json = p.json();
                }
                case TransactionDao.TxPush p -> {
                    uuid = p.uuid();
                    json = p.json();
                }
                case AppointmentDao.AppointmentPush p -> {
                    uuid = p.uuid();
                    json = p.json();
                }
                default -> throw new IllegalStateException("Unknown push item " + item.getClass());
            }
            try {
                JsonNode saved = sc.upsert(table, json);
                markClean(table, uuid, saved);
            } catch (SupabaseClient.ApiException e) {
                sessionIssues.add(table + " " + uuid.substring(0, Math.min(8, uuid.length()))
                        + " -> " + e.getMessage());
                Log.warn("Push rejected for " + table + "/" + uuid + ": " + e.getMessage());
            }
        }
    }

    private static void markClean(String table, String uuid, JsonNode serverRow) {
        long id = Json.lng(serverRow, "id");
        Instant updated = Json.instant(serverRow, "updated_at");
        LocalDatabase.exec(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE " + table + " SET _dirty = 0, id = ?, updated_at = ? WHERE uuid = ?")) {
                ps.setLong(1, id);
                ps.setString(2, updated == null ? Instant.now().toString() : updated.toString());
                ps.setString(3, uuid);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private static void pushClients(SupabaseClient sc, List<String> issues)
            throws IOException, InterruptedException {
        push(sc, "clients", new ArrayList<>(ClientDao.dirtyJsonForPush()), issues);
    }

    private static void pushMedicines(SupabaseClient sc, List<String> issues)
            throws IOException, InterruptedException {
        push(sc, "medicines", new ArrayList<>(MedicineDao.dirtyJsonForPush()), issues);
    }

    private static void pushAnimals(SupabaseClient sc, List<String> issues)
            throws IOException, InterruptedException {
        push(sc, "client_animals", new ArrayList<>(AnimalDao.dirtyJsonForPush(issues)), issues);
    }

    private static void pushTransactions(SupabaseClient sc, List<String> issues)
            throws IOException, InterruptedException {
        push(sc, "transactions", new ArrayList<>(TransactionDao.dirtyJsonForPush(issues)), issues);
    }

    private static void pushAppointments(SupabaseClient sc, List<String> issues)
            throws IOException, InterruptedException {
        push(sc, "appointments", new ArrayList<>(AppointmentDao.dirtyJsonForPush(issues)), issues);
    }

    // -------------------------------------------------------------------- pull

    private interface RowApplier {
        void apply(List<JsonNode> rows);
    }

    private static void pull(SupabaseClient sc, String table, RowApplier applier)
            throws IOException, InterruptedException {
        Instant since = LocalDatabase.lastPull(table);
        List<JsonNode> rows = sc.fetchUpdated(table, since);
        if (!rows.isEmpty()) {
            applier.apply(rows);
            Instant max = since;
            for (JsonNode r : rows) {
                Instant u = Json.instant(r, "updated_at");
                if (u != null && (max == null || u.isAfter(max))) {
                    max = u;
                }
            }
            if (max != null) {
                LocalDatabase.setLastPull(table, max);
            }
        }
    }

    // ------------------------------------------------------------------ status

    private static void publish(Status status) {
        lastStatus = status;
        for (Consumer<Status> l : LISTENERS) {
            Platform.runLater(() -> l.accept(status));
        }
    }
}
