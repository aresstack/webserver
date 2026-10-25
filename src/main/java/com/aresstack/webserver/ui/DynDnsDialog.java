package com.aresstack.webserver.ui;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;

/**
 * Dynamic DNS als eigener Dialog.
 *
 * <p>Die Einstellungen sind hier gelandet, weil sie in den Server-Einstellungen nur
 * Platz gekostet haben: wer DynDNS nicht benutzt - etwa weil der Router das Update
 * macht - musste sich trotzdem durch fuenf Felder scrollen.
 */
final class DynDnsDialog extends JDialog {

    private DynDnsDialog(Window owner) {
        super(owner, "Dynamic DNS", ModalityType.APPLICATION_MODAL);

        JCheckBox enabled = new JCheckBox(
                "Manage Dynamic DNS with AresStack Webserver", AppPreferences.dynDnsEnabled());
        JTextField updateUrl = new JTextField(AppPreferences.dynDnsUpdateUrl(), 32);
        JTextField username = new JTextField(AppPreferences.dynDnsUsername(), 24);
        // Mehrzeilig: wer pro Dienst einen eigenen A-Record fuehrt, muss auch jeden
        // einzeln aktualisieren. Mit einem CNAME auf den DynDNS-Namen genuegt eine
        // Zeile - dann folgt die Subdomain von selbst.
        JTextArea domains = new JTextArea(
                AppPreferences.dynDnsDomain().replace(",", "\n"), 4, 24);
        domains.setLineWrap(false);
        JScrollPane domainsScroll = new JScrollPane(domains);
        domainsScroll.setPreferredSize(new Dimension(
                username.getPreferredSize().width, username.getPreferredSize().height * 4));
        JPasswordField password = new JPasswordField(AppPreferences.dynDnsPassword(), 24);
        JLabel status = new JLabel("Last update: " + DynDnsClient.lastUpdateInfo());
        JButton test = new JButton("Test configuration");

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(12, 12, 4, 12));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 0, 4, 12);
        c.anchor = GridBagConstraints.WEST;
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        form.add(enabled, c);
        c.gridwidth = 1;
        Forms.addRow(form, c, 1, "Update URL", updateUrl);
        Forms.addRow(form, c, 2, "Domains (one per line)", domainsScroll);
        Forms.addRow(form, c, 3, "Username", username);
        Forms.addRow(form, c, 4, "Password", password);
        c.gridx = 0;
        c.gridy = 5;
        c.gridwidth = 2;
        form.add(new JLabel("<html><small>Placeholders: &lt;domain&gt; &lt;username&gt;"
                + " &lt;password&gt; &lt;ipaddr&gt; &lt;ip6addr&gt;<br>"
                + "Each name is updated with its own request. A subdomain pointing to"
                + " your DynDNS name by CNAME needs no entry of its own."
                + "</small></html>"), c);
        c.gridy = 6;
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actions.add(test);
        actions.add(status);
        form.add(actions, c);

        Runnable fieldState = () -> {
            boolean on = enabled.isSelected();
            updateUrl.setEnabled(on);
            domains.setEnabled(on);
            username.setEnabled(on);
            password.setEnabled(on);
            test.setEnabled(on);
        };
        fieldState.run();
        enabled.addActionListener(e -> fieldState.run());

        test.addActionListener(e -> {
            // Aktuelle Feldwerte fuer den Test uebernehmen.
            store(enabled, updateUrl, domains, username, password);
            test.setEnabled(false);
            new SwingWorker<DynDnsClient.Result, Void>() {
                @Override
                protected DynDnsClient.Result doInBackground() {
                    return DynDnsClient.updateNow();
                }

                @Override
                protected void done() {
                    test.setEnabled(true);
                    try {
                        DynDnsClient.Result result = get();
                        status.setText("Last update: " + DynDnsClient.lastUpdateInfo());
                        javax.swing.JOptionPane.showMessageDialog(DynDnsDialog.this,
                                (result.success() ? "Update successful.\n" : "Update failed.\n")
                                        + result.message(),
                                "Dynamic DNS", result.success()
                                        ? javax.swing.JOptionPane.INFORMATION_MESSAGE
                                        : javax.swing.JOptionPane.WARNING_MESSAGE);
                    } catch (Exception failure) {
                        FriendlyErrors.show(DynDnsDialog.this, "Dynamic DNS", failure);
                    }
                }
            }.execute();
        });

        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        JButton save = new JButton("Save");
        save.addActionListener(e -> {
            store(enabled, updateUrl, domains, username, password);
            dispose();
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        buttons.add(cancel);
        buttons.add(save);

        getContentPane().add(form, BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(save);
        pack();
        setResizable(false);
        setLocationRelativeTo(owner);
    }

    private static void store(JCheckBox enabled, JTextField updateUrl, JTextArea domains,
                              JTextField username, JPasswordField password) {
        AppPreferences.setDynDnsEnabled(enabled.isSelected());
        AppPreferences.setDynDnsUpdateUrl(updateUrl.getText().trim());
        AppPreferences.setDynDnsDomain(domains.getText().trim());
        AppPreferences.setDynDnsUsername(username.getText().trim());
        AppPreferences.setDynDnsPassword(new String(password.getPassword()));
    }

    static void show(Window owner) {
        new DynDnsDialog(owner).setVisible(true);
    }
}
