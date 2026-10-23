package com.aresstack.webserver.ui;

import com.aresstack.webserver.application.ApplyConfiguration;
import com.aresstack.webserver.application.UpdateManagedApplication;
import com.aresstack.webserver.application.StartWebServer;
import com.aresstack.webserver.domain.AcmeConfiguration;
import com.aresstack.webserver.domain.DomainName;
import com.aresstack.webserver.domain.ManagedProcessStatus;
import com.aresstack.webserver.domain.ManagedApplication;
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
import com.aresstack.webserver.domain.OAuthClientConfiguration;
import com.aresstack.webserver.domain.ScopeEntry;
import com.aresstack.webserver.domain.OAuthConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
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
    private final com.aresstack.webserver.infrastructure.oauth.OAuthRuntime oauthRuntime;
    /**
     * Turns a plaintext client secret into its stored form. Production uses Windows
     * DPAPI; tests inject a stub so they do not depend on PowerShell being present.
     */
    private java.util.function.UnaryOperator<String> secretProtector =
            com.aresstack.webserver.infrastructure.managed.WindowsDpapiSecrets::protect;
    private final List<Runnable> listeners = new ArrayList<>();
    // Serializes every configuration mutation for the entire duration of an update,
    // including its readiness probe and journal commit/rollback.
    private final ReentrantLock configurationTransactions = new ReentrantLock(true);

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
        // Self-reported scopes are folded into the catalogue; see registerSiteScopes.
        this.oauthRuntime = new com.aresstack.webserver.infrastructure.oauth.OAuthRuntime(
                directories.root(), log::info);
        this.oauthRuntime.registrationSink(this::registerSiteScopes0);
        this.managedProcesses.extraEnvironment(this::registrationEnvironment);
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

    public void start() {
        configurationTransactions.lock();
        try {
            if (!java.nio.file.Files.exists(directories.configFile())) {
                repository.save(configuration());
            }
            current = startWebServer.start();
            managedProcesses.reconcile(current.sites());
            oauthRuntime.apply(current.oauth(),
                    com.aresstack.webserver.infrastructure.oauth.OAuthRuntime.resourceScopes(current));
            log.info("Server started");
            notifyChanged();
        
        } finally {
            configurationTransactions.unlock();
        }
    }

    public void stop() {
        configurationTransactions.lock();
        try {
            managedProcesses.stopAll();
            oauthRuntime.stop();
            runtime.stop();
            log.info("Server stopped");
            notifyChanged();
        
        } finally {
            configurationTransactions.unlock();
        }
    }

    public void restart() {
        configurationTransactions.lock();
        try {
            managedProcesses.stopAll();
            oauthRuntime.stop();
            runtime.stop();
            current = startWebServer.start();
            managedProcesses.reconcile(current.sites());
            oauthRuntime.apply(current.oauth(),
                    com.aresstack.webserver.infrastructure.oauth.OAuthRuntime.resourceScopes(current));
            log.info("Server restarted");
            notifyChanged();
        
        } finally {
            configurationTransactions.unlock();
        }
    }

    public void addSite(Site site) {
        configurationTransactions.lock();
        try {
            site = secretStore.secure(jarInstaller.install(site));
            WebServerConfiguration config = configuration();
            List<Site> sites = new ArrayList<>(config.sites());
            boolean firstService = sites.isEmpty();
            sites.add(site);
            java.time.Instant requestedAfter = java.time.Instant.now();
            // Beim allerersten Service übernimmt das informative Domain-Feld
            // dessen Host — es gibt keinen separaten "Primary Domain"-Begriff.
            applyNew(new WebServerConfiguration(
                    firstService ? site.host() : config.domain(),
                    config.acme(), config.defaultUpstream(), sites,
                    config.httpPort(), config.httpsPort(), config.oauth()));
            awaitFirstStartOrRollback(site, config, requestedAfter);
            log.info("Published " + site.host());
            if (site.httpsEnabled()) {
                log.info("Requested HTTPS certificate for " + site.host());
            }
        
        } finally {
            configurationTransactions.unlock();
        }
    }

    public void updateSite(DomainName originalHost, Site updated) {
        configurationTransactions.lock();
        try {
            WebServerConfiguration snapshot = configuration();
            Site previous = snapshot.sites().stream()
                    .filter(site -> site.host().equals(originalHost))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown published service"));
            Site installed = secretStore.secure(jarInstaller.install(updated));
            List<Site> previousSites = List.copyOf(snapshot.sites());
            List<Site> newSites = snapshot.sites().stream()
                    .map(site -> site.host().equals(originalHost) ? installed : site)
                    .collect(Collectors.toList());

            String previousApplicationId = previous.managedApplication()
                    .map(ManagedApplication::id)
                    .orElseGet(() -> installed.managedApplication()
                            .map(ManagedApplication::id).orElse(null));
            ManagedProcessStatus previousProcessStatus = previousApplicationId == null
                    ? ManagedProcessStatus.stopped()
                    : managedProcesses.status(previousApplicationId);

            if (requiresManagedDeployment(previous, installed, previousProcessStatus,
                    runtime.isRunning())) {
                new UpdateManagedApplication(managedProcesses, updateJournal).execute(
                        previous, installed, snapshot.defaultUpstream(), previousSites, newSites,
                        sites -> applyNew(withSites(sites)));
            } else {
                applyNew(withSites(newSites));
            }
            log.info("Updated " + installed.host());
        
        } finally {
            configurationTransactions.unlock();
        }
    }

    /**
     * Die Readiness-Garantie gilt auch für die Erstveröffentlichung: wird eine
     * neue verwaltete Anwendung im laufenden Server nicht rechtzeitig bereit,
     * wird die Veröffentlichung zurückgenommen statt still gespeichert.
     */
    private void awaitFirstStartOrRollback(Site site, WebServerConfiguration previousConfiguration,
                                           java.time.Instant requestedAfter) {
        boolean mustAwait = runtime.isRunning() && site.managedApplication()
                .map(app -> app.isEnabled() && app.startAutomatically())
                .orElse(false);
        if (!mustAwait) {
            return;
        }
        try {
            new com.aresstack.webserver.application.WaitForManagedApplication(managedProcesses)
                    .await(site, previousConfiguration.defaultUpstream(), -1, requestedAfter);
        } catch (RuntimeException failure) {
            try {
                applyNew(previousConfiguration);
            } catch (RuntimeException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw new IllegalStateException("Publishing " + site.host()
                    + " was rolled back because the managed application did not become ready: "
                    + failure.getMessage(), failure);
        }
    }

    static boolean requiresManagedDeployment(Site previous, Site updated,
                                             ManagedProcessStatus previousStatus,
                                             boolean serverRunning) {
        if (!serverRunning || updated.managedApplication().isEmpty()) {
            return false;
        }
        ManagedApplication candidate = updated.managedApplication().orElseThrow();
        if (!candidate.isEnabled()) {
            return false;
        }
        java.util.Optional<ManagedApplication> previousApplication = previous.managedApplication();
        boolean launchSettingsChanged = previousApplication
                .map(existing -> !existing.sameLaunchSettings(candidate))
                .orElse(true);
        boolean running = previousStatus.pid() > 0;
        if (launchSettingsChanged) {
            return candidate.startAutomatically() || running;
        }
        // Unveränderte Launch-Einstellungen: ein Deployment ist nur nötig, um
        // eine NICHT laufende Anwendung zu starten, deren Autostart gerade
        // eingeschaltet wurde. Das Umschalten von Lifecycle-Optionen darf eine
        // laufende Anwendung weder stoppen noch neu starten.
        boolean automaticStartEnabled = previousApplication
                .map(existing -> !existing.startAutomatically() && candidate.startAutomatically())
                .orElse(candidate.startAutomatically());
        return !running && automaticStartEnabled;
    }

    public void removeSite(DomainName host) {
        configurationTransactions.lock();
        try {
            List<Site> sites = configuration().sites().stream()
                    .filter(site -> !site.host().equals(host))
                    .collect(Collectors.toList());
            applyNew(withSites(sites));
            log.info("Removed " + host);
        
        } finally {
            configurationTransactions.unlock();
        }
    }

    public void updateAcmeEmail(String email) {
        configurationTransactions.lock();
        try {
            WebServerConfiguration config = configuration();
            applyNew(new WebServerConfiguration(
                    config.domain(),
                    new AcmeConfiguration(email, config.acme().ca()),
                    config.defaultUpstream(),
                    config.sites(),
                    config.httpPort(),
                    config.httpsPort(),
                    config.oauth()));
            log.info("Updated Let's Encrypt account email");
        
        } finally {
            configurationTransactions.unlock();
        }
    }

    /**
     * Replaces the scope policy of a protected site: the service-wide Allow/Deny
     * rule plus the per-scope overrides and the manually maintained catalogue.
     */
    public void updateSiteScopePolicy(DomainName host,
                                      com.aresstack.webserver.domain.ScopePolicy policy) {
        configurationTransactions.lock();
        try {
            List<Site> sites = new ArrayList<>();
            boolean found = false;
            for (Site site : configuration().sites()) {
                if (site.host().equals(host) && site.authentication().isPresent()) {
                    sites.add(site.withAuthentication(
                            site.authentication().get().withScopePolicy(policy)));
                    found = true;
                } else {
                    sites.add(site);
                }
            }
            if (!found) {
                throw new IllegalArgumentException(
                        "No central-OAuth site for host " + host.value());
            }
            applyNew(withSites(sites));
            log.info("Updated scope policy for " + host.value() + ": "
                    + (policy.allowByDefault() ? "allow all" : "deny all") + ", "
                    + policy.entries().size() + " scope(s) known");
        } finally {
            configurationTransactions.unlock();
        }
    }

    /**
     * Folds a service's self-reported scopes into its catalogue.
     *
     * <p>Automatic discovery is a convenience: it describes which permissions exist,
     * it never decides whether they are granted. Existing operator decisions are
     * therefore preserved and new scopes follow the service-wide rule. A service
     * that reports nothing stays fully configurable by hand.
     *
     * @return true when the catalogue actually changed
     */
    /**
     * Environment a managed application needs to report its scopes.
     *
     * <p>Only handed out for a service whose site is actually protected by central
     * OAuth, and the token is bound to that host - a service can therefore only
     * describe its own resource. Without this the operator would have to copy a
     * secret out of a file by hand for every service.
     */
    private Map<String, String> registrationEnvironment(String serviceId) {
        for (Site site : configuration().sites()) {
            boolean matches = site.managedApplication()
                    .map(app -> app.id().equals(serviceId)).orElse(false);
            if (!matches || site.authentication().isEmpty()) {
                continue;
            }
            String host = site.host().value();
            return Map.of(
                    "ARESSTACK_SCOPE_REGISTRATION_URL", oauthRuntime.registrationUrl(),
                    "ARESSTACK_SCOPE_REGISTRATION_HOST", host,
                    "ARESSTACK_SCOPE_REGISTRATION_TOKEN", oauthRuntime.registrationToken(host));
        }
        return Map.of();
    }

    /** Sink adapter: the endpoint reports a host name, not a DomainName. */
    private boolean registerSiteScopes0(String host, List<ScopeEntry> reported) {
        try {
            return registerSiteScopes(new DomainName(host), reported);
        } catch (RuntimeException rejected) {
            // A malformed or unknown host must never take the reporting service down.
            log.info("Scope registration rejected for '" + host + "': "
                    + rejected.getClass().getSimpleName());
            return false;
        }
    }

    public boolean registerSiteScopes(DomainName host, List<ScopeEntry> reported) {
        configurationTransactions.lock();
        try {
            Site target = null;
            for (Site site : configuration().sites()) {
                if (site.host().equals(host) && site.authentication().isPresent()) {
                    target = site;
                }
            }
            if (target == null) {
                // Not an error: a managed application may report before its site is
                // protected, and that must not break the application.
                log.info("Ignoring scope registration for unprotected host " + host.value());
                return false;
            }
            com.aresstack.webserver.domain.ScopePolicy current =
                    target.authentication().get().scopePolicy();
            com.aresstack.webserver.domain.ScopePolicy merged = current.mergeRegistered(reported);
            if (merged.equals(current)) {
                return false;
            }
            updateSiteScopePolicyInternal(host, merged);
            log.info("Registered " + reported.size() + " scope(s) for " + host.value());
            return true;
        } finally {
            configurationTransactions.unlock();
        }
    }

    private void updateSiteScopePolicyInternal(
            DomainName host, com.aresstack.webserver.domain.ScopePolicy policy) {
        List<Site> sites = new ArrayList<>();
        for (Site site : configuration().sites()) {
            if (site.host().equals(host) && site.authentication().isPresent()) {
                sites.add(site.withAuthentication(
                        site.authentication().get().withScopePolicy(policy)));
            } else {
                sites.add(site);
            }
        }
        applyNew(withSites(sites));
    }

    /** Test seam: replace DPAPI protection. */
    void secretProtector(java.util.function.UnaryOperator<String> protector) {
        this.secretProtector = protector;
    }

    /**
     * Replaces the registered OAuth clients.
     *
     * <p>This is what makes the central OAuth configuration manageable at all: client
     * id, secret and redirect URIs used to be editable only by hand in
     * {@code webserver.json}. Secrets are stored DPAPI-protected, never as a new
     * plaintext copy, and are never written to the log.
     *
     * <p>An empty client list disables the OAuth service, because
     * {@link OAuthConfiguration} rightly refuses to be enabled without a client.
     */
    public void updateOAuthClients(String issuer, List<OAuthClientConfiguration> clients) {
        configurationTransactions.lock();
        try {
            WebServerConfiguration config = configuration();
            List<OAuthClientConfiguration> stored = new ArrayList<>();
            for (OAuthClientConfiguration client : clients) {
                stored.add(new OAuthClientConfiguration(client.clientId(),
                        secretProtector.apply(client.clientSecret()), client.redirectUris()));
            }
            String effectiveIssuer = issuer == null || issuer.isBlank()
                    ? config.oauth().issuer() : issuer.trim();
            applyNew(new WebServerConfiguration(
                    config.domain(), config.acme(), config.defaultUpstream(), config.sites(),
                    config.httpPort(), config.httpsPort(),
                    new OAuthConfiguration(!stored.isEmpty(), effectiveIssuer, stored)));
            // Deliberately only the count and the ids - never a secret.
            log.info("Updated OAuth clients: " + (stored.isEmpty() ? "none registered"
                    : stored.size() + " registered (" + String.join(", ",
                            stored.stream().map(OAuthClientConfiguration::clientId).toList()) + ")"));
        } finally {
            configurationTransactions.unlock();
        }
    }

    public void updatePorts(int httpPort, int httpsPort) {
        configurationTransactions.lock();
        try {
            WebServerConfiguration config = configuration();
            applyNew(new WebServerConfiguration(
                    config.domain(), config.acme(), config.defaultUpstream(), config.sites(),
                    httpPort, httpsPort, config.oauth()));
            log.info("Changed server ports to HTTP " + httpPort + " / HTTPS " + httpsPort);
        
        } finally {
            configurationTransactions.unlock();
        }
    }

    private WebServerConfiguration withSites(List<Site> sites) {
        WebServerConfiguration config = configuration();
        return new WebServerConfiguration(
                config.domain(), config.acme(), config.defaultUpstream(), sites,
                config.httpPort(), config.httpsPort(), config.oauth());
    }

    /**
     * Läuft der Server, wird atomar per Admin-API umgeschaltet; sonst wird
     * nur validiert, gerendert und persistiert.
     */
    /** Commit publication settings only after lifecycle transitions completed successfully. */
    private void applyNew(WebServerConfiguration next) {
        next.validate();
        WebServerConfiguration previous = configuration();
        if (runtime.isRunning()) {
            // A failed stop (or any reconcile failure) must veto persistence.
            managedProcesses.reconcile(next.sites());
            try {
                applyConfiguration.apply(next);
            } catch (RuntimeException failure) {
                try {
                    managedProcesses.reconcile(previous.sites());
                } catch (RuntimeException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
        } else {
            String caddyfile = writer.render(next);
            writer.validateWithCaddy(caddyfile);
            writer.persistGenerated(caddyfile);
            repository.save(next);
        }
        if (runtime.isRunning()) {
            oauthRuntime.apply(next.oauth(),
                    com.aresstack.webserver.infrastructure.oauth.OAuthRuntime.resourceScopes(next));
        }
        current = next;
        notifyChanged();
    }

    public boolean isManagedUpdatePending(Site site) {
        return site.managedApplication()
                .map(app -> updateJournal.isUpdatePending(app.id())).orElse(false);
    }

    public ManagedProcessStatus managedStatus(Site site) {
        return site.managedApplication().map(app -> managedProcesses.status(app.id()))
                .orElse(ManagedProcessStatus.stopped());
    }

    public void startManaged(Site site) {
        configurationTransactions.lock();
        try {
            site.managedApplication().ifPresent(app -> {
                long previousPid = managedProcesses.status(app.id()).pid();
                java.time.Instant requestedAfter = java.time.Instant.now();
                managedProcesses.start(app.id());
                if (previousPid <= 0) {
                    awaitManagedReady(site, previousPid, requestedAfter);
                }
            });
            notifyChanged();
        } finally {
            configurationTransactions.unlock();
        }
    }

    /** Auch manuelle Starts warten auf die konfigurierte Readiness-Prüfung. */
    private void awaitManagedReady(Site site, long previousPid, java.time.Instant requestedAfter) {
        try {
            new com.aresstack.webserver.application.WaitForManagedApplication(managedProcesses)
                    .await(site, configuration().defaultUpstream(), previousPid, requestedAfter);
        } finally {
            notifyChanged();
        }
    }

    public void stopManaged(Site site) {
        configurationTransactions.lock();
        try {
            site.managedApplication().ifPresent(app -> managedProcesses.stop(app.id()));
            notifyChanged();
        } finally {
            configurationTransactions.unlock();
        }
    }

    public void restartManaged(Site site) {
        configurationTransactions.lock();
        try {
            site.managedApplication().ifPresent(app -> {
                long previousPid = managedProcesses.status(app.id()).pid();
                java.time.Instant requestedAfter = java.time.Instant.now();
                managedProcesses.restart(app.id());
                awaitManagedReady(site, previousPid, requestedAfter);
            });
            notifyChanged();
        } finally {
            configurationTransactions.unlock();
        }
    }

    public boolean hasPendingManagedRecovery(Site site) {
        return site.managedApplication()
                .map(app -> managedProcesses.hasPendingIdentity(app.id(), app.jarPath())).orElse(false);
    }

    public void resolvePendingManagedRecovery(Site site) {
        configurationTransactions.lock();
        try {
            site.managedApplication().ifPresent(app ->
                    managedProcesses.resolvePendingIdentity(app.id(), app.jarPath()));
            notifyChanged();
        } finally {
            configurationTransactions.unlock();
        }
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
