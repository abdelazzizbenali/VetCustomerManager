package dev.parent.util;

import dev.parent.config.Log;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

/** Small JavaFX helpers shared by every screen. */
public final class FxUtil {

    public static final String APP_ICON = "/dev/parent/res/app.png";

    private static final String RES = "/dev/parent/res/";
    private static final NumberFormat MONEY = NumberFormat.getNumberInstance(Locale.FRANCE);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    static {
        MONEY.setMinimumFractionDigits(2);
        MONEY.setMaximumFractionDigits(2);
    }

    private FxUtil() {
    }

    // ------------------------------------------------------------------ images

    public static Image image(String name) {
        var in = FxUtil.class.getResourceAsStream(RES + name);
        if (in == null) {
            Log.warn("Missing icon resource: " + name);
            return new Image(FxUtil.class.getResourceAsStream("/dev/parent/res/home.png"));
        }
        return new Image(in);
    }

    public static ImageView icon(String name, int size) {
        ImageView iv = new ImageView(image(name));
        iv.setFitWidth(size);
        iv.setFitHeight(size);
        iv.setPreserveRatio(true);
        return iv;
    }

    public static void setAppIcon(Stage stage) {
        try (var in = FxUtil.class.getResourceAsStream(APP_ICON)) {
            if (in != null) {
                stage.getIcons().add(new Image(in));
            }
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------- money

    public static String money(double amount) {
        return MONEY.format(amount) + " DA";
    }

    public static String qty(double q) {
        if (q == Math.floor(q)) {
            return String.valueOf((long) q);
        }
        return String.format(Locale.US, "%.2f", q);
    }

    public static String time(OffsetDateTime t) {
        return t == null ? "" : t.format(TIME);
    }

    public static String date(LocalDate d) {
        return d == null ? "" : d.format(DATE);
    }

    public static String dateTime(Instant i) {
        if (i == null) {
            return "never";
        }
        return LocalDateTime.ofInstant(i, ZoneId.systemDefault()).format(DATE_TIME);
    }

    public static String expiry(LocalDate d) {
        if (d == null || d.equals(dev.parent.model.Medicine.NO_EXPIRY)) {
            return "-";
        }
        return date(d);
    }

    // ------------------------------------------------------------------ alerts

    public static void error(Window owner, String title, String message) {
        alert(Alert.AlertType.ERROR, owner, title, message);
    }

    public static void info(Window owner, String title, String message) {
        alert(Alert.AlertType.INFORMATION, owner, title, message);
    }

    public static boolean confirm(Window owner, String title, String message) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.OK, ButtonType.CANCEL);
        a.setTitle(title);
        a.setHeaderText(title);
        style(a);
        if (owner != null) {
            a.initOwner(owner);
        }
        Optional<ButtonType> r = a.showAndWait();
        return r.isPresent() && r.get() == ButtonType.OK;
    }

    private static void alert(Alert.AlertType type, Window owner, String title, String message) {
        Platform.runLater(() -> {
            Alert a = new Alert(type, message);
            a.setTitle(title);
            a.setHeaderText(title);
            style(a);
            if (owner != null) {
                a.initOwner(owner);
            }
            a.show();
        });
    }

    public static void fatal(Throwable t) {
        Log.error("Unexpected error", t);
        Platform.runLater(() -> {
            Alert a = new Alert(Alert.AlertType.ERROR);
            a.setTitle("Unexpected error");
            a.setHeaderText("Something went wrong: " + t.getMessage());
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            TextArea area = new TextArea(sw.toString());
            area.setEditable(false);
            area.setMaxWidth(Double.MAX_VALUE);
            area.setMaxHeight(Double.MAX_VALUE);
            GridPane.setVgrow(area, Priority.ALWAYS);
            GridPane.setHgrow(area, Priority.ALWAYS);
            GridPane content = new GridPane();
            content.setMaxWidth(Double.MAX_VALUE);
            content.add(new Label("The full error report:"), 0, 0);
            content.add(area, 0, 1);
            a.getDialogPane().setExpandableContent(content);
            style(a);
            a.show();
        });
    }

    private static void style(Alert a) {
        DialogPane pane = a.getDialogPane();
        var css = FxUtil.class.getResource("/dev/parent/res/theme.css");
        if (css != null) {
            pane.getStylesheets().add(css.toExternalForm());
            pane.getStyleClass().add("root-pane");
        }
    }

    public static Node hSpacer() {
        Label l = new Label();
        l.setMaxWidth(Double.MAX_VALUE);
        javafx.scene.layout.HBox.setHgrow(l, Priority.ALWAYS);
        return l;
    }
}
