package dev.parent.net;

import dev.parent.config.AppConfig;
import dev.parent.scanner.CameraScanService;
import dev.parent.ui.MainWindow;
import javafx.application.Platform;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Client-side scan routing. Barcode events now arrive from the LOCAL camera
 * service ({@link CameraScanService}, same process as the UI) and are routed
 * by the view currently on screen:
 *
 * <pre>
 *   capture dialog open  -> delivered only to that dialog
 *   Daily Usage          -> straight into the open cart
 *   Medicines            -> fills the barcode field / search
 *   anywhere else        -> classic POS fallback (window handles it)
 * </pre>
 * The camera keeps running the whole time; USB/serial scanners ride the same
 * consumers through {@link dev.parent.scanner.ScannerService} as always.
 */
public final class ScanRouter {

    @FunctionalInterface
    public interface StatusListener {
        void onStatus(boolean running, String message);
    }

    private static ScanRouter instance;

    public static ScanRouter get() {
        if (instance == null) {
            instance = new ScanRouter();
        }
        return instance;
    }

    private final List<StatusListener> statusListeners = new CopyOnWriteArrayList<>();

    private volatile Consumer<String> captureConsumer;
    private volatile String currentContext = "daily";

    private final CameraScanService.CodeListener codeListener = (code, engine) ->
            Platform.runLater(() -> route(code));

    private final CameraScanService.StatusListener cameraStatusListener = this::notifyStatus;

    private ScanRouter() {
    }

    // ------------------------------------------------------------- lifecycle

    /** Start the local camera pipeline (if enabled) and subscribe to scan events. */
    public synchronized void start() {
        CameraScanService cam = CameraScanService.get();
        cam.removeCodeListener(codeListener);
        cam.addCodeListener(codeListener);
        cam.removeStatusListener(cameraStatusListener);
        cam.addStatusListener(cameraStatusListener);
        if (AppConfig.get().cameraAlwaysOn()) {
            Thread.ofVirtual().name("vetms-camera-start").start(cam::start);
        }
        MainWindow w = MainWindow.get();
        postContext(w == null ? "daily" : w.currentViewKey());
    }

    public synchronized void stop() {
        try {
            CameraScanService cam = CameraScanService.get();
            cam.removeCodeListener(codeListener);
            cam.stop(); // the window is closing: the camera must close with it
        } catch (Throwable ignored) {
        }
    }

    public void addStatusListener(StatusListener listener) {
        statusListeners.add(listener);
    }

    /** Emit the current local engine status so a fresh window shows it immediately. */
    public void refreshStatus() {
        Thread.ofVirtual().name("vetms-camera-status").start(() -> {
            CameraScanService cam = CameraScanService.get();
            notifyStatus(cam.running(), cam.statusText());
        });
    }

    private void notifyStatus(boolean running, String message) {
        Platform.runLater(() -> {
            for (StatusListener l : statusListeners) {
                try {
                    l.onStatus(running, message);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    // --------------------------------------------------------------- context

    /** Called by MainWindow whenever the active view changes. */
    public void postContext(String viewKey) {
        if (viewKey == null || viewKey.isBlank()) {
            viewKey = "other";
        }
        currentContext = viewKey;
    }

    // ------------------------------------------------------- capture dialogs

    /**
     * The next camera scan goes ONLY to this consumer (modal "assign barcode
     * to the product" dialogs). Companion to the HID exclusive capture.
     */
    public synchronized void beginCapture(Consumer<String> consumer) {
        captureConsumer = consumer;
        postContext("capture");
    }

    public synchronized void endCapture(Consumer<String> consumer) {
        if (captureConsumer == consumer) {
            captureConsumer = null;
        }
        MainWindow w = MainWindow.get();
        postContext(w == null ? "daily" : w.currentViewKey());
    }

    // --------------------------------------------------------------- routing

    private void route(String code) {
        Consumer<String> capture = captureConsumer;
        if ("capture".equals(currentContext) && capture != null) {
            capture.accept(code);
            return;
        }
        switch (currentContext) {
            case "daily" -> forward("daily", code);
            case "medicines" -> forward("medicines", code);
            default -> fallback(code, capture);
        }
    }

    /** The code belongs to a specific view; it must exist (created lazily). */
    private void forward(String viewKey, String code) {
        MainWindow w = MainWindow.get();
        if (w == null) {
            return;
        }
        if (!viewKey.equals(w.currentViewKey())) {
            w.show(viewKey);
        }
        w.forwardScanToCurrentView(code);
    }

    private void fallback(String code, Consumer<String> capture) {
        MainWindow w = MainWindow.get();
        if (w == null) {
            return;
        }
        if (capture != null) {
            capture.accept(code);
            return;
        }
        w.handleExternalScan(code);
    }
}
