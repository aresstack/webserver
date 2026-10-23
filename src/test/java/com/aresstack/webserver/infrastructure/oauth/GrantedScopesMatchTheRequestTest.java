package com.aresstack.webserver.infrastructure.oauth;

import com.aresstack.webserver.domain.OAuthClientConfiguration;
import com.aresstack.webserver.domain.OAuthConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A token carries exactly the scopes that were requested and approved.
 *
 * <p>Earlier this implementation added every approved scope to the token so that an
 * approval would take effect for a connector that only asks for its base scope. That
 * was wrong: a connector must never receive more rights than it asked for, otherwise
 * connectors cannot be managed or tested independently. Approving a scope means it
 * <em>may be requested</em>, not that it is handed out.
 *
 * <p>Isolation is modelled here with two resources, {@code pyloros} and
 * {@code askai}, which must not influence each other.
 */
class GrantedScopesMatchTheRequestTest {

    private static final String PYLOROS = "pyloros.example.org";
    private static final String ASKAI = "askai.example.org";
    private static final String CLIENT = "chatgpt";
    private static final String SECRET = "client-secret";
    private static final String REDIRECT = "https://chatgpt.com/connector_oauth_redirect";
    private static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_1_1).build();

    @TempDir Path root;
    private OAuthRuntime runtime;

    @AfterEach
    void stopRuntime() {
        if (runtime != null) {
            runtime.stop();
        }
    }

    private void start(Map<String, List<String>> approvedPerHost) {
        if (runtime == null) {
            runtime = new OAuthRuntime(root, message -> { }, 0);
        }
        runtime.apply(new OAuthConfiguration(true, "https://auth.example.org",
                        List.of(new OAuthClientConfiguration(CLIENT, SECRET,
                                List.of("https://chatgpt.com")))),
                approvedPerHost);
    }

    @Test
    void requestingMcpYieldsOnlyMcpEvenThoughMoreIsApproved() throws Exception {
        start(Map.of(PYLOROS, List.of("mcp", "mcp:read", "pyloros:git.write")));

        JsonNode token = exchange(PYLOROS, "mcp");

        assertEquals("mcp", token.path("scope").asText());
        assertEquals("mcp", scopeClaim(token.path("access_token").asText()),
                "a connector must not receive scopes it did not request");
    }

    @Test
    void requestingPylorosYieldsOnlyPyloros() throws Exception {
        start(Map.of(PYLOROS, List.of("mcp", "pyloros")));

        String scope = scopeClaim(exchange(PYLOROS, "pyloros").path("access_token").asText());

        assertEquals("pyloros", scope);
        assertFalse(scope.contains("mcp"));
    }

    @Test
    void aDeniedScopeYieldsNoToken() throws Exception {
        // pyloros blocked, only mcp approved.
        start(Map.of(PYLOROS, List.of("mcp")));

        HttpResponse<String> authorize = authorize(PYLOROS, "pyloros");

        assertEquals(400, authorize.statusCode());
        assertTrue(authorize.body().contains("invalid_scope"), authorize.body());
    }

    @Test
    void blockingOneConnectorLeavesTheOtherWorking() throws Exception {
        start(Map.of(PYLOROS, List.of("mcp"), ASKAI, List.of("askai")));

        assertEquals(400, authorize(PYLOROS, "pyloros").statusCode(),
                "pyloros is blocked for its own resource");
        assertEquals("askai", scopeClaim(exchange(ASKAI, "askai").path("access_token").asText()),
                "askai must stay independent of that decision");
    }

    @Test
    void aScopeApprovedForOneResourceIsNotValidForAnother() throws Exception {
        start(Map.of(PYLOROS, List.of("pyloros"), ASKAI, List.of("askai")));

        assertEquals(400, authorize(PYLOROS, "askai").statusCode());
        assertEquals(400, authorize(ASKAI, "pyloros").statusCode());
    }

    @Test
    void anUnknownScopeIsRejected() throws Exception {
        start(Map.of(PYLOROS, List.of("mcp", "pyloros")));

        assertEquals(400, authorize(PYLOROS, "admin").statusCode());
        assertEquals(400, authorize(PYLOROS, "mcp admin").statusCode(),
                "a partially unapproved request must fail instead of being trimmed");
    }

    @Test
    void finerGrainedScopesAreIssuedWhenRequestedAndApproved() throws Exception {
        start(Map.of(PYLOROS, List.of("pyloros", "pyloros:intellij.read", "pyloros:git.write")));

        String scope = scopeClaim(exchange(PYLOROS, "pyloros pyloros:intellij.read")
                .path("access_token").asText());

        assertEquals("pyloros pyloros:intellij.read", scope);
        assertFalse(scope.contains("git.write"), "what was not requested stays out");
    }

    @Test
    void theTokenStaysBoundToItsOwnResource() throws Exception {
        start(Map.of(PYLOROS, List.of("pyloros")));

        JsonNode claims = claims(exchange(PYLOROS, "pyloros").path("access_token").asText());

        assertEquals("https://" + PYLOROS, claims.path("iss").asText());
        assertEquals("https://" + PYLOROS + "/", claims.path("aud").asText());
    }

    @Test
    void protectedResourceMetadataAdvertisesTheApprovedScopes() throws Exception {
        start(Map.of(PYLOROS, List.of("pyloros", "pyloros:git.write")));

        HttpResponse<String> response = http.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + runtime.port()
                                + "/.well-known/oauth-protected-resource"))
                        .header("Host", PYLOROS).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        JsonNode metadata = json.readTree(response.body());
        assertEquals("https://" + PYLOROS + "/", metadata.path("resource").asText());
        // This is how a client learns which scopes it may ask for.
        assertEquals(List.of("pyloros", "pyloros:git.write"),
                java.util.stream.StreamSupport.stream(
                                metadata.path("scopes_supported").spliterator(), false)
                        .map(JsonNode::asText).toList());
    }

    // ---- helpers ----------------------------------------------------------

    private HttpResponse<String> authorize(String host, String requestedScope) throws Exception {
        String query = "?response_type=code&client_id=" + CLIENT
                + "&redirect_uri=" + encode(REDIRECT)
                + "&scope=" + encode(requestedScope)
                + "&code_challenge=" + s256(VERIFIER) + "&code_challenge_method=S256"
                + "&resource=" + encode("https://" + host + "/");
        return http.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + runtime.port()
                                + "/oauth/authorize" + query))
                        .header("Host", host).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode exchange(String host, String requestedScope) throws Exception {
        HttpResponse<String> authorized = authorize(host, requestedScope);
        assertEquals(302, authorized.statusCode(), authorized.body());
        String location = authorized.headers().firstValue("Location").orElseThrow();
        String code = location.substring(location.indexOf("code=") + 5);
        if (code.contains("&")) {
            code = code.substring(0, code.indexOf('&'));
        }

        String form = "grant_type=authorization_code&code=" + code
                + "&redirect_uri=" + encode(REDIRECT)
                + "&code_verifier=" + VERIFIER
                + "&resource=" + encode("https://" + host + "/");
        HttpResponse<String> token = http.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + runtime.port() + "/oauth/token"))
                        .header("Host", host)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .header("Authorization", "Basic " + Base64.getEncoder()
                                .encodeToString((CLIENT + ":" + SECRET)
                                        .getBytes(StandardCharsets.UTF_8)))
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, token.statusCode(), token.body());
        return json.readTree(token.body());
    }

    private String scopeClaim(String jwt) throws Exception {
        return claims(jwt).path("scope").asText();
    }

    private JsonNode claims(String jwt) throws Exception {
        return json.readTree(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String s256(String verifier) throws Exception {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256")
                        .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
    }
}
