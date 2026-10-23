package com.aresstack.webserver.infrastructure.oauth;

import com.aresstack.webserver.domain.OAuthClientConfiguration;
import com.aresstack.webserver.domain.OAuthConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A changed OAuth configuration must reach the running service, and the published
 * scopes must come from that configuration.
 *
 * <p>The client registry is built once when the service starts, so re-applying is the
 * only way a newly registered client can work without restarting the webserver.
 * {@code scopes_supported} used to be hardcoded to {@code mcp} regardless of what a
 * site had configured.
 */
class OAuthRuntimeReconfigurationTest {

    private static final String HOST = "pyloros.example.org";
    private static final String FALLBACK_ISSUER = "https://auth.example.org";
    private static final String REDIRECT = "https://chatgpt.com/connector_oauth_redirect";
    private static final String CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    @TempDir Path root;
    private OAuthRuntime runtime;

    @AfterEach
    void stopRuntime() {
        if (runtime != null) {
            runtime.stop();
        }
    }

    private void apply(List<OAuthClientConfiguration> clients, List<String> scopes) {
        if (runtime == null) {
            runtime = new OAuthRuntime(root, message -> { }, 0);
        }
        runtime.apply(new OAuthConfiguration(true, FALLBACK_ISSUER, clients),
                Map.of(HOST, scopes));
    }

    private static OAuthClientConfiguration client(String id) {
        return new OAuthClientConfiguration(id, id + "-secret", List.of("https://chatgpt.com"));
    }

    @Test
    void aNewlyRegisteredClientWorksWithoutRestartingTheService() throws Exception {
        apply(List.of(client("first")), List.of("mcp"));
        int portBefore = runtime.port();
        assertEquals(400, authorize("second").statusCode(),
                "an unregistered client must be rejected");

        // Same lifecycle call the controller and the headless watcher now make.
        apply(List.of(client("first"), client("second")), List.of("mcp"));

        assertEquals(302, authorize("second").statusCode(),
                "the re-applied configuration must be live");
        assertEquals(302, authorize("first").statusCode(), "the existing client keeps working");
        assertTrue(runtime.isRunning());
        assertTrue(portBefore > 0);
    }

    @Test
    void aScopeOnlyChangeTakesEffectWithoutRestartingTheService() throws Exception {
        apply(List.of(client("first")), List.of("mcp"));
        int portBefore = runtime.port();
        assertEquals(400, authorize("first", "pyloros:read").statusCode(),
                "an unapproved scope must be rejected");

        // What a scope registration triggers: same clients, a larger catalogue.
        apply(List.of(client("first")), List.of("mcp", "pyloros:read"));

        assertEquals(portBefore, runtime.port(),
                "a scope change must not restart the service: the registration that "
                        + "causes it arrives through this very server, and a restart "
                        + "would kill the response and drop pending authorization codes");
        assertEquals(302, authorize("first", "pyloros:read").statusCode(),
                "the new scope must be requestable immediately");
        assertEquals(List.of("mcp", "pyloros:read"), scopesSupported());
    }

    @Test
    void aWithdrawnScopeAlsoTakesEffectWithoutRestarting() throws Exception {
        apply(List.of(client("first")), List.of("mcp", "pyloros:read"));
        int portBefore = runtime.port();

        apply(List.of(client("first")), List.of("mcp"));

        assertEquals(portBefore, runtime.port());
        assertEquals(400, authorize("first", "pyloros:read").statusCode(),
                "a withdrawn scope must stop being granted at once");
        assertEquals(List.of("mcp"), scopesSupported());
    }

    private List<String> scopesSupported() throws Exception {
        List<String> scopes = new java.util.ArrayList<>();
        for (JsonNode node : getJson("/.well-known/oauth-authorization-server")
                .withArray("scopes_supported")) {
            scopes.add(node.asText());
        }
        return scopes;
    }

    @Test
    void aRemovedClientStopsWorkingImmediately() throws Exception {
        apply(List.of(client("first"), client("second")), List.of("mcp"));
        assertEquals(302, authorize("second").statusCode());

        apply(List.of(client("first")), List.of("mcp"));

        assertEquals(400, authorize("second").statusCode(),
                "a removed client must lose access at once, not after a restart");
    }

    @Test
    void publishedScopesComeFromTheSiteConfiguration() throws Exception {
        apply(List.of(client("first")), List.of("mcp", "mcp:read"));

        JsonNode authorizationServer =
                getJson("/.well-known/oauth-authorization-server").get("scopes_supported");
        JsonNode protectedResource =
                getJson("/.well-known/oauth-protected-resource").get("scopes_supported");

        assertEquals(List.of("mcp", "mcp:read"), scopeList(authorizationServer));
        assertEquals(List.of("mcp", "mcp:read"), scopeList(protectedResource));

        // Change the configuration: the metadata must follow it.
        apply(List.of(client("first")), List.of("mcp"));
        assertEquals(List.of("mcp"),
                scopeList(getJson("/.well-known/oauth-authorization-server")
                        .get("scopes_supported")));
    }

    @Test
    void aScopeTheSiteDoesNotAllowIsRejectedAtTheAuthorizationEndpoint() throws Exception {
        apply(List.of(client("first")), List.of("mcp"));

        HttpResponse<String> response = authorize("first", "mcp admin");

        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("invalid_scope"), response.body());
    }

    @Test
    void anAllowedScopeSubsetIsAccepted() throws Exception {
        apply(List.of(client("first")), List.of("mcp", "mcp:read"));

        assertEquals(302, authorize("first", "mcp:read").statusCode());
    }

    private List<String> scopeList(JsonNode array) {
        return java.util.stream.StreamSupport.stream(array.spliterator(), false)
                .map(JsonNode::asText)
                .toList();
    }

    private HttpResponse<String> authorize(String clientId) throws Exception {
        return authorize(clientId, "mcp");
    }

    private HttpResponse<String> authorize(String clientId, String scope) throws Exception {
        String query = "?response_type=code&client_id=" + clientId
                + "&redirect_uri=" + java.net.URLEncoder.encode(REDIRECT,
                        java.nio.charset.StandardCharsets.UTF_8)
                + "&scope=" + java.net.URLEncoder.encode(scope,
                        java.nio.charset.StandardCharsets.UTF_8)
                + "&code_challenge=" + CHALLENGE + "&code_challenge_method=S256"
                + "&resource=" + java.net.URLEncoder.encode("https://" + HOST + "/",
                        java.nio.charset.StandardCharsets.UTF_8);
        return http.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + runtime.port()
                                + "/oauth/authorize" + query))
                        .header("Host", HOST).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode getJson(String path) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + runtime.port() + path))
                        .header("Host", HOST).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body());
    }
}
