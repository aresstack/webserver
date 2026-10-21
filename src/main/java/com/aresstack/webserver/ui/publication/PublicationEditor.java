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
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * "Add service" / Bearbeiten: öffentliche Adresse, Ziel, HTTPS.
 * Der triviale Fall ist Domain eintippen → Add service; Backend-Protokoll
 * und Pfadregeln liegen unter Advanced.
 */
public class PublicationEditor extends JDialog {

    private final JTextField addressField = new JTextField(26);
    private final JTextField hostField = new JTextField("localhost", 18);
    private final JTextField portField = new JTextField("8080", 6);
    private final JCheckBox httpsBox = new JCheckBox("HTTPS", true);
    private final JComboBox<String> backendProtocol = new JComboBox<>(new String[]{"HTTP", "HTTPS"});
    private final List<Route> routes = new ArrayList<>();
    private final JPanel routeList = new JPanel();
    private final JPanel advancedPanel = new JPanel(new BorderLayout(0, 8));

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
        }

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(5, 12, 5, 12);
        c.anchor = GridBagConstraints.WEST;
        int row = 0;
        Forms.addRow(form, c, row++, "Public address", addressField);
        JPanel forwardTo = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        forwardTo.add(hostField);
        forwardTo.add(portField);
        Forms.addRow(form, c, row++, "Forward to", forwardTo);
        JPanel secure = new JPanel();
        secure.setLayout(new BoxLayout(secure, BoxLayout.Y_AXIS));
        secure.add(httpsBox);
        JLabel secureHint = new JLabel(
                "<html><small>AresStack automatically obtains and renews the certificate.<br>"
                        + "After publishing, it checks whether your DNS and network are ready.</small></html>");
        secureHint.setBorder(BorderFactory.createEmptyBorder(0, 22, 0, 0));
        secure.add(secureHint);
        Forms.addRow(form, c, row++, "Secure connection", secure);

        // Advanced: Backend-Protokoll und Pfadregeln — eingeklappt, damit der
        // Standardfall einfach bleibt.
        JToggleButton advancedToggle = new JToggleButton("▸ Advanced");
        advancedToggle.setBorderPainted(false);
        advancedToggle.setContentAreaFilled(false);
        advancedToggle.setFocusPainted(false);
        advancedToggle.setHorizontalAlignment(JButton.LEFT);
        advancedToggle.addActionListener(e -> {
            advancedPanel.setVisible(advancedToggle.isSelected());
            advancedToggle.setText(advancedToggle.isSelected() ? "▾ Advanced" : "▸ Advanced");
            pack();
        });

        JPanel protocolRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        protocolRow.add(new JLabel("Backend protocol"));
        protocolRow.add(backendProtocol);
        routeList.setLayout(new BoxLayout(routeList, BoxLayout.Y_AXIS));
        JButton addPath = new JButton("+ Add path");
        addPath.addActionListener(e -> addPath());
        JPanel routing = new JPanel(new BorderLayout(0, 4));
        routing.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        routing.add(new JLabel("Path routing"), BorderLayout.NORTH);
        routing.add(routeList, BorderLayout.CENTER);
        JPanel addPathRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 2));
        addPathRow.add(addPath);
        routing.add(addPathRow, BorderLayout.SOUTH);
        advancedPanel.setBorder(BorderFactory.createEmptyBorder(0, 14, 0, 14));
        advancedPanel.add(protocolRow, BorderLayout.NORTH);
        advancedPanel.add(routing, BorderLayout.CENTER);
        advancedPanel.setVisible(false);
        rebuildRouteList();

        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        JButton save = new JButton(existing == null ? "Publish" : "Save");
        save.addActionListener(e -> save());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        buttons.add(cancel);
        buttons.add(save);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.add(form);
        JPanel togglePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        togglePanel.add(advancedToggle);
        content.add(togglePanel);
        content.add(advancedPanel);
        content.add(applicationPanel);

        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        int maxHeight = Math.max(320, Math.min(520,
                java.awt.Toolkit.getDefaultToolkit().getScreenSize().height - 160));
        scroll.setPreferredSize(new java.awt.Dimension(
                Math.max(550, content.getPreferredSize().width + 24), maxHeight));
        getContentPane().add(scroll, BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(save);
        pack();
        setLocationRelativeTo(owner);
    }

    private void rebuildRouteList() {
        routeList.removeAll();
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
            routeList.add(line);
        }
        routeList.add(Box.createVerticalStrut(2));
        routeList.revalidate();
        routeList.repaint();
        if (isVisible()) {
            pack();
        }
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
            result = new Site(new DomainName(addressField.getText()), Optional.of(upstream),
                    List.copyOf(routes), httpsBox.isSelected(), applicationPanel.value());
            dispose();
        } catch (RuntimeException e) {
            FriendlyErrors.show(this, "Publish service", e);
        }
    }

    /** Zeigt den Editor modal; {@code null} bei Abbruch. */
    public static Site open(Window owner, Site existing, Upstream defaultUpstream,
                            String addressSuggestion) {
        PublicationEditor editor = new PublicationEditor(owner, existing, defaultUpstream, addressSuggestion);
        editor.setVisible(true);
        return editor.result;
    }
}
