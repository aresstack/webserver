package com.aresstack.webserver.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Die Server-Einstellungen stehen in einer Spalte, nicht in zentrierten Kaesten.
 *
 * <p>Authentication und Advanced waren eigene Panels mit eigenem Raster. Ihre
 * Beschriftungen begannen dadurch an anderer Stelle als die oberen, und das
 * umgebende BoxLayout zentrierte die Kaesten zusaetzlich - das Ergebnis sah aus wie
 * ein Formular mit drei verschiedenen Tabulatoren.
 */
@EnabledOnOs(OS.WINDOWS)
class SettingsDialogLayoutTest {

    private Path root;
    private boolean previousOauthDefault;

    @BeforeEach
    void prepare() throws Exception {
        root = Files.createTempDirectory("webserver-settings-layout");
        previousOauthDefault = AppPreferences.oauthForNewServices();
    }

    @AfterEach
    void cleanUp() {
        AppPreferences.setOauthForNewServices(previousOauthDefault);
        if (root == null) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (java.io.IOException locked) {
                    // Aufraeumen darf den Test nicht kippen.
                }
            });
        } catch (java.io.IOException ignored) {
            // dito
        }
    }

    @Test
    void everyLabelStartsAtTheSameIndent() throws Exception {
        JDialog dialog = dialog();
        // Aufgeklappt, damit auch die Portzeilen vermessen werden - unsichtbare
        // Komponenten laesst GridBag aus und sie behielten x = 0.
        SwingUtilities.invokeAndWait(advancedToggle(dialog)::doClick);

        Set<Integer> lefts = new LinkedHashSet<>();
        List<String> captions = new ArrayList<>();
        for (JLabel label : labels(dialog)) {
            if (!isCaption(label)) {
                continue;
            }
            lefts.add(label.getX());
            captions.add(label.getText());
        }

        assertTrue(captions.size() >= 5, "es muessen Beschriftungen gefunden werden: " + captions);
        assertEquals(1, lefts.size(),
                "alle Beschriftungen gehoeren auf dieselbe Einrueckung - " + captions
                        + " beginnen bei " + lefts);
    }

    /** Eine Beschriftung ist ein Label in Spalte 0 des Formularrasters. */
    private static boolean isCaption(JLabel label) {
        if (label.getText() == null || label.getText().startsWith("<html")
                || label.getText().isBlank()
                || !(label.getParent().getLayout() instanceof java.awt.GridBagLayout grid)) {
            return false;
        }
        return grid.getConstraints(label).gridx == 0;
    }

    @Test
    void advancedIsCollapsedAndOpensOnClick() throws Exception {
        JDialog dialog = dialog();
        JTextField httpPort = portField(dialog);

        assertFalse(httpPort.isVisible(), "Ports aendert man selten - also zugeklappt");

        JButton toggle = advancedToggle(dialog);
        SwingUtilities.invokeAndWait(toggle::doClick);
        assertTrue(httpPort.isVisible(), "ein Klick muss sie zeigen");

        SwingUtilities.invokeAndWait(toggle::doClick);
        assertFalse(httpPort.isVisible(), "und der naechste wieder verbergen");
    }

    @Test
    void theLabelOfACollapsedRowIsHiddenWithIt() throws Exception {
        JDialog dialog = dialog();

        boolean portCaptionVisible = labels(dialog).stream()
                .filter(label -> "HTTP port".equals(label.getText()))
                .anyMatch(Component::isVisible);

        assertFalse(portCaptionVisible,
                "sonst steht die Beschriftung ohne ihr Feld im Formular");
    }

    @Test
    void theOauthDefaultSitsNextToTheDomainAndIsOnByDefault() throws Exception {
        JDialog dialog = dialog();
        JCheckBox oauth = checkBoxes(dialog).stream()
                .filter(box -> box.getText().contains("Central OAuth"))
                .findFirst().orElseThrow(() ->
                        new AssertionError("die Checkbox fehlt"));

        assertTrue(oauth.isSelected(), "geschuetzt ist das sicherere Versehen");
        JLabel domainCaption = labels(dialog).stream()
                .filter(label -> "Domain for new services".equals(label.getText()))
                .findFirst().orElseThrow();
        assertEquals(domainCaption.getParent(), oauth.getParent().getParent(),
                "die Checkbox gehoert in die Zeile der Domain, nicht in einen eigenen Block");
    }

    private static JTextField portField(Container container) {
        List<JTextField> fields = new ArrayList<>();
        collect(container, JTextField.class, fields);
        // Die Portfelder sind die schmalsten; HTTP-Port ist das vorletzte.
        return fields.stream().filter(field -> "80".equals(field.getText()))
                .findFirst().orElseThrow(() -> new AssertionError("kein HTTP-Port-Feld"));
    }

    private static JButton advancedToggle(Container container) {
        List<JButton> buttons = new ArrayList<>();
        collect(container, JButton.class, buttons);
        return buttons.stream().filter(button -> button.getText().contains("Advanced"))
                .findFirst().orElseThrow(() -> new AssertionError("kein Advanced-Schalter"));
    }

    private static List<JLabel> labels(Container container) {
        List<JLabel> found = new ArrayList<>();
        collect(container, JLabel.class, found);
        return found;
    }

    private static List<JCheckBox> checkBoxes(Container container) {
        List<JCheckBox> found = new ArrayList<>();
        collect(container, JCheckBox.class, found);
        return found;
    }

    private static <T> void collect(Container container, Class<T> type, List<T> into) {
        for (Component child : container.getComponents()) {
            if (type.isInstance(child)) {
                into.add(type.cast(child));
            }
            if (child instanceof Container nested) {
                collect(nested, type, into);
            }
        }
    }

    private JDialog dialog() throws Exception {
        AtomicReference<JDialog> created = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                var directories =
                        new com.aresstack.webserver.infrastructure.caddy.RuntimeDirectories(root);
                directories.ensureExist();
                WebServerController controller = new WebServerController(directories, new UserLog());
                var constructor = SettingsDialog.class.getDeclaredConstructor(
                        java.awt.Window.class, WebServerController.class);
                constructor.setAccessible(true);
                JDialog dialog = (JDialog) constructor.newInstance(null, controller);
                // Sichtbar, weil getLocationOnScreen sonst nicht definiert ist.
                dialog.setVisible(true);
                created.set(dialog);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        });
        return created.get();
    }
}
