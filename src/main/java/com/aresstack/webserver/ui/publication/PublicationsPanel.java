package com.aresstack.webserver.ui.publication;

import com.aresstack.webserver.domain.Site;
import com.aresstack.webserver.domain.WebServerConfiguration;
import com.aresstack.webserver.ui.status.PublicationStatus;
import com.aresstack.webserver.domain.ManagedProcessStatus;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import java.awt.BorderLayout;
import java.awt.Font;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Liste der Veröffentlichungen ("Published services") mit Add-Aktion.
 */
public class PublicationsPanel extends JPanel {

    public interface Actions {
        void add();

        void edit(Site site);

        void remove(Site site);

        void details(Site site);

        void checkAgain();

        void startManaged(Site site);

        void stopManaged(Site site);

        void restartManaged(Site site);
    }

    private final JPanel cards = new JPanel();
    private final Actions actions;

    public PublicationsPanel(Actions actions) {
        super(new BorderLayout(0, 8));
        this.actions = actions;
        setBorder(BorderFactory.createEmptyBorder(10, 14, 6, 14));

        JLabel heading = new JLabel("Published services");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, heading.getFont().getSize() + 1f));
        JButton add = new JButton("+ Publish service");
        add.addActionListener(e -> actions.add());
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(heading, BorderLayout.WEST);
        header.add(add, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);

        cards.setLayout(new BoxLayout(cards, BoxLayout.Y_AXIS));
        JPanel scrollContent = new JPanel(new BorderLayout());
        scrollContent.add(cards, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(scrollContent);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);
    }

    public void update(WebServerConfiguration configuration, Map<String, PublicationStatus> statuses,
                       java.util.function.Function<Site, ManagedProcessStatus> processStatuses,
                       boolean serverRunning) {
        cards.removeAll();
        for (Site site : configuration.sites()) {
            PublicationStatus status = statuses.getOrDefault(
                    site.host().value(), PublicationStatus.checking());
            cards.add(new PublicationCard(site, configuration.defaultUpstream(), status,
                    processStatuses.apply(site), serverRunning, new PublicationCard.CardActions() {
                        @Override
                        public void edit() {
                            actions.edit(site);
                        }

                        @Override
                        public void remove() {
                            actions.remove(site);
                        }

                        @Override
                        public void details() {
                            actions.details(site);
                        }

                        @Override
                        public void checkAgain() {
                            actions.checkAgain();
                        }

                        @Override
                        public void startManaged() { actions.startManaged(site); }

                        @Override
                        public void stopManaged() { actions.stopManaged(site); }

                        @Override
                        public void restartManaged() { actions.restartManaged(site); }
                    }));
        }
        if (configuration.sites().isEmpty()) {
            cards.add(emptyState());
        }
        cards.add(Box.createVerticalStrut(6));
        cards.revalidate();
        cards.repaint();
    }

    /** Erster Start: die Anwendung ist sofort bedienbar, kein Wizard. */
    private JPanel emptyState() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(70, 0, 40, 0));

        JLabel title = new JLabel("No services published yet");
        title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize() + 3f));
        title.setAlignmentX(CENTER_ALIGNMENT);
        JLabel hint = new JLabel("Publish a service to make it available over HTTPS.");
        hint.setAlignmentX(CENTER_ALIGNMENT);
        hint.setBorder(BorderFactory.createEmptyBorder(8, 0, 18, 0));
        JButton publish = new JButton("+ Publish service");
        publish.setAlignmentX(CENTER_ALIGNMENT);
        publish.addActionListener(e -> actions.add());

        panel.add(title);
        panel.add(hint);
        panel.add(publish);
        return panel;
    }
}
