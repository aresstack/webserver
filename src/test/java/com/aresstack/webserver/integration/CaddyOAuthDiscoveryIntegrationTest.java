package com.aresstack.webserver.integration;

import com.aresstack.webserver.domain.AcmeConfiguration;
import com.aresstack.webserver.domain.DomainName;
import com.aresstack.webserver.domain.OAuthClientConfiguration;
import com.aresstack.webserver.domain.OAuthConfiguration;
import com.aresstack.webserver.domain.Site;
import com.aresstack.webserver.domain.SiteAuthentication;
import com.aresstack.webserver.domain.Upstream;
import com.aresstack.webserver.domain.WebServerConfiguration;
import com.aresstack.webserver.infrastructure.caddy.CaddyAdminClient;
import com.aresstack.webserver.infrastructure.caddy.CaddyConfigurationAdapter;
import com.aresstack.webserver.infrastructure.caddy.CaddyProcessManager;
import com.aresstack.webserver.infrastructure.caddy.CaddyfileRenderer;
import com.aresstack.webserver.infrastructure.caddy.RuntimeDirectories;
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
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Caddy-in-the-loop für die OAuth-Discovery: zwei GESCHÜTZTE Sites laufen über
 * den echten Caddy-Prozess; /.well-known/* und /oauth/* werden an die zentrale
 * Loopback-Runtime geroutet und müssen PRO HOST die korrekte Ressourcen-URL
 * und den eigenen Issuer liefern — der Beweis, dass Caddy den Original-Host
 * durchreicht und keine Metadaten auf dem Weg verloren gehen. Der MCP-Pfad
 * der Site landet weiterhin beim Backend.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CaddyOAuthDiscoveryIntegrationTest {

    private static final String ASKAI = "askai.aresstack.de";
    private static final String PYLOROS = "pyloros.aresstack.de";

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();

    private Path root;
    private CaddyProcessManager caddy;
    private OAuthRuntime oauthRuntime;
    private HttpServer backend;
    private int httpPort;

    @BeforeAll
    void setUp() throws Exception {
        Path binary = Path.of(System.getProperty("caddy.binary", ""));
        assumeTrue(Files.exists(binary), "Caddy binary missing — run: gradlew downloadCaddy");

        root = Files.createTempDirectory("webserver-oauth-it");
        RuntimeDirectories directories = new RuntimeDirectories(root);
        directories.ensureExist();
        Files.createDirectories(directories.caddyBinary().getParent());
        Files.copy(binary, directories.caddyBinary());

        backend = HttpServer.create(new InetSocketAddress(
                InetAddress.getLoopbackAddress(), 0), 0);
        backend.createContext("/", exchange -> {
            byte[] body = "backend".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        backend.start();

        oauthRuntime = new OAuthRuntime(root, message -> { }, 0);
        oauthRuntime.apply(new OAuthConfiguration(true, "https://auth.aresstack.de",
                List.of(new OAuthClientConfiguration("chatgpt", "secret",
                        List.of("https://chatgpt.com")))));

        httpPort = freePort();
        int adminPort = freePort();
        CaddyfileRenderer renderer = new CaddyfileRenderer(
                CaddyfileRenderer.Options.localHttp(adminPort, httpPort, oauthRuntime.port()));
        CaddyConfigurationAdapter writer = new CaddyConfigurationAdapter(directories, renderer);
        CaddyAdminClient admin = new CaddyAdminClient(URI.create("http://127.0.0.1:" + adminPort));
        caddy = new CaddyProcessManager(directories, admin);

        Upstream backendUpstream = Upstream.parse(
                "http://127.0.0.1:" + backend.getAddress().getPort());
        WebServerConfiguration configuration = new WebServerConfiguration(
                new DomainName("aresstack.de"), AcmeConfiguration.withoutEmail(),
                backendUpstream,
                List.of(
                        protectedSite(ASKAI, backendUpstream),
                        protectedSite(PYLOROS, backendUpstream)),
                80, 443,
                new OAuthConfiguration(true, "https://auth.aresstack.de",
                        List.of(new OAuthClientConfiguration("chatgpt", "secret",
                                List.of("https://chatgpt.com")))));
        writer.persistGenerated(writer.render(configuration));
        caddy.start();
    }

    private static Site protectedSite(String host, Upstream upstream) {
        return new Site(new DomainName(host), Optional.of(upstream), List.of(), true,
                Optional.empty(), Optional.of(SiteAuthentication.centralOAuth()));
    }

    @AfterAll
    void tearDown() {
        if (caddy != null && caddy.isRunning()) {
            caddy.stop();
        }
        if (oauthRuntime != null) {
            oauthRuntime.stop();
        }
        if (backend != null) {
            backend.stop(0);
        }
    }

    @Test
    void protectedResourceMetadataArePerHostThroughRealCaddy() throws Exception {
        for (String host : List.of(ASKAI, PYLOROS)) {
            JsonNode metadata = getJson(host, "/.well-known/oauth-protected-resource");
            assertEquals("https://" + host + "/", metadata.get("resource").asText(),
                    "resource must identify the site itself, not the central fallback");
            assertEquals("https://" + host,
                    metadata.get("authorization_servers").get(0).asText());

            JsonNode server = getJson(host, "/.well-known/oauth-authorization-server");
            assertEquals("https://" + host, server.get("issuer").asText());
        }
    }

    @Test
    void oauthEndpointsReachTheRuntimeWhileMcpStaysAtTheBackend() throws Exception {
        // /oauth/* landet an der Runtime (hier: 400 wegen fehlender Parameter,
        // nicht die Backend-Antwort).
        HttpResponse<String> authorize = get(ASKAI, "/oauth/authorize");
        assertEquals(400, authorize.statusCode());
        assertTrue(authorize.body().contains("unsupported_response_type"), authorize.body());

        // Der MCP-/Anwendungspfad geht weiterhin an das Backend der Site.
        HttpResponse<String> root = get(ASKAI, "/anything");
        assertEquals(200, root.statusCode());
        assertEquals("backend", root.body());
    }

    private HttpResponse<String> get(String host, String path) throws Exception {
        return http.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + httpPort + path))
                        .header("Host", host).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode getJson(String host, String path) throws Exception {
        HttpResponse<String> response = get(host, path);
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body());
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }
}
