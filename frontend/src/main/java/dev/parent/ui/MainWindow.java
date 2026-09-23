package dev.parent.ui;

import dev.parent.config.Log;
import dev.parent.db.AppointmentDao;
import dev.parent.db.LocalDatabase;
import dev.parent.db.MedicineDao;
import dev.parent.db.SyncService;
import dev.parent.model.Medicine;
import dev.parent.net.ScanRouter;
import dev.parent.scanner.ScannerService;
import dev.parent.util.FxUtil;
import dev.parent.util.SoundPlayer;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import javafx.stage.Stage;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The application shell: left icon navigation, a content area and a status
 * bar showing the online/offline sync state of the Supabase database.
 * It also routes global barcode scan events to the currently open screen.
 */
public final class MainWindow {

    /** Every screen derives from this and can refresh itself from the local cache. */
    public abstract static class BaseView extends BorderPane {
        public abstract String title();

        public abstract void refreshData();

        /** Optional scan handling while this view is visible. Return true when consumed. */
        public boolean handleScan(String code, Optional<Medicine> found) {
            return false;
        }
    }

    private static MainWindow instance;

    public static MainWindow get() {
        return instance;
    }

    private final Stage stage;
    private final StackPane content = new StackPane();
    private final Map<String, Supplier<BaseView>> factories = new LinkedHashMap<>();
    private final Map<String, BaseView> cache = new LinkedHashMap<>();
    private final Map<String, ToggleButton> navButtons = new LinkedHashMap<>();
    private String currentKey;

    private final Region syncDot = new Region();
    private final Label syncLabel = new Label("Offline");
    private final Label lastSyncLabel = new Label();
    private final Label pendingLabel = new Label();
    private final Region camDot = new Region();
    private final Label camLabel = new Label("Camera off");
    private final Button bell = new Button();

    public MainWindow(Stage stage) {
        this.stage = stage;
        instance = this;

        factories.put("dashboard", DashboardView::new);
        factories.put("daily", DailyUsageView::new);
        factories.put("transactions", TransactionsView::new);
        factories.put("clients", ClientsView::new);
        factories.put("medicines", MedicinesView::new);
        factories.put("appointments", AppointmentsView::new);
        factories.put("settings", SettingsView::new);

        BorderPane root = new BorderPane();
        root.setLeft(buildSidebar());
        root.setCenter(content);
        root.setBottom(buildStatusBar());

        Scene scene = new Scene(root, 1280, 780);
        var css = getClass().getResource("/dev/parent/res/theme.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }
        stage.setScene(scene);
        stage.setTitle("VetCustomerManager 4.0");
        FxUtil.setAppIcon(stage);
        stage.setMaximized(true);
        stage.setMinWidth(1024);
        stage.setMinHeight(640);

        ScannerService.attachHidCapture(scene);
        ScannerService.addListener(this::handleScan);

        SyncService.addListener(this::onSyncStatus);
        ScanRouter.get().addStatusListener(this::onCameraStatus);
        ScanRouter.get().refreshStatus();

        stage.show();
        show("daily"); // the POS is the home screen: scanning is what everyone does
    }

    // ---------------------------------------------------------------- sidebar

    private VBox buildSidebar() {
        VBox box = new VBox();
        box.getStyleClass().add("sidebar");
        Label title = new Label("Vet Manager");
        title.getStyleClass().add("sidebar-title");
        Label sub = new Label("online edition - Supabase sync");
        sub.getStyleClass().add("sidebar-sub");
        box.getChildren().addAll(title, sub);

        ToggleGroup group = new ToggleGroup();
        addNav(box, group, "dashboard", "Dashboard", "home.png");
        addNav(box, group, "daily", "Daily Usage", "coin.png");
        addNav(box, group, "transactions", "Transactions", "database.png");
        addNav(box, group, "clients", "Clients", "client.png");
        addNav(box, group, "medicines", "Medicines", "medicine.png");
        addNav(box, group, "appointments", "Appointments", "calendar.png");

        Region grow = new Region();
        VBox.setVgrow(grow, Priority.ALWAYS);
        box.getChildren().add(grow);
        addNav(box, group, "settings", "Settings", "reglage.png");
        return box;
    }

    private void addNav(VBox box, ToggleGroup group, String key, String text, String icon) {
        ToggleButton b = new ToggleButton("  " + text);
        b.setGraphic(FxUtil.icon(icon, 26));
        b.getStyleClass().add("nav-button");
        b.setMaxWidth(Double.MAX_VALUE);
        b.setToggleGroup(group);
        b.setOnAction(e -> show(key));
        b.selectedProperty().addListener((obs, o, on) -> {
            if (on) {
                if (!b.getStyleClass().contains("active")) {
                    b.getStyleClass().add("active");
                }
            } else {
                b.getStyleClass().remove("active");
            }
        });
        navButtons.put(key, b);
        box.getChildren().add(b);
    }

    // ------------------------------------------------------------- status bar

    private HBox buildStatusBar() {
        HBox bar = new HBox(14);
        bar.getStyleClass().add("statusbar");
        syncDot.getStyleClass().addAll("sync-dot", "sync-offline");

        Label dbLabel = new Label("Local data: " + LocalDatabase.file().getFileName());
        dbLabel.setTooltip(new Tooltip(LocalDatabase.file().toString()));

        Button refresh = new Button("Sync now");
        refresh.setGraphic(FxUtil.icon("refresh.png", 18));
        refresh.getStyleClass().add("ghost");
        refresh.setOnAction(e -> SyncService.syncNow());

        bell.setGraphic(FxUtil.icon("notification_off.png", 20));
        bell.getStyleClass().add("ghost");
        bell.setTooltip(new Tooltip("Stock & appointment reminders"));
        bell.setOnAction(e -> showNotifications());

        camDot.getStyleClass().addAll("sync-dot", "sync-offline");
        camLabel.setTooltip(new Tooltip("Camera barcode scanner - always on, no button to press"));

        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);

        bar.getChildren().addAll(syncDot, syncLabel, lastSyncLabel, pendingLabel,
                grow, dbLabel, camDot, camLabel, bell, refresh);
        return bar;
    }

    private void onCameraStatus(boolean running, String message) {
        Platform.runLater(() -> {
            camDot.getStyleClass().removeAll("sync-online", "sync-busy", "sync-error", "sync-offline");
            camDot.getStyleClass().add(running ? "sync-online" : "sync-error");
            camLabel.setText(running ? "CAMERA ON" : "Camera off");
            camLabel.setStyle(running
                    ? "-fx-text-fill:#66BB6A; -fx-font-weight:bold;"
                    : "-fx-text-fill:#90A4AE;");
            camLabel.setTooltip(new Tooltip(message));
        });
    }

    private void onSyncStatus(SyncService.Status s) {
        Platform.runLater(() -> {
            String dotClass = switch (s.state()) {
                case ONLINE -> "sync-online";
                case SYNCING -> "sync-busy";
                case ERROR -> "sync-error";
                case OFFLINE -> "sync-offline";
            };
            if (!syncDot.getStyleClass().contains(dotClass)) {
                syncDot.getStyleClass().removeAll("sync-online", "sync-busy", "sync-error", "sync-offline");
                syncDot.getStyleClass().add(dotClass);
            }
            syncLabel.setText(switch (s.state()) {
                case ONLINE -> "Online database connected";
                case SYNCING -> "Synchronizing with Supabase...";
                case ERROR -> "Sync problem";
                case OFFLINE -> "Offline mode (local data)";
            });
            lastSyncLabel.setText(s.lastSync() == null ? "" : "last sync " + FxUtil.dateTime(s.lastSync()));
            pendingLabel.setText(s.pending() == 0 ? "" : s.pending() + " change(s) waiting to upload");
            String tip = s.message() == null ? "" : s.message();
            if (!SyncService.lastIssues().isEmpty()) {
                tip += "\n" + String.join("\n", SyncService.lastIssues());
            }
            syncLabel.setTooltip(new Tooltip(tip));
            if (currentView() != null && s.state() == SyncService.State.ONLINE) {
                currentView().refreshData();
            }
        });
    }

    // -------------------------------------------------------------- navigation

    private BaseView currentView() {
        return currentKey == null ? null : cache.get(currentKey);
    }

    public void show(String key) {
        currentKey = key;
        BaseView view = cache.computeIfAbsent(key, k -> factories.get(k).get());
        ToggleButton b = navButtons.get(key);
        if (b != null && !b.isSelected()) {
            b.setSelected(true);
        }
        content.getChildren().setAll(view);
        stage.setTitle("VetCustomerManager 4.0 - " + view.title());
        view.refreshData();
        ScanRouter.get().postContext(key); // backend routes next scans to this view
    }

    /** Key of the view currently on screen (dashboard, daily, medicines, ...). */
    public String currentViewKey() {
        return currentKey;
    }

    public void refreshCurrent() {
        if (currentView() != null) {
            currentView().refreshData();
        }
    }

    /** Forces all cached views to reload (after a full sync for instance). */
    public void refreshAll() {
        cache.values().forEach(BaseView::refreshData);
    }

    public Stage stage() {
        return stage;
    }

    // ---------------------------------------------------------------- scanning

    /** Backend-sent scan whose routing hint already placed us on the right view. */
    public void forwardScanToCurrentView(String code) {
        BaseView view = currentView();
        Optional<Medicine> found = Optional.empty();
        try {
            found = MedicineDao.byBarcode(code);
        } catch (Throwable t) {
            Log.warn("Barcode lookup failed: " + t.getMessage());
        }
        boolean consumed = view != null && view.handleScan(code, found);
        if (!consumed) {
            handleScan(code); // classic POS fallback (also plays the sounds)
        }
    }

    /** Same behavior the USB/serial driver events get (view context + POS default). */
    public void handleExternalScan(String code) {
        handleScan(code);
    }

    private void handleScan(String code) {
        BaseView view = currentView();
        Optional<Medicine> found = Optional.empty();
        try {
            found = MedicineDao.byBarcode(code);
        } catch (Throwable t) {
            Log.warn("Barcode lookup failed: " + t.getMessage());
        }
        boolean consumed = view != null && view.handleScan(code, found);
        if (!consumed) {
            // POS behaviour: every known scan lands in the Daily Usage cart,
            // no matter which screen you were looking at.
            if (found.isPresent() && !"daily".equals(currentKey)) {
                show("daily");
                BaseView daily = cache.get("daily");
                if (daily != null && daily.handleScan(code, found)) {
                    return;
                }
            }
            if (found.isPresent()) {
                SoundPlayer.playFound();
                Medicine m = found.get();
                FxUtil.info(stage, "Product scanned",
                        m.name() + "\nStock: " + m.stock() + " unit(s), open: "
                                + FxUtil.qty(m.size()) + "\nSell price: " + FxUtil.money(m.sellPrice()));
            } else {
                SoundPlayer.playNotFound();
                FxUtil.error(stage, "Unknown barcode",
                        "No product with barcode \"" + code + "\" exists yet.\n"
                                + "Open Medicines to add it.");
            }
        }
    }

    // ------------------------------------------------------------ notifications

    private void showNotifications() {
        int days = dev.parent.config.AppConfig.get().notifyExpiryDays();
        dev.parent.util.Bg.load(() -> {
                    List<String> items = new java.util.ArrayList<>();
                    for (Medicine m : MedicineDao.lowStock()) {
                        items.add("LOW STOCK: " + m.name() + " - " + m.stock() + " unit(s) left");
                    }
                    for (Medicine m : MedicineDao.expiring(days)) {
                        items.add("EXPIRING: " + m.name() + " expires " + FxUtil.expiry(m.expiryDate()));
                    }
                    int appts = AppointmentDao.countPendingFor(LocalDate.now());
                    if (appts > 0) {
                        items.add("TODAY: " + appts + " appointment(s) still pending");
                    }
                    return items;
                },
                items -> showNotificationPopup(items));
    }

    private void showNotificationPopup(List<String> items) {
        ListView<String> list = new ListView<>();
        list.getItems().addAll(items);
        if (list.getItems().isEmpty()) {
            list.getItems().add("Nothing needs your attention. Great!");
        }
        long attention = items.size();
        list.setPrefSize(460, Math.min(360, 60 + list.getItems().size() * 34));
        bell.setGraphic(FxUtil.icon(attention > 0 ? "notification_on.png" : "notification_off.png", 20));
        Popup popup = new Popup();
        popup.getContent().add(list);
        var css = getClass().getResource("/dev/parent/res/theme.css");
        if (css != null) {
            list.getStylesheets().add(css.toExternalForm());
        }
        list.getStyleClass().add("root-pane");
        popup.setAutoHide(true);
        popup.show(bell,
                bell.localToScreen(bell.getBoundsInLocal()).getMinX() - 380,
                bell.localToScreen(bell.getBoundsInLocal()).getMaxY() + 6);
    }
}
