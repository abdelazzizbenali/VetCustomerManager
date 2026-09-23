package dev.parent.ui;

import dev.parent.db.ClientDao;
import dev.parent.model.Client;
import dev.parent.util.Bg;
import dev.parent.util.FxUtil;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.Optional;

/**
 * Fast "who is this cart for?" picker: a search box and a big client list -
 * type to filter, Enter or double-click to choose. If the name is new,
 * "Create & open" registers the client instantly and selects it, so an
 * unknown walk-in never blocks the queue.
 */
final class ClientPickerDialog {

    private final Stage stage = new Stage();
    private final ListView<Client> list = new ListView<>();
    private final TextField search = new TextField();
    private final Button useBtn = new Button("Open cart for this client");
    private final Button createBtn = new Button("Create & open");
    private volatile Client chosen;

    private ClientPickerDialog(Window owner) {
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Which client?");
        FxUtil.setAppIcon(stage);

        search.setPromptText("Type a name to search - Enter selects, or create a new client below");
        search.setMaxWidth(Double.MAX_VALUE);

        list.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(Client c, boolean empty) {
                super.updateItem(c, empty);
                if (empty || c == null) {
                    setText(null);
                    return;
                }
                setText(c.name() + (c.phone() == null || c.phone().isBlank()
                        ? "" : "   (" + c.phone() + ")"));
            }
        });
        list.setPrefHeight(320);
        VBox.setVgrow(list, Priority.ALWAYS);

        useBtn.getStyleClass().add("accent");
        useBtn.setMaxWidth(Double.MAX_VALUE);
        useBtn.setDefaultButton(true);
        useBtn.setOnAction(e -> useSelection());

        createBtn.setMaxWidth(Double.MAX_VALUE);
        createBtn.setOnAction(e -> createAndUse());

        search.textProperty().addListener((obs, o, text) -> reload(text));
        search.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.TAB) {
                targetSelection();
                e.consume();
            }
        });
        list.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                useSelection();
            }
        });
        list.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                useSelection();
            }
        });

        VBox root = new VBox(10,
                new Label("Pick the client this cart belongs to:"), search,
                list, useBtn,
                new Label("Not in the list? The typed name becomes a new client:"),
                createBtn);
        root.setPadding(new Insets(14));
        Ui.setScene(stage, root, 460, 560);

        reload("");
        stage.setOnShown(e -> search.requestFocus());
    }

    /** Shows the dialog modally and returns the picked (or freshly created) client. */
    static Optional<Client> show(Window owner) {
        ClientPickerDialog d = new ClientPickerDialog(owner);
        d.stage.showAndWait();
        return Optional.ofNullable(d.chosen);
    }

    private void targetSelection() {
        if (list.getSelectionModel().getSelectedItem() == null
                && !list.getItems().isEmpty()) {
            list.getSelectionModel().selectFirst();
        }
        if (list.getSelectionModel().getSelectedItem() != null) {
            useSelection();
        }
    }

    private void useSelection() {
        Client c = list.getSelectionModel().getSelectedItem();
        if (c == null) {
            if (!list.getItems().isEmpty()) {
                list.getSelectionModel().selectFirst();
                return;
            }
            createAndUse();
            return;
        }
        chosen = c;
        stage.close();
    }

    private void createAndUse() {
        String name = search.getText() == null ? "" : search.getText().trim();
        if (name.isEmpty()) {
            FxUtil.error(stage, "Name needed",
                    "Type the new client's name in the search box first.");
            return;
        }
        createBtn.setDisable(true);
        Bg.load(() -> ClientDao.insert(new Client(null, name, "", "", "[CREATED AT POS]", false, null)),
                created -> {
                    chosen = created;
                    stage.close();
                });
    }

    private void reload(String filter) {
        Bg.load(() -> ClientDao.listAll().stream()
                        .filter(c -> filter == null || filter.isBlank()
                                || c.name().toLowerCase().contains(filter.toLowerCase()))
                        .limit(100)
                        .toList(),
                rows -> {
                    list.getItems().setAll(rows);
                    if (!rows.isEmpty()) {
                        list.getSelectionModel().selectFirst();
                    }
                });
    }
}
