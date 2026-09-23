package dev.parent.scanner;

import dev.parent.config.AppConfig;
import dev.parent.config.Log;

import java.awt.image.BufferedImage;
import java.lang.reflect.Method;

/**
 * Premium decode path running INSIDE the client: Dynamsoft Barcode Reader,
 * loaded via REFLECTION so the app builds and runs perfectly without it.
 * Activates when the dbr jar is on the classpath (bundled by the installer)
 * and a license key exists - the owner's key is the built-in default.
 *
 * <p>Two jar generations are probed, in order:
 * {@code com.dynamsoft.dbr.BarcodeReader} (the classic 9.6.x jar the build
 * bundles) then {@code com.dynamsoft.barcode.BarcodeReader} (the 10.x+
 * bundle artifact). A wrong/expired key can never break the free engine.</p>
 */
public final class DynamsoftLocal {

    private static volatile Object reader;
    private static volatile Method decodeMethod;
    private static volatile boolean failed;
    private static volatile String licenseUsed = "";
    private static volatile String lastError = "";

    private DynamsoftLocal() {
    }

    /** The precise reason the engine is not running (for user-facing diagnostics). */
    public static String lastError() {
        return lastError;
    }

    /** True when a license is configured and the engine could be initialized. */
    public static boolean isAvailable() {
        if (failed) {
            return false;
        }
        String key = AppConfig.get().dynamsoftLicense();
        if (key.isEmpty()) {
            return false;
        }
        Object r = reader;
        if (r != null && key.equals(licenseUsed)) {
            return true;
        }
        synchronized (DynamsoftLocal.class) {
            if (reader != null && key.equals(licenseUsed)) {
                return true;
            }
            try {
                Class<?> cls = loadReaderClass();
                cls.getMethod("initLicense", String.class).invoke(null, key);
                Object instance = cls.getMethod("getInstance").invoke(null);
                Method decode = cls.getMethod("decodeBufferedImage",
                        BufferedImage.class, String.class);
                reader = instance;
                decodeMethod = decode;
                licenseUsed = key;
                lastError = "";
                Log.info("Dynamsoft barcode engine ready (premium decoding ON)");
                return true;
            } catch (Throwable t) {
                reader = null;
                decodeMethod = null;
                failed = true; // retry only via reinit()
                lastError = explain(t);
                Log.warn("Dynamsoft engine unavailable: " + lastError
                        + " - falling back to the built-in decoder");
                return false;
            }
        }
    }

    /** Resolves the BarcodeReader class from either jar generation. */
    private static Class<?> loadReaderClass() throws ClassNotFoundException {
        for (String name : new String[]{
                "com.dynamsoft.dbr.BarcodeReader",       // 9.6.x classic jar (bundled)
                "com.dynamsoft.barcode.BarcodeReader"}) { // 10.x+ bundle
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException next) {
                // try the other generation
            }
        }
        throw new ClassNotFoundException("neither com.dynamsoft.dbr.BarcodeReader "
                + "nor com.dynamsoft.barcode.BarcodeReader is on the classpath");
    }

    /** Turns the messy reflection error into a sentence the user can act on. */
    private static String explain(Throwable t) {
        Throwable cause = t instanceof java.lang.reflect.InvocationTargetException ite
                && ite.getCause() != null ? ite.getCause() : t;
        String msg = cause.getMessage() == null ? cause.toString() : cause.getMessage();
        if (cause instanceof ClassNotFoundException) {
            return "no Dynamsoft library is on the classpath (looked for both the "
                    + "9.6 dbr jar and the 10.x bundle) - restart the app once after "
                    + "installing; if it persists, rebuild the installer.";
        }
        if (cause instanceof UnsatisfiedLinkError || cause instanceof NoClassDefFoundError) {
            return "the Dynamsoft jar is present but its native engine could not load: "
                    + msg + " - restart the PC once, or reinstall so the jar can unpack "
                    + "its native files.";
        }
        String lower = msg.toLowerCase();
        if (lower.contains("license")) {
            if (lower.contains("version") || lower.contains("edition") || lower.contains("match")) {
                return "the key does NOT fit this Dynamsoft engine version: " + msg
                        + " - grab a fresh 30-day trial key from dynamsoft.com (free) and "
                        + "paste it in Settings; premium unlocks instantly.";
            }
            return "Dynamsoft rejected the key: " + msg;
        }
        return msg;
    }

    /** Call after the license key changed: allows a fresh attempt. */
    public static void reinit() {
        synchronized (DynamsoftLocal.class) {
            failed = false;
            licenseUsed = "";
            reader = null;
            decodeMethod = null;
        }
        isAvailable();
    }

    /** Returns the first decoded barcode text, or null. Never throws. */
    public static String decode(BufferedImage image) {
        Object r = reader;
        Method decode = decodeMethod;
        if (r == null || decode == null) {
            return null;
        }
        try {
            Object results = decode.invoke(r, image, "");
            if (results instanceof Object[] items) {
                for (Object item : items) {
                    if (item == null) {
                        continue;
                    }
                    Object text = item.getClass().getField("barcodeText").get(item);
                    if (text instanceof String s && !s.isBlank()) {
                        return s.trim();
                    }
                }
            }
        } catch (Throwable t) {
            Log.warn("Dynamsoft decode error (using fallback this frame): " + t.getMessage());
        }
        return null;
    }
}
