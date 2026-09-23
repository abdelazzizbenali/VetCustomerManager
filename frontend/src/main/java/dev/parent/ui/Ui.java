package dev.parent.ui;

import dev.parent.util.FxUtil;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.layout.HBox;

import java.util.function.Function;
import java.util.function.ToDoubleFunction;

/** Tiny builders shared by the screens so every table & toolbar looks the same. */
final class Ui {

    private Ui() {
    }

    static HBox toolbar(Node... children) {
        HBox box = new HBox(10, children);
        box.getStyleClass().add("toolbar");
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    static Button toolButton(String text, String icon, Runnable action) {
        Button b = new Button(text);
        b.setGraphic(FxUtil.icon(icon, 20));
        b.setOnAction(e -> action.run());
        return b;
    }

    static <S, T> TableColumn<S, T> col(String title, Function<S, T> extractor, int width) {
        TableColumn<S, T> c = new TableColumn<>(title);
        c.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(extractor.apply(cd.getValue())));
        c.setPrefWidth(width);
        return c;
    }

    static <S> TableColumn<S, String> textCol(String title, Function<S, String> extractor, int width) {
        return col(title, extractor, width);
    }

    static <S> TableColumn<S, String> moneyCol(String title, ToDoubleFunction<S> extractor, int width) {
        TableColumn<S, String> c = new TableColumn<>(title);
        c.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(FxUtil.money(extractor.applyAsDouble(cd.getValue()))));
        c.setCellFactory(tc -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(item);
                    setAlignment(Pos.CENTER_RIGHT);
                }
            }
        });
        c.setPrefWidth(width);
        c.setStyle("-fx-alignment: CENTER-RIGHT;");
        return c;
    }

    static <S> TableColumn<S, String> boolCol(String title, Function<S, Boolean> extractor, int width) {
        TableColumn<S, String> c = new TableColumn<>(title);
        c.setCellValueFactory(cd ->
                new ReadOnlyObjectWrapper<>(Boolean.TRUE.equals(extractor.apply(cd.getValue())) ? "YES" : "no"));
        c.setCellFactory(tc -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item);
                    setStyle("YES".equals(item)
                            ? "-fx-text-fill:#66BB6A; -fx-font-weight:bold;"
                            : "-fx-text-fill:#EF9A9A;");
                    setAlignment(Pos.CENTER);
                }
            }
        });
        c.setPrefWidth(width);
        return c;
    }

    static Label sectionTitle(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("section-title");
        return l;
    }

    static Label stat(String value, String styleClass) {
        Label l = new Label(value);
        l.getStyleClass().addAll("stat-value", styleClass);
        return l;
    }

    /** Builds a themed scene for a dialog/auxiliary stage and centers it. */
    static void setScene(javafx.stage.Stage stage, javafx.scene.Parent content,
                         double width, double height) {
        javafx.scene.Scene scene = new javafx.scene.Scene(content, width, height);
        var css = Ui.class.getResource("/dev/parent/res/theme.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }
        content.getStyleClass().add("root-pane");
        stage.setScene(scene);
        stage.centerOnScreen();
    }
}
