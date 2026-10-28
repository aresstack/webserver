package com.aresstack.webserver.ui;

import com.aresstack.webserver.domain.DomainName;
import com.aresstack.webserver.domain.ManagedApplication;
import com.aresstack.webserver.domain.ManagedEnvironmentVariable;
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

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Eine von Hand angelegte Umgebungsvariable muss das Speichern ueberleben -
 * im Controller-Zustand, aus dem der Dialog beim naechsten Oeffnen liest, und in
 * {@code webserver.json}. Auch dann, wenn die Anwendung mit den neuen
 * Einstellungen nicht hochkommt.
 */
@EnabledOnOs(OS.WINDOWS)
class ManagedEnvironmentPersistenceTest {

    private static final DomainName HOST = new DomainName("argos.example.org");
    private static final String BROWSER_DIR = "G:\\camoufox-bidi-smoke-20261025\\browser";

    private Path root;
    private WebServerController running;

    @BeforeEach
    void createRoot() throws java.io.IOException {
        root = Files.createTempDirectory("webserver-environment-test");
    }

    @AfterEach
    void removeRoot() {
        if (running != null) {
            try {
                running.stop();
            } catch (RuntimeException ignored) {
                // Aufraeumen darf den Test nicht kippen.
            }
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (java.io.IOException stillLocked) {
                    // A running caddy.exe can hold the file; nothing to do about it here.
                }
            });
        } catch (java.io.IOException ignored) {
            // Leaving a temp directory behind must not fail the test.
        }
    }

    @Test
    void aNewVariableSurvivesSaveAndReload() throws Exception {
        WebServerController controller = controller();
        controller.addSite(site(application(Map.of(), false), 8081));

        ManagedApplication stored = stored(controller);
        Map<String, ManagedEnvironmentVariable> edited = new LinkedHashMap<>(stored.environment());
        edited.put("BROWSER_DIR", new ManagedEnvironmentVariable(BROWSER_DIR, false, true));
        controller.updateSite(HOST, site(stored.withEnvironment(edited), 8081));

        assertNotNull(stored(controller).environment().get("BROWSER_DIR"),
                "the dialog reopens from the controller state");
        ManagedEnvironmentVariable persisted = reload().environment().get("BROWSER_DIR");
        assertNotNull(persisted, "the variable must reach webserver.json");
        assertEquals(BROWSER_DIR, persisted.value());
    }

    @Test
    void aSwitchedOffVariableStaysSwitchedOff() throws Exception {
        WebServerController controller = controller();
        controller.addSite(site(application(Map.of(
                "DEBUG", new ManagedEnvironmentVariable("1", false, false)), false), 8081));

        assertFalse(stored(controller).environment().get("DEBUG").enabled());
        assertFalse(reload().environment().get("DEBUG").enabled(),
                "saving must not silently switch a disabled variable back on");
    }

    @Test
    void aFailedRestartKeepsTheNewVariableAndSwitchesTheApplicationOff() throws Exception {
        // Caddys Admin-Port ist eine feste Konstante. Laeuft auf dieser Maschine eine
        // echte Instanz, darf der Test sie nicht anfassen - dann wird uebersprungen.
        org.junit.jupiter.api.Assumptions.assumeTrue(adminPortIsFree(),
                "a real webserver instance is running on this machine");
        WebServerController controller = controller();
        controller.updatePorts(freePort(), freePort());
        controller.start();
        running = controller;

        try (ServerSocket foreign = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            // Wie im Fehlerbild: ein fremder Prozess haelt den Port der Anwendung.
            int port = foreign.getLocalPort();
            controller.addSite(site(application(Map.of(), false), port));
            ManagedApplication stored = stored(controller);
            Map<String, ManagedEnvironmentVariable> edited =
                    new LinkedHashMap<>(stored.environment());
            edited.put("BROWSER_DIR", new ManagedEnvironmentVariable(BROWSER_DIR, false, true));
            ManagedApplication autostart = new ManagedApplication(stored.id(), stored.jarPath(),
                    stored.javaExecutable(), stored.workingDirectory(), stored.jvmArguments(),
                    stored.applicationArguments(), edited, true, false,
                    stored.restartPolicy(), stored.readinessPolicy(),
                    stored.stopTimeoutSeconds(), stored.forceStopTimeoutSeconds(), Boolean.TRUE);

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> controller.updateSite(HOST, site(autostart, port)));

            assertTrue(failure.getMessage().contains("Your settings are kept"),
                    failure.getMessage());
        }
        ManagedApplication persisted = reload();
        assertEquals(BROWSER_DIR, persisted.environment().get("BROWSER_DIR").value(),
                "a failed restart must not throw away the operator's input");
        assertFalse(persisted.isEnabled(),
                "the application that did not start is switched off, like on publish");
        assertNotNull(stored(controller).environment().get("BROWSER_DIR"));
    }

    private static ManagedApplication stored(WebServerController controller) {
        return controller.configuration().sites().get(0).managedApplication().orElseThrow();
    }

    private ManagedApplication reload() {
        return new JsonConfigurationRepository(new RuntimeDirectories(root).configFile())
                .load().sites().get(0).managedApplication().orElseThrow();
    }

    private static Site site(ManagedApplication application, int port) {
        return new Site(HOST, Optional.of(Upstream.parse("http://127.0.0.1:" + port)),
                List.of(), false, Optional.of(application));
    }

    private ManagedApplication application(Map<String, ManagedEnvironmentVariable> env,
                                           boolean startAutomatically) throws Exception {
        Path jar = root.resolve("argos.jar");
        if (!Files.exists(jar)) {
            Files.writeString(jar, "this is not a jar");
        }
        return new ManagedApplication("argos", jar.toString(),
                Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(), "",
                List.of(), List.of(), env, startAutomatically, false, RestartPolicy.defaults(),
                // Kurzer Timeout: der Test soll das Verhalten zeigen, nicht warten.
                new ReadinessPolicy(2, 200, 500, "", 200), 5, 5, Boolean.TRUE);
    }

    private WebServerController controller() throws java.io.IOException {
        RuntimeDirectories directories = new RuntimeDirectories(root);
        directories.ensureExist();
        Path executable = Path.of(System.getProperty("user.dir"), "runtime", "caddy", "bin",
                "caddy.exe");
        assertTrue(Files.isRegularFile(executable),
                "Windows CI must prepare the Caddy test binary");
        Files.createDirectories(directories.caddyBinary().getParent());
        Files.copy(executable, directories.caddyBinary());
        WebServerController controller = new WebServerController(directories, new UserLog());
        controller.secretProtector(secret -> "dpapi:" + secret);
        return controller;
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
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }
}
