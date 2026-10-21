package com.aresstack.webserver.ui.publication;

import com.aresstack.webserver.domain.ManagedApplication;
import com.aresstack.webserver.domain.ManagedEnvironmentVariable;
import com.aresstack.webserver.domain.RestartPolicy;
import com.aresstack.webserver.infrastructure.managed.WindowsDpapiSecrets;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
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
import java.awt.GridLayout;
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
    private final JTextField jarPath = new JTextField(28);
    private final JTextField javaExecutable = new JTextField(ManagedApplication.defaultJavaExecutable(), 28);
    private final JTextField workingDirectory = new JTextField(28);
    private final JCheckBox startAutomatically = new JCheckBox("Start automatically", true);
    private final JCheckBox restartAutomatically = new JCheckBox("Restart after process exit", true);
    private final JTextArea jvmArgs = new JTextArea(2, 28);
    private final JTextArea appArgs = new JTextArea(2, 28);
    private final DefaultTableModel variables = new DefaultTableModel(
            new Object[]{"Name", "Value", "Secret"}, 0) {
        @Override
        public Class<?> getColumnClass(int column) {
            return column == 2 ? Boolean.class : String.class;
        }
    };
    private final JSpinner initialDelay = seconds(1);
    private final JSpinner multiplier = new JSpinner(new SpinnerNumberModel(2.0, 1.0, 100.0, 0.1));
    private final JSpinner maxDelay = seconds(300);
    private final JSpinner resetAfter = seconds(300);
    private final JSpinner maxAttempts = seconds(0);
    private final JSpinner stopTimeout = seconds(10);
    private final JPanel settings = new JPanel();
    private final JPanel advanced = new JPanel();
    private final ManagedApplication original;

    ManagedApplicationPanel(Optional<ManagedApplication> original) {
        super();
        this.original = original.orElse(null);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));

        enabled.setSelected(this.original != null);
        enabled.addActionListener(e -> {
            settings.setVisible(enabled.isSelected());
            resizeDialog();
        });
        add(enabled);

        settings.setLayout(new BoxLayout(settings, BoxLayout.Y_AXIS));
        settings.add(label("JAR file (drop a file from Windows Explorer)"));
        JPanel fileRow = new JPanel(new BorderLayout(6, 0));
        jarPath.setToolTipText("Drop a .jar file here, paste its path, or use Browse");
        jarPath.setTransferHandler(new JarDropHandler());
        JButton browseJar = new JButton("Browse…");
        browseJar.addActionListener(e -> chooseFile(jarPath));
        fileRow.add(jarPath, BorderLayout.CENTER);
        fileRow.add(browseJar, BorderLayout.EAST);
        settings.add(fileRow);

        settings.add(label("Java executable"));
        JPanel javaRow = new JPanel(new BorderLayout(6, 0));
        JButton browseJava = new JButton("Browse…");
        browseJava.addActionListener(e -> chooseFile(javaExecutable));
        javaRow.add(javaExecutable, BorderLayout.CENTER);
        javaRow.add(browseJava, BorderLayout.EAST);
        settings.add(javaRow);
        settings.add(startAutomatically);
        settings.add(restartAutomatically);

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
        settings.add(variablesToggle);

        variablesPanel.add(label("Environment variables"));
        JTable table = new JTable(variables);
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        table.setFillsViewportHeight(true);
        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setPreferredSize(new java.awt.Dimension(460, 96));
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
        variablesPanel.add(variableActions);
        variablesPanel.setVisible(false);
        settings.add(variablesPanel);

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
        settings.add(advancedToggle);
        advanced.setLayout(new BoxLayout(advanced, BoxLayout.Y_AXIS));
        advanced.add(label("Working directory (blank: JAR directory)"));
        advanced.add(workingDirectory);
        advanced.add(label("JVM arguments (one per line)"));
        advanced.add(new JScrollPane(jvmArgs));
        advanced.add(label("Application arguments (one per line)"));
        advanced.add(new JScrollPane(appArgs));


        JPanel policy = new JPanel(new GridLayout(0, 2, 8, 4));
        policy.setBorder(BorderFactory.createTitledBorder("Restart policy"));
        addSetting(policy, "Initial delay (seconds)", initialDelay);
        addSetting(policy, "Backoff multiplier", multiplier);
        addSetting(policy, "Maximum delay (seconds)", maxDelay);
        addSetting(policy, "Reset after uptime (seconds)", resetAfter);
        addSetting(policy, "Maximum attempts (0 = unlimited)", maxAttempts);
        addSetting(policy, "Stop timeout (seconds)", stopTimeout);
        advanced.add(policy);
        advanced.setVisible(false);
        settings.add(advanced);
        settings.setVisible(enabled.isSelected());
        add(settings);
        loadOriginal();
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
            } else if (secret) {
                value = WindowsDpapiSecrets.protect(value);
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
        return label;
    }

    private static void addSetting(JPanel panel, String name, JSpinner control) {
        panel.add(new JLabel(name));
        panel.add(control);
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
        java.awt.Window window = javax.swing.SwingUtilities.getWindowAncestor(this);
        if (window != null) {
            window.pack();
            window.setLocationRelativeTo(window.getOwner());
        }
    }

    private final class JarDropHandler extends TransferHandler {
        @Override
        public boolean canImport(TransferSupport support) {
            return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
        }

        @Override
        public boolean importData(TransferSupport support) {
            if (!canImport(support)) {
                return false;
            }
            try {
                @SuppressWarnings("unchecked")
                List<File> files = (List<File>) support.getTransferable()
                        .getTransferData(DataFlavor.javaFileListFlavor);
                if (files.size() != 1 || !files.get(0).isFile()
                        || !files.get(0).getName().toLowerCase().endsWith(".jar")) {
                    return false;
                }
                jarPath.setText(files.get(0).getAbsolutePath());
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
