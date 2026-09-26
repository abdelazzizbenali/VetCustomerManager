package dev.parent.ui;

import dev.parent.VetApp;
import dev.parent.config.AppConfig;
import dev.parent.config.AppDirs;
import dev.parent.config.Log;
import dev.parent.db.ClientDao;
import dev.parent.db.LocalDatabase;
import dev.parent.db.MedicineDao;
import dev.parent.db.SupabaseClient;
import dev.parent.db.SyncService;
import dev.parent.db.TransactionDao;
import dev.parent.model.Client;
import dev.parent.model.Medicine;
import dev.parent.model.SaleTransaction;
import dev.parent.scanner.ScannerService;
import dev.parent.util.Bg;
import dev.parent.util.FxUtil;
import dev.parent.util.SoundPlayer;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.image.ImageView;
import javafx.scene.image.Image;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Settings: database connection, sync behaviour, scanner devices, data tools. */
final class SettingsView extends MainWindow.BaseView {

    private final AppConfig cfg = AppConfig.get();
    private final TextField urlField = new TextField();
    private final PasswordField keyField = new PasswordField();
    private final Label dbStatus = new Label(" ");
    private final Spinner<Integer> interval = new Spinner<>(10, 600, 30, 5);
    private final CheckBox sounds = new CheckBox("Play Found / notFound sounds when scanning");
    private final CheckBox hid = new CheckBox("Enable USB keyboard scanner (plug && play)");
    private final CheckBox cameraAlwaysOn = new CheckBox(
            "Camera always on - show a barcode anytime, no button to press");
    private final CheckBox showPreview = new CheckBox(
            "Show the live camera preview on Daily Usage (turn off for a lighter screen)");
    private final TextField dynamsoftKey = new TextField();
    private final Label cameraEngineInfo = new Label("Hybrid camera: OpenCV (DSHOW/MSMF) with webcam-capture fallback — no external programs to leave running behind");
    private final Label serverScanStatus = new Label("Camera: checking...");
    private final ComboBox<String> serialPort = new ComboBox<>();
    private final ComboBox<Integer> serialBaud = new ComboBox<>(
            javafx.collections.FXCollections.observableArrayList(1200, 2400, 4800, 9600, 19200, 38400, 57600, 115200));
    private final TextField gap = new TextField();
    private final Label issues = new Label();

    // ---- Camera Lab (Gemini) ----
    private final Slider labBrightness = new Slider(-100, 100, 0);
    private final Slider labContrast = new Slider(50, 250, 100);
    private final Slider labSaturation = new Slider(0, 200, 100);
    private final Slider labSharpness = new Slider(0, 200, 0);
    private final Slider labRoiSize = new Slider(40, 95, 75);
    private final Label labBrightnessVal = new Label("0");
    private final Label labContrastVal = new Label("100%");
    private final Label labSaturationVal = new Label("100%");
    private final Label labSharpnessVal = new Label("0%");
    private final Label labRoiSizeVal = new Label("75%");
    private final CheckBox labGrayscale = new CheckBox("Grayscale");
    private final CheckBox labInvert = new CheckBox("Invert");
    private final CheckBox labRoiEnabled = new CheckBox("ROI — crop central band (Gemini: thin horizontal for EAN)");
    private final CheckBox labTryHarder = new CheckBox("TRY_HARDER (spends more time on blurry frames)");
    private final CheckBox labDownscale = new CheckBox("Downscale to 640px (faster on 1080p)");
    private final TextField labFormats = new TextField();
    private final ImageView labPreview = new ImageView();
    private final Label labPreviewStatus = new Label("Lab stopped — press Start preview");
    private final TextField labLastCode = new TextField();
    private final Label labLastEngine = new Label("");
    private final Label labLabInfo = new Label();
    private volatile boolean labActive = false;
    private volatile int labBrightnessV = 0, labContrastV = 100, labSaturationV = 100, labSharpnessV = 0, labRoiSizeV = 75;
    private volatile boolean labGrayscaleV = false, labInvertV = false, labRoiEnabledV = false, labTryHarderV = true, labDownscaleV = true;
    private volatile String labFormatsV = "CODE_128,CODE_39,EAN_13,EAN_8,UPC_A,UPC_E,QR_CODE";
    private dev.parent.scanner.CameraScanService.FrameListener labFrameListener;
    private dev.parent.scanner.CameraScanService.CodeListener labCodeListener;
    private long labLastPreviewMs = 0;
    private String labLastCodeStr = ""; private long labLastCodeAt = 0;

    SettingsView() {
        urlField.setText(cfg.supabaseUrl());
        keyField.setText(cfg.supabaseKey());
        interval.getValueFactory().setValue(cfg.syncIntervalSec());
        interval.setEditable(true);
        sounds.setSelected(cfg.soundsEnabled());
        hid.setSelected(cfg.hidEnabled());
        cameraAlwaysOn.setSelected(cfg.cameraAlwaysOn());
        cameraAlwaysOn.setOnAction(e -> {
            cfg.setCameraAlwaysOn(cameraAlwaysOn.isSelected());
            cfg.save();
            if (cameraAlwaysOn.isSelected()) {
                Bg.run("camera-on", () -> dev.parent.scanner.CameraScanService.get().start());
            } else {
                Bg.run("camera-off", () -> dev.parent.scanner.CameraScanService.get().stop());
            }
        });
        showPreview.setSelected(cfg.cameraPreviewVisible());
        showPreview.setOnAction(e -> {
            cfg.setCameraPreviewVisible(showPreview.isSelected());
            cfg.save();
            MainWindow w = MainWindow.get();
            if (w != null) {
                w.refreshCurrent(); // Daily Usage applies it instantly
            }
        });
        cameraEngineInfo.getStyleClass().add("hint");
        serverScanStatus.getStyleClass().add("hint");
        serverScanStatus.setWrapText(true);
        dynamsoftKey.setText(cfg.dynamsoftLicense());
        dynamsoftKey.setPromptText("Optional: Dynamsoft license key (30-day free trial on dynamsoft.com)");
        gap.setText(String.valueOf(cfg.hidMaxGapMs()));
        serialBaud.getSelectionModel().select(Integer.valueOf(cfg.serialBaud()));

        VBox root = new VBox(4);
        root.setPadding(new Insets(20));
        Label title = new Label("Settings");
        title.getStyleClass().add("view-title");
        root.getChildren().addAll(title,
                buildSupabaseSection(),
                buildSyncSection(),
                buildScannerSection(),
                buildCameraLabSection(),
                buildDataSection(),
                buildAboutSection());
        ScrollPane scroll = new ScrollPane(root);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: #263238;");
        setCenter(scroll);
    }

    // ------------------------------------------------------------- sections

    private VBox buildSupabaseSection() {
        VBox box = section("Online database (Supabase)");
        GridPane grid = new GridPane();
        grid.getStyleClass().add("form-grid");
        grid.add(rowLabel("Project URL"), 0, 0);
        grid.add(urlField, 1, 0);
        grid.add(rowLabel("API key (anon public)"), 0, 1);
        grid.add(keyField, 1, 1);
        urlField.setPrefColumnCount(34);
        keyField.setPrefColumnCount(34);

        Button test = Ui.toolButton("Test connection", "database.png", () -> testConnection(null));
        Button save = Ui.toolButton("Save && apply", "check.png", () -> {
            cfg.setSupabase(SupabaseClient.normalizeBaseUrl(urlField.getText()), keyField.getText());
            cfg.save();
            if (cfg.hasSupabase()) {
                SyncService.configure(new SupabaseClient(cfg.supabaseUrl(), cfg.supabaseKey()));
                SyncService.restart();
                dbStatus.setStyle("-fx-text-fill:#FFB300;");
                dbStatus.setText("Saved. Connecting...");
            } else {
                dbStatus.setStyle("-fx-text-fill:#EF5350;");
                dbStatus.setText("Saved, but URL or key is empty - offline mode.");
            }
        });
        save.getStyleClass().add("accent");
        HBox buttons = new HBox(10, test, save);
        box.getChildren().addAll(grid, buttons, dbStatus);
        return box;
    }

    private void testConnection(Runnable onOk) {
        dbStatus.setStyle("-fx-text-fill:#FFB300;");
        dbStatus.setText("Testing...");
        Task<Integer> task = new Task<>() {
            @Override
            protected Integer call() throws Exception {
                return new SupabaseClient(urlField.getText(), keyField.getText()).ping();
            }
        };
        task.setOnSucceeded(e -> {
            dbStatus.setStyle("-fx-text-fill:#66BB6A;");
            dbStatus.setText("Connection OK (schema v" + task.getValue() + ")");
            if (onOk != null) {
                onOk.run();
            }
        });
        task.setOnFailed(e -> {
            dbStatus.setStyle("-fx-text-fill:#EF5350;");
            dbStatus.setText("Failed: " + task.getException().getMessage());
        });
        Thread t = new Thread(task, "vetms-db-test");
        t.setDaemon(true);
        t.start();
    }

    private VBox buildSyncSection() {
        VBox box = section("Synchronisation");
        GridPane grid = new GridPane();
        grid.getStyleClass().add("form-grid");
        grid.add(rowLabel("Sync every (seconds)"), 0, 0);
        grid.add(interval, 1, 0);
        Button apply = Ui.toolButton("Apply", "check.png", () -> {
            cfg.setSyncIntervalSec(interval.getValue());
            cfg.save();
            SyncService.restart();
            FxUtil.info(getWindow(), "Sync", "Sync interval updated.");
        });
        grid.add(apply, 2, 0);

        Button now = Ui.toolButton("Sync now", "refresh.png", SyncService::syncNow);
        Button full = Ui.toolButton("Full re-download", "database_add.png", () -> {
            if (FxUtil.confirm(getWindow(), "Full re-download",
                    "Discard the local cache and re-download everything from Supabase?\n"
                            + "Changes not yet uploaded will be LOST.")) {
                SyncService.fullResync();
            }
        });
        full.getStyleClass().add("danger");
        issues.getStyleClass().add("hint");
        issues.setWrapText(true);
        box.getChildren().addAll(grid, new HBox(10, now, full), issues);
        return box;
    }

    private VBox buildScannerSection() {
        VBox box = section("Barcode / QR scanner devices");
        GridPane grid = new GridPane();
        grid.getStyleClass().add("form-grid");
        grid.add(rowLabel("Scanner type"), 0, 0);
        grid.add(new VBox(6, sounds, hid, cameraAlwaysOn, showPreview), 1, 0);
        grid.add(rowLabel("Key speed threshold (ms)"), 0, 1);
        grid.add(gap, 1, 1);
        grid.add(rowLabel("Serial port (optional)"), 0, 2);
        HBox portRow = new HBox(10, serialPort, Ui.toolButton("Detect ports", "refresh.png", this::detectPorts));
        portRow.setAlignment(Pos.CENTER_LEFT);
        grid.add(portRow, 1, 2);
        grid.add(rowLabel("Baud rate"), 0, 3);
        grid.add(serialBaud, 1, 3);
        grid.add(rowLabel("Dynamsoft license"), 0, 4);
        HBox keyRow = new HBox(10, dynamsoftKey,
                Ui.toolButton("Save key", "check.png", this::saveDynamsoftKey));
        keyRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(dynamsoftKey, Priority.ALWAYS);
        dynamsoftKey.setPrefWidth(320);
        grid.add(keyRow, 1, 4);
        grid.add(rowLabel("Camera engine"), 0, 5);
        grid.add(cameraEngineInfo, 1, 5);
        grid.add(rowLabel("Camera status"), 0, 6);
        HBox camRow = new HBox(10, serverScanStatus, Ui.toolButton("Diagnose", "refresh.png", this::diagnoseCamera));
        camRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(serverScanStatus, Priority.ALWAYS);
        grid.add(camRow, 1, 6);
        serialPort.setEditable(true);
        serialPort.setPrefWidth(160);
        serialPort.setPromptText("none");
        String current = cfg.serialPort();
        serialPort.getItems().setAll(ScannerService.availableSerialPorts());
        if (!current.isEmpty()) {
            serialPort.setValue(current);
        }

        Button apply = Ui.toolButton("Apply scanner settings", "check.png", () -> {
            cfg.setSoundsEnabled(sounds.isSelected());
            cfg.setHidEnabled(hid.isSelected());
            try {
                cfg.setHidMaxGapMs(Integer.parseInt(gap.getText().trim()));
            } catch (NumberFormatException ignored) {
            }
            cfg.setSerialPort(serialPort.getValue() == null ? "" : serialPort.getValue().trim());
            cfg.setSerialBaud(serialBaud.getValue() == null ? 9600 : serialBaud.getValue());
            cfg.save();
            SoundPlayer.setEnabled(true);
            ScannerService.startSerialReader();
            FxUtil.info(getWindow(), "Scanner", "Scanner settings applied.");
        });
        apply.getStyleClass().add("accent");
        Label hint = new Label("USB scanners work out of the box: no driver needed. "
                + "Serial scanners: pick the COM port after pressing Detect ports.");
        hint.getStyleClass().add("hint");
        hint.setWrapText(true);
        box.getChildren().addAll(grid, apply, hint);
        return box;
    }

    private VBox buildCameraLabSection() {
        VBox box = section("Camera Lab — low-quality webcam tuning (live)");
        Label desc = new Label("Gemini tips: reduce to 640px, boost contrast, ROI crop, TRY_HARDER. "
                + "Move sliders — preview updates instantly. Point a barcode at the camera — if it decodes, the number appears below with a 'found' sound so you can compare to the real barcode.");
        desc.getStyleClass().add("hint");
        desc.setWrapText(true);

        // Init from AppConfig
        labBrightness.setValue(cfg.cameraFilterBrightness());
        labContrast.setValue(cfg.cameraFilterContrast());
        labSaturation.setValue(cfg.cameraFilterSaturation());
        labSharpness.setValue(cfg.cameraFilterSharpness());
        labRoiSize.setValue(cfg.cameraFilterRoiSize());
        labGrayscale.setSelected(cfg.cameraFilterGrayscale());
        labInvert.setSelected(cfg.cameraFilterInvert());
        labRoiEnabled.setSelected(cfg.cameraFilterRoiEnabled());
        labTryHarder.setSelected(cfg.cameraFilterTryHarder());
        labDownscale.setSelected(cfg.cameraFilterDownscale());
        labFormats.setText(cfg.cameraFilterFormats());
        labFormats.setPromptText("CODE_128,QR_CODE,...");
        labFormats.setPrefWidth(320);
        // Value labels
        labBrightnessVal.setText(String.valueOf((int) labBrightness.getValue()));
        labContrastVal.setText((int) labContrast.getValue() + "%");
        labSaturationVal.setText((int) labSaturation.getValue() + "%");
        labSharpnessVal.setText((int) labSharpness.getValue() + "%");
        labRoiSizeVal.setText((int) labRoiSize.getValue() + "%");
        // Volatiles
        labBrightnessV = (int) labBrightness.getValue();
        labContrastV = (int) labContrast.getValue();
        labSaturationV = (int) labSaturation.getValue();
        labSharpnessV = (int) labSharpness.getValue();
        labRoiSizeV = (int) labRoiSize.getValue();
        labGrayscaleV = labGrayscale.isSelected();
        labInvertV = labInvert.isSelected();
        labRoiEnabledV = labRoiEnabled.isSelected();
        labTryHarderV = labTryHarder.isSelected();
        labDownscaleV = labDownscale.isSelected();
        labFormatsV = labFormats.getText();

        // Sliders setup
        for (Slider s : new Slider[]{labBrightness, labContrast, labSaturation, labSharpness, labRoiSize}) {
            s.setShowTickMarks(false);
            s.setShowTickLabels(false);
            s.setPrefWidth(220);
        }
        labBrightness.setMajorTickUnit(50); labBrightness.setBlockIncrement(5);
        labContrast.setMajorTickUnit(50); labContrast.setBlockIncrement(5);
        labSaturation.setMajorTickUnit(50); labSaturation.setBlockIncrement(5);
        labSharpness.setMajorTickUnit(50); labSharpness.setBlockIncrement(5);
        labRoiSize.setMajorTickUnit(10); labRoiSize.setBlockIncrement(5);

        // Listeners update volatiles + labels live
        labBrightness.valueProperty().addListener((o, oldV, v) -> { labBrightnessV = v.intValue(); labBrightnessVal.setText(String.valueOf(labBrightnessV)); });
        labContrast.valueProperty().addListener((o, oldV, v) -> { labContrastV = v.intValue(); labContrastVal.setText(labContrastV + "%"); });
        labSaturation.valueProperty().addListener((o, oldV, v) -> { labSaturationV = v.intValue(); labSaturationVal.setText(labSaturationV + "%"); });
        labSharpness.valueProperty().addListener((o, oldV, v) -> { labSharpnessV = v.intValue(); labSharpnessVal.setText(labSharpnessV + "%"); });
        labRoiSize.valueProperty().addListener((o, oldV, v) -> { labRoiSizeV = v.intValue(); labRoiSizeVal.setText(labRoiSizeV + "%"); });
        labGrayscale.selectedProperty().addListener((o, oldV, v) -> labGrayscaleV = v);
        labInvert.selectedProperty().addListener((o, oldV, v) -> labInvertV = v);
        labRoiEnabled.selectedProperty().addListener((o, oldV, v) -> labRoiEnabledV = v);
        labTryHarder.selectedProperty().addListener((o, oldV, v) -> labTryHarderV = v);
        labDownscale.selectedProperty().addListener((o, oldV, v) -> labDownscaleV = v);
        labFormats.textProperty().addListener((o, oldV, v) -> labFormatsV = v == null ? "" : v.trim());

        GridPane grid = new GridPane();
        grid.getStyleClass().add("form-grid");
        grid.setHgap(10); grid.setVgap(8);
        int r = 0;
        grid.add(rowLabel("Brightness (-100..100)"), 0, r); grid.add(labBrightness, 1, r); grid.add(labBrightnessVal, 2, r++); 
        grid.add(rowLabel("Contrast (50..250%)"), 0, r); grid.add(labContrast, 1, r); grid.add(labContrastVal, 2, r++);
        grid.add(rowLabel("Saturation (0..200%)"), 0, r); grid.add(labSaturation, 1, r); grid.add(labSaturationVal, 2, r++);
        grid.add(rowLabel("Netteté / Sharpness (0..200%)"), 0, r); grid.add(labSharpness, 1, r); grid.add(labSharpnessVal, 2, r++);
        grid.add(rowLabel("ROI size (40..95%)"), 0, r); grid.add(labRoiSize, 1, r); grid.add(labRoiSizeVal, 2, r++);
        grid.add(rowLabel("Options"), 0, r); grid.add(new VBox(6, labGrayscale, labInvert, labRoiEnabled, labTryHarder, labDownscale), 1, r++);
        grid.add(rowLabel("Formats (ZXing)"), 0, r); grid.add(labFormats, 1, r++);

        // Preview area
        labPreview.setFitWidth(420);
        labPreview.setFitHeight(320);
        labPreview.setPreserveRatio(true);
        labPreview.setStyle("-fx-background-color: black; -fx-border-color: #455A64; -fx-border-width: 1;");
        labPreviewStatus.getStyleClass().add("hint");
        labPreviewStatus.setWrapText(true);
        labLastCode.setPromptText("Scanned numbers will appear here — compare to the real barcode");
        labLastCode.setPrefWidth(320);
        labLastCode.setEditable(false);
        labLastEngine.getStyleClass().add("hint");
        labLabInfo.getStyleClass().add("hint");
        labLabInfo.setWrapText(true);
        labLabInfo.setText("Tip: for EAN-13 on shiny/cylindrical bottles, align the barcode horizontally inside the green ROI band — then only that thin strip is sent to ZXing (ultra-fast).");

        VBox previewBox = new VBox(6);
        previewBox.setAlignment(Pos.TOP_LEFT);
        previewBox.getChildren().addAll(new Label("Live preview (with current sliders) + ROI:"), labPreview, labPreviewStatus);

        VBox codeBox = new VBox(6);
        codeBox.setAlignment(Pos.TOP_LEFT);
        codeBox.getChildren().addAll(new Label("Last decoded (live test):"), labLastCode, labLastEngine, labLabInfo);

        HBox previewRow = new HBox(16, previewBox, codeBox);
        previewRow.setAlignment(Pos.TOP_LEFT);

        Button start = Ui.toolButton("Start Lab preview", "camera.png", this::startCameraLab);
        Button stop = Ui.toolButton("Stop", "cross.png", this::stopCameraLab);
        Button save = Ui.toolButton("Save tuning to file", "check.png", this::saveCameraLab);
        save.getStyleClass().add("accent");
        Button reset = Ui.toolButton("Reset defaults", "refresh.png", this::resetCameraLab);
        HBox btns = new HBox(10, start, stop, save, reset);
        btns.setAlignment(Pos.CENTER_LEFT);

        Label hint = new Label("Sliders change the preview instantly. 'Save tuning' writes to config.properties — the main scanner will then use these filters for every scan (decode + preview).");
        hint.getStyleClass().add("hint");
        hint.setWrapText(true);

        box.getChildren().addAll(desc, grid, previewRow, btns, hint);
        return box;
    }

    private void saveDynamsoftKey() {
        String key = dynamsoftKey.getText() == null ? "" : dynamsoftKey.getText().trim();
        cfg.setDynamsoftLicense(key);
        cfg.save();
        Thread.ofVirtual().name("vetms-dynamsoft-save").start(() -> {
            String msg;
            if (key.isBlank()) {
                dev.parent.scanner.DynamsoftLocal.reinit();
                msg = "Key cleared \u2013 free engine (ZXing) is active.\nScanning works without any key.";
            } else {
                boolean jarPresent;
                try {
                    Class.forName("com.dynamsoft.dbr.BarcodeReader");
                    jarPresent = true;
                } catch (ClassNotFoundException e1) {
                    try {
                        Class.forName("com.dynamsoft.barcode.BarcodeReader");
                        jarPresent = true;
                    } catch (ClassNotFoundException e2) {
                        jarPresent = false;
                    }
                }
                if (!jarPresent) {
                    dev.parent.scanner.DynamsoftLocal.reinit();
                    msg = "Key saved \u2013 free engine active and scanning normally.\n\n"
                            + "Premium (Dynamsoft) was not bundled in this installer "
                            + "(download was unavailable when it was built). Your key is SAFE and saved.\n\n"
                            + "How to activate premium later:\n"
                            + "1) Rebuild the installer on a PC with internet (build-exe.bat auto-downloads the jar), or\n"
                            + "2) Drop the Dynamsoft jar (dbr-9.6.40.jar renamed to dbr.jar) into the app's install folder next to vetms-*.jar and restart.\n\n"
                            + "Until then ZXing decodes every barcode/QR just fine.";
                } else {
                    dev.parent.scanner.DynamsoftLocal.reinit();
                    boolean live = dev.parent.scanner.DynamsoftLocal.isAvailable();
                    String engineMsg = dev.parent.scanner.DynamsoftLocal.lastError();
                    if (live) {
                        msg = "Dynamsoft premium scanning is now ACTIVE.\nShow a barcode to the camera!";
                    } else if (engineMsg.toLowerCase().contains("no dynamsoft library")) {
                        msg = "Key saved but premium jar not found \u2013 free engine stays active.\n\n"
                                + "Rebuild the installer with the Dynamsoft jar to unlock premium.";
                    } else {
                        msg = "Key saved but the engine did not start.\n\nReason: " + engineMsg
                                + "\n\nFree engine (ZXing) keeps scanning \u2013 check the key or try a fresh trial key from dynamsoft.com.";
                    }
                }
            }
            String finalMsg = msg;
            javafx.application.Platform.runLater(
                    () -> FxUtil.info(getWindow(), "License key", finalMsg));
            refreshDataLater();
        });
    }

    private void diagnoseCamera() {
        Thread.ofVirtual().name("vetms-camera-diag").start(() -> {
            String diag;
            try {
                boolean probe = dev.parent.scanner.CameraScanService.probeAny();
                String detail = dev.parent.scanner.CameraScanService.engineDetail();
                String probeDetail = dev.parent.scanner.CameraScanService.lastProbeDetail();
                String nativeErr = dev.parent.scanner.CameraScanService.nativesError();
                String status = dev.parent.scanner.CameraScanService.get().statusText();
                boolean running = dev.parent.scanner.CameraScanService.get().running();
                // also try to list webcam-capture devices directly
                String webcamList = "";
                try {
                    // run on platform thread to avoid 'Cannot execute task' on virtual threads
                    var webcams = java.util.concurrent.Executors.newSingleThreadExecutor(r -> { Thread th=new Thread(r); th.setDaemon(true); return th; }).submit(() -> com.github.sarxos.webcam.Webcam.getWebcams()).get(4, java.util.concurrent.TimeUnit.SECONDS);
                    if (webcams == null) webcamList = "null";
                    else {
                        long physical = webcams.stream().filter(w -> { String n=w.getName().toLowerCase(); return !n.contains("virtual") && !n.contains("mirametrix"); }).count();
                        webcamList = webcams.size() + " device(s): " + webcams + " | physical=" + physical;
                        if (physical==0 && webcams.size()>0) webcamList += " (only virtual/Mirametrix found — disable it in Device Manager to expose real camera)";
                    }
                } catch (Throwable t) { webcamList = "Webcam.getWebcams() failed: " + (t.getCause()!=null?t.getCause().getMessage():t.getMessage()); }
                diag = "Running: " + running + "\nStatus: " + status
                        + "\nEngine: " + detail
                        + "\nProbe: " + (probe ? "found" : "none")
                        + "\nProbe detail: " + probeDetail
                        + "\nNative error: " + (nativeErr.isBlank() ? "(none)" : nativeErr)
                        + "\nWebcam-capture list: " + webcamList
                        + "\n\nTips if 'none':\n"
                        + "- Windows Settings -> Privacy & security -> Camera -> Let desktop apps access camera = ON\n"
                        + "- Close Teams / Zoom / Browser that may hold the camera, unplug camera 5s, try again\n"
                        + "- Try 'Camera always on' OFF then ON, or restart the app\n"
                        + "- Check Device Manager -> Cameras has no yellow !";
            } catch (Throwable t) { diag = "Diagnose failed: " + t.getMessage(); }
            String f = diag;
            javafx.application.Platform.runLater(() -> FxUtil.info(getWindow(), "Camera diagnose", f));
        });
    }

    // ---- Camera Lab controls ----
    private void startCameraLab() {
        if (labActive) return;
        labActive = true;
        labPreviewStatus.setText("Lab running — point a barcode at the camera");
        labLastCode.setText("");
        labLastEngine.setText("");
        // Ensure camera is running (platform thread safe)
        try { dev.parent.scanner.CameraScanService.get().start(); } catch (Throwable ignored) {}
        // Frame listener: apply live slider filters and update preview + test-decode
        labFrameListener = jpeg -> {
            // Throttle preview to ~10 fps to avoid saturating FX thread
            long now = System.currentTimeMillis();
            if (now - labLastPreviewMs < 90) return;
            labLastPreviewMs = now;
            Thread.ofVirtual().name("lab-frame").start(() -> {
                try {
                    java.awt.image.BufferedImage raw = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(jpeg));
                    if (raw == null) return;
                    // Preview with ROI rectangle (what user sees)
                    java.awt.image.BufferedImage preview = dev.parent.scanner.ImageFilters.applyWithParams(
                            raw, labBrightnessV, labContrastV, labSaturationV, labSharpnessV,
                            labGrayscaleV, labInvertV, labDownscaleV, labRoiEnabledV, labRoiSizeV, true);
                    javafx.scene.image.Image fx = javafx.embed.swing.SwingFXUtils.toFXImage(preview, null);
                    javafx.application.Platform.runLater(() -> labPreview.setImage(fx));
                    // Decode with ROI crop (what ZXing will actually see) — single filtered image
                    java.awt.image.BufferedImage toDecode = dev.parent.scanner.ImageFilters.applyWithParams(
                            raw, labBrightnessV, labContrastV, labSaturationV, labSharpnessV,
                            labGrayscaleV, labInvertV, labDownscaleV, labRoiEnabledV, labRoiSizeV, false);
                    String code = decodeLabImage(toDecode);
                    if (code != null) {
                        long cnow = System.currentTimeMillis();
                        if (!code.equals(labLastCodeStr) || cnow - labLastCodeAt > 1500) {
                            labLastCodeStr = code; labLastCodeAt = cnow;
                            String fcode = code;
                            javafx.application.Platform.runLater(() -> {
                                labLastCode.setText(fcode);
                                labLastEngine.setText("Found via " + (labTryHarderV ? "TRY_HARDER" : "standard") + " — compare to real barcode");
                            });
                            dev.parent.util.SoundPlayer.playFound();
                        }
                    }
                } catch (Throwable ignored) {}
            });
        };
        labCodeListener = (code, engine) -> {
            // Also listen to global scanner (if lab filters are not used, this shows global decode)
            // But lab frame decode is more accurate for tuning, so we keep both
            javafx.application.Platform.runLater(() -> {
                if (!labLastCode.getText().equals(code)) {
                    labLastCode.setText(code);
                    labLastEngine.setText("Global: " + engine);
                }
            });
        };
        dev.parent.scanner.CameraScanService.get().addFrameListener(labFrameListener);
        dev.parent.scanner.CameraScanService.get().addCodeListener(labCodeListener);
        // Also hook HID wedge for completeness
        dev.parent.scanner.ScannerService.beginExclusiveCapture(code -> {
            if (labActive) javafx.application.Platform.runLater(() -> { labLastCode.setText(code); labLastEngine.setText("Wedge"); dev.parent.util.SoundPlayer.playFound(); });
        });
    }

    private String decodeLabImage(java.awt.image.BufferedImage img) {
        try {
            com.google.zxing.MultiFormatReader reader = new com.google.zxing.MultiFormatReader();
            java.util.Map<com.google.zxing.DecodeHintType,Object> hints = new java.util.EnumMap<>(com.google.zxing.DecodeHintType.class);
            hints.put(com.google.zxing.DecodeHintType.TRY_HARDER, labTryHarderV);
            hints.put(com.google.zxing.DecodeHintType.POSSIBLE_FORMATS, dev.parent.scanner.ImageFilters.parseFormats(labFormatsV));
            com.google.zxing.LuminanceSource src = new com.google.zxing.client.j2se.BufferedImageLuminanceSource(img);
            // Try Hybrid first (better for low quality), then Global
            try {
                return reader.decode(new com.google.zxing.BinaryBitmap(new com.google.zxing.common.HybridBinarizer(src)), hints).getText();
            } catch (com.google.zxing.NotFoundException e) {
                return reader.decode(new com.google.zxing.BinaryBitmap(new com.google.zxing.common.GlobalHistogramBinarizer(src)), hints).getText();
            }
        } catch (Throwable t) { return null; }
    }

    private void stopCameraLab() {
        labActive = false;
        labPreviewStatus.setText("Lab stopped");
        if (labFrameListener != null) { dev.parent.scanner.CameraScanService.get().removeFrameListener(labFrameListener); labFrameListener = null; }
        if (labCodeListener != null) { dev.parent.scanner.CameraScanService.get().removeCodeListener(labCodeListener); labCodeListener = null; }
        try { dev.parent.scanner.ScannerService.endExclusiveCapture(null); } catch (Throwable ignored) {}
    }

    private void saveCameraLab() {
        cfg.setCameraFilterBrightness(labBrightnessV);
        cfg.setCameraFilterContrast(labContrastV);
        cfg.setCameraFilterSaturation(labSaturationV);
        cfg.setCameraFilterSharpness(labSharpnessV);
        cfg.setCameraFilterGrayscale(labGrayscaleV);
        cfg.setCameraFilterInvert(labInvertV);
        cfg.setCameraFilterRoiEnabled(labRoiEnabledV);
        cfg.setCameraFilterRoiSize(labRoiSizeV);
        cfg.setCameraFilterTryHarder(labTryHarderV);
        cfg.setCameraFilterDownscale(labDownscaleV);
        cfg.setCameraFilterFormats(labFormatsV);
        cfg.save();
        javafx.application.Platform.runLater(() -> FxUtil.info(getWindow(), "Camera Lab", "Tuning saved to config.properties\nThe main scanner now uses these filters for every scan — Daily Usage preview and ZXing decode update instantly on the next frame."));
    }

    private void resetCameraLab() {
        labBrightness.setValue(0); labContrast.setValue(100); labSaturation.setValue(100); labSharpness.setValue(0); labRoiSize.setValue(75);
        labGrayscale.setSelected(false); labInvert.setSelected(false); labRoiEnabled.setSelected(false); labTryHarder.setSelected(false); labDownscale.setSelected(false);
        labFormats.setText("CODE_128,CODE_39,EAN_13,EAN_8,UPC_A,UPC_E,QR_CODE");
        // volatiles will update via listeners
        labPreviewStatus.setText("Reset to defaults — press Save to keep");
    }

    private void refreshDataLater() {
        javafx.application.Platform.runLater(this::refreshData);
    }

    private void backendCall(String path) {
        // legacy hook kept for callers with no backend left: harmless no-op
        refreshData();
    }

    private void backend(String path, Object unused) {
        backendCall(path);
    }

    private void detectPorts() {
        serialPort.getItems().setAll(ScannerService.availableSerialPorts());
        if (serialPort.getItems().isEmpty()) {
            serialPort.setPromptText("No serial port detected");
        }
    }

    private VBox buildDataSection() {
        VBox box = section("Local data");
        Label path = new Label("Stored in: " + AppDirs.dataDir());
        path.getStyleClass().add("hint");
        path.setWrapText(true);
        Button export = Ui.toolButton("Export everything to CSV", "database.png", this::exportCsv);
        HBox row = new HBox(10, export);
        box.getChildren().addAll(path, row);
        return box;
    }

    private void exportCsv() {
        try {
            Path dir = AppDirs.dataDir().resolve("exports").resolve(
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
            Files.createDirectories(dir);
            writeMedicinesCsv(dir.resolve("medicines.csv"));
            writeClientsCsv(dir.resolve("clients.csv"));
            writeTransactionsCsv(dir.resolve("transactions.csv"));
            FxUtil.info(getWindow(), "Export done", "CSV files written to:\n" + dir);
        } catch (IOException | UncheckedIOException e) {
            FxUtil.error(getWindow(), "Export failed", e.getMessage());
        }
    }

    private static void writeMedicinesCsv(Path file) throws IOException {
        StringBuilder sb = new StringBuilder("name;barcode;type;stock;open_size;full_size;buy_price;sell_price;expiry;seller\n");
        for (Medicine m : MedicineDao.listAll("")) {
            sb.append(csv(m.name())).append(';').append(csv(m.barcode())).append(';')
                    .append(csv(m.type())).append(';').append(m.stock()).append(';')
                    .append(m.size()).append(';').append(m.fullSize()).append(';')
                    .append(m.buyPrice()).append(';').append(m.sellPrice()).append(';')
                    .append(FxUtil.expiry(m.expiryDate())).append(';').append(csv(m.seller())).append('\n');
        }
        Files.writeString(file, sb);
    }

    private static void writeClientsCsv(Path file) throws IOException {
        StringBuilder sb = new StringBuilder("name;phone;address;description;paid_total;unpaid_total\n");
        for (ClientDao.ClientSummary s : ClientDao.listSummaries("")) {
            Client c = s.client();
            sb.append(csv(c.name())).append(';').append(csv(c.phone())).append(';')
                    .append(csv(c.address())).append(';').append(csv(c.description())).append(';')
                    .append(s.totalPaid()).append(';').append(s.totalUnpaid()).append('\n');
        }
        Files.writeString(file, sb);
    }

    private static void writeTransactionsCsv(Path file) throws IOException {
        StringBuilder sb = new StringBuilder("date;time;client;medicine;quantity;amount;type;description;paid\n");
        for (TransactionDao.DaySummary day : TransactionDao.dailySummary()) {
            for (SaleTransaction t : TransactionDao.listByDate(day.date())) {
                sb.append(FxUtil.date(t.createdAt().toLocalDate())).append(';')
                        .append(FxUtil.time(t.createdAt())).append(';')
                        .append(csv(t.clientName())).append(';')
                        .append(csv(t.medicineName() == null ? "" : t.medicineName())).append(';')
                        .append(t.quantity()).append(';').append(t.amount()).append(';')
                        .append(csv(t.type())).append(';').append(csv(t.description())).append(';')
                        .append(t.paid() ? "yes" : "no").append('\n');
            }
        }
        Files.writeString(file, sb);
    }

    private static String csv(String s) {
        if (s == null) {
            return "";
        }
        if (s.contains(";") || s.contains("\"") || s.contains("\n")) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }

    private VBox buildAboutSection() {
        VBox box = section("About");
        Label about = new Label("VetCustomerManager " + VetApp.VERSION + "\n"
                + "Offline-first veterinary customer manager.\n"
                + "Data: your Supabase project (PostgreSQL) + local SQLite cache.\n"
                + "Scanning: USB keyboard scanners, serial scanners, camera (ZXing).");
        about.getStyleClass().add("hint");
        about.setWrapText(true);
        box.getChildren().add(about);
        return box;
    }

    // ---------------------------------------------------------------- helpers

    private VBox section(String title) {
        VBox box = new VBox(6);
        box.getStyleClass().add("card");
        box.setPadding(new Insets(16));
        VBox.setMargin(box, new Insets(10, 0, 10, 0));
        Label t = new Label(title);
        t.getStyleClass().add("card-title");
        box.getChildren().add(t);
        return box;
    }

    private Label rowLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("form-label");
        return l;
    }

    private javafx.stage.Window getWindow() {
        return getScene() == null ? MainWindow.get().stage() : getScene().getWindow();
    }

    @Override
    public String title() {
        return "Settings";
    }

    @Override
    public void refreshData() {
        Bg.run("camera-status", () -> {
                        try {
                            var cam = dev.parent.scanner.CameraScanService.get();
                            boolean running = cam.running();
                            String message = cam.statusText();
                            boolean dynamsoft = !cfg.dynamsoftLicense().isEmpty()
                                    && dev.parent.scanner.DynamsoftLocal.isAvailable();
                            String dynamsoftMsg = dev.parent.scanner.DynamsoftLocal.lastError();
                            javafx.application.Platform.runLater(() -> {
                                String base = running
                                        ? "Engine: built-in - " + message
                                        + "  [decode: " + (dynamsoft ? "dynamsoft" : "zxing") + "]"
                                        : "Not scanning - " + message
                                        + " (toggle \"camera always on\" or check Settings)";
                                String dyn = cfg.dynamsoftLicense().isEmpty() ? ""
                                        : dynamsoft
                                        ? "   |   Dynamsoft PREMIUM: active"
                                        : "   |   Dynamsoft: not active"
                                        + (dynamsoftMsg.isBlank() ? "" : " - " + dynamsoftMsg);
                                serverScanStatus.setText(base + dyn);
                            });
                        } catch (Throwable t) {
                            javafx.application.Platform.runLater(() -> serverScanStatus.setText(
                                    "Camera engine unavailable: " + t.getMessage()));
                        }
                    });
        SyncService.Status s = SyncService.status();
        if (s.state() == SyncService.State.ONLINE && " ".equals(dbStatus.getText())) {
            dbStatus.setStyle("-fx-text-fill:#66BB6A;");
            dbStatus.setText("Connected and in sync.");
        }
        if (!SyncService.lastIssues().isEmpty()) {
            issues.setText("Attention needed:\n- " + String.join("\n- ", SyncService.lastIssues()));
        } else {
            issues.setText("");
        }
    }
}
