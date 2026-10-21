package com.aresstack.webserver.ui.publication;

import com.aresstack.webserver.domain.Site;
import com.aresstack.webserver.domain.WebServerConfiguration;
import com.aresstack.webserver.ui.status.PublicationStatus;
import com.aresstack.webserver.ui.WebServerController;
import com.aresstack.webserver.infrastructure.managed.ManagedProcessManager;
import javax.swing.JOptionPane;
import java.nio.file.Files;
import java.nio.file.Path;
import com.aresstack.webserver.ui.status.PublicationStatusPresenter;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.text.SimpleDateFormat;

/**
 * Detailansicht des Veröffentlichungszustands. Sie ist nie notwendig, um die
 * Einrichtung abzuschließen — alle Handlungsaufforderungen stehen bereits auf
 * der Karte; hier gibt es dieselben Informationen mit Zertifikatsdaten.
 */
public class PublicationStatusView extends JDialog {

    private static final Color OK = new Color(0x2e7d32);
    private static final Color WARN = new Color(0xed6c02);
    private static final Color MUTED = new Color(0x757575);

    private final JPanel rows = new JPanel();
    private final WebServerConfiguration configuration;
    private final Site site;
    private final boolean serverRunning;
    private final WebServerController controller;

    private PublicationStatusView(Window owner, WebServerConfiguration configuration, Site site,
                                  boolean serverRunning, WebServerController controller) {
        super(owner, site.host().value(), ModalityType.MODELESS);
        this.configuration = configuration;
        this.site = site;
        this.serverRunning = serverRunning;
        this.controller = controller;

        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        rows.setBorder(BorderFactory.createEmptyBorder(14, 18, 8, 18));

        JButton checkAgain = new JButton("Check again");
        checkAgain.addActionListener(e -> refresh(checkAgain));
        JButton close = new JButton("Close");
        close.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        if (site.managedApplication().isPresent()) {
            JButton start = new JButton("Start JAR");
            start.addActionListener(e -> perform(() -> controller.startManaged(site), checkAgain));
            JButton stop = new JButton("Stop JAR");
            stop.addActionListener(e -> perform(() -> controller.stopManaged(site), checkAgain));
            JButton restart = new JButton("Restart JAR");
            restart.addActionListener(e -> perform(() -> controller.restartManaged(site), checkAgain));
            JButton logs = new JButton("Logs");
            logs.addActionListener(e -> showLogs());
            buttons.add(start);
            buttons.add(stop);
            buttons.add(restart);
            buttons.add(logs);
        }
        buttons.add(checkAgain);
        buttons.add(close);

        getContentPane().add(rows, BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);
        setMinimumSize(new java.awt.Dimension(480, 280));
        refresh(checkAgain);
        pack();
        setLocationRelativeTo(owner);
    }

    private void refresh(JButton checkAgain) {
        checkAgain.setEnabled(false);
        rows.removeAll();
        rows.add(line(MUTED, "Checking…"));
        rows.revalidate();
        rows.repaint();

        new SwingWorker<PublicationStatus, Void>() {
            @Override
            protected PublicationStatus doInBackground() {
                return new PublicationStatusPresenter().probe(configuration, site, serverRunning);
            }

            @Override
            protected void done() {
                checkAgain.setEnabled(true);
                try {
                    render(get());
                } catch (Exception e) {
                    rows.removeAll();
                    rows.add(line(WARN, "The check could not be completed."));
                    rows.revalidate();
                }
            }
        }.execute();
    }

    private void render(PublicationStatus status) {
        rows.removeAll();

        JLabel headline = new JLabel(status.headline());
        headline.setFont(headline.getFont().deriveFont(Font.BOLD, headline.getFont().getSize() + 2f));
        headline.setForeground(status.overall() == PublicationStatus.Overall.ACTION_REQUIRED ? WARN
                : status.overall() == PublicationStatus.Overall.LIVE ? OK : MUTED);
        rows.add(headline);
        rows.add(javax.swing.Box.createVerticalStrut(6));

        if (status.actionText() != null) {
            for (String textLine : status.actionText().split("\n")) {
                rows.add(line(MUTED, textLine));
            }
            rows.add(javax.swing.Box.createVerticalStrut(8));
        }
        if (status.dnsRecord() != null) {
            rows.add(line(MUTED, "Type: CNAME    Hostname: " + status.dnsRecord().hostname()
                    + "    Target: " + status.dnsRecord().target()));
            rows.add(javax.swing.Box.createVerticalStrut(8));
        }

        for (PublicationStatus.SubStatus sub : status.subStatuses()) {
            Color color = switch (sub.state()) {
                case OK -> OK;
                case WARN -> WARN;
                case PENDING, OFF -> MUTED;
            };
            rows.add(line(color, symbol(sub.state()) + "  " + sub.label() + " — " + sub.detail()));
        }

        if (site.managedApplication().isPresent()) {
            ManagedProcessManager.ProcessStatus process = controller.managedStatus(site);
            rows.add(javax.swing.Box.createVerticalStrut(8));
            rows.add(line(MUTED, "Managed application: " + process.state()));
            if (process.pid() > 0) rows.add(line(MUTED, "PID: " + process.pid()));
            rows.add(line(MUTED, "Restart failures: " + process.failures()));
            if (process.startedAt() != null)
                rows.add(line(MUTED, "Started: " + process.startedAt()));
            if (process.lastExitCode() != null)
                rows.add(line(MUTED, "Last exit code: " + process.lastExitCode()));
            if (process.nextStart() != null)
                rows.add(line(MUTED, "Next restart: " + process.nextStart()));
        }
        if (status.certificate() != null) {
            SimpleDateFormat format = new SimpleDateFormat("dd.MM.yyyy");
            rows.add(javax.swing.Box.createVerticalStrut(8));
            rows.add(line(MUTED, "Certificate issued " + format.format(status.certificate().issued())
                    + " · valid until " + format.format(status.certificate().expires())
                    + " · renews automatically"));
        }

        rows.revalidate();
        rows.repaint();
        pack();
    }

    private void perform(Runnable operation, JButton refreshButton) {
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                operation.run();
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    refresh(refreshButton);
                } catch (Exception e) {
                    JOptionPane.showMessageDialog(PublicationStatusView.this,
                            "Could not manage process: " + e.getMessage(),
                            "Managed application", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void showLogs() {
        String id = site.managedApplication().orElseThrow().id();
        Path file = controller.directories().logs().resolve("services").resolve(id + ".log");
        try {
            if (!Files.exists(file)) {
                JOptionPane.showMessageDialog(this, "No application log is available yet.");
                return;
            }
            // Display only the latest log content to avoid blocking the user interface.
            String content = Files.readString(file);
            if (content.length() > 30000) {
                content = content.substring(content.length() - 30000);
            }
            javax.swing.JTextArea area = new javax.swing.JTextArea(content, 22, 88);
            area.setEditable(false);
            area.setCaretPosition(area.getDocument().getLength());
            JOptionPane.showMessageDialog(this, new javax.swing.JScrollPane(area),
                    "Application logs", JOptionPane.PLAIN_MESSAGE);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Cannot read application log.");
        }
    }

    private static String symbol(PublicationStatus.SubState state) {
        return switch (state) {
            case OK -> "✓";
            case WARN -> "⚠";
            case PENDING -> "◌";
            case OFF -> "○";
        };
    }

    private static JLabel line(Color color, String text) {
        JLabel label = new JLabel(text);
        label.setForeground(color);
        label.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        return label;
    }

    public static void open(Window owner, WebServerConfiguration configuration, Site site,
                            boolean serverRunning, WebServerController controller) {
        new PublicationStatusView(owner, configuration, site, serverRunning, controller).setVisible(true);
    }
}
