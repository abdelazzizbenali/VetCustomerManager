import mdlaf.MaterialLookAndFeel;
import mdlaf.themes.MaterialOceanicTheme;
import org.jdesktop.swingx.JXDatePicker;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.*;
import java.sql.*;
import java.time.*;
import java.time.format.*;

class VeterinaryDBMS {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
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
            throw new SQLException("MySQL Driver missing");
        }
        return DriverManager.getConnection("jdbc:mysql://localhost:3306/parent", "root", "root");
    }
}
class MainWindow extends JFrame {
    private JPanel mainPanel;
    private MedicinePanel medicinePanel;
    private ClientPanel clientPanel;
    public MainWindow() {
        initializeUI();
    }
    private void initializeUI() {
        setTitle("Veterinary Management System");
        setSize(1200, 800);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        JToolBar navToolbar = new JToolBar();
        navToolbar.setFloatable(false);
        JButton btnMedicines = new JButton("Medicines Stock");
        JButton btnClients = new JButton("Client List");
        mainPanel = new JPanel(new CardLayout());
        medicinePanel = new MedicinePanel();
        clientPanel = new ClientPanel();
        mainPanel.add(medicinePanel, "MEDICINES");
        mainPanel.add(clientPanel, "CLIENTS");
        btnMedicines.addActionListener(e -> showPanel("MEDICINES"));
        btnClients.addActionListener(e -> showPanel("CLIENTS"));
        navToolbar.add(btnMedicines);
        navToolbar.add(btnClients);
        add(navToolbar, BorderLayout.NORTH);
        add(mainPanel, BorderLayout.CENTER);
    }
    private void showPanel(String panelName) {
        CardLayout cl = (CardLayout) mainPanel.getLayout();
        cl.show(mainPanel, panelName);
        if(panelName.equals("MEDICINES")) medicinePanel.refreshData();
        else if(panelName.equals("CLIENTS")) clientPanel.refreshData();
    }
}
class MedicinePanel extends JPanel {
    private JTable dataTable;
    private DefaultTableModel tableModel;
    private JLabel statusLabel;
    public MedicinePanel() {
        initializeUI();
        loadData();
    }
    private void initializeUI() {
        setLayout(new BorderLayout());
        String[] columnNames = {"ID", "Medicine Name", "Buy Price", "Sell Price", "Expiry Date"};
        tableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        dataTable = new JTable(tableModel);
        dataTable.setAutoCreateRowSorter(true);
        JScrollPane scrollPane = new JScrollPane(dataTable);
        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        addButton(toolBar, "Add", "Add new medicine", e -> showAddDialog());
        addButton(toolBar, "Edit", "Edit selected", e -> showEditDialog());
        addButton(toolBar, "Delete", "Delete selected", e -> deleteMedicine());
        addButton(toolBar, "Refresh", "Refresh data", e -> refreshData());
        statusLabel = new JLabel(" Ready");
        statusLabel.setBorder(new EmptyBorder(5, 10, 5, 10));
        statusLabel.setBackground(new Color(240, 240, 240));
        statusLabel.setOpaque(true);
        add(toolBar, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);
    }
    private void addButton(JToolBar bar, String text, String tooltip, ActionListener action) {
        JButton btn = new JButton(text);
        btn.setToolTipText(tooltip);
        btn.addActionListener(action);
        bar.add(btn);
        bar.addSeparator();
    }
    private void loadData() {
        try {
            tableModel.setRowCount(0);
            ResultSet rs = MedicineDAO.getAllMedicines();
            while (rs.next()) {
                Object[] row = {
                        rs.getInt("m_id"),
                        rs.getString("m_name"),
                        rs.getDouble("m_buy_price"),
                        rs.getDouble("m_sell_price"),
                        rs.getDate("m_expiry_date")
                };
                tableModel.addRow(row);
            }
            statusLabel.setText(" Loaded " + tableModel.getRowCount() + " medicines");
        } catch (SQLException e) {
            showError("Load failed: " + e.getMessage());
        }
    }
    private void showAddDialog() {
        new MedicineDialog(null, "Add Medicine", -1).setVisible(true);
        refreshData();
    }
    private void showEditDialog() {
        int row = dataTable.getSelectedRow();
        if (row == -1) {
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
        int confirm = JOptionPane.showConfirmDialog(this, "Delete selected medicine?", "Confirm Delete", JOptionPane.YES_NO_OPTION);
        if (confirm == JOptionPane.YES_OPTION) {
            try {
                int modelRow = dataTable.convertRowIndexToModel(row);
                int id = (int) tableModel.getValueAt(modelRow, 0);
                MedicineDAO.deleteMedicine(id);
                refreshData();
            } catch (SQLException e) {
                showError("Delete failed: " + e.getMessage());
            }
        }
    }
    public void refreshData() {
        loadData();
        statusLabel.setText(" Medicine data refreshed at " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("YYYY-MM-DD // HH:mm:ss")));
    }
    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "Medicine Error", JOptionPane.ERROR_MESSAGE);
    }
}
class ClientPanel extends JPanel {
    private JTable clientTable;
    private DefaultTableModel clientModel;
    private JLabel statusLabel;
    public ClientPanel() {
        initializeUI();
        loadData();
    }
    private void initializeUI() {
        setLayout(new BorderLayout());
        String[] columnNames = {"ID", "Client Name", "Phone", "Email"};
        clientModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        clientTable = new JTable(clientModel);
        clientTable.setAutoCreateRowSorter(true);
        JScrollPane scrollPane = new JScrollPane(clientTable);
        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        addButton(toolBar, "Add", "Add new client", e -> showAddDialog());
        addButton(toolBar, "Edit", "Edit selected", e -> showEditDialog());
        addButton(toolBar, "Delete", "Delete selected", e -> deleteClient());
        addButton(toolBar, "Refresh", "Refresh data", e -> refreshData());
        statusLabel = new JLabel(" Ready");
        statusLabel.setBorder(new EmptyBorder(5, 10, 5, 10));
        statusLabel.setBackground(new Color(240, 240, 240));
        statusLabel.setOpaque(true);
        add(toolBar, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);
    }
    private void addButton(JToolBar bar, String text, String tooltip, ActionListener action) {
        JButton btn = new JButton(text);
        btn.setToolTipText(tooltip);
        btn.addActionListener(action);
        bar.add(btn);
        bar.addSeparator();
    }
    private void loadData() {
        try {
            clientModel.setRowCount(0);
            ResultSet rs = ClientDAO.getAllClients();
            while (rs.next()) {
                Object[] row = {
                        rs.getInt("c_id"),
                        rs.getString("c_name"),
                        rs.getString("phone"),
                        rs.getString("email"),
                };
                clientModel.addRow(row);
            }
            statusLabel.setText(" Loaded " + clientModel.getRowCount() + " clients");
        } catch (SQLException e) {
            showError("Load failed: " + e.getMessage());
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
    private void deleteClient() {
        int row = clientTable.getSelectedRow();
        if (row == -1) {
            showError("No selection");
            return;
        }
        int confirm = JOptionPane.showConfirmDialog(this,
                "Delete selected client?",
                "Confirm Delete",
                JOptionPane.YES_NO_OPTION);
        if (confirm == JOptionPane.YES_OPTION) {
            try {
                int modelRow = clientTable.convertRowIndexToModel(row);
                int id = (int) clientModel.getValueAt(modelRow, 0);
                ClientDAO.deleteClient(id);
                refreshData();
            } catch (SQLException e) {
                showError("Delete failed: " + e.getMessage());
            }
        }
    }
    public void refreshData() {
        loadData();
        statusLabel.setText(" Client data refreshed at " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("YYYY-MM-DD // HH:mm:ss")));
    }
    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "Client Error", JOptionPane.ERROR_MESSAGE);
    }
}
class MedicineDAO {
    public static ResultSet getAllMedicines() throws SQLException {
        Connection conn = DatabaseConnector.getConnection();
        Statement stmt = conn.createStatement();
        return stmt.executeQuery("SELECT * FROM medicines");
    }
    public static void addMedicine(String name, double buyPrice, double sellPrice, LocalDate expiry) throws SQLException {
        String sql = "INSERT INTO medicines (m_name, m_buy_price, m_sell_price, m_expiry_date) VALUES (?, ?, ?, ?)";
        try (Connection conn = DatabaseConnector.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, name);
            stmt.setDouble(2, buyPrice);
            stmt.setDouble(3, sellPrice);
            stmt.setDate(4, Date.valueOf(expiry));
            stmt.executeUpdate();
        }
    }
    public static void updateMedicine(int id, String name, double buyPrice, double sellPrice, LocalDate expiry) throws SQLException {
        String sql = "UPDATE medicines SET m_name=?, m_buy_price=?, m_sell_price=?, m_expiry_date=? WHERE m_id=?";
        try (Connection conn = DatabaseConnector.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, name);
            stmt.setDouble(2, buyPrice);
            stmt.setDouble(3, sellPrice);
            stmt.setDate(4, Date.valueOf(expiry));
            stmt.setInt(5, id);
            stmt.executeUpdate();
        }
    }
    public static void deleteMedicine(int id) throws SQLException {
        String sql = "DELETE FROM medicines WHERE m_id=?";
        try (Connection conn = DatabaseConnector.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, id);
            stmt.executeUpdate();
        }
    }
    public static Medicine getMedicineById(int id) throws SQLException {
        String sql = "SELECT * FROM medicines WHERE m_id=?";
        try (Connection conn = DatabaseConnector.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, id);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return new Medicine(
                        rs.getInt("m_id"),
                        rs.getString("m_name"),
                        rs.getDouble("m_buy_price"),
                        rs.getDouble("m_sell_price"),
                        rs.getDate("m_expiry_date").toLocalDate()
                );
            }
            return null;
        }
    }
}

class ClientDAO {
    public static ResultSet getAllClients() throws SQLException {
        Connection conn = DatabaseConnector.getConnection();
        Statement stmt = conn.createStatement();
        return stmt.executeQuery("SELECT * FROM clients");
    }
    public static void addClient(String name, String phone, String email, LocalDate lastPurchase) throws SQLException {
        String sql = "INSERT INTO clients (c_name, phone, email) VALUES (?, ?, ?)";
        try (Connection conn = DatabaseConnector.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, name);
            stmt.setString(2, phone);
            stmt.setString(3, email);
            stmt.setDate(4, Date.valueOf(lastPurchase));
            stmt.executeUpdate();
        }
    }
    public static void updateClient(int id, String name, String phone, String email, LocalDate lastPurchase) throws SQLException {
        String sql = "UPDATE clients SET c_name=?, phone=?, email=? WHERE c_id=?";

        try (Connection conn = DatabaseConnector.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, name);
            stmt.setString(2, phone);
            stmt.setString(3, email);
            stmt.setDate(4, Date.valueOf(lastPurchase));
            stmt.setInt(5, id);
            stmt.executeUpdate();
        }
    }
    public static void deleteClient(int id) throws SQLException {
        String sql = "DELETE FROM clients WHERE c_id=?";
        try (Connection conn = DatabaseConnector.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, id);
            stmt.executeUpdate();
        }
    }
    public static Client getClientById(int id) throws SQLException {
        String sql = "SELECT * FROM clients WHERE c_id=?";
        try (Connection conn = DatabaseConnector.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, id);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return new Client(
                        rs.getInt("c_id"),
                        rs.getString("c_name"),
                        rs.getString("phone"),
                        rs.getString("email")
                );
            }
            return null;
        }
    }
}
class Medicine {
    private final int id;
    private final String name;
    private final double buyPrice;
    private final double sellPrice;
    private final LocalDate expiryDate;
    public Medicine(int id, String name, double buyPrice, double sellPrice, LocalDate expiryDate) {
        this.id = id;
        this.name = name;
        this.buyPrice = buyPrice;
        this.sellPrice = sellPrice;
        this.expiryDate = expiryDate;
    }
    public int getId() { return id; }
    public String getName() { return name; }
    public double getBuyPrice() { return buyPrice; }
    public double getSellPrice() { return sellPrice; }
    public LocalDate getExpiryDate() { return expiryDate; }
}
class Client {
    private final int id;
    private final String name;
    private final String phone;
    private final String email;
    public Client(int id, String name, String phone, String email) {
        this.id = id;
        this.name = name;
        this.phone = phone;
        this.email = email;
    }
    public int getId() { return id; }
    public String getName() { return name; }
    public String getPhone() { return phone; }
    public String getEmail() { return email; }
}
class MedicineDialog extends JDialog {
    private final JTextField nameField = new JTextField();
    private final JSpinner buySpinner = new JSpinner(new SpinnerNumberModel(0.0, 0.0, 10000.0, 0.5));
    private final JSpinner sellSpinner = new JSpinner(new SpinnerNumberModel(0.0, 0.0, 10000.0, 0.5));
    private final JTextField dateField = new JTextField();
    private final int editId;
    public MedicineDialog(Frame owner, String title, int editId) {
        super(owner, title, true);
        this.editId = editId;
        initializeUI();
    }
    private void initializeUI() {
        setSize(400, 250);
        setLocationRelativeTo(getOwner());
        JPanel panel = new JPanel(new GridLayout(5, 2, 10, 10));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));
        if (editId != -1) {
            try {
                Medicine medicine = MedicineDAO.getMedicineById(editId);
                if (medicine != null) {
                    nameField.setText(medicine.getName());
                    buySpinner.setValue(medicine.getBuyPrice());
                    sellSpinner.setValue(medicine.getSellPrice());
                    dateField.setText(medicine.getExpiryDate().toString());
                }
            } catch (SQLException e) {
                JOptionPane.showMessageDialog(this, "Error loading medicine: " + e.getMessage());
            }
        }
        panel.add(new JLabel("Medicine Name:"));
        panel.add(nameField);
        panel.add(new JLabel("Buy Price:"));
        panel.add(buySpinner);
        panel.add(new JLabel("Sell Price:"));
        panel.add(sellSpinner);
        panel.add(new JLabel("Expiry Date (YYYY-MM-DD):"));
        panel.add(dateField);
        JButton btnSave = new JButton(editId == -1 ? "Save" : "Update");
        btnSave.addActionListener(e -> saveMedicine());
        add(panel, BorderLayout.CENTER);
        add(btnSave, BorderLayout.SOUTH);
    }
    private void saveMedicine() {
        try {
            String name = nameField.getText();
            if (name.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Medicine name cannot be empty!");
                return;
            }
            double buyPrice = (Double) buySpinner.getValue();
            double sellPrice = (Double) sellSpinner.getValue();
            LocalDate expiry = LocalDate.parse(dateField.getText());
            if (editId == -1) {
                MedicineDAO.addMedicine(name, buyPrice, sellPrice, expiry);
            } else {
                MedicineDAO.updateMedicine(editId, name, buyPrice, sellPrice, expiry);
            }
            dispose();
        } catch (DateTimeParseException e) {
            JOptionPane.showMessageDialog(this, "Invalid date format!\nUse YYYY-MM-DD");
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Database error: " + e.getMessage());
        }
    }
}
class ClientDialog extends JDialog {
    private final JTextField nameField = new JTextField();
    private final JTextField phoneField = new JTextField();
    private final JTextField emailField = new JTextField();
    private final int editId;
    public ClientDialog(Frame owner, String title, int editId) {
        super(owner, title, true);
        this.editId = editId;
        initializeUI();
    }
    private void initializeUI() {
        setSize(400, 250);
        setLocationRelativeTo(getOwner());
        JPanel panel = new JPanel(new GridLayout(5, 2, 10, 10));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));
        if (editId != -1) {
            try {
                Client client = ClientDAO.getClientById(editId);
                if (client != null) {
                    nameField.setText(client.getName());
                    phoneField.setText(client.getPhone());
                    emailField.setText(client.getEmail());
                }
            } catch (SQLException e) {
                JOptionPane.showMessageDialog(this, "Error loading client: " + e.getMessage());
            }
        }
        panel.add(new JLabel("Client Name:"));
        panel.add(nameField);
        panel.add(new JLabel("Phone:"));
        panel.add(phoneField);
        panel.add(new JLabel("Email:"));
        panel.add(emailField);
        JButton btnSave = new JButton(editId == -1 ? "Save" : "Update");
        btnSave.addActionListener(e -> saveClient());
        add(panel, BorderLayout.CENTER);
        add(btnSave, BorderLayout.SOUTH);
    }
    private void saveClient() {
        try {
            String name = nameField.getText();
            if (name.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Client name cannot be empty!");
                return;
            }
            String phone = phoneField.getText();
            String email = emailField.getText();
            dispose();
        } catch (DateTimeParseException e) {
            JOptionPane.showMessageDialog(this, "Invalid date format!\nUse YYYY-MM-DD or leave empty");
        }
    }
}