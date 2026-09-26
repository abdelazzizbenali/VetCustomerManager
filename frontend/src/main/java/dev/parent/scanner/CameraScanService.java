package dev.parent.scanner;

import com.github.sarxos.webcam.Webcam;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import dev.parent.config.AppConfig;
import dev.parent.config.Log;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_videoio.VideoCapture;

import javax.imageio.ImageIO;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.ByteArrayInputStream;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.bytedeco.opencv.global.opencv_imgcodecs.imencode;
import static org.bytedeco.opencv.global.opencv_videoio.CAP_ANY;
import static org.bytedeco.opencv.global.opencv_videoio.CAP_DSHOW;
import static org.bytedeco.opencv.global.opencv_videoio.CAP_MSMF;
import static org.bytedeco.opencv.global.opencv_videoio.CAP_PROP_FRAME_HEIGHT;
import static org.bytedeco.opencv.global.opencv_videoio.CAP_PROP_FRAME_WIDTH;

/**
 * Built-in camera scanner — single JVM, no helper process.
 *
 * <p>Preferred engine is OpenCV (bytedeco) — tries DSHOW, MSMF, then ANY for each
 * index 0..3 so Windows cameras that only expose MSMF are still found.
 * If OpenCV natives cannot even initialize (old JDK25 + missing --enable-native-access,
 * or mismatched openblas natives) the service transparently falls back to
 * sarxos webcam-capture (pure Java, BridJ/DSHOW). One loop thread does preview
 * JPEGs + decode chain Dynamsoft → ZXing.</p>
 */
public final class CameraScanService {

    @FunctionalInterface
    public interface StatusListener { void onStatus(boolean running, String message); }
    @FunctionalInterface
    public interface FrameListener { void onFrame(byte[] jpeg); }
    @FunctionalInterface
    public interface CodeListener { void onCode(String code, String engine); }

    private static final long PREVIEW_INTERVAL_MS = 140;
    private static final long SAME_CODE_COOLDOWN_MS = 2500;
    private static final int MAX_CAMERAS_TO_TRY = 4;

    private static volatile boolean nativesOk;
    private static volatile String nativesError = "";
    private static volatile boolean useOpenCv = true;
    private static volatile String engineDetail = "OpenCV";
    private static volatile String lastProbeDetail = "";

    public static synchronized boolean ensureNatives() {
        if (nativesOk) return true;
        Throwable firstError = null;
        try {
            if (System.getProperty("org.bytedeco.openblas.load") == null) {
                System.setProperty("org.bytedeco.openblas.load", "none");
            }
        } catch (Throwable ignored) {}
        try {
            Loader.load(org.bytedeco.opencv.global.opencv_videoio.class);
            Loader.load(org.bytedeco.opencv.global.opencv_imgcodecs.class);
            try { Loader.load(org.bytedeco.opencv.global.opencv_core.class); } catch (Throwable ignored) {}
            Class.forName("org.bytedeco.opencv.opencv_core$Mat");
            nativesOk = true; nativesError = ""; useOpenCv = true; engineDetail = "OpenCV";
            return true;
        } catch (Throwable t) {
            firstError = t;
            String m = t.toString(); if (t.getCause()!=null) m += " -> " + t.getCause();
            System.err.println("[Camera] OpenCV natives failed: " + m);
            Log.warn("OpenCV init failed: " + m);
        }
        try {
            System.clearProperty("org.bytedeco.openblas.load");
            Loader.load(org.bytedeco.opencv.global.opencv_core.class);
            Class.forName("org.bytedeco.opencv.opencv_core$Mat");
            nativesOk = true; nativesError = ""; useOpenCv = true; engineDetail = "OpenCV";
            return true;
        } catch (Throwable t) {
            String m = t.toString(); if (t.getCause()!=null) m += " -> "+t.getCause();
            System.err.println("[Camera] OpenCV fallback load failed: " + m);
        }
        // webcam-capture fallback — verify BridJ can enumerate
        try {
            // touch slf4j + webcam classes
            Class.forName("com.github.sarxos.webcam.Webcam");
            // trigger discovery — may throw BridJ UnsatisfiedLinkError on restricted JVMs
            List<Webcam> w = Webcam.getWebcams();
            // if we get here, the driver itself loaded (even if list empty, that's OK for ensure)
            nativesOk = true; nativesError = "";
            useOpenCv = false;
            String hint = firstError != null ? String.valueOf(firstError.getMessage()) : "unknown";
            engineDetail = "Webcam-Capture (fallback, OpenCV unavailable)";
            lastProbeDetail = "OpenCV failed: " + hint + " | webcam-capture driver loaded, " + w.size() + " device(s) enumerated";
            System.out.println("[Camera] Using webcam-capture fallback (" + lastProbeDetail + ")");
            Log.info("Camera fallback active: " + lastProbeDetail);
            return true;
        } catch (Throwable t) {
            String m = firstError != null ? firstError.toString() : "Unknown";
            m += " | Webcam fallback also failed: " + t + (t.getCause()!=null?" -> "+t.getCause():"");
            nativesError = m;
            if (m.contains("opencv_core") || m.contains("openblas_nolapack")) {
                nativesError = "Could not initialize OpenCV natives. This often happens on JDK 25 without --enable-native-access or with a mismatched JavaFX/JDK. "
                        + "Raw: " + m;
            }
            System.err.println("[Camera] No engine available: " + nativesError);
            return false;
        }
    }

    public static String nativesError() { return nativesError; }
    public static String engineDetail() { return engineDetail; }
    public static String lastProbeDetail() { return lastProbeDetail; }

    private static CameraScanService instance;
    public static synchronized CameraScanService get() {
        if (instance == null) instance = new CameraScanService();
        return instance;
    }

    private final List<StatusListener> statusListeners = new CopyOnWriteArrayList<>();
    private final List<FrameListener> frameListeners = new CopyOnWriteArrayList<>();
    private final List<CodeListener> codeListeners = new CopyOnWriteArrayList<>();

    private volatile boolean wantRun;
    private volatile boolean running;
    private volatile Thread worker;
    private volatile VideoCapture camera;
    private volatile Webcam webcamFallback;
    private volatile String statusText = "camera idle";
    private volatile String lastCode = "";
    private volatile long lastCodeAt;
    private volatile String lastEngine = "";

    private CameraScanService() {}

    public void addStatusListener(StatusListener l) { statusListeners.add(l); }
    public void removeStatusListener(StatusListener l) { statusListeners.remove(l); }
    public void addFrameListener(FrameListener l) { frameListeners.add(l); }
    public void removeFrameListener(FrameListener l) { frameListeners.remove(l); }
    public void addCodeListener(CodeListener l) { codeListeners.add(l); }
    public void removeCodeListener(CodeListener l) { codeListeners.remove(l); }

    public synchronized void start() {
        if (worker != null) return;
        wantRun = true;
        worker = Thread.ofVirtual().name("vetms-camera").start(this::loop);
    }
    public synchronized void stop() {
        wantRun = false;
        Thread w = worker;
        if (w != null) w.interrupt();
        worker = null;
        closeCamera();
        running = false;
        setStatus("camera stopped");
    }
    public boolean running() { return running; }
    public String statusText() { return statusText; }
    public String lastEngine() { return lastEngine; }

    public static boolean probeAny() {
        if (!ensureNatives()) return false;
        StringBuilder diag = new StringBuilder();
        if (useOpenCv) {
            for (int i = 0; i < MAX_CAMERAS_TO_TRY; i++) {
                for (int backend : new int[]{CAP_DSHOW, CAP_MSMF, CAP_ANY}) {
                    VideoCapture probe = null;
                    try {
                        probe = new VideoCapture(i, backend);
                        if (probe.isOpened()) {
                            probe.release();
                            lastProbeDetail = "OpenCV index " + i + " via " + backendName(backend) + " is available";
                            return true;
                        } else {
                            diag.append("idx").append(i).append("/").append(backendName(backend)).append(" closed; ");
                        }
                    } catch (Throwable t) {
                        diag.append("idx").append(i).append("/").append(backendName(backend)).append(" err:").append(t.getMessage()).append("; ");
                    } finally { if (probe!=null) try{probe.release();}catch(Throwable ignored){} }
                }
            }
            lastProbeDetail = diag.toString();
            // try webcam fallback probe before giving up
            if (probeWebcamFallbackDetailed(diag)) return true;
            return false;
        } else {
            return probeWebcamFallback();
        }
    }

    private static boolean probeWebcamFallback() {
        StringBuilder sb = new StringBuilder();
        boolean r = probeWebcamFallbackDetailed(sb);
        if (!r) lastProbeDetail = sb.toString();
        return r;
    }
    private static boolean probeWebcamFallbackDetailed(StringBuilder out) {
        try {
            List<Webcam> webcams = getWebcamsOnPlatformThread();
            if (webcams != null && !webcams.isEmpty()) {
                String names = webcams.stream().map(w -> w.getName()).collect(Collectors.joining(", "));
                long physical = webcams.stream().filter(w -> !isVirtualCamera(w.getName())).count();
                out.append("webcam-capture found ").append(webcams.size()).append(" device(s) [").append(names).append("]; physical=").append(physical);
                lastProbeDetail = out.toString();
                return true;
            }
            Webcam d = getDefaultWebcamOnPlatformThread();
            if (d != null) {
                out.append("webcam-capture default=").append(d.getName());
                lastProbeDetail = out.toString();
                return true;
            }
            out.append("webcam-capture enumerated 0 devices");
            return false;
        } catch (Throwable t) {
            out.append("webcam-capture probe failed: ").append(t).append(t.getCause()!=null?" -> "+t.getCause():"");
            Log.warn("webcam probe failed: " + t);
            return false;
        }
    }

    private static List<Webcam> getWebcamsOnPlatformThread() throws Exception {
        return callOnPlatformThread(() -> Webcam.getWebcams(), 4000);
    }
    private static Webcam getDefaultWebcamOnPlatformThread() throws Exception {
        return callOnPlatformThread(() -> Webcam.getDefault(), 2500);
    }
    private static <T> T callOnPlatformThread(Callable<T> task, long timeoutMs) throws Exception {
        ExecutorService ex = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "webcam-platform");
            t.setDaemon(true);
            return t;
        });
        try {
            Future<T> f = ex.submit(task);
            return f.get(timeoutMs, TimeUnit.MILLISECONDS);
        } finally {
            ex.shutdownNow();
        }
    }
    private static boolean isVirtualCamera(String name) {
        if (name == null) return false;
        String n = name.toLowerCase();
        return n.contains("virtual") || n.contains("mirametrix") || n.contains("obs virtual") || n.contains("xsplit");
    }

    private void loop() {
        if (!ensureNatives()) {
            setStatus("camera engine missing: " + nativesError);
            cameraCleanupFromInside(); return;
        }
        if (useOpenCv) loopOpenCv(); else loopWebcamFallback();
    }

    private void loopOpenCv() {
        VideoCapture cam = openAnyCameraOpenCv();
        if (cam == null) {
            String diag = lastProbeDetail;
            if (probeWebcamFallback()) {
                useOpenCv = false; engineDetail = "Webcam-Capture (fallback)";
                Log.info("OpenCV found no camera (" + diag + ") — switching to webcam-capture");
                loopWebcamFallback(); return;
            }
            setStatus("no camera found - plug one in and press the camera button again"
                    + (diag.isBlank() ? "" : " | " + diag)
                    + " | Tip: check Windows Privacy -> Camera is ON for desktop apps, and no other app (Teams/Zoom/browser) is using the camera.");
            cameraCleanupFromInside(); return;
        }
        this.camera = cam; running = true;
        setStatus("camera live (" + engineDetail + " via " + lastProbeDetail + ") - hold a barcode in front of it");
        Mat frame = new Mat(); long lastPreview=0, lastEmptyLog=0;
        try {
            while (wantRun && !Thread.currentThread().isInterrupted()) {
                if (!cam.grab()) sleepQuietly(30);
                if (!cam.retrieve(frame) || frame.empty()) {
                    long now = System.currentTimeMillis();
                    if (now - lastEmptyLog > 5000) { lastEmptyLog=now; setStatus("camera is on but sending no picture - is another app using it? ("+lastProbeDetail+")"); }
                    sleepQuietly(60); continue;
                }
                long now = System.currentTimeMillis();
                if (now - lastPreview >= PREVIEW_INTERVAL_MS && !frameListeners.isEmpty()) {
                    lastPreview = now;
                    BytePointer jpeg = new BytePointer();
                    if (imencode(".jpg", frame, jpeg)) {
                        byte[] bytes = new byte[(int) jpeg.limit()]; jpeg.get(bytes);
                        for (FrameListener l : frameListeners) try{ l.onFrame(bytes);}catch(Throwable ignored){}
                    }
                }
                decodeMat(frame); sleepQuietly(90);
            }
        } catch (Throwable t) {
            String m = String.valueOf(t.getMessage());
            if (m.contains("opencv_core")) { useOpenCv=false; engineDetail="Webcam-Capture (fallback after OpenCV crash)"; System.err.println("[Camera] OpenCV loop crashed, switching: "+t); }
            setStatus("camera stopped: " + t.getMessage());
            Log.warn("Camera loop crash: " + t);
        } finally { closeCamera(); running=false; if(wantRun) setStatus("camera stopped unexpectedly"); cameraCleanupFromInside(); }
    }

    private void loopWebcamFallback() {
        Webcam webcam = openAnyCameraWebcam();
        if (webcam == null) {
            String diag = lastProbeDetail.isBlank()? "" : " | "+lastProbeDetail;
            setStatus("no camera found - plug one in and press the camera button again (fallback driver also found nothing)"
                    + diag + " | Tip: check Windows Settings -> Privacy -> Camera, and close Teams/Zoom/browser that may hold the camera. If you have a camera, try unplugging it for 5s.");
            cameraCleanupFromInside(); return;
        }
        this.webcamFallback = webcam; running=true;
        setStatus("camera live (" + engineDetail + " ~ " + webcam.getName() + ") - hold a barcode in front of it");
        long lastPreview=0;
        try {
            while (wantRun && !Thread.currentThread().isInterrupted()) {
                if (!webcam.isOpen()) { sleepQuietly(100); continue; }
                BufferedImage image = webcam.getImage();
                if (image == null) { sleepQuietly(60); continue; }
                long now = System.currentTimeMillis();
                if (now - lastPreview >= PREVIEW_INTERVAL_MS && !frameListeners.isEmpty()) {
                    lastPreview = now;
                    try { java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream(); ImageIO.write(image, "jpg", baos); byte[] b = baos.toByteArray(); for(FrameListener l:frameListeners) try{l.onFrame(b);}catch(Throwable ignored){} } catch(Throwable ignored){}
                }
                decodeImage(image); sleepQuietly(90);
            }
        } catch (Throwable t) { setStatus("camera stopped (fallback): "+t.getMessage()); Log.warn("fallback loop: "+t); }
        finally { closeCameraFallback(); running=false; if(wantRun) setStatus("camera stopped unexpectedly"); cameraCleanupFromInside(); }
    }

    private VideoCapture openAnyCameraOpenCv() {
        lastProbeDetail = "";
        for (int i=0;i<MAX_CAMERAS_TO_TRY;i++) {
            for (int backend : new int[]{CAP_DSHOW, CAP_MSMF, CAP_ANY}) {
                VideoCapture cam = null;
                try {
                    cam = new VideoCapture(i, backend);
                    if (cam.isOpened()) {
                        cam.set(CAP_PROP_FRAME_WIDTH, 1280);
                        cam.set(CAP_PROP_FRAME_HEIGHT, 720);
                        lastProbeDetail = "camera " + i + " via " + backendName(backend);
                        setStatus(lastProbeDetail + " found ("+engineDetail+")");
                        return cam;
                    }
                } catch (Throwable t) { lastProbeDetail = "idx"+i+"/"+backendName(backend)+" err: "+t.getMessage(); }
                if (cam!=null) try{cam.release();}catch(Throwable ignored){}
            }
        }
        lastProbeDetail += " | tried " + MAX_CAMERAS_TO_TRY + " indices x DSHOW/MSMF/ANY";
        return null;
    }

    private Webcam openAnyCameraWebcam() {
        StringBuilder diag = new StringBuilder();
        try {
            List<Webcam> webcams = getWebcamsOnPlatformThread();
            if (webcams==null || webcams.isEmpty()) {
                lastProbeDetail = "Webcam.getWebcams() returned 0 devices";
                try {
                    Webcam def = getDefaultWebcamOnPlatformThread();
                    if (def != null) {
                        prepareAndOpenOnPlatformThread(def);
                        if (def.isOpen()) { lastProbeDetail = "default webcam "+def.getName(); return def; }
                    }
                } catch (Throwable t2) { lastProbeDetail += " | default also failed: "+t2.getMessage(); }
                return null;
            }
            diag.append("found ").append(webcams.size()).append(": ");
            diag.append(webcams.stream().map(w -> w.getName()).collect(Collectors.joining(", ")));
            // Try physical cameras first, then virtual
            List<Webcam> physical = webcams.stream().filter(w -> !isVirtualCamera(w.getName())).collect(Collectors.toList());
            List<Webcam> virtual  = webcams.stream().filter(w -> isVirtualCamera(w.getName())).collect(Collectors.toList());
            List<Webcam> ordered = new java.util.ArrayList<>();
            ordered.addAll(physical);
            ordered.addAll(virtual);
            if (virtual.size() > 0 && physical.isEmpty()) {
                diag.append(" | NOTE: only virtual camera(s) found (Mirametrix/OBS) — real camera may be disabled by Windows Privacy or unplugged");
            }
            StringBuilder errors = new StringBuilder();
            for (Webcam cam : ordered) {
                try {
                    prepareAndOpenOnPlatformThread(cam);
                    if (cam.isOpen()) { lastProbeDetail = diag + " | opened "+cam.getName(); return cam; }
                    errors.append(cam.getName()).append(": failed to open; ");
                } catch (Throwable t) {
                    String msg = t.getMessage()==null? t.toString(): t.getMessage();
                    if (msg.contains("Cannot execute task")) {
                        errors.append(cam.getName()).append(": Cannot execute task (virtual-camera driver blocked — try closing eye-tracker/OBS or disable Mirametrix in Device Manager); ");
                    } else {
                        errors.append(cam.getName()).append(" err: ").append(msg).append("; ");
                    }
                    Log.warn("open webcam "+cam.getName()+" failed: "+t);
                }
            }
            lastProbeDetail = diag.toString() + " | " + errors.toString().trim();
            // fallback to default
            try { Webcam def = getDefaultWebcamOnPlatformThread(); if(def!=null){ prepareAndOpenOnPlatformThread(def); if(def.isOpen()) return def; } } catch(Throwable ignored){}
        } catch (Throwable t) {
            lastProbeDetail = "webcam-capture failed: "+t.getMessage()+(t.getCause()!=null?" -> "+t.getCause():"");
            Log.warn("openAnyCameraWebcam failed: "+t);
        }
        // also try OpenCV once more for any index that might have been blocked by virtual driver
        if (lastProbeDetail.contains("only virtual")) {
            lastProbeDetail += " | Tip: Disable 'Mirametrix Virtual Camera' in Device Manager -> Cameras, then unplug/replug real camera";
        }
        return null;
    }

    private static void prepareAndOpenOnPlatformThread(Webcam cam) throws Exception {
        callOnPlatformThread(() -> { prepareAndOpen(cam); return null; }, 5000);
    }

    private static void prepareAndOpen(Webcam cam) {
        if (cam.isOpen()) return;
        // setViewSize must be before open; pick a supported size
        try {
            Dimension want = new Dimension(1280,720);
            Dimension[] sizes = cam.getViewSizes();
            boolean supported = false;
            if (sizes!=null) for(Dimension d: sizes) if(d.equals(want)) {supported=true; break;}
            if (supported) cam.setViewSize(want);
            else if (sizes!=null && sizes.length>0) {
                // pick largest <= 1280, else first
                Dimension best = sizes[0];
                for(Dimension s: sizes) if(s.width<=1280 && s.width>best.width) best=s;
                cam.setViewSize(best);
            } else cam.setViewSize(want);
        } catch (Throwable ignored) {}
        cam.open();
    }

    private static String backendName(int b) {
        if (b==CAP_DSHOW) return "DSHOW";
        if (b==CAP_MSMF) return "MSMF";
        if (b==CAP_ANY) return "ANY";
        return "backend#"+b;
    }

    private void cameraCleanupFromInside() { synchronized(this){ if(Thread.currentThread()==worker) worker=null; } }
    private synchronized void closeCamera() { VideoCapture cam=camera; camera=null; if(cam!=null) try{cam.release();}catch(Throwable ignored){} closeCameraFallback(); }
    private synchronized void closeCameraFallback() { Webcam cam=webcamFallback; webcamFallback=null; if(cam!=null) try{cam.close();}catch(Throwable ignored){} }

    private void setStatus(String text) { statusText=text; for(StatusListener l:statusListeners) try{l.onStatus(running,text);}catch(Throwable ignored){} }
    private static void sleepQuietly(long ms){ try{Thread.sleep(ms);}catch(InterruptedException e){Thread.currentThread().interrupt();} }

    private void decodeMat(Mat frame){ BufferedImage image=toImage(frame); decodeImage(image); }
    private void decodeImage(BufferedImage image){
        // Gemini tip: pre-process before decoding (contrast, ROI, downscale) so low-quality webcams succeed
        BufferedImage filtered = ImageFilters.applyForDecode(image);
        if (filtered == null) filtered = image;
        String text = tryDynamsoft(filtered);
        String engine = null;
        if (text != null && isPlausible(text, null)) {
            engine = "Dynamsoft";
        } else {
            text = null;
            var r1 = zxingWithFormat(filtered, false);
            if (r1 != null && isPlausible(r1.text, r1.format)) { text = r1.text; engine = "ZXing"; }
            else {
                var r2 = zxingWithFormat(filtered, true);
                if (r2 != null && isPlausible(r2.text, r2.format)) { text = r2.text; engine = "ZXing"; }
                else text = null;
            }
        }
        if(text==null || text.isBlank()) return;
        long now=System.currentTimeMillis();
        if(text.equals(lastCode) && now - lastCodeAt < SAME_CODE_COOLDOWN_MS) return;
        lastCode=text; lastCodeAt=now; lastEngine=engine;
        for(CodeListener l:codeListeners) try{l.onCode(text,engine);}catch(Throwable ignored){}
    }
    private static String tryDynamsoft(BufferedImage image){
        try{
            String t = DynamsoftLocal.decode(image);
            if (t != null && isPlausible(t, null)) return t;
            return null;
        } catch(Throwable t){return null;}
    }
    private record ZxResult(String text, BarcodeFormat format) {}
    private static ZxResult zxingWithFormat(BufferedImage image, boolean hybrid){
        try{
            MultiFormatReader reader=new MultiFormatReader();
            Map<DecodeHintType,Object> hints=new EnumMap<>(DecodeHintType.class);
            hints.put(DecodeHintType.TRY_HARDER, AppConfig.get().cameraFilterTryHarder());
            hints.put(DecodeHintType.POSSIBLE_FORMATS, ImageFilters.parseFormats(AppConfig.get().cameraFilterFormats()));
            LuminanceSource source=new BufferedImageLuminanceSource(image);
            BinaryBitmap bitmap=hybrid? new BinaryBitmap(new HybridBinarizer(source)) : new BinaryBitmap(new com.google.zxing.common.GlobalHistogramBinarizer(source));
            var result = reader.decode(bitmap,hints);
            return new ZxResult(result.getText(), result.getBarcodeFormat());
        } catch(NotFoundException e){ return null; } catch(Throwable t){ return null; }
    }
    private static String zxing(BufferedImage image, boolean hybrid){
        var r = zxingWithFormat(image, hybrid);
        return r == null ? null : r.text;
    }

    /** Reject obvious false positives (text OCR as barcode, partial reads, bad checksums). */
    private static boolean isPlausible(String text, BarcodeFormat fmt) {
        if (text == null) return false;
        text = text.trim();
        if (text.length() < 4 || text.length() > 64) return false;
        if (text.contains(" ") || text.contains("\n") || text.contains("\t") || text.contains("\r")) return false;
        if (text.matches("^[a-z]{4,}$")) return false;
        for (int i = 0; i < text.length(); i++) { char c = text.charAt(i); if (c < 32 || c > 126) return false; }
        if (fmt == BarcodeFormat.EAN_13) {
            if (!text.matches("\\d{13}")) return false;
            return checkEan13(text);
        }
        if (fmt == BarcodeFormat.EAN_8) {
            if (!text.matches("\\d{8}")) return false;
            return checkEan8(text);
        }
        if (fmt == BarcodeFormat.UPC_A) {
            if (!text.matches("\\d{12}")) return false;
            return checkUpca(text);
        }
        if (fmt == BarcodeFormat.UPC_E) {
            if (!text.matches("\\d{6,8}")) return false;
        }
        if (fmt == null) {
            if (text.matches("\\d{13}") && !checkEan13(text)) return false;
            if (text.matches("\\d{8}") && !checkEan8(text)) return false;
            if (text.matches("\\d{12}") && !checkUpca(text)) return false;
        }
        return true;
    }
    private static boolean checkEan13(String s) {
        try { int sum = 0; for (int i = 0; i < 12; i++) { int d = s.charAt(i) - '0'; sum += (i % 2 == 0) ? d : d * 3; } int chk = (10 - (sum % 10)) % 10; return chk == (s.charAt(12) - '0'); } catch (Throwable t) { return false; }
    }
    private static boolean checkEan8(String s) {
        try { int sum = 0; for (int i = 0; i < 7; i++) { int d = s.charAt(i) - '0'; sum += (i % 2 == 0) ? d * 3 : d; } int chk = (10 - (sum % 10)) % 10; return chk == (s.charAt(7) - '0'); } catch (Throwable t) { return false; }
    }
    private static boolean checkUpca(String s) {
        try { int sum = 0; for (int i = 0; i < 11; i++) { int d = s.charAt(i) - '0'; sum += (i % 2 == 0) ? d * 3 : d; } int chk = (10 - (sum % 10)) % 10; return chk == (s.charAt(11) - '0'); } catch (Throwable t) { return false; }
    }

    private static boolean hasActiveFilters() {
        AppConfig c = AppConfig.get();
        return c.cameraFilterBrightness()!=0 || c.cameraFilterContrast()!=100 || c.cameraFilterSaturation()!=100
                || c.cameraFilterSharpness()!=0 || c.cameraFilterGrayscale() || c.cameraFilterInvert()
                || c.cameraFilterDownscale() || c.cameraFilterRoiEnabled();
    }
    private static BufferedImage toImage(Mat frame){
        int w=frame.cols(), h=frame.rows();
        BufferedImage image=new BufferedImage(w,h,BufferedImage.TYPE_3BYTE_BGR);
        WritableRaster raster=image.getRaster(); byte[] row=new byte[w*3];
        for(int y=0;y<h;y++){ frame.ptr(y,0).get(row); raster.setDataElements(0,y,w,1,row); }
        return image;
    }
    public static BufferedImage jpegToImage(byte[] jpeg) throws Exception { return ImageIO.read(new ByteArrayInputStream(jpeg)); }
}
