package dev.parent.ui;

import dev.parent.db.AppointmentDao;
import dev.parent.db.ClientDao;
import dev.parent.model.Appointment;
import dev.parent.model.Client;
import dev.parent.util.FxUtil;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.time.LocalDate;
import java.util.Optional;

/** Client appointments with done tracking. */
final class AppointmentsView extends MainWindow.BaseView {

    private final TableView<Appointment> table = new TableView<>();
    private AppointmentDao.Filter filter = AppointmentDao.Filter.PENDING;

    AppointmentsView() {
        Button add = Ui.toolButton("Add", "add.png", () -> {
            AppointmentDialog.show(getWindow());
            refreshData();
        });
        Button toggle = Ui.toolButton("Toggle done", "check.png", () -> selected().ifPresent(a -> {
            dev.parent.util.Bg.load(() -> {
                        AppointmentDao.setDone(a.uuid(), !a.done());
                        return null;
                    },
                    v -> refreshData());
        }));
        Button del = Ui.toolButton("Delete", "delete.png", () -> selected().ifPresent(a -> {
            if (FxUtil.confirm(getWindow(), "Delete appointment",
                    "Delete the appointment of " + a.clientName() + " on " + FxUtil.date(a.date()) + "?")) {
                dev.parent.util.Bg.load(() -> {
                            AppointmentDao.softDelete(a.uuid());
                            return null;
                        },
                        v -> refreshData());
            }
        }));
        del.getStyleClass().add("danger");
        Button refresh = Ui.toolButton("Refresh", "refresh.png", this::refreshData);

        ComboBox<AppointmentDao.Filter> filterBox = new ComboBox<>(
                javafx.collections.FXCollections.observableArrayList(AppointmentDao.Filter.values()));
        filterBox.getSelectionModel().select(AppointmentDao.Filter.PENDING);
        filterBox.setOnAction(e -> {
            filter = filterBox.getValue();
            refreshData();
        });

        setTop(Ui.toolbar(add, toggle, del, refresh, FxUtil.hSpacer(),
                new Label("Show:"), filterBox));

        table.getColumns().setAll(
                Ui.textCol("Date", a -> FxUtil.date(a.date()), 130),
                Ui.textCol("Client", Appointment::clientName, 240),
                Ui.boolCol("Done", Appointment::done, 90),
                Ui.textCol("Description", a -> a.description() == null ? "" : a.description(), 420));
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("No appointment in this view"));
        table.setRowFactory(tv -> new TableRow<>() {
            @Override
            protected void updateItem(Appointment a, boolean empty) {
                super.updateItem(a, empty);
                getStyleClass().removeAll("row-warn", "row-danger");
                if (a == null || empty || a.done()) {
                    return;
                }
                if (a.date().isBefore(LocalDate.now())) {
                    getStyleClass().add("row-danger"); // overdue
                } else if (a.date().equals(LocalDate.now())) {
                    getStyleClass().add("row-warn"); // today
                }
            }
        });
        table.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                selected().ifPresent(a -> {
                    dev.parent.util.Bg.load(() -> {
                                AppointmentDao.setDone(a.uuid(), !a.done());
                                return null;
                            },
                            v -> refreshData());
                });
            }
        });
        setCenter(table);
        setMargin(table, new javafx.geometry.Insets(0, 10, 10, 10));
    }

    private Optional<Appointment> selected() {
        Appointment a = table.getSelectionModel().getSelectedItem();
        if (a == null) {
            FxUtil.error(getWindow(), "Nothing selected", "Select an appointment first.");
        }
        return Optional.ofNullable(a);
    }

    private Window getWindow() {
        return getScene() == null ? MainWindow.get().stage() : getScene().getWindow();
    }

    @Override
    public String title() {
        return "Appointments";
    }

    @Override
    public void refreshData() {
        AppointmentDao.Filter f = filter;
        dev.parent.util.Bg.load(() -> AppointmentDao.list(f),
                rows -> table.getItems().setAll(rows));
    }
}

/** New appointment dialog. */
final class AppointmentDialog {

    private AppointmentDialog() {
    }

    static void show(Window owner) {
        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.initOwner(owner);
        dialog.setTitle("New appointment");
        FxUtil.setAppIcon(dialog);

        ComboBox<Client> client = new ComboBox<>();
        dev.parent.util.Bg.load(ClientDao::listAll,
                rows -> client.getItems().setAll(rows));
        client.setMaxWidth(Double.MAX_VALUE);
        client.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(Client c) {
                return c == null ? "" : c.name();
            }

            @Override
            public Client fromString(String s) {
                return null;
            }
        });
        DatePicker date = new DatePicker(LocalDate.now());
        TextArea description = new TextArea();
        description.setPromptText("Reason of the visit, animals concerned...");
        description.setPrefRowCount(4);

        GridPane grid = new GridPane();
        grid.getStyleClass().add("form-grid");
        grid.add(rowLabel("Client *"), 0, 0);
        grid.add(client, 1, 0);
        grid.add(rowLabel("Date *"), 0, 1);
        grid.add(date, 1, 1);
        grid.add(rowLabel("Description"), 0, 2);
        grid.add(description, 1, 2);

        Button save = new Button("Add appointment");
        save.getStyleClass().add("accent");
        save.setOnAction(e -> {
            if (client.getValue() == null) {
                FxUtil.error(dialog, "Missing client", "Choose the client first.");
                return;
            }
            if (date.getValue() == null) {
                FxUtil.error(dialog, "Missing date", "Choose the appointment date.");
                return;
            }
            AppointmentDao.insert(new Appointment(null, client.getValue().uuid(), null,
                    date.getValue(), false,
                    description.getText() == null || description.getText().isBlank()
                            ? "[NO DESCRIPTION]" : description.getText().trim(),
                    false, null));
            dialog.close();
        });
        Button cancel = new Button("Cancel");
        cancel.setOnAction(e -> dialog.close());
        HBox buttons = new HBox(10, FxUtil.hSpacer(), cancel, save);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        buttons.setPadding(new javafx.geometry.Insets(6, 16, 16, 16));

        VBox root = new VBox(grid, buttons);
        Ui.setScene(dialog, root, 540, 420);
        dialog.showAndWait();
    }

    private static Label rowLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("form-label");
        return l;
    }
}
