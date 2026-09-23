package dev.parent;

import dev.parent.config.AppConfig;
import dev.parent.config.Log;
import dev.parent.db.LocalDatabase;
import dev.parent.db.SupabaseClient;
import dev.parent.db.SyncService;
import dev.parent.net.ScanRouter;
import dev.parent.scanner.ScannerService;
import dev.parent.ui.MainWindow;
import dev.parent.ui.SetupWizard;
import dev.parent.ui.SplashScreen;
import dev.parent.util.FxUtil;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.stage.Stage;

/**
 * Application entry point.
 *
 * Startup flow:
 *   splash screen (component checks)
 *   -> if first launch:   ask the Supabase Project URL + API key
 *   -> if DB unreachable: offer Work offline / Retry / Reconfigure
 *   -> start the sync service and open the main window
 */
public class VetApp extends Application {

    public static final String VERSION = "4.0.0";

    public static void main(String[] args) {
        launch(VetApp.class, args);
    }

    @Override
    public void start(Stage primaryStage) {
        Thread.currentThread().setUncaughtExceptionHandler((t, e) -> FxUtil.fatal(e));
        Platform.setImplicitExit(true);

        primaryStage.hide();
        Stage splashStage = new Stage();
        FxUtil.setAppIcon(splashStage);
        SplashScreen splash = new SplashScreen(splashStage);
        splash.show();
        splash.runChecks(report -> Platform.runLater(
                () -> continueStartup(primaryStage, splash, splashStage, report)));
    }

    // --------------------------------------------------------------------

    private void continueStartup(Stage primaryStage, SplashScreen splash,
                                 Stage splashStage, SplashScreen.Report report) {
        if (report.criticalFailure()) {
            SplashScreen.CheckResult bad = report.results().stream()
                    .filter(r -> r.status() == SplashScreen.CheckStatus.FAIL)
                    .findFirst().orElse(null);
            FxUtil.error(splashStage, "Cannot start",
                    "A required component failed:\n\n"
                            + (bad == null ? "unknown" : bad.detail())
                            + "\n\nPlease fix the problem and start the program again.");
            Platform.exit();
            return;
        }

        AppConfig cfg = AppConfig.get();
        if (!cfg.hasSupabase() && !cfg.hasRemoteServer()) {
            boolean saved = new SetupWizard().showAndWait(splashStage);
            if (!saved || (!cfg.hasSupabase() && !cfg.hasRemoteServer())) {
                Log.info("Setup cancelled: shutting down");
                splashStage.close();
                Platform.exit();
                return;
            }
        } else if (!report.supabaseOnline()) {
            handleOffline(primaryStage, splash, splashStage, 0);
            return;
        }
        openMain(primaryStage, splash);
    }

    private void handleOffline(Stage primaryStage, SplashScreen splash,
                               Stage splashStage, int attempt) {
        ButtonType workOffline = new ButtonType("Work offline", ButtonBar.ButtonData.LEFT);
        ButtonType retry = new ButtonType("Retry connection", ButtonBar.ButtonData.APPLY);
        ButtonType reconfigure = new ButtonType("Database settings...", ButtonBar.ButtonData.OTHER);
        ButtonType quit = new ButtonType("Quit", ButtonBar.ButtonData.CANCEL_CLOSE);

        Alert alert = new Alert(Alert.AlertType.WARNING,
                "The program cannot reach your online database right now.\n\n"
                        + "- Work offline: use the local copy; everything syncs back automatically\n"
                        + "  when the connection returns (sales, clients, animals: nothing is lost)\n"
                        + "- Retry: check the connection again\n"
                        + "- Database settings: fix the URL / API key",
                workOffline, retry, reconfigure, quit);
        alert.setTitle("Online database unreachable");
        alert.setHeaderText("No connection to Supabase");
        DialogPane pane = alert.getDialogPane();
        var css = getClass().getResource("/dev/parent/res/theme.css");
        if (css != null) {
            pane.getStylesheets().add(css.toExternalForm());
        }
        alert.initOwner(splashStage);
        ButtonType choice = alert.showAndWait().orElse(quit);

        if (choice == quit) {
            splashStage.close();
            Platform.exit();
        } else if (choice == workOffline) {
            openMain(primaryStage, splash); // sync retries in the background
        } else if (choice == reconfigure) {
            new SetupWizard().showAndWait(splashStage);
            retryConnection(primaryStage, splash, splashStage, attempt + 1);
        } else {
            retryConnection(primaryStage, splash, splashStage, attempt + 1);
        }
    }

    private void retryConnection(Stage primaryStage, SplashScreen splash,
                                 Stage splashStage, int attempt) {
        AppConfig cfg = AppConfig.get();
        Thread probe = new Thread(() -> {
            boolean online = false;
            try {
                new SupabaseClient(cfg.supabaseUrl(), cfg.supabaseKey()).ping();
                online = true;
            } catch (Exception ignored) {
            }
            boolean ok = online;
            Platform.runLater(() -> {
                if (ok) {
                    openMain(primaryStage, splash);
                } else {
                    handleOffline(primaryStage, splash, splashStage, attempt);
                }
            });
        }, "vetms-retry");
        probe.setDaemon(true);
        probe.start();
    }

    private void openMain(Stage primaryStage, SplashScreen splash) {
        AppConfig cfg = AppConfig.get();
        if (cfg.hasSupabase() || cfg.hasRemoteServer()) {
            // remote PCs need NO Supabase credentials: every REST call is
            // relayed through the clinic server automatically.
            SyncService.configure(new SupabaseClient(cfg.supabaseUrl(), cfg.supabaseKey()));
        }
        SyncService.start();
        ScannerService.startSerialReader();
        ScanRouter.get().start(); // subscribe to the backend's always-on scan pipeline

        new MainWindow(primaryStage);
        primaryStage.setOnCloseRequest(e -> shutdown());
        splash.close();
        Log.info("VetCustomerManager " + VERSION + " started");
    }

    private void shutdown() {
        // Everything closes together - one process, one camera, no orphans.
        // ScanRouter.stop() stops the camera engine, so the device is free
        // the instant the window closes.
        try {
            ScanRouter.get().stop();
        } catch (Throwable ignored) {
        }
        try {
            SyncService.stop();
        } catch (Throwable ignored) {
        }
        try {
            ScannerService.stopSerialReader();
        } catch (Throwable ignored) {
        }
        try {
            LocalDatabase.close();
        } catch (Throwable ignored) {
        }
    }
}
