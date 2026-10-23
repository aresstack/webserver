package com.aresstack.webserver.infrastructure.oauth;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-Repo-Smoke-Test: der zentrale OAuth-Dienst bedient zwei geschützte
 * Dienste (AskAI, Pyloros) über ihre öffentlichen Hostnamen — genau so, wie
 * Caddy die Anfragen mit Original-Host an die Loopback-Runtime weiterleitet.
 * Die Validatoren sind exakt so konfiguriert wie der Central-Modus in AskAI
 * und Pyloros (Issuer = eigene Origin, Audience = Origin + "/").
 *
 * Matrix: Discovery pro Host, PKCE-Authorize, Token-Exchange, gültige und
 * Kreuz-Tokens (Audience-Isolation), Code-/Refresh-Bindung an die Ressource,
 * Schlüsselrotation über einen Neustart.
 */
class OAuthCrossServiceSmokeTest {

    private static final String ASKAI_HOST = "askai.example.org";
    private static final String PYLOROS_HOST = "pyloros.example.org";
    private static final String FALLBACK_ISSUER = "https://auth.example.org";
    private static final String CLIENT_ID = "chatgpt";
    private static final String CLIENT_SECRET = "smoke-test-secret";
    private static final String REDIRECT_URI = "https://chatgpt.com/connector_oauth_redirect";
    private static final String VERIFIER =
            "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXkdBjftJeZ4CVP";

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).build();

    @TempDir Path root;
    private OAuthRuntime runtime;

    @AfterEach
    void stopRuntime() {
        if (runtime != null) {
            runtime.stop();
        }
    }

    private void startRuntime() {
        runtime = new OAuthRuntime(root, message -> { }, 0);
        runtime.apply(new OAuthConfiguration(true, FALLBACK_ISSUER,
                        List.of(new OAuthClientConfiguration(CLIENT_ID, CLIENT_SECRET,
                                List.of("https://chatgpt.com")))),
                java.util.Map.of(ASKAI_HOST, java.util.List.of("mcp"),
                        PYLOROS_HOST, java.util.List.of("mcp")));
    }

    @Test
    void discoveryAuthorizationIsolationAndRotationAcrossTwoServices() throws Exception {
        startRuntime();

        // 1) Discovery pro öffentlichem Hostnamen: Ressource und Issuer sind
        //    die jeweilige Site, nicht der zentrale Fallback.
        for (String host : List.of(ASKAI_HOST, PYLOROS_HOST)) {
            JsonNode resourceMetadata = getJson(host, "/.well-known/oauth-protected-resource");
            assertEquals("https://" + host + "/", resourceMetadata.get("resource").asText());
            assertEquals("https://" + host,
                    resourceMetadata.get("authorization_servers").get(0).asText());

            JsonNode serverMetadata = getJson(host, "/.well-known/oauth-authorization-server");
            assertEquals("https://" + host, serverMetadata.get("issuer").asText());
            assertEquals("https://" + host + "/oauth/token",
                    serverMetadata.get("token_endpoint").asText());
        }

        // 2) Kompletter Flow pro Dienst.
        TokenPair askai = fullFlow(ASKAI_HOST);
        TokenPair pyloros = fullFlow(PYLOROS_HOST);

        // 3) Offline-Validierung wie im Central-Modus von AskAI/Pyloros.
        JWKSet jwks = JWKSet.parse(get(ASKAI_HOST, "/.well-known/jwks.json").body());
        JwtAccessTokenValidator askaiValidator = validatorFor(ASKAI_HOST, jwks);
        JwtAccessTokenValidator pylorosValidator = validatorFor(PYLOROS_HOST, jwks);

        assertTrue(askaiValidator.validate(askai.accessToken).isPresent());
        assertTrue(pylorosValidator.validate(pyloros.accessToken).isPresent());

        // 4) Audience-Isolation: Kreuz-Validierung scheitert in beide Richtungen.
        assertFalse(pylorosValidator.validate(askai.accessToken).isPresent(),
                "an AskAI token must be rejected by Pyloros");
        assertFalse(askaiValidator.validate(pyloros.accessToken).isPresent(),
                "a Pyloros token must be rejected by AskAI");

        // 5) Grant-Bindung: ein AskAI-Refresh-Token refresht nicht am Pyloros-Host.
        HttpResponse<String> crossRefresh = postToken(PYLOROS_HOST,
                "grant_type=refresh_token&refresh_token=" + encode(askai.refreshToken)
                        + "&client_id=" + CLIENT_ID + "&client_secret=" + CLIENT_SECRET);
        assertEquals(400, crossRefresh.statusCode());
        assertTrue(crossRefresh.body().contains("invalid_grant"));

        // 6) Ein AskAI-Code ist am Pyloros-Host wertlos.
        String codeForAskai = authorizeAndExtractCode(ASKAI_HOST);
        HttpResponse<String> crossExchange = postToken(PYLOROS_HOST,
                "grant_type=authorization_code&code=" + encode(codeForAskai)
                        + "&redirect_uri=" + encode(REDIRECT_URI)
                        + "&code_verifier=" + VERIFIER
                        + "&client_id=" + CLIENT_ID + "&client_secret=" + CLIENT_SECRET);
        assertEquals(400, crossExchange.statusCode());

        // 7) Schlüsselrotation + Neustart: alte Tokens bleiben über das
        //    aktualisierte JWKS gültig, neue Tokens signieren mit neuem Key.
        OAuthKeyRingStore keyStore = new OAuthKeyRingStore(root);
        keyStore.rotate(keyStore.loadOrCreate());
        runtime.stop();
        startRuntime();

        JWKSet rotatedJwks = JWKSet.parse(get(ASKAI_HOST, "/.well-known/jwks.json").body());
        assertEquals(2, rotatedJwks.getKeys().size(), "old key must stay published");
        assertTrue(validatorFor(ASKAI_HOST, rotatedJwks).validate(askai.accessToken).isPresent(),
                "tokens issued before the rotation must stay valid");

        HttpResponse<String> refreshedAfterRotation = postToken(ASKAI_HOST,
                "grant_type=refresh_token&refresh_token=" + encode(askai.refreshToken)
                        + "&client_id=" + CLIENT_ID + "&client_secret=" + CLIENT_SECRET);
        assertEquals(200, refreshedAfterRotation.statusCode(), refreshedAfterRotation.body());
        String newToken = json.readTree(refreshedAfterRotation.body())
                .get("access_token").asText();
        assertNotEquals(askai.accessToken, newToken);
        assertTrue(validatorFor(ASKAI_HOST, rotatedJwks).validate(newToken).isPresent());
        assertFalse(validatorFor(PYLOROS_HOST, rotatedJwks).validate(newToken).isPresent(),
                "the resource binding must survive refresh after rotation");
    }

    @Test
    void unknownHostHeaderMintsNoResourceIdentity() throws Exception {
        startRuntime();
        // Ein Host außerhalb der Allowlist (geschützte Sites + Issuer) darf
        // weder Metadaten noch Codes oder Tokens erhalten.
        HttpResponse<String> metadata = get("evil.example.net",
                "/.well-known/oauth-protected-resource");
        assertEquals(404, metadata.statusCode(), metadata.body());

        HttpResponse<String> authorize = get("evil.example.net",
                "/oauth/authorize?response_type=code&client_id=" + CLIENT_ID
                        + "&redirect_uri=" + encode(REDIRECT_URI)
                        + "&code_challenge=" + s256(VERIFIER) + "&code_challenge_method=S256");
        assertEquals(404, authorize.statusCode());
        assertFalse(authorize.headers().firstValue("Location").isPresent());

        // Der Issuer-Host selbst bleibt zulässig (zentrale Discovery).
        assertEquals(200, get("auth.example.org",
                "/.well-known/oauth-authorization-server").statusCode());
    }

    @Test
    void resourceParameterMustMatchTheHostBoundResource() throws Exception {
        startRuntime();
        // RFC 8707: passender resource-Indikator (mit/ohne Slash) wird akzeptiert.
        HttpResponse<String> matching = get(ASKAI_HOST, "/oauth/authorize?response_type=code"
                + "&client_id=" + CLIENT_ID + "&redirect_uri=" + encode(REDIRECT_URI)
                + "&code_challenge=" + s256(VERIFIER) + "&code_challenge_method=S256"
                + "&resource=" + encode("https://" + ASKAI_HOST));
        assertEquals(302, matching.statusCode(), matching.body());

        // Schema/Host sind case-insensitiv (RFC 3986) — Großschreibung dort passt.
        HttpResponse<String> upperHost = get(ASKAI_HOST, "/oauth/authorize?response_type=code"
                + "&client_id=" + CLIENT_ID + "&redirect_uri=" + encode(REDIRECT_URI)
                + "&code_challenge=" + s256(VERIFIER) + "&code_challenge_method=S256"
                + "&resource=" + encode("https://" + ASKAI_HOST.toUpperCase(java.util.Locale.ROOT) + "/"));
        assertEquals(302, upperHost.statusCode(), upperHost.body());

        // Pfade sind case-SENSITIV und hier grundsätzlich unzulässig: ein
        // Indikator mit Pfad bezeichnet NIE unsere pfadlose Origin-Ressource —
        // unabhängig von seiner Schreibung.
        for (String pathVariant : List.of("/AskAI", "/askai", "/?x=1", "/#f")) {
            HttpResponse<String> withPath = get(ASKAI_HOST, "/oauth/authorize?response_type=code"
                    + "&client_id=" + CLIENT_ID + "&redirect_uri=" + encode(REDIRECT_URI)
                    + "&code_challenge=" + s256(VERIFIER) + "&code_challenge_method=S256"
                    + "&resource=" + encode("https://" + ASKAI_HOST + pathVariant));
            assertEquals(400, withPath.statusCode(),
                    "resource with path/query/fragment must be rejected: " + pathVariant);
            assertTrue(withPath.body().contains("invalid_target"));
        }

        // Widersprüchlicher Indikator wird abgelehnt statt still ignoriert.
        HttpResponse<String> conflicting = get(ASKAI_HOST, "/oauth/authorize?response_type=code"
                + "&client_id=" + CLIENT_ID + "&redirect_uri=" + encode(REDIRECT_URI)
                + "&code_challenge=" + s256(VERIFIER) + "&code_challenge_method=S256"
                + "&resource=" + encode("https://" + PYLOROS_HOST + "/"));
        assertEquals(400, conflicting.statusCode());
        assertTrue(conflicting.body().contains("invalid_target"), conflicting.body());

        // Auch am Token-Endpoint (Code-Exchange und Refresh).
        String code = authorizeAndExtractCode(ASKAI_HOST);
        HttpResponse<String> conflictingExchange = postToken(ASKAI_HOST,
                "grant_type=authorization_code&code=" + encode(code)
                        + "&redirect_uri=" + encode(REDIRECT_URI)
                        + "&code_verifier=" + VERIFIER
                        + "&client_id=" + CLIENT_ID + "&client_secret=" + CLIENT_SECRET
                        + "&resource=" + encode("https://" + PYLOROS_HOST + "/"));
        assertEquals(400, conflictingExchange.statusCode());
        assertTrue(conflictingExchange.body().contains("invalid_target"));

        HttpResponse<String> matchingExchange = postToken(ASKAI_HOST,
                "grant_type=authorization_code&code=" + encode(code)
                        + "&redirect_uri=" + encode(REDIRECT_URI)
                        + "&code_verifier=" + VERIFIER
                        + "&client_id=" + CLIENT_ID + "&client_secret=" + CLIENT_SECRET
                        + "&resource=" + encode("https://" + ASKAI_HOST + "/"));
        assertEquals(200, matchingExchange.statusCode(), matchingExchange.body());

        String refreshToken = json.readTree(matchingExchange.body())
                .get("refresh_token").asText();
        HttpResponse<String> conflictingRefresh = postToken(ASKAI_HOST,
                "grant_type=refresh_token&refresh_token=" + encode(refreshToken)
                        + "&client_id=" + CLIENT_ID + "&client_secret=" + CLIENT_SECRET
                        + "&resource=" + encode("https://" + PYLOROS_HOST + "/"));
        assertEquals(400, conflictingRefresh.statusCode());
        assertTrue(conflictingRefresh.body().contains("invalid_target"));
    }

    @Test
    void authorizeWithoutPkceOrWithForeignRedirectIsRejectedPerHost() throws Exception {
        startRuntime();
        HttpResponse<String> withoutPkce = get(ASKAI_HOST, "/oauth/authorize?response_type=code"
                + "&client_id=" + CLIENT_ID + "&redirect_uri=" + encode(REDIRECT_URI));
        assertEquals(400, withoutPkce.statusCode());

        HttpResponse<String> foreignRedirect = get(PYLOROS_HOST,
                "/oauth/authorize?response_type=code&client_id=" + CLIENT_ID
                        + "&redirect_uri=" + encode("https://evil.example.com/cb")
                        + "&code_challenge=" + s256(VERIFIER) + "&code_challenge_method=S256");
        assertEquals(400, foreignRedirect.statusCode());
    }

    // ------------------------------------------------------------------

    private static final class TokenPair {
        final String accessToken;
        final String refreshToken;

        TokenPair(String accessToken, String refreshToken) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
        }
    }

    private TokenPair fullFlow(String host) throws Exception {
        String code = authorizeAndExtractCode(host);
        HttpResponse<String> token = postToken(host,
                "grant_type=authorization_code&code=" + encode(code)
                        + "&redirect_uri=" + encode(REDIRECT_URI)
                        + "&code_verifier=" + VERIFIER
                        + "&client_id=" + CLIENT_ID + "&client_secret=" + CLIENT_SECRET);
        assertEquals(200, token.statusCode(), token.body());
        JsonNode body = json.readTree(token.body());
        return new TokenPair(body.get("access_token").asText(),
                body.get("refresh_token").asText());
    }

    private String authorizeAndExtractCode(String host) throws Exception {
        HttpResponse<String> authorize = get(host, "/oauth/authorize?response_type=code"
                + "&client_id=" + CLIENT_ID
                + "&redirect_uri=" + encode(REDIRECT_URI)
                + "&state=s&scope=mcp"
                + "&code_challenge=" + s256(VERIFIER) + "&code_challenge_method=S256");
        assertEquals(302, authorize.statusCode(), authorize.body());
        String location = authorize.headers().firstValue("Location").orElseThrow();
        for (String pair : URI.create(location).getRawQuery().split("&")) {
            if (pair.startsWith("code=")) {
                return java.net.URLDecoder.decode(pair.substring(5), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("No code in " + location);
    }

    private static JwtAccessTokenValidator validatorFor(String host, JWKSet jwks) {
        return new JwtAccessTokenValidator("https://" + host, "https://" + host + "/", jwks);
    }

    private HttpResponse<String> get(String host, String path) throws Exception {
        return http.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + runtime.port() + path))
                        .header("Host", host).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode getJson(String host, String path) throws Exception {
        HttpResponse<String> response = get(host, path);
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body());
    }

    private HttpResponse<String> postToken(String host, String form) throws Exception {
        return http.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + runtime.port() + "/oauth/token"))
                        .header("Host", host)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString());
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
