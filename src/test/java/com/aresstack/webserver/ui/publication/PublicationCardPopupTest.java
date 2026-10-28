package com.aresstack.webserver.ui.publication;

import com.aresstack.webserver.domain.DomainName;
import com.aresstack.webserver.domain.ManagedProcessStatus;
import com.aresstack.webserver.domain.Site;
import com.aresstack.webserver.domain.Upstream;
import com.aresstack.webserver.ui.status.PublicationStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Die Karten werden bei jeder Statusabfrage ersetzt. Ein Button, der dabei gerade
 * gedrueckt ist, darf nach dem Entfernen nichts mehr ausloesen - vorher oeffnete der
 * Fokusverlust das Menue einer unsichtbaren Karte und warf auf dem EDT eine
 * IllegalComponentStateException.
 */
class PublicationCardPopupTest {

    private final List<String> fired = new ArrayList<>();
    private JFrame frame;

    @BeforeEach
    void requireDisplay() {
        org.junit.jupiter.api.Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "the card needs a real window to be added and removed");
    }

    @AfterEach
    void closeFrame() throws Exception {
        if (frame != null) {
            SwingUtilities.invokeAndWait(frame::dispose);
        }
    }

    @Test
    void aPressedButtonOfARemovedCardTriggersNothing() throws Exception {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            PublicationCard card = card();
            frame = new JFrame();
            frame.getContentPane().add(card);
            frame.pack();
            JButton more = button(card, "⋮");
            JButton edit = button(card, "Edit");
            press(more);
            press(edit);

            frame.getContentPane().remove(card);
            try {
                loseFocus(more);
                loseFocus(edit);
            } catch (Throwable failure) {
                thrown.set(failure);
            }
        });

        assertEquals(null, thrown.get(), "a late focus loss must not reach the popup");
        assertEquals(List.of(), fired, "a removed card must not trigger its actions");
    }

    @Test
    void theMenuStillOpensOnAVisibleCard() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            PublicationCard card = card();
            frame = new JFrame();
            frame.getContentPane().add(card);
            frame.pack();
            frame.setVisible(true);
        });
        SwingUtilities.invokeAndWait(() -> button(
                (Container) frame.getContentPane().getComponent(0), "⋮").doClick());

        AtomicReference<Boolean> open = new AtomicReference<>(false);
        SwingUtilities.invokeAndWait(() -> {
            for (Window window : Window.getWindows()) {
                open.set(open.get() || showingPopup(window));
            }
        });
        assertTrue(open.get(), "the ordinary click must keep opening the menu");
    }

    private PublicationCard card() {
        Site site = new Site(new DomainName("argos.example.org"),
                Optional.of(Upstream.parse("http://127.0.0.1:8085")), List.of(), true);
        return new PublicationCard(site, Upstream.parse("http://127.0.0.1:8080"),
                PublicationStatus.checking(), ManagedProcessStatus.stopped(), false,
                new PublicationCard.CardActions() {
                    @Override
                    public void edit() {
                        fired.add("edit");
                    }

                    @Override
                    public void remove() {
                        fired.add("remove");
                    }

                    @Override
                    public void details() {
                        fired.add("details");
                    }

                    @Override
                    public void checkAgain() {
                        fired.add("checkAgain");
                    }

                    @Override
                    public void startManaged() {
                        fired.add("start");
                    }

                    @Override
                    public void stopManaged() {
                        fired.add("stop");
                    }

                    @Override
                    public void restartManaged() {
                        fired.add("restart");
                    }
                });
    }

    /** Maus unten auf dem Button, aber noch nicht losgelassen. */
    private static void press(AbstractButton button) {
        button.getModel().setArmed(true);
        button.getModel().setPressed(true);
    }

    /** Der Fokusverlust, den das Entfernen der Karte nachtraeglich ausloest. */
    private static void loseFocus(Component component) {
        FocusEvent lost = new FocusEvent(component, FocusEvent.FOCUS_LOST);
        for (FocusListener listener : component.getFocusListeners()) {
            listener.focusLost(lost);
        }
    }

    private static JButton button(Container container, String text) {
        for (Component child : container.getComponents()) {
            if (child instanceof JButton button && text.equals(button.getText())) {
                return button;
            }
            if (child instanceof Container nested) {
                JButton found = buttonOrNull(nested, text);
                if (found != null) {
                    return found;
                }
            }
        }
        throw new AssertionError("no button " + text);
    }

    private static JButton buttonOrNull(Container container, String text) {
        try {
            return button(container, text);
        } catch (AssertionError missing) {
            return null;
        }
    }

    private static boolean showingPopup(Container container) {
        for (Component child : container.getComponents()) {
            if (child instanceof JPopupMenu popup && popup.isShowing()) {
                return true;
            }
            if (child instanceof Container nested && showingPopup(nested)) {
                return true;
            }
        }
        return false;
    }
}
