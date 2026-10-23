package com.aresstack.webserver.ui.publication;

import com.aresstack.webserver.domain.ManagedApplication;
import com.aresstack.webserver.domain.ManagedEnvironmentVariable;
import org.junit.jupiter.api.Test;

import javax.swing.JTable;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedApplicationPanelSecretTest {

    @Test
    void removingTheSecretMarkingMustNeverStoreThePlaceholderAsValue() throws Exception {
        ManagedApplication original = ManagedApplication.defaults(
                        "secret-fixture", "C:/services/fixture/app.jar")
                .withEnvironment(Map.of("API_KEY",
                        new ManagedEnvironmentVariable("secret://stored-reference", true)));

        AtomicReference<ManagedApplicationPanel> panelRef = new AtomicReference<>();
        AtomicReference<JTable> tableRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            ManagedApplicationPanel panel = new ManagedApplicationPanel(Optional.of(original));
            panelRef.set(panel);
            tableRef.set(findTable(panel.environmentTab()));
        });
        JTable table = tableRef.get();
        assertNotNull(table);
        assertEquals("(unchanged)",
                table.getModel().getValueAt(0, ManagedApplicationPanel.COLUMN_VALUE));

        // Secret-Haken entfernen, ohne den Wert neu einzugeben.
        SwingUtilities.invokeAndWait(() -> table.getModel()
                .setValueAt(false, 0, ManagedApplicationPanel.COLUMN_SECRET));

        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() ->
                failure.set(assertThrows(IllegalArgumentException.class,
                        () -> panelRef.get().value())));
        assertTrue(failure.get().getMessage().contains("API_KEY"));
    }

    @Test
    void unchangedSecretKeepsItsStoredValue() throws Exception {
        ManagedApplication original = ManagedApplication.defaults(
                        "secret-keep", "C:/services/fixture/app.jar")
                .withEnvironment(Map.of("API_KEY",
                        new ManagedEnvironmentVariable("secret://stored-reference", true)));

        AtomicReference<ManagedApplication> value = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            ManagedApplicationPanel panel = new ManagedApplicationPanel(Optional.of(original));
            panel.environmentTab();
            value.set(panel.value().orElseThrow());
        });
        assertEquals("secret://stored-reference", value.get().environment().get("API_KEY").value());
        assertTrue(value.get().environment().get("API_KEY").secret());
    }

    private static JTable findTable(Component component) {
        if (component instanceof JTable table) {
            return table;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                JTable table = findTable(child);
                if (table != null) {
                    return table;
                }
            }
        }
        return null;
    }
}
