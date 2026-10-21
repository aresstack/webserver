package com.aresstack.webserver.ui;

import com.aresstack.webserver.application.ApplyConfiguration;
import com.aresstack.webserver.application.UpdateManagedApplication;
import com.aresstack.webserver.application.StartWebServer;
import com.aresstack.webserver.domain.AcmeConfiguration;
import com.aresstack.webserver.domain.DomainName;
import com.aresstack.webserver.domain.ManagedProcessStatus;
import com.aresstack.webserver.domain.Site;
import com.aresstack.webserver.domain.WebServerConfiguration;
import com.aresstack.webserver.infrastructure.caddy.CaddyAdminClient;
import com.aresstack.webserver.infrastructure.caddy.CaddyConfigurationAdapter;
import com.aresstack.webserver.infrastructure.caddy.CaddyProcessManager;
import com.aresstack.webserver.infrastructure.caddy.RuntimeDirectories;
import com.aresstack.webserver.infrastructure.configuration.JsonConfigurationRepository;
import com.aresstack.webserver.infrastructure.managed.ManagedJarInstaller;
import com.aresstack.webserver.infrastructure.managed.ManagedProcessManager;
import com.aresstack.webserver.infrastructure.managed.ManagedSecretStore;
import com.aresstack.webserver.infrastructure.managed.FileManagedUpdateJournal;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Vermittelt zwischen der Oberfläche und den Use Cases. Alle Änderungen
 * laufen über den atomaren Apply-Pfad; eine ungültige Konfiguration erreicht
 * niemals den laufenden Server.
 */
public class WebServerController {

    private final RuntimeDirectories directories;
    private final JsonConfigurationRepository repository;
    private final CaddyConfigurationAdapter writer;
    private final CaddyProcessManager runtime;
    private final StartWebServer startWebServer;
    private final ApplyConfiguration applyConfiguration;
    private final UserLog log;
    private final ManagedProcessManager managedProcesses;
    private final ManagedJarInstaller jarInstaller;
    private final ManagedSecretStore secretStore;
    private final FileManagedUpdateJournal updateJournal;
    private final List<Runnable> listeners = new ArrayList<>();

    private volatile WebServerConfiguration current;

    public WebServerController(RuntimeDirectories directories, UserLog log) {
        this.directories = directories;
        this.log = log;
        this.repository = new JsonConfigurationRepository(directories.configFile());
        this.writer = new CaddyConfigurationAdapter(directories);
        CaddyAdminClient admin = new CaddyAdminClient();
        this.runtime = new CaddyProcessManager(directories, admin);
        this.startWebServer = new StartWebServer(repository, writer, runtime);
        this.applyConfiguration = new ApplyConfiguration(writer, admin, repository);
        this.managedProcesses = new ManagedProcessManager(directories.root(), log::info);
        this.jarInstaller = new ManagedJarInstaller(directories.root());
        this.secretStore = new ManagedSecretStore(directories.root());
        this.updateJournal = new FileManagedUpdateJournal(directories.root(), managedProcesses);
        this.updateJournal.recover();
    }

    public RuntimeDirectories directories() {
        return directories;
    }

    public WebServerConfiguration configuration() {
        WebServerConfiguration snapshot = current;
        if (snapshot != null) {
            return snapshot;
        }
        synchronized (this) {
            if (current == null) {
                current = java.nio.file.Files.exists(directories.configFile())
                        ? repository.load() : defaultConfiguration();
            }
            return current;
        }
    }

    /**
     * Fehlende webserver.json ist kein Setup-Sonderfall, sondern bedeutet
     * "noch keine veröffentlichten Services".
     */
    private static WebServerConfiguration defaultConfiguration() {
        return new WebServerConfiguration(
                new DomainName("aresstack.de"),
                AcmeConfiguration.withoutEmail(),
                com.aresstack.webserver.domain.Upstream.parse("http://127.0.0.1:8080"),
                List.of());
    }

    public boolean isRunning() {
        return runtime.isRunning();
    }

    public synchronized void start() {
        if (!java.nio.file.Files.exists(directories.configFile())) {
            repository.save(configuration());
        }
        current = startWebServer.start();
        managedProcesses.reconcile(current.sites());
        log.info("Server started");
        notifyChanged();
    }

    public synchronized void stop() {
        managedProcesses.stopAll();
        runtime.stop();
        log.info("Server stopped");
        notifyChanged();
    }

    public synchronized void restart() {
        managedProcesses.stopAll();
        runtime.stop();
        current = startWebServer.start();
        managedProcesses.reconcile(current.sites());
        log.info("Server restarted");
        notifyChanged();
    }

    public synchronized void addSite(Site site) {
        site = secretStore.secure(jarInstaller.install(site));
        WebServerConfiguration config = configuration();
        List<Site> sites = new ArrayList<>(config.sites());
        boolean firstService = sites.isEmpty();
        sites.add(site);
        // Beim allerersten Service übernimmt das informative Domain-Feld
        // dessen Host — es gibt keinen separaten "Primary Domain"-Begriff.
        applyNew(new WebServerConfiguration(
                firstService ? site.host() : config.domain(),
                config.acme(), config.defaultUpstream(), sites,
                config.httpPort(), config.httpsPort()));
        log.info("Published " + site.host());
        if (site.httpsEnabled()) {
            log.info("Requested HTTPS certificate for " + site.host());
        }
    }

    public void updateSite(DomainName originalHost, Site updated) {
        WebServerConfiguration snapshot = configuration();
        Site previous = snapshot.sites().stream()
                .filter(site -> site.host().equals(originalHost))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown published service"));
        Site installed = secretStore.secure(jarInstaller.install(updated));
        List<Site> previousSites = List.copyOf(snapshot.sites());
        List<Site> newSites = snapshot.sites().stream()
                .map(site -> site.host().equals(originalHost) ? installed : site)
                .collect(Collectors.toList());

        boolean jarChanged = installed.managedApplication().isPresent()
                && previous.managedApplication()
                        .map(old -> !old.jarPath().equals(
                                installed.managedApplication().orElseThrow().jarPath()))
                        .orElse(true);
        if (jarChanged && installed.managedApplication().orElseThrow().startAutomatically()
                && runtime.isRunning()) {
            new UpdateManagedApplication(managedProcesses, updateJournal).execute(
                    previous, installed, snapshot.defaultUpstream(), previousSites, newSites,
                    sites -> applyNew(withSites(sites)));
        } else {
            applyNew(withSites(newSites));
        }
        log.info("Updated " + installed.host());
    }

    public synchronized void removeSite(DomainName host) {
        List<Site> sites = configuration().sites().stream()
                .filter(site -> !site.host().equals(host))
                .collect(Collectors.toList());
        applyNew(withSites(sites));
        log.info("Removed " + host);
    }

    public synchronized void updateAcmeEmail(String email) {
        WebServerConfiguration config = configuration();
        applyNew(new WebServerConfiguration(
                config.domain(),
                new AcmeConfiguration(email, config.acme().ca()),
                config.defaultUpstream(),
                config.sites(),
                config.httpPort(),
                config.httpsPort()));
        log.info("Updated Let's Encrypt account email");
    }

    public synchronized void updatePorts(int httpPort, int httpsPort) {
        WebServerConfiguration config = configuration();
        applyNew(new WebServerConfiguration(
                config.domain(), config.acme(), config.defaultUpstream(), config.sites(),
                httpPort, httpsPort));
        log.info("Changed server ports to HTTP " + httpPort + " / HTTPS " + httpsPort);
    }

    private WebServerConfiguration withSites(List<Site> sites) {
        WebServerConfiguration config = configuration();
        return new WebServerConfiguration(
                config.domain(), config.acme(), config.defaultUpstream(), sites,
                config.httpPort(), config.httpsPort());
    }

    /**
     * Läuft der Server, wird atomar per Admin-API umgeschaltet; sonst wird
     * nur validiert, gerendert und persistiert.
     */
    private synchronized void applyNew(WebServerConfiguration next) {
        next.validate();
        if (runtime.isRunning()) {
            applyConfiguration.apply(next);
        } else {
            String caddyfile = writer.render(next);
            writer.validateWithCaddy(caddyfile);
            writer.persistGenerated(caddyfile);
            repository.save(next);
        }
        current = next;
        if (runtime.isRunning()) {
            managedProcesses.reconcile(next.sites());
        }
        notifyChanged();
    }

    public ManagedProcessStatus managedStatus(Site site) {
        return site.managedApplication().map(app -> managedProcesses.status(app.id()))
                .orElse(ManagedProcessStatus.stopped());
    }

    public void startManaged(Site site) {
        site.managedApplication().ifPresent(app -> managedProcesses.start(app.id()));
        notifyChanged();
    }

    public void stopManaged(Site site) {
        site.managedApplication().ifPresent(app -> managedProcesses.stop(app.id()));
        notifyChanged();
    }

    public void restartManaged(Site site) {
        site.managedApplication().ifPresent(app -> managedProcesses.restart(app.id()));
        notifyChanged();
    }

    public synchronized void addListener(Runnable listener) {
        listeners.add(listener);
    }

    private void notifyChanged() {
        for (Runnable listener : listeners) {
            SwingUtilities.invokeLater(listener);
        }
    }
}
