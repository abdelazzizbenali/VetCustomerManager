import mdlaf.*;
import mdlaf.themes.*;
import org.jdesktop.swingx.JXDatePicker;
import org.jdesktop.swingx.JXTitledPanel;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.plaf.basic.BasicComboBoxEditor;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.sql.*;
import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.lang.Object;
import java.util.List;
/**
 **     Author: Parent: by AAB
 **/
class VeterinaryDBMS {
    public static void main() throws SQLException {
        DatabaseConnector.initializeDatabase();
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(new MaterialLookAndFeel(new MaterialOceanicTheme()));
                UIManager.put("TabbedPane.selected", Color.BLUE);
                UIManager.put("TabbedPane.selectedForeground", Color.WHITE);
                UIManager.put("Table.selectionBackground", Color.decode("#0C0C0C"));
                UIManager.put("Table.selectionForeground", Color.WHITE);
                UIManager.put("Table.foreground", Color.BLACK);
                UIManager.put("Table.background", Color.decode("#F8FAFC"));
                UIManager.put("Table.rowHeight", 50);
                UIManager.put("TableHeader.foreground", Color.WHITE);
                UIManager.put("TableHeader.background", Color.decode("#555555"));
                UIManager.put("TableHeader.separatorColor", Color.BLACK);
                UIManager.put("Table.sortIconColor", Color.WHITE);
                UIManager.put("TableHeader.font", new Font("DejaVu",Font.PLAIN,16));
                UIManager.put("Spinner.foreground", Color.BLACK);
                UIManager.put("Spinner.background", Color.WHITE);
                UIManager.put("ComboBox.foreground", Color.WHITE);
                UIManager.put("ComboBox.font", new Font("Tahoma",Font.PLAIN,16));
                UIManager.put("ComboBox.padding", 10);
                UIManager.put("Table.font", new Font("DejaVu",Font.PLAIN,20));
                UIManager.put("TabbedPane.font", new Font("DejaVu",Font.PLAIN,20));
                UIManager.put("TabbedPane.foreground", Color.WHITE);
                UIManager.put("TextField.font", new Font("Tahoma", Font.PLAIN, 18));
                UIManager.put("TextArea.font", new Font("Tahoma", Font.PLAIN, 18));
                UIManager.put("Button.foreground", Color.WHITE);
                new MainWindow().setVisible(true);
            } catch (Exception e) {
                JOptionPane.showMessageDialog(null, "Error: " + e.getMessage());
            }
        });
    }
}
class DatabaseConnector {
    public static Connection getConnection() throws SQLException {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("Database Driver missing");
        }
        Connection connection = DriverManager.getConnection("jdbc:mysql://localhost:3306", "root", "root");
        ensureDatabaseExists(connection);
        connection.close();
        return DriverManager.getConnection("jdbc:mysql://localhost:3306/parent", "root", "root");
    }
    public static void ensureDatabaseExists(Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            ResultSet rs = stmt.executeQuery("SHOW DATABASES WHERE Database = 'parent'");
            if (!rs.next()) {
                stmt.executeUpdate("CREATE DATABASE parent");
            }
        }
    }
    public static void initializeDatabase() throws SQLException {
        try (Statement stmt = getConnection().createStatement()) {
            stmt.execute("USE parent");
            ResultSet rs = stmt.executeQuery("SHOW TABLES IN VETMSDB");
            if (!rs.next()) {
                stmt.executeUpdate("CREATE TABLE IF NOT EXISTS medicines (" +
                        "  m_id INT PRIMARY KEY AUTO_INCREMENT," +
                        "  m_name VARCHAR(100) NOT NULL," +
                        "  m_type VARCHAR(100) NOT NULL" +
                        ")ENGINE=InnoDB");
                stmt.executeUpdate("CREATE TABLE IF NOT EXISTS medicinesInfo (" +
                        "  m_id INT PRIMARY KEY," +
                        "  m_size DECIMAL(10,2) NOT NULL," +
                        "  m_full_size DECIMAL(10,2) NOT NULL," +
                        "  m_buyPrice DECIMAL(10,2) NOT NULL," +
                        "  m_sellPrice DECIMAL(10,2) NOT NULL," +
                        "  m_expiryDate DATE NOT NULL," +
                        "  m_amount INT NOT NULL," +
                        "  m_seller VARCHAR(100)," +
                        "  m_description TEXT," +
                        "  is_deleted TINYINT(1) DEFAULT 0," +
                        "  FOREIGN KEY (m_id) REFERENCES medicines(m_id) ON DELETE CASCADE" +
                        ")ENGINE=InnoDB");
                stmt.executeUpdate("CREATE TABLE IF NOT EXISTS clients (" +
                        "  client_id INT PRIMARY KEY AUTO_INCREMENT," +
                        "  name VARCHAR(100) NOT NULL," +
                        "  phone VARCHAR(20)," +
                        "  clientDescription TEXT" +
                        ")ENGINE=InnoDB");
                stmt.executeUpdate("INSERT IGNORE INTO clients (client_id, name) VALUES(-1, '[UNKNOWN CLIENT]')");
                stmt.executeUpdate("CREATE TABLE IF NOT EXISTS transactions (" +
                        "  transaction_id INT PRIMARY KEY AUTO_INCREMENT," +
                        "  client_id INT NOT NULL," +
                        "  m_id INT NOT NULL," +
                        "  date TIMESTAMP NOT NULL," +
                        "  q_sold DECIMAL(10,2) NOT NULL," +
                        "  amount DECIMAL(10,2) NOT NULL," +
                        "  type VARCHAR(512)," +
                        "  description TEXT," +
                        "  payer BOOLEAN NOT NULL," +
                        "  is_deleted TINYINT(1) DEFAULT 0," +
                        "  FOREIGN KEY (client_id) REFERENCES clients(client_id) ON DELETE CASCADE," +
                        "  FOREIGN KEY (m_id) REFERENCES medicines(m_id)" +
                        ")ENGINE=InnoDB");
                stmt.executeUpdate("CREATE TABLE IF NOT EXISTS appointments (" +
                        "  id INT AUTO_INCREMENT PRIMARY KEY," +
                        "  client_id INT," +
                        "  date date NOT NULL," +
                        "  is_done boolean NOT NULL," +
                        "  is_deleted tinyint(1) DEFAULT 0," +
                        "  description TEXT," +
                        "  FOREIGN KEY (client_id) REFERENCES clients(client_id)" +
                        ")ENGINE=InnoDB");
                stmt.executeUpdate("CREATE PROCEDURE IF NOT EXISTS SellMedicinePartial(" +
                        "    IN medicine_id INT," +
                        "    IN sell_quantity DECIMAL(10,2)" +
                        ")" +
                        "BEGIN" +
                        "    DECLARE current_size DECIMAL(10,2);" +
                        "    DECLARE full_size DECIMAL(10,2);" +
                        "    DECLARE current_amount INT;" +
                        "    START TRANSACTION;" +
                        "    SELECT m_size, m_full_size, m_amount " +
                        "    INTO current_size, full_size, current_amount " +
                        "    FROM medicinesInfo " +
                        "    WHERE m_id = medicine_id " +
                        "    FOR UPDATE;" +
                        "    IF current_amount <= 0 THEN" +
                        "        SIGNAL SQLSTATE '45000' " +
                        "        SET MESSAGE_TEXT = 'Not enough stock';" +
                        "    END IF;" +
                        "    SET current_size = current_size - sell_quantity;" +
                        "    IF current_size <= 0 THEN" +
                        "        SET current_amount = current_amount - 1;" +
                        "        SET current_size = full_size - ABS(current_size);" +
                        "    END IF;" +
                        "    IF current_amount < 0 THEN" +
                        "        SIGNAL SQLSTATE '45000' " +
                        "        SET MESSAGE_TEXT = 'Not enough stock';" +
                        "    END IF;" +
                        "    UPDATE medicinesInfo " +
                        "    SET m_amount = current_amount," +
                        "        m_size = CASE " +
                        "            WHEN current_amount > 0 THEN current_size " +
                        "            ELSE 0 " +
                        "        END" +
                        "    WHERE m_id = medicine_id;" +
                        "    COMMIT;" +
                        "END;");
            }
        } catch (SQLException e) {
            throw new SQLException("Database initialization failed: " + e.getMessage());
        }
    }
}
class MainWindow extends JFrame {
    public MainWindow() {
        initializeUI();
    }
    private void initializeUI() {
        setTitle("Veterinary Management System");
        setExtendedState(JFrame.MAXIMIZED_BOTH);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        JTabbedPane tabbedPane = new JTabbedPane(SwingConstants.TOP);
        tabbedPane.insertTab("Daily Usage", new ImageIcon(Objects.requireNonNull(getClass().getResource("res/home.png"))), new DailyUsagePanel(), "Adding daily transaction here.", 0);
        tabbedPane.insertTab("Client List", new ImageIcon(Objects.requireNonNull(getClass().getResource("res/client.png"))), new ClientPanel(), "Adding, editing and deleting client list here.", 1);
        tabbedPane.insertTab("Medicine Stock", new ImageIcon(Objects.requireNonNull(getClass().getResource("res/medicine.png"))), new MedicinePanel(), "Updating medicine stock here.", 2);
        tabbedPane.insertTab("Appointments", new ImageIcon(Objects.requireNonNull(getClass().getResource("res/calendar.png"))), new AppointmentPanel(), "Adding clients appointment.", 3);
        tabbedPane.insertTab("Settings", new ImageIcon(Objects.requireNonNull(getClass().getResource("res/reglage.png"))), new SettingPanel(), "Change the settings about this program.", 4);
        add(tabbedPane, BorderLayout.CENTER);
    }
}
class DailyUsagePanel extends JPanel {
    private final JComboBox<Client> clientCombo = new JComboBox<>();
    private final JComboBox<Medicine> medicineCombo = new JComboBox<>();
    private final JSpinner quantitySpinner = new JSpinner(new SpinnerNumberModel(0.0, 0, 10000000, 1));
    private final JSpinner amountSpinner = new JSpinner(new SpinnerNumberModel(0.0, 0, 10000000, 100));
    private final JComboBox<String> typeCombo = new JComboBox<>(new String[]{"Consultation", "Treatment", "Product"});
    private final JTextArea descriptionArea = new JTextArea();
    private final JCheckBox payedCheckBox = new JCheckBox("Payed ?");
    private final JTabbedPane tabbedPane = new JTabbedPane();
    private JTable todayTable;
    private DefaultTableModel todayModel;
    private JTable historyTable;
    private DefaultTableModel historyModel;
    public DailyUsagePanel() {
        initializeUI();
        refreshData();
    }
    private void initializeUI() {
        setLayout(new BorderLayout());
        JPanel trPanel = new JPanel(new BorderLayout(10, 10));
        trPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
        JPanel formPanel = new JPanel(new GridLayout(0, 1, 10, 10));
        typeCombo.setFont(new Font("DejaVu",Font.BOLD,20));
        descriptionArea.setFont(new Font("DejaVu",Font.BOLD,20));
        payedCheckBox.setFont(new Font("DejaVu",Font.BOLD,20));
        medicineCombo.setFont(new Font("DejaVu",Font.BOLD,20));
        medicineCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                if (value instanceof Medicine) {
                    value = ((Medicine) value).getName();
                }
                return super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            }
        });
//        medicineCombo.getEditor().getEditorComponent();
//        medicineName.getDocument().addDocumentListener(new DocumentListener() {
//            private final Timer timer = new Timer(300, _ -> performSearch());
//            @Override
//            public void insertUpdate(DocumentEvent e) {
//                triggerDelayedUpdate();
//            }
//            @Override
//            public void removeUpdate(DocumentEvent e) {
//                triggerDelayedUpdate();
//            }
//            @Override
//            public void changedUpdate(DocumentEvent e) {}
//            private void triggerDelayedUpdate() {
//                timer.stop();
//                timer.start();
//            }
//            private void performSearch() {
//                new SwingWorker<List<Medicine>, Void>() {
//                    @Override
//                    protected List<Medicine> doInBackground() throws Exception {
//                        return MedicineDAO.getAllMedicinesAsList(medicineName.getText());
//                    }
//                    @Override
//                    protected void done() {
//                        try {
//                            List<Medicine> results = get();
//                            SwingUtilities.invokeLater(() -> {
//                                medicineCombo.removeAllItems();
//                                DefaultComboBoxModel<Medicine> model = new DefaultComboBoxModel<>();
//                                results.forEach(model::addElement);
//                                medicineCombo.setPopupVisible(false);
//                                medicineCombo.setSelectedItem(null);
//                                medicineName.setText(medicineName.getText());
//                                medicineCombo.setModel(model);
//                                medicineCombo.setPopupVisible(true);
//                            });
//                        } catch (Exception ex) {
//                            ex.printStackTrace();
//                        }
//                    }
//                }.execute();
//                String typedText = medicineName.getText().toLowerCase();
//                try {
//                    List<Medicine> items = new ArrayList<>(MedicineDAO.getAllMedicinesAsList(typedText));
//                    SwingUtilities.invokeLater(() -> {
//                        DefaultComboBoxModel<Medicine> model = new DefaultComboBoxModel<>();
//                        items.forEach(model::addElement);
//                        medicineCombo.setPopupVisible(false);
//                        medicineCombo.setSelectedItem(null);
//                        medicineName.setText(typedText);
//                        medicineCombo.setPopupVisible(true);
//                    });
//                } catch (SQLException e) {
//                    throw new RuntimeException(e);
//                }
//            }
//        });
        clientCombo.setFont(new Font("DejaVu",Font.BOLD,20));
        clientCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                if (value instanceof Client) {
                    value = ((Client) value).getName();
                }
                return super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            }
        });
        quantitySpinner.setFont(new Font("DejaVu",Font.BOLD,20));
        amountSpinner.setFont(new Font("DejaVu",Font.BOLD,20));
        formPanel.add(new JLabel("Client:")).setFont(new Font("DejaVu",Font.BOLD,20));
        formPanel.add(clientCombo);
        formPanel.add(new JLabel("Medicine:")).setFont(new Font("DejaVu",Font.BOLD,20));
        formPanel.add(medicineCombo);
        formPanel.add(new JLabel("Quantity: (en ml)")).setFont(new Font("DejaVu",Font.BOLD,20));
        formPanel.add(quantitySpinner);
        formPanel.add(new JLabel("Amount*:")).setFont(new Font("DejaVu",Font.BOLD,20));
        formPanel.add(amountSpinner);
        formPanel.add(new JLabel("Type*:")).setFont(new Font("DejaVu",Font.BOLD,20));
        formPanel.add(typeCombo);
        formPanel.add(new JLabel("Description:")).setFont(new Font("DejaVu",Font.BOLD,20));
        formPanel.add(new JScrollPane(descriptionArea));
        formPanel.add(payedCheckBox);
        loadMedicines();
        loadClients();
        JButton btnSave = new JButton("Save");
        btnSave.addActionListener(_ -> saveTransaction());
        trPanel.add(formPanel, BorderLayout.CENTER);
        trPanel.add(btnSave, BorderLayout.SOUTH);
        JPanel todayPanel = new JPanel(new BorderLayout());
        todayModel = new DefaultTableModel(new String[]{"ID", "Time", "Client", "Medicine", "Quantity", "Amount", "Type", "Description", "Payed"}, 0) {
            @Override
            public boolean isCellEditable(int row, int col) {
                return false;
            }
            @Override
            public Class<?> getColumnClass(int column) {
                return switch (column) {
                    case 0 -> Integer.class;
                    case 1 -> LocalDate.class;
                    case 4, 5 -> Double.class;
                    case 8 -> Boolean.class;
                    default -> String.class;
                };
            }
        };
        todayTable = new JTable(todayModel);
        todayTable.removeColumn(todayTable.getColumnModel().getColumn(0));
        todayTable.setRowMargin(1);
        JToolBar todayToolbar = new JToolBar();
        addButton(todayToolbar, "Delete", new ImageIcon(Objects.requireNonNull(getClass().getResource("res/delete.png"))), _ -> deleteTodayTransactions());
        addButton(todayToolbar, "Set payed", new ImageIcon(Objects.requireNonNull(getClass().getResource("res/coin.png"))), _ -> setTransactionsPayed());
        addButton(todayToolbar, "Refresh", new ImageIcon(Objects.requireNonNull(getClass().getResource("res/refresh.png"))), _ -> refreshData());
        todayPanel.add(todayToolbar, BorderLayout.NORTH);
        todayPanel.add(new JScrollPane(todayTable), BorderLayout.CENTER);
        todayPanel.add(trPanel, BorderLayout.EAST);
        JPanel historyPanel = new JPanel(new BorderLayout());
        historyModel = new DefaultTableModel(new String[]{"Date", "Total Transactions", "Total Amount"}, 0) {
            @Override
            public boolean isCellEditable(int row, int col) {
                return false;
            }
            @Override
            public Class<?> getColumnClass(int column) {
                return switch (column) {
                    case 0 -> LocalDate.class;
                    case 1 -> Integer.class;
                    case 2 -> Double.class;
                    default -> Object.class;
                };
            }
        };
        historyTable = new JTable(historyModel);
        historyTable.setRowMargin(1);
        historyTable.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = historyTable.rowAtPoint(e.getPoint());
                    LocalDate date = (LocalDate) historyModel.getValueAt(row, 0);
                    showDateTransactions(date);
                }
            }
        });
        historyPanel.add(new JScrollPane(historyTable), BorderLayout.CENTER);
        tabbedPane.insertTab("Today Transactions", new ImageIcon(), todayPanel, "Transactions happened today", 0);
        tabbedPane.insertTab("Transaction's History", new ImageIcon(), historyPanel, "Transaction's history", 1);
        add(tabbedPane, BorderLayout.CENTER);
        revalidate();
        repaint();
    }
    private void addButton(JToolBar bar, String text, ImageIcon icon, ActionListener action) {
        JButton btn = new JButton(text);
        btn.addActionListener(action);
        btn.setIcon(icon);
        btn.setIconTextGap(15);
        btn.setFont(new Font("DejaVu Bold", Font.PLAIN, 18));
        btn.setPreferredSize(new Dimension(140,50));
        bar.addSeparator(new Dimension(10,0));
        bar.add(btn);
        bar.addSeparator(new Dimension(10,0));
    }
    private void loadTodayData() {
        try {
            todayModel.setRowCount(0);
            ResultSet rs = DailyUsageDAO.getTodaysTransactions();
            while (rs.next()) {
                todayModel.addRow(new Object[]{
                        rs.getInt("transaction_id"),
                        rs.getTimestamp("date").toLocalDateTime().toLocalTime(),
                        rs.getString("name"),
                        rs.getString("m_name"),
                        rs.getDouble("q_sold"),
                        rs.getDouble("amount"),
                        rs.getString("type"),
                        rs.getString("description"),
                        rs.getBoolean("payer")
                });
            }
        } catch (SQLException e) {
            showError("Error loading today's data: " + e.getMessage());
        }
    }
    private void loadHistoryData() {
        try {
            historyModel.setRowCount(0);
            ResultSet rs = DailyUsageDAO.getDailySummary();
            while (rs.next()) {
                historyModel.addRow(new Object[]{
                        rs.getDate("date").toLocalDate(),
                        rs.getInt("transaction_count"),
                        rs.getDouble("total_amount")
                });
            }
        } catch (SQLException e) {
            showError("Error loading history: " + e.getMessage());
        }
    }
    private void showDateTransactions(LocalDate date) {
        JDialog dialog = new JDialog((Frame) SwingUtilities.getWindowAncestor(this),
                "Transactions for " + date.format(DateTimeFormatter.ISO_DATE), true);
        dialog.add(new DailyTransactionsPanel(date));
        dialog.pack();
        dialog.setSize(800, 600);
        dialog.setVisible(true);
    }
    private void deleteTodayTransactions() {
        int[] row = todayTable.getSelectedRows();
        if (row.length == 0) {
            showError("Select at least one transaction.");
            return;
        }
        int confirm = JOptionPane.showConfirmDialog(this, "Delete selected transactions ? ", "Confirm Delete", JOptionPane.YES_NO_OPTION);
        if (confirm == JOptionPane.YES_OPTION) {
            try {
                for (int n : row) {
                    DailyUsageDAO.deleteTransaction((int) todayModel.getValueAt(todayTable.convertRowIndexToModel(n), 0));
                }
                refreshData();
            } catch (SQLException e) {
                showError("Delete failed: " + e.getMessage());
            }
        }
    }
    private void setTransactionsPayed() {
        int[] row = todayTable.getSelectedRows();
        if (row.length == 0) {
            showError("Select at least one transaction.");
            return;
        }
        int confirm = JOptionPane.showConfirmDialog(this, "Set selected transactions payed ?", "Client pay these", JOptionPane.YES_NO_OPTION);
        if (confirm == JOptionPane.YES_OPTION) {
            try {
                for (int n : row) {
                    int modelRow = todayTable.convertRowIndexToModel(n);
                    DailyUsageDAO.setTransactionPayed((int) todayModel.getValueAt(modelRow, 0));
                }
                refreshData();
            } catch (SQLException e) {
                showError("Setting failed: " + e.getMessage());
            }
        }
    }
    public void refreshData() {
        loadTodayData();
        loadHistoryData();
    }
    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "Transaction Error", JOptionPane.ERROR_MESSAGE);
    }
    private void loadMedicines() {
        try {
            List<Medicine> medicines = MedicineDAO.getAllMedicinesAsList("");
            medicines.forEach(medicineCombo::addItem);
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Error loading medicines: " + e.getMessage());
        }
    }
    private void loadClients() {
        try {
            List<Client> clients = ClientDAO.getAllClientsAsList();
            clients.forEach(clientCombo::addItem);
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Error loading clients: " + e.getMessage());
        }
    }
    private void saveTransaction() {
        try {
            TransactionDAO.addTransaction(
                    ((Client) Objects.requireNonNull(clientCombo.getSelectedItem())).getId() ,
                    ((Medicine) Objects.requireNonNull(medicineCombo.getSelectedItem())).getId(),
                    LocalDateTime.now(),
                    (Double) quantitySpinner.getValue(),
                    (Double) amountSpinner.getValue(),
                    (String) typeCombo.getSelectedItem(),
                    descriptionArea.getText(),
                    payedCheckBox.isSelected()
            );
            refreshData();
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Error saving: " + e.getMessage());
        }
    }
}
class DailyTransactionsPanel extends JPanel {
    private final LocalDate date;
    private DefaultTableModel model;
    private JTable table;
    private JLabel statusLabel;
    public DailyTransactionsPanel(LocalDate date) {
        this.date = date;
        initializeUI();
        loadData();
    }
    private void initializeUI() {
        setLayout(new BorderLayout());
        setSize(600, 500);
        model = new DefaultTableModel(new String[]{"Time", "Client", "Medicine", "Quantity", "Amount", "Type", "Description", "Payed"}, 0);
        table = new JTable(model);
        statusLabel = new JLabel();
        statusLabel.setBorder(new EmptyBorder(10, 15, 10, 15));
        statusLabel.setBackground(new Color(0, 0, 0));
        statusLabel.setForeground(Color.WHITE);
        statusLabel.setOpaque(true);
        statusLabel.setFont(new Font("DejaVu", Font.PLAIN, 18));
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);
        revalidate();
        repaint();
    }
    private void loadData() {
        try {
            ResultSet rs = DailyUsageDAO.getTransactionsByDate(date);
            while (rs.next()) {
                model.addRow(new Object[]{
                        rs.getTimestamp("date").toLocalDateTime().toLocalTime(),
                        rs.getString("name"),
                        rs.getString("m_name"),
                        rs.getDouble("q_sold"),
                        rs.getDouble("amount"),
                        rs.getString("type"),
                        rs.getString("description"),
                        rs.getBoolean("payer")
                });
            }
            statusLabel.setText(" Loaded " + model.getRowCount() + " transaction.");
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Error loading data: " + e.getMessage());
        }
    }
}
class ClientPanel extends JPanel {
    private JTable clientTable;
    private DefaultTableModel clientModel;
    private JLabel statusLabel;
    private JTextField searchField;
    public ClientPanel() {
        initializeUI();
        loadData();
    }
    private void initializeUI() {
        setLayout(new BorderLayout());
        String[] columnNames = {"ID", "Name", "Payed", "Non Payed"};
        clientModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        clientTable = new JTable(clientModel);
        clientTable.setAutoCreateRowSorter(true);
        clientTable.removeColumn(clientTable.getColumnModel().getColumn(0));
        clientTable.setRowMargin(1);
        clientTable.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int viewRow = clientTable.rowAtPoint(e.getPoint());
                    if (viewRow != -1) {
                        int modelRow = clientTable.convertRowIndexToModel(viewRow);
                        int clientId = (int) clientModel.getValueAt(modelRow, 0);
                        String clientName = (String) clientModel.getValueAt(modelRow, 1);
                        showTransactionsWindow(clientId, clientName);
                    }
                }
            }
        });
        searchField = new JTextField(20);
        searchField.addActionListener(_ -> refreshData());
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                refreshData();
            }
            @Override
            public void removeUpdate(DocumentEvent e) {
                refreshData();
            }
            @Override
            public void changedUpdate(DocumentEvent e) {
                refreshData();
            }
        });
        JScrollPane scrollPane = new JScrollPane(clientTable);
        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        addButton(toolBar, "Add", new ImageIcon(Objects.requireNonNull(getClass().getResource("res/add.png"))), "Add new client", _ -> showAddDialog(), KeyEvent.VK_ADD);
        addButton(toolBar, "Edit", new ImageIcon(Objects.requireNonNull(getClass().getResource("res/edit.png"))), "Edit selected", _ -> showEditDialog(), KeyEvent.VK_S);
        addButton(toolBar, "Delete", new ImageIcon(Objects.requireNonNull(getClass().getResource("res/delete.png"))), "Delete selected", _ -> deleteClients(), KeyEvent.VK_MINUS);
        addButton(toolBar, "Refresh", new ImageIcon(Objects.requireNonNull(getClass().getResource("res/refresh.png"))), "Refresh data", _ -> refreshData(), KeyEvent.VK_F1);
        toolBar.add(new JLabel("   Search :   ")).setFont(new Font("DejaVu",Font.BOLD,20));
        toolBar.add(searchField,BorderLayout.WEST);
        statusLabel = new JLabel(" Ready");
        statusLabel.setBorder(new EmptyBorder(10, 10, 10, 10));
        statusLabel.setBackground(new Color(0, 0, 0));
        statusLabel.setForeground(Color.WHITE);
        statusLabel.setOpaque(true);
        statusLabel.setFont(new Font("DejaVu",Font.PLAIN,18));
        add(toolBar, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);
        revalidate();
        repaint();
    }
    private void addButton(JToolBar bar, String text, ImageIcon icon, String tooltip, ActionListener action, int shortKey) {
        JButton btn = new JButton(text);
        btn.setToolTipText(tooltip);
        btn.addActionListener(action);
        btn.setIcon(icon);
        btn.setIconTextGap(15);
        btn.setFont(new Font("DejaVu",Font.PLAIN,18));
        btn.setPreferredSize(new Dimension(140,50));
        btn.setMnemonic(shortKey);
        bar.addSeparator(new Dimension(10,0));
        bar.add(btn);
        bar.addSeparator(new Dimension(10,0));
    }
    private void showTransactionsWindow(int clientId, String clientName) {
        JDialog dialog = new JDialog((Frame) SwingUtilities.getWindowAncestor(this),
                "Transactions for " + clientName, true);
        dialog.add(new TransactionPanel(clientId));
        dialog.pack();
        dialog.setSize(800, 400);
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }
    private void loadData() {
        String searchTerm = searchField.getText();
        try {
            ResultSet rs;
            if (searchTerm.isEmpty()) {
                rs = ClientDAO.getAllClients();
            } else {
                rs = ClientDAO.searchClientByName(searchTerm);
            }
            clientModel.setRowCount(0);
            while (rs.next()) {
                Object[] row = {
                        rs.getInt("client_id"),
                        rs.getString("name"),
                        rs.getDouble("total_payed"),
                        rs.getDouble("total_notPayed")
                };
                clientModel.addRow(row);
            }
            statusLabel.setText("Loaded " + clientModel.getRowCount() + " clients");
        } catch (SQLException e) {
            showError("Search or Load failed: " + e.getMessage());
        }
    }
    private void showAddDialog() {
        new ClientDialog(null, "Add Client", -1).setVisible(true);
        refreshData();
    }
    private void showEditDialog() {
        int row = clientTable.getSelectedRow();
        if (row == -1) {
            showError("Please select a client to edit");
            return;
        }
        int modelRow = clientTable.convertRowIndexToModel(row);
        int id = (int) clientModel.getValueAt(modelRow, 0);
        new ClientDialog(null, "Edit Client", id).setVisible(true);
        refreshData();
    }
    private void deleteClients() {
        int[] row = clientTable.getSelectedRows();
        if (row.length == 0) {
            showError("Select at least one client.");
            return;
        }
        int confirm = JOptionPane.showConfirmDialog(this, "Delete selected " + clientTable.getSelectedRows().length + " clients ?", "Confirm Delete", JOptionPane.YES_NO_OPTION);
        if (confirm == JOptionPane.YES_OPTION) {
            try {
                for (int n : row) {
                    ClientDAO.deleteClient((int) clientModel.getValueAt(clientTable.convertRowIndexToModel(n), 0));
                }
                refreshData();
            } catch (SQLException e) {
                showError("Delete failed: " + e.getMessage());
            }
        }
    }
    public void refreshData() {
        loadData();
        statusLabel.setText("Loaded " + clientModel.getRowCount() + " clients");
    }
    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "Client Error", JOptionPane.ERROR_MESSAGE);
    }
}
class TransactionPanel extends JPanel {
    private final int clientId;
    private DefaultTableModel tableModel;
    private JTable transactionTable;
    private JLabel statusLabel;
    public TransactionPanel(int clientId) {
        this.clientId = clientId;
        initializeUI();
        loadData();
    }
    private void initializeUI() {
        setSize(1100, 900);
        setLayout(new BorderLayout());
        String[] columns = {"ID", "Date", "Medicine Name", "Quantity", "Amount", "Type", "Description", "Payer"};
        tableModel = new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int row, int column) {
                return false;
            }
            @Override public Class<?> getColumnClass(int columns) {
                return switch (columns) {
                    case 0 -> LocalDateTime.class;
                    case 2, 3 -> Double.class;
                    case 6 -> Boolean.class;
                    default -> String.class;
                };
            }
        };
        transactionTable = new JTable(tableModel);
        transactionTable.setAutoCreateRowSorter(true);
        transactionTable.removeColumn(transactionTable.getColumnModel().getColumn(0));
        transactionTable.setRowMargin(1);
        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        addButton(toolBar, "Add", new ImageIcon(Objects.requireNonNull(getClass().getResource("res/add.png"))), "Add new transaction", _ -> showAddDialog());
        addButton(toolBar, "Delete", new ImageIcon(Objects.requireNonNull(getClass().getResource("res/delete.png"))), "Delete selected", _ -> deleteTransactions());
        addButton(toolBar, "Set payed", new ImageIcon(Objects.requireNonNull(getClass().getResource("res/coin.png"))), "Set selected as payed", _ -> setTransactionsPayed());
        addButton(toolBar, "Refresh", new ImageIcon(Objects.requireNonNull(getClass().getResource("res/refresh.png"))), "Refresh data", _ -> refreshData());
        statusLabel = new JLabel(" Ready");
        statusLabel.setBorder(new EmptyBorder(10, 10, 10, 10));
        statusLabel.setBackground(new Color(0, 0, 0));
        statusLabel.setForeground(Color.WHITE);
        statusLabel.setBorder(new EmptyBorder(10,10,10,10));
        statusLabel.setOpaque(true);
        statusLabel.setFont(new Font("DejaVu",Font.PLAIN,18));
        add(toolBar, BorderLayout.NORTH);
        add(new JScrollPane(transactionTable), BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);
        revalidate();
        repaint();
    }
    private void addButton(JToolBar bar, String text, ImageIcon icon, String tooltip, ActionListener action) {
        JButton btn = new JButton(text);
        btn.setIcon(icon);
        btn.setIconTextGap(15);
        btn.setFont(new Font( "DejaVu", Font.PLAIN, 18));
        btn.setPreferredSize(new Dimension(140,50));
        btn.setToolTipText(tooltip);
        btn.addActionListener(action);
        bar.addSeparator(new Dimension(10,0));
        bar.add(btn);
        bar.addSeparator(new Dimension(10,0));
    }
    private void loadData() {
        try {
            tableModel.setRowCount(0);
            ResultSet rs = TransactionDAO.getTransactionsByClient(clientId);
            while (rs.next()) {
                tableModel.addRow(new Object[]{
                        rs.getInt("transaction_id"),
                        rs.getTimestamp("date").toLocalDateTime().format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy - HH:mm:ss", Locale.FRENCH)),
                        rs.getString("m_name"),
                        rs.getDouble("q_sold"),
                        rs.getDouble("amount"),
                        rs.getString("type"),
                        rs.getString("description"),
                        rs.getBoolean("payer")
                });
            }
            statusLabel.setText("Loaded " + tableModel.getRowCount() + " transactions");
        } catch (SQLException e) {
            showError("Load failed: " + e.getMessage());
        }
    }
    private void showAddDialog() {
        new TransactionDialog(null, "Add Transaction", clientId).setVisible(true);
        refreshData();
    }
    private void deleteTransactions() {
        int[] row = transactionTable.getSelectedRows();
        if (row.length == 0) {
            showError("Select at least one transaction.");
            return;
        }
        int confirm = JOptionPane.showConfirmDialog(this, "Delete selected transactions ? ", "Confirm Delete", JOptionPane.YES_NO_OPTION);
        if (confirm == JOptionPane.YES_OPTION) {
            try {
                for (int n : row) {
                    TransactionDAO.deleteTransaction((int) tableModel.getValueAt(transactionTable.convertRowIndexToModel(n), 0));
                }
                refreshData();
            } catch (SQLException e) {
                showError("Delete failed: " + e.getMessage());
            }
        }
    }
    private void setTransactionsPayed() {
        int[] row = transactionTable.getSelectedRows();
        if (row.length == 0) {
            showError("Select at least one transaction.");
            return;
        }
        int confirm = JOptionPane.showConfirmDialog(this, "Set transactions payed ?", "Client pay these", JOptionPane.YES_NO_OPTION);
        if (confirm == JOptionPane.YES_OPTION) {
            try {
                for (int n : row) {
                    int modelRow = transactionTable.convertRowIndexToModel(n);
                    TransactionDAO.setTransactionPayed((int) tableModel.getValueAt(modelRow, 0));
                }
                refreshData();
            } catch (SQLException e) {
                showError("Setting failed: " + e.getMessage());
            }
        }
    }
    public void refreshData() {
        loadData();
        statusLabel.setText("Loaded " + tableModel.getRowCount() + " transactions");
    }
    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "Transaction Error", JOptionPane.ERROR_MESSAGE);
    }
}
class MedicinePanel extends JPanel {
    private JTable dataTable;
    private DefaultTableModel tableModel;
    private JLabel statusLabel;
    private JTextField searchField;
    private final JLabel TMSP = new JLabel();
    private final JLabel TMBP = new JLabel();
    private final JLabel TSP = new JLabel();
    private final JLabel TBP = new JLabel();
    private final JLabel TS = new JLabel();
    public MedicinePanel() {
        initializeUI();
        loadData();
    }
    private void initializeUI() {
        setLayout(new BorderLayout());
        String[] columnNames = {"ID", "Medicine Name", "Size(ml)", "Type", "Buy Price(DA)", "Sell Price(DA)", "Expiry Date", "Stock"};
        tableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return switch (columnIndex) {
                    case 0, 7 -> Integer.class;
                    case 2, 4, 5 -> Double.class;
                    default -> String.class;
                };
            }
        };
        searchField = new JTextField(20);
        dataTable = new JTable(tableModel);
        dataTable.setAutoCreateRowSorter(true);
        dataTable.removeColumn(dataTable.getColumnModel().getColumn(0));
        dataTable.setRowMargin(1);
        dataTable.setShowHorizontalLines(true);
        JPanel infoPanel = new JPanel();
        infoPanel.setBorder(new TitledBorder(new EmptyBorder(5,5,5,5),"Info Area"));
        infoPanel.setLayout(new GridLayout(5,0,5,5));
        infoPanel.add(new JLabel("Total Medicine Selling Price : "));
        infoPanel.add(TMSP);
        infoPanel.add(new JLabel("Total Medicine Buying Price : "));
        infoPanel.add(TMBP);
        infoPanel.add(new JLabel("Total Selling Price : "));
        infoPanel.add(TSP);
        infoPanel.add(new JLabel("Total Buying Price : "));
        infoPanel.add(TBP);
        infoPanel.add(new JLabel("Total Stock : "));
        infoPanel.add(TS);
        JScrollPane scrollPane = new JScrollPane(dataTable);
        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        searchField.addActionListener(_ -> refreshData());
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                refreshData();
            }
            @Override
            public void removeUpdate(DocumentEvent e) {
                refreshData();
            }
            @Override
            public void changedUpdate(DocumentEvent e) {
                refreshData();
            }
        });
        addButton(toolBar, "Add", new ImageIcon(Objects.requireNonNull(getClass().getResource("res/add.png"))), "Add new medicine", this::showAddDialog, KeyEvent.VK_ADD);
        addButton(toolBar, "Edit", new ImageIcon(Objects.requireNonNull(getClass().getResource("res/edit.png"))), "Edit selected", this::showEditDialog, KeyEvent.VK_E);
        addButton(toolBar, "Delete", new ImageIcon(Objects.requireNonNull(getClass().getResource("res/delete.png"))), "Delete selected", this::deleteMedicines, KeyEvent.VK_MINUS);
        addButton(toolBar, "Refresh", new ImageIcon(Objects.requireNonNull(getClass().getResource("res/refresh.png"))), "Refresh data", _ -> refreshData(), KeyEvent.VK_F1);
        toolBar.add(new JLabel("   Search :   ")).setFont(new Font("DejaVu", Font.BOLD, 20));
        toolBar.add(searchField, BorderLayout.WEST);
        statusLabel = new JLabel(" Ready");
        statusLabel.setBorder(new EmptyBorder(10, 10, 10, 10));
        statusLabel.setBackground(new Color(0, 0, 0));
        statusLabel.setForeground(Color.WHITE);
        statusLabel.setOpaque(true);
        statusLabel.setFont(new Font("DejaVu",Font.PLAIN,18));
        add(toolBar, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
        add(infoPanel, BorderLayout.WEST);
        add(statusLabel, BorderLayout.SOUTH);
        revalidate();
        repaint();
    }
    private void addButton(JToolBar bar, String text, ImageIcon icon, String tooltip, ActionListener action, int shortKey) {
        JButton btn = new JButton(text);
        btn.setToolTipText(tooltip);
        btn.addActionListener(action);
        btn.setIcon(icon);
        btn.setIconTextGap(15);
        btn.setFont(new Font("DejaVu", Font.PLAIN, 18));
        btn.setPreferredSize(new Dimension(140,50));
        btn.setMnemonic(shortKey);
        bar.addSeparator(new Dimension(10,0));
        bar.add(btn);
        bar.addSeparator(new Dimension(10,0));
    }
    private void loadData() {
        String searchTerm = searchField.getText();
        try {
            ResultSet rs;
            if (searchTerm.isEmpty()) {
                rs = MedicineDAO.searchMedicinesByName("");
            } else {
                rs = MedicineDAO.searchMedicinesByName(searchTerm);
            }
            tableModel.setRowCount(0);
            while (rs.next()) {
                Object[] row = {
                        rs.getInt("m_id"),
                        rs.getString("m_name"),
                        rs.getDouble("m_size"),
                        rs.getString("m_type"),
                        rs.getDouble("m_buyPrice"),
                        rs.getDouble("m_sellPrice"),
                        rs.getDate("m_expiryDate"),
                        rs.getInt("m_amount")
                };
                tableModel.addRow(row);
            }
            statusLabel.setText("Loaded " + tableModel.getRowCount() + " medicines");
        } catch (SQLException ex) {
            showError("Search or Load failed: " + ex.getMessage());
        }
    }
    private void updateInfoPanel() {
        new SwingWorker<ResultSet, Void>() {
            @Override
            protected ResultSet doInBackground() throws Exception {
                return MedicineDAO.getMedicineInfo();
            }
            @Override
            protected void done() {
                try {
                    ResultSet results = get();
                    SwingUtilities.invokeLater(() -> {

                    });
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(null, ex.getMessage(), "Loading Medicine Info Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }
    private void showAddDialog(ActionEvent e) {
        new MedicineDialog(null, "Add Medicine", -1).setVisible(true);
        refreshData();
    }
    private void showEditDialog(ActionEvent e) {
        int viewRow = dataTable.getSelectedRow();
        if (viewRow == -1) {
            showError("Please select a medicine to edit");
            return;
        }
        int modelRow = dataTable.convertRowIndexToModel(viewRow);
        int id = (int) tableModel.getValueAt(modelRow, 0);
        new MedicineDialog(null, "Edit Medicine", id).setVisible(true);
        refreshData();
    }
    private void deleteMedicines(ActionEvent e) {
        int[] row = dataTable.getSelectedRows();
        if (row.length == 0) {
            showError("Select at least one medicine.");
            return;
        }
        int confirm = JOptionPane.showConfirmDialog(this, "Delete selected " + dataTable.getSelectedRows().length + " medicines ?", "Confirm Delete", JOptionPane.YES_NO_OPTION);
        if (confirm == JOptionPane.YES_OPTION) {
            try {
                for (int n : row) {
                    MedicineDAO.deleteMedicine((int) tableModel.getValueAt(dataTable.convertRowIndexToModel(n), 0));
                }
                refreshData();
            } catch (SQLException ex) {
                showError("Delete failed: " + ex.getMessage());
            }
        }
    }
    public void refreshData() {
        loadData();
        updateInfoPanel();
        statusLabel.setText("Loaded " + tableModel.getRowCount() + " medicines");
    }
    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "Medicine Error", JOptionPane.ERROR_MESSAGE);
    }
}
class AppointmentPanel extends JPanel {
    public AppointmentPanel() {
        initializeUI();
    }
    public void initializeUI() {
        setBorder(new EmptyBorder(20, 20, 20, 20));
        setLayout(new GridLayout(5, 7, 10, 10));
        refreshData();
        revalidate();
        repaint();
    }
    public void refreshData() {
        removeAll();
        for (int i = 0; i < 35; i++) {
            loadAppointmentByDate(LocalDate.now().minusDays(3).plusDays(i));
            int finalI = i;
            addButton(String.format("<html><center>%s<br>%s</center></html>",
                            LocalDate.now().minusDays(3).plusDays(i).format(DateTimeFormatter.ofPattern("dd MMMM")), String.join(", ", loadAppointmentByDate(LocalDate.now().minusDays(3).plusDays(i)))),
                    _ -> showAppointmentDialog(LocalDate.now().minusDays(3).plusDays(finalI)));
        }
    }
    private void addButton(String text, ActionListener action) {
        JButton btn = new JButton(text);
        btn.addActionListener(action);
        btn.setFont(new Font("DejaVu", Font.PLAIN, 20));
        btn.setForeground(Color.WHITE);
        add(btn);
    }
    private void showAppointmentDialog(LocalDate date) {
        new AppointmentDialog(this, date, loadAppointmentByDate(date).isEmpty()).setVisible(true);
        refreshData();
    }
    private List<String> loadAppointmentByDate(LocalDate date) {
        List<String> clients = new ArrayList<>();
        try {
            ResultSet rs = AppointmentDAO.getAppointmentByDate(date);
            while (rs.next()) {
                clients.add(rs.getString("name"));
            }
        } catch (SQLException e) {
            showError("Load failed: " + e.getMessage());
        }
        return clients;
    }
    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "Appointment Error", JOptionPane.ERROR_MESSAGE);
    }
}
class SettingPanel extends JPanel {
    JFileChooser fileChooser;
    public SettingPanel() {
        initializeUI();
        loadSettings();
        refreshData();
    }
    public void initializeUI() {
        setLayout(new GridLayout(0, 2, 5, 5));
        addButton("Load Data", new ImageIcon(Objects.requireNonNull(getClass().getResource("res/database_add.png"))), "Load data to a specific table in the database.", this::loadFile);
        addButton("Button text that is coming soon", new ImageIcon(Objects.requireNonNull(getClass().getResource("res/database_add.png"))), "This is another button.", this::loadFile);
    }
    private void addButton(String text, ImageIcon icon, String tooltip, ActionListener action) {
        JButton btn = new JButton(text);
        btn.setLayout(new BorderLayout(20, 20));
        btn.setSize(new Dimension(35, 35));
        btn.setToolTipText(tooltip);
        btn.addActionListener(action);
        btn.setIcon(icon);
        btn.setIconTextGap(15);
        btn.setFont(new Font("DejaVu", Font.PLAIN, 18));
        btn.setPreferredSize(new Dimension(140, 50));
        add(btn);
    }
    public void loadSettings() {
//        ObjectMapper objectMapper = new ObjectMapper();
    }
    public void loadFile(ActionEvent e) {
        fileChooser = new JFileChooser();
        if (fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            String path = String.valueOf(new File(fileChooser.getSelectedFile().getAbsolutePath()));
            System.out.println(path);
        }
    }
    public void refreshData() {
    }
}
class MedicineDAO {
    public static List<Medicine> getAllMedicinesAsList(String term) throws SQLException {
        List<Medicine> medicines = new ArrayList<>();
        ResultSet rs = searchMedicinesByName(term);
        while (rs.next()) {
            medicines.add(new Medicine(
                    rs.getInt("m_id"),
                    rs.getString("m_name")
            ));
        }
        return medicines;
    }
    public static int addMedicine(String name, String type) throws SQLException {
        String query = "INSERT INTO medicines (m_name, m_type) VALUES (?, ?)";
        try (Connection conn = DatabaseConnector.getConnection();
             PreparedStatement stmt = conn.prepareStatement(query, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, name);
            stmt.setString(2, type);
            stmt.executeUpdate();
            try (ResultSet generatedKeys = stmt.getGeneratedKeys()) {
                if (generatedKeys.next()) {
                    return generatedKeys.getInt(1);
                }
            }
        }
        throw new SQLException("Failed to get generated ID");
    }
    public static void addMedicineInfo(int m_id, double size, double fullSize, double buyPrice, double sellPrice, LocalDate expiryDate, int amount) throws SQLException {
        String query = "INSERT INTO medicinesInfo (m_id, m_size, m_full_size, m_buyPrice, m_sellPrice, m_expiryDate, m_amount, is_deleted) VALUES (?, ?, ?, ?, ?, ?, ?, 0)";
        try (Connection conn = DatabaseConnector.getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {
            stmt.setInt(1, m_id);
            stmt.setDouble(2, size);
            stmt.setDouble(3, fullSize);
            stmt.setDouble(4, buyPrice);
            stmt.setDouble(5, sellPrice);
            stmt.setDate(6, Date.valueOf(expiryDate));
            stmt.setInt(7, amount);
            stmt.executeUpdate();
        }
    }
    public static void updateMedicine(int id, String name, String type) throws SQLException {
        String sql = "UPDATE medicines SET m_name = ?, m_type = ? WHERE m_id = ?";
        try (Connection conn = DatabaseConnector.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, name);
            stmt.setString(2, type);
            stmt.setInt(3, id);
            stmt.executeUpdate();
        }
    }
    public static void updateMedicineInfo(int m_id, double size, double fullSize, double buyPrice, double sellPrice, LocalDate expiryDate, int amount) throws SQLException {
        String sql = "UPDATE medicinesInfo SET m_size = ?, m_full_size = ?, m_buyPrice = ?, m_sellPrice = ?, m_expiryDate = ?, m_amount = ? WHERE m_id = ?";
        try (PreparedStatement stmt = DatabaseConnector.getConnection().prepareStatement(sql)) {
            stmt.setDouble(1, size);
            stmt.setDouble(2, fullSize);
            stmt.setDouble(3, buyPrice);
            stmt.setDouble(4, sellPrice);
            stmt.setDate(5, Date.valueOf(expiryDate));
            stmt.setInt(6, amount);
            stmt.setInt(7, m_id);
            stmt.executeUpdate();
        }
    }
    public static void deleteMedicine(int id) throws SQLException {
        String query = "UPDATE medicinesInfo SET is_deleted = 1 WHERE m_id = ?";
        try (Connection conn = DatabaseConnector.getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {
            stmt.setInt(1, id);
            stmt.executeUpdate();
        }
    }
    public static Medicine getMedicineById(int id) throws SQLException {
        String sql = "SELECT m.*, mi.* FROM medicines m JOIN medicinesInfo mi ON m.m_id = mi.m_id WHERE m.m_id = ?";
        try (PreparedStatement stmt = DatabaseConnector.getConnection().prepareStatement(sql)) {
            stmt.setInt(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return new Medicine(
                            rs.getInt("m_id"),
                            rs.getString("m_name"),
                            rs.getDouble("m_size"),
                            rs.getDouble("m_full_size"),
                            rs.getString("m_type"),
                            rs.getDouble("m_buyPrice"),
                            rs.getDouble("m_sellPrice"),
                            rs.getDate("m_expiryDate").toLocalDate(),
                            rs.getInt("m_amount")
                    );
                }
            }
        }
        return null;
    }
    public static ResultSet searchMedicinesByName(String searchTerm) throws SQLException {
        String sql = "SELECT m.*, mi.* FROM medicines m JOIN medicinesInfo mi ON m.m_id = mi.m_id WHERE m.m_name LIKE '%"+searchTerm+"%'";
        return DatabaseConnector.getConnection().prepareStatement(sql).executeQuery(sql);
    }
    public static void sellPartialMedicine(int medicineId, double quantity) throws SQLException {
        try (CallableStatement stmt = DatabaseConnector.getConnection().prepareCall("{call SellMedicinePartial(?, ?)}")) {
            stmt.setInt(1, medicineId);
            stmt.setDouble(2, quantity);
            stmt.execute();
        } catch (SQLException e) {
            if ("45000".equals(e.getSQLState())) {
                throw new SQLException("Stock Error: " + e.getMessage());
            }
            throw new SQLException("Database Error: " + e.getMessage());
        }
    }
    public static ResultSet getMedicineInfo(int id) {
        ResultSet rs = null;
        ResultSet rs2 = null;
        try (PreparedStatement stmt = DatabaseConnector.getConnection().prepareStatement("SELECT m_sellPrice*m_amount AS TMSP, m_buyPrice*m_amount AS TMBP FROM medicinesInfo WHERE m_id = ?")) {
            stmt.setInt(1, id);
            rs = stmt.executeQuery();
            rs2 = DatabaseConnector.getConnection().createStatement().executeQuery("SELECT SUM(m_sellPrice*m_amount) AS TSP, SUM(m_buyPrice*m_amount) AS TBP, SUM(m_amount) AS TS FROM medicinesInfo");
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(null, e.getMessage(), "Database Error", JOptionPane.ERROR_MESSAGE);
        }
        return rs + rs2;
    }
}
class ClientDAO {
    public static ResultSet getAllClients() throws SQLException {
        return DatabaseConnector.getConnection().createStatement().executeQuery("SELECT c.*,COALESCE(SUM(IF(t.payer = 1, t.amount, 0)), 0) AS total_payed,COALESCE(SUM(IF(t.payer = 0, t.amount, 0)), 0) AS total_notPayed FROM clients c LEFT JOIN transactions t ON c.client_id = t.client_id GROUP BY c.client_id");
    }
    public static void addClient(String name, String phone, String description) throws SQLException {
        try (PreparedStatement stmt = DatabaseConnector.getConnection().prepareStatement("INSERT INTO clients (name, phone, clientDescription) VALUES (?, ?, ?)")) {
            stmt.setString(1, name);
            stmt.setString(2, phone);
            stmt.setString(3, description);
            stmt.executeUpdate();
        }
    }
    public static List<Client> getAllClientsAsList() throws SQLException {
        List<Client> clients = new ArrayList<>();
        ResultSet rs = getAllClients();
        while (rs.next()) {
            clients.add(new Client(
                    rs.getInt("client_id"),
                    rs.getString("name"),
                    rs.getString("phone"),
                    rs.getString("clientDescription")
            ));
        }
        return clients;
    }
    public static void updateClient(int id, String name, String phone, String description) throws SQLException {
        String sql = "UPDATE clients SET name=?, phone=?, clientDescription=? WHERE client_id=?";
        try (Connection conn = DatabaseConnector.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, name);
            stmt.setString(2, phone);
            stmt.setString(3, description);
            stmt.setInt(4, id);
            stmt.executeUpdate();
        }
    }
    public static void deleteClient(int id) throws SQLException {
        String sql = "DELETE FROM clients WHERE client_id=?";
        try (Connection conn = DatabaseConnector.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, id);
            stmt.executeUpdate();
        }
    }
    public static Client getClientById(int id) throws SQLException {
        String sql = "SELECT * FROM clients WHERE client_id = ?";
        try (Connection conn = DatabaseConnector.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, id);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return new Client(
                        rs.getInt("client_id"),
                        rs.getString("name"),
                        rs.getString("phone"),
                        rs.getString("clientDescription")
                );
            }
            return null;
        }
    }
    public static ResultSet searchClientByName(String searchTerm) throws SQLException {
        String sql = "SELECT c.*,COALESCE(SUM(IF(t.payer = 1, t.amount, 0)), 0) AS total_payed,COALESCE(SUM(IF(t.payer = 0, t.amount, 0)), 0) AS total_notPayed FROM clients c LEFT JOIN transactions t ON c.client_id = t.client_id WHERE c.name LIKE '%" + searchTerm + "%' GROUP BY c.client_id";
        return DatabaseConnector.getConnection().prepareStatement(sql).executeQuery(sql);
    }
}
class TransactionDAO {
    public static ResultSet getTransactionsByClient(int clientId) throws SQLException {
        PreparedStatement stmt = DatabaseConnector.getConnection().prepareStatement("SELECT t.transaction_id, t.date, m.m_name, t.q_sold, t.amount, t.type, t.description, t.payer FROM transactions t JOIN medicines m ON t.m_id = m.m_id WHERE client_id = ? AND is_deleted = 0");
        stmt.setInt(1, clientId);
        return stmt.executeQuery();
    }
    public static void addTransaction(int clientId, int medicineId, LocalDateTime date, double quantitySold, double amount, String type, String description, boolean payer) throws SQLException {
        Connection conn = DatabaseConnector.getConnection();
        try {
            conn.setAutoCommit(false);
            String sql = "INSERT INTO transactions (client_id, m_id, date, amount, type, description, payer, q_sold) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, clientId);
                stmt.setInt(2, medicineId);
                stmt.setTimestamp(3, Timestamp.valueOf(date));
                stmt.setDouble(4, amount);
                stmt.setString(5, type);
                stmt.setString(6, description);
                stmt.setBoolean(7, payer);
                stmt.setDouble(8, quantitySold);
                stmt.executeUpdate();
            }
            MedicineDAO.sellPartialMedicine(medicineId, quantitySold);
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }
    public static void deleteTransaction(int transactionId) throws SQLException {
        String sql = "UPDATE transactions SET is_deleted = 1 WHERE transaction_id = ?";
        try (Connection conn = DatabaseConnector.getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, transactionId);
            stmt.executeUpdate();
        }
    }
    public static void setTransactionPayed(int trId) throws SQLException {
        try (PreparedStatement stmt = DatabaseConnector.getConnection().prepareStatement("UPDATE transactions SET payer = 1 WHERE transaction_id = ?")) {
            stmt.setInt(1, trId);
            stmt.executeUpdate();
        }
    }
}
class DailyUsageDAO {
    public static ResultSet getTodaysTransactions() throws SQLException {
        Connection conn = DatabaseConnector.getConnection();
        PreparedStatement stmt = conn.prepareStatement(
                "SELECT t.*, c.name, m.m_name FROM transactions t JOIN clients c ON t.client_id = c.client_id JOIN medicines m ON t.m_id = m.m_id WHERE DATE(t.date) = CURDATE() AND is_deleted = 0");
        return stmt.executeQuery();
    }
    public static ResultSet getDailySummary() throws SQLException {
        return DatabaseConnector.getConnection().createStatement().executeQuery("SELECT DATE(date) AS date, COUNT(*) AS transaction_count, SUM(amount) AS total_amount FROM transactions GROUP BY DATE(date) ORDER BY DATE(date) DESC");
    }
    public static ResultSet getTransactionsByDate(LocalDate date) throws SQLException {
        Connection conn = DatabaseConnector.getConnection();
        PreparedStatement stmt = conn.prepareStatement(
                "SELECT t.*, c.name, m.m_name FROM transactions t " +
                        "JOIN clients c ON t.client_id = c.client_id " +
                        "JOIN medicines m ON t.m_id = m.m_id " +
                        "WHERE DATE(t.date) = ?");
        stmt.setDate(1, Date.valueOf(date));
        return stmt.executeQuery();
    }
    public static void deleteTransaction(int id) throws SQLException {
        try (Connection conn = DatabaseConnector.getConnection();
             PreparedStatement stmt = conn.prepareStatement("UPDATE transactions SET is_deleted = 1 WHERE transaction_id = ?")) {
            stmt.setInt(1, id);
            stmt.executeUpdate();
        }
    }
    public static void setTransactionPayed(int trId) throws SQLException {
        String sql = "UPDATE transactions SET payer = 1 WHERE transaction_id = ?";
        try (Connection conn = DatabaseConnector.getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, trId);
            stmt.executeUpdate();
        }
    }
}

class AppointmentDAO {
    public static ResultSet getAppointmentByDate(LocalDate date) throws SQLException {
        PreparedStatement stmt = DatabaseConnector.getConnection().prepareStatement("SELECT a.*, c.name, c.phone FROM appointments a JOIN clients c ON a.client_id = c.client_id WHERE DATE(a.date) = ? AND a.is_deleted = 0");
        stmt.setDate(1, Date.valueOf(date));
        return stmt.executeQuery();
    }
    public static void addAppointment(int cId, LocalDate date, String description) throws SQLException {
        try (PreparedStatement stmt = DatabaseConnector.getConnection().prepareStatement("INSERT INTO appointments (client_id, date, description, is_deleted) VALUES (?, ?, ?, 0)")) {
            stmt.setInt(1, cId);
            stmt.setDate(2, Date.valueOf(date));
            stmt.setString(3, description);
            stmt.executeUpdate();
        }
    }
    public static void deleteAppointments(LocalDate date) throws SQLException {
        try (PreparedStatement stmt = DatabaseConnector.getConnection().prepareStatement("UPDATE appointments SET is_deleted = 1 WHERE DATE(date) LIKE '%" + Date.valueOf(date) + "%'")) {
            stmt.executeUpdate();
        }
    }

    public static void setAppointmentDone(LocalDate date) throws SQLException {
        try (PreparedStatement stmt = DatabaseConnector.getConnection().prepareStatement("UPDATE appointments SET is_done = 1 WHERE DATE(date) LIKE '%" + Date.valueOf(date) + "%' AND is_deleted = 0")) {
            stmt.executeUpdate();
        }
    }
}
class Medicine {
    private final int id;
    private final String name;
    private double currentSize = 0;
    private double fullSize = 0;
    private String type = null;
    private double buyPrice = 0;
    private double sellPrice = 0;
    private LocalDate expiryDate = null;
    private int amount = 0;
    public Medicine(int id, String name, double currentSize, double fullSize, String type, double buyPrice, double sellPrice, LocalDate expiryDate, int amount) {
        this.id = id;
        this.name = name;
        this.currentSize = currentSize;
        this.fullSize = fullSize;
        this.type = type;
        this.buyPrice = buyPrice;
        this.sellPrice = sellPrice;
        this.expiryDate = expiryDate;
        this.amount = amount;
    }
    public Medicine(int id, String name) {
        this.id = id;
        this.name = name;
    }
    public int getId() { return id; }
    public String getName() { return name; }
    public double getCurrentSize() { return currentSize; }
    public double getFullSize() { return fullSize; }
    public String getType() { return type; }
    public double getBuyPrice() { return buyPrice; }
    public double getSellPrice() { return sellPrice; }
    public LocalDate getExpiryDate() { return expiryDate; }
    public int getAmount() { return amount; }
    @Override
    public String toString() {
        return name;
    }
}
class Client {
    private final int id;
    private final String name;
    private final String phone;
    private final String description;
    public Client(int id, String name, String phone, String description) {
        this.id = id;
        this.name = name;
        this.phone = phone;
        this.description = description;
    }
    public int getId() { return id; }
    public String getName() { return name; }
    public String getPhone() { return phone; }
    public String getDescription() { return description; }
    @Override
    public String toString() {
        return name;
    }
}
class MedicineDialog extends JDialog {
    private final JTextField nameField = new JTextField();
    private final JSpinner sizeSpinner = new JSpinner();
    private final JComboBox<String> typeField = new JComboBox<>(new String[]{"Anti-Biotiques", "Anti-Inflammatoires", "Anti-Parasitaires", "CMV et Addetifs", "Vaccins", "Anesthesiques", "Accessoires", "Outils", "Desinfectent", "Hormones", "Serum et Fluid"});
    private final JSpinner buySpinner = new JSpinner(new SpinnerNumberModel(0, 0, 100000, 100));
    private final JSpinner sellSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 100000, 100));
    private final JXDatePicker datePicker = new JXDatePicker();
    private final JSpinner amountSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 10000, 1));
    private final int editId;
    public MedicineDialog(Frame owner, String title, int editId) {
        super(owner, title, true);
        this.editId = editId;
        initializeUI();
    }
    private void initializeUI() {
        setSize(700, 800);
        setLocationRelativeTo(getOwner());
        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(new EmptyBorder(15, 15, 15, 15));
        JPanel formPanel = new JPanel(new GridLayout(0, 2, 10, 10));
        if (editId != -1) {
            try {
                Medicine medicine = MedicineDAO.getMedicineById(editId);
                if (medicine != null) {
                    nameField.setText(medicine.getName());
                    sizeSpinner.setValue(medicine.getCurrentSize());
                    typeField.setSelectedItem(medicine.getType());
                    buySpinner.setValue(medicine.getBuyPrice());
                    sellSpinner.setValue(medicine.getSellPrice());
                    datePicker.setDate(Date.valueOf(medicine.getExpiryDate()));
                    amountSpinner.setValue(medicine.getAmount());
                }
            } catch (SQLException e) {
                JOptionPane.showMessageDialog(this, "Error loading medicine: " + e.getMessage());
            }
        }
        formPanel.add(new JLabel("Medicine Name*:")).setFont(new Font("DejaVu",Font.PLAIN,22));
        formPanel.add(nameField).setFont(new Font("DejaVu",Font.BOLD,20));
        formPanel.add(new JLabel("Medicine Size*:")).setFont(new Font("DejaVu",Font.PLAIN,22));
        formPanel.add(sizeSpinner).setFont(new Font("DejaVu",Font.BOLD,20));
        formPanel.add(new JLabel("Type*:")).setFont(new Font("DejaVu",Font.PLAIN,22));
        formPanel.add(typeField).setFont(new Font("DejaVu",Font.BOLD,20));
        formPanel.add(new JLabel("Buy Price*:")).setFont(new Font("DejaVu",Font.PLAIN,22));
        formPanel.add(buySpinner).setFont(new Font("DejaVu",Font.BOLD,20));
        formPanel.add(new JLabel("Sell Price*:")).setFont(new Font("DejaVu",Font.PLAIN,22));
        formPanel.add(sellSpinner).setFont(new Font("DejaVu",Font.BOLD,20));
        formPanel.add(new JLabel("Expiry Date* (Y-M-D):")).setFont(new Font("DejaVu",Font.PLAIN,22));
        datePicker.setFormats("yyyy-MM-dd");
        formPanel.add(datePicker).setFont(new Font("DejaVu",Font.BOLD,20));
        formPanel.add(new JLabel("Stock Amount*:")).setFont(new Font("DejaVu",Font.PLAIN,22));
        formPanel.add(amountSpinner).setFont(new Font("DejaVu",Font.BOLD,20));
        JButton btnSave = new JButton(editId == -1 ? "Save" : "Update");
        btnSave.addActionListener(_ -> saveMedicine());
        btnSave.setFont(new Font("DejaVu",Font.PLAIN,28));
        btnSave.setForeground(Color.WHITE);
        btnSave.setOpaque(true);
        mainPanel.add(formPanel, BorderLayout.CENTER);
        mainPanel.add(btnSave, BorderLayout.SOUTH);
        add(mainPanel);
    }
    private void saveMedicine() {
        try {
            if (nameField.getText().isEmpty()) {
                JOptionPane.showMessageDialog(this, "Fields marked with * are required!");
                return;
            }
            String name = nameField.getText();
            double size = Double.parseDouble(sizeSpinner.getValue().toString());
            String type = Objects.requireNonNull(typeField.getSelectedItem()).toString();
            double buyPrice = Double.parseDouble(buySpinner.getValue().toString());
            double sellPrice = Double.parseDouble(sellSpinner.getValue().toString());
            LocalDate expiry = LocalDate.ofInstant(datePicker.getDate().toInstant(), ZoneId.systemDefault());
            int amount = Integer.parseInt(amountSpinner.getValue().toString());
            if (editId == -1) {
                int id = MedicineDAO.addMedicine(name, type);
                MedicineDAO.addMedicineInfo(id, size, size, buyPrice, sellPrice, expiry, amount);
            } else {
                MedicineDAO.updateMedicine(editId, name, type);
                MedicineDAO.updateMedicineInfo(editId, size, size, buyPrice, sellPrice, expiry, amount);
            }
            dispose();
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Database error: " + e.getMessage());
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "Invalid number format in fields!");
        }
    }
}
class ClientDialog extends JDialog {
    private final JTextField nameField = new JTextField();
    private final JTextField phoneField = new JTextField();
    private final JTextField descriptionField = new JTextField();
    private final int editId;
    public ClientDialog(Frame owner, String title, int editId) {
        super(owner, title, true);
        this.editId = editId;
        initializeUI();
    }
    private void initializeUI() {
        setSize(600, 400);
        setLocationRelativeTo(getOwner());
        JPanel panel = new JPanel(new GridLayout(0, 2, 10, 10));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));
        if (editId != -1) {
            try {
                Client client = ClientDAO.getClientById(editId);
                if (client != null) {
                    nameField.setText(client.getName());
                    phoneField.setText(client.getPhone());
                    descriptionField.setText(client.getDescription());
                }
            } catch (SQLException e) {
                JOptionPane.showMessageDialog(this, "Error loading client: " + e.getMessage());
            }
        }
        panel.add(new JLabel("Client Name:")).setFont(new Font("DejaVu", Font.BOLD, 20));
        panel.add(nameField).setFont(new Font("DejaVu", Font.BOLD, 20));
        panel.add(new JLabel("Phone:")).setFont(new Font("DejaVu", Font.BOLD, 20));
        panel.add(phoneField).setFont(new Font("DejaVu", Font.BOLD, 20));
        panel.add(new JLabel("Client Description:")).setFont(new Font("DejaVu", Font.BOLD, 20));
        panel.add(descriptionField);
        JButton btnSave = new JButton(editId == -1 ? "Save" : "Update");
        btnSave.addActionListener(_ -> saveClient());
        btnSave.setMnemonic(KeyEvent.VK_ENTER);
        btnSave.setFont(new Font("DejaVu", Font.BOLD, 20));
        btnSave.setSize(20, 20);
        add(panel, BorderLayout.CENTER);
        add(btnSave, BorderLayout.SOUTH);
    }
    private void saveClient() {
        try {
            String name = nameField.getText();
            String phone = phoneField.getText();
            String description = descriptionField.getText();
            if (name.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Client name cannot be empty!");
                return;
            }
            if (editId == -1) {
                ClientDAO.addClient(name, phone, description);
            } else {
                ClientDAO.updateClient(editId, name, phone, description);
            }
            dispose();
        } catch (DateTimeParseException e) {
            JOptionPane.showMessageDialog(this, "Invalid date format!\nUse YYYY-MM-DD or leave empty");
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
class TransactionDialog extends JDialog {
    private final JTextField dateField = new JTextField(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd, hh:mm:ss")));
    private final JComboBox<Medicine> medicineCombo  = new JComboBox<>();
    private final JSpinner quantitySpinner = new JSpinner(new SpinnerNumberModel(0.0, 0, 1000000, 1));
    private final JSpinner amountSpinner = new JSpinner(new SpinnerNumberModel(0.0, 0, 1000000, 100));
    private final JComboBox<String> typeField = new JComboBox<>(new String[]{"Consultation", "Treatment", "Product"});
    private final JTextArea descriptionArea = new JTextArea();
    private final JCheckBox payerCheckbox = new JCheckBox("Payed ?");
    private final int clientId;
    public TransactionDialog(Frame owner, String title, int clientId) {
        super(owner, title, true);
        this.clientId = clientId;
        initializeUI();
    }
    private void initializeUI() {
        setSize(600, 500);
        setLocationRelativeTo(getOwner());
        JPanel trPanel = new JPanel(new BorderLayout(10, 10));
        trPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
        JPanel formPanel = new JPanel(new GridLayout(0, 2, 10, 10));
        medicineCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                if (value instanceof Medicine) {
                    value = ((Medicine) value).getName();
                }
                return super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            }
        });
        formPanel.add(new JLabel("Date*:")).setFont(new Font("DejaVu", Font.BOLD, 20));
        formPanel.add(dateField).setFont(new Font("DejaVu", Font.BOLD, 20));
        formPanel.add(new JLabel("Medicine:")).setFont(new Font("DejaVu", Font.BOLD, 20));
        formPanel.add(medicineCombo).setFont(new Font("DejaVu", Font.BOLD, 20));
        formPanel.add(new JLabel("Quantity: (en ml)")).setFont(new Font("DejaVu", Font.BOLD, 20));
        formPanel.add(quantitySpinner).setFont(new Font("DejaVu", Font.BOLD, 20));
        formPanel.add(new JLabel("Amount*:")).setFont(new Font("DejaVu", Font.BOLD, 20));
        formPanel.add(amountSpinner).setFont(new Font("DejaVu", Font.BOLD, 20));
        formPanel.add(new JLabel("Type*:")).setFont(new Font("DejaVu", Font.BOLD, 20));
        formPanel.add(typeField).setFont(new Font("DejaVu", Font.BOLD, 20));
        formPanel.add(new JLabel("Description:")).setFont(new Font("DejaVu", Font.BOLD, 20));
        formPanel.add(new JScrollPane(descriptionArea)).setFont(new Font("DejaVu", Font.BOLD, 20));
        formPanel.add(new JLabel("Payment Type:")).setFont(new Font("DejaVu", Font.BOLD, 20));
        formPanel.add(payerCheckbox).setFont(new Font("DejaVu", Font.BOLD, 20));
        JButton btnSave = new JButton("Save");
        loadMedicines();
        btnSave.addActionListener(_ -> saveTransaction());
        btnSave.setFont(new Font("DejaVu", Font.BOLD, 20));
        trPanel.add(formPanel, BorderLayout.CENTER);
        trPanel.add(btnSave, BorderLayout.SOUTH);
        add(trPanel);
    }
    private void saveTransaction() {
        LocalDateTime transactionDate = LocalDateTime.parse(dateField.getText());
        Medicine selectedMedicine = (Medicine) medicineCombo.getSelectedItem();
        double quantity = (Double) quantitySpinner.getValue();
        if (selectedMedicine == null || quantity <= 0) {
            JOptionPane.showMessageDialog(this, "Invalid selection");
            return;
        }
        try {
            TransactionDAO.addTransaction(clientId, selectedMedicine.getId(), transactionDate, quantity, (Double)amountSpinner.getValue(), Objects.requireNonNull(typeField.getSelectedItem()).toString(), descriptionArea.getText(), payerCheckbox.isSelected());
            dispose();
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Database Error: " + e.getMessage(), "Save Error", JOptionPane.ERROR_MESSAGE);
        }
    }
    private void loadMedicines() {
        try {
            List<Medicine> medicines = MedicineDAO.getAllMedicinesAsList("");
            medicines.forEach(medicineCombo::addItem);
            if (!medicines.isEmpty()) {
                updateMaxQuantity();
            }
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Error loading medicines: " + e.getMessage());
        }
    }
    private void updateMaxQuantity() {
        Medicine selected = (Medicine) medicineCombo.getSelectedItem();
        if (selected != null) {
            double max = selected.getCurrentSize() +
                    (selected.getAmount() - 1) * selected.getFullSize();
            SpinnerNumberModel model = (SpinnerNumberModel) quantitySpinner.getModel();
            model.setMaximum(max);
        }
    }
}
class AppointmentDialog extends JDialog {
    private final LocalDate date;
    private final boolean isTaken;
    private JComboBox<Client> clientCombo;
    private JTextArea descriptionArea;
    public AppointmentDialog(JPanel parent, LocalDate date, boolean isTaken) {
        super((Frame) SwingUtilities.getWindowAncestor(parent), true);
        this.date = date;
        this.isTaken = isTaken;
        initializeUI();
    }
    private void initializeUI() {
        setSize(600, 400);
        setLocationRelativeTo(getOwner());
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));
        if (!isTaken) {
            panel.setLayout(new GridLayout(2, 0, 5, 5));
            JXTitledPanel namePanel = new JXTitledPanel("Client : ");
            namePanel.setForeground(Color.WHITE);
            namePanel.setTitleFont(new Font("DejaVu", Font.PLAIN, 22));
            namePanel.setTitleForeground(Color.WHITE);
            namePanel.setBackground(Color.WHITE);
            JXTitledPanel descriptionPanel = new JXTitledPanel("Description : ");
            descriptionPanel.setForeground(Color.WHITE);
            descriptionPanel.setTitleFont(new Font("DejaVu", Font.PLAIN, 22));
            descriptionPanel.setTitleForeground(Color.WHITE);
            descriptionPanel.setBackground(Color.WHITE);
            JLabel name = new JLabel();
            name.setFont(new Font("DejaVu", Font.PLAIN, 20));
            name.setForeground(Color.WHITE);
            JLabel description = new JLabel();
            description.setFont(new Font("DejaVu", Font.PLAIN, 20));
            description.setForeground(Color.WHITE);
            try {
                ResultSet rs = AppointmentDAO.getAppointmentByDate(date);
                while (rs.next()) {
                    name.setText(String.format("<html>%s<br>%s</html>", " Name : " + rs.getString("name") + ".", " Phone : " + rs.getString("phone")));
                    description.setText(rs.getString("description"));
                    if (rs.getBoolean("is_done")) {
                        setTitle("This appointment is done.");
                    } else {
                        setTitle("This appointment isn't done.");
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            namePanel.add(name);
            descriptionPanel.add(description);
            panel.add(namePanel);
            panel.add(descriptionPanel);
            JPanel btnPanel = new JPanel();
            btnPanel.setLayout(new GridLayout(0, 2, 5, 5));
            btnPanel.setBorder(new EmptyBorder(5, 5, 5, 5));
            addButton(btnPanel, "Delete", new ImageIcon(Objects.requireNonNull(getClass().getResource("res/delete.png"))), "Deleting this appointment.", _ -> deleteAppointment(date));
            addButton(btnPanel, "Set Done", new ImageIcon(Objects.requireNonNull(getClass().getResource("res/check.png"))), "Setting this appointment done.", _ -> setAppointmentDone(date));
            add(panel, BorderLayout.CENTER);
            add(btnPanel, BorderLayout.SOUTH);
        } else {
            panel.setLayout(new GridLayout(0, 2, 5, 5));
            setTitle("New appointment for " + date);
            clientCombo = new JComboBox<>();
            clientCombo.setRenderer(new DefaultListCellRenderer() {
                public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                    return super.getListCellRendererComponent(list, (value instanceof Client) ? ((Client) value).getName() : value, index, isSelected, cellHasFocus);
                }
            });
            loadClients();
            descriptionArea = new JTextArea(3, 20);
            JPanel btnPanel = new JPanel();
            btnPanel.setBorder(new EmptyBorder(5, 5, 5, 5));
            btnPanel.setLayout(new GridLayout(1, 0, 5, 5));
            addButton(btnPanel, "Add", new ImageIcon(Objects.requireNonNull(getClass().getResource("res/add.png"))), "Add a new appointment", _ -> addAppointment());
            panel.add(new JLabel("Client : ")).setFont(new Font("DejaVu", Font.BOLD, 20));
            panel.add(clientCombo);
            panel.add(new JLabel("Add client : ")).setFont(new Font("DejaVu", Font.BOLD, 20));
            addButton(panel, "", new ImageIcon(Objects.requireNonNull(getClass().getResource("res/add.png"))), "Add a new client if it isn't exist.", _ -> {
                new ClientDialog(null, "New Client", -1).setVisible(true);
                loadClients();
            });
            panel.add(new JLabel("Description : ")).setFont(new Font("DejaVu", Font.BOLD, 20));
            panel.add(new JScrollPane(descriptionArea));
            add(panel, BorderLayout.CENTER);
            add(btnPanel, BorderLayout.SOUTH);
        }
    }

    private void addButton(JPanel panel, String title, ImageIcon icon, String tooltip, ActionListener action) {
        JButton btn = new JButton(title);
        btn.setToolTipText(tooltip);
        btn.addActionListener(action);
        btn.setIcon(icon);
        btn.setIconTextGap(15);
        btn.setFont(new Font("DejaVu", Font.PLAIN, 22));
        panel.add(btn);
    }
    private void loadClients() {
        clientCombo.removeAllItems();
        try {
            ClientDAO.getAllClientsAsList().forEach(clientCombo::addItem);
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Error loading clients: " + e.getMessage());
        }
    }

    public void deleteAppointment(LocalDate date) {
        try {
            AppointmentDAO.deleteAppointments(date);
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
        dispose();
    }

    public void setAppointmentDone(LocalDate date) {
        try {
            AppointmentDAO.setAppointmentDone(date);
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
        dispose();
    }
    private void addAppointment() {
        Client client = (Client) clientCombo.getSelectedItem();
        String description = descriptionArea.getText();
        if (client == null) {
            JOptionPane.showMessageDialog(this, "Select a client!");
            return;
        }
        dispose();
        try {
            AppointmentDAO.addAppointment(client.getId(), date, description);
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Error adding appointment: " + e.getMessage());
        }
    }
}

class JSearchableComboBox extends JComboBox {
    public JSearchableComboBox () {
        super();
        init();
    }
    public JSearchableComboBox (Object[] elements) {
        super(elements);
        init();
    }
    public void setModel (ComboBoxModel model) {
        super.setModel (model);
        init ();
    }
    private void init () {
        setEditable(true);
        setEditor(new SearchEditor (this));
    }
    private static class SearchEditor extends BasicComboBoxEditor {
        private final TernarySearchTree _data = new TernarySearchTree ();
        public Object getItem () {
            return _data.get (super.getItem ().toString ());
        }
        public SearchEditor (final JSearchableComboBox cb) {
            ComboBoxModel model = cb.getModel ();
            for (int i = 0; i < model.getSize (); i++) {
                Object data = model.getElementAt (i);
                _data.put (data.toString (), data);
            }
            KeyAdapter listener = new KeyAdapter () {
                public void keyReleased (KeyEvent ev) {
                    if ((ev.getKeyChar() >= 'a' && ev.getKeyChar () <= 'z') ||
                            (ev.getKeyChar () >= '0' && ev.getKeyChar () <= '9') ||
                            (ev.getKeyChar () >= 'A' && ev.getKeyChar () <= 'Z') ||
                            (ev.getKeyChar () == KeyEvent.VK_SPACE))
                    {
                        String startText = editor.getText ();
                        String finalText = _data.matchPrefixString (startText, 1);
                        if (finalText.isEmpty()) finalText = startText;
                        if (! finalText.equals (startText)) {
                            editor.setText (finalText);
                            editor.setSelectionStart (startText.length ());
                            editor.setSelectionEnd (finalText.length ());
                        }
                        cb.setSelectedItem (_data.get (finalText));
                    }
                }
            };
            editor.addKeyListener (listener);
            ActionListener actionListener = _ -> {
                if (cb.getSelectedItem () != null &&
                        ! editor.getText ().equals (cb.getSelectedItem ().toString ())) {
                    editor.setText (cb.getSelectedItem ().toString ());
                }
            };
            cb.addActionListener (actionListener);
        }
    }
}
class TernarySearchTree {
    private TSTNode rootNode;
    private int lastNumberOfReturnValues;
    public void put(String key, Object value) {
        getOrCreateNode(key).data = value;
    }
    public Object get(String key) {
        TSTNode node = getNode(key);
        if(node==null) return null;
        return node.data;
    }
    private int checkNumberOfReturnValues(int numReturnValues) {
        return ((numReturnValues < 0) ? -1 : numReturnValues);
    }
    public TSTNode getNode(String key) {
        return getNode(key, rootNode);
    }
    protected TSTNode getNode(String key, TSTNode startNode) {
        if(key == null || startNode == null || key.isEmpty()) return null;
        TSTNode currentNode = startNode;
        int charIndex = 0;
        while(true) {
            if(currentNode == null) return null;
            int charComp = CharUtility.compareCharsAlphabetically(key.charAt(charIndex), currentNode.splitchar);
            if (charComp == 0) {
                charIndex++;
                if(charIndex == key.length()) return currentNode;
                currentNode = currentNode.relatives[TSTNode.EQKID];
            } else if(charComp < 0) {
                currentNode = currentNode.relatives[TSTNode.LOKID];
            } else {
                currentNode = currentNode.relatives[TSTNode.HIKID];
            }
        }
    }
    public String matchPrefixString(String prefix, int numReturnValues) {
        TSTNode startNode = getNode(prefix);
        if(startNode == null) return "";
        sortKeysNumReturnValues = checkNumberOfReturnValues(numReturnValues);
        lastNumberOfReturnValues = sortKeysNumReturnValues;
        sortKeysBuffer = new StringBuffer();
        if(startNode.data != null) {
            sortKeysBuffer.append(getKey(startNode) + "\n");
            sortKeysNumReturnValues--;
        }
        sortKeysList = false;
        sortKeysRecursion(startNode.relatives[TSTNode.EQKID]);
        int bufferLength = sortKeysBuffer.length();
        if(bufferLength > 0) sortKeysBuffer.setLength(bufferLength - 1);
        lastNumberOfReturnValues = lastNumberOfReturnValues - sortKeysNumReturnValues;
        return sortKeysBuffer.toString();
    }
    private DoublyLinkedList sortKeysResult;
    private boolean sortKeysList;
    private StringBuffer sortKeysBuffer;
    private int sortKeysNumReturnValues;
    private void sortKeysRecursion(TSTNode currentNode) {
        if(currentNode == null) return;
        sortKeysRecursion(currentNode.relatives[TSTNode.LOKID]);
        if(sortKeysNumReturnValues == 0) return;
        if(currentNode.data != null) {
            if(sortKeysList) {
                sortKeysResult.addLast(getKey(currentNode));
            } else {
                sortKeysBuffer.append(getKey(currentNode) + "\n");
            }
            sortKeysNumReturnValues--;
        }
        sortKeysRecursion(currentNode.relatives[TSTNode.EQKID]);
        sortKeysRecursion(currentNode.relatives[TSTNode.HIKID]);
    }
    protected TSTNode getOrCreateNode(String key) throws NullPointerException, IllegalArgumentException {
        if(key == null) throw new NullPointerException("attempt to get or create node with null key");
        if(key.isEmpty()) throw new IllegalArgumentException("attempt to get or create node with key of zero length");
        if(rootNode==null) rootNode = new TSTNode(key.charAt(0), null);
        TSTNode currentNode = rootNode;
        int charIndex = 0;
        while(true) {
            int charComp = CharUtility.compareCharsAlphabetically(key.charAt(charIndex), currentNode.splitchar);
            if (charComp == 0) {
                charIndex++;
                if(charIndex == key.length()) return currentNode;
                if(currentNode.relatives[TSTNode.EQKID] == null) currentNode.relatives[TSTNode.EQKID] = new TSTNode(key.charAt(charIndex), currentNode);
                currentNode = currentNode.relatives[TSTNode.EQKID];
            } else if(charComp < 0) {
                if(currentNode.relatives[TSTNode.LOKID] == null) currentNode.relatives[TSTNode.LOKID] = new TSTNode(key.charAt(charIndex), currentNode);
                currentNode = currentNode.relatives[TSTNode.LOKID];
            } else {
                if(currentNode.relatives[TSTNode.HIKID] == null) currentNode.relatives[TSTNode.HIKID] = new TSTNode(key.charAt(charIndex), currentNode);
                currentNode = currentNode.relatives[TSTNode.HIKID];
            }
        }
    }
    protected static class TSTNode {
        protected static final int PARENT = 0, LOKID = 1, EQKID = 2, HIKID = 3;
        protected char splitchar;
        protected TSTNode[] relatives = new TSTNode[4];
        protected Object data;
        protected TSTNode(char splitchar, TSTNode parent) {
            this.splitchar = splitchar;
            relatives[PARENT] = parent;
        }
    }
    private final StringBuffer getKeyBuffer = new StringBuffer();
    protected String getKey(TSTNode node) {
        getKeyBuffer.setLength(0);
        getKeyBuffer.append(node.splitchar);
        TSTNode currentNode, lastNode;
        currentNode = node.relatives[TSTNode.PARENT];
        lastNode = node;
        while(currentNode != null) {
            if(currentNode.relatives[TSTNode.EQKID] == lastNode) getKeyBuffer.append(currentNode.splitchar);
            lastNode = currentNode;
            currentNode = currentNode.relatives[TSTNode.PARENT];
        }
        getKeyBuffer.reverse();
        return getKeyBuffer.toString();
    }
}
class CharUtility {
    public static int compareCharsAlphabetically(char cCompare, char cRef) {
        return (alphabetizeChar(cCompare) - alphabetizeChar(cRef));
    }
    private static int alphabetizeChar(char c) {
        if(c < 65) return c;
        if(c < 89) return (2 * c) - 65;
        if(c < 97) return c + 24;
        if(c < 121) return (2 * c) - 128;
        return c;
    }
}
class DoublyLinkedList {
    private DLLNode head, last;
    private int size = 0;

    public void addLast(Object data) {
        DLLNode newNode = new DLLNode();
        newNode.data = data;
        if (size == 0) {
            head = newNode;
        } else {
            last.nextNode = newNode;
            newNode.previousNode = last;
        }
        last = newNode;
        size++;
    }

    public int size() {
        return size;
    }

    protected static class DLLNode {
        protected DLLNode nextNode, previousNode;
        protected Object data;
    }
}