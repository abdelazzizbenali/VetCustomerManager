package dev.parent.scanner;

import com.fazecast.jSerialComm.SerialPort;
import dev.parent.config.AppConfig;
import dev.parent.config.Log;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Barcode / QR-code scanner "driver" for the app. Two device flavours are
 * supported and can be combined freely:
 *
 * 1. HID keyboard-wedge scanners (the common ~$20 USB scanners).
 *    They act like a super-fast keyboard: digits arrive within milliseconds
 *    of each other and end with ENTER. This service hooks the JavaFX scene,
 *    detects those bursts globally (no text field needs focus) and converts
 *    them into scan events. No native driver is needed - plug & play.
 *
 * 2. Serial (COM port) scanners. Configured in Settings; the port is read
 *    on a background thread and every line received becomes a scan event.
 *
 * Camera-based scanning lives in {@link CameraScanDialog} (ZXing).
 * All events are delivered on the JavaFX application thread.
 */
public final class ScannerService {

    @FunctionalInterface
    public interface ScanListener {
        void onScan(String code);
    }

    private static final List<ScanListener> LISTENERS = new CopyOnWriteArrayList<>();

    /** When set (e.g. a modal dialog waiting for one scan), only this listener receives codes. */
    private static volatile ScanListener exclusiveCapture;

    // HID burst detection state
    private static final StringBuilder buffer = new StringBuilder();
    private static long lastKeyNanos;
    private static boolean burstLikelyScanner;

    // serial state
    private static ScheduledExecutorService serialExecutor;
    private static volatile boolean serialRunning;
    private static String serialPortWanted = "";
    private static int serialBaudWanted;

    private ScannerService() {
    }

    // ------------------------------------------------------------------ events

    public static void addListener(ScanListener listener) {
        LISTENERS.add(listener);
    }

    public static void removeListener(ScanListener listener) {
        LISTENERS.remove(listener);
    }

    /**
     * The given listener takes over ALL scan delivery until
     * {@link #endExclusiveCapture} is called (used by dialogs that ask
     * "the next scan is mine" - prevents the main window from also reacting).
     */
    public static void beginExclusiveCapture(ScanListener listener) {
        exclusiveCapture = listener;
    }

    public static void endExclusiveCapture(ScanListener listener) {
        if (exclusiveCapture == listener) {
            exclusiveCapture = null;
        }
    }

    static void emit(String code) {
        if (code == null || code.isBlank()) {
            return;
        }
        Platform.runLater(() -> {
            ScanListener exclusive = exclusiveCapture;
            if (exclusive != null) {
                try {
                    exclusive.onScan(code);
                } catch (Throwable t) {
                    Log.error("Scan listener failed", t);
                }
                return;
            }
            for (ScanListener l : LISTENERS) {
                try {
                    l.onScan(code);
                } catch (Throwable t) {
                    Log.error("Scan listener failed", t);
                }
            }
        });
    }

    // ---------------------------------------------------------------- HID mode

    /**
     * Hooks keyboard-wedge detection on a scene. Fast bursts of characters
     * ending with ENTER are treated as scans; slow (human) typing is ignored.
     */
    public static void attachHidCapture(Scene scene) {
        AppConfig cfg = AppConfig.get();

        scene.addEventFilter(KeyEvent.KEY_TYPED, e -> {
            if (!cfg.hidEnabled()) {
                return;
            }
            // When the user (or the scanner itself) is typing into a text field
            // we let the characters land there naturally.
            if (e.getTarget() instanceof TextInputControl) {
                reset();
                return;
            }
            String ch = e.getCharacter();
            if (ch == null || ch.isEmpty() || "\r\n".contains(ch)) {
                return;
            }
            long now = System.nanoTime();
            long gapMs = (now - lastKeyNanos) / 1_000_000;
            lastKeyNanos = now;
            if (gapMs > cfg.hidMaxGapMs() && buffer.length() < cfg.hidMinLength()) {
                reset();
            }
            if (buffer.length() >= cfg.hidMinLength() && gapMs <= cfg.hidMaxGapMs()) {
                burstLikelyScanner = true;
            }
            for (char c : ch.toCharArray()) {
                if (Character.isLetterOrDigit(c) || "-_.$#/%+".indexOf(c) >= 0) {
                    buffer.append(c);
                }
            }
            if (burstLikelyScanner) {
                e.consume(); // keep table widgets from reacting to scanner speed typing
            }
        });

        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (!cfg.hidEnabled() || e.getCode() != KeyCode.ENTER) {
                return;
            }
            if (e.getTarget() instanceof TextInputControl) {
                reset();
                return;
            }
            long gapMs = (System.nanoTime() - lastKeyNanos) / 1_000_000;
            if (buffer.length() >= cfg.hidMinLength()
                    && burstLikelyScanner && gapMs <= cfg.hidMaxGapMs() * 3L) {
                String code = buffer.toString();
                reset();
                e.consume();
                Log.info("HID scan: " + code);
                emit(code);
            } else {
                reset();
            }
        });
    }

    private static void reset() {
        buffer.setLength(0);
        burstLikelyScanner = false;
        lastKeyNanos = 0;
    }

    // ------------------------------------------------------------- serial mode

    /** Available COM ports (empty when the serial library cannot enumerate). */
    public static List<String> availableSerialPorts() {
        try {
            return Arrays.stream(SerialPort.getCommPorts())
                    .map(SerialPort::getSystemPortName)
                    .sorted()
                    .toList();
        } catch (Throwable t) {
            Log.warn("Cannot list serial ports: " + t.getMessage());
            return List.of();
        }
    }

    /** Starts reading the configured serial scanner (no-op when not configured). */
    public static synchronized void startSerialReader() {
        stopSerialReader();
        AppConfig cfg = AppConfig.get();
        serialPortWanted = cfg.serialPort();
        serialBaudWanted = cfg.serialBaud();
        if (serialPortWanted.isEmpty()) {
            return;
        }
        serialRunning = true;
        serialExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "vetms-serial-scanner");
            t.setDaemon(true);
            return t;
        });
        serialExecutor.scheduleWithFixedDelay(ScannerService::serialLoop, 0, 5, TimeUnit.SECONDS);
        Log.info("Serial scanner reader starting on " + serialPortWanted + " @ " + serialBaudWanted);
    }

    public static synchronized void stopSerialReader() {
        serialRunning = false;
        if (serialExecutor != null) {
            serialExecutor.shutdownNow();
            serialExecutor = null;
        }
    }

    /** One iteration: open the port, read lines until unplugged, then the scheduler retries. */
    private static void serialLoop() {
        if (!serialRunning) {
            return;
        }
        SerialPort port;
        try {
            port = SerialPort.getCommPort(serialPortWanted);
            port.setBaudRate(serialBaudWanted);
            port.setNumDataBits(8);
            port.setNumStopBits(SerialPort.ONE_STOP_BIT);
            port.setParity(SerialPort.NO_PARITY);
            port.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING, 1000, 0);
            if (!port.openPort()) {
                Log.warn("Serial scanner port " + serialPortWanted + " is busy or missing");
                return;
            }
        } catch (Throwable t) {
            Log.warn("Serial scanner open failed: " + t.getMessage());
            return;
        }
        Log.info("Serial scanner reading on " + serialPortWanted);
        StringBuilder line = new StringBuilder();
        byte[] chunk = new byte[256];
        try {
            while (serialRunning && port.isOpen()) {
                int n = port.readBytes(chunk, chunk.length);
                if (n <= 0) {
                    continue;
                }
                String s = new String(chunk, 0, n, StandardCharsets.US_ASCII);
                for (char ch : s.toCharArray()) {
                    if (ch == '\r' || ch == '\n') {
                        String code = line.toString().trim();
                        line.setLength(0);
                        if (!code.isEmpty()) {
                            Log.info("Serial scan: " + code);
                            emit(code);
                        }
                    } else {
                        line.append(ch);
                    }
                }
            }
        } catch (Throwable t) {
            Log.warn("Serial scanner read ended: " + t.getMessage());
        } finally {
            try {
                port.closePort();
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ status

    /** One-line human description used by the startup checks. */
    public static String describeSetup() {
        StringBuilder sb = new StringBuilder();
        sb.append(AppConfig.get().hidEnabled() ? "USB scanner (plug & play) ready" : "USB scanner disabled");
        String port = AppConfig.get().serialPort();
        if (!port.isEmpty()) {
            sb.append(" + serial ").append(port);
        }
        if (Boolean.getBoolean("vetms.dev")) {
            sb.append(" [dev]");
        }
        return sb.toString();
    }
}
