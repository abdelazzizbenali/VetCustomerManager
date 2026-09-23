package dev.parent.ui;

import dev.parent.db.AnimalDao;
import dev.parent.db.ClientDao;
import dev.parent.db.TransactionDao;
import dev.parent.model.Animal;
import dev.parent.model.Client;
import dev.parent.model.SaleTransaction;
import dev.parent.util.FxUtil;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Spinner;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.List;
import java.util.Optional;

/** Clients list with the full client dossier: profile, animals, money history. */
final class ClientsView extends MainWindow.BaseView {

    private final TableView<ClientDao.ClientSummary> table = new TableView<>();
    private final TextField search = new TextField();

    ClientsView() {
        Button add = Ui.toolButton("Add", "add.png", () -> openClientEditor(null));
        Button edit = Ui.toolButton("Edit", "edit.png", () -> selectedSummary().ifPresent(s -> openClientEditor(s.client())));
        Button del = Ui.toolButton("Delete", "delete.png", this::deleteSelected);
        del.getStyleClass().add("danger");
        Button refresh = Ui.toolButton("Refresh", "refresh.png", this::refreshData);
        search.setPromptText("Search by name or phone...");
        search.getStyleClass().add("search-field");
        search.textProperty().addListener((obs, o, n) -> refreshData());
        setTop(Ui.toolbar(add, edit, del, refresh, FxUtil.hSpacer(), new Label("Search:"), search));

        table.getColumns().setAll(
                Ui.textCol("Name", s -> s.client().name(), 200),
                Ui.textCol("Phone", s -> empty(s.client().phone()), 130),
                Ui.textCol("Animals", ClientDao.ClientSummary::animalsSummary, 300),
                Ui.moneyCol("Total paid", ClientDao.ClientSummary::totalPaid, 140),
                Ui.moneyCol("Unpaid (debt)", ClientDao.ClientSummary::totalUnpaid, 160),
                Ui.textCol("Description", s -> empty(s.client().description()), 220));
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("No clients yet - add your first client with the Add button."));
        table.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                selectedSummary().ifPresent(s -> openClientEditor(s.client()));
            }
        });
        setCenter(table);
        setMargin(table, new Insets(0, 10, 10, 10));
    }

    private static String empty(String s) {
        return s == null ? "" : s;
    }

    private Optional<ClientDao.ClientSummary> selectedSummary() {
        ClientDao.ClientSummary s = table.getSelectionModel().getSelectedItem();
        if (s == null) {
            FxUtil.error(getWindow(), "Nothing selected", "Please select a client first.");
        }
        return Optional.ofNullable(s);
    }

    private void deleteSelected() {
        List<ClientDao.ClientSummary> sel = table.getSelectionModel().getSelectedItems();
        if (sel.isEmpty()) {
            FxUtil.error(getWindow(), "Nothing selected", "Select at least one client.");
            return;
        }
        if (!FxUtil.confirm(getWindow(), "Delete clients",
                "Delete " + sel.size() + " client(s) and their animals?\n"
                        + "(their transaction history is kept for accounting)")) {
            return;
        }
        dev.parent.util.Bg.load(() -> {
                    sel.forEach(s -> ClientDao.softDelete(s.client().uuid()));
                    return null;
                },
                v -> refreshData());
    }

    private void openClientEditor(Client client) {
        ClientDialog.show(getWindow(), client);
        refreshData();
    }

    private Window getWindow() {
        return getScene() == null ? MainWindow.get().stage() : getScene().getWindow();
    }

    @Override
    public String title() {
        return "Clients";
    }

    @Override
    public void refreshData() {
        String filter = search.getText();
        dev.parent.util.Bg.load(() -> ClientDao.listSummaries(filter),
                rows -> table.getItems().setAll(rows));
    }
}

/** Transactions + money totals loaded together off the FX thread. */
record TxLoad(java.util.List<dev.parent.model.SaleTransaction> rows,
              dev.parent.db.ClientDao.MoneyTotals money) {
}

/** Full client dossier dialog: info + animals (pets & livestock) + transactions. */
final class ClientDialog {

    private ClientDialog() {
    }

    static void show(Window owner, Client existing) {
        Client current = existing == null
                ? new Client(null, "", "", "", "", false, null)
                : existing;
        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.initOwner(owner);
        dialog.setTitle(existing == null ? "New client" : "Client: " + existing.name());
        FxUtil.setAppIcon(dialog);

        // ---------------------------------------------------------------- info
        TextField name = new TextField(current.name());
        name.setPromptText("Full name *");
        TextField phone = new TextField(current.phone());
        phone.setPromptText("Phone number");
        TextField address = new TextField(current.address());
        address.setPromptText("Address / village");
        TextArea desc = new TextArea(
                "[NO DESCRIPTION]".equals(current.description()) ? "" : current.description());
        desc.setPromptText("Notes about this client");
        desc.setPrefRowCount(3);

        GridPane infoForm = new GridPane();
        infoForm.getStyleClass().add("form-grid");
        infoForm.add(rowLabel("Name *"), 0, 0);
        infoForm.add(name, 1, 0);
        infoForm.add(rowLabel("Phone"), 0, 1);
        infoForm.add(phone, 1, 1);
        infoForm.add(rowLabel("Address"), 0, 2);
        infoForm.add(address, 1, 2);
        infoForm.add(rowLabel("Notes"), 0, 3);
        infoForm.add(desc, 1, 3);
        VBox infoBox = new VBox(infoForm);

        // ------------------------------------------------------------- animals
        VBox animalsBox = new VBox(8);
        animalsBox.setPadding(new Insets(10));
        TableView<Animal> animalsTable = new TableView<>();
        animalsTable.getColumns().setAll(
                Ui.textCol("Kind", a -> a.isPet() ? "Registered pet" : "Livestock", 120),
                Ui.textCol("Species", Animal::species, 100),
                Ui.textCol("Name", a -> a.isPet() ? empty(a.name()) : "-", 130),
                Ui.textCol("Breed", a -> a.isPet() ? empty(a.breed()) : "-", 120),
                Ui.textCol("Gender", a -> a.isPet() ? empty(a.gender()) : "-", 80),
                Ui.textCol("Birth date", a -> a.isPet() && a.birthDate() != null
                        ? FxUtil.date(a.birthDate()) : "-", 100),
                Ui.col("Quantity", Animal::quantity, 80),
                Ui.textCol("Notes", a -> empty(a.notes()), 200));
        animalsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        animalsTable.setPlaceholder(new Label("No animals registered for this client yet"));
        Runnable reloadAnimals = () -> {
            if (current.uuid() != null) {
                String uuid = current.uuid();
                dev.parent.util.Bg.load(() -> AnimalDao.listByClient(uuid),
                        rows -> animalsTable.getItems().setAll(rows));
            }
        };
        Button addAnimal = Ui.toolButton("Add animal", "add.png", () -> {
            if (current.uuid() == null) {
                FxUtil.error(dialog, "Save first", "Save the client before adding animals.");
                return;
            }
            AnimalDialog.show(dialog, current.uuid(), null);
            reloadAnimals.run();
        });
        Button editAnimal = Ui.toolButton("Edit", "edit.png", () -> {
            Animal a = animalsTable.getSelectionModel().getSelectedItem();
            if (a == null) {
                FxUtil.error(dialog, "Nothing selected", "Select an animal row first.");
                return;
            }
            AnimalDialog.show(dialog, current.uuid(), a);
            reloadAnimals.run();
        });
        Button removeAnimal = Ui.toolButton("Remove", "delete.png", () -> {
            Animal a = animalsTable.getSelectionModel().getSelectedItem();
            if (a == null) {
                FxUtil.error(dialog, "Nothing selected", "Select an animal row first.");
                return;
            }
            if (FxUtil.confirm(dialog, "Remove animal", "Remove " + a.shortLabel() + "?")) {
                AnimalDao.softDelete(a.uuid());
                reloadAnimals.run();
            }
        });
        removeAnimal.getStyleClass().add("danger");
        HBox animalButtons = new HBox(10, addAnimal, editAnimal, removeAnimal);
        animalsBox.getChildren().addAll(animalButtons, animalsTable);
        VBox.setVgrow(animalsTable, Priority.ALWAYS);
        reloadAnimals.run();

        // ---------------------------------------------------------- transactions
        VBox txBox = new VBox(8);
        txBox.setPadding(new Insets(10));
        TableView<SaleTransaction> txTable = new TableView<>();
        txTable.getColumns().setAll(
                Ui.textCol("Date", t -> FxUtil.date(t.createdAt().toLocalDate()), 100),
                Ui.textCol("Time", t -> FxUtil.time(t.createdAt()), 70),
                Ui.textCol("Medicine / service",
                        t -> t.medicineName() == null || t.medicineName().isBlank() ? "-" : t.medicineName(), 160),
                Ui.col("Qty", t -> FxUtil.qty(t.quantity()), 60),
                Ui.moneyCol("Amount", SaleTransaction::amount, 110),
                Ui.textCol("Type", SaleTransaction::type, 110),
                Ui.boolCol("Paid", SaleTransaction::paid, 70));
        txTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        txTable.setPlaceholder(new Label("No transactions for this client yet"));
        Label totals = new Label();
        totals.getStyleClass().add("stat-sub");
        Runnable reloadTx = () -> {
            if (current.uuid() != null) {
                String uuid = current.uuid();
                dev.parent.util.Bg.load(() -> {
                            java.util.List<dev.parent.model.SaleTransaction> rows =
                                    TransactionDao.listByClient(uuid);
                            ClientDao.MoneyTotals t = ClientDao.moneyTotals(uuid);
                            return new TxLoad(rows, t);
                        },
                        res -> {
                            txTable.getItems().setAll(res.rows());
                            totals.setText("Paid: " + FxUtil.money(res.money().paid())
                                    + "    |    Still owed: " + FxUtil.money(res.money().unpaid()));
                        });
            }
        };
        Button markPaid = Ui.toolButton("Set paid", "coin.png", () -> {
            List<SaleTransaction> unpaid = txTable.getItems().stream().filter(t -> !t.paid()).toList();
            if (unpaid.isEmpty()) {
                FxUtil.info(dialog, "Nothing to do", "Every transaction of this client is already paid.");
                return;
            }
            dev.parent.util.Bg.load(() -> {
                        TransactionDao.setPaid(unpaid.stream().map(SaleTransaction::uuid).toList(), true);
                        return null;
                    },
                    v -> reloadTx.run());
        });
        HBox txButtons = new HBox(10, markPaid, FxUtil.hSpacer(), totals);
        txButtons.setAlignment(Pos.CENTER_LEFT);
        txBox.getChildren().addAll(txButtons, txTable);
        VBox.setVgrow(txTable, Priority.ALWAYS);
        reloadTx.run();

        TabPane tabs = new TabPane(
                new Tab("Info", infoBox),
                new Tab("Animals", animalsBox),
                new Tab("Transactions", txBox));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        // --------------------------------------------------------------- buttons
        Button save = new Button(existing == null ? "Create client" : "Save changes");
        save.getStyleClass().add("accent");
        save.setOnAction(e -> {
            String nm = name.getText() == null ? "" : name.getText().trim();
            if (nm.isEmpty()) {
                FxUtil.error(dialog, "Name required", "The client name is mandatory.");
                tabs.getSelectionModel().selectFirst();
                return;
            }
            Client updated = new Client(current.uuid(), nm,
                    safe(phone.getText()), safe(address.getText()),
                    desc.getText().isBlank() ? "[NO DESCRIPTION]" : desc.getText().trim(),
                    false, current.updatedAt());
            if (current.uuid() == null) {
                Client created = ClientDao.insert(updated);
                // reopen the dialog in edit mode so animals can be added right away
                dialog.setOnHidden(ev -> ClientDialog.show(owner, created));
                dialog.close();
            } else {
                ClientDao.update(updated);
                dialog.close();
            }
        });
        Button close = new Button("Close");
        close.setOnAction(e -> dialog.close());
        HBox buttons = new HBox(10, FxUtil.hSpacer(), close, save);
        buttons.setPadding(new Insets(10, 16, 16, 16));
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(tabs, buttons);
        VBox.setVgrow(tabs, Priority.ALWAYS);
        Ui.setScene(dialog, root, 980, 640);
        dialog.showAndWait();
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    private static String empty(String s) {
        return s == null || s.isBlank() ? "" : s;
    }

    private static Label rowLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("form-label");
        return l;
    }
}

/** Add / edit one animal: a registered pet OR a livestock counter. */
final class AnimalDialog {

    private AnimalDialog() {
    }

    static void show(Window owner, String clientUuid, Animal existing) {
        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.initOwner(owner);
        dialog.setTitle(existing == null ? "Add animal" : "Edit animal");
        FxUtil.setAppIcon(dialog);

        ToggleGroup kindGroup = new ToggleGroup();
        RadioButton petRadio = new RadioButton("Registered pet (dog, cat, horse...)");
        petRadio.setToggleGroup(kindGroup);
        RadioButton stockRadio = new RadioButton("Livestock group (sheep, cows, chickens...)");
        stockRadio.setToggleGroup(kindGroup);
        boolean isPet = existing == null || existing.isPet();
        petRadio.setSelected(isPet);
        stockRadio.setSelected(!isPet);
        HBox kinds = new HBox(20, petRadio, stockRadio);

        TextField name = new TextField(existing == null ? "" : existing.name());
        name.setPromptText("Pet name (e.g. Rex)");
        TextField breed = new TextField(existing == null ? "" : existing.breed());
        breed.setPromptText("Breed (e.g. German Shepherd)");
        ComboBox<String> gender = new ComboBox<>(FXCollections.observableArrayList(
                "unknown", "male", "female"));
        gender.setValue(existing == null ? "unknown" : existing.gender());
        DatePicker birth = new DatePicker(existing == null ? null : existing.birthDate());
        Spinner<Integer> quantity = new Spinner<>(1, 100_000, existing == null ? 1
                : Math.max(1, existing.quantity()));
        quantity.setEditable(true);

        ComboBox<String> speciesPet = new ComboBox<>(FXCollections.observableArrayList(Animal.PET_SPECIES));
        speciesPet.setEditable(true);
        ComboBox<String> speciesStock = new ComboBox<>(FXCollections.observableArrayList(Animal.LIVESTOCK_SPECIES));
        speciesStock.setEditable(true);
        if (existing != null) {
            speciesPet.setValue(existing.species());
            speciesStock.setValue(existing.species());
        } else {
            speciesPet.getSelectionModel().selectFirst();
            speciesStock.getSelectionModel().selectFirst();
        }
        TextArea notes = new TextArea(existing == null ? "" : existing.notes());
        notes.setPromptText("Vaccines, health notes, ear tag number...");
        notes.setPrefRowCount(3);

        GridPane petGrid = new GridPane();
        petGrid.getStyleClass().add("form-grid");
        petGrid.add(rowLabel("Species"), 0, 0);
        petGrid.add(speciesPet, 1, 0);
        petGrid.add(rowLabel("Name"), 0, 1);
        petGrid.add(name, 1, 1);
        petGrid.add(rowLabel("Breed"), 0, 2);
        petGrid.add(breed, 1, 2);
        petGrid.add(rowLabel("Gender"), 0, 3);
        petGrid.add(gender, 1, 3);
        petGrid.add(rowLabel("Birth date"), 0, 4);
        petGrid.add(birth, 1, 4);

        GridPane stockGrid = new GridPane();
        stockGrid.getStyleClass().add("form-grid");
        stockGrid.add(rowLabel("Species"), 0, 0);
        stockGrid.add(speciesStock, 1, 0);
        stockGrid.add(rowLabel("How many?"), 0, 1);
        stockGrid.add(quantity, 1, 1);

        GridPane notesGrid = new GridPane();
        notesGrid.getStyleClass().add("form-grid");
        notesGrid.add(rowLabel("Notes"), 0, 0);
        notesGrid.add(notes, 1, 0);

        VBox formBox = new VBox(kinds, petGrid, notesGrid);
        kindGroup.selectedToggleProperty().addListener((obs, o, t) -> {
            boolean pet = t == petRadio;
            formBox.getChildren().setAll(kinds, pet ? petGrid : stockGrid, notesGrid);
        });

        Button save = new Button(existing == null ? "Add" : "Save");
        save.getStyleClass().add("accent");
        save.setOnAction(e -> {
            boolean pet = kindGroup.getSelectedToggle() == petRadio;
            String species = pet ? speciesPet.getValue() : speciesStock.getValue();
            if (species == null || species.isBlank()) {
                FxUtil.error(dialog, "Species required", "Choose or type the species.");
                return;
            }
            Animal a = new Animal(existing == null ? null : existing.uuid(), clientUuid,
                    pet ? Animal.PET : Animal.LIVESTOCK, species.trim(),
                    pet ? name.getText().trim() : "",
                    pet ? breed.getText().trim() : "",
                    pet ? gender.getValue() : "unknown",
                    pet ? birth.getValue() : null,
                    pet ? 1 : Math.max(1, quantity.getValue()),
                    notes.getText() == null ? "" : notes.getText().trim(),
                    false, existing == null ? null : existing.updatedAt());
            if (existing == null) {
                AnimalDao.insert(a);
            } else {
                AnimalDao.update(a);
            }
            dialog.close();
        });
        Button cancel = new Button("Cancel");
        cancel.setOnAction(e -> dialog.close());
        HBox buttons = new HBox(10, FxUtil.hSpacer(), cancel, save);
        buttons.setPadding(new Insets(6, 16, 16, 16));
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(formBox, buttons);
        root.setPadding(new Insets(14, 0, 0, 0));
        Ui.setScene(dialog, root, 620, petRadio.isSelected() ? 560 : 420);
        dialog.showAndWait();
    }

    private static Label rowLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("form-label");
        return l;
    }
}
