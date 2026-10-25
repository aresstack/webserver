package com.aresstack.webserver.ui;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JComboBox;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;

/**
 * Globale Servereinstellungen (§13): ACME-Mail und Autostart-Optionen;
 * die Ports liegen im Advanced-Bereich, weil sie normalerweise nicht
 * verändert werden müssen.
 */
class SettingsDialog extends JDialog {

    private SettingsDialog(Window owner, WebServerController controller) {
        super(owner, "Server Settings", ModalityType.APPLICATION_MODAL);

        JTextField emailField = new JTextField(controller.configuration().acme().email(), 24);
        JCheckBox autostartServer = new JCheckBox(
                "Start automatically with application", AppPreferences.autostartServer());
        boolean windowsSupported = WindowsAutostart.isSupported(controller.directories().root());
        JCheckBox startWithWindows = new JCheckBox("Start with Windows",
                windowsSupported && WindowsAutostart.isEnabled());
        startWithWindows.setEnabled(windowsSupported);
        if (!windowsSupported) {
            startWithWindows.setToolTipText(
                    "Available in the installed application (bin\\webserver.bat).");
        }
        // Editierbar: die Liste sind nur Vorschlaege. Aufgenommen ist, wer unter einem
        // kostenlosen Namen beliebige Unterdomains zulaesst - ohne das bekommt ein
        // Dienst pro Subdomain kein Zertifikat.
        JComboBox<String> baseDomain = new JComboBox<>(new String[]{
                "dedyn.io", "dynu.net", "mooo.com", "example.org"});
        baseDomain.setEditable(true);
        baseDomain.setSelectedItem(AppPreferences.baseDomain());
        ((JTextField) baseDomain.getEditor().getEditorComponent()).setColumns(20);

        JTextField httpPortField = new JTextField(
                String.valueOf(controller.configuration().httpPort()), 6);
        JTextField httpsPortField = new JTextField(
                String.valueOf(controller.configuration().httpsPort()), 6);

        // Eine einzige Spalte fuer alle Abschnitte. Vorher war jeder Abschnitt ein
        // eigenes Panel mit eigenem GridBag - die Beschriftungen standen damit auf
        // unterschiedlichen Einrueckungen und wurden vom BoxLayout zentriert.
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(14, 16, 8, 16));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 0, 4, 12);
        c.anchor = GridBagConstraints.WEST;
        int row = 0;

        JCheckBox oauthForNewServices = new JCheckBox("Use Central OAuth",
                AppPreferences.oauthForNewServices());
        oauthForNewServices.setToolTipText(
                "A new service starts with Central OAuth selected instead of none");
        // hgap 0, siehe oben: sonst beginnt diese eine Zeile weiter rechts als alle
        // anderen Felder.
        JPanel domainRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        domainRow.add(baseDomain);
        domainRow.add(javax.swing.Box.createHorizontalStrut(10));
        domainRow.add(oauthForNewServices);
        Forms.addRow(form, c, row++, "Domain for new services", domainRow);
        row = addNote(form, c, row, "A new service is published as"
                + " &lt;name&gt;.&lt;domain&gt;. The subdomain must resolve to this"
                + " machine before a certificate can be issued - with a dynamic IP"
                + " that means a CNAME to your DynDNS name, or a wildcard CNAME once.");

        Forms.addRow(form, c, row++, "Let's Encrypt email (optional)", emailField);
        Forms.addRow(form, c, row++, "Certificate management", new JLabel("Automatic"));
        c.gridx = 1;
        c.gridy = row++;
        form.add(autostartServer, c);
        c.gridx = 1;
        c.gridy = row++;
        form.add(startWithWindows, c);

        JButton manageClients = new JButton("OAuth clients…");
        manageClients.addActionListener(e ->
                OAuthClientsDialog.show(SettingsDialog.this, controller));
        Forms.addRow(form, c, row++, "Central OAuth", manageClients);
        row = addNote(form, c, row, "Client id, secret and redirect URIs of the"
                + " applications allowed to obtain tokens. The scopes a site grants are"
                + " set per publication under Authentication.");

        // Aufklappbar und zu: Ports aendert man einmal, wenn ueberhaupt.
        JButton advancedToggle = new JButton("▶  Advanced");
        advancedToggle.setBorderPainted(false);
        advancedToggle.setContentAreaFilled(false);
        advancedToggle.setFocusPainted(false);
        advancedToggle.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
        advancedToggle.setCursor(java.awt.Cursor.getPredefinedCursor(
                java.awt.Cursor.HAND_CURSOR));
        c.gridx = 0;
        c.gridy = row++;
        c.gridwidth = 2;
        form.add(advancedToggle, c);
        c.gridwidth = 1;

        // Die Portzeilen liegen im selben Raster, damit sie beim Aufklappen auf
        // derselben Einrueckung stehen wie alles darueber.
        java.util.List<java.awt.Component> advancedRows = new java.util.ArrayList<>();
        addRowTracked(form, c, row++, "HTTP port", httpPortField, advancedRows);
        addRowTracked(form, c, row++, "HTTPS port", httpsPortField, advancedRows);
        JLabel portNote = note("Changing ports usually also requires changing the router"
                + " port forwarding. Let's Encrypt requires ports 80 and 443 to be"
                + " reachable from the internet.");
        c.gridx = 1;
        c.gridy = row++;
        form.add(portNote, c);
        advancedRows.add(portNote);
        advancedRows.forEach(component -> component.setVisible(false));
        advancedToggle.addActionListener(e -> {
            boolean open = !httpPortField.isVisible();
            advancedRows.forEach(component -> component.setVisible(open));
            advancedToggle.setText((open ? "▼" : "▶") + "  Advanced");
            pack();
        });


        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        JButton save = new JButton("Save");
        save.addActionListener(e -> {
            try {
                String email = emailField.getText().trim();
                int httpPort = Integer.parseInt(httpPortField.getText().trim());
                int httpsPort = Integer.parseInt(httpsPortField.getText().trim());
                boolean autostart = autostartServer.isSelected();
                boolean windowsStart = startWithWindows.isSelected();
                String newBaseDomain = baseDomain.getSelectedItem() == null
                        ? "" : String.valueOf(baseDomain.getSelectedItem()).trim();
                AppPreferences.setBaseDomain(newBaseDomain);
                AppPreferences.setOauthForNewServices(oauthForNewServices.isSelected());
                save.setEnabled(false);
                new javax.swing.SwingWorker<Void, Void>() {
                    @Override
                    protected Void doInBackground() {
                        if (!email.equals(controller.configuration().acme().email())) {
                            controller.updateAcmeEmail(email);
                        }
                        if (httpPort != controller.configuration().httpPort()
                                || httpsPort != controller.configuration().httpsPort()) {
                            controller.updatePorts(httpPort, httpsPort);
                        }
                        return null;
                    }

                    @Override
                    protected void done() {
                        save.setEnabled(true);
                        try {
                            get();
                            AppPreferences.setAutostartServer(autostart);
                            if (windowsSupported) {
                                if (windowsStart) {
                                    WindowsAutostart.enable(controller.directories().root());
                                } else {
                                    WindowsAutostart.disable();
                                }
                            }
                            dispose();
                        } catch (Exception ex) {
                            FriendlyErrors.show(SettingsDialog.this, "Server Settings",
                                    ex.getCause() != null ? ex.getCause() : ex);
                        }
                    }
                }.execute();
            } catch (NumberFormatException ex) {
                FriendlyErrors.show(this, "Server Settings",
                        new IllegalArgumentException("Ports must be numbers between 1 and 65535."));
            }
        });
        JPanel buttons = new JPanel();
        buttons.add(cancel);
        buttons.add(save);

        JPanel content = new JPanel(new BorderLayout());
        content.add(form, BorderLayout.NORTH);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().setDefaultButton(save);
        pack();
        setLocationRelativeTo(owner);
    }

    /** Ein grauer Hinweis unter der Eingabe, in der Feldspalte. */
    private static int addNote(JPanel form, GridBagConstraints c, int row, String text) {
        c.gridx = 1;
        c.gridy = row;
        form.add(note(text), c);
        return row + 1;
    }

    private static JLabel note(String text) {
        JLabel label = new JLabel("<html><body style='width:380px'><small>" + text
                + "</small></body></html>");
        label.setForeground(new java.awt.Color(0x666666));
        return label;
    }

    /**
     * Wie {@link Forms#addRow}, merkt sich aber Beschriftung und Feld.
     *
     * <p>Beide kommen in dieselbe Liste: wird die Zeile ausgeblendet, muss die
     * Beschriftung mitgehen, sonst steht sie ohne ihr Feld im Formular.
     */
    private static void addRowTracked(JPanel form, GridBagConstraints c, int row,
                                      String label, java.awt.Component field,
                                      java.util.List<java.awt.Component> tracked) {
        JLabel caption = new JLabel(label);
        c.gridx = 0;
        c.gridy = row;
        form.add(caption, c);
        c.gridx = 1;
        form.add(field, c);
        tracked.add(caption);
        tracked.add(field);
    }

    static void show(Window owner, WebServerController controller) {
        new SettingsDialog(owner, controller).setVisible(true);
    }
}
