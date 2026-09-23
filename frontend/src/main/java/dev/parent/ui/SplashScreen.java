package dev.parent.ui;

import dev.parent.config.AppConfig;
import dev.parent.config.AppDirs;
import dev.parent.config.Log;
import dev.parent.db.LocalDatabase;
import dev.parent.db.SupabaseClient;
import dev.parent.scanner.ScannerService;
import dev.parent.util.SoundPlayer;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The startup screen the user sees while the app "is loading and checking
 * needed things": data folder, local database, sound output, camera, barcode
 * scanner driver and - when configured - the online Supabase database.
 * Each component is reported live with OK / warning / failure.
 */
public final class SplashScreen {

    public enum CheckStatus {RUNNING, OK, WARN, FAIL}

    public record CheckResult(String name, CheckStatus status, String detail, boolean critical) {
    }

    public record Report(List<CheckResult> results, boolean criticalFailure, boolean supabaseOnline) {
    }

    private static final String[] NAMES = {
            "Preparing application folder",
            "Loading configuration",
            "Opening local database",
            "Checking sound system",
            "Starting backend service (Spring Boot)",
            "Detecting camera scan engine",
            "Starting barcode scanner driver",
            "Contacting online database (Supabase)"
    };

    private final Stage stage;
    private final List<Label> statusIcons = new ArrayList<>();
    private final List<Label> detailLabels = new ArrayList<>();
    private final Label headline = new Label("Starting VetCustomerManager...");
    private final ProgressBar overall = new ProgressBar(0);

    public SplashScreen(Stage stage) {
        this.stage = stage;
    }

    public void show() {
        VBox box = new VBox(10);
        box.getStyleClass().add("splash");
        Label title = new Label("VetCustomerManager");
        title.getStyleClass().add("splash-title");
        Label sub = new Label("Please wait - the program is loading and checking required components");
        sub.getStyleClass().add("splash-sub");
        headline.getStyleClass().add("splash-sub");
        overall.setPrefWidth(460);
        overall.setPrefHeight(10);

        VBox checks = new VBox(6);
        checks.setPrefWidth(460);
        for (String name : NAMES) {
            Label icon = new Label("...");
            icon.setStyle("-fx-text-fill:#78909C; -fx-min-width: 30px;");
            Label detail = new Label(name);
            detail.setStyle("-fx-text-fill:#B0BEC5;");
            javafx.scene.layout.HBox row = new javafx.scene.layout.HBox(8, icon, detail);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPrefWidth(460);
            detail.setWrapText(true);
            statusIcons.add(icon);
            detailLabels.add(detail);
            checks.getChildren().add(row);
        }

        box.getChildren().addAll(title, sub, headline, overall, checks);
        Scene scene = new Scene(box, 560, 420);
        var css = getClass().getResource("/dev/parent/res/theme.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }
        stage.initStyle(StageStyle.UNDECORATED);
        stage.setScene(scene);
        stage.centerOnScreen();
        stage.show();
    }

    public void close() {
        stage.close();
    }

    /** Runs all checks on a worker thread; the callback fires on the FX thread. */
    public void runChecks(Consumer<Report> onDone) {
        Thread worker = new Thread(() -> {
            List<CheckResult> results = new ArrayList<>();
            boolean criticalFailure = false;
            boolean supabaseOnline = false;
            AppConfig cfg = AppConfig.get();

            for (int step = 0; step < NAMES.length; step++) {
                setStep(step, CheckStatus.RUNNING, NAMES[step]);
                CheckResult r = switch (step) {
                    case 0 -> checkAppDir();
                    case 1 -> checkConfig(cfg);
                    case 2 -> checkLocalDb();
                    case 3 -> checkSound();
                    case 4 -> checkBackend();
                    case 5 -> checkCamera();
                    case 6 -> checkScanner();
                    default -> checkSupabase(cfg);
                };
                if (step == 7 && r.status() == CheckStatus.OK) {
                    supabaseOnline = true;
                }
                results.add(r);
                setStep(step, r);
                if (r.critical() && r.status() == CheckStatus.FAIL) {
                    criticalFailure = true;
                    break;
                }
                sleep(320); // let the user read what is happening
            }
            Report report = new Report(List.copyOf(results), criticalFailure, supabaseOnline);
            int total = report.criticalFailure() ? results.size() : NAMES.length;
            final double progress = (double) total / NAMES.length;
            Platform.runLater(() -> {
                overall.setProgress(progress);
                headline.setText(report.criticalFailure()
                        ? "A required component failed to start"
                        : "Everything is ready - starting the program...");
                onDone.accept(report);
            });
        }, "vetms-startup-checks");
        worker.setDaemon(true);
        worker.start();
    }

    // ------------------------------------------------------------------ checks

    private CheckResult checkAppDir() {
        try {
            return new CheckResult(NAMES[0], CheckStatus.OK,
                    "Data folder ready: " + AppDirs.ensure(), true);
        } catch (Throwable t) {
            return new CheckResult(NAMES[0], CheckStatus.FAIL,
                    "Cannot write application folder: " + t.getMessage(), true);
        }
    }

    private CheckResult checkConfig(AppConfig cfg) {
        cfg.load();
        if (cfg.hasSupabase()) {
            return new CheckResult(NAMES[1], CheckStatus.OK,
                    "Configuration found (" + cfg.supabaseUrl() + ")", true);
        }
        return new CheckResult(NAMES[1], CheckStatus.WARN,
                "First launch: the Supabase URL and API key will be asked in a moment", true);
    }

    private CheckResult checkLocalDb() {
        try {
            LocalDatabase.open();
            return new CheckResult(NAMES[2], CheckStatus.OK,
                    "Offline cache ready: " + LocalDatabase.file().getFileName(), true);
        } catch (Throwable t) {
            Log.error("Local database failed to open", t);
            return new CheckResult(NAMES[2], CheckStatus.FAIL,
                    "Local database error: " + t.getMessage(), true);
        }
    }

    private CheckResult checkSound() {
        if (SoundPlayer.audioAvailable()) {
            return new CheckResult(NAMES[3], CheckStatus.OK,
                    "Scan sounds enabled (Found / notFound feedback)", false);
        }
        return new CheckResult(NAMES[3], CheckStatus.WARN,
                "No audio output - scan sounds will be silent", false);
    }

    /** Starts the built-in, local camera engine (one JVM, no second EXE). */
    private CheckResult checkBackend() {
        try {
            if (dev.parent.config.AppConfig.get().cameraAlwaysOn()) {
                dev.parent.scanner.CameraScanService.get().start();
            }
            return new CheckResult(NAMES[4], CheckStatus.OK,
                    "Camera engine ready - everything runs inside this one program", false);
        } catch (Throwable t) {
            return new CheckResult(NAMES[4], CheckStatus.WARN,
                    "Camera engine problem: " + t.getMessage()
                            + " - USB/serial scanners still work", false);
        }
    }

    private CheckResult checkCamera() {
        try {
            boolean present = dev.parent.scanner.CameraScanService.probeAny();
            if (present) {
                return new CheckResult(NAMES[5], CheckStatus.OK,
                        "Camera detected - built-in video engine"
                                + " - always-on decoding, no button to press", false);
            }
            return new CheckResult(NAMES[5], CheckStatus.WARN,
                    "No camera found - you can still use a USB or serial barcode scanner", false);
        } catch (Throwable t) {
            return new CheckResult(NAMES[5], CheckStatus.WARN,
                    "Camera probe failed (" + abbreviate(t.getMessage())
                            + ") - USB/serial scanners still work", false);
        }
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "unknown";
        }
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 180 ? s.substring(0, 180) + "..." : s;
    }

    private CheckResult checkScanner() {
        try {
            ScannerService.startSerialReader();
            return new CheckResult(NAMES[5], CheckStatus.OK, ScannerService.describeSetup(), false);
        } catch (Throwable t) {
            return new CheckResult(NAMES[5], CheckStatus.WARN,
                    "Scanner driver warning: " + t.getMessage(), false);
        }
    }

    private CheckResult checkSupabase(AppConfig cfg) {
        if (!cfg.hasSupabase()) {
            return new CheckResult(NAMES[6], CheckStatus.WARN,
                    "Not configured yet - the setup screen will ask for the URL and API key", false);
        }
        try {
            SupabaseClient sc = new SupabaseClient(cfg.supabaseUrl(), cfg.supabaseKey());
            int v = sc.ping();
            return new CheckResult(NAMES[6], CheckStatus.OK,
                    "Connected to " + sc.baseUrl() + " (database schema v" + v + ")", false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CheckResult(NAMES[6], CheckStatus.FAIL, "Interrupted", false);
        } catch (SupabaseClient.ApiException e) {
            return new CheckResult(NAMES[6], CheckStatus.FAIL, e.getMessage(), false);
        } catch (Throwable t) {
            return new CheckResult(NAMES[6], CheckStatus.FAIL,
                    "Cannot reach Supabase (offline mode is available): " + t.getMessage(), false);
        }
    }

    // ------------------------------------------------------------------- misc

    private void setStep(int index, CheckStatus status, String detail) {
        String icon = switch (status) {
            case RUNNING -> ">>";
            case OK -> "OK";
            case WARN -> "WARN";
            case FAIL -> "FAIL";
        };
        String color = switch (status) {
            case RUNNING -> "#4FC3F7";
            case OK -> "#66BB6A";
            case WARN -> "#FFB300";
            case FAIL -> "#EF5350";
        };
        Platform.runLater(() -> {
            statusIcons.get(index).setText(icon);
            statusIcons.get(index).setStyle("-fx-text-fill:" + color
                    + "; -fx-font-weight:bold; -fx-min-width: 44px;");
            detailLabels.get(index).setText(detail);
            overall.setProgress((index + 0.5) / NAMES.length);
            headline.setText(NAMES[index] + "...");
        });
    }

    private void setStep(int index, CheckResult r) {
        setStep(index, r.status(), r.detail());
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
