package com.aresstack.webserver.ui.publication;

import com.aresstack.webserver.domain.ScopeDecision;
import com.aresstack.webserver.domain.ScopeEntry;
import com.aresstack.webserver.domain.ScopePolicy;

import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Allow/Deny management for a service's permissions.
 *
 * <p>Reported scopes appear here on their own; manual ones can be added without any
 * registration, because automatic discovery is a convenience and never a
 * precondition. The service-wide rule defaults to {@code Allow all} and each scope
 * may override it.
 */
final class ScopePolicyDialog extends JDialog {

    private final List<ScopeEntry> entries;
    private final JComboBox<String> defaultRule =
            new JComboBox<>(new String[]{"Allow all", "Deny all"});
    private final ScopeTableModel model;
    private final JTable table;
    private ScopePolicy result;

    private ScopePolicyDialog(Window owner, ScopePolicy policy) {
        super(owner, "Permissions", ModalityType.APPLICATION_MODAL);
        this.entries = new ArrayList<>(policy.entries());
        this.model = new ScopeTableModel();
        this.table = new JTable(model);
        defaultRule.setSelectedItem(policy.allowByDefault() ? "Allow all" : "Deny all");

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        JComboBox<String> decisions =
                new JComboBox<>(new String[]{"Default", "Allow", "Deny"});
        table.getColumnModel().getColumn(3).setCellEditor(new DefaultCellEditor(decisions));
        defaultRule.addActionListener(event -> model.fireTableDataChanged());

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        top.add(new JLabel("Default for this service"));
        top.add(defaultRule);

        JButton add = new JButton("Add scope…");
        JButton remove = new JButton("Remove");
        add.addActionListener(event -> addScope());
        remove.addActionListener(event -> removeSelected());
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        actions.add(add);
        actions.add(remove);

        JButton ok = new JButton("OK");
        JButton cancel = new JButton("Cancel");
        ok.addActionListener(event -> accept());
        cancel.addActionListener(event -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(cancel);
        buttons.add(ok);

        JPanel centre = new JPanel(new BorderLayout());
        centre.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        centre.add(new JScrollPane(table), BorderLayout.CENTER);
        centre.add(actions, BorderLayout.SOUTH);

        JPanel footer = new JPanel(new BorderLayout());
        JPanel hint = new JPanel(new FlowLayout(FlowLayout.LEFT));
        hint.add(new JLabel("<html><small>Services may report their scopes; scopes added here"
                + " work without that.<br>A scope that is not listed is never granted, even if a"
                + " client asks for it.<br>The service itself must enforce the permission — the"
                + " token only carries it.</small></html>"));
        footer.add(hint, BorderLayout.NORTH);
        footer.add(buttons, BorderLayout.SOUTH);

        setLayout(new BorderLayout());
        add(top, BorderLayout.NORTH);
        add(centre, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);
        setSize(620, 320);
        setLocationRelativeTo(owner);
    }

    static Optional<ScopePolicy> edit(Window owner, ScopePolicy policy) {
        ScopePolicyDialog dialog = new ScopePolicyDialog(owner, policy);
        dialog.setVisible(true);
        return Optional.ofNullable(dialog.result);
    }

    private void addScope() {
        String name = JOptionPane.showInputDialog(this,
                "Scope name, e.g. mcp:read", "Add scope", JOptionPane.PLAIN_MESSAGE);
        if (name == null || name.isBlank()) {
            return;
        }
        try {
            ScopeEntry entry = ScopeEntry.manual(name.trim());
            if (entries.stream().anyMatch(known -> known.name().equals(entry.name()))) {
                JOptionPane.showMessageDialog(this, "That scope is already listed.",
                        "Add scope", JOptionPane.WARNING_MESSAGE);
                return;
            }
            entries.add(entry);
            model.fireTableDataChanged();
        } catch (RuntimeException invalid) {
            JOptionPane.showMessageDialog(this, invalid.getMessage(),
                    "Add scope", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void removeSelected() {
        int selected = table.getSelectedRow();
        if (selected < 0) {
            return;
        }
        ScopeEntry entry = entries.get(selected);
        String question = entry.registered()
                ? "Remove '" + entry.name() + "'? The service reported it, so it will reappear "
                        + "the next time it registers."
                : "Remove '" + entry.name() + "'?";
        if (JOptionPane.showConfirmDialog(this, question, "Remove scope",
                JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
            entries.remove(selected);
            model.fireTableDataChanged();
        }
    }

    private void accept() {
        result = new ScopePolicy("Allow all".equals(defaultRule.getSelectedItem()),
                List.copyOf(entries));
        dispose();
    }

    private boolean allowByDefault() {
        return "Allow all".equals(defaultRule.getSelectedItem());
    }

    /** Effective column recomputes from the rule, so the result is visible while editing. */
    private final class ScopeTableModel extends AbstractTableModel {

        private final String[] columns = {"Scope", "Description", "Source", "Decision", "Effective"};

        @Override
        public int getRowCount() {
            return entries.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return column == 3;
        }

        @Override
        public Object getValueAt(int row, int column) {
            ScopeEntry entry = entries.get(row);
            return switch (column) {
                case 0 -> entry.name();
                case 1 -> entry.description();
                case 2 -> entry.registered() ? "reported" : "manual";
                case 3 -> switch (entry.decision()) {
                    case ALLOW -> "Allow";
                    case DENY -> "Deny";
                    case DEFAULT -> "Default";
                };
                default -> entry.allowed(allowByDefault()) ? "granted" : "blocked";
            };
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            if (column != 3) {
                return;
            }
            ScopeDecision decision = switch (String.valueOf(value)) {
                case "Allow" -> ScopeDecision.ALLOW;
                case "Deny" -> ScopeDecision.DENY;
                default -> ScopeDecision.DEFAULT;
            };
            entries.set(row, entries.get(row).withDecision(decision));
            fireTableRowsUpdated(row, row);
        }
    }
}
