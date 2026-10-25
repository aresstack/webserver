package com.aresstack.webserver.ui.status;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;

/**
 * Fußzeile: Ports und Anzahl der Veröffentlichungen links, Aktionen rechts.
 */
public class ServerStatusBar extends JPanel {

    private static final Color OK = new Color(0x2e7d32);
    private static final Color OFF = new Color(0x9e9e9e);

    private final JLabel httpLabel = new JLabel();
    private final JLabel httpsLabel = new JLabel();
    private final JLabel servicesLabel = new JLabel();

    public ServerStatusBar(Runnable onLogs, Runnable onDynDns, Runnable onSettings) {
        super(new BorderLayout());
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(0xdddddd)),
                BorderFactory.createEmptyBorder(8, 14, 8, 14)));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 18, 0));
        left.setOpaque(false);
        left.add(httpLabel);
        left.add(httpsLabel);
        left.add(servicesLabel);
        add(left, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        JButton logs = new JButton("Logs");
        logs.addActionListener(e -> onLogs.run());
        JButton dynDns = new JButton("DynDNS");
        dynDns.addActionListener(e -> onDynDns.run());
        JButton settings = new JButton("⚙ Settings");
        settings.addActionListener(e -> onSettings.run());
        right.add(logs);
        right.add(dynDns);
        right.add(settings);
        add(right, BorderLayout.EAST);
    }

    public void update(boolean running, int httpPort, int httpsPort, int services) {
        httpLabel.setText("● HTTP " + httpPort);
        httpLabel.setForeground(running ? OK : OFF);
        httpsLabel.setText("● HTTPS " + httpsPort);
        httpsLabel.setForeground(running ? OK : OFF);
        servicesLabel.setText(services + (services == 1 ? " service" : " services"));
    }
}
