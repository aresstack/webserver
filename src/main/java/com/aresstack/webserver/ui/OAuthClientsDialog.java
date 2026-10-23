package com.aresstack.webserver.ui;

import com.aresstack.webserver.domain.OAuthClientConfiguration;
import com.aresstack.webserver.infrastructure.managed.WindowsDpapiSecrets;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * Manages the OAuth clients of the central authorization server.
 *
 * <p>Client id, secret and redirect URIs previously existed only in
 * {@code webserver.json} and had to be edited by hand — the GUI offered no way to
 * set them, which made the central OAuth service unusable without a text editor.
 *
 * <p>Secrets are write-only here: an existing secret is never displayed, only its
 * storage state. Leaving the field empty keeps the stored value. New values are
 * handed to the controller, which protects them with Windows DPAPI before they are
 * persisted.
 */
final class OAuthClientsDialog extends JDialog {

    private final WebServerController controller;
    private final List<OAuthClientConfiguration> clients;
    private final ClientTableModel model;
    private final JTextField issuerField;
    private final JTable table;

    private OAuthClientsDialog(Window owner, WebServerController controller) {
        super(owner, "OAuth Clients", ModalityType.APPLICATION_MODAL);
        this.controller = controller;
        this.clients = new ArrayList<>(controller.configuration().oauth().clients());
        this.model = new ClientTableModel();
        this.issuerField = new JTextField(controller.configuration().oauth().issuer(), 30);
        this.table = new JTable(model);

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);

        JPanel header = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 12, 4, 12);
        c.anchor = GridBagConstraints.WEST;
        Forms.addRow(header, c, 0, "Issuer (fallback)", issuerField);
        issuerField.setToolTipText("Used when a request carries no usable public host. "
                + "Protected sites issue tokens for their own host.");

        JButton add = new JButton("Add…");
        JButton edit = new JButton("Edit…");
        JButton remove = new JButton("Remove");
        add.addActionListener(event -> addClient());
        edit.addActionListener(event -> editSelected());
        remove.addActionListener(event -> removeSelected());

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        actions.add(add);
        actions.add(edit);
        actions.add(remove);

        JButton save = new JButton("Save");
        JButton cancel = new JButton("Cancel");
        save.addActionListener(event -> save());
        cancel.addActionListener(event -> dispose());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(cancel);
        buttons.add(save);

        JPanel centre = new JPanel(new BorderLayout());
        centre.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
        centre.add(new JScrollPane(table), BorderLayout.CENTER);
        centre.add(actions, BorderLayout.SOUTH);

        JPanel hint = new JPanel(new FlowLayout(FlowLayout.LEFT));
        hint.add(new JLabel("<html><small>Removing every client disables the OAuth service. "
                + "Secrets are stored protected for the current Windows user.</small></html>"));

        setLayout(new BorderLayout());
        add(header, BorderLayout.NORTH);
        add(centre, BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout());
        footer.add(hint, BorderLayout.NORTH);
        footer.add(buttons, BorderLayout.SOUTH);
        add(footer, BorderLayout.SOUTH);

        setSize(640, 340);
        setLocationRelativeTo(owner);
    }

    static void show(Window owner, WebServerController controller) {
        new OAuthClientsDialog(owner, controller).setVisible(true);
    }

    private void addClient() {
        ClientEditor.edit(this, null).ifPresent(created -> {
            if (indexOf(created.clientId()) >= 0) {
                JOptionPane.showMessageDialog(this,
                        "A client with id '" + created.clientId() + "' already exists.",
                        "Duplicate client", JOptionPane.WARNING_MESSAGE);
                return;
            }
            clients.add(created);
            model.fireTableDataChanged();
        });
    }

    private void editSelected() {
        int selected = table.getSelectedRow();
        if (selected < 0) {
            return;
        }
        OAuthClientConfiguration existing = clients.get(selected);
        ClientEditor.edit(this, existing).ifPresent(updated -> {
            clients.set(selected, updated);
            model.fireTableDataChanged();
        });
    }

    private void removeSelected() {
        int selected = table.getSelectedRow();
        if (selected < 0) {
            return;
        }
        String clientId = clients.get(selected).clientId();
        int choice = JOptionPane.showConfirmDialog(this,
                "Remove OAuth client '" + clientId + "'? Applications using it can no longer "
                        + "obtain tokens.",
                "Remove client", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice == JOptionPane.OK_OPTION) {
            clients.remove(selected);
            model.fireTableDataChanged();
        }
    }

    private void save() {
        try {
            controller.updateOAuthClients(issuerField.getText(), List.copyOf(clients));
            dispose();
        } catch (RuntimeException failure) {
            FriendlyErrors.show(this, "Could not save the OAuth clients", failure);
        }
    }

    private int indexOf(String clientId) {
        for (int index = 0; index < clients.size(); index++) {
            if (clients.get(index).clientId().equals(clientId)) {
                return index;
            }
        }
        return -1;
    }

    /** Secret column shows storage state only — never the value. */
    private final class ClientTableModel extends AbstractTableModel {

        private final String[] columns = {"Client ID", "Redirect URIs", "Secret"};

        @Override
        public int getRowCount() {
            return clients.size();
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
        public Object getValueAt(int row, int column) {
            OAuthClientConfiguration client = clients.get(row);
            return switch (column) {
                case 0 -> client.clientId();
                case 1 -> String.join(" ", client.redirectUris());
                default -> WindowsDpapiSecrets.isProtected(client.clientSecret())
                        ? "set (protected)" : "set (protected on save)";
            };
        }
    }

    /** Add/edit form for a single client. */
    private static final class ClientEditor extends JDialog {

        private final JTextField clientId = new JTextField(20);
        private final JTextField redirectUris = new JTextField(30);
        private final JPasswordField secret = new JPasswordField(24);
        private final OAuthClientConfiguration existing;
        private OAuthClientConfiguration result;

        private ClientEditor(Window owner, OAuthClientConfiguration existing) {
            super(owner, existing == null ? "Add OAuth Client" : "Edit OAuth Client",
                    ModalityType.APPLICATION_MODAL);
            this.existing = existing;
            if (existing != null) {
                clientId.setText(existing.clientId());
                redirectUris.setText(String.join(" ", existing.redirectUris()));
                secret.setToolTipText("Leave empty to keep the stored secret.");
            }

            JButton generate = new JButton("Generate");
            generate.addActionListener(event -> secret.setText(generateSecret()));

            JPanel form = new JPanel(new GridBagLayout());
            GridBagConstraints c = new GridBagConstraints();
            c.insets = new Insets(4, 12, 4, 12);
            c.anchor = GridBagConstraints.WEST;
            int row = 0;
            Forms.addRow(form, c, row++, "Client ID", clientId);
            Forms.addRow(form, c, row++, "Redirect URIs (space separated)", redirectUris);
            JPanel secretRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            secretRow.add(secret);
            secretRow.add(generate);
            Forms.addRow(form, c, row++, existing == null ? "Client secret"
                    : "New client secret (optional)", secretRow);
            redirectUris.setToolTipText("https URLs. An entry without a path matches any path "
                    + "on that host, e.g. https://chatgpt.com");

            JButton ok = new JButton("OK");
            JButton cancel = new JButton("Cancel");
            ok.addActionListener(event -> accept());
            cancel.addActionListener(event -> dispose());
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            buttons.add(cancel);
            buttons.add(ok);

            setLayout(new BorderLayout());
            add(form, BorderLayout.CENTER);
            add(buttons, BorderLayout.SOUTH);
            pack();
            setLocationRelativeTo(owner);
        }

        static java.util.Optional<OAuthClientConfiguration> edit(
                Window owner, OAuthClientConfiguration existing) {
            ClientEditor editor = new ClientEditor(owner, existing);
            editor.setVisible(true);
            return java.util.Optional.ofNullable(editor.result);
        }

        private void accept() {
            char[] entered = secret.getPassword();
            try {
                String secretValue = entered.length > 0 ? new String(entered)
                        : existing == null ? "" : existing.clientSecret();
                List<String> uris = Arrays.stream(redirectUris.getText().trim().split("[,\\s]+"))
                        .filter(value -> !value.isBlank())
                        .toList();
                // The record validates id, secret and URIs; surface its message as-is.
                result = new OAuthClientConfiguration(clientId.getText().trim(), secretValue, uris);
                dispose();
            } catch (RuntimeException invalid) {
                result = null;
                JOptionPane.showMessageDialog(this, invalid.getMessage(),
                        "Invalid client", JOptionPane.WARNING_MESSAGE);
            } finally {
                Arrays.fill(entered, '\0');
            }
        }

        private static String generateSecret() {
            byte[] raw = new byte[32];
            new SecureRandom().nextBytes(raw);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        }
    }
}
