package com.aresstack.webserver.ui.publication;

import com.aresstack.webserver.domain.DerivedEnvironment;
import com.aresstack.webserver.domain.ManagedApplication;
import com.aresstack.webserver.domain.ManagedEnvironmentVariable;
import com.aresstack.webserver.domain.ReadinessPolicy;
import com.aresstack.webserver.domain.RestartPolicy;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import javax.swing.table.TableModel;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Abgeleitete Umgebungsvariablen sind sichtbar, aber nicht von Hand pflegbar.
 *
 * <p>Port, Adresse, Modus, Issuer und Dienst-Scope stehen schon in den
 * Service-Einstellungen. Sie dort und hier zu pflegen, war doppelte Arbeit und eine
 * stille Fehlerquelle. Sie bleiben sichtbar, damit nachvollziehbar ist, was der
 * Prozess bekommt - aber Name und Wert sind gesperrt, und abschalten laesst sich
 * eine Variable ueber Enabled.
 */
class ManagedApplicationPanelDerivedTest {

    @Test
    void showsDerivedRowsLockedAndCustomRowsEditable() throws Exception {
        ManagedApplicationPanel panel = panel(Map.of(
                "MY_OWN", new ManagedEnvironmentVariable("value", false, true)));
        onSwing(() -> panel.showDerived(Map.of(
                DerivedEnvironment.SERVER_PORT, "8081",
                DerivedEnvironment.PUBLIC_ORIGIN, "https://pyloros.aresstack.de")));

        TableModel model = model(panel);
        assertEquals(DerivedEnvironment.SERVER_PORT,
                model.getValueAt(0, ManagedApplicationPanel.COLUMN_NAME),
                "abgeleitete Zeilen stehen oben");
        assertFalse(model.isCellEditable(0, ManagedApplicationPanel.COLUMN_NAME));
        assertFalse(model.isCellEditable(0, ManagedApplicationPanel.COLUMN_VALUE));
        assertTrue(model.isCellEditable(0, ManagedApplicationPanel.COLUMN_ENABLED),
                "Enabled bleibt bedienbar");

        int custom = rowOf(model, "MY_OWN");
        assertTrue(model.isCellEditable(custom, ManagedApplicationPanel.COLUMN_NAME));
        assertTrue(model.isCellEditable(custom, ManagedApplicationPanel.COLUMN_VALUE));
    }

    @Test
    void refreshingKeepsTheEnabledDecisionAndTheCustomRows() throws Exception {
        ManagedApplicationPanel panel = panel(Map.of(
                "MY_OWN", new ManagedEnvironmentVariable("value", false, true)));
        onSwing(() -> panel.showDerived(
                Map.of(DerivedEnvironment.SERVER_PORT, "8081")));
        TableModel model = model(panel);
        onSwing(() -> model.setValueAt(Boolean.FALSE, rowOf(model, DerivedEnvironment.SERVER_PORT),
                ManagedApplicationPanel.COLUMN_ENABLED));

        // Der Port wird im Service geaendert: neuer Wert, gleiche Entscheidung.
        onSwing(() -> panel.showDerived(
                Map.of(DerivedEnvironment.SERVER_PORT, "9090")));

        int row = rowOf(model, DerivedEnvironment.SERVER_PORT);
        assertEquals("9090", model.getValueAt(row, ManagedApplicationPanel.COLUMN_VALUE));
        assertEquals(Boolean.FALSE, model.getValueAt(row, ManagedApplicationPanel.COLUMN_ENABLED),
                "eine abgeschaltete Variable darf durch das Auffrischen nicht zurueckkehren");
        assertTrue(rowOf(model, "MY_OWN") >= 0, "eigene Variablen bleiben erhalten");
    }

    @Test
    void aDerivedNameThatNoLongerAppliesDisappears() throws Exception {
        ManagedApplicationPanel panel = panel(Map.of());
        onSwing(() -> panel.showDerived(Map.of(
                DerivedEnvironment.SERVER_PORT, "8081",
                DerivedEnvironment.CENTRAL_OAUTH_ISSUER, "https://pyloros.aresstack.de")));

        // Central OAuth abgeschaltet: der Issuer gilt nicht mehr.
        onSwing(() -> panel.showDerived(Map.of(DerivedEnvironment.SERVER_PORT, "8081")));

        TableModel model = model(panel);
        assertTrue(rowOf(model, DerivedEnvironment.CENTRAL_OAUTH_ISSUER) < 0,
                "ein nicht mehr geltender Wert darf nicht als Altlast stehen bleiben");
    }

    @Test
    void theEnabledDecisionReachesTheSavedApplication() throws Exception {
        ManagedApplicationPanel panel = panel(Map.of());
        onSwing(() -> panel.showDerived(Map.of(
                DerivedEnvironment.SERVER_PORT, "8081",
                DerivedEnvironment.PUBLIC_ORIGIN, "https://pyloros.aresstack.de")));
        TableModel model = model(panel);
        onSwing(() -> model.setValueAt(Boolean.FALSE,
                rowOf(model, DerivedEnvironment.PUBLIC_ORIGIN),
                ManagedApplicationPanel.COLUMN_ENABLED));

        AtomicReference<Optional<ManagedApplication>> saved = new AtomicReference<>();
        onSwing(() -> saved.set(panel.value()));

        Map<String, ManagedEnvironmentVariable> environment =
                saved.get().orElseThrow().environment();
        assertTrue(environment.get(DerivedEnvironment.SERVER_PORT).enabled());
        assertFalse(environment.get(DerivedEnvironment.PUBLIC_ORIGIN).enabled());
    }

    private static int rowOf(TableModel model, String name) {
        for (int row = 0; row < model.getRowCount(); row++) {
            if (name.equals(model.getValueAt(row, ManagedApplicationPanel.COLUMN_NAME))) {
                return row;
            }
        }
        return -1;
    }

    private static TableModel model(ManagedApplicationPanel panel) throws Exception {
        java.lang.reflect.Field field =
                ManagedApplicationPanel.class.getDeclaredField("variables");
        field.setAccessible(true);
        return (TableModel) field.get(panel);
    }

    private static ManagedApplicationPanel panel(
            Map<String, ManagedEnvironmentVariable> environment) throws Exception {
        AtomicReference<ManagedApplicationPanel> created = new AtomicReference<>();
        Map<String, ManagedEnvironmentVariable> copy = new LinkedHashMap<>(environment);
        onSwing(() -> {
            ManagedApplicationPanel panel = new ManagedApplicationPanel(Optional.of(
                    new ManagedApplication("pyloros", "app.jar",
                            java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java")
                                    .toString(),
                            "", List.of(), List.of(), copy, false, false,
                            RestartPolicy.defaults(), ReadinessPolicy.defaults(), 5, 5,
                            Boolean.TRUE)));
            panel.environmentTab();
            created.set(panel);
        });
        return created.get();
    }

    private static void onSwing(Runnable action) throws Exception {
        SwingUtilities.invokeAndWait(action);
    }
}
