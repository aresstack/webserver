package com.aresstack.webserver.ui;

import com.aresstack.webserver.domain.DomainName;
import com.aresstack.webserver.domain.ScopeDecision;
import com.aresstack.webserver.domain.ScopeEntry;
import com.aresstack.webserver.domain.ScopePolicy;
import com.aresstack.webserver.domain.Site;
import com.aresstack.webserver.domain.SiteAuthentication;
import com.aresstack.webserver.infrastructure.caddy.RuntimeDirectories;
import com.aresstack.webserver.infrastructure.configuration.JsonConfigurationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;


import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Allow/Deny decisions must survive a restart, and a registration must describe
 * capabilities without overriding what the operator decided.
 */
@EnabledOnOs(OS.WINDOWS)
class ScopePolicyPersistenceTest {

    private Path root;

    /**
     * Own temp directory instead of {@code @TempDir}: these tests copy the 49 MB
     * caddy.exe and run it, and JUnit's strict cleanup fails on the CI runner with an
     * IOException while the binary is still locked. Best-effort deletion keeps the
     * test result about the behaviour under test.
     */
    @org.junit.jupiter.api.BeforeEach
    void createRoot() throws java.io.IOException {
        root = Files.createTempDirectory("webserver-oauth-test");
    }

    @org.junit.jupiter.api.AfterEach
    void removeRoot() {
        if (root == null) {
            return;
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

    private static final DomainName HOST = new DomainName("pyloros.example.org");

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
        WebServerController controller = new WebServerController(directories, new UserLog());
        controller.secretProtector(secret -> "dpapi:" + secret);
        // A central-OAuth site is only valid while the OAuth service is enabled, which
        // requires at least one registered client.
        controller.updateOAuthClients("https://pyloros.example.org", List.of(
                new com.aresstack.webserver.domain.OAuthClientConfiguration(
                        "app", "secret", List.of("https://chatgpt.com"))));
        controller.addSite(new Site(HOST,
                Optional.of(com.aresstack.webserver.domain.Upstream.parse("http://127.0.0.1:8081")),
                List.of(), true, Optional.empty(),
                Optional.of(SiteAuthentication.centralOAuth())));
        return controller;
    }

    private SiteAuthentication reloadAuthentication() {
        return new JsonConfigurationRepository(new RuntimeDirectories(root).configFile())
                .load().sites().stream()
                .filter(site -> site.host().equals(HOST))
                .findFirst().orElseThrow()
                .authentication().orElseThrow();
    }

    @Test
    void aFreshSiteStartsWithAllowAllOverItsKnownScopes() {
        controller();

        SiteAuthentication authentication = reloadAuthentication();
        assertTrue(authentication.scopePolicy().allowByDefault());
        assertEquals(List.of("mcp"), authentication.effectiveScopes());
    }

    @Test
    void storesDenyAllWithIndividualAllowOverride() {
        WebServerController controller = controller();

        controller.updateSiteScopePolicy(HOST, new ScopePolicy(false, List.of(
                ScopeEntry.manual("mcp").withDecision(ScopeDecision.ALLOW),
                ScopeEntry.manual("mcp:write"))));

        SiteAuthentication reloaded = reloadAuthentication();
        assertFalse(reloaded.scopePolicy().allowByDefault());
        assertEquals(List.of("mcp"), reloaded.effectiveScopes(),
                "deny all plus one allow override must survive a reload");
    }

    @Test
    void storesAManuallyAddedScopeWithoutAnyRegistration() {
        WebServerController controller = controller();

        controller.updateSiteScopePolicy(HOST, new ScopePolicy(true, List.of(
                ScopeEntry.manual("mcp"), ScopeEntry.manual("git:write"))));

        SiteAuthentication reloaded = reloadAuthentication();
        assertEquals(List.of("mcp", "git:write"), reloaded.effectiveScopes());
        assertFalse(reloaded.scopePolicy().entries().get(1).registered(),
                "a hand-written scope stays marked as manual");
    }

    @Test
    void registrationAddsScopesButKeepsExistingDecisions() {
        WebServerController controller = controller();
        controller.updateSiteScopePolicy(HOST, new ScopePolicy(true, List.of(
                ScopeEntry.manual("mcp").withDecision(ScopeDecision.DENY))));

        boolean changed = controller.registerSiteScopes(HOST, List.of(
                ScopeEntry.registered("mcp", "Base access"),
                ScopeEntry.registered("mcp:read", "Read-only tools")));

        assertTrue(changed);
        SiteAuthentication reloaded = reloadAuthentication();
        assertEquals(ScopeDecision.DENY, reloaded.scopePolicy().entries().stream()
                        .filter(entry -> entry.name().equals("mcp")).findFirst().orElseThrow()
                        .decision(),
                "a registration describes capabilities and must not undo a deny");
        assertEquals(List.of("mcp:read"), reloaded.effectiveScopes());
    }

    @Test
    void repeatingTheSameRegistrationChangesNothing() {
        WebServerController controller = controller();
        List<ScopeEntry> reported = List.of(ScopeEntry.registered("mcp", "Base access"));

        assertTrue(controller.registerSiteScopes(HOST, reported));
        assertFalse(controller.registerSiteScopes(HOST, reported),
                "an unchanged catalogue must not rewrite the configuration on every start");
    }

    @Test
    void aRegistrationForAnUnprotectedHostIsIgnoredInsteadOfFailing() {
        WebServerController controller = controller();

        assertFalse(controller.registerSiteScopes(new DomainName("unknown.example.org"),
                        List.of(ScopeEntry.registered("mcp", ""))),
                "a service must not break because its site is not protected");
    }

    @Test
    void aLegacyConfigurationWithoutACatalogueStillLoads() throws Exception {
        controller();
        Path configFile = new RuntimeDirectories(root).configFile();
        String legacy = Files.readString(configFile)
                .replaceAll("(?s),\\s*\"allowAllScopes\".*?\\]", "");
        Files.writeString(configFile, legacy);

        SiteAuthentication reloaded = reloadAuthentication();
        assertEquals(List.of("mcp"), reloaded.effectiveScopes(),
                "an older configuration must keep working without migration");
        assertTrue(reloaded.scopePolicy().allowByDefault());
    }
}
