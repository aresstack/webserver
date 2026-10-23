package com.aresstack.webserver.infrastructure.oauth;

import com.aresstack.webserver.domain.OAuthClientConfiguration;
import com.aresstack.webserver.domain.OAuthConfiguration;
import com.aresstack.webserver.domain.ScopeEntry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The optional registration endpoint: a service may report its scopes, and must not
 * be required to.
 */
class ScopeRegistrationEndpointTest {

    private static final String HOST = "pyloros.example.org";

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1).build();

    @TempDir Path root;
    private OAuthRuntime runtime;
    private final List<String> registrations = new ArrayList<>();

    @BeforeEach
    void startRuntime() {
        runtime = new OAuthRuntime(root, message -> { }, 0);
        runtime.registrationSink((host, scopes) -> {
            registrations.add(host + "=" + scopes.stream().map(ScopeEntry::name).toList());
            return !scopes.isEmpty();
        });
        runtime.apply(new OAuthConfiguration(true, "https://auth.example.org",
                        List.of(new OAuthClientConfiguration("app", "secret",
                                List.of("https://chatgpt.com")))),
                Map.of(HOST, List.of("mcp")));
    }

    @AfterEach
    void stopRuntime() {
        if (runtime != null) {
            runtime.stop();
        }
    }

    @Test
    void acceptsAReportedCatalogueWithAValidToken() throws Exception {
        HttpResponse<String> response = post(runtime.registrationToken(HOST), """
                {"host":"%s","scopes":[
                  {"name":"mcp:read","description":"Read-only tools"},
                  {"name":"mcp:write","description":"Changing tools"}]}
                """.formatted(HOST));

        assertEquals(200, response.statusCode(), response.body());
        JsonNode body = json.readTree(response.body());
        assertEquals(HOST, body.path("host").asText());
        assertEquals(2, body.path("accepted").asInt());
        assertTrue(body.path("changed").asBoolean());
        assertEquals(List.of(HOST + "=[mcp:read, mcp:write]"), registrations);
    }

    @Test
    void rejectsAWrongOrMissingToken() throws Exception {
        assertEquals(401, post("not-the-token", body()).statusCode());

        HttpResponse<String> withoutHeader = http.send(HttpRequest.newBuilder()
                        .uri(endpoint())
                        .POST(HttpRequest.BodyPublishers.ofString(body()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, withoutHeader.statusCode());
        assertTrue(registrations.isEmpty(), "an unauthenticated report must not reach the sink");
    }

    @Test
    void rejectsGetAndMalformedBodies() throws Exception {
        HttpResponse<String> get = http.send(HttpRequest.newBuilder()
                        .uri(endpoint())
                        .header("Authorization", "Bearer " + runtime.registrationToken(HOST))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(405, get.statusCode());

        assertEquals(400, post(runtime.registrationToken(HOST), "not json").statusCode());
        assertEquals(400, post(runtime.registrationToken(HOST), "{\"scopes\":[]}").statusCode(),
                "a report without a host cannot be attributed");
        assertEquals(400, post(runtime.registrationToken(HOST),
                        "{\"host\":\"" + HOST + "\",\"scopes\":[{\"name\":\"has space\"}]}")
                .statusCode());
    }

    @Test
    void theTokenIsStableAcrossCallsAndDiffersPerService() {
        assertEquals(runtime.registrationToken(HOST), runtime.registrationToken(HOST));
        assertTrue(runtime.registrationToken(HOST).length() >= 40, "256 bit, url-safe");
        assertTrue(!runtime.registrationToken(HOST).equals(runtime.registrationToken("other.example.org")),
                "each service gets its own token");
    }

    @Test
    void aServiceCannotDescribeAnotherServicesResource() throws Exception {
        // Token of "other" against a report for HOST: the token is bound to the host.
        HttpResponse<String> response = post(runtime.registrationToken("other.example.org"),
                "{\"host\":\"" + HOST + "\",\"scopes\":[{\"name\":\"mcp:read\"}]}");

        assertEquals(401, response.statusCode());
        assertTrue(registrations.isEmpty());
    }

    @Test
    void aManipulatedHostIsRefusedAndTouchesNoFile() throws Exception {
        // The host used to be turned into a file name before the caller's token was
        // checked, and the backslash guard was escaped wrongly. With a drive letter
        // that produced an absolute path, so any local process could have a file
        // created wherever it liked. No credential was needed to get that far.
        Path registrationDirectory = root.resolve("data").resolve("oauth")
                .resolve("registration");
        Path outside = root.resolve("outside");
        Files.createDirectories(outside);
        String absolute = outside.toString().replace("/", BACKSLASH) + BACKSLASH + "evil";

        for (String host : List.of(absolute, "sub" + BACKSLASH + "nested", "../../escaped",
                "host:8080")) {
            HttpResponse<String> response = post("irrelevant",
                    "{" + quoted("host") + ":" + quoted(host) + ",\"scopes\":[]}");
            assertEquals(400, response.statusCode(),
                    "a manipulated host must be refused: " + host + " -> " + response.body());
        }

        assertTrue(isEmpty(outside), "no file may be created outside the data directory");
        assertTrue(!Files.exists(registrationDirectory) || isEmpty(registrationDirectory),
                "and none inside the registration directory either");
        assertTrue(registrations.isEmpty());
    }

    @Test
    void anUnconfiguredHostIsRefusedBeforeAnyTokenFileIsCreated() throws Exception {
        Path tokenFile = root.resolve("data").resolve("oauth").resolve("registration")
                .resolve("stranger.example.org.token");

        HttpResponse<String> response = post("irrelevant",
                "{\"host\":\"stranger.example.org\",\"scopes\":[]}");

        assertEquals(404, response.statusCode(), response.body());
        assertTrue(!Files.exists(tokenFile),
                "a host that is not a configured resource must not get a token file");
        assertTrue(registrations.isEmpty());
    }

    private static final String BACKSLASH = String.valueOf((char) 92);

    private static String quoted(String value) {
        return "\"" + value.replace(BACKSLASH, BACKSLASH + BACKSLASH) + "\"";
    }

    private static boolean isEmpty(Path directory) throws java.io.IOException {
        try (var entries = Files.list(directory)) {
            return entries.findAny().isEmpty();
        }
    }

    @Test
    void theRegistrationUrlPointsAtTheLoopbackEndpoint() {
        assertTrue(runtime.registrationUrl().startsWith("http://127.0.0.1:"),
                runtime.registrationUrl());
        assertTrue(runtime.registrationUrl().endsWith("/internal/scopes"));
    }

    @Test
    void refusesWhenNoSinkIsConfigured() throws Exception {
        runtime.registrationSink(null);

        assertEquals(503, post(runtime.registrationToken(HOST), body()).statusCode());
    }

    @Test
    void reportingNothingIsNotAnError() throws Exception {
        // A plugin that knows no scopes must keep working; the catalogue stays manual.
        HttpResponse<String> response = post(runtime.registrationToken(HOST),
                "{\"host\":\"" + HOST + "\",\"scopes\":[]}");

        assertEquals(200, response.statusCode());
        assertEquals(0, json.readTree(response.body()).path("accepted").asInt());
    }

    private URI endpoint() {
        return URI.create("http://127.0.0.1:" + runtime.port() + "/internal/scopes");
    }

    private String body() {
        return "{\"host\":\"" + HOST + "\",\"scopes\":[{\"name\":\"mcp:read\"}]}";
    }

    private HttpResponse<String> post(String token, String body) throws Exception {
        return http.send(HttpRequest.newBuilder()
                        .uri(endpoint())
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
