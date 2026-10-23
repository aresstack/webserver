package com.aresstack.webserver.ui.publication;

import com.aresstack.webserver.domain.DomainName;
import com.aresstack.webserver.domain.Route;
import com.aresstack.webserver.domain.Site;
import com.aresstack.webserver.domain.Upstream;
import com.aresstack.webserver.ui.Forms;
import com.aresstack.webserver.ui.FriendlyErrors;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * "Publish service" / Bearbeiten mit fester Größe und zweistufiger Navigation:
 * links die Hauptbereiche <b>Service</b> und <b>Application</b> (CardLayout),
 * innerhalb von Application die Untertabs Runtime, Environment und
 * Restart &amp; health. Eine verwaltete Anwendung ist konfiguriert, sobald eine
 * JAR-Datei angegeben ist — es gibt keine separate "Manage"-Checkbox.
 */
public class PublicationEditor extends JDialog {

    private final JTextField addressField = new JTextField();
    private final JTextField hostField = new JTextField("localhost");
    private final JTextField portField = new JTextField("8080", 6);
    private final JCheckBox httpsBox = new JCheckBox("Use HTTPS (recommended)", true);
    private final JComboBox<String> backendProtocol = new JComboBox<>(new String[]{"HTTP", "HTTPS"});
    private final JComboBox<String> authenticationMode =
            new JComboBox<>(new String[]{"None", "Central OAuth"});
    private final JTextField requiredScopes = new JTextField("mcp");
    /** Allow/Deny catalogue of the edited site; derived from requiredScopes when absent. */
    private com.aresstack.webserver.domain.ScopePolicy scopePolicy = null;
    private final List<Route> routes = new ArrayList<>();
    private final JPanel routeList = new JPanel();

    private Site result;
    private final ManagedApplicationPanel applicationPanel;

    private PublicationEditor(Window owner, Site existing, Upstream defaultUpstream,
                              String addressSuggestion) {
        super(owner, existing == null ? "Publish service" : "Edit service", ModalityType.APPLICATION_MODAL);
        applicationPanel = new ManagedApplicationPanel(existing == null
                ? Optional.empty() : existing.managedApplication());

        if (existing == null && addressSuggestion != null) {
            addressField.setText(addressSuggestion);
        }
        if (existing != null) {
            addressField.setText(existing.host().value());
            Upstream target = existing.effectiveUpstream(defaultUpstream);
            hostField.setText(target.host());
            portField.setText(String.valueOf(target.port()));
            backendProtocol.setSelectedItem(target.scheme().equals("https") ? "HTTPS" : "HTTP");
            httpsBox.setSelected(existing.httpsEnabled());
            routes.addAll(existing.routes());
            existing.authentication().ifPresent(auth -> {
                authenticationMode.setSelectedItem("Central OAuth");
                requiredScopes.setText(String.join(" ", auth.requiredScopes()));
                scopePolicy = auth.scopePolicy();
            });
        }

        JTabbedPane applicationTabs = new JTabbedPane();
        applicationTabs.addTab("Runtime", applicationPanel.runtimeTab());
        applicationTabs.addTab("Environment", applicationPanel.environmentTab());
        applicationTabs.addTab("Restart & health", applicationPanel.restartTab());
        applicationPanel.onConfiguredChanged(configured -> {
            applicationTabs.setEnabledAt(1, configured);
            applicationTabs.setEnabledAt(2, configured);
            if (!configured && applicationTabs.getSelectedIndex() != 0) {
                applicationTabs.setSelectedIndex(0);
            }
        });
        applicationPanel.refreshConfiguredState();
        // Die abgeleiteten Umgebungsvariablen zeigen, was der Prozess bekommt -
        // also muessen sie jeder Aenderung an Adresse, Port, HTTPS, Modus oder
        // Scope sofort folgen, nicht erst nach dem Speichern.
        Runnable refreshDerived = () -> applicationPanel.showDerived(derivedEnvironment());
        refreshDerived.run();
        for (javax.swing.text.JTextComponent field
                : java.util.List.of(addressField, portField, hostField, requiredScopes)) {
            field.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                @Override
                public void insertUpdate(javax.swing.event.DocumentEvent e) {
                    refreshDerived.run();
                }

                @Override
                public void removeUpdate(javax.swing.event.DocumentEvent e) {
                    refreshDerived.run();
                }

                @Override
                public void changedUpdate(javax.swing.event.DocumentEvent e) {
                    refreshDerived.run();
                }
            });
        }
        httpsBox.addActionListener(e -> refreshDerived.run());
        authenticationMode.addActionListener(e -> refreshDerived.run());

        CardLayout cards = new CardLayout();
        JPanel cardPanel = new JPanel(cards);
        cardPanel.add(serviceCard(), "Service");
        cardPanel.add(applicationTabs, "Application");

        JList<String> navigation = new JList<>(new String[]{"Service", "Application"});
        navigation.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        navigation.setSelectedIndex(0);
        navigation.setFixedCellHeight(32);
        navigation.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        navigation.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && navigation.getSelectedValue() != null) {
                cards.show(cardPanel, navigation.getSelectedValue());
            }
        });
        JScrollPane navigationScroll = new JScrollPane(navigation);
        navigationScroll.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Color.LIGHT_GRAY));
        navigationScroll.setPreferredSize(new Dimension(140, 100));

        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        JButton save = new JButton(existing == null ? "Publish" : "Save");
        save.addActionListener(e -> save());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        buttons.add(cancel);
        buttons.add(save);

        getContentPane().add(navigationScroll, BorderLayout.WEST);
        getContentPane().add(cardPanel, BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(save);

        cardPanel.setPreferredSize(new Dimension(680, 480));
        pack();
        setMinimumSize(getSize());
        setLocationRelativeTo(owner);
    }

    private JComponent serviceCard() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(5, 0, 5, 8);
        c.anchor = GridBagConstraints.NORTHWEST;
        int row = 0;

        addLabeledField(form, c, row++, "Public address", addressField);

        JPanel forwardTo = new JPanel(new BorderLayout(6, 0));
        forwardTo.add(hostField, BorderLayout.CENTER);
        JPanel portGroup = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        portGroup.add(new JLabel("Port"));
        portGroup.add(portField);
        forwardTo.add(portGroup, BorderLayout.EAST);
        addLabeledField(form, c, row++, "Forward to", forwardTo);

        addLabeledField(form, c, row++, "Backend protocol", leftAligned(backendProtocol));

        JPanel authentication = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        authentication.add(authenticationMode);
        authentication.add(new JLabel("Required scopes"));
        requiredScopes.setColumns(12);
        requiredScopes.setToolTipText("Space-separated scopes the service expects, e.g. mcp");
        authentication.add(requiredScopes);
        JButton permissions = new JButton("Permissions…");
        permissions.setToolTipText("Allow or deny the individual scopes of this service");
        permissions.addActionListener(e -> ScopePolicyDialog
                .edit(PublicationEditor.this, currentScopePolicy())
                .ifPresent(updated -> scopePolicy = updated));
        authentication.add(permissions);
        Runnable updateScopesEnabled = () -> {
            boolean central = "Central OAuth".equals(authenticationMode.getSelectedItem());
            requiredScopes.setEnabled(central);
            permissions.setEnabled(central);
        };
        authenticationMode.addActionListener(e -> updateScopesEnabled.run());
        updateScopesEnabled.run();
        JPanel authColumn = new JPanel();
        authColumn.setLayout(new BoxLayout(authColumn, BoxLayout.Y_AXIS));
        authentication.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        authColumn.add(authentication);
        JLabel authHint = hintLabel(
                "Central OAuth: the AresStack OAuth service issues tokens; the backend validates them.");
        authHint.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        authColumn.add(authHint);
        addLabeledField(form, c, row++, "Authentication", authColumn);

        JPanel secure = new JPanel();
        secure.setLayout(new BoxLayout(secure, BoxLayout.Y_AXIS));
        secure.add(httpsBox);
        JLabel secureHint = hintLabel("<html>AresStack automatically obtains and renews the certificate.<br>"
                + "After publishing, it checks whether your DNS and network are ready.</html>");
        secureHint.setBorder(BorderFactory.createEmptyBorder(0, 22, 0, 0));
        secure.add(secureHint);
        addLabeledField(form, c, row++, "Secure connection", secure);

        routeList.setLayout(new BoxLayout(routeList, BoxLayout.Y_AXIS));
        rebuildRouteList();
        JScrollPane routeScroll = new JScrollPane(routeList);
        routeScroll.setPreferredSize(new Dimension(100, 110));
        JPanel routing = new JPanel(new BorderLayout(0, 4));
        routing.add(routeScroll, BorderLayout.CENTER);
        JPanel routingActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        JButton addPath = new JButton("+ Add path");
        addPath.addActionListener(e -> addPath());
        routingActions.add(addPath);
        routing.add(routingActions, BorderLayout.SOUTH);
        GridBagConstraints routeConstraints = (GridBagConstraints) c.clone();
        routeConstraints.gridx = 0;
        routeConstraints.gridy = row;
        routeConstraints.weightx = 0;
        panelLabel(form, routeConstraints, "Path routing");
        routeConstraints.gridx = 1;
        routeConstraints.weightx = 1;
        routeConstraints.weighty = 1;
        routeConstraints.fill = GridBagConstraints.BOTH;
        form.add(routing, routeConstraints);

        return form;
    }

    private static void panelLabel(JPanel form, GridBagConstraints c, String text) {
        GridBagConstraints labelConstraints = (GridBagConstraints) c.clone();
        labelConstraints.fill = GridBagConstraints.NONE;
        labelConstraints.weighty = 0;
        form.add(new JLabel(text), labelConstraints);
    }

    private static void addLabeledField(JPanel form, GridBagConstraints template, int row,
                                        String label, JComponent field) {
        GridBagConstraints c = (GridBagConstraints) template.clone();
        c.gridx = 0;
        c.gridy = row;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        form.add(new JLabel(label), c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        form.add(field, c);
    }

    private static JComponent leftAligned(JComponent component) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        panel.add(component);
        return panel;
    }

    private static JLabel hintLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(label.getFont().getSize2D() - 1f));
        label.setForeground(Color.GRAY);
        return label;
    }

    private void rebuildRouteList() {
        routeList.removeAll();
        if (routes.isEmpty()) {
            JLabel empty = hintLabel("No path rules; everything is forwarded to the target above.");
            empty.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
            routeList.add(empty);
        }
        for (Route route : List.copyOf(routes)) {
            JPanel line = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 1));
            line.add(new JLabel(route.pathMatcher() + "   →   " + route.upstream().toCaddyAddress()));
            JButton remove = new JButton("×");
            remove.setMargin(new Insets(0, 6, 0, 6));
            remove.addActionListener(e -> {
                routes.remove(route);
                rebuildRouteList();
            });
            line.add(remove);
            line.setMaximumSize(new Dimension(Integer.MAX_VALUE, line.getPreferredSize().height));
            routeList.add(line);
        }
        routeList.add(Box.createVerticalGlue());
        routeList.revalidate();
        routeList.repaint();
    }

    private void addPath() {
        JTextField path = new JTextField("/", 14);
        JTextField host = new JTextField("localhost", 14);
        JTextField port = new JTextField("8080", 6);
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 6, 3, 6);
        c.anchor = GridBagConstraints.WEST;
        Forms.addRow(panel, c, 0, "Path (e.g. /api/*)", path);
        Forms.addRow(panel, c, 1, "Forward to", host);
        Forms.addRow(panel, c, 2, "Port", port);
        int choice = JOptionPane.showConfirmDialog(this, panel, "Add path",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice == JOptionPane.OK_OPTION) {
            try {
                routes.add(new Route(path.getText().trim(),
                        Upstream.parse("http://" + host.getText().trim() + ":" + port.getText().trim())));
                rebuildRouteList();
            } catch (RuntimeException e) {
                FriendlyErrors.show(this, "Add path", e);
            }
        }
    }

    private void save() {
        try {
            String scheme = "HTTPS".equals(backendProtocol.getSelectedItem()) ? "https" : "http";
            Upstream upstream = Upstream.parse(
                    scheme + "://" + hostField.getText().trim() + ":" + portField.getText().trim());
            Site site = new Site(new DomainName(addressField.getText()), Optional.of(upstream),
                    List.copyOf(routes), httpsBox.isSelected(), applicationPanel.value());
            site = withAuthentication(site,
                    "Central OAuth".equals(authenticationMode.getSelectedItem()),
                    scopeNames(), currentScopePolicy());
            result = site;
            dispose();
        } catch (RuntimeException e) {
            FriendlyErrors.show(this, "Publish service", e);
        }
    }

    /**
     * Die aus dem aktuellen Formularzustand ableitbaren Umgebungsvariablen.
     *
     * <p>Bewusst aus den Feldern und nicht aus der gespeicherten Site: der Dialog
     * soll zeigen, was nach dem Speichern gilt. Unvollstaendige Eingaben liefern
     * einfach weniger Werte statt eines Fehlers.
     */
    private java.util.Map<String, String> derivedEnvironment() {
        String address = addressField.getText().trim();
        if (address.isBlank()) {
            return java.util.Map.of();
        }
        com.aresstack.webserver.domain.Site preview;
        try {
            String scheme = "HTTPS".equals(backendProtocol.getSelectedItem()) ? "https" : "http";
            Upstream upstream = Upstream.parse(
                    scheme + "://" + hostField.getText().trim() + ":" + portField.getText().trim());
            preview = new Site(new DomainName(address), Optional.of(upstream), List.of(),
                    httpsBox.isSelected(), Optional.empty());
        } catch (RuntimeException incomplete) {
            return java.util.Map.of();
        }
        if ("Central OAuth".equals(authenticationMode.getSelectedItem())) {
            preview = preview.withAuthentication(
                    new com.aresstack.webserver.domain.SiteAuthentication(
                            scopeNames(), currentScopePolicy()));
        }
        return com.aresstack.webserver.domain.DerivedEnvironment.valuesFor(preview);
    }

    private java.util.List<String> scopeNames() {
        return java.util.Arrays.stream(requiredScopes.getText().trim().split("[,\s]+"))
                .filter(scope -> !scope.isBlank()).toList();
    }

    /**
     * The policy being edited. Without one, it is derived from the scope field so a
     * freshly published service starts with Allow all over exactly those scopes.
     * Scopes typed into the field are folded in, so the text box and the dialog stay
     * consistent either way.
     */
    private com.aresstack.webserver.domain.ScopePolicy currentScopePolicy() {
        java.util.List<String> typed = scopeNames();
        if (scopePolicy == null) {
            return com.aresstack.webserver.domain.ScopePolicy.allowAll(typed);
        }
        com.aresstack.webserver.domain.ScopePolicy policy = scopePolicy;
        for (String scope : typed) {
            if (!policy.knows(scope)) {
                policy = policy.withDecision(scope,
                        com.aresstack.webserver.domain.ScopeDecision.DEFAULT);
            }
        }
        return policy;
    }

    /**
     * Haengt die Authentifizierung an die Site - samt Berechtigungskatalog.
     *
     * <p>Hier wurde bisher nur die Scope-Liste uebergeben. Der Katalog wurde daraus
     * als "Allow all" neu aufgebaut, womit jede im Permissions-Dialog getroffene
     * Deny-Entscheidung und jeder gemeldete Eintrag beim Speichern verloren ging -
     * stillschweigend und genau fuer die Dienste, bei denen das am meisten weh tut.
     */
    static Site withAuthentication(Site site, boolean centralOAuth,
                                   java.util.List<String> scopes,
                                   com.aresstack.webserver.domain.ScopePolicy policy) {
        if (!centralOAuth) {
            return site;
        }
        return site.withAuthentication(
                new com.aresstack.webserver.domain.SiteAuthentication(scopes, policy));
    }

    /** Zeigt den Editor modal; {@code null} bei Abbruch. */
    public static Site open(Window owner, Site existing, Upstream defaultUpstream,
                            String addressSuggestion) {
        PublicationEditor editor = new PublicationEditor(owner, existing, defaultUpstream, addressSuggestion);
        editor.setVisible(true);
        return editor.result;
    }
}
