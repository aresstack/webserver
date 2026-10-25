package com.aresstack.webserver.ui;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URI;

/**
 * Dynamic DNS.
 *
 * <p>Die Update-URL von Hand zu tippen war der unangenehmste Teil: ein falscher
 * Parametername faellt erst Wochen spaeter auf, wenn nach einem IP-Wechsel das
 * Zertifikat nicht mehr erneuert wird. Hier waehlt man den Anbieter, bekommt dessen
 * Adresse eingetragen, einen Link zur passenden Seite und den Hinweis, was bei
 * diesem Anbieter als Benutzername und Passwort gilt - bei deSEC etwa die Domain und
 * ein API-Token, bei Duck DNS gar kein Benutzername.
 *
 * <p>Darunter steht, was tatsaechlich aufgerufen wird. Der Test zeigt das Ergebnis je
 * Name im Dialog statt in einem Meldungsfenster, damit man bei mehreren Namen sieht,
 * welcher klemmt.
 */
final class DynDnsDialog extends JDialog {

    private static final Color LINK = new Color(0x2a6fb5);
    private static final Color MUTED = new Color(0x666666);
    /**
     * Breite jedes umbrochenen Hinweistexts.
     *
     * <p>Vorher standen hier drei verschiedene Werte, die zudem breiter waren als die
     * Spalte, in der die Labels sassen - der Text wurde rechts abgeschnitten. Und
     * weil jeder Anbieter einen unterschiedlich langen Hinweis hat, wanderten beim
     * Wechsel alle Zeilen darunter nach oben oder unten.
     */
    private static final int TEXT_WIDTH = 440;

    private final JCheckBox enabled = new JCheckBox(
            "Manage Dynamic DNS with AresStack Webserver", AppPreferences.dynDnsEnabled());
    private final JComboBox<DynDnsProvider> provider =
            new JComboBox<>(DynDnsProvider.all().toArray(new DynDnsProvider[0]));
    private final JLabel website = new JLabel();
    private final JLabel hint = new JLabel();
    private final JTextArea domains = new JTextArea(4, 26);
    private final JLabel usernameLabel = new JLabel();
    private final JTextField username = new JTextField(26);
    private final JLabel passwordLabel = new JLabel();
    private final JPasswordField password = new JPasswordField(26);
    // Feste Spaltenzahl: sonst bestimmt die laengste Anbieter-URL die Dialogbreite,
    // und die aendert sich bei jedem Wechsel. Der Inhalt scrollt im Feld.
    private final JTextField updateUrl = new JTextField(34);
    private final JLabel preview = new JLabel();
    private final JLabel status = new JLabel();
    private final JTextArea results = new JTextArea(4, 26);
    private final JButton test = new JButton("Test now");

    private DynDnsDialog(Window owner) {
        super(owner, "Dynamic DNS", ModalityType.APPLICATION_MODAL);

        domains.setText(AppPreferences.dynDnsDomain().replace(",", "\n"));
        username.setText(AppPreferences.dynDnsUsername());
        password.setText(AppPreferences.dynDnsPassword());
        updateUrl.setText(AppPreferences.dynDnsUpdateUrl());
        // Eine bestehende URL erkennt ihren Anbieter wieder, damit der Dialog nach
        // einem Update nicht bei "Custom" steht, obwohl alles eingerichtet ist.
        provider.setSelectedItem(DynDnsProvider.matching(updateUrl.getText())
                .orElseGet(() -> DynDnsProvider.byId(AppPreferences.dynDnsProvider())));

        getContentPane().add(buildForm(), BorderLayout.CENTER);
        getContentPane().add(buildButtons(), BorderLayout.SOUTH);
        wireBehaviour();

        applyProvider(false);
        updateFieldState();
        updatePreview();
        reserveTextHeights();
        getRootPane().setDefaultButton(null);
        pack();
        setMinimumSize(getSize());
        setLocationRelativeTo(owner);
    }

    private JPanel buildForm() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(14, 16, 8, 16));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 0, 4, 10);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        int row = 0;

        c.gridx = 0;
        c.gridy = row++;
        c.gridwidth = 2;
        form.add(enabled, c);

        c.gridwidth = 1;
        JPanel providerRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        providerRow.add(provider);
        providerRow.add(website);
        addRow(form, c, row++, "Provider", providerRow);

        c.gridx = 0;
        c.gridy = row++;
        c.gridwidth = 2;
        hint.setForeground(MUTED);
        form.add(hint, c);
        c.gridwidth = 1;

        domains.setLineWrap(false);
        JScrollPane domainsScroll = new JScrollPane(domains);
        domainsScroll.setPreferredSize(new Dimension(
                username.getPreferredSize().width, username.getPreferredSize().height * 4));
        addRow(form, c, row++, "Domains", domainsScroll);
        c.gridx = 0;
        c.gridy = row++;
        c.gridwidth = 2;
        form.add(muted("One name per line. A subdomain that points to your DynDNS name"
                + " by CNAME needs no entry of its own."), c);
        c.gridwidth = 1;

        addRow(form, c, row++, usernameLabel, username);
        addRow(form, c, row++, passwordLabel, password);

        addRow(form, c, row++, "Update URL", updateUrl);
        c.gridx = 0;
        c.gridy = row++;
        c.gridwidth = 2;
        preview.setForeground(MUTED);
        form.add(preview, c);
        c.gridwidth = 1;

        results.setEditable(false);
        results.setLineWrap(false);
        results.setBackground(form.getBackground());
        results.setBorder(BorderFactory.createEmptyBorder());
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actions.add(test);
        actions.add(status);
        addRow(form, c, row++, "", actions);
        c.gridx = 1;
        c.gridy = row;
        c.weightx = 1;
        c.fill = GridBagConstraints.BOTH;
        form.add(new JScrollPane(results), c);
        return form;
    }

    /**
     * Haelt Platz fuer den laengsten Hinweis und zwei Vorschauzeilen frei.
     *
     * <p>Ohne das aendert jeder Anbieterwechsel die Hoehe des Hinweises, und alle
     * Zeilen darunter springen - bei No-IP mit seinem langen Satz am deutlichsten.
     * Die Hoehe wird einmal ueber alle Anbieter ermittelt und dann festgehalten.
     */
    private void reserveTextHeights() {
        String current = hint.getText();
        int tallest = 0;
        for (DynDnsProvider candidate : DynDnsProvider.all()) {
            hint.setText(wrapped(candidate.hint()));
            tallest = Math.max(tallest, hint.getPreferredSize().height);
        }
        hint.setText(current);
        fix(hint, tallest);

        String shown = preview.getText();
        preview.setText(wrapped("x"));
        int oneLine = preview.getPreferredSize().height;
        preview.setText(shown);
        fix(preview, oneLine * 2);
    }

    private static void fix(JLabel label, int height) {
        Dimension size = new Dimension(TEXT_WIDTH, height);
        label.setPreferredSize(size);
        label.setMinimumSize(size);
        label.setVerticalAlignment(javax.swing.SwingConstants.TOP);
    }

    private JPanel buildButtons() {
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        JButton save = new JButton("Save");
        save.addActionListener(e -> {
            store();
            dispose();
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        buttons.add(cancel);
        buttons.add(save);
        return buttons;
    }

    private void wireBehaviour() {
        enabled.addActionListener(e -> updateFieldState());
        provider.addActionListener(e -> {
            applyProvider(true);
            updateFieldState();
            updatePreview();
        });
        DocumentListener refresh = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                updatePreview();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                updatePreview();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                updatePreview();
            }
        };
        updateUrl.getDocument().addDocumentListener(refresh);
        domains.getDocument().addDocumentListener(refresh);
        username.getDocument().addDocumentListener(refresh);
        test.addActionListener(e -> runTest());

        website.setForeground(LINK);
        website.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        website.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                openWebsite();
            }
        });
    }

    /** Uebernimmt Adresse und Beschriftungen des gewaehlten Anbieters. */
    private void applyProvider(boolean replaceUrl) {
        DynDnsProvider selected = selectedProvider();
        usernameLabel.setText(selected.needsUsername() ? selected.usernameLabel() : "Username");
        passwordLabel.setText(selected.passwordLabel());
        hint.setText(wrapped(selected.hint()));
        website.setText(selected.website().isEmpty() ? "" : "Open provider website ↗");
        website.setVisible(!selected.website().isEmpty());
        if (replaceUrl && !selected.isCustom()) {
            updateUrl.setText(selected.updateUrl());
        }
    }

    private void updateFieldState() {
        boolean on = enabled.isSelected();
        DynDnsProvider selected = selectedProvider();
        provider.setEnabled(on);
        domains.setEnabled(on);
        username.setEnabled(on && selected.needsUsername());
        usernameLabel.setEnabled(on && selected.needsUsername());
        password.setEnabled(on);
        passwordLabel.setEnabled(on);
        // Eine vorgegebene Adresse laesst sich nicht versehentlich zerschiessen;
        // wer sie braucht, waehlt Custom.
        updateUrl.setEditable(on && selected.isCustom());
        updateUrl.setEnabled(on);
        test.setEnabled(on);
    }

    /** Zeigt den Aufruf, der wirklich hinausgeht - mit maskiertem Passwort. */
    private void updatePreview() {
        String template = updateUrl.getText().trim();
        java.util.List<String> names = names();
        if (template.isEmpty() || names.isEmpty()) {
            preview.setText(" ");
            return;
        }
        String resolved = template
                .replace("<domain>", names.get(0))
                .replace("<username>", username.getText().trim())
                .replace("<password>", "••••••")
                .replace("<ipaddr>", "<your IPv4>")
                .replace("<ip6addr>", "");
        String suffix = names.size() > 1 ? "  (and " + (names.size() - 1) + " more)" : "";
        preview.setText(wrapped(resolved + suffix));
    }

    private void runTest() {
        store();
        test.setEnabled(false);
        results.setText("Updating…");
        new SwingWorker<DynDnsClient.Result, Void>() {
            @Override
            protected DynDnsClient.Result doInBackground() {
                return DynDnsClient.updateNow();
            }

            @Override
            protected void done() {
                test.setEnabled(enabled.isSelected());
                try {
                    DynDnsClient.Result result = get();
                    status.setText("Last update: " + DynDnsClient.lastUpdateInfo());
                    results.setForeground(result.success() ? new Color(0x1a7f37)
                            : new Color(0xb3261e));
                    results.setText(result.message());
                    results.setCaretPosition(0);
                } catch (Exception failure) {
                    FriendlyErrors.show(DynDnsDialog.this, "Dynamic DNS", failure);
                }
            }
        }.execute();
    }

    private void openWebsite() {
        String url = selectedProvider().website();
        if (url.isEmpty()) {
            return;
        }
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
            }
        } catch (Exception unavailable) {
            // Kein Browser erreichbar: die Adresse steht im Tooltip.
            website.setToolTipText(url);
        }
    }

    private DynDnsProvider selectedProvider() {
        Object selected = provider.getSelectedItem();
        return selected instanceof DynDnsProvider known ? known : DynDnsProvider.CUSTOM;
    }

    private java.util.List<String> names() {
        return java.util.Arrays.stream(domains.getText().split("[,;\\s]+"))
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .toList();
    }

    private void store() {
        AppPreferences.setDynDnsEnabled(enabled.isSelected());
        AppPreferences.setDynDnsProvider(selectedProvider().id());
        AppPreferences.setDynDnsUpdateUrl(updateUrl.getText().trim());
        AppPreferences.setDynDnsDomain(domains.getText().trim());
        AppPreferences.setDynDnsUsername(username.getText().trim());
        AppPreferences.setDynDnsPassword(new String(password.getPassword()));
    }

    private static JLabel muted(String text) {
        JLabel label = new JLabel(wrapped(text));
        label.setForeground(MUTED);
        return label;
    }

    private static String wrapped(String text) {
        return "<html><body style='width:" + TEXT_WIDTH + "px'>" + escape(text)
                + "</body></html>";
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static void addRow(JPanel form, GridBagConstraints c, int row, String label,
                               java.awt.Component field) {
        addRow(form, c, row, new JLabel(label), field);
    }

    private static void addRow(JPanel form, GridBagConstraints c, int row, JLabel label,
                               java.awt.Component field) {
        c.gridx = 0;
        c.gridy = row;
        c.weightx = 0;
        form.add(label, c);
        c.gridx = 1;
        c.weightx = 1;
        form.add(field, c);
        c.weightx = 0;
    }

    static void show(Window owner) {
        new DynDnsDialog(owner).setVisible(true);
    }
}
