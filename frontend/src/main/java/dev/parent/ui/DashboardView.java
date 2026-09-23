package dev.parent.ui;

import dev.parent.config.AppConfig;
import dev.parent.db.AppointmentDao;
import dev.parent.db.ClientDao;
import dev.parent.db.MedicineDao;
import dev.parent.db.TransactionDao;
import dev.parent.model.SaleTransaction;
import dev.parent.util.Bg;
import dev.parent.util.FxUtil;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Home screen: what happened today, the money picture (income per month,
 * paid vs still owed, shelf value) and vet-specific ranking analytics
 * (top products, top clients, services vs medicine sales).
 */
final class DashboardView extends MainWindow.BaseView {

    private final Label todaySales = new Label("-");
    private final Label todayCount = new Label("-");
    private final Label unpaidTotal = new Label("-");
    private final Label lowStock = new Label("-");
    private final Label expiring = new Label("-");
    private final Label appointments = new Label("-");
    private final Label weekIncome = new Label("-");
    private final Label monthIncome = new Label("-");
    private final Label avgTicket = new Label("-");
    private final Label shelfValue = new Label("-");
    private final TableView<SaleTransaction> recentTable = new TableView<>();

    private final CategoryAxis monthAxis = new CategoryAxis();
    private final NumberAxis moneyAxis = new NumberAxis();
    private final BarChart<String, Number> incomeChart = new BarChart<>(monthAxis, moneyAxis);
    private final XYChart.Series<String, Number> paidSeries = new XYChart.Series<>();
    private final XYChart.Series<String, Number> owedSeries = new XYChart.Series<>();

    private final ListView<String> topProducts = new ListView<>();
    private final ListView<String> incomeSplit = new ListView<>();
    private final ListView<String> topClients = new ListView<>();

    DashboardView() {
        VBox root = new VBox(16);
        root.setPadding(new Insets(20));

        HBox header = new HBox(14);
        header.setAlignment(Pos.CENTER_LEFT);
        Label title = new Label("Dashboard");
        title.getStyleClass().add("view-title");
        Label sub = new Label("Your clinic at a glance - " + FxUtil.date(LocalDate.now()));
        sub.getStyleClass().add("stat-sub");
        HBox.setMargin(sub, new Insets(8, 0, 0, 0));
        header.getChildren().addAll(title, sub);

        GridPane cards = new GridPane();
        cards.setHgap(14);
        cards.setVgap(14);
        cards.add(card("Today's sales", todaySales, "accent"), 0, 0);
        cards.add(card("Today's transactions", todayCount, ""), 1, 0);
        cards.add(card("Average ticket today", avgTicket, ""), 2, 0);
        cards.add(card("Income this week", weekIncome, "accent"), 0, 1);
        cards.add(card("Income this month", monthIncome, "accent"), 1, 1);
        cards.add(card("Money still owed (all time)", unpaidTotal, "danger"), 2, 1);
        cards.add(card("Products low on stock", lowStock, "danger"), 0, 2);
        cards.add(card("Products expiring in " + AppConfig.get().notifyExpiryDays() + " days", expiring, ""), 1, 2);
        cards.add(card("Appointments today", appointments, ""), 2, 2);
        cards.add(card("Money on your shelves (buy -> sell worth)", shelfValue, ""), 0, 3);

        // ------------------------------------------------ money flow chart
        incomeChart.setAnimated(false);
        incomeChart.setLegendVisible(true);
        incomeChart.setPrefHeight(280);
        incomeChart.setMinHeight(240);
        paidSeries.setName("Received (paid)");
        owedSeries.setName("Still owed");
        incomeChart.getData().addAll(paidSeries, owedSeries);
        moneyAxis.setLabel("DA");
        VBox chartCard = new VBox(6);
        chartCard.getStyleClass().add("card");
        chartCard.setPadding(new Insets(16));
        Label chartTitle = Ui.sectionTitle("Your income - last 6 months (paid vs still owed)");
        chartCard.getChildren().addAll(chartTitle, incomeChart);

        // ------------------------------------------------ ranking lists
        topProducts.setPrefHeight(190);
        topClients.setPrefHeight(190);
        incomeSplit.setPrefHeight(190);
        VBox ranks = new VBox(6);
        HBox rankRow = new HBox(14);
        rankRow.setAlignment(Pos.TOP_LEFT);
        rankRow.getChildren().addAll(
                listCard("Top products - last 30 days", topProducts),
                listCard("Income split - last 30 days", incomeSplit),
                listCard("Top clients - last 30 days", topClients));
        rankRow.getChildren().forEach(n -> HBox.setHgrow(n, Priority.ALWAYS));
        ranks.getChildren().add(rankRow);

        // ------------------------------------------------ quick actions
        VBox actions = new VBox(8);
        actions.getChildren().add(Ui.sectionTitle("Quick actions"));
        HBox buttons = new HBox(10);
        Button sale = Ui.toolButton("New sale / service", "coin.png",
                () -> MainWindow.get().show("daily"));
        sale.getStyleClass().add("accent");
        Button client = Ui.toolButton("New client", "client.png",
                () -> MainWindow.get().show("clients"));
        Button med = Ui.toolButton("New medicine", "medicine.png",
                () -> MainWindow.get().show("medicines"));
        Button appt = Ui.toolButton("New appointment", "calendar.png",
                () -> MainWindow.get().show("appointments"));
        Button journal = Ui.toolButton("Money journal", "database.png",
                () -> MainWindow.get().show("transactions"));
        buttons.getChildren().addAll(sale, client, med, appt, journal);
        actions.getChildren().add(buttons);

        // ------------------------------------------------ recent activity
        VBox recent = new VBox(6);
        recent.getChildren().add(Ui.sectionTitle("Today's latest activity"));
        recentTable.getColumns().setAll(
                Ui.textCol("Time", t -> FxUtil.time(t.createdAt()), 80),
                Ui.textCol("Client", t -> t.clientName(), 200),
                Ui.textCol("Medicine / service",
                        t -> t.medicineName() == null || t.medicineName().isBlank() ? "-" : t.medicineName(), 220),
                Ui.textCol("Type", SaleTransaction::type, 120),
                Ui.col("Qty", t -> FxUtil.qty(t.quantity()), 70),
                Ui.moneyCol("Amount", SaleTransaction::amount, 130),
                Ui.boolCol("Paid", SaleTransaction::paid, 80));
        recentTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        recentTable.setPlaceholder(new Label("Nothing recorded yet today. Make your first sale from Daily Usage."));
        recentTable.setPrefHeight(240);
        recent.getChildren().add(recentTable);

        root.getChildren().addAll(header, cards, chartCard, ranks, actions, recent);
        ScrollPane scroll = new ScrollPane(root);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: #263238;");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        setCenter(scroll);
    }

    private VBox card(String title, Label value, String style) {
        VBox card = new VBox(4);
        card.getStyleClass().addAll("card", style == null ? "" : style);
        Label t = new Label(title);
        t.getStyleClass().add("card-title");
        value.getStyleClass().add("stat-value");
        value.setWrapText(true);
        card.getChildren().addAll(t, value);
        card.setPrefWidth(280);
        return card;
    }

    private VBox listCard(String title, ListView<String> list) {
        VBox card = new VBox(6);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(16));
        card.setMaxWidth(Double.MAX_VALUE);
        Label t = new Label(title);
        t.getStyleClass().add("card-title");
        card.getChildren().addAll(t, list);
        return card;
    }

    @Override
    public String title() {
        return "Dashboard";
    }

    // ---------------------------------------------------------------- snapshot

    private record MonthRow(String label, double paid, double owed) {
    }

    private record TopRow(String name, double qty, double amount) {
    }

    /** Everything the dashboard needs, computed in one background pass. */
    private record Snapshot(double sales, long count, double unpaid, int lowStock,
                            int expiring, int appointments, List<SaleTransaction> recent,
                            double weekIncome, double monthIncome, double avgTicket,
                            double shelfBuy, double shelfSell,
                            List<MonthRow> months, List<TopRow> products, List<TopRow> clients,
                            double serviceAmount, double productAmount,
                            double monthPaid, double monthOwed) {
    }

    private static Snapshot buildSnapshot() {
        LocalDate today = LocalDate.now();
        TransactionDao.DayTotals totals = TransactionDao.totalsFor(today);
        double week = TransactionDao.listBetween(today.minusDays(6), today).stream()
                .mapToDouble(SaleTransaction::amount).sum();
        LocalDate monthStart = today.withDayOfMonth(1);
        List<SaleTransaction> monthTx = TransactionDao.listBetween(monthStart, today);
        double monthIncome = monthTx.stream().mapToDouble(SaleTransaction::amount).sum();
        double monthPaid = monthTx.stream().filter(SaleTransaction::paid)
                .mapToDouble(SaleTransaction::amount).sum();
        double avg = totals.count() == 0 ? 0 : totals.total() / (double) totals.count();
        MedicineDao.Totals meds = MedicineDao.totals();

        // ---- six-month paid/owed curve
        LocalDate rangeStart = today.minusMonths(5).withDayOfMonth(1);
        List<SaleTransaction> sixMonths = TransactionDao.listBetween(rangeStart, today);
        Map<YearMonth, double[]> byMonth = new LinkedHashMap<>();
        for (int i = 0; i < 6; i++) {
            byMonth.put(YearMonth.from(rangeStart.plusMonths(i)), new double[2]);
        }
        for (SaleTransaction t : sixMonths) {
            double[] cell = byMonth.get(YearMonth.from(t.createdAt().toLocalDate()));
            if (cell != null) {
                cell[t.paid() ? 0 : 1] += t.amount();
            }
        }
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MMM yy", Locale.ENGLISH);
        List<MonthRow> months = new ArrayList<>();
        for (Map.Entry<YearMonth, double[]> e : byMonth.entrySet()) {
            months.add(new MonthRow(e.getKey().format(fmt), e.getValue()[0], e.getValue()[1]));
        }

        // ---- 30-day rankings + income split
        List<SaleTransaction> last30 = TransactionDao.listBetween(today.minusDays(29), today);
        Map<String, TopRow> products = new LinkedHashMap<>();
        Map<String, double[]> clients = new LinkedHashMap<>();
        double serviceAmount = 0;
        double productAmount = 0;
        for (SaleTransaction t : last30) {
            boolean service = t.type() != null && !"Product".equalsIgnoreCase(t.type());
            if (service) {
                serviceAmount += t.amount();
            } else {
                productAmount += t.amount();
            }
            String medName = t.medicineName();
            if (!service && medName != null && !medName.isBlank()) {
                TopRow cur = products.get(medName);
                products.put(medName, cur == null
                        ? new TopRow(medName, t.quantity(), t.amount())
                        : new TopRow(medName, cur.qty() + t.quantity(), cur.amount() + t.amount()));
            }
            if (t.clientName() != null && !t.clientName().isBlank()) {
                double[] cell = clients.computeIfAbsent(t.clientName(), k -> new double[2]);
                cell[0] += t.amount();
                cell[1]++;
            }
        }
        List<TopRow> rankedProducts = new ArrayList<>(products.values());
        rankedProducts.sort((a, b) -> Double.compare(b.amount(), a.amount()));
        List<TopRow> topProducts = rankedProducts.size() > 5
                ? rankedProducts.subList(0, 5) : rankedProducts;
        // topClients is captured by the forEach below, so it must not be
        // reassigned afterwards (not effectively final -> compile error);
        // build the ranked list first, then limit once.
        List<TopRow> rankedClients = new ArrayList<>();
        clients.forEach((name, c) -> rankedClients.add(new TopRow(name, c[1], c[0])));
        rankedClients.sort((a, b) -> Double.compare(b.amount(), a.amount()));
        List<TopRow> topClients = rankedClients.size() > 5
                ? rankedClients.subList(0, 5) : rankedClients;

        return new Snapshot(
                totals.total(), totals.count(),
                ClientDao.globalUnpaidTotals().unpaid(),
                MedicineDao.lowStock().size(),
                MedicineDao.expiring(AppConfig.get().notifyExpiryDays()).size(),
                AppointmentDao.countPendingFor(today),
                TransactionDao.listToday().stream().limit(40).toList(),
                week, monthIncome, avg,
                meds.buyValue(), meds.sellValue(),
                months, topProducts, topClients,
                serviceAmount, productAmount, monthPaid, monthIncome - monthPaid);
    }

    @Override
    public void refreshData() {
        Bg.load(DashboardView::buildSnapshot, s -> {
            todaySales.setText(FxUtil.money(s.sales()));
            todayCount.setText(String.valueOf(s.count()));
            avgTicket.setText(s.count() == 0 ? "no sale yet" : FxUtil.money(s.avgTicket()));
            weekIncome.setText(FxUtil.money(s.weekIncome()));
            monthIncome.setText(FxUtil.money(s.monthIncome()));
            unpaidTotal.setText(FxUtil.money(s.unpaid()));
            lowStock.setText(String.valueOf(s.lowStock()));
            expiring.setText(String.valueOf(s.expiring()));
            appointments.setText(String.valueOf(s.appointments()));
            shelfValue.setText(FxUtil.money(s.shelfBuy()) + "  ->  " + FxUtil.money(s.shelfSell()));
            recentTable.getItems().setAll(s.recent());

            paidSeries.getData().clear();
            owedSeries.getData().clear();
            for (MonthRow m : s.months()) {
                paidSeries.getData().add(new XYChart.Data<>(m.label(), m.paid()));
                owedSeries.getData().add(new XYChart.Data<>(m.label(), m.owed()));
            }

            List<String> prodRows = new ArrayList<>();
            if (s.products().isEmpty()) {
                prodRows.add("No product sales in the last 30 days yet.");
            } else {
                int rank = 1;
                for (TopRow t : s.products()) {
                    prodRows.add(rank++ + ".  " + t.name() + "   -   " + FxUtil.qty(t.qty())
                            + " sold  -  " + FxUtil.money(t.amount()));
                }
            }
            topProducts.getItems().setAll(prodRows);

            double total30 = s.serviceAmount() + s.productAmount();
            String pct = total30 > 0
                    ? String.format(Locale.ENGLISH, "%.0f%%", 100.0 * s.serviceAmount() / total30)
                    : "0%";
            List<String> splitRows = new ArrayList<>();
            splitRows.add("Services (consultations, vaccines...):  " + FxUtil.money(s.serviceAmount())
                    + "  (" + pct + " of it)");
            splitRows.add("Medicines / products sold:  " + FxUtil.money(s.productAmount()));
            splitRows.add(" ");
            splitRows.add("This month:  " + FxUtil.money(s.monthIncome())
                    + "  ->  received " + FxUtil.money(s.monthPaid())
                    + "  |  still owed " + FxUtil.money(s.monthOwed()));
            splitRows.add(s.monthOwed() > 0
                    ? "Tip: " + FxUtil.money(s.monthOwed()) + " is sleeping at your clients - the money journal lists who."
                    : "Everything from this month is already collected. Well done!");
            incomeSplit.getItems().setAll(splitRows);

            List<String> clientRows = new ArrayList<>();
            if (s.clients().isEmpty()) {
                clientRows.add("No client activity in the last 30 days yet.");
            } else {
                int rank = 1;
                for (TopRow t : s.clients()) {
                    clientRows.add(rank++ + ".  " + t.name() + "   -   " + (int) t.qty()
                            + " visit(s)/sale(s)  -  " + FxUtil.money(t.amount()));
                }
            }
            topClients.getItems().setAll(clientRows);
        });
    }
}
