package dev.parent.ui;

import dev.parent.VetApp;
import dev.parent.config.AppConfig;
import dev.parent.config.AppDirs;
import dev.parent.config.Log;
import dev.parent.db.ClientDao;
import dev.parent.db.LocalDatabase;
import dev.parent.db.MedicineDao;
import dev.parent.db.SupabaseClient;
import dev.parent.db.SyncService;
import dev.parent.db.TransactionDao;
import dev.parent.model.Client;
import dev.parent.model.Medicine;
import dev.parent.model.SaleTransaction;
import dev.parent.scanner.ScannerService;
import dev.parent.util.Bg;
import dev.parent.util.FxUtil;
import dev.parent.util.SoundPlayer;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Settings: database connection, sync behaviour, scanner devices, data tools. */
final class SettingsView extends MainWindow.BaseView {

    private final AppConfig cfg = AppConfig.get();
    private final TextField urlField = new TextField();
    private final PasswordField keyField = new PasswordField();
    private final Label dbStatus = new Label(" ");
    private final Spinner<Integer> interval = new Spinner<>(10, 600, 30, 5);
    private final CheckBox sounds = new CheckBox("Play Found / notFound sounds when scanning");
    private final CheckBox hid = new CheckBox("Enable USB keyboard scanner (plug && play)");
    private final CheckBox cameraAlwaysOn = new CheckBox(
            "Camera always on - show a barcode anytime, no button to press");
    private final CheckBox showPreview = new CheckBox(
            "Show the live camera preview on Daily Usage (turn off for a lighter screen)");
    private final TextField dynamsoftKey = new TextField();
    private final Label cameraEngineInfo = new Label("Built-in Java camera driver "
            + "(the only engine now - no external programs to leave running behind)");
    private final Label serverScanStatus = new Label("Backend: checking...");
    private final ComboBox<String> serialPort = new ComboBox<>();
    private final ComboBox<Integer> serialBaud = new ComboBox<>(
            javafx.collections.FXCollections.observableArrayList(1200, 2400, 4800, 9600, 19200, 38400, 57600, 115200));
    private final TextField gap = new TextField();
    private final Label issues = new Label();

    SettingsView() {
        urlField.setText(cfg.supabaseUrl());
        keyField.setText(cfg.supabaseKey());
        interval.getValueFactory().setValue(cfg.syncIntervalSec());
        interval.setEditable(true);
        sounds.setSelected(cfg.soundsEnabled());
        hid.setSelected(cfg.hidEnabled());
        cameraAlwaysOn.setSelected(cfg.cameraAlwaysOn());
        cameraAlwaysOn.setOnAction(e -> {
            cfg.setCameraAlwaysOn(cameraAlwaysOn.isSelected());
            cfg.save();
            if (cameraAlwaysOn.isSelected()) {
                Bg.run("camera-on", () -> dev.parent.scanner.CameraScanService.get().start());
            } else {
                Bg.run("camera-off", () -> dev.parent.scanner.CameraScanService.get().stop());
            }
        });
        showPreview.setSelected(cfg.cameraPreviewVisible());
        showPreview.setOnAction(e -> {
            cfg.setCameraPreviewVisible(showPreview.isSelected());
            cfg.save();
            MainWindow w = MainWindow.get();
            if (w != null) {
                w.refreshCurrent(); // Daily Usage applies it instantly
            }
        });
        cameraEngineInfo.getStyleClass().add("hint");
        serverScanStatus.getStyleClass().add("hint");
        serverScanStatus.setWrapText(true);
        dynamsoftKey.setText(cfg.dynamsoftLicense());
        dynamsoftKey.setPromptText("Optional: Dynamsoft license key (30-day free trial on dynamsoft.com)");
        gap.setText(String.valueOf(cfg.hidMaxGapMs()));
        serialBaud.getSelectionModel().select(Integer.valueOf(cfg.serialBaud()));

        VBox root = new VBox(4);
        root.setPadding(new Insets(20));
        Label title = new Label("Settings");
        title.getStyleClass().add("view-title");
        root.getChildren().addAll(title,
                buildSupabaseSection(),
                buildClinicServerSection(),
                buildSyncSection(),
                buildScannerSection(),
                buildDataSection(),
                buildAboutSection());
        ScrollPane scroll = new ScrollPane(root);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: #263238;");
        setCenter(scroll);
    }

    // ------------------------------------------------------------- sections

    private VBox buildSupabaseSection() {
        VBox box = section("Online database (Supabase)");
        GridPane grid = new GridPane();
        grid.getStyleClass().add("form-grid");
        grid.add(rowLabel("Project URL"), 0, 0);
        grid.add(urlField, 1, 0);
        grid.add(rowLabel("API key (anon public)"), 0, 1);
        grid.add(keyField, 1, 1);
        urlField.setPrefColumnCount(34);
        keyField.setPrefColumnCount(34);

        Button test = Ui.toolButton("Test connection", "database.png", () -> testConnection(null));
        Button save = Ui.toolButton("Save && apply", "check.png", () -> {
            cfg.setSupabase(SupabaseClient.normalizeBaseUrl(urlField.getText()), keyField.getText());
            cfg.save();
            if (cfg.hasSupabase()) {
                SyncService.configure(new SupabaseClient(cfg.supabaseUrl(), cfg.supabaseKey()));
                SyncService.restart();
                dbStatus.setStyle("-fx-text-fill:#FFB300;");
                dbStatus.setText("Saved. Connecting...");
            } else {
                dbStatus.setStyle("-fx-text-fill:#EF5350;");
                dbStatus.setText("Saved, but URL or key is empty - offline mode.");
            }
        });
        save.getStyleClass().add("accent");
        HBox buttons = new HBox(10, test, save);
        box.getChildren().addAll(grid, buttons, dbStatus);
        return box;
    }

    // --------------------------------------------------------- clinic server

    private final TextField remoteUrl = new TextField();
    private final TextField remoteToken = new TextField();

    /**
     * One database for the whole clinic: run the optional "Server" launcher
     * on ONE PC (it serves /api/supabase with ITS Supabase credentials),
     * then every other PC just points at it - no keys, no setup anywhere else.
     */
    private VBox buildClinicServerSection() {
        VBox box = section("Clinic server (share one database across PCs)");
        remoteUrl.setText(cfg.remoteServerUrl());
        remoteUrl.setPromptText("http://192.168.1.15:9677 - leave empty on the server PC");
        remoteUrl.setPrefColumnCount(34);
        remoteToken.setText(cfg.remoteServerToken());
        remoteToken.setPromptText("server token - see %APPDATA%\\VetCustomerManager\\server-info.json on the server PC");
        remoteToken.setPrefColumnCount(34);

        GridPane grid = new GridPane();
        grid.getStyleClass().add("form-grid");
        grid.add(rowLabel("Server address"), 0, 0);
        grid.add(remoteUrl, 1, 0);
        grid.add(rowLabel("Server token"), 0, 1);
        grid.add(remoteToken, 1, 1);

        Button test = Ui.toolButton("Test server link", "refresh.png", () -> {
            cfg.setRemoteServer(remoteUrl.getText(), remoteToken.getText());
            cfg.save();
            if (!cfg.hasRemoteServer()) {
                FxUtil.error(getWindow(), "Clinic server",
                        "Fill the server address and token first.");
                return;
            }
            Bg.run("clinic-test", () -> {
                try {
                    int v = new SupabaseClient("", "").ping();
                    javafx.application.Platform.runLater(() -> FxUtil.info(getWindow(),
                            "Clinic server", "Connected through the server (schema v" + v + ")."));
                } catch (Throwable t) {
                    javafx.application.Platform.runLater(() -> FxUtil.error(getWindow(),
                            "Clinic server", "Server not answering correctly:\n" + t.getMessage()));
                }
            });
        });
        Button save = Ui.toolButton("Save clinic server", "check.png", () -> {
            cfg.setRemoteServer(remoteUrl.getText(), remoteToken.getText());
            cfg.save();
            if (cfg.hasSupabase() || cfg.hasRemoteServer()) {
                SyncService.configure(new SupabaseClient(cfg.supabaseUrl(), cfg.supabaseKey()));
                SyncService.restart();
            }
            String mode = cfg.hasRemoteServer()
                    ? "This PC now works THROUGH the clinic server at "
                    + cfg.remoteServerUrl() + "\nSame data everywhere - try Sync now."
                    : "Clinic-server link cleared - this PC uses its own settings.";
            FxUtil.info(getWindow(), "Clinic server", mode);
        });
        save.getStyleClass().add("accent");

        Label hint = new Label("Server PC: leave BOTH fields empty and put the Supabase URL/key "
                + "in the section above, then start the \"Server (only on the clinic's database PC)\" "
                + "shortcut from the Start Menu and leave it running (the address it serves is written "
                + "into server-info.json).\n"
                + "Other PCs: paste the server PC's address and token here. Every scan and edit then "
                + "goes through the server - one database for the whole clinic.");
        hint.getStyleClass().add("hint");
        hint.setWrapText(true);
        box.getChildren().addAll(grid, new HBox(10, test, save), hint);
        return box;
    }

    private void testConnection(Runnable onOk) {
        dbStatus.setStyle("-fx-text-fill:#FFB300;");
        dbStatus.setText("Testing...");
        Task<Integer> task = new Task<>() {
            @Override
            protected Integer call() throws Exception {
                return new SupabaseClient(urlField.getText(), keyField.getText()).ping();
            }
        };
        task.setOnSucceeded(e -> {
            dbStatus.setStyle("-fx-text-fill:#66BB6A;");
            dbStatus.setText("Connection OK (schema v" + task.getValue() + ")");
            if (onOk != null) {
                onOk.run();
            }
        });
        task.setOnFailed(e -> {
            dbStatus.setStyle("-fx-text-fill:#EF5350;");
            dbStatus.setText("Failed: " + task.getException().getMessage());
        });
        Thread t = new Thread(task, "vetms-db-test");
        t.setDaemon(true);
        t.start();
    }

    private VBox buildSyncSection() {
        VBox box = section("Synchronisation");
        GridPane grid = new GridPane();
        grid.getStyleClass().add("form-grid");
        grid.add(rowLabel("Sync every (seconds)"), 0, 0);
        grid.add(interval, 1, 0);
        Button apply = Ui.toolButton("Apply", "check.png", () -> {
            cfg.setSyncIntervalSec(interval.getValue());
            cfg.save();
            SyncService.restart();
            FxUtil.info(getWindow(), "Sync", "Sync interval updated.");
        });
        grid.add(apply, 2, 0);

        Button now = Ui.toolButton("Sync now", "refresh.png", SyncService::syncNow);
        Button full = Ui.toolButton("Full re-download", "database_add.png", () -> {
            if (FxUtil.confirm(getWindow(), "Full re-download",
                    "Discard the local cache and re-download everything from Supabase?\n"
                            + "Changes not yet uploaded will be LOST.")) {
                SyncService.fullResync();
            }
        });
        full.getStyleClass().add("danger");
        issues.getStyleClass().add("hint");
        issues.setWrapText(true);
        box.getChildren().addAll(grid, new HBox(10, now, full), issues);
        return box;
    }

    private VBox buildScannerSection() {
        VBox box = section("Barcode / QR scanner devices");
        GridPane grid = new GridPane();
        grid.getStyleClass().add("form-grid");
        grid.add(rowLabel("Scanner type"), 0, 0);
        grid.add(new VBox(6, sounds, hid, cameraAlwaysOn, showPreview), 1, 0);
        grid.add(rowLabel("Key speed threshold (ms)"), 0, 1);
        grid.add(gap, 1, 1);
        grid.add(rowLabel("Serial port (optional)"), 0, 2);
        HBox portRow = new HBox(10, serialPort, Ui.toolButton("Detect ports", "refresh.png", this::detectPorts));
        portRow.setAlignment(Pos.CENTER_LEFT);
        grid.add(portRow, 1, 2);
        grid.add(rowLabel("Baud rate"), 0, 3);
        grid.add(serialBaud, 1, 3);
        grid.add(rowLabel("Dynamsoft license"), 0, 4);
        HBox keyRow = new HBox(10, dynamsoftKey,
                Ui.toolButton("Save key", "check.png", this::saveDynamsoftKey));
        keyRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(dynamsoftKey, Priority.ALWAYS);
        dynamsoftKey.setPrefWidth(320);
        grid.add(keyRow, 1, 4);
        grid.add(rowLabel("Camera engine (backend)"), 0, 5);
        grid.add(cameraEngineInfo, 1, 5);
        grid.add(rowLabel("Backend scanner"), 0, 6);
        grid.add(serverScanStatus, 1, 6);
        serialPort.setEditable(true);
        serialPort.setPrefWidth(160);
        serialPort.setPromptText("none");
        String current = cfg.serialPort();
        serialPort.getItems().setAll(ScannerService.availableSerialPorts());
        if (!current.isEmpty()) {
            serialPort.setValue(current);
        }

        Button apply = Ui.toolButton("Apply scanner settings", "check.png", () -> {
            cfg.setSoundsEnabled(sounds.isSelected());
            cfg.setHidEnabled(hid.isSelected());
            try {
                cfg.setHidMaxGapMs(Integer.parseInt(gap.getText().trim()));
            } catch (NumberFormatException ignored) {
            }
            cfg.setSerialPort(serialPort.getValue() == null ? "" : serialPort.getValue().trim());
            cfg.setSerialBaud(serialBaud.getValue() == null ? 9600 : serialBaud.getValue());
            cfg.save();
            SoundPlayer.setEnabled(true);
            ScannerService.startSerialReader();
            FxUtil.info(getWindow(), "Scanner", "Scanner settings applied.");
        });
        apply.getStyleClass().add("accent");
        Label hint = new Label("USB scanners work out of the box: no driver needed. "
                + "Serial scanners: pick the COM port after pressing Detect ports.");
        hint.getStyleClass().add("hint");
        hint.setWrapText(true);
        box.getChildren().addAll(grid, apply, hint);
        return box;
    }

    private void saveDynamsoftKey() {
        cfg.setDynamsoftLicense(dynamsoftKey.getText());
        cfg.save();
        // the premium engine lives in THIS process now: reload it in place
        Thread.ofVirtual().name("vetms-dynamsoft-save").start(() -> {
            dev.parent.scanner.DynamsoftLocal.reinit();
            boolean live = dev.parent.scanner.DynamsoftLocal.isAvailable();
            String engineMsg = dev.parent.scanner.DynamsoftLocal.lastError();
            String msg = live
                    ? "Dynamsoft premium scanning is now ACTIVE.\nShow a barcode to the camera!"
                    : dynamsoftKey.getText().isBlank()
                    ? "Key cleared - the free built-in scanner engine is used."
                    : "The engine did not start.\n\nReason: " + engineMsg
                    + "\n\nThe free engine keeps scanning meanwhile.";
            javafx.application.Platform.runLater(
                    () -> FxUtil.info(getWindow(), "License key", msg));
            refreshDataLater();
        });
    }

    private void refreshDataLater() {
        javafx.application.Platform.runLater(this::refreshData);
    }

    private void backendCall(String path) {
        // legacy hook kept for callers with no backend left: harmless no-op
        refreshData();
    }

    private void backend(String path, Object unused) {
        backendCall(path);
    }

    private void detectPorts() {
        serialPort.getItems().setAll(ScannerService.availableSerialPorts());
        if (serialPort.getItems().isEmpty()) {
            serialPort.setPromptText("No serial port detected");
        }
    }

    private VBox buildDataSection() {
        VBox box = section("Local data");
        Label path = new Label("Stored in: " + AppDirs.dataDir());
        path.getStyleClass().add("hint");
        path.setWrapText(true);
        Button export = Ui.toolButton("Export everything to CSV", "database.png", this::exportCsv);
        HBox row = new HBox(10, export);
        box.getChildren().addAll(path, row);
        return box;
    }

    private void exportCsv() {
        try {
            Path dir = AppDirs.dataDir().resolve("exports").resolve(
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
            Files.createDirectories(dir);
            writeMedicinesCsv(dir.resolve("medicines.csv"));
            writeClientsCsv(dir.resolve("clients.csv"));
            writeTransactionsCsv(dir.resolve("transactions.csv"));
            FxUtil.info(getWindow(), "Export done", "CSV files written to:\n" + dir);
        } catch (IOException | UncheckedIOException e) {
            FxUtil.error(getWindow(), "Export failed", e.getMessage());
        }
    }

    private static void writeMedicinesCsv(Path file) throws IOException {
        StringBuilder sb = new StringBuilder("name;barcode;type;stock;open_size;full_size;buy_price;sell_price;expiry;seller\n");
        for (Medicine m : MedicineDao.listAll("")) {
            sb.append(csv(m.name())).append(';').append(csv(m.barcode())).append(';')
                    .append(csv(m.type())).append(';').append(m.stock()).append(';')
                    .append(m.size()).append(';').append(m.fullSize()).append(';')
                    .append(m.buyPrice()).append(';').append(m.sellPrice()).append(';')
                    .append(FxUtil.expiry(m.expiryDate())).append(';').append(csv(m.seller())).append('\n');
        }
        Files.writeString(file, sb);
    }

    private static void writeClientsCsv(Path file) throws IOException {
        StringBuilder sb = new StringBuilder("name;phone;address;description;paid_total;unpaid_total\n");
        for (ClientDao.ClientSummary s : ClientDao.listSummaries("")) {
            Client c = s.client();
            sb.append(csv(c.name())).append(';').append(csv(c.phone())).append(';')
                    .append(csv(c.address())).append(';').append(csv(c.description())).append(';')
                    .append(s.totalPaid()).append(';').append(s.totalUnpaid()).append('\n');
        }
        Files.writeString(file, sb);
    }

    private static void writeTransactionsCsv(Path file) throws IOException {
        StringBuilder sb = new StringBuilder("date;time;client;medicine;quantity;amount;type;description;paid\n");
        for (TransactionDao.DaySummary day : TransactionDao.dailySummary()) {
            for (SaleTransaction t : TransactionDao.listByDate(day.date())) {
                sb.append(FxUtil.date(t.createdAt().toLocalDate())).append(';')
                        .append(FxUtil.time(t.createdAt())).append(';')
                        .append(csv(t.clientName())).append(';')
                        .append(csv(t.medicineName() == null ? "" : t.medicineName())).append(';')
                        .append(t.quantity()).append(';').append(t.amount()).append(';')
                        .append(csv(t.type())).append(';').append(csv(t.description())).append(';')
                        .append(t.paid() ? "yes" : "no").append('\n');
            }
        }
        Files.writeString(file, sb);
    }

    private static String csv(String s) {
        if (s == null) {
            return "";
        }
        if (s.contains(";") || s.contains("\"") || s.contains("\n")) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }

    private VBox buildAboutSection() {
        VBox box = section("About");
        Label about = new Label("VetCustomerManager " + VetApp.VERSION + "\n"
                + "Offline-first veterinary customer manager.\n"
                + "Data: your Supabase project (PostgreSQL) + local SQLite cache.\n"
                + "Scanning: USB keyboard scanners, serial scanners, camera (ZXing).");
        about.getStyleClass().add("hint");
        about.setWrapText(true);
        box.getChildren().add(about);
        return box;
    }

    // ---------------------------------------------------------------- helpers

    private VBox section(String title) {
        VBox box = new VBox(6);
        box.getStyleClass().add("card");
        box.setPadding(new Insets(16));
        VBox.setMargin(box, new Insets(10, 0, 10, 0));
        Label t = new Label(title);
        t.getStyleClass().add("card-title");
        box.getChildren().add(t);
        return box;
    }

    private Label rowLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("form-label");
        return l;
    }

    private javafx.stage.Window getWindow() {
        return getScene() == null ? MainWindow.get().stage() : getScene().getWindow();
    }

    @Override
    public String title() {
        return "Settings";
    }

    @Override
    public void refreshData() {
        Bg.run("camera-status", () -> {
                        try {
                            var cam = dev.parent.scanner.CameraScanService.get();
                            boolean running = cam.running();
                            String message = cam.statusText();
                            boolean dynamsoft = !cfg.dynamsoftLicense().isEmpty()
                                    && dev.parent.scanner.DynamsoftLocal.isAvailable();
                            String dynamsoftMsg = dev.parent.scanner.DynamsoftLocal.lastError();
                            javafx.application.Platform.runLater(() -> {
                                String base = running
                                        ? "Engine: built-in - " + message
                                        + "  [decode: " + (dynamsoft ? "dynamsoft" : "zxing") + "]"
                                        : "Not scanning - " + message
                                        + " (toggle \"camera always on\" or check Settings)";
                                String dyn = cfg.dynamsoftLicense().isEmpty() ? ""
                                        : dynamsoft
                                        ? "   |   Dynamsoft PREMIUM: active"
                                        : "   |   Dynamsoft: not active"
                                        + (dynamsoftMsg.isBlank() ? "" : " - " + dynamsoftMsg);
                                serverScanStatus.setText(base + dyn);
                            });
                        } catch (Throwable t) {
                            javafx.application.Platform.runLater(() -> serverScanStatus.setText(
                                    "Camera engine unavailable: " + t.getMessage()));
                        }
                    });
        SyncService.Status s = SyncService.status();
        if (s.state() == SyncService.State.ONLINE && " ".equals(dbStatus.getText())) {
            dbStatus.setStyle("-fx-text-fill:#66BB6A;");
            dbStatus.setText("Connected and in sync.");
        }
        if (!SyncService.lastIssues().isEmpty()) {
            issues.setText("Attention needed:\n- " + String.join("\n- ", SyncService.lastIssues()));
        } else {
            issues.setText("");
        }
    }
}
