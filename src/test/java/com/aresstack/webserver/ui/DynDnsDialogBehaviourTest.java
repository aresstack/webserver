package com.aresstack.webserver.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Der Dialog nimmt dem Benutzer die Update-URL ab.
 *
 * <p>Sie von Hand zu tippen war die haeufigste Fehlerquelle - und eine, die erst
 * nach dem naechsten IP-Wechsel auffaellt. Anbieter waehlen genuegt; die Vorschau
 * zeigt den tatsaechlichen Aufruf, ohne das Passwort preiszugeben.
 */
class DynDnsDialogBehaviourTest {

    private String url;
    private String domain;
    private String user;
    private String providerId;
    private boolean wasEnabled;

    @BeforeEach
    void rememberPreferences() {
        url = AppPreferences.dynDnsUpdateUrl();
        domain = AppPreferences.dynDnsDomain();
        user = AppPreferences.dynDnsUsername();
        providerId = AppPreferences.dynDnsProvider();
        wasEnabled = AppPreferences.dynDnsEnabled();
        // Ohne den Hauptschalter bleibt die ganze Sektion deaktiviert - konfiguriert
        // wird sie ja nur, wenn man sie auch benutzen will.
        AppPreferences.setDynDnsEnabled(true);
        AppPreferences.setDynDnsUpdateUrl("");
        AppPreferences.setDynDnsDomain("pyloros.aresstack.de\nargos.aresstack.de");
        AppPreferences.setDynDnsUsername("angel");
        AppPreferences.setDynDnsProvider("custom");
    }

    @AfterEach
    void restorePreferences() {
        AppPreferences.setDynDnsUpdateUrl(url);
        AppPreferences.setDynDnsDomain(domain);
        AppPreferences.setDynDnsUsername(user);
        AppPreferences.setDynDnsProvider(providerId);
        AppPreferences.setDynDnsEnabled(wasEnabled);
    }

    @Test
    void choosingAProviderFillsItsUpdateUrl() throws Exception {
        Dialog dialog = dialog();

        select(dialog, DynDnsProvider.DESEC);

        assertEquals(DynDnsProvider.DESEC.updateUrl(), dialog.url.getText());
    }

    @Test
    void aKnownProviderLocksTheUrlAndCustomReleasesIt() throws Exception {
        Dialog dialog = dialog();

        select(dialog, DynDnsProvider.DYNU);
        assertFalse(dialog.url.isEditable(),
                "eine vorgegebene Adresse soll man nicht versehentlich zerschiessen");

        select(dialog, DynDnsProvider.CUSTOM);
        assertTrue(dialog.url.isEditable(), "mit Custom gehoert die Adresse dem Benutzer");
    }

    @Test
    void customDoesNotOverwriteAnEnteredUrl() throws Exception {
        Dialog dialog = dialog();
        onSwing(() -> dialog.url.setText("https://example.org/update?x=<domain>"));

        select(dialog, DynDnsProvider.CUSTOM);

        assertEquals("https://example.org/update?x=<domain>", dialog.url.getText());
    }

    @Test
    void duckDnsDisablesTheUsernameBecauseItHasNone() throws Exception {
        Dialog dialog = dialog();

        select(dialog, DynDnsProvider.DUCKDNS);
        assertFalse(dialog.username.isEnabled(), "Duck DNS kennt nur ein Token");

        select(dialog, DynDnsProvider.DESEC);
        assertTrue(dialog.username.isEnabled());
        assertTrue(dialog.usernameLabel.getText().contains("domain"),
                "deSEC erwartet die Domain als Benutzernamen: " + dialog.usernameLabel.getText());
    }

    @Test
    void thePreviewShowsTheRealCallButNeverThePassword() throws Exception {
        Dialog dialog = dialog();

        select(dialog, DynDnsProvider.DESEC);

        String preview = dialog.preview.getText();
        assertTrue(preview.contains("update.dedyn.io"), preview);
        assertTrue(preview.contains("pyloros.aresstack.de"),
                "der erste Name wird eingesetzt: " + preview);
        assertTrue(preview.contains("and 1 more"),
                "bei mehreren Namen muss das sichtbar sein: " + preview);
        assertFalse(preview.contains("<domain>"), preview);
    }

    @Test
    void anExistingUrlPreselectsItsProvider() throws Exception {
        AppPreferences.setDynDnsUpdateUrl(DynDnsProvider.DYNU.updateUrl());

        Dialog dialog = dialog();

        assertEquals(DynDnsProvider.DYNU, dialog.provider.getSelectedItem(),
                "sonst stuende der Dialog auf Custom, obwohl alles eingerichtet ist");
    }

    /** Die Felder, auf die es hier ankommt. */
    private record Dialog(JComboBox<?> provider, JTextField url, JTextField username,
                          JLabel usernameLabel, JLabel preview, JTextArea domains) {
    }

    private Dialog dialog() throws Exception {
        AtomicReference<Dialog> created = new AtomicReference<>();
        onSwing(() -> {
            try {
                var constructor = DynDnsDialog.class.getDeclaredConstructor(java.awt.Window.class);
                constructor.setAccessible(true);
                Object instance = constructor.newInstance((java.awt.Window) null);
                created.set(new Dialog(
                        (JComboBox<?>) read(instance, "provider"),
                        (JTextField) read(instance, "updateUrl"),
                        (JTextField) read(instance, "username"),
                        (JLabel) read(instance, "usernameLabel"),
                        (JLabel) read(instance, "preview"),
                        (JTextArea) read(instance, "domains")));
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

    private static void select(Dialog dialog, DynDnsProvider provider) throws Exception {
        onSwing(() -> dialog.provider.setSelectedItem(provider));
    }

    private static void onSwing(Runnable action) throws Exception {
        SwingUtilities.invokeAndWait(action);
    }
}
