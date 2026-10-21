package com.aresstack.webserver.ui.publication;

import com.aresstack.webserver.domain.Site;
import com.aresstack.webserver.domain.Upstream;
import com.aresstack.webserver.ui.status.PublicationStatus;
import com.aresstack.webserver.infrastructure.managed.ManagedProcessManager;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;

/**
 * Eine Veröffentlichung als Karte. Der Badge zeigt den End-to-End-Zustand
 * der öffentlichen Adresse; erfordert ein Schritt eine Benutzeraktion, steht
 * die konkrete Anweisung direkt hier — nie nur in einer Detailansicht.
 */
class PublicationCard extends JPanel {

    private static final Color LIVE = new Color(0x2e7d32);
    private static final Color WARN = new Color(0xed6c02);
    private static final Color MUTED = new Color(0x757575);
    private static final Color BORDER = new Color(0xdddddd);

    interface CardActions {
        void edit();

        void remove();

        void details();

        void checkAgain();

        void startManaged();

        void stopManaged();

        void restartManaged();
    }

    PublicationCard(Site site, Upstream defaultUpstream, PublicationStatus status,
                    ManagedProcessManager.ProcessStatus processStatus, CardActions actions) {
        super(new BorderLayout(12, 0));
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(4, 0, 4, 0),
                BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(BORDER, 1, true),
                        BorderFactory.createEmptyBorder(12, 14, 12, 14))));

        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));

        JLabel host = new JLabel(site.host().value());
        host.setFont(host.getFont().deriveFont(Font.BOLD, host.getFont().getSize() + 2f));
        text.add(host);
        text.add(Box.createVerticalStrut(5));

        String httpsBadge = !site.httpsEnabled() ? "HTTP" : status.isSecured() ? "HTTPS ✓" : "HTTPS";
        text.add(new JLabel(httpsBadge + "     →     "
                + site.effectiveUpstream(defaultUpstream).toCaddyAddress()
                + (site.routes().isEmpty() ? "" : "     · " + site.routes().size() + " path rule(s)")));

        if (site.managedApplication().isPresent()) {
            String state = processStatus.state() + (processStatus.pid() > 0
                    ? " (PID " + processStatus.pid() + ")" : "");
            text.add(mutedLine("Managed JAR: " + state));
        }

        // Kopfzeile des Zustands + ggf. konkrete nächste Handlung.
        text.add(Box.createVerticalStrut(6));
        JLabel headline = new JLabel(status.headline());
        headline.setForeground(status.overall() == PublicationStatus.Overall.ACTION_REQUIRED ? WARN : MUTED);
        headline.setFont(headline.getFont().deriveFont(Font.BOLD));
        text.add(headline);

        if (status.overall() == PublicationStatus.Overall.SETTING_UP
                && status.actionText() == null && !"Checking…".equals(status.headline())) {
            text.add(mutedLine("No action required."));
        }
        if (status.actionText() != null) {
            text.add(Box.createVerticalStrut(3));
            for (String line : status.actionText().split("\n")) {
                text.add(mutedLine(line));
            }
        }
        if (status.dnsRecord() != null) {
            text.add(Box.createVerticalStrut(4));
            text.add(dnsRecordBlock(status.dnsRecord()));
        }
        if (status.overall() == PublicationStatus.Overall.ACTION_REQUIRED
                || status.overall() == PublicationStatus.Overall.UNVERIFIED) {
            text.add(Box.createVerticalStrut(6));
            JPanel actionButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            actionButtons.setOpaque(false);
            actionButtons.setAlignmentX(LEFT_ALIGNMENT);
            JButton check = new JButton("Check again");
            check.addActionListener(e -> actions.checkAgain());
            actionButtons.add(check);
            text.add(actionButtons);
        }

        // Teilstatus direkt auf der Karte, solange nicht alles läuft.
        if (status.overall() != PublicationStatus.Overall.LIVE && !status.subStatuses().isEmpty()) {
            text.add(Box.createVerticalStrut(8));
            for (PublicationStatus.SubStatus sub : status.subStatuses()) {
                JLabel row = new JLabel(subSymbol(sub.state()) + "  "
                        + String.format("%-10s", sub.label()) + "  " + sub.detail());
                row.setFont(row.getFont().deriveFont(row.getFont().getSize() - 1f));
                row.setForeground(sub.state() == PublicationStatus.SubState.WARN ? WARN : MUTED);
                text.add(row);
            }
        }
        add(text, BorderLayout.CENTER);

        JPanel right = new JPanel(new BorderLayout());
        right.setOpaque(false);
        JLabel badge = new JLabel(badgeText(status.overall()));
        badge.setForeground(badgeColor(status.overall()));
        badge.setHorizontalAlignment(JLabel.RIGHT);
        right.add(badge, BorderLayout.NORTH);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.setOpaque(false);
        JButton edit = new JButton("Edit");
        edit.addActionListener(e -> actions.edit());
        JButton more = new JButton("⋮");
        more.setMargin(new java.awt.Insets(2, 8, 2, 8));
        JPopupMenu menu = new JPopupMenu();
        JMenuItem details = new JMenuItem("Status details");
        details.addActionListener(e -> actions.details());
        JMenuItem remove = new JMenuItem("Remove…");
        remove.addActionListener(e -> actions.remove());
        menu.add(details);
        if (site.managedApplication().isPresent()) {
            menu.addSeparator();
            JMenuItem startProcess = new JMenuItem("Start application");
            startProcess.addActionListener(e -> actions.startManaged());
            JMenuItem stopProcess = new JMenuItem("Stop application");
            stopProcess.addActionListener(e -> actions.stopManaged());
            JMenuItem restartProcess = new JMenuItem("Restart application");
            restartProcess.addActionListener(e -> actions.restartManaged());
            menu.add(startProcess);
            menu.add(stopProcess);
            menu.add(restartProcess);
        }
        menu.addSeparator();
        menu.add(remove);
        more.addActionListener(e -> menu.show(more, 0, more.getHeight()));
        buttons.add(edit);
        buttons.add(more);
        right.add(buttons, BorderLayout.SOUTH);
        add(right, BorderLayout.EAST);

        setMaximumSize(new Dimension(Integer.MAX_VALUE, getPreferredSize().height + 8));
    }

    /**
     * Exakt die Feldwerte, die das Formular des DNS-Providers erwartet —
     * jede Zeile mit eigenem Copy-Button, nie ein zusammengesetzter Befehl.
     */
    private JPanel dnsRecordBlock(PublicationStatus.DnsRecord record) {
        JPanel block = new JPanel(new java.awt.GridBagLayout());
        block.setOpaque(false);
        block.setAlignmentX(LEFT_ALIGNMENT);
        java.awt.GridBagConstraints c = new java.awt.GridBagConstraints();
        c.insets = new java.awt.Insets(1, 0, 1, 10);
        c.anchor = java.awt.GridBagConstraints.WEST;

        addDnsRow(block, c, 0, "Type", "CNAME", null, null);
        addDnsRow(block, c, 1, "Hostname", record.hostname(), record.hostname(), null);
        addDnsRow(block, c, 2, "Target", record.target(), record.target(),
                helpButton(record));
        // Füllzelle, damit die Zeilen links ausgerichtet bleiben, wenn das
        // Panel auf Kartenbreite gestreckt wird.
        c.gridy = 0;
        c.gridx = 4;
        c.weightx = 1;
        block.add(javax.swing.Box.createHorizontalGlue(), c);
        return block;
    }

    private void addDnsRow(JPanel block, java.awt.GridBagConstraints c, int row,
                           String label, String value, String copyValue, JButton extra) {
        c.gridy = row;
        c.gridx = 0;
        JLabel name = mutedLine(label);
        block.add(name, c);
        c.gridx = 1;
        JLabel valueLabel = new JLabel(value);
        valueLabel.setFont(valueLabel.getFont().deriveFont(Font.BOLD));
        block.add(valueLabel, c);
        c.gridx = 2;
        if (copyValue != null) {
            JButton copy = squareButton("⧉", "Copy " + label.toLowerCase(java.util.Locale.ROOT));
            copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(copyValue), null));
            block.add(copy, c);
        }
        c.gridx = 3;
        if (extra != null) {
            block.add(extra, c);
        }
    }

    private JButton helpButton(PublicationStatus.DnsRecord record) {
        JButton help = squareButton("?", "How do I configure this?");
        help.addActionListener(e -> DnsHelpDialog.open(
                javax.swing.SwingUtilities.getWindowAncestor(this), record));
        return help;
    }

    private static JButton squareButton(String text, String tooltip) {
        JButton button = new JButton(text);
        button.setMargin(new java.awt.Insets(1, 5, 1, 5));
        button.setToolTipText(tooltip);
        return button;
    }

    private static JLabel mutedLine(String line) {
        JLabel label = new JLabel(line);
        label.setForeground(MUTED);
        return label;
    }

    private static String subSymbol(PublicationStatus.SubState state) {
        return switch (state) {
            case OK -> "✓";
            case WARN -> "⚠";
            case PENDING -> "◌";
            case OFF -> "○";
        };
    }

    private static String badgeText(PublicationStatus.Overall overall) {
        return switch (overall) {
            case LIVE -> "● Live";
            case SETTING_UP -> "◌ Setting up";
            case UNVERIFIED -> "◌ Not verified";
            case ACTION_REQUIRED -> "⚠ Action required";
            case STOPPED -> "○ Stopped";
        };
    }

    private static Color badgeColor(PublicationStatus.Overall overall) {
        return switch (overall) {
            case LIVE -> LIVE;
            case ACTION_REQUIRED -> WARN;
            case SETTING_UP, UNVERIFIED, STOPPED -> MUTED;
        };
    }
}
