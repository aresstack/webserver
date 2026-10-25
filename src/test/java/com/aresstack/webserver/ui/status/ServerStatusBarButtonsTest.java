package com.aresstack.webserver.ui.status;

import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Die Statusleiste bietet Logs, DynDNS und Einstellungen - in dieser Reihenfolge.
 *
 * <p>Dynamic DNS hatte vorher einen Block in den Server-Einstellungen. Wer es nicht
 * benutzt - etwa weil der Router das Update macht - musste sich trotzdem an fuenf
 * Feldern vorbeiscrollen.
 */
class ServerStatusBarButtonsTest {

    @Test
    void theDynDnsButtonSitsBetweenLogsAndSettings() throws Exception {
        List<JButton> buttons = buttonsOf(bar(new AtomicInteger(), new AtomicInteger(),
                new AtomicInteger()));

        assertEquals(List.of("Logs", "DynDNS", "⚙ Settings"),
                buttons.stream().map(JButton::getText).toList());
    }

    @Test
    void eachButtonTriggersItsOwnAction() throws Exception {
        AtomicInteger logs = new AtomicInteger();
        AtomicInteger dynDns = new AtomicInteger();
        AtomicInteger settings = new AtomicInteger();
        List<JButton> buttons = buttonsOf(bar(logs, dynDns, settings));

        for (JButton button : buttons) {
            SwingUtilities.invokeAndWait(button::doClick);
        }

        assertEquals(1, logs.get(), "Logs");
        assertEquals(1, dynDns.get(), "DynDNS");
        assertEquals(1, settings.get(), "Settings");
    }

    @Test
    void theDynDnsButtonDoesNotRunAnythingOnItsOwn() throws Exception {
        AtomicInteger dynDns = new AtomicInteger();
        bar(new AtomicInteger(), dynDns, new AtomicInteger());

        assertEquals(0, dynDns.get(), "erst der Klick oeffnet den Dialog");
    }

    private static ServerStatusBar bar(AtomicInteger logs, AtomicInteger dynDns,
                                       AtomicInteger settings) throws Exception {
        AtomicReference<ServerStatusBar> created = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> created.set(new ServerStatusBar(
                logs::incrementAndGet, dynDns::incrementAndGet, settings::incrementAndGet)));
        return created.get();
    }

    private static List<JButton> buttonsOf(Container container) {
        List<JButton> found = new ArrayList<>();
        collect(container, found);
        assertTrue(found.size() >= 3, "die Leiste hat mindestens drei Schaltflaechen");
        return found;
    }

    private static void collect(Container container, List<JButton> into) {
        for (Component child : container.getComponents()) {
            if (child instanceof JButton button) {
                into.add(button);
            }
            if (child instanceof Container nested) {
                collect(nested, into);
            }
        }
    }
}
