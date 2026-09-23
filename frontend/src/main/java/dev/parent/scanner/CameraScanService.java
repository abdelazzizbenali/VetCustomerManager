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
 * <p>Engine: OpenCV's DirectShow backend (org.bytedeco preset bundles), running
 * entirely inside this JVM - no helper process, no daemon, no temp files, and
 * no bridj-style native reflection (which was unstable on modern JDKs: it is
 * what made the old build die after a few minutes). One loop thread reads BGR
 * frames, JPEG-encodes them for the live preview and hands the same pixels to
 * the decode chain {@code Dynamsoft (licensed) -> ZXing global -> ZXing hybrid}.
 * All decode engines run in-process.</p>
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

    /** The one-time load of the OpenCV native libraries (idempotent). */
    public static synchronized boolean ensureNatives() {
        if (nativesOk) {
            return true;
        }
        try {
            Loader.load(org.bytedeco.opencv.global.opencv_javaio.class);
            nativesOk = true;
            nativesError = "";
            return true;
        } catch (Throwable t) {
            nativesError = String.valueOf(t.getMessage());
            return false;
        }
    }

    public static String nativesError() {
        return nativesError;
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
    private volatile VideoCapture camera;
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
                        // nothing else to do
                    }
                }
            }
        }
        return false;
    }

    // ----------------------------------------------------------------- loops

    private void loop() {
        if (!ensureNatives()) {
            setStatus("camera engine missing: " + nativesError);
            cameraCleanupFromInside();
            return;
        }
        VideoCapture cam = openAnyCamera();
        if (cam == null) {
            setStatus("no camera found - plug one in and press the camera button again");
            cameraCleanupFromInside();
            return;
        }
        this.camera = cam;
        running = true;
        setStatus("camera live - hold a barcode in front of it");

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
                                // viewers come and go
                            }
                        }
                    }
                }

                decode(frame);
                sleepQuietly(90); // ~11 fps read loop - plenty for hand-held barcodes
            }
        } catch (Throwable t) {
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

    private VideoCapture openAnyCamera() {
        for (int i = 0; i < MAX_CAMERAS_TO_TRY; i++) {
            VideoCapture cam = null;
            try {
                cam = new VideoCapture(i, CAP_DSHOW);
                if (cam.isOpened()) {
                    cam.set(CAP_PROP_FRAME_WIDTH, 1280);
                    cam.set(CAP_PROP_FRAME_HEIGHT, 720);
                    setStatus("camera " + i + " found");
                    return cam;
                }
            } catch (Throwable ignored) {
                // next index
            }
            if (cam != null) {
                try {
                    cam.release();
                } catch (Throwable ignored) {
                    // nothing else to do
                }
            }
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
                // release is best-effort
            }
        }
    }

    private void setStatus(String text) {
        statusText = text;
        for (StatusListener l : statusListeners) {
            try {
                l.onStatus(running, text);
            } catch (Throwable ignored) {
                // listeners come and go
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

    private void decode(Mat frame) {
        String text;
        String engine;

        // Same pixels feed every engine: one conversion, then the chain.
        BufferedImage image = toImage(frame);

        // 1) Dynamsoft - professional engine, needs the free license key.
        text = tryDynamsoft(image);
        if (text != null) {
            engine = "Dynamsoft";
        } else {
            // 2) ZXing global binarizer (fast), then 3) hybrid (more tolerant).
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
            return; // debounce: same barcode waved for a couple of seconds
        }
        lastCode = text;
        lastCodeAt = now;
        lastEngine = engine;
        for (CodeListener l : codeListeners) {
            try {
                l.onCode(text, engine);
            } catch (Throwable ignored) {
                // listeners come and go
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
