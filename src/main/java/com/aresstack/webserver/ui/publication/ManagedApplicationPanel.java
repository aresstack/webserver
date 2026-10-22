package com.aresstack.webserver.ui.publication;

import com.aresstack.webserver.domain.ManagedApplication;
import com.aresstack.webserver.domain.ManagedEnvironmentVariable;
import com.aresstack.webserver.domain.RestartPolicy;
import com.aresstack.webserver.domain.ReadinessPolicy;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JPasswordField;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.TransferHandler;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.FlowLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.datatransfer.DataFlavor;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Baut die Tabs für die verwaltete JAR-Anwendung (Application, Environment,
 * Restart &amp; health) und sammelt deren Werte ein. Die Felder werden per
 * Enable/Disable geschaltet statt ein-/ausgeblendet, damit der Dialog nie
 * seine Größe ändern muss.
 */
final class ManagedApplicationPanel {
    private static final String UNCHANGED_SECRET = "(unchanged)";

    private final JCheckBox enabled = new JCheckBox("Run a local JAR application for this service");
    private final JTextField jarPath = new JTextField();
    private final JTextField javaExecutable = new JTextField(ManagedApplication.defaultJavaExecutable());
    private final JTextField workingDirectory = new JTextField();
    private final JCheckBox startAutomatically = new JCheckBox("Start automatically", true);
    private final JCheckBox restartAutomatically = new JCheckBox("Restart after process exit", true);
    private final JTextArea jvmArgs = new JTextArea(3, 20);
    private final JTextArea appArgs = new JTextArea(3, 20);
    private final DefaultTableModel variables = new DefaultTableModel(
            new Object[]{"Name", "Value", "Secret"}, 0) {
        @Override
        public Class<?> getColumnClass(int column) {
            return column == 2 ? Boolean.class : String.class;
        }
    };
    private final JTable variableTable = new JTable(variables);
    private final JSpinner initialDelay = seconds(1);
    private final JSpinner multiplier = new JSpinner(new SpinnerNumberModel(2.0, 1.0, 100.0, 0.1));
    private final JSpinner maxDelay = seconds(300);
    private final JSpinner resetAfter = seconds(300);
    private final JSpinner maxAttempts = seconds(0);
    private final JSpinner stopTimeout = seconds(10);
    private final JSpinner startupTimeout = seconds(30);
    private final JSpinner readinessPoll = seconds(200);
    private final JSpinner connectionTimeout = seconds(500);
    private final JTextField healthPath = new JTextField();
    private final JSpinner healthStatus = new JSpinner(new SpinnerNumberModel(200, 100, 599, 1));
    private final ManagedApplication original;
    private final List<JComponent> managedSections = new ArrayList<>();
    private final List<Consumer<Boolean>> enabledListeners = new ArrayList<>();

    ManagedApplicationPanel(Optional<ManagedApplication> original) {
        this.original = original.orElse(null);
        enabled.setSelected(this.original != null);
        enabled.addActionListener(e -> fireEnabledChanged());
        loadOriginal();
    }

    boolean managedEnabled() {
        return enabled.isSelected();
    }

    /** Wird bei jedem Umschalten der Checkbox (und einmal initial) aufgerufen. */
    void onEnabledChanged(Consumer<Boolean> listener) {
        enabledListeners.add(listener);
        listener.accept(enabled.isSelected());
    }

    /** Nach dem Aufbau aller Tabs aufrufen, damit der Initialzustand stimmt. */
    void refreshEnabledState() {
        fireEnabledChanged();
    }

    private void fireEnabledChanged() {
        boolean on = enabled.isSelected();
        for (JComponent section : managedSections) {
            setEnabledRecursively(section, on);
        }
        for (Consumer<Boolean> listener : enabledListeners) {
            listener.accept(on);
        }
    }

    private static void setEnabledRecursively(Component component, boolean on) {
        component.setEnabled(on);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                setEnabledRecursively(child, on);
            }
        }
    }

    /** Tab "Application": JAR, Java, Arbeitsverzeichnis, Argumente, Lifecycle. */
    JComponent applicationTab() {
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 0, 4, 8);
        c.anchor = GridBagConstraints.NORTHWEST;
        int row = 0;

        GridBagConstraints full = (GridBagConstraints) c.clone();
        full.gridx = 0;
        full.gridwidth = 2;
        full.weightx = 1;
        full.fill = GridBagConstraints.HORIZONTAL;
        full.gridy = row++;
        form.add(enabled, full);

        JPanel fields = new JPanel(new GridBagLayout());
        GridBagConstraints f = new GridBagConstraints();
        f.insets = new Insets(4, 0, 4, 8);
        f.anchor = GridBagConstraints.NORTHWEST;
        int fieldRow = 0;

        jarPath.setToolTipText("Drop a .jar file here, paste its path, or use Browse");
        jarPath.setTransferHandler(new JarDropHandler());
        addLabeledField(fields, f, fieldRow++, "JAR file", withBrowseButton(jarPath));
        addLabeledField(fields, f, fieldRow++, "Java executable", withBrowseButton(javaExecutable));
        workingDirectory.setToolTipText("Leave blank to use the JAR directory");
        addLabeledField(fields, f, fieldRow++, "Working directory", withHint(workingDirectory,
                "Blank: directory of the JAR file"));
        addLabeledField(fields, f, fieldRow++, "JVM arguments", withHint(new JScrollPane(jvmArgs),
                "One argument per line, e.g. -Xmx512m"));
        addLabeledField(fields, f, fieldRow++, "Application arguments", withHint(new JScrollPane(appArgs),
                "One argument per line"));

        JPanel lifecycle = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        lifecycle.add(startAutomatically);
        lifecycle.add(restartAutomatically);
        addLabeledField(fields, f, fieldRow, "Lifecycle", lifecycle);

        full.gridy = row++;
        full.insets = new Insets(8, 0, 4, 8);
        form.add(fields, full);
        managedSections.add(fields);

        // Restlichen Platz nach unten schlucken, damit das Formular oben bleibt.
        GridBagConstraints glue = new GridBagConstraints();
        glue.gridx = 0;
        glue.gridy = row;
        glue.weighty = 1;
        form.add(new JPanel(), glue);

        return pad(form);
    }

    /** Tab "Environment": Tabelle mit Umgebungsvariablen. */
    JComponent environmentTab() {
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        variableTable.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        variableTable.setFillsViewportHeight(true);
        variableTable.setRowHeight(variableTable.getRowHeight() + 4);
        variableTable.getColumnModel().getColumn(2).setMaxWidth(70);
        variableTable.getColumnModel().getColumn(1).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable target, Object value,
                    boolean selected, boolean focused, int row, int column) {
                super.getTableCellRendererComponent(target, value, selected, focused, row, column);
                int modelRow = target.convertRowIndexToModel(row);
                if (Boolean.TRUE.equals(target.getModel().getValueAt(modelRow, 2))) {
                    setText("••••••••");
                }
                return this;
            }
        });
        variableTable.getColumnModel().getColumn(1).setCellEditor(new SecretCellEditor());
        panel.add(new JScrollPane(variableTable), BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JButton addVariable = new JButton("+ Add variable");
        addVariable.addActionListener(e -> variables.addRow(new Object[]{"", "", false}));
        JButton removeVariable = new JButton("Remove selected");
        removeVariable.addActionListener(e -> {
            if (variableTable.getSelectedRow() >= 0) {
                variables.removeRow(variableTable.convertRowIndexToModel(variableTable.getSelectedRow()));
            }
        });
        actions.add(addVariable);
        actions.add(removeVariable);
        JLabel hint = hintLabel("Secret values are stored encrypted and shown masked.");
        JPanel south = new JPanel(new BorderLayout());
        south.add(actions, BorderLayout.WEST);
        south.add(hint, BorderLayout.SOUTH);
        panel.add(south, BorderLayout.SOUTH);
        managedSections.add(panel);
        return pad(panel);
    }

    /** Tab "Restart &amp; health": Restart-Policy und Readiness-Checks. */
    JComponent restartTab() {
        JPanel restart = new JPanel(new GridBagLayout());
        restart.setBorder(BorderFactory.createTitledBorder("Restart policy"));
        int row = 0;
        addSetting(restart, row++, "Initial delay (s)", initialDelay);
        addSetting(restart, row++, "Backoff multiplier", multiplier);
        addSetting(restart, row++, "Maximum delay (s)", maxDelay);
        addSetting(restart, row++, "Reset after uptime (s)", resetAfter);
        maxAttempts.setToolTipText("0 means unlimited restart attempts");
        addSetting(restart, row++, "Maximum attempts (0 = unlimited)", maxAttempts);
        addSetting(restart, row, "Stop timeout (s)", stopTimeout);

        JPanel readiness = new JPanel(new GridBagLayout());
        readiness.setBorder(BorderFactory.createTitledBorder("Readiness check"));
        row = 0;
        addSetting(readiness, row++, "Startup timeout (s)", startupTimeout);
        addSetting(readiness, row++, "Poll interval (ms)", readinessPoll);
        addSetting(readiness, row++, "TCP timeout (ms)", connectionTimeout);
        healthPath.setToolTipText("Leave empty to use TCP readiness checks");
        healthPath.setColumns(12);
        addSetting(readiness, row++, "HTTP health path (blank: TCP check)", healthPath);
        addSetting(readiness, row, "Expected HTTP status", healthStatus);

        JPanel columns = new JPanel(new GridBagLayout());
        GridBagConstraints col = new GridBagConstraints();
        col.gridy = 0;
        col.anchor = GridBagConstraints.NORTHWEST;
        col.weightx = 1;
        col.fill = GridBagConstraints.HORIZONTAL;
        col.insets = new Insets(0, 0, 0, 8);
        col.gridx = 0;
        columns.add(restart, col);
        col.insets = new Insets(0, 8, 0, 0);
        col.gridx = 1;
        columns.add(readiness, col);

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(columns, BorderLayout.NORTH);
        managedSections.add(panel);
        return pad(panel);
    }

    private JComponent withBrowseButton(JTextField field) {
        JPanel panel = new JPanel(new BorderLayout(6, 0));
        JButton browse = new JButton("Browse…");
        browse.addActionListener(e -> chooseFile(field));
        panel.add(field, BorderLayout.CENTER);
        panel.add(browse, BorderLayout.EAST);
        return panel;
    }

    private static JComponent withHint(JComponent field, String hint) {
        JPanel panel = new JPanel(new BorderLayout(0, 2));
        panel.add(field, BorderLayout.CENTER);
        panel.add(hintLabel(hint), BorderLayout.SOUTH);
        return panel;
    }

    private static JLabel hintLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(label.getFont().getSize2D() - 1f));
        label.setForeground(java.awt.Color.GRAY);
        return label;
    }

    private static JComponent pad(JComponent component) {
        component.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(12, 12, 12, 12), component.getBorder()));
        return component;
    }

    private static void addLabeledField(JPanel panel, GridBagConstraints template, int row,
                                        String label, Component field) {
        GridBagConstraints c = (GridBagConstraints) template.clone();
        c.gridx = 0;
        c.gridy = row;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        panel.add(new JLabel(label), c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(field, c);
    }

    private static void addSetting(JPanel panel, int row, String name, Component control) {
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 8, 3, 8);
        c.anchor = GridBagConstraints.WEST;
        c.gridy = row;
        c.gridx = 0;
        c.weightx = 1;
        panel.add(new JLabel(name), c);
        c.gridx = 1;
        c.weightx = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(control, c);
    }

    Optional<ManagedApplication> value() {
        if (!enabled.isSelected()) {
            return Optional.empty();
        }
        String jar = jarPath.getText().trim();
        if (jar.isEmpty()) {
            throw new IllegalArgumentException("Choose a JAR file or disable the managed application");
        }
        if (!jar.toLowerCase().endsWith(".jar")) {
            throw new IllegalArgumentException("Application must be a JAR file");
        }
        if (variableTable.isEditing() && !variableTable.getCellEditor().stopCellEditing()) {
            throw new IllegalStateException("Complete the environment variable edit first");
        }
        if (tableEditing()) {
            throw new IllegalStateException("Complete the environment variable edit first");
        }
        RestartPolicy policy = new RestartPolicy(
                ((Number) initialDelay.getValue()).longValue(),
                ((Number) multiplier.getValue()).doubleValue(),
                ((Number) maxDelay.getValue()).longValue(),
                ((Number) resetAfter.getValue()).longValue(),
                ((Number) maxAttempts.getValue()).intValue());
        String id = original != null ? original.id() : UUID.randomUUID().toString();
        return Optional.of(new ManagedApplication(id, jar, javaExecutable.getText().trim(),
                workingDirectory.getText().trim(), lines(jvmArgs), lines(appArgs),
                collectVariables(), startAutomatically.isSelected(),
                restartAutomatically.isSelected(), policy,
                new ReadinessPolicy(((Number) startupTimeout.getValue()).intValue(),
                        ((Number) readinessPoll.getValue()).intValue(),
                        ((Number) connectionTimeout.getValue()).intValue(),
                        healthPath.getText().trim(),
                        ((Number) healthStatus.getValue()).intValue()),
                ((Number) stopTimeout.getValue()).intValue()));
    }

    private boolean tableEditing() {
        for (int row = 0; row < variables.getRowCount(); row++) {
            if (variables.getValueAt(row, 0) == null || variables.getValueAt(row, 1) == null) {
                return true;
            }
        }
        return false;
    }

    private Map<String, ManagedEnvironmentVariable> collectVariables() {
        Map<String, ManagedEnvironmentVariable> result = new LinkedHashMap<>();
        for (int row = 0; row < variables.getRowCount(); row++) {
            String key = String.valueOf(variables.getValueAt(row, 0)).trim();
            String value = String.valueOf(variables.getValueAt(row, 1));
            boolean secret = Boolean.TRUE.equals(variables.getValueAt(row, 2));
            if (key.isBlank()) {
                if (value.isBlank()) {
                    continue;
                }
                throw new IllegalArgumentException("Environment variable name cannot be empty");
            }
            if (secret && UNCHANGED_SECRET.equals(value)) {
                ManagedEnvironmentVariable old = original == null ? null : original.environment().get(key);
                if (old == null || !old.secret()) {
                    throw new IllegalArgumentException("Enter the new secret value for " + key);
                }
                value = old.value();
            }
            if (result.putIfAbsent(key, new ManagedEnvironmentVariable(value, secret)) != null) {
                throw new IllegalArgumentException("Duplicate environment variable: " + key);
            }
        }
        return result;
    }

    private void loadOriginal() {
        if (original == null) {
            return;
        }
        jarPath.setText(original.jarPath());
        javaExecutable.setText(original.javaExecutable());
        workingDirectory.setText(original.workingDirectory());
        startAutomatically.setSelected(original.startAutomatically());
        restartAutomatically.setSelected(original.restartAutomatically());
        jvmArgs.setText(String.join("\n", original.jvmArguments()));
        appArgs.setText(String.join("\n", original.applicationArguments()));
        original.environment().forEach((key, variable) ->
                variables.addRow(new Object[]{key, variable.secret() ? UNCHANGED_SECRET : variable.value(),
                        variable.secret()}));
        RestartPolicy policy = original.restartPolicy();
        initialDelay.setValue(Math.toIntExact(policy.initialDelaySeconds()));
        multiplier.setValue(policy.multiplier());
        maxDelay.setValue(Math.toIntExact(policy.maxDelaySeconds()));
        resetAfter.setValue(Math.toIntExact(policy.resetAfterSeconds()));
        maxAttempts.setValue(policy.maximumAttempts());
        stopTimeout.setValue(original.stopTimeoutSeconds());
        startupTimeout.setValue(original.readinessPolicy().startupTimeoutSeconds());
        readinessPoll.setValue(original.readinessPolicy().pollIntervalMillis());
        connectionTimeout.setValue(original.readinessPolicy().connectionTimeoutMillis());
        healthPath.setText(original.readinessPolicy().httpHealthPath());
        healthStatus.setValue(original.readinessPolicy().expectedHttpStatus());
    }

    private static List<String> lines(JTextArea field) {
        List<String> values = new ArrayList<>();
        for (String line : field.getText().split("\\R")) {
            if (!line.isBlank()) {
                values.add(line.trim());
            }
        }
        return values;
    }

    private static JSpinner seconds(int defaultValue) {
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(defaultValue, 0, null, 1));
        Dimension size = spinner.getPreferredSize();
        spinner.setPreferredSize(new Dimension(Math.max(size.width, 70), size.height));
        return spinner;
    }

    private void chooseFile(JTextField target) {
        JFileChooser chooser = new JFileChooser();
        if (!target.getText().isBlank()) {
            chooser.setSelectedFile(new File(target.getText()));
        }
        if (chooser.showOpenDialog(target) == JFileChooser.APPROVE_OPTION) {
            target.setText(chooser.getSelectedFile().getAbsolutePath());
            if (!enabled.isSelected()) {
                enabled.setSelected(true);
                fireEnabledChanged();
            }
        }
    }

    private final class SecretCellEditor extends javax.swing.AbstractCellEditor
            implements javax.swing.table.TableCellEditor {
        private final JTextField ordinary = new JTextField();
        private final JPasswordField password = new JPasswordField();
        private JTextField active;

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value,
                boolean selected, int row, int column) {
            int modelRow = table.convertRowIndexToModel(row);
            boolean secret = Boolean.TRUE.equals(table.getModel().getValueAt(modelRow, 2));
            active = secret ? password : ordinary;
            active.setText(String.valueOf(value == null ? "" : value));
            return active;
        }

        @Override
        public Object getCellEditorValue() {
            if (active == password) {
                char[] chars = password.getPassword();
                try {
                    return new String(chars);
                } finally {
                    java.util.Arrays.fill(chars, '\0');
                    password.setText("");
                }
            }
            return ordinary.getText();
        }
    }

    private final class JarDropHandler extends TransferHandler {
        @Override
        public boolean canImport(TransferSupport support) {
            return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
                    || support.isDataFlavorSupported(DataFlavor.stringFlavor);
        }

        @Override
        public boolean importData(TransferSupport support) {
            if (!canImport(support)) {
                return false;
            }
            try {
                String path;
                if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    @SuppressWarnings("unchecked")
                    List<File> files = (List<File>) support.getTransferable()
                            .getTransferData(DataFlavor.javaFileListFlavor);
                    if (files.size() != 1) {
                        return false;
                    }
                    path = files.get(0).getAbsolutePath();
                } else {
                    path = String.valueOf(support.getTransferable()
                            .getTransferData(DataFlavor.stringFlavor)).trim();
                    if (path.length() >= 2 && path.startsWith("\"") && path.endsWith("\"")) {
                        path = path.substring(1, path.length() - 1);
                    }
                }
                File jar = new File(path);
                if (!jar.isFile() || !jar.getName().toLowerCase().endsWith(".jar")) {
                    return false;
                }
                jarPath.setText(jar.getAbsolutePath());
                if (!enabled.isSelected()) {
                    enabled.setSelected(true);
                    fireEnabledChanged();
                }
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }
}
