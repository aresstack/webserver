package com.aresstack.webserver.ui.publication;

import com.aresstack.webserver.ui.AppPreferences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Der Dienstname steht oben und fuellt die oeffentliche Adresse.
 *
 * <p>Vorher musste man {@code pyloros.aresstack.de} jedes Mal vollstaendig tippen,
 * obwohl die Domain fuer alle Dienste dieselbe ist. Jetzt wie in einer IDE: oben den
 * Namen eingeben, der Rest ergibt sich - und wer die Adresse selbst anfasst, behaelt
 * sie.
 */
class AddressSuggestionTest {

    private String previousBaseDomain;

    @BeforeEach
    void rememberPreference() {
        previousBaseDomain = AppPreferences.baseDomain();
        AppPreferences.setBaseDomain("aresstack.de");
    }

    @AfterEach
    void restorePreference() {
        AppPreferences.setBaseDomain(previousBaseDomain);
    }

    @Test
    void theNameFillsTheAddressWithTheConfiguredDomain() throws Exception {
        Editor editor = editor();

        type(editor.name, "pyloros");

        assertEquals("pyloros.aresstack.de", editor.address.getText());
    }

    @Test
    void theAddressFollowsEveryChangeOfTheName() throws Exception {
        Editor editor = editor();

        type(editor.name, "pyloros");
        type(editor.name, "argos");

        assertEquals("argos.aresstack.de", editor.address.getText());
    }

    @Test
    void anEmptyNameLeavesTheAddressEmpty() throws Exception {
        Editor editor = editor();

        type(editor.name, "argos");
        type(editor.name, "");

        assertEquals("", editor.address.getText());
    }

    @Test
    void aHandEditedAddressIsNotOverwritten() throws Exception {
        Editor editor = editor();
        type(editor.name, "pyloros");

        // Wie ein Tastendruck im Adressfeld: ab hier gehoert es dem Benutzer.
        onSwing(() -> editor.address.getKeyListeners()[0].keyTyped(
                new java.awt.event.KeyEvent(editor.address,
                        java.awt.event.KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0,
                        java.awt.event.KeyEvent.VK_UNDEFINED, 'x')));
        type(editor.address, "intern.example.com");
        type(editor.name, "etwas-anderes");

        assertEquals("intern.example.com", editor.address.getText(),
                "eine selbst gesetzte Adresse darf der Name nicht wieder ueberschreiben");
    }

    @Test
    void aNameWithoutAConfiguredDomainStaysBare() throws Exception {
        AppPreferences.setBaseDomain("");
        Editor editor = editor();

        type(editor.name, "pyloros");

        assertEquals("pyloros", editor.address.getText());
    }

    @Test
    void theNameFieldSitsAboveTheAddressField() throws Exception {
        Editor editor = editor();

        assertTrue(editor.nameRow < editor.addressRow,
                "der Name wird zuerst ausgefuellt, also steht er oben");
    }

    /** Zugriff auf die beiden Felder und ihre Position im Formular. */
    private record Editor(JTextField name, JTextField address, int nameRow, int addressRow) {
    }

    private Editor editor() throws Exception {
        AtomicReference<Editor> created = new AtomicReference<>();
        onSwing(() -> {
            try {
                PublicationEditor dialog = newEditor();
                JTextField name = field(dialog, "nameField");
                JTextField address = field(dialog, "addressField");
                java.util.List<java.awt.Component> order = new java.util.ArrayList<>();
                collect(dialog.getContentPane(), order);
                created.set(new Editor(name, address, order.indexOf(name), order.indexOf(address)));
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        });
        return created.get();
    }

    private static PublicationEditor newEditor() throws Exception {
        var constructor = PublicationEditor.class.getDeclaredConstructor(
                java.awt.Window.class, com.aresstack.webserver.domain.Site.class,
                com.aresstack.webserver.domain.Upstream.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(null, null,
                com.aresstack.webserver.domain.Upstream.parse("http://127.0.0.1:8080"), null);
    }

    private static JTextField field(PublicationEditor dialog, String name) throws Exception {
        Field field = PublicationEditor.class.getDeclaredField(name);
        field.setAccessible(true);
        return (JTextField) field.get(dialog);
    }

    private static void collect(java.awt.Container container, java.util.List<java.awt.Component> into) {
        for (java.awt.Component child : container.getComponents()) {
            into.add(child);
            if (child instanceof java.awt.Container nested) {
                collect(nested, into);
            }
        }
    }

    private static void type(JTextField field, String text) throws Exception {
        onSwing(() -> field.setText(text));
    }

    private static void onSwing(Runnable action) throws Exception {
        SwingUtilities.invokeAndWait(action);
    }
}
