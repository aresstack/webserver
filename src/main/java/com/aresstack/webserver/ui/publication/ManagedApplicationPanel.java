package com.aresstack.webserver.ui.publication;

import com.aresstack.webserver.domain.ManagedApplication;
import com.aresstack.webserver.domain.ManagedEnvironmentVariable;
import com.aresstack.webserver.domain.RestartPolicy;
import com.aresstack.webserver.domain.ReadinessPolicy;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.DefaultCellEditor;
import javax.swing.JPasswordField;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SpinnerNumberModel;
import javax.swing.TransferHandler;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
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

/** Keep optional JAR settings out of the existing HTTP publication form. */
final class ManagedApplicationPanel extends JPanel {
    private static final String UNCHANGED_SECRET = "(unchanged)";

    private final JCheckBox enabled = new JCheckBox("Manage local JAR application");
    private final JTextField jarPath = new JTextField(22);
    private final JTextField javaExecutable = new JTextField(ManagedApplication.defaultJavaExecutable(), 22);
    private final JTextField workingDirectory = new JTextField(28);
    private final JCheckBox startAutomatically = new JCheckBox("Start automatically", true);
    private final JCheckBox restartAutomatically = new JCheckBox("Restart after process exit", true);
    private final JTextArea jvmArgs = new JTextArea(2, 24);
    private final JTextArea appArgs = new JTextArea(2, 24);
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
    private final JTextField healthPath = new JTextField(12);
    private final JSpinner healthStatus = new JSpinner(new SpinnerNumberModel(200, 100, 599, 1));
    private final JPanel settings = new JPanel();
    private final JPanel advanced = new JPanel();
    private final ManagedApplication original;
    private final Runnable layoutChanged;

    ManagedApplicationPanel(Optional<ManagedApplication> original, Runnable layoutChanged) {
        super();
        this.original = original.orElse(null);
        this.layoutChanged = layoutChanged;
        setLayout(new GridBagLayout());
        setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));

        enabled.setSelected(this.original != null);
        enabled.addActionListener(e -> {
            settings.setVisible(enabled.isSelected());
            resizeDialog();
        });

        GridBagConstraints panelConstraints = new GridBagConstraints();
        panelConstraints.gridx = 0;
        panelConstraints.gridy = 0;
        panelConstraints.gridwidth = 2;
        panelConstraints.anchor = GridBagConstraints.WEST;
        panelConstraints.insets = new Insets(4, 12, 2, 12);
        add(enabled, panelConstraints);

        settings.setLayout(new GridBagLayout());
        GridBagConstraints settingsConstraints = new GridBagConstraints();
        settingsConstraints.insets = new Insets(3, 12, 3, 12);
        settingsConstraints.anchor = GridBagConstraints.WEST;
        int row = 0;
        settings.add(label("JAR file"), labelConstraints(settingsConstraints, row));
        JPanel fileRow = new JPanel(new BorderLayout(6, 0));
        jarPath.setToolTipText("Drop a .jar file here, paste its path, or use Browse");
        jarPath.setTransferHandler(new JarDropHandler());
        JButton browseJar = new JButton("Browse…");
        browseJar.addActionListener(e -> chooseFile(jarPath));
        fileRow.add(jarPath, BorderLayout.CENTER);
        fileRow.add(browseJar, BorderLayout.EAST);
        addField(settings, settingsConstraints, row++, fileRow);

        settings.add(label("Java executable"), labelConstraints(settingsConstraints, row));
        JPanel javaRow = new JPanel(new BorderLayout(6, 0));
        JButton browseJava = new JButton("Browse…");
        browseJava.addActionListener(e -> chooseFile(javaExecutable));
        javaRow.add(javaExecutable, BorderLayout.CENTER);
        javaRow.add(browseJava, BorderLayout.EAST);
        addField(settings, settingsConstraints, row++, javaRow);
        addControl(settings, settingsConstraints, row++, startAutomatically, false);
        addControl(settings, settingsConstraints, row++, restartAutomatically, false);

        JToggleButton variablesToggle = new JToggleButton("▸ Environment variables");
        variablesToggle.setHorizontalAlignment(JToggleButton.LEFT);
        variablesToggle.setBorderPainted(false);
        variablesToggle.setContentAreaFilled(false);
        JPanel variablesPanel = new JPanel();
        variablesPanel.setLayout(new BoxLayout(variablesPanel, BoxLayout.Y_AXIS));
        variablesToggle.addActionListener(e -> {
            variablesPanel.setVisible(variablesToggle.isSelected());
            variablesToggle.setText(variablesToggle.isSelected()
                    ? "▾ Environment variables" : "▸ Environment variables");
            resizeDialog();
        });
        addControl(settings, settingsConstraints, row++, variablesToggle, false);

        variablesPanel.add(label("Environment variables"));
        JTable table = variableTable;
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        table.setFillsViewportHeight(true);
        table.getColumnModel().getColumn(1).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public java.awt.Component getTableCellRendererComponent(JTable target, Object value,
                    boolean selected, boolean focused, int row, int column) {
                super.getTableCellRendererComponent(target, value, selected, focused, row, column);
                int modelRow = target.convertRowIndexToModel(row);
                if (Boolean.TRUE.equals(target.getModel().getValueAt(modelRow, 2))) {
                    setText("••••••••");
                }
                return this;
            }
        });
        table.getColumnModel().getColumn(1).setCellEditor(new SecretCellEditor());
        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setPreferredSize(new Dimension(280, 96));
        tableScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        variablesPanel.add(tableScroll);
        JPanel variableActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        JButton addVariable = new JButton("+ Add variable");
        addVariable.addActionListener(e -> variables.addRow(new Object[]{"", "", false}));
        JButton removeVariable = new JButton("Remove selected");
        removeVariable.addActionListener(e -> {
            if (table.getSelectedRow() >= 0) {
                variables.removeRow(table.convertRowIndexToModel(table.getSelectedRow()));
            }
        });
        variableActions.add(addVariable);
        variableActions.add(removeVariable);
        variableActions.setAlignmentX(Component.LEFT_ALIGNMENT);
        variablesPanel.add(variableActions);
        variablesPanel.setVisible(false);
        addControl(settings, settingsConstraints, row++, variablesPanel, true);

        JToggleButton advancedToggle = new JToggleButton("▸ Advanced application settings");
        advancedToggle.setHorizontalAlignment(JToggleButton.LEFT);
        advancedToggle.setBorderPainted(false);
        advancedToggle.setContentAreaFilled(false);
        advancedToggle.addActionListener(e -> {
            advanced.setVisible(advancedToggle.isSelected());
            advancedToggle.setText(advancedToggle.isSelected()
                    ? "▾ Advanced application settings" : "▸ Advanced application settings");
            resizeDialog();
        });
        addControl(settings, settingsConstraints, row++, advancedToggle, false);
        advanced.setLayout(new BoxLayout(advanced, BoxLayout.Y_AXIS));
        JLabel workingDirectoryLabel = label("Working directory (blank: JAR directory)");
        workingDirectoryLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        workingDirectory.setToolTipText("Leave blank to use the JAR directory");
        workingDirectory.setAlignmentX(Component.LEFT_ALIGNMENT);
        advanced.add(workingDirectoryLabel);
        advanced.add(workingDirectory);
        JLabel jvmArgumentsLabel = label("JVM arguments (one per line)");
        jvmArgumentsLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        jvmArgs.setAlignmentX(Component.LEFT_ALIGNMENT);
        advanced.add(jvmArgumentsLabel);
        JScrollPane jvmArgumentsScroll = new JScrollPane(jvmArgs);
        jvmArgumentsScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        advanced.add(jvmArgumentsScroll);
        JLabel applicationArgumentsLabel = label("Application arguments (one per line)");
        applicationArgumentsLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        appArgs.setAlignmentX(Component.LEFT_ALIGNMENT);
        advanced.add(applicationArgumentsLabel);
        JScrollPane applicationArgumentsScroll = new JScrollPane(appArgs);
        applicationArgumentsScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        advanced.add(applicationArgumentsScroll);


        JPanel policy = new JPanel(new GridBagLayout());
        policy.setBorder(BorderFactory.createTitledBorder("Restart policy"));
        addSetting(policy, "Initial delay (s)", initialDelay);
        addSetting(policy, "Backoff multiplier", multiplier);
        addSetting(policy, "Maximum delay (s)", maxDelay);
        addSetting(policy, "Reset after uptime (s)", resetAfter);
        maxAttempts.setToolTipText("0 means unlimited restart attempts");
        addSetting(policy, "Maximum attempts", maxAttempts);
        addSetting(policy, "Stop timeout (s)", stopTimeout);
        addSetting(policy, "Startup timeout (s)", startupTimeout);
        addSetting(policy, "Readiness poll (ms)", readinessPoll);
        addSetting(policy, "TCP timeout (ms)", connectionTimeout);
        healthPath.setToolTipText("Leave empty to use TCP readiness checks");
        addSetting(policy, "Health path", healthPath);
        addSetting(policy, "Expected HTTP status", healthStatus);
        advanced.add(policy);
        advanced.setVisible(false);
        addControl(settings, settingsConstraints, row, advanced, true);
        settings.setVisible(enabled.isSelected());
        panelConstraints.gridy = 1;
        panelConstraints.weightx = 1;
        panelConstraints.fill = GridBagConstraints.HORIZONTAL;
        panelConstraints.insets = new Insets(0, 0, 0, 0);
        add(settings, panelConstraints);
        loadOriginal();

    }

    private static GridBagConstraints labelConstraints(GridBagConstraints template, int row) {
        GridBagConstraints constraints = (GridBagConstraints) template.clone();
        constraints.gridx = 0;
        constraints.gridy = row;
        constraints.weightx = 0;
        constraints.fill = GridBagConstraints.NONE;
        constraints.gridwidth = 1;
        return constraints;
    }

    private static void addField(JPanel panel, GridBagConstraints template, int row, Component field) {
        GridBagConstraints constraints = (GridBagConstraints) template.clone();
        constraints.gridx = 1;
        constraints.gridy = row;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.gridwidth = 1;
        panel.add(field, constraints);
    }

    private static void addControl(JPanel panel, GridBagConstraints template, int row,
                                   Component control, boolean fillHorizontal) {
        GridBagConstraints constraints = (GridBagConstraints) template.clone();
        constraints.gridx = fillHorizontal ? 1 : 0;
        constraints.gridy = row;
        constraints.weightx = 1;
        constraints.fill = fillHorizontal ? GridBagConstraints.HORIZONTAL : GridBagConstraints.NONE;
        constraints.gridwidth = fillHorizontal ? 1 : 2;
        panel.add(control, constraints);
    }

    Optional<ManagedApplication> value() {
        if (!enabled.isSelected()) {
            return Optional.empty();
        }
        String jar = jarPath.getText().trim();
        if (jar.isEmpty()) {
            throw new IllegalArgumentException("Choose a JAR file or disable managed application");
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
        return new JSpinner(new SpinnerNumberModel(defaultValue, 0, null, 1));
    }

    private static JLabel label(String value) {
        JLabel label = new JLabel(value);
        label.setBorder(BorderFactory.createEmptyBorder(5, 0, 2, 0));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private static void addSetting(JPanel panel, String name, Component control) {
        int row = panel.getComponentCount() / 2;
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(2, 6, 2, 6);
        constraints.anchor = GridBagConstraints.WEST;
        constraints.gridy = row;
        constraints.gridx = 0;
        constraints.weightx = 0;
        constraints.fill = GridBagConstraints.NONE;
        panel.add(new JLabel(name), constraints);
        constraints.gridx = 1;
        constraints.weightx = 1;
        panel.add(control, constraints);
    }

    private void chooseFile(JTextField target) {
        JFileChooser chooser = new JFileChooser();
        if (!target.getText().isBlank()) {
            chooser.setSelectedFile(new File(target.getText()));
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            target.setText(chooser.getSelectedFile().getAbsolutePath());
            enabled.setSelected(true);
            settings.setVisible(true);
            resizeDialog();
        }
    }

    private void resizeDialog() {
        layoutChanged.run();
    }

    private final class SecretCellEditor extends javax.swing.AbstractCellEditor
            implements javax.swing.table.TableCellEditor {
        private final JTextField ordinary = new JTextField();
        private final JPasswordField password = new JPasswordField();
        private JTextField active;

        @Override
        public java.awt.Component getTableCellEditorComponent(JTable table, Object value,
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
                enabled.setSelected(true);
                settings.setVisible(true);
                resizeDialog();
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }
}
