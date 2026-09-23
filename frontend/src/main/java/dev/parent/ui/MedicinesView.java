package dev.parent.ui;

import dev.parent.config.AppConfig;
import dev.parent.db.LocalDatabase;
import dev.parent.db.MedicineDao;
import dev.parent.model.Medicine;
import dev.parent.scanner.CameraScanDialog;
import dev.parent.scanner.ScannerService;
import dev.parent.util.Bg;
import dev.parent.util.FxUtil;
import dev.parent.util.SoundPlayer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Medicine / product stock management with scan integration. */
final class MedicinesView extends MainWindow.BaseView {

    private final TableView<Medicine> table = new TableView<>();
    private final TextField search = new TextField();
    private final Label totals = new Label();

    MedicinesView() {
        Button add = Ui.toolButton("Add", "add.png", () -> openAddDialog(getWindow(), null));
        Button edit = Ui.toolButton("Edit", "edit.png", () -> selected().ifPresent(m -> {
            MedicineDialog.show(getWindow(), m);
            refreshData();
        }));
        Button del = Ui.toolButton("Delete", "delete.png", this::deleteSelected);
        del.getStyleClass().add("danger");
        Button camera = Ui.toolButton("Camera scan", "check.png", () -> {
            try {
                new CameraScanDialog(getWindow(), code -> {
                    Optional<Medicine> found = MedicineDao.byBarcode(code);
                    handleScan(code, found);
                }).show();
            } catch (Throwable t) {
                FxUtil.error(getWindow(), "Camera unavailable",
                        "Could not start the camera: " + t.getMessage());
            }
        });
        Button refresh = Ui.toolButton("Refresh", "refresh.png", this::refreshData);

        search.setPromptText("Search by name or barcode...");
        search.getStyleClass().add("search-field");
        search.textProperty().addListener((obs, o, n) -> refreshData());
        search.setTooltip(new Tooltip("Scan or type a barcode here to find a product instantly"));

        setTop(Ui.toolbar(add, edit, del, camera, refresh, FxUtil.hSpacer(),
                new Label("Search / scan:"), search));

        table.getColumns().setAll(
                Ui.textCol("Name", Medicine::name, 220),
                Ui.textCol("Barcode", Medicine::barcode, 150),
                Ui.textCol("Type", Medicine::type, 130),
                Ui.col("Stock", Medicine::stock, 80),
                Ui.col("Open (ml/doses)", m -> FxUtil.qty(m.size()), 130),
                Ui.moneyCol("Buy price", Medicine::buyPrice, 120),
                Ui.moneyCol("Sell price", Medicine::sellPrice, 120),
                Ui.textCol("Expiry", m -> FxUtil.expiry(m.expiryDate()), 110),
                Ui.textCol("Seller", Medicine::seller, 140));
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("No products yet. Add medicines or scan a barcode."));
        table.setRowFactory(tv -> new TableRow<>() {
            @Override
            protected void updateItem(Medicine m, boolean empty) {
                super.updateItem(m, empty);
                getStyleClass().removeAll("row-warn", "row-danger");
                if (m == null || empty) {
                    return;
                }
                boolean expired = m.expiryDate() != null && m.expiryDate().isBefore(LocalDate.now());
                boolean exhausted = m.stock() == 0 && m.size() <= 0;
                if (expired || exhausted) {
                    getStyleClass().add("row-danger");
                } else if (m.lowStock() || m.expiringWithin(AppConfig.get().notifyExpiryDays())) {
                    getStyleClass().add("row-warn");
                }
            }
        });
        table.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                selected().ifPresent(m -> {
                    MedicineDialog.show(getWindow(), m);
                    refreshData();
                });
            }
        });

        totals.getStyleClass().add("stat-sub");
        HBox footer = new HBox(30, totals);
        footer.setPadding(new Insets(8, 12, 8, 12));
        footer.setAlignment(Pos.CENTER_LEFT);

        VBox center = new VBox(table, footer);
        VBox.setVgrow(table, Priority.ALWAYS);
        setCenter(center);
    }

    private Optional<Medicine> selected() {
        Medicine m = table.getSelectionModel().getSelectedItem();
        if (m == null) {
            FxUtil.error(getWindow(), "Nothing selected", "Please select a medicine row first.");
        }
        return Optional.ofNullable(m);
    }

    private void deleteSelected() {
        List<Medicine> sel = table.getSelectionModel().getSelectedItems();
        if (sel.isEmpty()) {
            FxUtil.error(getWindow(), "Nothing selected", "Select at least one medicine.");
            return;
        }
        if (!FxUtil.confirm(getWindow(), "Delete medicines",
                "Delete " + sel.size() + " medicine(s)?\n(their transaction history is kept)")) {
            return;
        }
        Bg.load(() -> {
                    sel.forEach(m -> MedicineDao.softDelete(m.uuid()));
                    return null;
                },
                v -> refreshData());
    }

    /** Opens the "new medicine" dialog, optionally with a scanned barcode pre-filled. */
    static void openAddDialog(Window owner, String barcode) {
        MedicineDialog.show(owner, null, barcode);
    }

    @Override
    public boolean handleScan(String code, Optional<Medicine> found) {
        if (found.isPresent()) {
            SoundPlayer.playFound();
            table.getItems().setAll(found.get());
            table.getSelectionModel().selectFirst();
            table.scrollTo(0);
            search.setText(code);
        } else {
            SoundPlayer.playNotFound();
            if (FxUtil.confirm(getWindow(), "Unknown barcode",
                    "No product with barcode \"" + code + "\" exists.\nRegister it now?")) {
                openAddDialog(getWindow(), code);
                refreshData();
            }
        }
        return true;
    }

    private Window getWindow() {
        return getScene() == null ? MainWindow.get().stage() : getScene().getWindow();
    }

    @Override
    public String title() {
        return "Medicine Stock";
    }

    @Override
    public void refreshData() {
        String filter = search.getText();
        Bg.load(() -> new LoadResult(MedicineDao.listAll(filter), MedicineDao.totals()),
                res -> {
                    table.getItems().setAll(res.items());
                    MedicineDao.Totals t = res.totals();
                    totals.setText(t.products() + " products  |  " + t.stockUnits() + " units in stock  |  "
                            + "stock value (buy): " + FxUtil.money(t.buyValue()) + "  |  "
                            + "stock value (sell): " + FxUtil.money(t.sellValue()) + "  |  "
                            + "potential profit: " + FxUtil.money(t.potentialProfit()));
                });
    }

    private record LoadResult(List<Medicine> items, MedicineDao.Totals totals) {
    }
}

/** Create / edit a medicine. */
final class MedicineDialog {

    private MedicineDialog() {
    }

    static void show(Window owner, Medicine existing) {
        show(owner, existing, null);
    }

    static void show(Window owner, Medicine existing, String presetBarcode) {
        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.initOwner(owner);
        dialog.setTitle(existing == null ? "New medicine" : "Edit: " + existing.name());
        FxUtil.setAppIcon(dialog);

        TextField name = new TextField(existing == null ? "" : existing.name());
        name.setPromptText("Medicine name *");

        TextField barcode = new TextField(existing == null
                ? (presetBarcode == null ? "" : presetBarcode)
                : existing.barcode());
        barcode.setPromptText("Barcode (scan or type)");
        Button scanNow = Ui.toolButton("Scan", "check.png", () -> {
            try {
                new CameraScanDialog(dialog, barcode::setText).show();
            } catch (Throwable t) {
                FxUtil.error(dialog, "Camera unavailable", t.getMessage());
            }
        });
        HBox barcodeRow = new HBox(8, barcode, scanNow);
        HBox.setHgrow(barcode, Priority.ALWAYS);

        // While this dialog is open, ANY scan (camera is always on) drops
        // straight into the barcode field - no button press needed.
        ScannerService.ScanListener capture = code -> {
            barcode.setText(code);
            SoundPlayer.playFound();
        };
        dialog.setOnShown(e -> ScannerService.beginExclusiveCapture(capture));
        dialog.setOnHiding(e -> ScannerService.endExclusiveCapture(capture));

        ComboBox<String> type = new ComboBox<>(javafx.collections.FXCollections.observableArrayList(
                "Antibiotic", "Antiparasitic", "Vaccine", "Anti-inflammatory",
                "Vitamin", "Disinfectant", "Anesthetic", "Hormone", "Other"));
        type.setEditable(true);
        type.setValue(existing == null ? "[UNKNOWN TYPE]" : existing.type());

        Spinner<Integer> stock = new Spinner<>(0, 1_000_000, existing == null ? 0 : existing.stock());
        stock.setEditable(true);
        Spinner<Double> fullSize = new Spinner<>(0.0, 1_000_000.0,
                existing == null ? 0.0 : existing.fullSize(), 1.0);
        fullSize.setEditable(true);
        Spinner<Double> openSize = new Spinner<>(0.0, 1_000_000.0,
                existing == null ? 0.0 : existing.size(), 1.0);
        openSize.setEditable(true);
        Spinner<Double> buyPrice = new Spinner<>(0.0, 100_000_000.0,
                existing == null ? 0.0 : existing.buyPrice(), 50.0);
        buyPrice.setEditable(true);
        Spinner<Double> sellPrice = new Spinner<>(0.0, 100_000_000.0,
                existing == null ? 0.0 : existing.sellPrice(), 50.0);
        sellPrice.setEditable(true);
        Spinner<Integer> lowStock = new Spinner<>(0, 100_000,
                existing == null ? 3 : existing.lowStockThreshold());
        lowStock.setEditable(true);

        DatePicker expiry = new DatePicker(
                existing == null || Medicine.NO_EXPIRY.equals(existing.expiryDate())
                        ? null : existing.expiryDate());
        CheckBox noExpiry = new CheckBox("No expiry date");
        noExpiry.setSelected(existing == null || Medicine.NO_EXPIRY.equals(existing.expiryDate()));
        noExpiry.selectedProperty().addListener((obs, o, on) -> expiry.setDisable(on));
        expiry.setDisable(noExpiry.isSelected());
        HBox expiryRow = new HBox(10, expiry, noExpiry);
        expiryRow.setAlignment(Pos.CENTER_LEFT);

        TextField seller = new TextField(existing == null ? "" : existing.seller());
        seller.setPromptText("Supplier / seller");
        TextArea description = new TextArea(existing == null
                || "[NO DESCRIPTION]".equals(existing.description()) ? "" : existing.description());
        description.setPromptText("Description / usage notes");
        description.setPrefRowCount(3);

        GridPane grid = new GridPane();
        grid.getStyleClass().add("form-grid");
        int r = 0;
        grid.add(rowLabel("Name *"), 0, r);
        grid.add(name, 1, r++);
        grid.add(rowLabel("Barcode"), 0, r);
        grid.add(barcodeRow, 1, r++);
        grid.add(rowLabel("Type"), 0, r);
        grid.add(type, 1, r++);
        grid.add(rowLabel("Units in stock"), 0, r);
        grid.add(stock, 1, r++);
        grid.add(rowLabel("Full unit size (ml/doses)"), 0, r);
        grid.add(fullSize, 1, r++);
        grid.add(rowLabel("Open unit remaining"), 0, r);
        grid.add(openSize, 1, r++);
        grid.add(rowLabel("Buy price (DA)"), 0, r);
        grid.add(buyPrice, 1, r++);
        grid.add(rowLabel("Sell price (DA)"), 0, r);
        grid.add(sellPrice, 1, r++);
        grid.add(rowLabel("Low stock alert at"), 0, r);
        grid.add(lowStock, 1, r++);
        grid.add(rowLabel("Expiry date"), 0, r);
        grid.add(expiryRow, 1, r++);
        grid.add(rowLabel("Seller"), 0, r);
        grid.add(seller, 1, r++);
        grid.add(rowLabel("Description"), 0, r);
        grid.add(description, 1, r++);
        type.setMaxWidth(Double.MAX_VALUE);

        Button save = new Button(existing == null ? "Add medicine" : "Save changes");
        save.getStyleClass().add("accent");
        save.setOnAction(e -> {
            if (name.getText() == null || name.getText().isBlank()) {
                FxUtil.error(dialog, "Name required", "The medicine name is mandatory.");
                return;
            }
            Medicine m = new Medicine(
                    existing == null ? null : existing.uuid(),
                    barcode.getText() == null || barcode.getText().isBlank()
                            ? "[UNKNOWN BARCODE]" : barcode.getText().trim(),
                    name.getText().trim(),
                    type.getValue() == null || type.getValue().isBlank() ? "[UNKNOWN TYPE]" : type.getValue().trim(),
                    num(openSize), num(fullSize), num(buyPrice), num(sellPrice),
                    noExpiry.isSelected() || expiry.getValue() == null
                            ? Medicine.NO_EXPIRY : expiry.getValue(),
                    stock.getValue(),
                    seller.getText() == null || seller.getText().isBlank()
                            ? "[UNKNOWN SELLER]" : seller.getText().trim(),
                    description.getText() == null || description.getText().isBlank()
                            ? "[NO DESCRIPTION]" : description.getText().trim(),
                    lowStock.getValue(),
                    false,
                    existing == null ? null : existing.updatedAt());
            try {
                // barcode uniqueness is guarded locally to keep a clean error message
                if (!"[UNKNOWN BARCODE]".equals(m.barcode())) {
                    Optional<Medicine> clash = MedicineDao.byBarcode(m.barcode());
                    if (clash.isPresent() && !clash.get().uuid().equals(m.uuid())) {
                        FxUtil.error(dialog, "Duplicate barcode",
                                "Another product already uses this barcode: " + clash.get().name());
                        return;
                    }
                }
                if (existing == null) {
                    MedicineDao.insert(m);
                    SoundPlayer.playFound();
                } else {
                    MedicineDao.update(m);
                }
                dialog.close();
            } catch (LocalDatabase.DaoException ex) {
                FxUtil.error(dialog, "Could not save", ex.getMessage());
            }
        });
        Button cancel = new Button("Cancel");
        cancel.setOnAction(e -> dialog.close());
        HBox buttons = new HBox(10, FxUtil.hSpacer(), cancel, save);
        buttons.setPadding(new Insets(6, 16, 16, 16));
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(grid, buttons);
        Ui.setScene(dialog, root, 640, 760);
        dialog.showAndWait();
    }

    private static double num(Spinner<Double> s) {
        try {
            s.getValueFactory().setValue(
                    Double.parseDouble(s.getEditor().getText().replace(",", ".")));
        } catch (Exception ignored) {
        }
        Object v = s.getValue();
        return v == null ? 0 : ((Number) v).doubleValue();
    }

    private static Label rowLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("form-label");
        return l;
    }
}
