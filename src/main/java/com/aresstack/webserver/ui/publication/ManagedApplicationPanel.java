package com.aresstack.webserver.ui.publication;

import com.aresstack.webserver.domain.ManagedApplication;
import com.aresstack.webserver.domain.DerivedEnvironment;
import com.aresstack.webserver.domain.ManagedEnvironmentVariable;
import com.aresstack.webserver.domain.RestartPolicy;
import com.aresstack.webserver.domain.ReadinessPolicy;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JPasswordField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.TransferHandler;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.JTextComponent;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Baut die Application-Unterseiten (Runtime, Environment, Restart &amp; health)
 * und sammelt deren Werte ein. Es gibt keine "Manage"-Checkbox mehr: eine
 * verwaltete Anwendung ist genau dann konfiguriert, wenn eine JAR-Datei
 * angegeben ist. Der Enabled-Schalter unter Lifecycle schaltet nur die
 * Prozessverwaltung ab, ohne die Konfiguration zu verwerfen.
 */
final class ManagedApplicationPanel {
    private static final String UNCHANGED_SECRET = "(unchanged)";

    private final JTextField jarPath = new JTextField();
    private final JComboBox<String> javaExecutable = new JComboBox<>();
    private final JTextField workingDirectory = new JTextField();
    private final JCheckBox applicationEnabled = new JCheckBox("Application enabled", true);
    private final JCheckBox startAutomatically = new JCheckBox("Start automatically", true);
    private final JCheckBox restartAutomatically = new JCheckBox("Restart after process exit", true);
    private final JTextArea jvmArgs = new JTextArea(4, 20);
    private final JTextArea appArgs = new JTextArea(4, 20);
    /**
     * Spalten: Enabled, Name, Value, Secret.
     *
     * <p>Abgeleitete Variablen (Port, Origin, Scope, Issuer, Security-Mode) ergeben
     * sich aus den Service-Einstellungen und werden dort gepflegt. Name und Wert
     * sind deshalb gesperrt -- sonst stuenden in der Oberflaeche zwei Wahrheiten.
     * Abschalten bleibt moeglich: dann wird die Variable beim Start weggelassen.
     */
    private final DefaultTableModel variables = new DefaultTableModel(
            new Object[]{"Enabled", "Name", "Value", "Secret"}, 0) {
        @Override
        public Class<?> getColumnClass(int column) {
            return column == COLUMN_ENABLED || column == COLUMN_SECRET
                    ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            if (column == COLUMN_ENABLED) {
                return true;
            }
            return !isDerivedRow(row);
        }
    };
    static final int COLUMN_ENABLED = 0;
    static final int COLUMN_NAME = 1;
    static final int COLUMN_VALUE = 2;
    static final int COLUMN_SECRET = 3;
    private final JTable variableTable = new JTable(variables);
    /** Liefert die oeffentliche Adresse, aus der sich der Ordnername ergibt. */
    private java.util.function.Supplier<String> serviceName = () -> "";
    private final JSpinner initialDelay = seconds(1);
    private final JSpinner multiplier = new JSpinner(new SpinnerNumberModel(2.0, 1.0, 100.0, 0.1));
    private final JSpinner maxDelay = seconds(300);
    private final JSpinner resetAfter = seconds(300);
    private final JSpinner maxAttempts = seconds(0);
    // Minimum 1: die Domain-Validierung (ManagedApplication/ReadinessPolicy)
    // lehnt 0 ab — der Spinner darf den Wert gar nicht erst anbieten.
    private final JSpinner stopTimeout = atLeastOne(10);
    private final JSpinner startupTimeout = atLeastOne(30);
    private final JSpinner readinessPoll = atLeastOne(200);
    private final JSpinner connectionTimeout = atLeastOne(500);
    private final JTextField healthPath = new JTextField();
    private final JSpinner healthStatus = new JSpinner(new SpinnerNumberModel(200, 100, 599, 1));
    private final ManagedApplication original;
    private final List<JComponent> configuredSections = new ArrayList<>();
    private final List<Consumer<Boolean>> configuredListeners = new ArrayList<>();

    ManagedApplicationPanel(Optional<ManagedApplication> original) {
        this.original = original.orElse(null);
        javaExecutable.setEditable(true);
        javaExecutable.setModel(new DefaultComboBoxModel<>(
                new String[]{ManagedApplication.defaultJavaExecutable()}));
        jarPath.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                fireConfiguredChanged();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                fireConfiguredChanged();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                fireConfiguredChanged();
            }
        });
        loadOriginal();
    }

    /** Eine Anwendung gilt als konfiguriert, sobald eine JAR-Datei angegeben ist. */
    boolean applicationConfigured() {
        return !jarPath.getText().isBlank();
    }

    /** Wird bei jeder Änderung der JAR-Angabe (und einmal initial) aufgerufen. */
    void onConfiguredChanged(Consumer<Boolean> listener) {
        configuredListeners.add(listener);
        listener.accept(applicationConfigured());
    }

    /** Nach dem Aufbau aller Tabs aufrufen, damit der Initialzustand stimmt. */
    void refreshConfiguredState() {
        fireConfiguredChanged();
    }

    private void fireConfiguredChanged() {
        boolean configured = applicationConfigured();
        for (JComponent section : configuredSections) {
            setEnabledRecursively(section, configured);
        }
        for (Consumer<Boolean> listener : configuredListeners) {
            listener.accept(configured);
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

    /** Untertab "Runtime": JAR, Java-Runtime, Arbeitsverzeichnis, Argumente, Lifecycle. */
    JComponent runtimeTab() {
        JPanel fields = new JPanel(new GridBagLayout());
        GridBagConstraints f = new GridBagConstraints();
        f.insets = new Insets(4, 0, 4, 8);
        f.anchor = GridBagConstraints.NORTHWEST;
        int row = 0;

        jarPath.setToolTipText("Drop a .jar file here, paste its path, or use Browse");
        jarPath.setTransferHandler(new JarDropHandler());
        JButton clearJar = iconButton("✕", "Remove JAR file");
        clearJar.addActionListener(e -> jarPath.setText(""));
        jarPath.getDocument().addDocumentListener(new DocumentListener() {
            private void update() {
                clearJar.setEnabled(!jarPath.getText().isBlank());
            }

            @Override
            public void insertUpdate(DocumentEvent e) {
                update();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                update();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                update();
            }
        });
        clearJar.setEnabled(false);
        JButton browseJar = new JButton("Browse…");
        browseJar.addActionListener(e -> chooseFile(jarPath, JFileChooser.FILES_ONLY));
        addLabeledField(fields, f, row++, "JAR file", withHint(
                combined(jarPath, clearJar, browseJar),
                "Blank: this service stays a plain proxy without a managed application"));

        JButton detectJava = iconButton("⟳", "Detect installed Java runtimes");
        detectJava.addActionListener(e -> detectJavaRuntimes());
        JButton browseJava = new JButton("Browse…");
        browseJava.addActionListener(e -> {
            JTextField proxy = new JTextField(currentJavaExecutable());
            chooseFile(proxy, JFileChooser.FILES_ONLY);
            if (!proxy.getText().isBlank()) {
                selectJavaExecutable(proxy.getText());
            }
        });
        addLabeledField(fields, f, row++, "Java executable",
                combined(javaExecutable, detectJava, browseJava));

        workingDirectory.setToolTipText(
                "Leave blank to use a data directory managed by AresStack");
        JButton browseWorkingDirectory = new JButton("Browse…");
        browseWorkingDirectory.addActionListener(e ->
                chooseFile(workingDirectory, JFileChooser.DIRECTORIES_ONLY));
        addLabeledField(fields, f, row++, "Working directory", withHint(
                combined(workingDirectory, browseWorkingDirectory),
                "Blank: AresStack uses services\\<id>\\data inside its own directory"));

        addLabeledField(fields, f, row++, "JVM arguments", withHint(argumentEditor(jvmArgs),
                "One argument per line, e.g. -Xmx512m"));
        addLabeledField(fields, f, row++, "Application arguments", withHint(argumentEditor(appArgs),
                "One argument per line"));

        applicationEnabled.setToolTipText(
                "Off: keep this configuration but do not start or monitor the process");
        JPanel lifecycle = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        lifecycle.add(applicationEnabled);
        lifecycle.add(startAutomatically);
        lifecycle.add(restartAutomatically);
        addLabeledField(fields, f, row, "Lifecycle", lifecycle);

        JPanel form = new JPanel(new BorderLayout());
        form.add(fields, BorderLayout.NORTH);
        configuredSections.add(lifecycle);
        return pad(form);
    }

    /** Der Editor reicht die aktuelle Adresse durch, bevor gespeichert wird. */
    void serviceName(java.util.function.Supplier<String> name) {
        this.serviceName = name == null ? () -> "" : name;
    }

    /** Untertab "Environment": Tabelle mit Umgebungsvariablen. */
    JComponent environmentTab() {
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        variableTable.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        variableTable.setFillsViewportHeight(true);
        variableTable.setRowHeight(variableTable.getRowHeight() + 4);
        variableTable.getColumnModel().getColumn(COLUMN_ENABLED).setMaxWidth(70);
        variableTable.getColumnModel().getColumn(COLUMN_SECRET).setMaxWidth(70);
        variableTable.getColumnModel().getColumn(COLUMN_NAME)
                .setCellRenderer(new DerivedAwareRenderer());
        variableTable.getColumnModel().getColumn(COLUMN_VALUE)
                .setCellRenderer(new DerivedAwareRenderer() {
                    @Override
                    public Component getTableCellRendererComponent(JTable target, Object value,
                            boolean selected, boolean focused, int row, int column) {
                        super.getTableCellRendererComponent(target, value, selected, focused,
                                row, column);
                        int modelRow = target.convertRowIndexToModel(row);
                        if (Boolean.TRUE.equals(
                                target.getModel().getValueAt(modelRow, COLUMN_SECRET))) {
                            setText("••••••••");
                        }
                        return this;
                    }
                });
        variableTable.getColumnModel().getColumn(COLUMN_VALUE)
                .setCellEditor(new SecretCellEditor());
        panel.add(new JScrollPane(variableTable), BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JButton addVariable = new JButton("+ Add variable");
        addVariable.addActionListener(e ->
                variables.addRow(new Object[]{Boolean.TRUE, "", "", false}));
        JButton removeVariable = new JButton("Remove selected");
        removeVariable.addActionListener(e -> {
            int selected = variableTable.getSelectedRow();
            if (selected < 0) {
                return;
            }
            int modelRow = variableTable.convertRowIndexToModel(selected);
            if (isDerivedRow(modelRow)) {
                // Loeschen wuerde nichts bringen: der Eintrag kommt aus den
                // Service-Einstellungen und waere beim naechsten Anwenden wieder da.
                javax.swing.JOptionPane.showMessageDialog(variableTable,
                        String.valueOf(variables.getValueAt(modelRow, COLUMN_NAME))
                                + " ergibt sich aus den Service-Einstellungen und kann"
                                + " nicht entfernt werden. Ueber Enabled laesst"
                                + " sich die Variable abschalten; dann wird sie"
                                + " beim Start weggelassen.",
                        "Abgeleitete Variable", javax.swing.JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            variables.removeRow(modelRow);
        });
        actions.add(addVariable);
        actions.add(removeVariable);
        JLabel hint = hintLabel("Grey rows are derived from the service settings and cannot"
                + " be edited or removed - switch them off with Enabled instead."
                + " Secret values are stored encrypted and shown masked.");
        JPanel south = new JPanel(new BorderLayout());
        south.add(actions, BorderLayout.WEST);
        south.add(hint, BorderLayout.SOUTH);
        panel.add(south, BorderLayout.SOUTH);
        configuredSections.add(panel);
        return pad(panel);
    }

    /** Untertab "Restart &amp; health": Restart-Policy und Readiness-Checks. */
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
        configuredSections.add(panel);
        return pad(panel);
    }

    private void detectJavaRuntimes() {
        Set<String> entries = new LinkedHashSet<>();
        String current = currentJavaExecutable();
        if (!current.isBlank()) {
            entries.add(current);
        }
        entries.addAll(JavaRuntimeDetector.detect());
        javaExecutable.setModel(new DefaultComboBoxModel<>(entries.toArray(new String[0])));
        if (!current.isBlank()) {
            javaExecutable.setSelectedItem(current);
        }
        javaExecutable.showPopup();
    }

    private String currentJavaExecutable() {
        Object editorItem = javaExecutable.getEditor().getItem();
        return editorItem == null ? "" : editorItem.toString().trim();
    }

    private void selectJavaExecutable(String path) {
        DefaultComboBoxModel<String> model = (DefaultComboBoxModel<String>) javaExecutable.getModel();
        if (model.getIndexOf(path) < 0) {
            model.insertElementAt(path, 0);
        }
        javaExecutable.setSelectedItem(path);
    }

    private static JButton iconButton(String glyph, String tooltip) {
        JButton button = new JButton(glyph);
        button.setToolTipText(tooltip);
        button.setMargin(new Insets(0, 0, 0, 0));
        int height = new JTextField().getPreferredSize().height;
        button.setPreferredSize(new Dimension(height, height));
        return button;
    }

    /** Eingabefeld plus zugehörige Buttons als ein kombiniertes Kontrollelement. */
    private static JComponent combined(JComponent field, JComponent... buttons) {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = 0;
        c.gridx = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(field, c);
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        for (JComponent button : buttons) {
            c.gridx++;
            c.insets = new Insets(0, 4, 0, 0);
            panel.add(button, c);
        }
        return panel;
    }

    private static JComponent withHint(JComponent field, String hint) {
        JPanel panel = new JPanel(new BorderLayout(0, 2));
        panel.add(field, BorderLayout.CENTER);
        panel.add(hintLabel(hint), BorderLayout.SOUTH);
        return panel;
    }

    private static JScrollPane argumentEditor(JTextArea editor) {
        editor.setLineWrap(true);
        editor.setWrapStyleWord(false);

        JScrollPane scrollPane = new JScrollPane(editor,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        Dimension preferredSize = scrollPane.getPreferredSize();
        int viewportHeight = editor.getPreferredScrollableViewportSize().height;
        int borderHeight = scrollPane.getInsets().top + scrollPane.getInsets().bottom;
        int preferredHeight = Math.max(preferredSize.height, viewportHeight + borderHeight + 4);
        scrollPane.setPreferredSize(new Dimension(preferredSize.width, preferredHeight));
        scrollPane.setMinimumSize(new Dimension(0, preferredHeight));
        return scrollPane;
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
        String jar = jarPath.getText().trim();
        if (jar.isEmpty()) {
            return Optional.empty();
        }
        if (!jar.toLowerCase().endsWith(".jar")) {
            throw new IllegalArgumentException("Application must be a JAR file");
        }
        if (currentJavaExecutable().isBlank()) {
            throw new IllegalArgumentException("Choose a Java executable for the application");
        }
        String health = healthPath.getText().trim();
        if (!health.isEmpty() && !health.startsWith("/")) {
            throw new IllegalArgumentException(
                    "Health path must start with '/' (or stay empty for TCP checks)");
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
        String id = original != null ? original.id() : newApplicationId();
        int stop = ((Number) stopTimeout.getValue()).intValue();
        // Das Formular hat kein eigenes Feld für den Force-Stop-Timeout; ein
        // per Config abweichend gesetzter Wert darf beim Edit nicht stillschweigend
        // auf den normalen Stop-Timeout kollabieren.
        int forceStop = original != null
                && original.forceStopTimeoutSeconds() != original.stopTimeoutSeconds()
                ? original.forceStopTimeoutSeconds() : stop;
        return Optional.of(new ManagedApplication(id, jar, currentJavaExecutable(),
                workingDirectory.getText().trim(), lines(jvmArgs), lines(appArgs),
                collectVariables(), startAutomatically.isSelected(),
                restartAutomatically.isSelected(), policy,
                new ReadinessPolicy(((Number) startupTimeout.getValue()).intValue(),
                        ((Number) readinessPoll.getValue()).intValue(),
                        ((Number) connectionTimeout.getValue()).intValue(),
                        health,
                        ((Number) healthStatus.getValue()).intValue()),
                stop, forceStop, applicationEnabled.isSelected()));
    }

    private boolean tableEditing() {
        for (int row = 0; row < variables.getRowCount(); row++) {
            if (variables.getValueAt(row, COLUMN_NAME) == null
                    || variables.getValueAt(row, COLUMN_VALUE) == null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Zeigt die aus den Service-Einstellungen abgeleiteten Variablen an.
     *
     * <p>Bestehende Enabled-Entscheidungen bleiben erhalten; Namen, die fuer diese
     * Site nicht mehr gelten (etwa der Issuer nach dem Abschalten von Central
     * OAuth), verschwinden, statt mit einem veralteten Wert stehen zu bleiben.
     * Eigene Variablen fasst die Methode nicht an.
     */
    void showDerived(java.util.Map<String, String> derived) {
        java.util.Map<String, Boolean> enabledBefore = new LinkedHashMap<>();
        for (int row = variables.getRowCount() - 1; row >= 0; row--) {
            Object name = variables.getValueAt(row, COLUMN_NAME);
            String key = name == null ? "" : String.valueOf(name).trim();
            if (DerivedEnvironment.isDerived(key)) {
                enabledBefore.put(key,
                        !Boolean.FALSE.equals(variables.getValueAt(row, COLUMN_ENABLED)));
                variables.removeRow(row);
            }
        }
        int insertAt = 0;
        for (String name : DerivedEnvironment.NAMES) {
            String value = derived.get(name);
            if (value == null) {
                continue;
            }
            variables.insertRow(insertAt++, new Object[]{
                    enabledBefore.getOrDefault(name, Boolean.TRUE), name, value, Boolean.FALSE});
        }
    }

    /**
     * Die Kennung eines neuen Dienstes - und damit sein Ordner- und Log-Name.
     *
     * <p>Fruher eine UUID. Das Ergebnis waren Verzeichnisse wie
     * {@code services/6136f70c-2de6-4602-a9f5-e8dc1e794f39} und Logdateien gleichen
     * Namens: fuer einen Betreiber nicht zuzuordnen. Jetzt der erste Teil der
     * oeffentlichen Adresse, also {@code argos} fuer {@code argos.aresstack.de}.
     */
    private String newApplicationId() {
        String name = serviceName.get();
        String sanitised = name == null ? "" : name.trim().toLowerCase(java.util.Locale.ROOT)
                .split("\\.")[0].replaceAll("[^a-z0-9-]", "");
        return sanitised.isBlank() ? UUID.randomUUID().toString() : sanitised;
    }

    private void addVariableRow(String key, ManagedEnvironmentVariable variable) {
        variables.addRow(new Object[]{variable.enabled(), key,
                variable.secret() ? UNCHANGED_SECRET : variable.value(), variable.secret()});
    }

    /** Eine Zeile, deren Name aus den Service-Einstellungen stammt. */
    private boolean isDerivedRow(int modelRow) {
        if (modelRow < 0 || modelRow >= variables.getRowCount()) {
            return false;
        }
        Object name = variables.getValueAt(modelRow, COLUMN_NAME);
        return name != null && DerivedEnvironment.isDerived(String.valueOf(name).trim());
    }

    /** Stellt abgeleitete Zeilen gedaempft dar, damit "gesperrt" sichtbar ist. */
    private class DerivedAwareRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable target, Object value,
                boolean selected, boolean focused, int row, int column) {
            super.getTableCellRendererComponent(target, value, selected, focused, row, column);
            int modelRow = target.convertRowIndexToModel(row);
            boolean derived = isDerivedRow(modelRow);
            if (!selected) {
                setForeground(derived
                        ? javax.swing.UIManager.getColor("Label.disabledForeground")
                        : target.getForeground());
            }
            setToolTipText(derived
                    ? "Ergibt sich aus den Service-Einstellungen und wird dort gepflegt."
                    : null);
            return this;
        }
    }

    private Map<String, ManagedEnvironmentVariable> collectVariables() {
        Map<String, ManagedEnvironmentVariable> result = new LinkedHashMap<>();
        for (int row = 0; row < variables.getRowCount(); row++) {
            String key = String.valueOf(variables.getValueAt(row, COLUMN_NAME)).trim();
            String value = String.valueOf(variables.getValueAt(row, COLUMN_VALUE));
            boolean secret = Boolean.TRUE.equals(variables.getValueAt(row, COLUMN_SECRET));
            boolean enabled = !Boolean.FALSE.equals(variables.getValueAt(row, COLUMN_ENABLED));
            if (key.isBlank()) {
                if (value.isBlank()) {
                    continue;
                }
                throw new IllegalArgumentException("Environment variable name cannot be empty");
            }
            if (!key.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException("Invalid environment variable name '" + key
                        + "': use letters, digits and underscores, starting with a letter or '_'");
            }
            if (UNCHANGED_SECRET.equals(value)) {
                ManagedEnvironmentVariable old = original == null ? null : original.environment().get(key);
                boolean hadSecret = old != null && old.secret();
                if (secret) {
                    if (!hadSecret) {
                        throw new IllegalArgumentException("Enter the new secret value for " + key);
                    }
                    value = old.value();
                } else if (hadSecret) {
                    // Der Platzhalter darf nie als Klartextwert gespeichert werden.
                    throw new IllegalArgumentException("Enter the value for " + key
                            + " to store it without the secret marking");
                }
            }
            if (result.putIfAbsent(key,
                    new ManagedEnvironmentVariable(value, secret, enabled)) != null) {
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
        selectJavaExecutable(original.javaExecutable());
        workingDirectory.setText(original.workingDirectory());
        applicationEnabled.setSelected(original.isEnabled());
        startAutomatically.setSelected(original.startAutomatically());
        restartAutomatically.setSelected(original.restartAutomatically());
        jvmArgs.setText(String.join("\n", original.jvmArguments()));
        appArgs.setText(String.join("\n", original.applicationArguments()));
        // Abgeleitete Variablen zuerst, damit die gesperrten Zeilen oben stehen.
        original.environment().forEach((key, variable) -> {
            if (DerivedEnvironment.isDerived(key)) {
                addVariableRow(key, variable);
            }
        });
        original.environment().forEach((key, variable) -> {
            if (!DerivedEnvironment.isDerived(key)) {
                addVariableRow(key, variable);
            }
        });
        RestartPolicy policy = original.restartPolicy();
        // Die Policy speichert long; hand-editierte Configs mit Werten über
        // Integer.MAX_VALUE dürfen das Öffnen des Dialogs nicht crashen.
        initialDelay.setValue(clampToInt(policy.initialDelaySeconds()));
        multiplier.setValue(policy.multiplier());
        maxDelay.setValue(clampToInt(policy.maxDelaySeconds()));
        resetAfter.setValue(clampToInt(policy.resetAfterSeconds()));
        maxAttempts.setValue(policy.maximumAttempts());
        stopTimeout.setValue(original.stopTimeoutSeconds());
        startupTimeout.setValue(original.readinessPolicy().startupTimeoutSeconds());
        readinessPoll.setValue(original.readinessPolicy().pollIntervalMillis());
        connectionTimeout.setValue(original.readinessPolicy().connectionTimeoutMillis());
        healthPath.setText(original.readinessPolicy().httpHealthPath());
        healthStatus.setValue(original.readinessPolicy().expectedHttpStatus());
    }

    private static int clampToInt(long value) {
        return (int) Math.max(0, Math.min(value, Integer.MAX_VALUE));
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
        return spinner(defaultValue, 0);
    }

    private static JSpinner atLeastOne(int defaultValue) {
        return spinner(defaultValue, 1);
    }

    private static JSpinner spinner(int defaultValue, int minimum) {
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(defaultValue, minimum, null, 1));
        Dimension size = spinner.getPreferredSize();
        spinner.setPreferredSize(new Dimension(Math.max(size.width, 70), size.height));
        return spinner;
    }

    private void chooseFile(JTextField target, int mode) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(mode);
        if (!target.getText().isBlank()) {
            chooser.setSelectedFile(new File(target.getText()));
        }
        if (chooser.showOpenDialog(target) == JFileChooser.APPROVE_OPTION) {
            target.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private final class SecretCellEditor extends javax.swing.AbstractCellEditor
            implements javax.swing.table.TableCellEditor {
        private final JTextField ordinary = new JTextField();
        private final JPasswordField password = new JPasswordField();
        private JTextComponent active;
        private boolean editingUnchangedSecret;

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value,
                boolean selected, int row, int column) {
            int modelRow = table.convertRowIndexToModel(row);
            boolean secret = Boolean.TRUE.equals(table.getModel().getValueAt(modelRow, 2));
            active = secret ? password : ordinary;
            String text = String.valueOf(value == null ? "" : value);
            // Den Platzhalter nie ins maskierte Feld vorbefüllen: sonst würde
            // ein angehängter neuer Wert als "(unchanged)xyz" wörtlich gespeichert.
            editingUnchangedSecret = secret && UNCHANGED_SECRET.equals(text);
            active.setText(editingUnchangedSecret ? "" : text);
            return active;
        }

        @Override
        public Object getCellEditorValue() {
            if (active == password) {
                char[] chars = password.getPassword();
                try {
                    if (editingUnchangedSecret && chars.length == 0) {
                        return UNCHANGED_SECRET;
                    }
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
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }
}
