package com.aresstack.webserver.launcher;

import com.aresstack.webserver.application.ApplyConfiguration;
import com.aresstack.webserver.application.StartWebServer;
import com.aresstack.webserver.application.StopWebServer;
import com.aresstack.webserver.domain.WebServerConfiguration;
import com.aresstack.webserver.infrastructure.caddy.CaddyAdminClient;
import com.aresstack.webserver.infrastructure.caddy.CaddyConfigurationAdapter;
import com.aresstack.webserver.infrastructure.caddy.CaddyProcessManager;
import com.aresstack.webserver.infrastructure.caddy.RuntimeDirectories;
import com.aresstack.webserver.infrastructure.configuration.JsonConfigurationRepository;
import com.aresstack.webserver.infrastructure.managed.ManagedProcessManager;
import com.aresstack.webserver.infrastructure.managed.FileManagedUpdateJournal;
import com.aresstack.webserver.ui.FriendlyErrors;
import com.aresstack.webserver.ui.WebServerFrame;
import com.aresstack.webserver.ui.UserLog;
import com.aresstack.webserver.ui.WebServerController;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Einstiegspunkt. Standard ist die grafische Anwendung; {@code --headless}
 * startet den bisherigen Servermodus mit Konfigurations-Watcher.
 */
public final class WebServerMain {

    private WebServerMain() {
    }

    public static void main(String[] args) throws Exception {
        Path root = resolveRoot(args);
        boolean headless = Arrays.asList(args).contains("--headless")
                || GraphicsEnvironment.isHeadless();
        if (headless) {
            runHeadless(root);
        } else {
            com.formdev.flatlaf.FlatLightLaf.setup();
            SwingUtilities.invokeLater(() -> launchGui(root));
        }
    }

    private static Path resolveRoot(String[] args) {
        // Priorität: explizites Argument, dann das vom Startskript gesetzte
        // APP_HOME (-Dwebserver.root), sonst das Arbeitsverzeichnis.
        for (String arg : args) {
            if (!arg.startsWith("--")) {
                return Path.of(arg).toAbsolutePath().normalize();
            }
        }
        String property = System.getProperty("webserver.root", ".");
        if (property.isBlank() || property.contains("APP_HOME")) {
            // Nicht ersetzter Skript-Platzhalter — auf Arbeitsverzeichnis zurückfallen.
            property = ".";
        }
        return Path.of(property).toAbsolutePath().normalize();
    }

    // ------------------------------------------------------------------
    // GUI
    // ------------------------------------------------------------------

    private static void launchGui(Path root) {
        RuntimeDirectories directories = new RuntimeDirectories(root);
        directories.ensureExist();
        com.aresstack.webserver.infrastructure.caddy.CaddyBinaryProvider.ensureAvailable(directories);

        if (!Files.exists(directories.caddyBinary())) {
            JOptionPane.showMessageDialog(null,
                    "The webserver engine is missing:\n" + directories.caddyBinary()
                            + "\n\nReinstall the application or run: gradlew downloadCaddy",
                    "AresStack Webserver", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }

        // Kein First-Run-Wizard: das Hauptfenster ist immer das erste Fenster.
        // Eine fehlende webserver.json bedeutet nur "0 veröffentlichte Services".
        UserLog log = new UserLog();
        WebServerController controller = new WebServerController(directories, log);
        WebServerFrame window = new WebServerFrame(controller, log);
        window.setVisible(true);

        // Server automatisch starten, sobald es etwas zu servieren gibt
        // (abschaltbar in den Einstellungen); Fehler landen verständlich im Dialog.
        if (com.aresstack.webserver.ui.AppPreferences.autostartServer()
                && !controller.configuration().sites().isEmpty()) {
            new Thread(() -> {
                try {
                    controller.start();
                } catch (RuntimeException e) {
                    SwingUtilities.invokeLater(() ->
                            FriendlyErrors.show(window, "Start Server", e));
                }
            }, "server-autostart").start();
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (controller.isRunning()) {
                controller.stop();
            }
        }, "caddy-shutdown"));
    }

    // ------------------------------------------------------------------
    // Headless (Serverbetrieb)
    // ------------------------------------------------------------------

    private static void runHeadless(Path root) throws Exception {
        RuntimeDirectories directories = new RuntimeDirectories(root);
        directories.ensureExist();
        com.aresstack.webserver.infrastructure.caddy.CaddyBinaryProvider.ensureAvailable(directories);

        if (!Files.exists(directories.configFile())) {
            System.err.println("Missing configuration: " + directories.configFile());
            System.err.println("Create it first, e.g. based on config/webserver.example.json");
            System.exit(1);
        }
        if (!Files.exists(directories.caddyBinary())) {
            System.err.println("Missing caddy binary: " + directories.caddyBinary());
            System.err.println("Run: gradlew downloadCaddy");
            System.exit(1);
        }

        JsonConfigurationRepository repository = new JsonConfigurationRepository(directories.configFile());
        CaddyConfigurationAdapter configurationAdapter = new CaddyConfigurationAdapter(directories);
        CaddyAdminClient admin = new CaddyAdminClient();
        CaddyProcessManager runtime = new CaddyProcessManager(directories, admin);

        StartWebServer startWebServer = new StartWebServer(repository, configurationAdapter, runtime);
        StopWebServer stopWebServer = new StopWebServer(runtime);
        ApplyConfiguration applyConfiguration =
                new ApplyConfiguration(configurationAdapter, admin, repository);

        ManagedProcessManager managedProcesses = new ManagedProcessManager(root, System.out::println);
        com.aresstack.webserver.infrastructure.oauth.OAuthRuntime oauthRuntime =
                new com.aresstack.webserver.infrastructure.oauth.OAuthRuntime(
                        root, System.out::println);
        new FileManagedUpdateJournal(root, managedProcesses).recover();
        WebServerConfiguration configuration = startWebServer.start();
        managedProcesses.reconcile(configuration.sites());
        oauthRuntime.apply(configuration.oauth(),
                com.aresstack.webserver.infrastructure.oauth.OAuthRuntime.resourceScopes(configuration));
        System.out.println("Caddy started for " + configuration.domain()
                + " with " + configuration.sites().size() + " site(s)");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            managedProcesses.stopAll();
            oauthRuntime.stop();
            stopWebServer.stop();
        }, "webserver-shutdown"));

        watchConfiguration(directories, repository, applyConfiguration, managedProcesses, oauthRuntime,
                configuration);
    }

    private static void watchConfiguration(RuntimeDirectories directories,
                                           JsonConfigurationRepository repository,
                                           ApplyConfiguration applyConfiguration,
                                           ManagedProcessManager managedProcesses,
                                           com.aresstack.webserver.infrastructure.oauth.OAuthRuntime
                                                   oauthRuntime,
                                           WebServerConfiguration initial) throws Exception {
        Path configDir = directories.configFile().getParent();
        Path configName = directories.configFile().getFileName();
        WebServerConfiguration lastApplied = initial;
        try (var watchService = configDir.getFileSystem().newWatchService()) {
            configDir.register(watchService,
                    java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY,
                    java.nio.file.StandardWatchEventKinds.ENTRY_CREATE);
            while (true) {
                var key = watchService.take();
                boolean changed = key.pollEvents().stream()
                        .anyMatch(event -> configName.equals(event.context()));
                key.reset();
                if (!changed) {
                    continue;
                }
                try {
                    WebServerConfiguration reloaded = repository.load();
                    // ApplyConfiguration persists the configuration, so our own write
                    // fires another ENTRY_MODIFY. Without this guard the watcher
                    // reloads itself in a tight loop — observed at roughly three
                    // reloads per second — which keeps the lifecycle worker busy and
                    // prevents a managed application from ever being (re)started.
                    // Record equality makes a no-op edit (reformatting, a touched
                    // timestamp, our canonical rewrite) cheap to detect.
                    if (reloaded.equals(lastApplied)) {
                        continue;
                    }
                    // The config file can have been edited by an external process.
                    // Reject changes that cannot be enacted instead of reporting
                    // a new JAR while a different child is still running.
                    managedProcesses.reconcile(reloaded.sites());
                    applyConfiguration.apply(reloaded);
                    // The OAuth service builds its client registry once at start, so a
                    // changed client or scope only takes effect if the runtime is
                    // reapplied here. Without this, editing OAuth clients required a
                    // full restart in headless mode.
                    oauthRuntime.apply(reloaded.oauth(),
                            com.aresstack.webserver.infrastructure.oauth.OAuthRuntime
                                    .resourceScopes(reloaded));
                    lastApplied = reloaded;
                    System.out.println("Configuration reloaded");
                } catch (RuntimeException e) {
                    // Restore both process intent and persisted configuration.
                    try {
                        managedProcesses.reconcile(lastApplied.sites());
                        repository.save(lastApplied);
                    } catch (RuntimeException rollback) {
                        e.addSuppressed(rollback);
                    }
                    System.err.println("Configuration rejected: " + e.getMessage());
                }
            }
        }
    }
}
