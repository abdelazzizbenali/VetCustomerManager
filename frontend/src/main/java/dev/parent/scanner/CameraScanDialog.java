package dev.parent.scanner;

import dev.parent.net.ScanRouter;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.ByteArrayInputStream;
import java.util.function.Consumer;

/**
 * Modal camera capture dialog, fed by the built-in local camera engine:
 * <ul>
 *   <li>the always-on camera keeps scanning - the window appears INSTANTLY
 *       and shows the live preview (no hardware open/close, no HTTP)</li>
 *   <li>while open, the next scan (camera OR usb/serial wedge) is exclusively
 *       delivered here, then the dialog closes</li>
 * </ul>
 */
public final class CameraScanDialog {

    private final Stage stage = new Stage();
    private final ImageView preview = new ImageView();
    private final Label status = new Label("Starting the camera preview...");
    private volatile boolean delivered;
    private volatile boolean alive = true;

    private Consumer<String> captureListener;
    private ScannerService.ScanListener hidCapture;
    private final ScanRouter.StatusListener statusListener = (running, msg) ->
            status.setText(running
                    ? "Show the barcode / QR to the camera - reading is automatic"
                    : msg);
    private final CameraScanService.FrameListener frameListener = jpeg -> {
        Image img = new Image(new ByteArrayInputStream(jpeg));
        if (!img.isError()) {
            Platform.runLater(() -> preview.setImage(img));
        }
    };

    public CameraScanDialog(Window owner, Consumer<String> onCode) {
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Camera scan");
        preview.setFitWidth(520);
        preview.setFitHeight(360);
        preview.setPreserveRatio(true);
        preview.setStyle("-fx-background-color: black;");

        Button close = new Button("Close");
        close.setOnAction(e -> stage.close());
        HBox bottom = new HBox(10, status, close);
        bottom.setAlignment(Pos.CENTER_LEFT);
        bottom.setPadding(new Insets(10));
        HBox.setHgrow(status, Priority.ALWAYS);
        status.setStyle("-fx-text-fill: #CFD8DC;");
        status.setWrapText(true);

        BorderPane root = new BorderPane(preview);
        root.setBottom(bottom);
        root.setStyle("-fx-background-color: #1E272C;");
        stage.setScene(new Scene(root, 560, 440));
        stage.setOnCloseRequest(e -> unhook());

        stage.setOnShown(e -> hook(onCode));
    }

    private void hook(Consumer<String> onCode) {
        ScanRouter.get().addStatusListener(statusListener);
        ScanRouter.get().refreshStatus();
        captureListener = code -> {
            if (delivered) {
                return;
            }
            delivered = true;
            try {
                onCode.accept(code);
            } finally {
                Platform.runLater(stage::close);
            }
        };
        // two exclusive paths: camera events (local engine) + usb/serial wedge
        ScanRouter.get().beginCapture(captureListener);
        hidCapture = code -> {
            if (captureListener != null) {
                captureListener.accept(code);
            }
        };
        ScannerService.beginExclusiveCapture(hidCapture);
        CameraScanService.get().addFrameListener(frameListener);
    }

    private void unhook() {
        alive = false;
        if (captureListener != null) {
            ScanRouter.get().endCapture(captureListener);
            captureListener = null;
        }
        if (hidCapture != null) {
            ScannerService.endExclusiveCapture(hidCapture);
            hidCapture = null;
        }
        CameraScanService.get().removeFrameListener(frameListener);
    }

    public void show() {
        stage.show();
    }
}
