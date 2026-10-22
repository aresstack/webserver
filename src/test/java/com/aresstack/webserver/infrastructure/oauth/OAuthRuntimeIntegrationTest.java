package com.aresstack.webserver.infrastructure.oauth;

import com.aresstack.anauthentia.jwt.JwtAccessToken;
import com.aresstack.anauthentia.jwt.JwtAccessTokenValidator;
import com.aresstack.webserver.domain.OAuthClientConfiguration;
import com.aresstack.webserver.domain.OAuthConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.JWKSet;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fährt den kompletten OAuth-Flow über HTTP gegen die eingebettete Runtime:
 * Discovery → Authorize (PKCE S256) → Token → Offline-JWT-Validierung per
 * JWKS → Refresh → Revoke — und nach einem Neustart der Runtime bleiben
 * Schlüssel und Refresh-Tokens gültig.
 */
class OAuthRuntimeIntegrationTest {

    private static final String ISSUER = "https://auth.example.org";
    private static final String CLIENT_ID = "chatgpt";
    private static final String CLIENT_SECRET = "integration-test-secret";
    private static final String REDIRECT_URI =
            "https://chatgpt.com/connector_platform_oauth_redirect?x=1";
    private static final String VERIFIER =
            "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXkdBjftJeZ4CVP";

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).build();

    @TempDir Path root;
    private OAuthRuntime runtime;

    private OAuthConfiguration configuration() {
        return new OAuthConfiguration(true, ISSUER, List.of(new OAuthClientConfiguration(
                CLIENT_ID, CLIENT_SECRET,
                // Origin-Eintrag: erlaubt jeden Pfad auf chatgpt.com.
                List.of("https://chatgpt.com"))));
    }

    private String base() {
        return "http://127.0.0.1:" + runtime.port();
    }

    @AfterEach
    void stopRuntime() {
        if (runtime != null) {
            runtime.stop();
        }
    }

    @Test
    void fullFlowWorksAndSurvivesARestart() throws Exception {
        runtime = new OAuthRuntime(root, message -> { }, 0);
        runtime.apply(configuration());

        // Discovery.
        JsonNode metadata = getJson("/.well-known/oauth-authorization-server");
        assertEquals(ISSUER, metadata.get("issuer").asText());
        assertEquals(ISSUER + "/.well-known/jwks.json", metadata.get("jwks_uri").asText());
        assertEquals("S256", metadata.get("code_challenge_methods_supported").get(0).asText());
        JsonNode resource = getJson("/.well-known/oauth-protected-resource");
        assertEquals(ISSUER + "/", resource.get("resource").asText());

        // Authorize mit S256-PKCE → 302 mit Code.
        String challenge = s256(VERIFIER);
        HttpResponse<String> authorize = get("/oauth/authorize?response_type=code"
                + "&client_id=" + CLIENT_ID
                + "&redirect_uri=" + encode(REDIRECT_URI)
                + "&state=xyz&scope=mcp"
                + "&code_challenge=" + challenge + "&code_challenge_method=S256");
        assertEquals(302, authorize.statusCode());
        String location = authorize.headers().firstValue("Location").orElseThrow();
        assertTrue(location.startsWith("https://chatgpt.com/"), location);
        assertTrue(location.contains("state=xyz"));
        String code = extractQueryValue(location, "code");

        // Ohne PKCE-Challenge wird der Authorize-Request abgelehnt.
        HttpResponse<String> withoutPkce = get("/oauth/authorize?response_type=code"
                + "&client_id=" + CLIENT_ID + "&redirect_uri=" + encode(REDIRECT_URI));
        assertEquals(400, withoutPkce.statusCode());

        // Token-Exchange.
        JsonNode token = postForm("/oauth/token",
                "grant_type=authorization_code&code=" + encode(code)
                        + "&redirect_uri=" + encode(REDIRECT_URI)
                        + "&code_verifier=" + VERIFIER
                        + "&client_id=" + CLIENT_ID
                        + "&client_secret=" + CLIENT_SECRET, 200);
        String accessToken = token.get("access_token").asText();
        String refreshToken = token.get("refresh_token").asText();
        assertEquals("Bearer", token.get("token_type").asText());
        assertEquals("mcp", token.get("scope").asText());

        // Offline-Validierung nur mit dem öffentlichen JWKS-Endpoint.
        JWKSet jwks = JWKSet.parse(get("/.well-known/jwks.json").body());
        JwtAccessTokenValidator validator =
                new JwtAccessTokenValidator(ISSUER, ISSUER + "/", jwks);
        Optional<JwtAccessToken> validated = validator.validate(accessToken);
        assertTrue(validated.isPresent());
        assertEquals(CLIENT_ID, validated.get().getClientId());
        assertTrue(validated.get().hasScope("mcp"));

        // Falsche Client-Credentials am Token-Endpoint → 401.
        postForm("/oauth/token", "grant_type=refresh_token&refresh_token=" + encode(refreshToken)
                + "&client_id=" + CLIENT_ID + "&client_secret=wrong", 401);

        // Runtime neu starten (simulierter Prozess-Neustart).
        runtime.stop();
        runtime = new OAuthRuntime(root, message -> { }, 0);
        runtime.apply(configuration());

        // Persistierte Schlüssel: der alte Access-Token validiert weiter offline.
        JWKSet jwksAfterRestart = JWKSet.parse(get("/.well-known/jwks.json").body());
        assertTrue(new JwtAccessTokenValidator(ISSUER, ISSUER + "/", jwksAfterRestart)
                .validate(accessToken).isPresent(),
                "signing keys must survive a restart");

        // Persistierte Refresh-Tokens: Refresh nach Neustart (Basic-Auth) rotiert.
        String basic = Base64.getEncoder().encodeToString(
                (CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> refreshed = http.send(HttpRequest.newBuilder()
                        .uri(URI.create(base() + "/oauth/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .header("Authorization", "Basic " + basic)
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "grant_type=refresh_token&refresh_token=" + encode(refreshToken)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, refreshed.statusCode(), refreshed.body());
        JsonNode refreshedBody = json.readTree(refreshed.body());
        String rotatedRefreshToken = refreshedBody.get("refresh_token").asText();
        assertNotEquals(refreshToken, rotatedRefreshToken, "rotation must issue a new token");

        // Revoke → anschließender Refresh scheitert.
        HttpResponse<String> revoke = post("/oauth/revoke",
                "token=" + encode(rotatedRefreshToken)
                        + "&client_id=" + CLIENT_ID + "&client_secret=" + CLIENT_SECRET);
        assertEquals(200, revoke.statusCode());
        postForm("/oauth/token", "grant_type=refresh_token&refresh_token="
                + encode(rotatedRefreshToken)
                + "&client_id=" + CLIENT_ID + "&client_secret=" + CLIENT_SECRET, 400);

        // Fremde Redirect-Hosts bleiben verboten.
        HttpResponse<String> badRedirect = get("/oauth/authorize?response_type=code"
                + "&client_id=" + CLIENT_ID
                + "&redirect_uri=" + encode("https://evil.example.com/cb")
                + "&code_challenge=" + challenge + "&code_challenge_method=S256");
        assertEquals(400, badRedirect.statusCode());
        assertFalse(badRedirect.headers().firstValue("Location").isPresent());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder().uri(URI.create(base() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode getJson(String path) throws Exception {
        HttpResponse<String> response = get(path);
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body());
    }

    private HttpResponse<String> post(String path, String form) throws Exception {
        return http.send(HttpRequest.newBuilder()
                        .uri(URI.create(base() + path))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode postForm(String path, String form, int expectedStatus) throws Exception {
        HttpResponse<String> response = post(path, form);
        assertEquals(expectedStatus, response.statusCode(), response.body());
        return json.readTree(response.body());
    }

    private static String extractQueryValue(String uri, String name) {
        for (String pair : URI.create(uri).getRawQuery().split("&")) {
            if (pair.startsWith(name + "=")) {
                return java.net.URLDecoder.decode(
                        pair.substring(name.length() + 1), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("Missing query parameter " + name + " in " + uri);
    }

    private static String s256(String verifier) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
