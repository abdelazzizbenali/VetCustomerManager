package dev.parent.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Properties;

/**
 * Typed accessor around the persisted {@code config.properties} file.
 * The Supabase URL + anon API key are asked from the user on the first
 * launch and saved here (per-user data directory, never inside the program).
 */
public final class AppConfig {

    private static final AppConfig INSTANCE = new AppConfig();

    // ---- keys -------------------------------------------------------------
    public static final String SUPABASE_URL = "supabase.url";
    public static final String SUPABASE_KEY = "supabase.apikey";

    public static final String SYNC_INTERVAL_SEC = "sync.interval.sec";

    public static final String SOUND_ENABLED = "scanner.sounds.enabled";
    public static final String HID_ENABLED = "scanner.hid.enabled";
    public static final String HID_MAX_GAP_MS = "scanner.hid.maxgap.ms";
    public static final String HID_MIN_LENGTH = "scanner.hid.minlength";
    public static final String SERIAL_PORT = "scanner.serial.port";
    public static final String SERIAL_BAUD = "scanner.serial.baud";
    public static final String CAMERA_SERVICE_ENABLED = "scanner.camera.service.enabled";
    public static final String CAMERA_ALWAYS_ON = "scanner.camera.alwayson";
    public static final String CAMERA_PROVIDER = "scanner.camera.provider";
    public static final String DYNAMSOFT_LICENSE = "scanner.dynamsoft.license";

    public static final String NOTIFY_EXPIRY_DAYS = "notify.expiry.days";

    private final Properties props = new Properties();

    private AppConfig() {
        // sensible defaults
        props.setProperty(SYNC_INTERVAL_SEC, "30");
        props.setProperty(SOUND_ENABLED, "true");
        props.setProperty(HID_ENABLED, "true");
        props.setProperty(HID_MAX_GAP_MS, "100");
        props.setProperty(HID_MIN_LENGTH, "4");
        props.setProperty(SERIAL_PORT, "");
        props.setProperty(SERIAL_BAUD, "9600");
        props.setProperty(CAMERA_SERVICE_ENABLED, "false");
        props.setProperty(CAMERA_ALWAYS_ON, "true");
        props.setProperty(CAMERA_PROVIDER, "java");
        props.setProperty(DYNAMSOFT_LICENSE, "");
        props.setProperty(NOTIFY_EXPIRY_DAYS, "90");
    }

    public static AppConfig get() {
        return INSTANCE;
    }

    // ---- loading / saving ---------------------------------------------------

    public synchronized void load() {
        if (Files.exists(AppDirs.configFile())) {
            try (InputStream in = Files.newInputStream(AppDirs.configFile())) {
                props.load(in);
            } catch (IOException e) {
                Log.warn("Could not read config file: " + e.getMessage());
            }
        }
    }

    public synchronized void save() {
        try (OutputStream out = Files.newOutputStream(AppDirs.configFile())) {
            props.store(out, "VetCustomerManager configuration - edit at your own risk");
        } catch (IOException e) {
            Log.warn("Could not save config: " + e.getMessage());
        }
    }

    // ---- Supabase ------------------------------------------------------------

    public synchronized String supabaseUrl() {
        return props.getProperty(SUPABASE_URL, "").trim();
    }

    public synchronized String supabaseKey() {
        return props.getProperty(SUPABASE_KEY, "").trim();
    }

    public synchronized boolean hasSupabase() {
        return !supabaseUrl().isEmpty() && !supabaseKey().isEmpty();
    }

    public synchronized void setSupabase(String url, String key) {
        props.setProperty(SUPABASE_URL, url == null ? "" : url.trim());
        props.setProperty(SUPABASE_KEY, key == null ? "" : key.trim());
    }

    // ---- scanners -------------------------------------------------------------

    public synchronized boolean soundsEnabled() {
        return getBool(SOUND_ENABLED);
    }

    public synchronized void setSoundsEnabled(boolean v) {
        props.setProperty(SOUND_ENABLED, Boolean.toString(v));
    }

    public synchronized boolean hidEnabled() {
        return getBool(HID_ENABLED);
    }

    public synchronized void setHidEnabled(boolean v) {
        props.setProperty(HID_ENABLED, Boolean.toString(v));
    }

    public synchronized int hidMaxGapMs() {
        return getInt(HID_MAX_GAP_MS, 100);
    }

    public synchronized void setHidMaxGapMs(int ms) {
        props.setProperty(HID_MAX_GAP_MS, Integer.toString(Math.max(20, Math.min(1000, ms))));
    }

    public synchronized int hidMinLength() {
        return getInt(HID_MIN_LENGTH, 4);
    }

    public synchronized void setHidMinLength(int len) {
        props.setProperty(HID_MIN_LENGTH, Integer.toString(Math.max(3, len)));
    }

    public synchronized String serialPort() {
        return props.getProperty(SERIAL_PORT, "").trim();
    }

    public synchronized void setSerialPort(String port) {
        props.setProperty(SERIAL_PORT, port == null ? "" : port.trim());
    }

    public synchronized int serialBaud() {
        return getInt(SERIAL_BAUD, 9600);
    }

    public synchronized void setSerialBaud(int baud) {
        props.setProperty(SERIAL_BAUD, Integer.toString(baud));
    }

    public synchronized boolean cameraAlwaysOn() {
        return getBool(CAMERA_ALWAYS_ON);
    }

    public synchronized void setCameraAlwaysOn(boolean v) {
        props.setProperty(CAMERA_ALWAYS_ON, Boolean.toString(v));
    }

    /** Camera provider: {@code auto} | {@code sidecar} | {@code java}. */
    public synchronized String cameraProvider() {
        return props.getProperty(CAMERA_PROVIDER, "auto").trim();
    }

    public synchronized void setCameraProvider(String provider) {
        props.setProperty(CAMERA_PROVIDER, provider == null ? "auto" : provider.trim());
    }

    public synchronized String dynamsoftLicense() {
        return props.getProperty(DYNAMSOFT_LICENSE, "").trim();
    }

    public synchronized void setDynamsoftLicense(String key) {
        props.setProperty(DYNAMSOFT_LICENSE, key == null ? "" : key.trim());
    }

    public synchronized boolean cameraServiceEnabled() {
        return getBool(CAMERA_SERVICE_ENABLED);
    }

    public synchronized void setCameraEnabled(boolean v) {
        props.setProperty(CAMERA_SERVICE_ENABLED, Boolean.toString(v));
    }

    // ---- sync ------------------------------------------------------------------

    public synchronized int syncIntervalSec() {
        return Math.max(10, getInt(SYNC_INTERVAL_SEC, 30));
    }

    public synchronized void setSyncIntervalSec(int seconds) {
        props.setProperty(SYNC_INTERVAL_SEC, Integer.toString(Math.max(10, seconds)));
    }

    public synchronized int notifyExpiryDays() {
        return getInt(NOTIFY_EXPIRY_DAYS, 90);
    }

    // ---- helpers -----------------------------------------------------------------

    private boolean getBool(String key) {
        return Boolean.parseBoolean(props.getProperty(key, "false"));
    }

    private int getInt(String key, int def) {
        try {
            return Integer.parseInt(props.getProperty(key, String.valueOf(def)).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
