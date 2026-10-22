package com.aresstack.webserver.ui.publication;

import org.junit.jupiter.api.Test;

import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedApplicationPanelLayoutTest {

    @Test
    void argumentEditorsShowSeveralLinesAndAvoidHorizontalScrollbars() throws Exception {
        AtomicReference<JComponent> runtimeTab = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> runtimeTab.set(
                new ManagedApplicationPanel(Optional.empty()).runtimeTab()));

        List<JScrollPane> argumentEditors = new ArrayList<>();
        collectScrollPanes(runtimeTab.get(), argumentEditors);

        assertEquals(2, argumentEditors.size());
        for (JScrollPane scrollPane : argumentEditors) {
            JTextArea editor = (JTextArea) scrollPane.getViewport().getView();

            assertTrue(editor.getLineWrap());
            assertEquals(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                    scrollPane.getVerticalScrollBarPolicy());
            assertEquals(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER,
                    scrollPane.getHorizontalScrollBarPolicy());
            assertTrue(scrollPane.getPreferredSize().height
                    >= editor.getPreferredScrollableViewportSize().height + 4);
        }
    }

    private static void collectScrollPanes(Component component, List<JScrollPane> results) {
        if (component instanceof JScrollPane) {
            results.add((JScrollPane) component);
        }
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                collectScrollPanes(child, results);
            }
        }
    }
}
