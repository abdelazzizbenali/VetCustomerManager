package dev.parent.ui;

import dev.parent.db.TransactionDao;
import dev.parent.net.ScanRouter;
import dev.parent.scanner.CameraScanService;
import dev.parent.model.Client;
import dev.parent.model.Medicine;
import dev.parent.model.SaleTransaction;
import dev.parent.util.Bg;
import dev.parent.util.FxUtil;
import dev.parent.util.SoundPlayer;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The POS heart of the application - rebuilt around three ideas:
 * <ol>
 *   <li><b>Cart tabs, one per client</b> - like browser tabs. Serve the urgent
 *       client now, go back to the other carts afterwards: nothing is lost.</li>
 *   <li><b>Always-on camera</b> - the preview is live on screen; showing a
 *       barcode puts the product in the open cart instantly.</li>
 *   <li><b>Calculator column</b> - scan, type a number on the numpad, press
 *       SET QTY (partial amounts like 5 ml with a syringe) or SET PRICE.</li>
 * </ol>
 * All database work runs on virtual threads ({@link Bg}); checkout never
 * freezes the screen.
 */
final class DailyUsageView extends MainWindow.BaseView {

    // ------------------------------------------------------------------ model

    /** One line inside a cart (medicine or free service).
     *  The QUANTITY ALWAYS drives the amount: amount = qty * unitPrice.
     *  "SET PRICE" stores a custom unit price (so the shown amount becomes the
     *  typed one), but a later "SET QTY" still recalculates from it - the
     *  total can never freeze. */
    private static final class CartLine {
        final String key = UUID.randomUUID().toString();
        String medUuid;      // null = manual service line
        String label;
        String type = "Product";
        double qty;
        double unitPrice;
        double amount;       // always round2(qty * unitPrice); kept as the table's cell value
        boolean customPrice; // display hint only: the price was typed by hand
        String note = "";

        void recompute() {
            amount = Math.round(qty * unitPrice * 100.0) / 100.0;
        }
    }

    /** An open cart: belongs to one client, lives across tab switches. */
    private static final class Cart {
        final String id = UUID.randomUUID().toString();
        Client client;
        final ObservableList<CartLine> lines = FXCollections.observableArrayList();

        double total() {
            double sum = 0;
            for (CartLine l : lines) {
                l.recompute(); // derive it fresh - display can never lag the quantity
                sum += l.amount;
            }
            return sum;
        }
    }

    private record CheckoutResult(List<CartLine> done, List<String> failures) {
    }

    // ------------------------------------------------------------------- state

    private final List<Cart> carts = new ArrayList<>();
    private Cart active;

    // cart strip
    private final HBox strip = new HBox(6);
    private final ToggleGroup stripGroup = new ToggleGroup();

    // cart area
    private final TableView<CartLine> cartTable = new TableView<>();
    private final Label cartClient = new Label("No cart open - press \"+ New cart\"");
    private final Label cartTotal = new Label("0.00 DA");
    private final Button paidBtn = new Button("CASH (paid)");
    private final Button creditBtn = new Button("CREDIT (unpaid)");

    // quick-scan column
    private final ImageView camera = new ImageView();
    private final Label cameraStatus = new Label("Camera starting...");
    private final Label lastScan = new Label("Show a barcode to the camera");
    private final TextField entry = new TextField();
    private final Label calcStatus = new Label(" ");

    // big total shown above the camera preview (mirrors the checkout-bar total)
    private final Label bigTotal = new Label("0.00 DA");

    // camera preview widgets (togglable from Settings -> "show live preview")
    private StackPane camBox;
    private volatile long lastPreviewMs;

    // -------------------------------------------------------------- constructor

    DailyUsageView() {
        // --------------------------------------------------------- toolbar
        Button addService = Ui.toolButton("Add service", "add.png", this::addServiceLine);
        Button removeLine = Ui.toolButton("Remove line", "delete.png", this::removeSelectedLines);
        removeLine.getStyleClass().add("danger");
        Button refresh = Ui.toolButton("Refresh", "refresh.png", this::refreshData);
        Label tip = new Label("Just show a barcode to the camera: it lands in the open cart instantly");
        tip.getStyleClass().add("hint");

        setTop(Ui.toolbar(addService, removeLine, refresh, FxUtil.hSpacer(), tip));

        // ------------------------------------------------------- cart strip
        buildCartStrip();

        // ------------------------------------------------------- cart table
        cartTable.getColumns().setAll(
                Ui.textCol("Product / service", l -> l.label, 220),
                Ui.col("Qty", l -> FxUtil.qty(l.qty), 70),
                Ui.moneyCol("Unit price", l -> l.unitPrice, 110),
                Ui.moneyCol("Amount", l -> l.amount, 120),
                Ui.textCol("Note", l -> l.note, 140));
        cartTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        cartTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        cartTable.setPlaceholder(new Label(
                "Cart is empty.\nScan a product, or press \"Add service\".\n"
                        + "Then type a number and press SET QTY / SET PRICE."));

        VBox cartBox = new VBox(8, buildDayStrip(), cartTable, buildCheckoutBar());
        cartBox.setPadding(new Insets(10));
        VBox.setVgrow(cartTable, Priority.ALWAYS);

        // -------------------------------------------------- quick-scan column
        VBox quick = buildQuickScanColumn();

        SplitPane upper = new SplitPane(cartBox, quick);
        upper.setDividerPositions(0.70);
        setCenter(upper); // the transactions journal lives on its own screen now

        // camera hooks: the local built-in engine owns the hardware; we get
        // live preview frames and status directly (same JVM, no loopback)
        ScanRouter.get().addStatusListener((running, msg) ->
                cameraStatus.setText(running ? "LIVE - just show a code" : msg));
        dev.parent.scanner.CameraScanService.get().addFrameListener(jpeg -> {
            // Only show preview when Daily Usage is actually on screen — avoids the
            // "Medicines + Daily both scanning" confusion and saves CPU.
            try {
                dev.parent.ui.MainWindow w = dev.parent.ui.MainWindow.get();
                if (w != null && !"daily".equals(w.currentViewKey())) return;
                if (!dev.parent.config.AppConfig.get().cameraPreviewVisible()) return;
            } catch (Throwable ignored) {}
            long now = System.currentTimeMillis();
            if (now - lastPreviewMs < 90) return;
            lastPreviewMs = now;
            // Show filtered preview so Settings sliders affect the WHOLE program (preview + ZXing)
            Thread.ofVirtual().name("daily-preview").start(() -> {
                try {
                    java.awt.image.BufferedImage raw = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(jpeg));
                    if (raw == null) return;
                    java.awt.image.BufferedImage filtered = dev.parent.scanner.ImageFilters.applyForPreview(raw);
                    javafx.scene.image.Image fx = javafx.embed.swing.SwingFXUtils.toFXImage(filtered, null);
                    if (fx != null && !fx.isError()) {
                        javafx.application.Platform.runLater(() -> camera.setImage(fx));
                    }
                } catch (Throwable t) {
                    try {
                        javafx.scene.image.Image img = new javafx.scene.image.Image(new java.io.ByteArrayInputStream(jpeg));
                        if (!img.isError()) javafx.application.Platform.runLater(() -> camera.setImage(img));
                    } catch (Throwable ignored2) {}
                }
            });
        });
        cameraStatus.textProperty().addListener((obs, o, n) ->
                cameraStatus.setStyle(n != null && n.startsWith("LIVE")
                        ? "-fx-text-fill:#66BB6A; -fx-font-weight:bold;"
                        : "-fx-text-fill:#FFB300;"));

        selectCart(null);
    }

    // -------------------------------------------------------------- cart strip

    private void buildCartStrip() {
        strip.setAlignment(Pos.CENTER_LEFT);
        strip.setPadding(new Insets(4));
        // one group listener for life: restyle tabs when the selection moves
        stripGroup.selectedToggleProperty().addListener((obs, o, now) -> {
            for (javafx.scene.Node node : strip.getChildren()) {
                if (node instanceof HBox box && !box.getChildren().isEmpty()
                        && box.getChildren().get(0) instanceof ToggleButton tab) {
                    boolean isActive = now != null && now.getUserData() == tab.getUserData();
                    tab.setStyle(isActive
                            ? "-fx-background-color: #2E7D6E; -fx-text-fill: white; -fx-font-weight: bold;"
                            + " -fx-background-radius: 8 8 0 0; -fx-padding: 6 10 6 10;"
                            : "-fx-background-radius: 8 8 0 0; -fx-padding: 6 10 6 10;");
                }
            }
        });
    }

    private HBox buildDayStrip() {
        Button newCart = new Button("+ New cart");
        newCart.getStyleClass().add("accent");
        newCart.setOnAction(e -> openNewCart());
        Label caption = new Label("Open carts:");
        caption.getStyleClass().add("hint");
        ScrollPane scroll = new ScrollPane(strip);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setFitToHeight(true);
        scroll.setPrefViewportHeight(42);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        HBox row = new HBox(8, caption, scroll, newCart);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(scroll, Priority.ALWAYS);
        return row;
    }

    private void rebuildStripButtons() {
        strip.getChildren().clear();
        stripGroup.getToggles().clear(); // drop dead toggles so the group never leaks
        for (Cart cart : carts) {
            ToggleButton tab = new ToggleButton(cartTabText(cart));
            tab.setToggleGroup(stripGroup);
            tab.setSelected(cart == active);
            tab.setUserData(cart);
            tab.setStyle("-fx-background-radius: 8 8 0 0; -fx-padding: 6 10 6 10;");
            tab.selectedProperty().addListener((obs, o, on) -> {
                if (on) {
                    selectCart(cart);
                }
            });
            Button close = new Button("x");
            close.setStyle("-fx-font-size: 9px; -fx-padding: 2 5 2 5;");
            close.getStyleClass().add("ghost");
            close.setOnAction(e -> closeCart(cart));
            HBox entry = new HBox(2, tab, close);
            entry.setAlignment(Pos.CENTER);
            strip.getChildren().add(entry);
        }
    }

    private String cartTabText(Cart cart) {
        String name = cart.client == null ? "?" : cart.client.name();
        if (name.length() > 18) {
            name = name.substring(0, 17) + "..";
        }
        return cart.lines.isEmpty() ? name : name + " (" + cart.lines.size() + ")";
    }

    private void refreshStripLabels() {
        for (javafx.scene.Node node : strip.getChildren()) {
            if (node instanceof HBox box && !box.getChildren().isEmpty()
                    && box.getChildren().get(0) instanceof ToggleButton tab
                    && tab.getUserData() instanceof Cart cart) {
                tab.setText(cartTabText(cart));
            }
        }
    }

    // ------------------------------------------------------------- cart actions

    private void openNewCart() {
        Window win = getWindow();
        ClientPickerDialog.show(win).ifPresentOrElse(this::createCart,
                () -> calcStatus.setText("Cart creation cancelled."));
    }

    private void createCart(Client client) {
        Cart cart = new Cart();
        cart.client = client;
        carts.add(cart);
        rebuildStripButtons();
        selectCart(cart);
        calcStatus.setText("New cart opened for " + client.name());
    }

    private void closeCart(Cart cart) {
        if (!cart.lines.isEmpty()
                && !FxUtil.confirm(getWindow(), "Close cart",
                "Close " + (cart.client == null ? "this cart" : cart.client.name() + "'s cart")
            + " and discard its " + cart.lines.size() + " line(s)?\n"
            + "Not checked out = not recorded.")) {
            return;
        }
        carts.remove(cart);
        if (cart == active) {
            selectCart(carts.isEmpty() ? null : carts.get(carts.size() - 1));
        }
        rebuildStripButtons();
        refreshStripLabels();
    }

    private void selectCart(Cart cart) {
        active = cart;
        if (cart != null) {
            for (javafx.scene.Node node : strip.getChildren()) {
                if (node instanceof HBox box && !box.getChildren().isEmpty()
                        && box.getChildren().get(0) instanceof ToggleButton tab
                        && tab.getUserData() == cart) {
                    tab.setSelected(true);
                }
            }
            cartTable.setItems(cart.lines);
            cartClient.setText("Cart for: " + (cart.client == null ? "?" : cart.client.name()));
        } else {
            cartTable.setItems(FXCollections.observableArrayList());
            cartClient.setText("No cart open - press \"+ New cart\" (or just scan: a picker pops up)");
        }
        updateTotal();
    }

    /**
     * Adds a service line (consultation, vaccination, visit...) INTO the open
     * cart - so products and the service checkout together in one line group.
     */
    private void addServiceLine() {
        Cart cart = ensureCart();
        if (cart == null) {
            return;
        }
        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.initOwner(getWindow());
        dialog.setTitle("Add a service to " + cart.client.name() + "'s cart");
        FxUtil.setAppIcon(dialog);

        javafx.scene.control.ComboBox<String> type = new javafx.scene.control.ComboBox<>(
                FXCollections.observableArrayList(SaleTransaction.TYPES));
        type.getSelectionModel().selectFirst(); // Consultation
        type.setEditable(true);
        type.setMaxWidth(Double.MAX_VALUE);

        TextField what = new TextField();
        what.setPromptText("What did you do? (e.g. wound care, teeth check)");
        what.setMaxWidth(Double.MAX_VALUE);

        TextField price = new TextField();
        price.setPromptText("Price in DA (e.g. 500)");
        price.setMaxWidth(Double.MAX_VALUE);

        Button add = new Button("Add to cart");
        add.getStyleClass().add("accent");
        add.setDefaultButton(true);
        add.setMaxWidth(Double.MAX_VALUE);
        Button cancel = new Button("Cancel");
        cancel.setMaxWidth(Double.MAX_VALUE);
        cancel.setOnAction(e -> dialog.close());

        add.setOnAction(e -> {
            double p;
            try {
                p = Double.parseDouble(price.getText() == null ? "0"
                        : price.getText().trim().replace(",", ".").replace(" ", ""));
            } catch (NumberFormatException ex) {
                FxUtil.error(dialog, "Price?", "Type the price numbers only (e.g. 500 or 250.50).");
                return;
            }
            if (p < 0) {
                FxUtil.error(dialog, "Price?", "The price cannot be negative.");
                return;
            }
            String chosenType = type.getValue() == null || type.getValue().isBlank()
                    ? "Consultation" : type.getValue().trim();
            String desc = what.getText() == null ? "" : what.getText().trim();
            CartLine line = new CartLine();
            line.medUuid = null;
            line.label = desc.isBlank() ? chosenType : chosenType + " - " + desc;
            line.type = chosenType;
            line.qty = 1;
            line.unitPrice = p;
            line.amount = round2(p);
            line.customPrice = true; // never let SET QTY rewrite a service price
            line.note = desc;
            cart.lines.add(line);
            cartTable.getSelectionModel().clearSelection();
            cartTable.getSelectionModel().select(line);
            cartTable.scrollTo(line);
            refreshStripLabels();
            updateTotal();
            SoundPlayer.playFound();
            calcStatus.setText("\"" + line.label + "\" added to the cart. "
                    + "You can still change its price with SET PRICE.");
            dialog.close();
        });

        GridPane grid = new GridPane();
        grid.getStyleClass().add("form-grid");
        grid.add(rowLabel("Service"), 0, 0);
        grid.add(type, 1, 0);
        grid.add(rowLabel("Description"), 0, 1);
        grid.add(what, 1, 1);
        grid.add(rowLabel("Price (DA) *"), 0, 2);
        grid.add(price, 1, 2);

        HBox buttons = new HBox(10, cancel, add);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        buttons.setPadding(new Insets(12, 16, 14, 16));
        VBox root = new VBox(grid, buttons);
        Ui.setScene(dialog, root, 460, 300);
        dialog.setOnShown(e -> what.requestFocus());
        dialog.showAndWait();
    }

    private static Label rowLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("form-label");
        return l;
    }

    private void removeSelectedLines() {
        if (active == null) {
            return;
        }
        List<CartLine> sel = new ArrayList<>(cartTable.getSelectionModel().getSelectedItems());
        if (sel.isEmpty()) {
            calcStatus.setText("Select the line(s) to remove first.");
            return;
        }
        sel.forEach(l -> active.lines.removeIf(x -> x.key.equals(l.key)));
        refreshStripLabels();
        updateTotal();
        calcStatus.setText(sel.size() + " line(s) removed.");
    }

    /** Finds / asks for the cart a scan or an action should go into. */
    private Cart ensureCart() {
        if (active != null) {
            return active;
        }
        Optional<Client> picked = ClientPickerDialog.show(getWindow());
        if (picked.isPresent()) {
            createCart(picked.get());
            return active;
        }
        return null;
    }

    // --------------------------------------------------------------- numpad

    private HBox buildCheckoutBar() {
        cartClient.setStyle("-fx-font-weight: bold; -fx-text-fill: #B0BEC5;");
        Button changeClient = new Button("Change client");
        changeClient.getStyleClass().add("ghost");
        changeClient.setOnAction(e -> {
            if (active == null) {
                openNewCart();
                return;
            }
            ClientPickerDialog.show(getWindow()).ifPresent(c -> {
                active.client = c;
                rebuildStripButtons();
                selectCart(active);
            });
        });

        cartTotal.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: #80CBC4;");

        paidBtn.getStyleClass().add("accent");
        paidBtn.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-padding: 10 18 10 18;");
        paidBtn.setOnAction(e -> checkout(true));
        creditBtn.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-padding: 10 18 10 18;");

        creditBtn.setOnAction(e -> checkout(false));

        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        HBox bar = new HBox(10, cartClient, changeClient, grow, cartTotal, creditBtn, paidBtn);
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private VBox buildQuickScanColumn() {
        VBox col = new VBox(8);
        col.setPadding(new Insets(10));
        col.setPrefWidth(340);

        // ---- big total on TOP of the camera: impossible to miss ------------
        Label totalCaption = new Label("TOTAL");
        totalCaption.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; "
                + "-fx-text-fill: #78909C; -fx-letter-spacing: 2;");
        bigTotal.setStyle("-fx-font-size: 34px; -fx-font-weight: bold; -fx-text-fill: #80CBC4;");
        VBox totalBanner = new VBox(0, totalCaption, bigTotal);
        totalBanner.setAlignment(Pos.CENTER);
        totalBanner.setStyle("-fx-background-color: #10181B; -fx-background-radius: 10;"
                + " -fx-padding: 8 0 8 0; -fx-border-color: #2E7D6E; -fx-border-radius: 10;"
                + " -fx-border-width: 1.5;");

        Label title = Ui.sectionTitle("Live scan");

        camera.setFitWidth(300);
        camera.setFitHeight(210);
        camera.setPreserveRatio(true);
        Label waiting = new Label("Camera preview");
        waiting.getStyleClass().add("hint");
        camBox = new StackPane(camera, waiting);
        camBox.setStyle("-fx-background-color: #10181B; -fx-background-radius: 8;");
        camBox.setMinHeight(220);
        camera.imageProperty().addListener((obs, o, img) -> waiting.setVisible(img == null));
        applyPreviewVisibility();

        lastScan.setWrapText(true);
        lastScan.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #FFCC80;");
        lastScan.setMinHeight(42);

        entry.setEditable(false);
        entry.setAlignment(Pos.CENTER_RIGHT);
        entry.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; -fx-background-color: #10181B;"
                + " -fx-text-fill: #80CBC4;");
        entry.setPromptText("0");

        HBox presets = new HBox(6);
        for (int ml : new int[]{1, 5, 10, 20}) {
            Button b = new Button(ml + " ml");
            b.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(b, Priority.ALWAYS);
            b.setOnAction(e -> applyQty(ml));
            presets.getChildren().add(b);
        }
        presets.setAlignment(Pos.CENTER);

        // digits 3x4 + action column
        GridPane digits = new GridPane();
        digits.setHgap(6);
        digits.setVgap(6);
        String[][] keys = {
                {"7", "8", "9"},
                {"4", "5", "6"},
                {"1", "2", "3"},
                {"00", "0", "."}};
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 3; c++) {
                String k = keys[r][c];
                Button b = new Button(k);
                b.setPrefSize(72, 52);
                b.setStyle("-fx-font-size: 17px; -fx-font-weight: bold;");
                b.setOnAction(e -> typeKey(k));
                digits.add(b, c, r);
            }
        }
        Button back = new Button("DEL");
        back.setPrefSize(86, 52);
        back.setOnAction(e -> backspace());
        Button clear = new Button("C");
        clear.getStyleClass().add("danger");
        clear.setPrefSize(86, 52);
        clear.setOnAction(e -> entry.setText(""));
        Button qtyBtn = new Button("SET QTY");
        qtyBtn.getStyleClass().add("accent");
        qtyBtn.setPrefSize(86, 52);
        qtyBtn.setStyle("-fx-font-weight: bold;");
        qtyBtn.setOnAction(e -> applyFromEntry(true));
        Button priceBtn = new Button("SET PRICE");
        priceBtn.setPrefSize(86, 52);
        priceBtn.setStyle("-fx-font-weight: bold;");
        priceBtn.setOnAction(e -> applyFromEntry(false));
        VBox actions = new VBox(6, back, clear, qtyBtn, priceBtn);
        HBox numpad = new HBox(8, digits, actions);
        numpad.setAlignment(Pos.CENTER);

        calcStatus.getStyleClass().add("hint");
        calcStatus.setWrapText(true);

        col.getChildren().addAll(totalBanner, title, camBox, cameraStatus, lastScan, entry,
                presets, numpad, calcStatus);
        return col;
    }

    /** Settings -> "show the live camera preview" toggle, applied instantly. */
    private void applyPreviewVisibility() {
        boolean show = dev.parent.config.AppConfig.get().cameraPreviewVisible();
        if (camBox != null) {
            camBox.setVisible(show);
            camBox.setManaged(show);
        }
    }

    // --------------------------------------------------------------- calculator

    private void typeKey(String key) {
        String cur = entry.getText() == null ? "" : entry.getText();
        if (cur.length() >= 12) {
            return;
        }
        if (".".equals(key)) {
            if (!cur.contains(".")) {
                entry.setText(cur.isEmpty() ? "0." : cur + ".");
            }
            return;
        }
        entry.setText(cur + key);
    }

    private void backspace() {
        String cur = entry.getText();
        if (cur != null && !cur.isEmpty()) {
            entry.setText(cur.substring(0, cur.length() - 1));
        }
    }

    /** Target line for the calculator: the selected one, else the last added. */
    private CartLine targetLine() {
        if (active == null || active.lines.isEmpty()) {
            return null;
        }
        CartLine sel = cartTable.getSelectionModel().getSelectedItem();
        if (sel != null) {
            return sel;
        }
        return active.lines.get(active.lines.size() - 1);
    }

    private void applyFromEntry(boolean qtyMode) {
        String raw = entry.getText();
        double v;
        try {
            v = Double.parseDouble(raw == null || raw.isBlank() ? "0" : raw.replace(",", "."));
        } catch (NumberFormatException e) {
            calcStatus.setText("\"" + raw + "\" is not a number.");
            return;
        }
        entry.setText("");
        if (qtyMode) {
            applyQty(v);
        } else {
            applyPrice(v);
        }
    }

    private void applyQty(double qty) {
        CartLine line = targetLine();
        if (line == null) {
            SoundPlayer.playNotFound();
            calcStatus.setText("Scan a product first - the number has nowhere to go.");
            return;
        }
        if (qty <= 0) {
            calcStatus.setText("Quantity must be above zero.");
            return;
        }
        line.qty = qty;
        line.recompute(); // quantity always drives the amount, custom price included
        cartTable.refresh();
        refreshStripLabels();
        updateTotal();
        calcStatus.setText("Quantity of \"" + line.label + "\" set to " + FxUtil.qty(qty)
                + " -> " + FxUtil.money(line.amount)
                + (line.customPrice ? " (unit price was typed by hand)" : ""));
    }

    private void applyPrice(double price) {
        CartLine line = targetLine();
        if (line == null) {
            SoundPlayer.playNotFound();
            calcStatus.setText("Scan a product first - the price has nowhere to go.");
            return;
        }
        if (price < 0) {
            calcStatus.setText("Price cannot be negative.");
            return;
        }
        line.amount = round2(price);
        if (line.qty > 0) {
            line.unitPrice = line.amount / line.qty; // so SET QTY later still scales it
        }
        line.customPrice = true;
        cartTable.refresh();
        refreshStripLabels();
        updateTotal();
        calcStatus.setText("Price of \"" + line.label + "\" set to " + FxUtil.money(price));
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private void updateTotal() {
        double total = active == null ? 0 : active.total();
        cartTotal.setText(FxUtil.money(total));
        bigTotal.setText(FxUtil.money(total)); // the big banner above the camera
        boolean canCheckout = active != null && !active.lines.isEmpty();
        paidBtn.setDisable(!canCheckout);
        creditBtn.setDisable(!canCheckout);
        refreshStripLabels();
    }

    // ---------------------------------------------------------------- scanning

    @Override
    public boolean handleScan(String code, Optional<Medicine> found) {
        if (found.isEmpty()) {
            SoundPlayer.playNotFound();
            lastScan.setText("Unknown code: " + code);
            if (FxUtil.confirm(getWindow(), "Unknown product",
                    "Barcode \"" + code + "\" is not registered.\nAdd it as a new medicine now?")) {
                MedicinesView.openAddDialog(getWindow(), code);
                refreshData();
            }
            return true;
        }
        SoundPlayer.playFound();
        Medicine med = found.get();
        lastScan.setText("SCANNED: " + med.name() + "  (" + FxUtil.money(med.sellPrice()) + ")");
        Cart cart = ensureCart();
        if (cart == null) {
            calcStatus.setText("No cart chosen - scan ignored.");
            return true;
        }
        CartLine line = null;
        for (CartLine l : cart.lines) {
            if (med.uuid().equals(l.medUuid)) {
                line = l;
                break;
            }
        }
        if (line == null) {
            line = new CartLine();
            line.medUuid = med.uuid();
            line.label = med.name();
            line.qty = med.fullSize() > 0 ? med.fullSize() : 1;
            line.unitPrice = med.sellPrice();
            line.amount = round2(line.qty * line.unitPrice);
            cart.lines.add(line);
        }
        cartTable.getSelectionModel().clearSelection();
        cartTable.getSelectionModel().select(line);
        cartTable.scrollTo(line);
        refreshStripLabels();
        updateTotal();
        calcStatus.setText("\"" + med.name() + "\" is in the cart. "
                + "Type a number, press SET QTY for ml / SET PRICE for a custom amount.");
        return true;
    }

    // ---------------------------------------------------------------- checkout

    private void checkout(boolean paid) {
        Cart cart = active;
        if (cart == null || cart.lines.isEmpty()) {
            return;
        }
        if (cart.client == null) {
            FxUtil.error(getWindow(), "No client", "Pick a client for this cart first.");
            return;
        }
        List<CartLine> snapshot = List.copyOf(cart.lines);
        Client client = cart.client;
        paidBtn.setDisable(true);
        creditBtn.setDisable(true);
        calcStatus.setText("Recording " + snapshot.size() + " line(s)...");
        Bg.load(() -> {
                    List<CartLine> done = new ArrayList<>();
                    List<String> failures = new ArrayList<>();
                    for (CartLine l : snapshot) {
                        try {
                            TransactionDao.insert(new SaleTransaction(null, client.uuid(), null,
                                    l.medUuid, null, OffsetDateTime.now(), l.qty, l.amount,
                                    l.type == null || l.type.isBlank() ? "Product" : l.type,
                                    l.note == null || l.note.isBlank() ? "[CART SALE]" : l.note,
                                    paid, false, null));
                            done.add(l);
                        } catch (Throwable t) {
                            failures.add(l.label + ": " + t.getMessage());
                        }
                    }
                    return new CheckoutResult(done, failures);
                },
                res -> {
                    for (CartLine l : res.done()) {
                        cart.lines.removeIf(x -> x.key.equals(l.key));
                    }
                    if (cart == active) {
                        cartTable.refresh();
                    }
                    refreshStripLabels();
                    updateTotal();
                    refreshData();
                    if (res.failures().isEmpty()) {
                        SoundPlayer.playFound();
                        calcStatus.setText("Checked out " + res.done().size() + " line(s) for "
                                + client.name() + (paid ? " (paid)" : " (credit)"));
                    } else {
                        SoundPlayer.playNotFound();
                        FxUtil.error(getWindow(), "Some lines could not be recorded",
                                String.join("\n", res.failures())
                                        + "\n\nThe failed lines stayed in the cart.");
                    }
                });
    }

    // ------------------------------------------------------------------ misc

    @Override
    public String title() {
        return "Daily Usage (POS)";
    }

    @Override
    public void refreshData() {
        applyPreviewVisibility(); // react live to the Settings toggle
        updateTotal();
    }

    private Window getWindow() {
        return getScene() == null ? MainWindow.get().stage() : getScene().getWindow();
    }
}

