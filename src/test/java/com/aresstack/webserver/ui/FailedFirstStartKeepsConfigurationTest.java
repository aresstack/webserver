package com.aresstack.webserver.ui;

import com.aresstack.webserver.domain.DomainName;
import com.aresstack.webserver.domain.ManagedApplication;
import com.aresstack.webserver.domain.ReadinessPolicy;
import com.aresstack.webserver.domain.RestartPolicy;
import com.aresstack.webserver.domain.Site;
import com.aresstack.webserver.domain.Upstream;
import com.aresstack.webserver.infrastructure.caddy.RuntimeDirectories;
import com.aresstack.webserver.infrastructure.configuration.JsonConfigurationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Eine Anwendung, die nicht startet, darf nicht die Konfiguration mitreissen.
 *
 * <p>Wurde eine neu veroeffentlichte Anwendung nicht rechtzeitig bereit, rollte der
 * Controller die <em>gesamte</em> Konfiguration zurueck: jede Eingabe des
 * Publish-Dialogs war weg, obwohl meist nur das JAR nicht startete - etwa weil es
 * ohne seine Abhaengigkeiten gebaut wurde. Dazu meldete die Oberflaeche nur "did not
 * become ready within 30 seconds" und verschwieg die Ausgabe des Kindprozesses, in
 * der der Grund wortwoertlich stand.
 */
@EnabledOnOs(OS.WINDOWS)
class FailedFirstStartKeepsConfigurationTest {

    private static final DomainName HOST = new DomainName("argos.example.org");

    private Path root;
    private WebServerController running;

    @BeforeEach
    void createRoot() throws Exception {
        root = Files.createTempDirectory("webserver-failed-start");
    }

    @AfterEach
    void removeRoot() {
        if (running != null) {
            try {
                running.stop();
            } catch (RuntimeException alreadyDown) {
                // Der Test darf nicht am Aufraeumen scheitern.
            }
        }
        if (root == null) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (java.io.IOException stillLocked) {
                    // Ein gerade beendeter Prozess kann die Datei noch halten.
                }
            });
        } catch (java.io.IOException ignored) {
            // Ein liegengebliebenes Temp-Verzeichnis darf den Test nicht kippen.
        }
    }

    @Test
    void theSiteSurvivesAndOnlyTheApplicationIsSwitchedOff() throws Exception {
        // Caddys Admin-Port ist eine feste Konstante. Laeuft auf dieser Maschine eine
        // echte Instanz, darf der Test sie nicht anfassen - dann wird uebersprungen.
        org.junit.jupiter.api.Assumptions.assumeTrue(adminPortIsFree(),
                "a real webserver instance is running on this machine");
        WebServerController controller = controller();
        // Die Readiness-Pruefung greift nur im laufenden Server; eigene hohe Ports,
        // damit der Test nichts Produktives anfasst.
        controller.updatePorts(freePort(), freePort());
        controller.start();
        running = controller;

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> controller.addSite(siteWithBrokenApplication()));

        // 1. Die Eingabe ist nicht verloren.
        Site persisted = reload();
        assertEquals(HOST, persisted.host());
        assertEquals(Optional.of(Upstream.parse("http://127.0.0.1:8185")), persisted.upstream());

        // 2. Die Anwendung ist abgeschaltet, damit die Absturzschleife endet.
        assertFalse(persisted.managedApplication().orElseThrow().isEnabled(),
                "eine Anwendung, die nicht hochkommt, muss abgeschaltet werden");

        // 3. Die Meldung sagt, dass nichts verloren ist, und nennt den echten Grund.
        assertTrue(failure.getMessage().contains("Your settings are kept"),
                failure.getMessage());
        assertTrue(failure.getMessage().contains("Last output of the application"),
                "die Ausgabe des Kindprozesses gehoert in die Meldung: "
                        + failure.getMessage());
        assertTrue(failure.getMessage().toLowerCase().contains("jar"),
                "der Grund des Absturzes muss sichtbar sein: " + failure.getMessage());
    }

    private static boolean adminPortIsFree() {
        try (java.net.Socket probe = new java.net.Socket()) {
            probe.connect(new java.net.InetSocketAddress("127.0.0.1", 29171), 300);
            return false;
        } catch (java.io.IOException nothingListening) {
            return true;
        }
    }

    private static int freePort() throws Exception {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0, 1,
                java.net.InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private Site siteWithBrokenApplication() throws Exception {
        // Ein JAR, das keines ist: der Kindprozess stirbt sofort und schreibt den
        // Grund nach stdout - genau wie ein ohne Abhaengigkeiten gebautes JAR.
        Path brokenJar = root.resolve("argos.jar");
        Files.writeString(brokenJar, "this is not a jar");
        ManagedApplication application = new ManagedApplication(
                "argos", brokenJar.toString(),
                Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "", List.of(), List.of(), Map.of(), true, true,
                RestartPolicy.defaults(),
                // Kurzer Timeout: der Test soll das Verhalten zeigen, nicht warten.
                new ReadinessPolicy(2, 200, 500, "", 200),
                5, 5, Boolean.TRUE);
        return new Site(HOST, Optional.of(Upstream.parse("http://127.0.0.1:8185")),
                List.of(), false, Optional.of(application));
    }

    private Site reload() {
        return new JsonConfigurationRepository(new RuntimeDirectories(root).configFile())
                .load().sites().stream()
                .filter(site -> site.host().equals(HOST))
                .findFirst().orElseThrow(() ->
                        new AssertionError("die veroeffentlichte Site wurde verworfen"));
    }

    private WebServerController controller() {
        RuntimeDirectories directories = new RuntimeDirectories(root);
        directories.ensureExist();
        try {
            Path executable = Path.of(System.getProperty("user.dir"), "runtime", "caddy", "bin",
                    "caddy.exe");
            assertTrue(Files.isRegularFile(executable),
                    "Windows CI must prepare the Caddy test binary");
            Files.createDirectories(directories.caddyBinary().getParent());
            if (!Files.exists(directories.caddyBinary())) {
                Files.copy(executable, directories.caddyBinary());
            }
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
        return new WebServerController(directories, new UserLog());
    }
}
