package com.aresstack.webserver.launcher;

import com.aresstack.webserver.application.ScopeRegistration;
import com.aresstack.webserver.domain.AcmeConfiguration;
import com.aresstack.webserver.domain.DomainName;
import com.aresstack.webserver.domain.ManagedApplication;
import com.aresstack.webserver.domain.OAuthClientConfiguration;
import com.aresstack.webserver.domain.OAuthConfiguration;
import com.aresstack.webserver.domain.ReadinessPolicy;
import com.aresstack.webserver.domain.RestartPolicy;
import com.aresstack.webserver.domain.ScopeDecision;
import com.aresstack.webserver.domain.ScopeEntry;
import com.aresstack.webserver.domain.ScopePolicy;
import com.aresstack.webserver.domain.Site;
import com.aresstack.webserver.domain.SiteAuthentication;
import com.aresstack.webserver.domain.Upstream;
import com.aresstack.webserver.domain.WebServerConfiguration;
import com.aresstack.webserver.infrastructure.configuration.JsonConfigurationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless is the mode the server actually runs in, so a self-reported scope
 * catalogue has to end up in the configuration file and be there again after a
 * restart.
 *
 * <p>Before this, the headless sink logged "ignored" and returned {@code false}: a
 * service could report its scopes, the operator saw nothing in the GUI, and the
 * catalogue was gone on the next start. Registration only worked while the Windows
 * GUI controller happened to be the one running.
 */
class HeadlessScopeRegistrationTest {

    @TempDir Path directory;

    private static final String HOST = "pyloros.example.org";

    @Test
    void aReportedCatalogueIsPersistedAndSurvivesARestart() {
        JsonConfigurationRepository repository = repository();
        repository.save(configuration(SiteAuthentication.centralOAuth()));
        Harness harness = harness(repository);

        assertTrue(harness.sink.register(HOST, List.of(
                ScopeEntry.registered("mcp", "Base access"),
                ScopeEntry.registered("mcp:read", "Read-only tools"))));

        // Restart: a brand new repository instance reads the file from disk.
        ScopePolicy afterRestart = reload(repository);
        assertEquals(List.of("mcp", "mcp:read"), afterRestart.entries().stream()
                .map(ScopeEntry::name).toList());
        assertTrue(afterRestart.entries().get(1).registered(),
                "the entry must be marked as reported, not hand-written");
        assertEquals(List.of("mcp", "mcp:read"),
                reloadAuthentication(repository).effectiveScopes());
    }

    @Test
    void anExistingDenyDecisionIsPreservedByARegistration() {
        JsonConfigurationRepository repository = repository();
        repository.save(configuration(new SiteAuthentication(List.of(), new ScopePolicy(true,
                List.of(ScopeEntry.manual("mcp:write").withDecision(ScopeDecision.DENY))))));
        Harness harness = harness(repository);

        assertTrue(harness.sink.register(HOST, List.of(
                ScopeEntry.registered("mcp", "Base access"),
                ScopeEntry.registered("mcp:write", "Writing tools"))));

        ScopePolicy reloaded = reload(repository);
        assertEquals(ScopeDecision.DENY, entry(reloaded, "mcp:write").decision(),
                "a registration describes capabilities and must not undo a deny");
        assertEquals(List.of("mcp"), reloadAuthentication(repository).effectiveScopes());
    }

    @Test
    void aManualEntryIsKeptEvenWhenTheServiceDoesNotReportIt() {
        JsonConfigurationRepository repository = repository();
        repository.save(configuration(new SiteAuthentication(List.of(), new ScopePolicy(true,
                List.of(ScopeEntry.manual("mcp"), ScopeEntry.manual("git:write"))))));
        Harness harness = harness(repository);

        // The service only knows about "mcp"; the operator added "git:write" by hand.
        assertTrue(harness.sink.register(HOST,
                List.of(ScopeEntry.registered("mcp", "Base access"))));

        ScopePolicy reloaded = reload(repository);
        assertEquals(List.of("mcp", "git:write"),
                reloaded.entries().stream().map(ScopeEntry::name).toList());
        assertFalse(entry(reloaded, "git:write").registered(),
                "a hand-written scope stays manual and must not be dropped");
    }

    @Test
    void anAllowAllDefaultFlippedToDenyAllIsNotReopenedByARegistration() {
        JsonConfigurationRepository repository = repository();
        repository.save(configuration(new SiteAuthentication(List.of(),
                new ScopePolicy(false, List.of(ScopeEntry.manual("mcp"))))));
        Harness harness = harness(repository);

        harness.sink.register(HOST, List.of(ScopeEntry.registered("mcp:read", "Read-only")));

        ScopePolicy reloaded = reload(repository);
        assertFalse(reloaded.allowByDefault(), "deny all must stay deny all");
        assertTrue(reloadAuthentication(repository).effectiveScopes().isEmpty(),
                "nothing may become requestable just because a service reported it");
    }

    @Test
    void repeatingTheSameRegistrationDoesNotRewriteTheConfiguration() {
        JsonConfigurationRepository repository = repository();
        repository.save(configuration(SiteAuthentication.centralOAuth()));
        Harness harness = harness(repository);
        List<ScopeEntry> reported = List.of(ScopeEntry.registered("mcp", "Base access"));

        assertTrue(harness.sink.register(HOST, reported));
        int writesAfterFirst = harness.commits.size();

        assertFalse(harness.sink.register(HOST, reported),
                "an unchanged catalogue must not be written again on every start");
        assertEquals(writesAfterFirst, harness.commits.size());
    }

    @Test
    void aRegistrationForAnUnknownOrUnprotectedHostIsIgnoredInsteadOfFailing() {
        JsonConfigurationRepository repository = repository();
        repository.save(configuration(SiteAuthentication.centralOAuth()));
        Harness harness = harness(repository);

        assertFalse(harness.sink.register("other.example.org",
                List.of(ScopeEntry.registered("mcp", ""))));
        assertFalse(harness.sink.register("unprotected.example.org",
                List.of(ScopeEntry.registered("mcp", ""))));
        assertTrue(harness.commits.isEmpty(), "nothing may be written for a foreign host");
    }

    @Test
    void aFailingCommitLeavesTheAppliedStateUntouchedAndIsReportedAsAFailure() {
        JsonConfigurationRepository repository = repository();
        WebServerConfiguration initial = configuration(SiteAuthentication.centralOAuth());
        repository.save(initial);
        AtomicReference<WebServerConfiguration> applied = new AtomicReference<>(initial);
        HeadlessScopeRegistrationSink sink = new HeadlessScopeRegistrationSink(applied,
                next -> {
                    throw new IllegalStateException("caddy rejected the configuration");
                },
                message -> {
                }, new Object());

        // A failure must be visible to the caller - "false" would read as
        // "already current" and the service would never learn it was not stored.
        assertThrows(IllegalStateException.class,
                () -> sink.register(HOST, List.of(ScopeEntry.registered("mcp:read", "Read"))));
        assertEquals(initial, applied.get());
        assertEquals(List.of("mcp"), reload(repository).entries().stream()
                        .map(ScopeEntry::name).toList(),
                "a failed apply must not leave a half-written catalogue behind");
    }

    @Test
    void aManagedApplicationIsLaunchedWithItsRegistrationCredentials() {
        WebServerConfiguration configuration = configuration(SiteAuthentication.centralOAuth());
        List<Site> sites = new ArrayList<>();
        sites.add(configuration.sites().get(0).withManagedApplication(application()));
        configuration = withSites(configuration, sites);

        Map<String, String> environment = ScopeRegistration.environmentFor(
                configuration, "pyloros", "http://127.0.0.1:9/internal/scopes",
                host -> "token-for-" + host);

        assertEquals(HOST, environment.get("ARESSTACK_SCOPE_REGISTRATION_HOST"));
        assertEquals("http://127.0.0.1:9/internal/scopes",
                environment.get("ARESSTACK_SCOPE_REGISTRATION_URL"));
        assertEquals("token-for-" + HOST, environment.get("ARESSTACK_SCOPE_REGISTRATION_TOKEN"));
        assertTrue(ScopeRegistration.environmentFor(configuration, "other",
                "http://127.0.0.1:9/internal/scopes", host -> "t").isEmpty(),
                "credentials are only handed to a protected service");
    }

    @Test
    void aRegistrationAndAConfigurationReloadCannotInterleave() throws Exception {
        JsonConfigurationRepository repository = repository();
        repository.save(configuration(SiteAuthentication.centralOAuth()));
        Harness harness = harness(repository);
        // The launcher serialises the watcher and the sink on one monitor. Holding it
        // from another thread must block a registration rather than let both write.
        Thread holder = new Thread(() -> {
            synchronized (harness.lock) {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        Thread registering = new Thread(() -> {
            entered.countDown();
            harness.sink.register(HOST, List.of(ScopeEntry.registered("mcp", "Base")));
        });

        holder.start();
        Thread.sleep(50);
        registering.start();
        entered.await();
        // While the lock is held the registration cannot have written anything yet.
        assertTrue(harness.commits.isEmpty(), "the sink must wait for the shared monitor");
        holder.join();
        registering.join();
        assertEquals(1, harness.commits.size());
    }

    @Test
    void theSinkRefusesAnIllegalHostWithoutThrowing() {
        JsonConfigurationRepository repository = repository();
        repository.save(configuration(SiteAuthentication.centralOAuth()));
        Harness harness = harness(repository);

        assertFalse(harness.sink.register(null, List.of(ScopeEntry.registered("mcp", ""))));
        assertFalse(harness.sink.register("  ", List.of(ScopeEntry.registered("mcp", ""))));
        assertThrows(NullPointerException.class,
                () -> ScopeRegistration.merge(null, HOST, List.of()),
                "a missing configuration is a programming error, not a service input");
    }

    /** The wiring the launcher builds, with the commit step captured. */
    private record Harness(HeadlessScopeRegistrationSink sink,
                           List<WebServerConfiguration> commits,
                           Object lock) {
    }

    private Harness harness(JsonConfigurationRepository repository) {
        AtomicReference<WebServerConfiguration> applied =
                new AtomicReference<>(repository.load());
        List<WebServerConfiguration> commits =
                java.util.Collections.synchronizedList(new ArrayList<>());
        Object lock = new Object();
        // Stands in for ApplyConfiguration: persisting is the part under test, Caddy
        // and the OAuth runtime are exercised by their own tests.
        HeadlessScopeRegistrationSink sink = new HeadlessScopeRegistrationSink(applied,
                next -> {
                    repository.save(next);
                    commits.add(next);
                },
                message -> {
                }, lock);
        return new Harness(sink, commits, lock);
    }

    private JsonConfigurationRepository repository() {
        return new JsonConfigurationRepository(directory.resolve("webserver.json"));
    }

    private static ScopeEntry entry(ScopePolicy policy, String name) {
        return policy.entries().stream().filter(e -> e.name().equals(name))
                .findFirst().orElseThrow();
    }

    private SiteAuthentication reloadAuthentication(JsonConfigurationRepository repository) {
        return new JsonConfigurationRepository(directory.resolve("webserver.json"))
                .load().sites().stream()
                .filter(site -> site.host().value().equals(HOST))
                .findFirst().orElseThrow()
                .authentication().orElseThrow();
    }

    private ScopePolicy reload(JsonConfigurationRepository repository) {
        return reloadAuthentication(repository).scopePolicy();
    }

    private static WebServerConfiguration configuration(SiteAuthentication authentication) {
        Upstream upstream = Upstream.parse("http://127.0.0.1:8081");
        return new WebServerConfiguration(new DomainName("example.org"),
                AcmeConfiguration.withoutEmail(), upstream,
                List.of(
                        new Site(new DomainName(HOST), Optional.of(upstream), List.of(), true,
                                Optional.empty(), Optional.of(authentication)),
                        new Site(new DomainName("unprotected.example.org"), Optional.of(upstream),
                                List.of(), true, Optional.empty(), Optional.empty())),
                80, 443,
                new OAuthConfiguration(true, "https://" + HOST,
                        List.of(new OAuthClientConfiguration("app", "dpapi:secret",
                                List.of("https://chatgpt.com")))));
    }

    private static WebServerConfiguration withSites(
            WebServerConfiguration configuration, List<Site> sites) {
        return new WebServerConfiguration(configuration.domain(), configuration.acme(),
                configuration.defaultUpstream(), sites, configuration.httpPort(),
                configuration.httpsPort(), configuration.oauth());
    }

    private ManagedApplication application() {
        return new ManagedApplication("pyloros", directory.resolve("app.jar").toString(),
                Path.of(System.getProperty("java.home"), "bin", "java").toString(), "",
                List.of(), List.of(), Map.of(), false, false,
                RestartPolicy.defaults(), ReadinessPolicy.defaults(), 5, 5, Boolean.TRUE);
    }
}
