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
 * The approvals must survive the whole OAuth flow and land in the JWT.
 *
 * <p>This is the question that decides whether the permission management is real or
 * decorative: the ChatGPT connector requests only the {@code baseScopes} from its
 * {@code mcp.json} — today just {@code mcp}. If the token carried only what was
 * requested, approving {@code mcp:read} in the GUI would change nothing.
 */
class ApprovedScopesReachTheTokenTest {

    private static final String HOST = "pyloros.example.org";
    private static final String CLIENT = "angel";
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

    private void start(List<String> approved) {
        if (runtime == null) {
            runtime = new OAuthRuntime(root, message -> { }, 0);
        }
        runtime.apply(new OAuthConfiguration(true, "https://auth.example.org",
                        List.of(new OAuthClientConfiguration(CLIENT, SECRET,
                                List.of("https://chatgpt.com")))),
                Map.of(HOST, approved));
    }

    @Test
    void aClientRequestingOnlyMcpStillReceivesTheApprovedScopes() throws Exception {
        start(List.of("mcp", "mcp:read"));

        JsonNode token = exchange("mcp");

        assertEquals("mcp mcp:read", token.path("scope").asText(),
                "the token response must report the granted scope");
        assertEquals("mcp mcp:read", scopeClaim(token.path("access_token").asText()),
                "the approval has to reach the JWT, otherwise the GUI is decorative");
    }

    @Test
    void revokingAnApprovalRemovesItFromTheNextToken() throws Exception {
        start(List.of("mcp", "mcp:read"));
        assertTrue(scopeClaim(exchange("mcp").path("access_token").asText()).contains("mcp:read"));

        // Same call the controller makes after a Deny override.
        start(List.of("mcp"));

        String scope = scopeClaim(exchange("mcp").path("access_token").asText());
        assertEquals("mcp", scope);
        assertFalse(scope.contains("mcp:read"), "a withdrawn approval must not survive");
    }

    @Test
    void theAudienceStillBindsTheTokenToItsOwnResource() throws Exception {
        start(List.of("mcp"));

        JsonNode claims = claims(exchange("mcp").path("access_token").asText());

        assertEquals("https://" + HOST, claims.path("iss").asText());
        assertEquals("https://" + HOST + "/", claims.path("aud").asText());
        // Still the client, not a user: there is no user management yet.
        assertEquals(CLIENT, claims.path("sub").asText());
    }

    private JsonNode exchange(String requestedScope) throws Exception {
        String resource = "https://" + HOST + "/";
        String query = "?response_type=code&client_id=" + CLIENT
                + "&redirect_uri=" + encode(REDIRECT)
                + "&scope=" + encode(requestedScope)
                + "&code_challenge=" + s256(VERIFIER) + "&code_challenge_method=S256"
                + "&resource=" + encode(resource);
        HttpResponse<String> authorize = http.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + runtime.port()
                                + "/oauth/authorize" + query))
                        .header("Host", HOST).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(302, authorize.statusCode(), authorize.body());
        String location = authorize.headers().firstValue("Location").orElseThrow();
        String code = location.substring(location.indexOf("code=") + 5);
        if (code.contains("&")) {
            code = code.substring(0, code.indexOf('&'));
        }

        String form = "grant_type=authorization_code&code=" + code
                + "&redirect_uri=" + encode(REDIRECT)
                + "&code_verifier=" + VERIFIER
                + "&resource=" + encode(resource);
        HttpResponse<String> token = http.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + runtime.port() + "/oauth/token"))
                        .header("Host", HOST)
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
        String payload = jwt.split("\\.")[1];
        return json.readTree(Base64.getUrlDecoder().decode(payload));
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
