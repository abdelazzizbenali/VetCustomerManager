package dev.parent.scanner;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_videoio.VideoCapture;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.ByteArrayInputStream;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.bytedeco.opencv.global.opencv_imgcodecs.imencode;
import static org.bytedeco.opencv.global.opencv_videoio.CAP_DSHOW;
import static org.bytedeco.opencv.global.opencv_videoio.CAP_PROP_FRAME_HEIGHT;
import static org.bytedeco.opencv.global.opencv_videoio.CAP_PROP_FRAME_WIDTH;

/**
 * The built-in camera scanner.
 *
 * <p>Engine: OpenCV's DirectShow backend (org.bytedeco preset bundles) when
 * available, otherwise falls back to webcam-capture (sarxos, pure Java).
 * One loop thread reads frames, JPEG-encodes them for the live preview and
 * hands the same pixels to the decode chain {@code Dynamsoft (licensed) ->
 * ZXing global -> ZXing hybrid}. All decode engines run in-process.</p>
 */
public final class CameraScanService {

    @FunctionalInterface
    public interface StatusListener {
        void onStatus(boolean running, String message);
    }

    @FunctionalInterface
    public interface FrameListener {
        void onFrame(byte[] jpeg);
    }

    @FunctionalInterface
    public interface CodeListener {
        void onCode(String code, String engine);
    }

    private static final long PREVIEW_INTERVAL_MS = 140;
    private static final long SAME_CODE_COOLDOWN_MS = 2500;
    private static final int MAX_CAMERAS_TO_TRY = 3;

    private static volatile boolean nativesOk;
    private static volatile String nativesError = "";
    private static volatile boolean useOpenCv = true; // false -> fallback to webcam-capture
    private static volatile String engineDetail = "OpenCV";

    /** The one-time load of the native libraries (idempotent). */
    public static synchronized boolean ensureNatives() {
        if (nativesOk) {
            return true;
        }
        // First, try OpenCV (bytedeco) – the preferred engine.
        // On some JVMs (especially JDK 25+ without --enable-native-access, or missing openblas natives)
        // the static initializer for opencv_core can fail with:
        //   Could not initialize class org.bytedeco.opencv.global.opencv_core
        //   Could not initialize class org.bytedeco.openblas.global.openblas_nolapack
        // We handle that gracefully and fall back to webcam-capture.
        Throwable firstError = null;
        // Try OpenCV with openblas disabled (lighter, avoids openblas_nolapack on JDK25)
        try {
            if (System.getProperty("org.bytedeco.openblas.load") == null) {
                System.setProperty("org.bytedeco.openblas.load", "none");
            }
        } catch (Throwable ignored) {
        }
        try {
            Loader.load(org.bytedeco.opencv.global.opencv_videoio.class);
            Loader.load(org.bytedeco.opencv.global.opencv_imgcodecs.class);
            try {
                Loader.load(org.bytedeco.opencv.global.opencv_core.class);
            } catch (Throwable ignored) {
            }
            // Verify that Mat class can actually be initialized (triggers static init)
            // If this throws, we will fall back.
            try {
                Class.forName("org.bytedeco.opencv.opencv_core$Mat");
            } catch (Throwable t) {
                throw t;
            }
            nativesOk = true;
            nativesError = "";
            useOpenCv = true;
            engineDetail = "OpenCV";
            return true;
        } catch (Throwable t) {
            firstError = t;
            // Log for diagnostics
            String msg = t.toString();
            if (t.getCause() != null) msg += " -> " + t.getCause().toString();
            System.err.println("[Camera] OpenCV natives failed: " + msg);
        }
        // Fallback: try classic core load with openblas enabled (if natives are actually present)
        try {
            System.clearProperty("org.bytedeco.openblas.load");
            Loader.load(org.bytedeco.opencv.global.opencv_core.class);
            // Verify again
            Class.forName("org.bytedeco.opencv.opencv_core$Mat");
            nativesOk = true;
            nativesError = "";
            useOpenCv = true;
            engineDetail = "OpenCV";
            return true;
        } catch (Throwable t) {
            String msg2 = t.toString();
            if (t.getCause() != null) msg2 += " -> " + t.getCause().toString();
            System.err.println("[Camera] OpenCV fallback load failed: " + msg2);
            // Don't return yet, try webcam-capture fallback
        }
        // Last resort: try webcam-capture (sarxos) – pure Java, no native OpenCV needed.
        try {
            Class<?> webcamClass = Class.forName("com.github.sarxos.webcam.Webcam");
            // Touch the class to ensure it loads
            webcamClass.getMethod("getWebcams");
            nativesOk = true;
            nativesError = "";
            useOpenCv = false;
            engineDetail = "Webcam-Capture (fallback, OpenCV unavailable: " + (firstError != null ? firstError.getMessage() : "unknown") + ")";
            System.out.println("[Camera] Using webcam-capture fallback (OpenCV unavailable)");
            return true;
        } catch (Throwable t) {
            String msg = firstError != null ? firstError.toString() : "Unknown";
            if (t != null) msg += " | Webcam fallback also failed: " + t.toString();
            nativesError = msg;
            // Keep the original OpenCV error as the primary message for the user, but mention fallback
            if (nativesError.contains("opencv_core") || nativesError.contains("openblas_nolapack")) {
                nativesError = "Could not initialize OpenCV natives (" + nativesError + "). "
                        + "This often happens on JDK 25 without --enable-native-access or with a mismatched JavaFX/JDK version. "
                        + "The app will try the fallback camera driver on next start, or please run with Java 21. "
                        + "Raw: " + msg;
            }
            return false;
        }
    }

    public static String nativesError() {
        return nativesError;
    }

    public static String engineDetail() {
        return engineDetail;
    }

    private static CameraScanService instance;

    public static synchronized CameraScanService get() {
        if (instance == null) {
            instance = new CameraScanService();
        }
        return instance;
    }

    private final List<StatusListener> statusListeners = new CopyOnWriteArrayList<>();
    private final List<FrameListener> frameListeners = new CopyOnWriteArrayList<>();
    private final List<CodeListener> codeListeners = new CopyOnWriteArrayList<>();

    private volatile boolean wantRun;
    private volatile boolean running;
    private volatile Thread worker;
    private volatile VideoCapture camera; // OpenCV path
    private volatile Object webcamFallback; // Webcam object for fallback path (avoid hard dep)
    private volatile String statusText = "camera idle";
    private volatile String lastCode = "";
    private volatile long lastCodeAt;
    private volatile String lastEngine = "";

    private CameraScanService() {
    }

    // ------------------------------------------------------------- listeners

    public void addStatusListener(StatusListener l) {
        statusListeners.add(l);
    }

    public void removeStatusListener(StatusListener l) {
        statusListeners.remove(l);
    }

    public void addFrameListener(FrameListener l) {
        frameListeners.add(l);
    }

    public void removeFrameListener(FrameListener l) {
        frameListeners.remove(l);
    }

    public void addCodeListener(CodeListener l) {
        codeListeners.add(l);
    }

    public void removeCodeListener(CodeListener l) {
        codeListeners.remove(l);
    }

    // -------------------------------------------------------------- lifecycle

    /** Starts the pipeline if the user enabled "camera always on". Idempotent. */
    public synchronized void start() {
        if (worker != null) {
            return;
        }
        wantRun = true;
        worker = Thread.ofVirtual().name("vetms-camera").start(this::loop);
    }

    public synchronized void stop() {
        wantRun = false;
        Thread w = worker;
        if (w != null) {
            w.interrupt();
        }
        worker = null;
        closeCamera();
        running = false;
        setStatus("camera stopped");
    }

    public boolean running() {
        return running;
    }

    public String statusText() {
        return statusText;
    }

    public String lastEngine() {
        return lastEngine;
    }

    /** Any camera device visible at all? (splash check) */
    public static boolean probeAny() {
        if (!ensureNatives()) {
            return false;
        }
        if (useOpenCv) {
            for (int i = 0; i < MAX_CAMERAS_TO_TRY; i++) {
                VideoCapture probe = null;
                try {
                    probe = new VideoCapture(i, CAP_DSHOW);
                    if (probe.isOpened()) {
                        probe.release();
                        return true;
                    }
                } catch (Throwable ignored) {
                    // try the next index
                } finally {
                    if (probe != null) {
                        try {
                            probe.release();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
            // OpenCV probe failed, but fallback webcam might still be available
            return probeWebcamFallback();
        } else {
            return probeWebcamFallback();
        }
    }

    private static boolean probeWebcamFallback() {
        try {
            Class<?> webcamClass = Class.forName("com.github.sarxos.webcam.Webcam");
            java.lang.reflect.Method getWebcams = webcamClass.getMethod("getWebcams");
            @SuppressWarnings("unchecked")
            java.util.List<?> webcams = (java.util.List<?>) getWebcams.invoke(null);
            return webcams != null && !webcams.isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ----------------------------------------------------------------- loops

    private void loop() {
        if (!ensureNatives()) {
            setStatus("camera engine missing: " + nativesError);
            cameraCleanupFromInside();
            return;
        }
        if (useOpenCv) {
            loopOpenCv();
        } else {
            loopWebcamFallback();
        }
    }

    private void loopOpenCv() {
        VideoCapture cam = openAnyCameraOpenCv();
        if (cam == null) {
            // Try fallback if OpenCV can't open any camera but webcam-capture might
            if (probeWebcamFallback()) {
                useOpenCv = false;
                engineDetail = "Webcam-Capture (fallback)";
                loopWebcamFallback();
                return;
            }
            setStatus("no camera found - plug one in and press the camera button again");
            cameraCleanupFromInside();
            return;
        }
        this.camera = cam;
        running = true;
        setStatus("camera live (" + engineDetail + ") - hold a barcode in front of it");

        Mat frame = new Mat();
        BytePointer jpeg = new BytePointer();
        long lastPreview = 0;
        long lastEmptyLog = 0;

        try {
            while (wantRun && !Thread.currentThread().isInterrupted()) {
                if (!cam.grab()) {
                    sleepQuietly(30);
                }
                if (!cam.retrieve(frame) || frame.empty()) {
                    long now = System.currentTimeMillis();
                    if (now - lastEmptyLog > 5000) {
                        lastEmptyLog = now;
                        setStatus("camera is on but sending no picture - is another app using it?");
                    }
                    sleepQuietly(60);
                    continue;
                }

                long now = System.currentTimeMillis();
                if (now - lastPreview >= PREVIEW_INTERVAL_MS && !frameListeners.isEmpty()) {
                    lastPreview = now;
                    jpeg = new BytePointer();
                    if (imencode(".jpg", frame, jpeg)) {
                        byte[] bytes = new byte[(int) jpeg.limit()];
                        jpeg.get(bytes);
                        for (FrameListener l : frameListeners) {
                            try {
                                l.onFrame(bytes);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }

                decodeMat(frame);
                sleepQuietly(90);
            }
        } catch (Throwable t) {
            // If OpenCV loop crashes (e.g., native error), try fallback on next start
            String msg = t.getMessage();
            if (msg != null && msg.contains("opencv_core")) {
                useOpenCv = false;
                engineDetail = "Webcam-Capture (fallback after OpenCV crash)";
                System.err.println("[Camera] OpenCV loop crashed, switching to fallback: " + t);
            }
            setStatus("camera stopped: " + t.getMessage());
        } finally {
            closeCamera();
            running = false;
            if (wantRun) {
                setStatus("camera stopped unexpectedly");
            }
            cameraCleanupFromInside();
        }
    }

    private void loopWebcamFallback() {
        Object webcam = openAnyCameraWebcam();
        if (webcam == null) {
            setStatus("no camera found - plug one in and press the camera button again (fallback driver also found nothing)");
            cameraCleanupFromInside();
            return;
        }
        this.webcamFallback = webcam;
        running = true;
        setStatus("camera live (" + engineDetail + ") - hold a barcode in front of it");

        long lastPreview = 0;
        try {
            java.lang.reflect.Method getImage = webcam.getClass().getMethod("getImage");
            java.lang.reflect.Method isOpen = webcam.getClass().getMethod("isOpen");
            while (wantRun && !Thread.currentThread().isInterrupted()) {
                Boolean open = (Boolean) isOpen.invoke(webcam);
                if (!open) {
                    sleepQuietly(100);
                    continue;
                }
                BufferedImage image = (BufferedImage) getImage.invoke(webcam);
                if (image == null) {
                    sleepQuietly(60);
                    continue;
                }
                long now = System.currentTimeMillis();
                if (now - lastPreview >= PREVIEW_INTERVAL_MS && !frameListeners.isEmpty()) {
                    lastPreview = now;
                    try {
                        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                        ImageIO.write(image, "jpg", baos);
                        byte[] bytes = baos.toByteArray();
                        for (FrameListener l : frameListeners) {
                            try {
                                l.onFrame(bytes);
                            } catch (Throwable ignored) {
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
                decodeImage(image);
                sleepQuietly(90);
            }
        } catch (Throwable t) {
            setStatus("camera stopped (fallback): " + t.getMessage());
        } finally {
            closeCameraFallback();
            running = false;
            if (wantRun) {
                setStatus("camera stopped unexpectedly");
            }
            cameraCleanupFromInside();
        }
    }

    private VideoCapture openAnyCameraOpenCv() {
        for (int i = 0; i < MAX_CAMERAS_TO_TRY; i++) {
            VideoCapture cam = null;
            try {
                cam = new VideoCapture(i, CAP_DSHOW);
                if (cam.isOpened()) {
                    cam.set(CAP_PROP_FRAME_WIDTH, 1280);
                    cam.set(CAP_PROP_FRAME_HEIGHT, 720);
                    setStatus("camera " + i + " found (" + engineDetail + ")");
                    return cam;
                }
            } catch (Throwable ignored) {
            }
            if (cam != null) {
                try {
                    cam.release();
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private Object openAnyCameraWebcam() {
        try {
            Class<?> webcamClass = Class.forName("com.github.sarxos.webcam.Webcam");
            java.lang.reflect.Method getDefault = webcamClass.getMethod("getDefault");
            java.lang.reflect.Method getWebcams = webcamClass.getMethod("getWebcams");
            java.lang.reflect.Method open = webcamClass.getMethod("open");
            java.lang.reflect.Method isOpen = webcamClass.getMethod("isOpen");
            @SuppressWarnings("unchecked")
            java.util.List<?> webcams = (java.util.List<?>) getWebcams.invoke(null);
            if (webcams == null || webcams.isEmpty()) return null;
            for (Object cam : webcams) {
                try {
                    Boolean opened = (Boolean) isOpen.invoke(cam);
                    if (!opened) {
                        open.invoke(cam);
                    }
                    // Set view size if possible
                    try {
                        java.lang.reflect.Method setViewSize = webcamClass.getMethod("setViewSize", java.awt.Dimension.class);
                        setViewSize.invoke(cam, new java.awt.Dimension(1280, 720));
                    } catch (Throwable ignored) {
                    }
                    return cam;
                } catch (Throwable ignored) {
                }
            }
            // Fallback to default
            Object def = getDefault.invoke(null);
            if (def != null) {
                try {
                    Boolean opened = (Boolean) isOpen.invoke(def);
                    if (!opened) open.invoke(def);
                    return def;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void cameraCleanupFromInside() {
        synchronized (this) {
            if (Thread.currentThread() == worker) {
                worker = null;
            }
        }
    }

    private synchronized void closeCamera() {
        VideoCapture cam = camera;
        camera = null;
        if (cam != null) {
            try {
                cam.release();
            } catch (Throwable ignored) {
            }
        }
        closeCameraFallback();
    }

    private synchronized void closeCameraFallback() {
        Object cam = webcamFallback;
        webcamFallback = null;
        if (cam != null) {
            try {
                cam.getClass().getMethod("close").invoke(cam);
            } catch (Throwable ignored) {
            }
        }
    }

    private void setStatus(String text) {
        statusText = text;
        for (StatusListener l : statusListeners) {
            try {
                l.onStatus(running, text);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ decode

    private void decodeMat(Mat frame) {
        BufferedImage image = toImage(frame);
        decodeImage(image);
    }

    private void decodeImage(BufferedImage image) {
        String text;
        String engine;

        text = tryDynamsoft(image);
        if (text != null) {
            engine = "Dynamsoft";
        } else {
            text = zxing(image, false);
            if (text != null) {
                engine = "ZXing";
            } else {
                text = zxing(image, true);
                engine = "ZXing";
            }
        }

        if (text == null || text.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (text.equals(lastCode) && now - lastCodeAt < SAME_CODE_COOLDOWN_MS) {
            return;
        }
        lastCode = text;
        lastCodeAt = now;
        lastEngine = engine;
        for (CodeListener l : codeListeners) {
            try {
                l.onCode(text, engine);
            } catch (Throwable ignored) {
            }
        }
    }

    private static String tryDynamsoft(BufferedImage image) {
        try {
            return DynamsoftLocal.decode(image);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Code 39/128, EAN-8/13, UPC-A/E, QR on the original BGR frame. */
    private static String zxing(BufferedImage image, boolean hybrid) {
        try {
            MultiFormatReader reader = new MultiFormatReader();
            Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
            hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
            hints.put(DecodeHintType.POSSIBLE_FORMATS, List.of(
                    BarcodeFormat.CODE_39, BarcodeFormat.CODE_128,
                    BarcodeFormat.EAN_8, BarcodeFormat.EAN_13,
                    BarcodeFormat.UPC_A, BarcodeFormat.UPC_E, BarcodeFormat.QR_CODE));
            LuminanceSource source = new BufferedImageLuminanceSource(image);
            BinaryBitmap bitmap = hybrid
                    ? new BinaryBitmap(new HybridBinarizer(source))
                    : new BinaryBitmap(new com.google.zxing.common.GlobalHistogramBinarizer(source));
            return reader.decode(bitmap, hints).getText();
        } catch (NotFoundException e) {
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** BGR Mat -> BufferedImage without ImageIO round trips. */
    private static BufferedImage toImage(Mat frame) {
        int w = frame.cols();
        int h = frame.rows();
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
        WritableRaster raster = image.getRaster();
        byte[] row = new byte[w * 3];
        for (int y = 0; y < h; y++) {
            frame.ptr(y, 0).get(row);
            raster.setDataElements(0, y, w, 1, row);
        }
        return image;
    }

    // ----------------------------------------------------------------- misc

    /** Preview consumers turn a JPEG payload back into a picture here. */
    public static BufferedImage jpegToImage(byte[] jpeg) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(jpeg));
    }
}
