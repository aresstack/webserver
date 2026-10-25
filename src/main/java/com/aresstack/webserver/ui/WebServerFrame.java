package com.aresstack.webserver.ui;

import com.aresstack.webserver.domain.Site;
import com.aresstack.webserver.domain.WebServerConfiguration;
import com.aresstack.webserver.ui.publication.PublicationEditor;
import com.aresstack.webserver.ui.publication.PublicationStatusView;
import com.aresstack.webserver.ui.publication.PublicationsPanel;
import com.aresstack.webserver.ui.status.PublicationStatus;
import com.aresstack.webserver.ui.status.PublicationStatusPresenter;
import com.aresstack.webserver.ui.status.ServerStatusBar;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hauptfenster: Veröffentlichungen als Karten, Serverstatus oben, Ports und
 * Aktionen unten. Die UI spricht die Sprache des Benutzers ("publish this
 * service here"); Sites, Upstreams und ACME bleiben Implementierungsdetail.
 */
public class WebServerFrame extends JFrame {

    private static final Color RUNNING = new Color(0x2e7d32);
    private static final Color STOPPED = new Color(0xc62828);

    private final WebServerController controller;
    private final UserLog log;
    private final PublicationsPanel publications;
    private final ServerStatusBar statusBar;
    private final JLabel serverBadge = new JLabel();
    private final JButton startStop = new JButton();
    private final PublicationStatusPresenter presenter = new PublicationStatusPresenter();
    private final Map<String, PublicationStatus> statuses = new ConcurrentHashMap<>();

    public WebServerFrame(WebServerController controller, UserLog log) {
        super("AresStack Webserver");
        this.controller = controller;
        this.log = log;
        setDefaultCloseOperation(EXIT_ON_CLOSE);

        JLabel title = new JLabel("AresStack Webserver");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
        JPanel header = new JPanel(new BorderLayout());
        header.setBorder(BorderFactory.createEmptyBorder(12, 14, 8, 14));
        header.add(title, BorderLayout.WEST);
        JPanel headerRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        headerRight.setOpaque(false);
        startStop.addActionListener(e -> toggleServer());
        headerRight.add(serverBadge);
        headerRight.add(startStop);
        header.add(headerRight, BorderLayout.EAST);

        publications = new PublicationsPanel(new PublicationsPanel.Actions() {
            @Override
            public void add() {
                WebServerConfiguration config = controller.configuration();
                // Beim allerersten Service wird die Standarddomain vorgeschlagen.
                String suggestion = config.sites().isEmpty() ? config.domain().value() : null;
                Site site = PublicationEditor.open(WebServerFrame.this, null,
                        config.nextFreeUpstream(), suggestion);
                if (site != null) {
                    // Publish → speichern → Server starten → Zertifikat beschaffen.
                    runAsync("Publish service", () -> {
                        controller.addSite(site);
                        if (!controller.isRunning()) {
                            controller.start();
                        }
                    });
                }
            }

            @Override
            public void edit(Site site) {
                Site updated = PublicationEditor.open(WebServerFrame.this, site,
                        controller.configuration().defaultUpstream(), null);
                if (updated != null) {
                    runAsync("Edit service", () -> controller.updateSite(site.host(), updated));
                }
            }

            @Override
            public void remove(Site site) {
                int confirm = JOptionPane.showConfirmDialog(WebServerFrame.this,
                        "Remove " + site.host() + "?\n\n"
                                + "The address will no longer be served by this webserver.",
                        "Remove service", JOptionPane.OK_CANCEL_OPTION);
                if (confirm == JOptionPane.OK_OPTION) {
                    runAsync("Remove service", () -> controller.removeSite(site.host()));
                }
            }

            @Override
            public void details(Site site) {
                PublicationStatusView.open(WebServerFrame.this,
                        controller.configuration(), site, controller.isRunning(), controller);
            }

            @Override
            public void startManaged(Site site) {
                runAsync("Start application", () -> controller.startManaged(site));
            }

            @Override
            public void stopManaged(Site site) {
                runAsync("Stop application", () -> controller.stopManaged(site));
            }

            @Override
            public void restartManaged(Site site) {
                runAsync("Restart application", () -> controller.restartManaged(site));
            }

            @Override
            public void checkAgain() {
                refreshStatuses();
            }
        });

        statusBar = new ServerStatusBar(
                () -> LogsDialog.show(this, log,
                        controller.directories().logs().resolve("caddy.log")),
                () -> DynDnsDialog.show(this),
                () -> SettingsDialog.show(this, controller));

        getContentPane().add(header, BorderLayout.NORTH);
        getContentPane().add(publications, BorderLayout.CENTER);
        getContentPane().add(statusBar, BorderLayout.SOUTH);
        setPreferredSize(new Dimension(720, 540));
        pack();
        setLocationRelativeTo(null);

        controller.addListener(this::refresh);
        refresh();
        new Timer(10_000, e -> refreshStatuses()).start();
        refreshStatuses();

        // Optionaler DynDNS-Client: prüft alle 5 Minuten, ob sich die
        // öffentliche Adresse geändert hat; deaktiviert = keinerlei Aktivität.
        Timer dynDnsTimer = new Timer(300_000, e ->
                new Thread(() -> DynDnsClient.updateIfAddressChanged(log), "dyndns-update").start());
        dynDnsTimer.start();
        new Thread(() -> DynDnsClient.updateIfAddressChanged(log), "dyndns-update").start();
    }

    private void refresh() {
        boolean running = controller.isRunning();
        serverBadge.setText(running ? "● Server running" : "● Server stopped");
        serverBadge.setForeground(running ? RUNNING : STOPPED);
        startStop.setText(running ? "Stop" : "Start");

        WebServerConfiguration config = controller.configuration();
        // Ohne veröffentlichte Services gibt es nichts zu starten.
        startStop.setEnabled(running || !config.sites().isEmpty());
        publications.update(config, statuses, controller::managedStatus, running);
        statusBar.update(running, config.httpPort(), config.httpsPort(), config.sites().size());
    }

    private void refreshStatuses() {
        WebServerConfiguration config = controller.configuration();
        boolean running = controller.isRunning();
        new SwingWorker<Map<String, PublicationStatus>, Void>() {
            @Override
            protected Map<String, PublicationStatus> doInBackground() {
                Map<String, PublicationStatus> result = new HashMap<>();
                for (Site site : config.sites()) {
                    result.put(site.host().value(), presenter.probe(config, site, running));
                }
                return result;
            }

            @Override
            protected void done() {
                try {
                    statuses.putAll(get());
                    refresh();
                } catch (Exception ignored) {
                }
            }
        }.execute();
    }

    private void toggleServer() {
        boolean running = controller.isRunning();
        runAsync(running ? "Stop server" : "Start server",
                running ? controller::stop : controller::start);
    }

    /** Serveroperationen laufen außerhalb des EDT; Fehler werden verständlich gemeldet. */
    private void runAsync(String title, Runnable action) {
        setEnabledRecursively(getContentPane(), false);
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                action.run();
                return null;
            }

            @Override
            protected void done() {
                setEnabledRecursively(getContentPane(), true);
                try {
                    get();
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    FriendlyErrors.show(WebServerFrame.this, title, cause);
                }
                refresh();
                refreshStatuses();
            }
        }.execute();
    }

    private static void setEnabledRecursively(Component component, boolean enabled) {
        component.setEnabled(enabled);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                setEnabledRecursively(child, enabled);
            }
        }
    }
}
