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
    public static final String CAMERA_PREVIEW = "scanner.camera.preview";
    public static final String DYNAMSOFT_LICENSE = "scanner.dynamsoft.license";

    // Camera Lab — low-quality webcam tuning (Gemini tips: contrast, ROI, TRY_HARDER, downscale)
    public static final String CAMERA_FILTER_BRIGHTNESS = "camera.filter.brightness";
    public static final String CAMERA_FILTER_CONTRAST = "camera.filter.contrast";
    public static final String CAMERA_FILTER_SATURATION = "camera.filter.saturation";
    public static final String CAMERA_FILTER_SHARPNESS = "camera.filter.sharpness";
    public static final String CAMERA_FILTER_GRAYSCALE = "camera.filter.grayscale";
    public static final String CAMERA_FILTER_INVERT = "camera.filter.invert";
    public static final String CAMERA_FILTER_ROI_ENABLED = "camera.filter.roi.enabled";
    public static final String CAMERA_FILTER_ROI_SIZE = "camera.filter.roi.size";
    public static final String CAMERA_FILTER_TRY_HARDER = "camera.filter.tryHarder";
    public static final String CAMERA_FILTER_DOWNSCALE = "camera.filter.downscale";
    public static final String CAMERA_FILTER_FORMATS = "camera.filter.formats";

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
        props.setProperty(CAMERA_PREVIEW, "true");
        // Camera Lab defaults — conservative for accuracy (no downscale/TRY_HARDER unless user enables)
        props.setProperty(CAMERA_FILTER_BRIGHTNESS, "0");
        props.setProperty(CAMERA_FILTER_CONTRAST, "100");
        props.setProperty(CAMERA_FILTER_SATURATION, "100");
        props.setProperty(CAMERA_FILTER_SHARPNESS, "0");
        props.setProperty(CAMERA_FILTER_GRAYSCALE, "false");
        props.setProperty(CAMERA_FILTER_INVERT, "false");
        props.setProperty(CAMERA_FILTER_ROI_ENABLED, "false");
        props.setProperty(CAMERA_FILTER_ROI_SIZE, "75");
        props.setProperty(CAMERA_FILTER_TRY_HARDER, "false");
        props.setProperty(CAMERA_FILTER_DOWNSCALE, "false");
        props.setProperty(CAMERA_FILTER_FORMATS, "CODE_128,CODE_39,EAN_13,EAN_8,UPC_A,UPC_E,QR_CODE");
        // owner's premium barcode license (built-in default; Settings can override)
        props.setProperty(DYNAMSOFT_LICENSE,
                "t0087YQEAAGL0FN6YdrvxUDrNFmM9hzMQUt4IsGDSrJ4KXCJNPhJGNBTl62CUjEYunLTqG5stfWf9bBq8Cwo7OBU7QMR9TrEz/GD6R5O7GXhvljFJRXRZUUpZ");
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

    /** True when the online database is configured. */
    public synchronized boolean hasAnyDatabase() {
        return hasSupabase();
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

    /** Camera provider handled by the backend: {@code auto} | {@code sidecar} | {@code java}. */
    public synchronized String cameraProvider() {
        return props.getProperty(CAMERA_PROVIDER, "auto").trim();
    }

    public synchronized void setCameraProvider(String provider) {
        props.setProperty(CAMERA_PROVIDER, provider == null ? "auto" : provider.trim());
    }

    /** Show the live camera preview box on Daily Usage (default on). */
    public synchronized boolean cameraPreviewVisible() {
        return Boolean.parseBoolean(props.getProperty(CAMERA_PREVIEW, "true"));
    }

    public synchronized void setCameraPreviewVisible(boolean v) {
        props.setProperty(CAMERA_PREVIEW, Boolean.toString(v));
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

    // ---- Camera Lab filters --------------------------------------------------

    public synchronized int cameraFilterBrightness() { return getInt(CAMERA_FILTER_BRIGHTNESS, 0); }
    public synchronized void setCameraFilterBrightness(int v) { props.setProperty(CAMERA_FILTER_BRIGHTNESS, Integer.toString(Math.max(-100, Math.min(100, v)))); }

    public synchronized int cameraFilterContrast() { return getInt(CAMERA_FILTER_CONTRAST, 100); }
    public synchronized void setCameraFilterContrast(int v) { props.setProperty(CAMERA_FILTER_CONTRAST, Integer.toString(Math.max(50, Math.min(250, v)))); }

    public synchronized int cameraFilterSaturation() { return getInt(CAMERA_FILTER_SATURATION, 100); }
    public synchronized void setCameraFilterSaturation(int v) { props.setProperty(CAMERA_FILTER_SATURATION, Integer.toString(Math.max(0, Math.min(200, v)))); }

    public synchronized int cameraFilterSharpness() { return getInt(CAMERA_FILTER_SHARPNESS, 0); }
    public synchronized void setCameraFilterSharpness(int v) { props.setProperty(CAMERA_FILTER_SHARPNESS, Integer.toString(Math.max(0, Math.min(200, v)))); }

    public synchronized boolean cameraFilterGrayscale() { return getBool(CAMERA_FILTER_GRAYSCALE); }
    public synchronized void setCameraFilterGrayscale(boolean v) { props.setProperty(CAMERA_FILTER_GRAYSCALE, Boolean.toString(v)); }

    public synchronized boolean cameraFilterInvert() { return getBool(CAMERA_FILTER_INVERT); }
    public synchronized void setCameraFilterInvert(boolean v) { props.setProperty(CAMERA_FILTER_INVERT, Boolean.toString(v)); }

    public synchronized boolean cameraFilterRoiEnabled() { return getBool(CAMERA_FILTER_ROI_ENABLED); }
    public synchronized void setCameraFilterRoiEnabled(boolean v) { props.setProperty(CAMERA_FILTER_ROI_ENABLED, Boolean.toString(v)); }

    public synchronized int cameraFilterRoiSize() { return getInt(CAMERA_FILTER_ROI_SIZE, 75); }
    public synchronized void setCameraFilterRoiSize(int v) { props.setProperty(CAMERA_FILTER_ROI_SIZE, Integer.toString(Math.max(40, Math.min(95, v)))); }

    public synchronized boolean cameraFilterTryHarder() { return Boolean.parseBoolean(props.getProperty(CAMERA_FILTER_TRY_HARDER, "true")); }
    public synchronized void setCameraFilterTryHarder(boolean v) { props.setProperty(CAMERA_FILTER_TRY_HARDER, Boolean.toString(v)); }

    public synchronized boolean cameraFilterDownscale() { return Boolean.parseBoolean(props.getProperty(CAMERA_FILTER_DOWNSCALE, "true")); }
    public synchronized void setCameraFilterDownscale(boolean v) { props.setProperty(CAMERA_FILTER_DOWNSCALE, Boolean.toString(v)); }

    public synchronized String cameraFilterFormats() { return props.getProperty(CAMERA_FILTER_FORMATS, "CODE_128,CODE_39,EAN_13,EAN_8,UPC_A,UPC_E,QR_CODE").trim(); }
    public synchronized void setCameraFilterFormats(String s) { props.setProperty(CAMERA_FILTER_FORMATS, s==null?"":s.trim()); }

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
