package com.aresstack.webserver.integration;

import com.aresstack.webserver.application.ApplyConfiguration;
import com.aresstack.webserver.domain.AcmeConfiguration;
import com.aresstack.webserver.domain.DomainName;
import com.aresstack.webserver.domain.OAuthClientConfiguration;
import com.aresstack.webserver.domain.OAuthConfiguration;
import com.aresstack.webserver.domain.ScopeDecision;
import com.aresstack.webserver.domain.ScopeEntry;
import com.aresstack.webserver.domain.ScopePolicy;
import com.aresstack.webserver.domain.Site;
import com.aresstack.webserver.domain.SiteAuthentication;
import com.aresstack.webserver.domain.Upstream;
import com.aresstack.webserver.domain.WebServerConfiguration;
import com.aresstack.webserver.infrastructure.caddy.CaddyAdminClient;
import com.aresstack.webserver.infrastructure.caddy.CaddyConfigurationAdapter;
import com.aresstack.webserver.infrastructure.caddy.CaddyProcessManager;
import com.aresstack.webserver.infrastructure.caddy.CaddyfileRenderer;
import com.aresstack.webserver.infrastructure.caddy.RuntimeDirectories;
import com.aresstack.webserver.infrastructure.configuration.JsonConfigurationRepository;
import com.aresstack.webserver.infrastructure.oauth.OAuthRuntime;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The headless registration path end to end, on its own root and its own ports.
 *
 * <p>Everything the launcher wires up is real here: the configuration repository, the
 * Caddy process, {@link ApplyConfiguration}, the OAuth runtime and the headless sink.
 * A service reports its scopes to {@code /internal/scopes} exactly as a managed
 * application would, and the test then checks the configuration file on disk, the
 * surviving operator decisions, and the publicly advertised {@code scopes_supported}
 * through the real reverse proxy.
 *
 * <p>Unit tests cover the merge rules; what they cannot show is that a registration
 * is persisted by the real apply step and is still there after a restart.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
class HeadlessScopeRegistrationIntegrationTest {

    private static final String HOST = "pyloros.test.invalid";

    private final ObjectMapper json = new ObjectMapper();
    // HTTP/1.1 pinned: the h2c upgrade dance against a just-reloaded Caddy shows up
    // as an EOF on the first request and would make this test flaky.
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3)).build();

    private Path root;
    private RuntimeDirectories directories;
    private JsonConfigurationRepository repository;
    private CaddyProcessManager caddy;
    private OAuthRuntime oauthRuntime;
    private HttpServer backend;
    private int httpPort;

    /** The applied configuration the launcher shares between watcher and sink. */
    private final AtomicReference<WebServerConfiguration> applied = new AtomicReference<>();
    private final Object registrationLock = new Object();
    private final List<String> log = new ArrayList<>();

    @BeforeAll
    void startIsolatedStack() throws Exception {
        Path binary = Path.of(System.getProperty("caddy.binary", ""));
        assumeTrue(Files.exists(binary), "Caddy binary missing - run: gradlew downloadCaddy");

        // Own root, own ports: the production instance is never touched.
        root = Files.createTempDirectory("webserver-headless-it");
        directories = new RuntimeDirectories(root);
        directories.ensureExist();
        Files.createDirectories(directories.caddyBinary().getParent());
        Files.copy(binary, directories.caddyBinary());

        backend = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        backend.createContext("/", exchange -> {
            byte[] body = "backend".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        backend.start();

        repository = new JsonConfigurationRepository(directories.configFile());

        // Starting point: an operator who denied one scope and added one by hand.
        WebServerConfiguration initial = configuration(new SiteAuthentication(List.of(),
                new ScopePolicy(true, List.of(
                        ScopeEntry.manual("mcp"),
                        ScopeEntry.manual("pyloros:write").withDecision(ScopeDecision.DENY),
                        ScopeEntry.manual("git:write")))));

        // The OAuth service first, because Caddy has to be told where to route.
        oauthRuntime = new OAuthRuntime(root, log::add, 0);
        oauthRuntime.apply(initial.oauth(), OAuthRuntime.resourceScopes(initial));

        httpPort = freePort();
        int adminPort = freePort();
        CaddyfileRenderer renderer = new CaddyfileRenderer(
                CaddyfileRenderer.Options.localHttp(adminPort, httpPort, oauthRuntime.port()));
        CaddyConfigurationAdapter adapter = new CaddyConfigurationAdapter(directories, renderer);
        CaddyAdminClient admin = new CaddyAdminClient(URI.create("http://127.0.0.1:" + adminPort));
        caddy = new CaddyProcessManager(directories, admin);
        ApplyConfiguration applyConfiguration =
                new ApplyConfiguration(adapter, admin, repository);

        repository.save(initial);
        adapter.persistGenerated(adapter.render(initial));
        caddy.start();
        applied.set(initial);

        oauthRuntime.registrationSink(new HeadlessSink(applyConfiguration));
    }

    /** Mirrors the launcher's commit step: persist, then reapply the runtime. */
    private final class HeadlessSink implements OAuthRuntime.ScopeRegistrationSink {
        private final ApplyConfiguration applyConfiguration;

        private HeadlessSink(ApplyConfiguration applyConfiguration) {
            this.applyConfiguration = applyConfiguration;
        }

        @Override
        public boolean register(String host, List<ScopeEntry> scopes) {
            synchronized (registrationLock) {
                var merged = com.aresstack.webserver.application.ScopeRegistration.merge(
                        applied.get(), host, scopes);
                if (merged.isEmpty()) {
                    return false;
                }
                applyConfiguration.apply(merged.get());
                oauthRuntime.apply(merged.get().oauth(),
                        OAuthRuntime.resourceScopes(merged.get()));
                applied.set(merged.get());
                return true;
            }
        }
    }

    @AfterAll
    void stopIsolatedStack() {
        if (caddy != null && caddy.isRunning()) {
            caddy.stop();
        }
        if (oauthRuntime != null) {
            oauthRuntime.stop();
        }
        if (backend != null) {
            backend.stop(0);
        }
        if (root != null) {
            try (var paths = Files.walk(root)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (java.io.IOException stillLocked) {
                        // A just-stopped caddy.exe can still hold its binary.
                    }
                });
            } catch (java.io.IOException ignored) {
                // Leaving a temp directory behind must not fail the run.
            }
        }
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    void aServiceReportIsPersistedAndVisibleToClientsAndSurvivesARestart() throws Exception {
        // 1. A service reports its catalogue the way a managed application does: to the
        //    loopback endpoint, with the token bound to its own host.
        HttpResponse<String> response = report("""
                {"host":"%s","scopes":[
                  {"name":"mcp","description":"Base access"},
                  {"name":"pyloros:read","description":"Read-only tools"},
                  {"name":"pyloros:write","description":"Writing tools"}
                ]}""".formatted(HOST));
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(json.readTree(response.body()).get("changed").asBoolean(),
                "the report must be accepted and persisted, not merely acknowledged");

        // 2. It is in the configuration file, not just in memory.
        ScopePolicy onDisk = reload();
        assertEquals(List.of("mcp", "pyloros:write", "git:write", "pyloros:read"),
                onDisk.entries().stream().map(ScopeEntry::name).toList());
        assertTrue(entry(onDisk, "pyloros:read").registered(),
                "a reported scope is marked as reported");

        // 3. The operator's decisions are untouched.
        assertEquals(ScopeDecision.DENY, entry(onDisk, "pyloros:write").decision(),
                "a report describes capabilities and must not undo a deny");
        assertFalse(entry(onDisk, "git:write").registered(),
                "a hand-written scope stays manual and must not be dropped");
        assertTrue(onDisk.allowByDefault());

        // 4. It took effect at runtime: a client sees the new scope through real Caddy,
        //    and the denied one is absent.
        List<String> advertised = scopesSupported();
        assertTrue(advertised.contains("pyloros:read"),
                "the reported scope must become requestable: " + advertised);
        assertFalse(advertised.contains("pyloros:write"),
                "a denied scope must not be advertised: " + advertised);

        // 5. Restart: a fresh repository and a fresh OAuth runtime read the state back
        //    from the same root, exactly as the launcher does on the next start.
        WebServerConfiguration afterRestart =
                new JsonConfigurationRepository(directories.configFile()).load();
        OAuthRuntime restarted = new OAuthRuntime(root, log::add, 0);
        try {
            restarted.apply(afterRestart.oauth(), OAuthRuntime.resourceScopes(afterRestart));

            SiteAuthentication authentication = authentication(afterRestart);
            assertEquals(List.of("mcp", "pyloros:write", "git:write", "pyloros:read"),
                    authentication.scopePolicy().entries().stream()
                            .map(ScopeEntry::name).toList(),
                    "the catalogue must still be there after a restart");
            assertEquals(ScopeDecision.DENY,
                    entry(authentication.scopePolicy(), "pyloros:write").decision());
            assertEquals(List.of("mcp", "git:write", "pyloros:read"),
                    authentication.effectiveScopes(),
                    "the effective scopes must be unchanged by the restart");
            assertEquals(List.of("mcp", "git:write", "pyloros:read"),
                    OAuthRuntime.resourceScopes(afterRestart).get(HOST),
                    "the restarted service must allow exactly the approved scopes");
        } finally {
            restarted.stop();
        }
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    void anUnchangedReportIsNotWrittenAgain() throws Exception {
        String body = """
                {"host":"%s","scopes":[{"name":"mcp","description":"Base access"}]}"""
                .formatted(HOST);
        report(body);

        HttpResponse<String> repeat = report(body);
        assertEquals(200, repeat.statusCode(), repeat.body());
        assertFalse(json.readTree(repeat.body()).get("changed").asBoolean(),
                "an unchanged catalogue must not rewrite the configuration on every start");
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    void aTokenBoundToAnotherHostCannotDescribeThisResource() throws Exception {
        HttpResponse<String> foreign = http.send(HttpRequest.newBuilder()
                        .uri(URI.create(oauthRuntime.registrationUrl()))
                        .header("Authorization", "Bearer " + "x".repeat(43))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"host\":\"" + HOST + "\",\"scopes\":[]}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(401, foreign.statusCode(), foreign.body());
        assertFalse(foreign.body().contains(HOST),
                "a rejection must not echo back any configuration");
    }

    private HttpResponse<String> report(String body) throws Exception {
        return http.send(HttpRequest.newBuilder()
                        .uri(URI.create(oauthRuntime.registrationUrl()))
                        .header("Authorization",
                                "Bearer " + oauthRuntime.registrationToken(HOST))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** What a client is told it may request, fetched through the real proxy. */
    private List<String> scopesSupported() throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + httpPort
                                + "/.well-known/oauth-authorization-server"))
                        .header("Host", HOST).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        List<String> scopes = new ArrayList<>();
        for (JsonNode node : json.readTree(response.body()).withArray("scopes_supported")) {
            scopes.add(node.asText());
        }
        return scopes;
    }

    private ScopePolicy reload() {
        return authentication(new JsonConfigurationRepository(directories.configFile()).load())
                .scopePolicy();
    }

    private static SiteAuthentication authentication(WebServerConfiguration configuration) {
        return configuration.sites().stream()
                .filter(site -> site.host().value().equals(HOST))
                .findFirst().orElseThrow()
                .authentication().orElseThrow();
    }

    private static ScopeEntry entry(ScopePolicy policy, String name) {
        return policy.entries().stream().filter(e -> e.name().equals(name))
                .findFirst().orElseThrow();
    }

    private WebServerConfiguration configuration(SiteAuthentication authentication) {
        Upstream upstream = Upstream.parse(
                "http://127.0.0.1:" + backend.getAddress().getPort());
        return new WebServerConfiguration(new DomainName("test.invalid"),
                AcmeConfiguration.withoutEmail(), upstream,
                List.of(new Site(new DomainName(HOST), Optional.of(upstream), List.of(), true,
                        Optional.empty(), Optional.of(authentication))),
                80, 443,
                new OAuthConfiguration(true, "https://" + HOST,
                        List.of(new OAuthClientConfiguration("test-client", "test-secret",
                                List.of("https://chatgpt.com")))));
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }
}
