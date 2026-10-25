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

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 12, 4, 12);
        c.anchor = GridBagConstraints.WEST;
        int row = 0;
        Forms.addRow(form, c, row++, "Domain for new services", baseDomain);
        c.gridx = 1;
        c.gridy = row++;
        form.add(new JLabel("<html><small>A new service is published as"
                + " &lt;name&gt;.&lt;domain&gt;. The subdomain must resolve to this"
                + " machine before a certificate can be issued - with a dynamic IP"
                + " that means a CNAME to your DynDNS name, or a wildcard CNAME"
                + " once.</small></html>"), c);
        Forms.addRow(form, c, row++, "Let's Encrypt email (optional)", emailField);
        Forms.addRow(form, c, row++, "Certificate management", new JLabel("Automatic"));
        c.gridx = 1;
        c.gridy = row++;
        form.add(autostartServer, c);
        c.gridx = 1;
        c.gridy = row++;
        form.add(startWithWindows, c);

        // DynDNS: standardmäßig aus — z.B. wenn der Router das Update
        // bereits übernimmt. Platzhalter wie im Router-Prinzip.
        JCheckBox dynDnsEnabled = new JCheckBox(
                "Manage Dynamic DNS with AresStack Webserver", AppPreferences.dynDnsEnabled());
        JTextField dynDnsUrl = new JTextField(AppPreferences.dynDnsUpdateUrl(), 28);
        // Mehrzeilig: wer pro Dienst einen eigenen A-Record fuehrt, muss auch jeden
        // einzeln aktualisieren. Mit einem CNAME auf den DynDNS-Namen genuegt eine
        // Zeile - dann folgt die Subdomain von selbst.
        JTextField dynDnsUser = new JTextField(AppPreferences.dynDnsUsername(), 20);
        javax.swing.JTextArea dynDnsDomain = new javax.swing.JTextArea(
                AppPreferences.dynDnsDomain().replace(",", "\n"), 3, 20);
        dynDnsDomain.setLineWrap(false);
        javax.swing.JScrollPane dynDnsDomainScroll = new javax.swing.JScrollPane(dynDnsDomain);
        dynDnsDomainScroll.setPreferredSize(new java.awt.Dimension(
                dynDnsUser.getPreferredSize().width,
                dynDnsUser.getPreferredSize().height * 3));
        javax.swing.JPasswordField dynDnsPassword =
                new javax.swing.JPasswordField(AppPreferences.dynDnsPassword(), 20);
        JLabel dynDnsStatus = new JLabel("Last update: " + DynDnsClient.lastUpdateInfo());
        JButton dynDnsTest = new JButton("Test configuration");

        JPanel dynDns = new JPanel(new GridBagLayout());
        dynDns.setBorder(BorderFactory.createTitledBorder("Dynamic DNS"));
        GridBagConstraints d = new GridBagConstraints();
        d.insets = new Insets(4, 12, 4, 12);
        d.anchor = GridBagConstraints.WEST;
        d.gridx = 0;
        d.gridy = 0;
        d.gridwidth = 2;
        dynDns.add(dynDnsEnabled, d);
        d.gridwidth = 1;
        Forms.addRow(dynDns, d, 1, "Update URL", dynDnsUrl);
        Forms.addRow(dynDns, d, 2, "Domains (one per line)", dynDnsDomainScroll);
        Forms.addRow(dynDns, d, 3, "Username", dynDnsUser);
        Forms.addRow(dynDns, d, 4, "Password", dynDnsPassword);
        d.gridx = 0;
        d.gridy = 5;
        d.gridwidth = 2;
        dynDns.add(new JLabel("<html><small>Placeholders: &lt;domain&gt; &lt;username&gt;"
                + " &lt;password&gt; &lt;ipaddr&gt; &lt;ip6addr&gt;<br>"
                + "Each name is updated with its own request. A subdomain pointing to"
                + " your DynDNS name by CNAME needs no entry of its own."
                + "</small></html>"), d);
        d.gridy = 6;
        JPanel dynDnsActions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0));
        dynDnsActions.add(dynDnsTest);
        dynDnsActions.add(dynDnsStatus);
        dynDns.add(dynDnsActions, d);

        Runnable dynDnsFieldState = () -> {
            boolean on = dynDnsEnabled.isSelected();
            dynDnsUrl.setEnabled(on);
            dynDnsDomain.setEnabled(on);
            dynDnsUser.setEnabled(on);
            dynDnsPassword.setEnabled(on);
            dynDnsTest.setEnabled(on);
        };
        dynDnsFieldState.run();
        dynDnsEnabled.addActionListener(e -> dynDnsFieldState.run());
        dynDnsTest.addActionListener(e -> {
            // Aktuelle Feldwerte für den Test übernehmen.
            AppPreferences.setDynDnsUpdateUrl(dynDnsUrl.getText().trim());
            AppPreferences.setDynDnsDomain(dynDnsDomain.getText().trim());
            AppPreferences.setDynDnsUsername(dynDnsUser.getText().trim());
            AppPreferences.setDynDnsPassword(new String(dynDnsPassword.getPassword()));
            dynDnsTest.setEnabled(false);
            new javax.swing.SwingWorker<DynDnsClient.Result, Void>() {
                @Override
                protected DynDnsClient.Result doInBackground() {
                    return DynDnsClient.updateNow();
                }

                @Override
                protected void done() {
                    dynDnsTest.setEnabled(true);
                    try {
                        DynDnsClient.Result result = get();
                        dynDnsStatus.setText("Last update: " + DynDnsClient.lastUpdateInfo());
                        javax.swing.JOptionPane.showMessageDialog(SettingsDialog.this,
                                (result.success() ? "Update successful.\n" : "Update failed.\n")
                                        + result.message(),
                                "Dynamic DNS", result.success()
                                        ? javax.swing.JOptionPane.INFORMATION_MESSAGE
                                        : javax.swing.JOptionPane.WARNING_MESSAGE);
                    } catch (Exception ex) {
                        FriendlyErrors.show(SettingsDialog.this, "Dynamic DNS", ex);
                    }
                }
            }.execute();
        });

        // Central OAuth: clients were only editable in webserver.json before.
        JPanel authentication = new JPanel(new GridBagLayout());
        authentication.setBorder(BorderFactory.createTitledBorder("Authentication"));
        GridBagConstraints auth = new GridBagConstraints();
        auth.insets = new Insets(4, 12, 4, 12);
        auth.anchor = GridBagConstraints.WEST;
        JButton manageClients = new JButton("OAuth clients…");
        manageClients.addActionListener(e ->
                OAuthClientsDialog.show(SettingsDialog.this, controller));
        Forms.addRow(authentication, auth, 0, "Central OAuth", manageClients);
        auth.gridx = 0;
        auth.gridy = 1;
        auth.gridwidth = 2;
        authentication.add(new JLabel("<html><small>Client id, secret and redirect URIs of the"
                + " applications allowed to obtain tokens.<br>The scopes a site grants are set"
                + " per publication under Authentication.</small></html>"), auth);

        JPanel advanced = new JPanel(new GridBagLayout());
        advanced.setBorder(BorderFactory.createTitledBorder("Advanced"));
        GridBagConstraints a = new GridBagConstraints();
        a.insets = new Insets(4, 12, 4, 12);
        a.anchor = GridBagConstraints.WEST;
        Forms.addRow(advanced, a, 0, "HTTP port", httpPortField);
        Forms.addRow(advanced, a, 1, "HTTPS port", httpsPortField);
        a.gridx = 0;
        a.gridy = 2;
        a.gridwidth = 2;
        advanced.add(new JLabel("<html><small>Changing ports usually also requires changing"
                + " the router port forwarding.<br>Let's Encrypt requires ports 80 and 443"
                + " to be reachable from the internet.</small></html>"), a);

        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        JButton save = new JButton("Save");
        save.addActionListener(e -> {
            try {
                String email = emailField.getText().trim();
                int httpPort = Integer.parseInt(httpPortField.getText().trim());
                int httpsPort = Integer.parseInt(httpsPortField.getText().trim());
                boolean autostart = autostartServer.isSelected();
                boolean dynamicDns = dynDnsEnabled.isSelected();
                String updateUrl = dynDnsUrl.getText().trim();
                String domain = dynDnsDomain.getText().trim();
                String username = dynDnsUser.getText().trim();
                String password = new String(dynDnsPassword.getPassword());
                boolean windowsStart = startWithWindows.isSelected();
                String newBaseDomain = baseDomain.getSelectedItem() == null
                        ? "" : String.valueOf(baseDomain.getSelectedItem()).trim();
                AppPreferences.setBaseDomain(newBaseDomain);
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
                            AppPreferences.setDynDnsEnabled(dynamicDns);
                            AppPreferences.setDynDnsUpdateUrl(updateUrl);
                            AppPreferences.setDynDnsDomain(domain);
                            AppPreferences.setDynDnsUsername(username);
                            AppPreferences.setDynDnsPassword(password);
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

        JPanel middle = new JPanel();
        middle.setLayout(new javax.swing.BoxLayout(middle, javax.swing.BoxLayout.Y_AXIS));
        middle.add(dynDns);
        middle.add(authentication);
        middle.add(advanced);
        JPanel content = new JPanel(new BorderLayout());
        content.add(form, BorderLayout.NORTH);
        content.add(middle, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().setDefaultButton(save);
        pack();
        setLocationRelativeTo(owner);
    }

    static void show(Window owner, WebServerController controller) {
        new SettingsDialog(owner, controller).setVisible(true);
    }
}
