package com.aresstack.webserver.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Der Anbieterwechsel darf das Layout nicht verschieben.
 *
 * <p>Jeder Anbieter hat einen unterschiedlich langen Hinweis. Weil dessen Hoehe frei
 * war, sprangen beim Wechsel alle Zeilen darunter - bei No-IP mit seinem langen Satz
 * am deutlichsten. Dazu waren die Umbruchbreiten der Hinweise breiter als die Spalte,
 * in der sie sassen, sodass der Text rechts abgeschnitten wurde.
 */
class DynDnsDialogLayoutTest {

    private String url;
    private String providerId;
    private boolean wasEnabled;

    @BeforeEach
    void rememberPreferences() {
        url = AppPreferences.dynDnsUpdateUrl();
        providerId = AppPreferences.dynDnsProvider();
        wasEnabled = AppPreferences.dynDnsEnabled();
        AppPreferences.setDynDnsEnabled(true);
        AppPreferences.setDynDnsUpdateUrl("");
        AppPreferences.setDynDnsProvider("custom");
    }

    @AfterEach
    void restorePreferences() {
        AppPreferences.setDynDnsUpdateUrl(url);
        AppPreferences.setDynDnsProvider(providerId);
        AppPreferences.setDynDnsEnabled(wasEnabled);
    }

    @Test
    void theDialogKeepsItsSizeForEveryProvider() throws Exception {
        Set<Dimension> sizes = new LinkedHashSet<>();
        List<String> seen = new ArrayList<>();

        for (DynDnsProvider candidate : DynDnsProvider.all()) {
            sizes.add(sizeWith(candidate));
            seen.add(candidate.displayName());
        }

        assertEquals(1, sizes.size(),
                "jeder Anbieter muss dieselbe Dialoggroesse ergeben, sonst springt das"
                        + " Fenster beim Wechseln - gemessen fuer " + seen + ": " + sizes);
    }

    @Test
    void theHintKeepsItsHeightSoNothingBelowMoves() throws Exception {
        Dialog dialog = dialog();
        Dimension before = dialog.hint.getPreferredSize();

        select(dialog, DynDnsProvider.NOIP);
        Dimension noip = dialog.hint.getPreferredSize();
        select(dialog, DynDnsProvider.DUCKDNS);
        Dimension duck = dialog.hint.getPreferredSize();

        assertEquals(before, noip, "No-IP hat den laengsten Hinweis");
        assertEquals(before, duck, "Duck DNS den kuerzesten");
    }

    @Test
    void theHintIsTallEnoughForTheLongestProviderText() throws Exception {
        Dialog dialog = dialog();
        JLabel probe = new JLabel();

        int tallest = 0;
        for (DynDnsProvider candidate : DynDnsProvider.all()) {
            probe.setText(dialog.hint.getText().replaceAll(">[^<]*<", ">"
                    + candidate.hint().replace("&", "&amp;").replace("<", "&lt;") + "<"));
            tallest = Math.max(tallest, probe.getPreferredSize().height);
        }

        assertTrue(dialog.hint.getPreferredSize().height >= tallest,
                "sonst wird der laengste Hinweis unten abgeschnitten");
    }

    @Test
    void everyWrappedTextUsesTheSameWidth() throws Exception {
        Dialog dialog = dialog();

        assertEquals(dialog.hint.getPreferredSize().width,
                dialog.preview.getPreferredSize().width,
                "drei verschiedene Breiten waren der Grund fuer den abgeschnittenen Text");
    }

    @Test
    void aLongProviderUrlDoesNotWidenTheDialog() throws Exception {
        Dimension withShortest = sizeWith(DynDnsProvider.CUSTOM);
        Dimension withLongest = sizeWith(DynDnsProvider.DUCKDNS);

        assertEquals(withShortest.width, withLongest.width,
                "die laengste Update-URL darf die Breite nicht bestimmen");
    }

    private Dimension sizeWith(DynDnsProvider candidate) throws Exception {
        AppPreferences.setDynDnsProvider(candidate.id());
        AppPreferences.setDynDnsUpdateUrl(candidate.updateUrl());
        Dialog dialog = dialog();
        AtomicReference<Dimension> size = new AtomicReference<>();
        onSwing(() -> size.set(dialog.window.getSize()));
        return size.get();
    }

    private record Dialog(JDialog window, JLabel hint, JLabel preview,
                          javax.swing.JComboBox<?> provider) {
    }

    private Dialog dialog() throws Exception {
        AtomicReference<Dialog> created = new AtomicReference<>();
        onSwing(() -> {
            try {
                var constructor = DynDnsDialog.class.getDeclaredConstructor(java.awt.Window.class);
                constructor.setAccessible(true);
                Object instance = constructor.newInstance((java.awt.Window) null);
                created.set(new Dialog((JDialog) instance,
                        (JLabel) read(instance, "hint"),
                        (JLabel) read(instance, "preview"),
                        (javax.swing.JComboBox<?>) read(instance, "provider")));
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        });
        return created.get();
    }

    private static Object read(Object dialog, String name) throws Exception {
        Field field = DynDnsDialog.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(dialog);
    }

    private static void select(Dialog dialog, DynDnsProvider candidate) throws Exception {
        onSwing(() -> dialog.provider.setSelectedItem(candidate));
    }

    private static void onSwing(Runnable action) throws Exception {
        SwingUtilities.invokeAndWait(action);
    }
}
