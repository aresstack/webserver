package com.aresstack.webserver.ui;

import com.aresstack.webserver.domain.OAuthClientConfiguration;
import com.aresstack.webserver.infrastructure.caddy.RuntimeDirectories;
import com.aresstack.webserver.infrastructure.configuration.JsonConfigurationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;


import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OAuth clients must be manageable without hand-editing {@code webserver.json}.
 *
 * <p>These tests cover the controller side of that: creating, changing and removing
 * clients, protected storage of the secret, and the guarantee that no secret reaches
 * the log. The DPAPI call is replaced by a stub so the test does not depend on
 * PowerShell; the production path uses {@code WindowsDpapiSecrets.protect}.
 */
@EnabledOnOs(OS.WINDOWS)
class OAuthClientManagementTest {

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

    private static final String ISSUER = "https://pyloros.example.com";

    private WebServerController controller(UserLog log) {
        RuntimeDirectories directories = new RuntimeDirectories(root);
        directories.ensureExist();
        // applyNew validates the rendered Caddyfile with the real binary, as in
        // WebServerControllerConcurrentMutationsTest.
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
        WebServerController controller = new WebServerController(directories, log);
        // Stand-in for DPAPI: marks the value as protected without touching Windows.
        controller.secretProtector(secret ->
                secret.startsWith("dpapi:") ? secret : "dpapi:" + secret);
        return controller;
    }

    private JsonConfigurationRepository persisted() {
        return new JsonConfigurationRepository(new RuntimeDirectories(root).configFile());
    }

    @Test
    void addsAClientAndPersistsItProtected() {
        WebServerController controller = controller(new UserLog());

        controller.updateOAuthClients(ISSUER, List.of(
                new OAuthClientConfiguration("angel", "s3cret", List.of("https://chatgpt.com"))));

        var oauth = persisted().load().oauth();
        assertTrue(oauth.enabled(), "a registered client enables the service");
        assertEquals(ISSUER, oauth.issuer());
        assertEquals(1, oauth.clients().size());
        OAuthClientConfiguration stored = oauth.clients().get(0);
        assertEquals("angel", stored.clientId());
        assertEquals(List.of("https://chatgpt.com"), stored.redirectUris());
        assertTrue(stored.clientSecret().startsWith("dpapi:"),
                "the secret must be stored protected, not in clear text");
        assertFalse(stored.clientSecret().contains("s3cret")
                && !stored.clientSecret().startsWith("dpapi:"));
    }

    @Test
    void changesClientIdSecretAndRedirectUris() {
        WebServerController controller = controller(new UserLog());
        controller.updateOAuthClients(ISSUER, List.of(
                new OAuthClientConfiguration("old", "first", List.of("https://chatgpt.com"))));

        controller.updateOAuthClients(ISSUER, List.of(
                new OAuthClientConfiguration("angel", "second",
                        List.of("https://chatgpt.com", "https://chat.openai.com"))));

        var clients = persisted().load().oauth().clients();
        assertEquals(1, clients.size());
        assertEquals("angel", clients.get(0).clientId());
        assertEquals(2, clients.get(0).redirectUris().size());
        assertEquals("dpapi:second", clients.get(0).clientSecret());
    }

    @Test
    void keepsAnAlreadyProtectedSecretUnchanged() {
        WebServerController controller = controller(new UserLog());
        controller.updateOAuthClients(ISSUER, List.of(
                new OAuthClientConfiguration("angel", "dpapi:previously-protected",
                        List.of("https://chatgpt.com"))));

        assertEquals("dpapi:previously-protected",
                persisted().load().oauth().clients().get(0).clientSecret(),
                "protection must be idempotent so editing another field cannot double-wrap");
    }

    @Test
    void removingEveryClientDisablesTheService() {
        WebServerController controller = controller(new UserLog());
        controller.updateOAuthClients(ISSUER, List.of(
                new OAuthClientConfiguration("angel", "s3cret", List.of("https://chatgpt.com"))));

        controller.updateOAuthClients(ISSUER, List.of());

        var oauth = persisted().load().oauth();
        assertFalse(oauth.enabled(), "OAuthConfiguration cannot be enabled without a client");
        assertTrue(oauth.clients().isEmpty());
    }

    @Test
    void neverWritesASecretToTheLog() {
        UserLog log = new UserLog();
        WebServerController controller = controller(log);

        controller.updateOAuthClients(ISSUER, List.of(
                new OAuthClientConfiguration("angel", "top-secret-value",
                        List.of("https://chatgpt.com"))));

        String logged = log.toString() + " " + String.join(" ", logLines(log));
        assertFalse(logged.contains("top-secret-value"), "the log must not contain the secret");
        assertFalse(logged.contains("dpapi:top-secret-value"),
                "not even the protected form belongs in the log");
    }

    @Test
    void rejectsAnInvalidClientBeforeAnythingIsPersisted() {
        WebServerController controller = controller(new UserLog());

        // http redirect URIs are refused by the domain record.
        assertThrows(RuntimeException.class, () -> controller.updateOAuthClients(ISSUER, List.of(
                new OAuthClientConfiguration("angel", "s3cret", List.of("http://chatgpt.com")))));

        assertFalse(Files.exists(new RuntimeDirectories(root).configFile())
                        && persisted().load().oauth().enabled(),
                "a rejected change must not enable the service");
    }

    /** UserLog keeps its entries for the GUI; read them back without assuming a format. */
    private static List<String> logLines(UserLog log) {
        try {
            var method = UserLog.class.getMethod("entries");
            Object value = method.invoke(log);
            if (value instanceof List<?> list) {
                return list.stream().map(String::valueOf).toList();
            }
        } catch (ReflectiveOperationException ignored) {
            // No accessor: the toString check above still guards the obvious leak.
        }
        return List.of();
    }
}
