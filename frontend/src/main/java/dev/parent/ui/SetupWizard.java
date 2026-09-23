package dev.parent.ui;

import dev.parent.config.AppConfig;
import dev.parent.db.SupabaseClient;
import dev.parent.util.FxUtil;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Shown the first time the program launches (and from Settings later): asks
 * for the Supabase project URL and anon API key, tests the connection and
 * saves them into config.properties.
 */
public final class SetupWizard {

    private boolean saved;

    public boolean showAndWait(Window owner) {
        Stage stage = new Stage();
        stage.initModality(Modality.APPLICATION_MODAL);
        if (owner != null) {
            stage.initOwner(owner);
        }
        stage.setTitle("Connect your online database");
        FxUtil.setAppIcon(stage);

        AppConfig cfg = AppConfig.get();

        Label title = new Label("Online database setup (Supabase)");
        title.setStyle("-fx-font-size:20px; -fx-font-weight:bold; -fx-text-fill:#80CBC4;");
        Label expl = new Label(
                """
                This program stores its data in YOUR online database so that several
                computers (and later the mobile apps) always share the same data.

                1. On https://supabase.com create a project
                2. Open its SQL Editor and run the 'supabase/schema.sql' file shipped with this app
                3. In Project Settings -> API copy the Project URL and the anon public key below""");
        expl.setWrapText(true);
        expl.getStyleClass().add("splash-sub");

        TextField urlField = new TextField(cfg.supabaseUrl());
        urlField.setPromptText("https://yourproject.supabase.co - OR http://SERVER-IP:9677 for a sister PC");
        urlField.setPrefColumnCount(32);
        PasswordField keyFieldHidden = new PasswordField();
        keyFieldHidden.setText(cfg.supabaseKey());
        keyFieldHidden.setPromptText("anon public API key");
        TextField keyFieldShown = new TextField(cfg.supabaseKey());
        keyFieldShown.setManaged(false);
        keyFieldShown.setVisible(false);
        CheckBox showKey = new CheckBox("Show key");
        showKey.selectedProperty().addListener((obs, o, on) -> {
            if (on) {
                keyFieldShown.setText(keyFieldHidden.getText());
                keyFieldHidden.setManaged(false);
                keyFieldHidden.setVisible(false);
                keyFieldShown.setManaged(true);
                keyFieldShown.setVisible(true);
            } else {
                keyFieldHidden.setText(keyFieldShown.getText());
                keyFieldShown.setManaged(false);
                keyFieldShown.setVisible(false);
                keyFieldHidden.setManaged(true);
                keyFieldHidden.setVisible(true);
            }
        });

        GridPane form = new GridPane();
        form.getStyleClass().add("form-grid");
        form.add(new Label("Project URL:"), 0, 0);
        form.add(urlField, 1, 0);
        form.add(new Label("API key (anon public):"), 0, 1);
        HBox keyBox = new HBox(10, keyFieldHidden, keyFieldShown, showKey);
        keyBox.setAlignment(Pos.CENTER_LEFT);
        form.add(keyBox, 1, 1);

        Label status = new Label(" ");
        status.setWrapText(true);
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(20, 20);
        spinner.setVisible(false);
        HBox statusRow = new HBox(10, spinner, status);
        statusRow.setAlignment(Pos.CENTER_LEFT);

        Button test = new Button("Test connection");
        Button save = new Button("Save && continue");
        save.getStyleClass().add("accent");
        save.setDefaultButton(true);
        Button cancel = new Button("Cancel");
        HBox buttons = new HBox(10, test, FxUtil.hSpacer(), cancel, save);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        Task<Boolean>[] current = new Task[1];
        Runnable runTest = () -> {
            String url = urlField.getText();
            String key = keyFieldHidden.isVisible() ? keyFieldHidden.getText() : keyFieldShown.getText();
            routeToCorrectPlace(url, key, cfg);          // Supabase direct OR clinic server
            status.setStyle("-fx-text-fill:#FFB300;");
            status.setText("Contacting the database...");
            spinner.setVisible(true);
            test.setDisable(true);
            Task<Boolean> task = new Task<>() {
                @Override
                protected Boolean call() throws Exception {
                    SupabaseClient sc = new SupabaseClient(cfg.supabaseUrl(), cfg.supabaseKey());
                    int version = sc.ping();
                    if (version != SupabaseClient.EXPECTED_SCHEMA_VERSION) {
                        throw new IllegalStateException(
                                "Connected, but the database schema version is v" + version +
                                        " and the app expects v" + SupabaseClient.EXPECTED_SCHEMA_VERSION +
                                        ". Re-run the latest supabase/schema.sql.");
                    }
                    return true;
                }
            };
            task.setOnSucceeded(e -> {
                spinner.setVisible(false);
                test.setDisable(false);
                status.setStyle("-fx-text-fill:#66BB6A;");
                status.setText("Connection OK - the online database is ready.");
            });
            task.setOnFailed(e -> {
                spinner.setVisible(false);
                test.setDisable(false);
                status.setStyle("-fx-text-fill:#EF5350;");
                Throwable ex = task.getException();
                status.setText("Connection failed: " + ex.getMessage());
            });
            current[0] = task;
            Thread t = new Thread(task, "vetms-supabase-test");
            t.setDaemon(true);
            t.start();
        };

        test.setOnAction(e -> runTest.run());

        save.setOnAction(e -> {
            String url = urlField.getText();
            String key = keyFieldHidden.isVisible() ? keyFieldHidden.getText() : keyFieldShown.getText();
            if (url == null || url.isBlank() || key == null || key.isBlank()) {
                status.setStyle("-fx-text-fill:#EF5350;");
                status.setText("Please enter both the Project URL and the API key.");
                return;
            }
            routeToCorrectPlace(url, key, cfg);
            cfg.save();
            saved = true;
            stage.close();
        });

        cancel.setOnAction(e -> stage.close());

        VBox root = new VBox(12, title, expl, form, statusRow, buttons);
        root.setPadding(new Insets(20));
        root.setPrefWidth(640);
        Scene scene = new Scene(root);
        var css = getClass().getResource("/dev/parent/res/theme.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }
        root.getStyleClass().add("root-pane");
        stage.setScene(scene);
        stage.centerOnScreen();
        stage.showAndWait();
        return saved;
    }

    /**
     * Where does this URL+key go?
     * A true Supabase project URL goes into the direct slots; anything else
     * (a sister PC points at the clinic server: http://192.168.x.x:9677)
     * becomes the clinic-server link, and {@link dev.parent.db.SupabaseClient}
     * relays every call through it.
     */
    private static void routeToCorrectPlace(String url, String key, AppConfig cfg) {
        String u = url == null ? "" : url.trim();
        String k = key == null ? "" : key.trim();
        if (u.contains(".supabase.co")) {
            cfg.setSupabase(SupabaseClient.normalizeBaseUrl(u), k);
            cfg.setRemoteServer("", "");       // direct beats relay
        } else {
            while (u.endsWith("/")) {
                u = u.substring(0, u.length() - 1);
            }
            cfg.setRemoteServer(u, k);          // key field carries the server token
        }
    }
}
