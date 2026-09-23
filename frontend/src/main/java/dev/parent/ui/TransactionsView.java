package dev.parent.ui;

import dev.parent.db.TransactionDao;
import dev.parent.model.SaleTransaction;
import dev.parent.util.Bg;
import dev.parent.util.FxUtil;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.geometry.Insets;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.time.LocalDate;
import java.util.List;

/**
 * The money journal, on its own screen now (it used to squat at the bottom
 * of Daily Usage): today's transactions with pay/delete actions, plus the
 * full per-day history with a double-click day inspector.
 */
final class TransactionsView extends MainWindow.BaseView {

    private final TableView<SaleTransaction> todayTable = new TableView<>();
    private final TableView<TransactionDao.DaySummary> historyTable = new TableView<>();
    private final Label statsLabel = new Label();

    TransactionsView() {
        Button setPaid = Ui.toolButton("Mark selected as paid", "check.png", () -> markSelectedPaid(true));
        Button delete = Ui.toolButton("Delete selected", "delete.png", this::deleteSelected);
        delete.getStyleClass().add("danger");
        Button refresh = Ui.toolButton("Refresh", "refresh.png", this::refreshData);
        Label tip = new Label("Double-click a day in the history to open its full listing");
        tip.getStyleClass().add("hint");
        setTop(Ui.toolbar(setPaid, delete, refresh, FxUtil.hSpacer(), tip));

        // ----------------------------------------------------------- today
        todayTable.getColumns().setAll(
                Ui.textCol("Time", t -> FxUtil.time(t.createdAt()), 75),
                Ui.textCol("Client", SaleTransaction::clientName, 180),
                Ui.textCol("Medicine / service",
                        t -> t.medicineName() == null || t.medicineName().isBlank()
                                ? "-" : t.medicineName(), 170),
                Ui.col("Qty", t -> FxUtil.qty(t.quantity()), 65),
                Ui.moneyCol("Amount", SaleTransaction::amount, 110),
                Ui.textCol("Type", SaleTransaction::type, 110),
                Ui.textCol("Description",
                        t -> t.description() == null ? "" : t.description(), 190),
                Ui.boolCol("Paid", SaleTransaction::paid, 70));
        todayTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        todayTable.setPlaceholder(new Label("No transaction today yet."));
        todayTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        statsLabel.getStyleClass().add("stat-sub");
        HBox todayFooter = new HBox(statsLabel);
        todayFooter.setAlignment(Pos.CENTER_LEFT);
        todayFooter.setPadding(new Insets(4, 8, 4, 8));
        VBox todayBox = new VBox(todayTable, todayFooter);
        VBox.setVgrow(todayTable, Priority.ALWAYS);

        // ---------------------------------------------------------- history
        historyTable.getColumns().setAll(
                Ui.textCol("Date", d -> FxUtil.date(d.date()), 140),
                Ui.col("Transactions", TransactionDao.DaySummary::count, 120),
                Ui.moneyCol("Total amount", TransactionDao.DaySummary::totalAmount, 160),
                Ui.moneyCol("Unpaid that day", TransactionDao.DaySummary::unpaidAmount, 160));
        historyTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        historyTable.setPlaceholder(new Label("History will appear here"));
        historyTable.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                TransactionDao.DaySummary d = historyTable.getSelectionModel().getSelectedItem();
                if (d != null) {
                    openDayDialog(d.date());
                }
            }
        });

        TabPane tabs = new TabPane(
                new Tab("Today's transactions", todayBox),
                new Tab("History per day", historyTable));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        setCenter(tabs);
    }

    // ------------------------------------------------------------ actions

    private void deleteSelected() {
        List<SaleTransaction> sel = todayTable.getSelectionModel().getSelectedItems();
        if (sel.isEmpty()) {
            FxUtil.error(getWindow(), "Nothing selected", "Select at least one transaction.");
            return;
        }
        if (!FxUtil.confirm(getWindow(), "Delete transactions",
                "Delete the " + sel.size() + " selected transaction(s)?\n"
                        + "(stock quantities are NOT put back)")) {
            return;
        }
        Bg.load(() -> {
                    sel.forEach(t -> TransactionDao.softDelete(t.uuid()));
                    return null;
                },
                v -> refreshData());
    }

    private void markSelectedPaid(boolean paid) {
        List<SaleTransaction> sel = todayTable.getSelectionModel().getSelectedItems();
        if (sel.isEmpty()) {
            FxUtil.error(getWindow(), "Nothing selected", "Select at least one transaction.");
            return;
        }
        Bg.load(() -> {
                    TransactionDao.setPaid(sel.stream().map(SaleTransaction::uuid).toList(), paid);
                    return null;
                },
                v -> refreshData());
    }

    private void openDayDialog(LocalDate date) {
        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.initOwner(getWindow());
        dialog.setTitle("Transactions of " + FxUtil.date(date));
        FxUtil.setAppIcon(dialog);
        TableView<SaleTransaction> table = new TableView<>();
        table.getColumns().setAll(
                Ui.textCol("Time", t -> FxUtil.time(t.createdAt()), 80),
                Ui.textCol("Client", SaleTransaction::clientName, 190),
                Ui.textCol("Medicine / service",
                        t -> t.medicineName() == null || t.medicineName().isBlank()
                                ? "-" : t.medicineName(), 170),
                Ui.col("Qty", t -> FxUtil.qty(t.quantity()), 70),
                Ui.moneyCol("Amount", SaleTransaction::amount, 120),
                Ui.textCol("Type", SaleTransaction::type, 110),
                Ui.boolCol("Paid", SaleTransaction::paid, 80));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        Label footer = new Label("Loading...");
        footer.getStyleClass().add("stat-sub");
        VBox box = new VBox(6, table, footer);
        box.setPadding(new Insets(10));
        VBox.setVgrow(table, Priority.ALWAYS);
        Ui.setScene(dialog, box, 900, 520);
        dialog.show();
        Bg.load(() -> TransactionDao.listByDate(date),
                rows -> table.getItems().setAll(rows));
        Bg.load(() -> TransactionDao.totalsFor(date),
                totals -> footer.setText(totals.count() + " transactions  |  total "
                        + FxUtil.money(totals.total()) + "  |  paid " + FxUtil.money(totals.paid())
                        + "  |  unpaid " + FxUtil.money(totals.unpaid())));
    }

    // -------------------------------------------------------------- misc

    @Override
    public String title() {
        return "Transactions & history";
    }

    @Override
    public void refreshData() {
        LocalDate today = LocalDate.now();
        Bg.load(TransactionDao::listToday, rows -> {
            todayTable.getItems().setAll(rows);
            Bg.load(() -> TransactionDao.totalsFor(today),
                    totals -> statsLabel.setText(totals.count() + " transaction(s) today   |   Total: "
                            + FxUtil.money(totals.total()) + "   |   Paid: "
                            + FxUtil.money(totals.paid()) + "   |   Unpaid: "
                            + FxUtil.money(totals.unpaid())));
        });
        Bg.load(TransactionDao::dailySummary,
                rows -> historyTable.getItems().setAll(rows));
    }

    private Window getWindow() {
        return getScene() == null ? MainWindow.get().stage() : getScene().getWindow();
    }
}
