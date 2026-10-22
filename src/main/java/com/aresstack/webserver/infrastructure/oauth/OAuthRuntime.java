package com.aresstack.webserver.infrastructure.oauth;

import com.aresstack.anauthentia.jwt.JwtAccessTokenIssuer;
import com.aresstack.anauthentia.jwt.JwtKeyRing;
import com.aresstack.anauthentia.oauth.AuthorizationCodeService;
import com.aresstack.anauthentia.oauth.FileRefreshTokenStore;
import com.aresstack.anauthentia.oauth.InMemoryAuthorizationCodeStore;
import com.aresstack.anauthentia.oauth.InMemoryOAuthClientRegistry;
import com.aresstack.anauthentia.oauth.OAuthClient;
import com.aresstack.anauthentia.oauth.OAuthException;
import com.aresstack.anauthentia.oauth.OAuthTokenService;
import com.aresstack.anauthentia.oauth.RedirectUriPolicy;
import com.aresstack.anauthentia.oauth.TokenResponse;
import com.aresstack.webserver.domain.OAuthClientConfiguration;
import com.aresstack.webserver.domain.OAuthConfiguration;
import com.aresstack.webserver.infrastructure.managed.WindowsDpapiSecrets;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Zentraler OAuth-Dienst (Authorization Server) als Loopback-HTTP-Dienst im
 * Webserver-Prozess. Caddy routet die öffentlichen Pfade der Sites hierher;
 * TLS terminiert bei Caddy. Die Protokolllogik kommt vollständig aus
 * AnAuthentia; dieser Adapter macht nur HTTP und Konfiguration.
 */
public final class OAuthRuntime {

    public static final int DEFAULT_PORT = 29172;

    private static final Duration CODE_LIFETIME = Duration.ofMinutes(5);
    private static final Duration ACCESS_TOKEN_LIFETIME = Duration.ofMinutes(10);
    private static final Duration REFRESH_TOKEN_LIFETIME = Duration.ofDays(30);

    private final Path root;
    private final Consumer<String> log;
    private final int requestedPort;
    private final ObjectMapper json = new ObjectMapper();

    private HttpServer server;
    private OAuthConfiguration activeConfiguration;
    private JwtKeyRing keyRing;

    public OAuthRuntime(Path root, Consumer<String> log) {
        this(root, log, DEFAULT_PORT);
    }

    public OAuthRuntime(Path root, Consumer<String> log, int port) {
        this.root = root;
        this.log = log;
        this.requestedPort = port;
    }

    /** Bringt den Dienst in den Zustand der Konfiguration (Start/Stop/Neustart). */
    public synchronized void apply(OAuthConfiguration configuration) {
        if (configuration.equals(activeConfiguration) && (server != null) == configuration.enabled()) {
            return;
        }
        stop();
        if (configuration.enabled()) {
            start(configuration);
        }
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
            activeConfiguration = null;
            log.accept("OAuth service stopped");
        }
    }

    public synchronized boolean isRunning() {
        return server != null;
    }

    /** Tatsächlich gebundener Loopback-Port (für Caddy-Routing und Tests). */
    public synchronized int port() {
        if (server == null) {
            throw new IllegalStateException("OAuth service is not running");
        }
        return server.getAddress().getPort();
    }

    /** Öffentliche Schlüssel des aktuellen Rings (JWKS-JSON). */
    public synchronized String publicJwksJson() {
        if (keyRing == null) {
            keyRing = new OAuthKeyRingStore(root).loadOrCreate();
        }
        return keyRing.publicJwksJson();
    }

    private void start(OAuthConfiguration configuration) {
        keyRing = new OAuthKeyRingStore(root).loadOrCreate();
        String issuer = configuration.issuer();
        String audience = issuer + "/";

        InMemoryOAuthClientRegistry clients = new InMemoryOAuthClientRegistry();
        Map<String, List<String>> redirectEntries = new HashMap<>();
        for (OAuthClientConfiguration client : configuration.clients()) {
            String secret = WindowsDpapiSecrets.isProtected(client.clientSecret())
                    ? WindowsDpapiSecrets.reveal(client.clientSecret())
                    : client.clientSecret();
            clients.register(OAuthClient.withHostManagedRedirects(client.clientId(), secret));
            redirectEntries.put(client.clientId(), client.redirectUris());
        }
        RedirectUriPolicy redirectPolicy = (client, redirectUri) ->
                allowsRedirect(redirectEntries.get(client.getClientId()), redirectUri);

        Clock clock = Clock.systemUTC();
        SecureRandom random = new SecureRandom();
        AuthorizationCodeService codes = new AuthorizationCodeService(
                new InMemoryAuthorizationCodeStore(), clients, random, clock,
                CODE_LIFETIME, false, redirectPolicy);
        OAuthTokenService tokens = new OAuthTokenService(
                codes, clients,
                new FileRefreshTokenStore(root.resolve("data").resolve("oauth")
                        .resolve("refresh-tokens.store")),
                random, clock, ACCESS_TOKEN_LIFETIME, REFRESH_TOKEN_LIFETIME, true,
                new JwtAccessTokenIssuer(issuer, audience, keyRing));

        try {
            HttpServer httpServer = HttpServer.create(
                    new InetSocketAddress("127.0.0.1", requestedPort), 0);
            httpServer.createContext("/", exchange ->
                    route(exchange, issuer, audience, codes, tokens));
            httpServer.start();
            server = httpServer;
            activeConfiguration = configuration;
            log.accept("OAuth service listening on 127.0.0.1:" + server.getAddress().getPort()
                    + " for issuer " + issuer);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot start the OAuth service", e);
        }
    }

    /** Exakter Treffer, oder der Eintrag ist ein reiner Origin und der Host stimmt. */
    static boolean allowsRedirect(List<String> entries, String redirectUri) {
        if (entries == null || redirectUri == null || !redirectUri.startsWith("https://")) {
            return false;
        }
        URI parsed;
        try {
            parsed = URI.create(redirectUri);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
        if (parsed.getHost() == null) {
            return false;
        }
        for (String entry : entries) {
            if (entry.equals(redirectUri)) {
                return true;
            }
            URI entryUri;
            try {
                entryUri = URI.create(entry);
            } catch (IllegalArgumentException invalid) {
                continue;
            }
            boolean originOnly = entryUri.getPath() == null || entryUri.getPath().isEmpty();
            if (originOnly && parsed.getHost().equalsIgnoreCase(entryUri.getHost())
                    && parsed.getPort() == entryUri.getPort()) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // HTTP-Routing
    // ------------------------------------------------------------------

    private void route(HttpExchange exchange, String issuer, String audience,
                       AuthorizationCodeService codes, OAuthTokenService tokens) throws IOException {
        String path = exchange.getRequestURI().getPath();
        try {
            if (path.startsWith("/.well-known/oauth-authorization-server")
                    || path.startsWith("/.well-known/openid-configuration")) {
                sendJson(exchange, 200, authorizationServerMetadata(issuer));
            } else if (path.startsWith("/.well-known/oauth-protected-resource")) {
                sendJson(exchange, 200, protectedResourceMetadata(issuer, audience));
            } else if (path.equals("/.well-known/jwks.json")) {
                send(exchange, 200, "application/json", keyRing.publicJwksJson());
            } else if (path.equals("/oauth/authorize") && isMethod(exchange, "GET")) {
                authorize(exchange, codes);
            } else if (path.equals("/oauth/token") && isMethod(exchange, "POST")) {
                token(exchange, tokens);
            } else if (path.equals("/oauth/revoke") && isMethod(exchange, "POST")) {
                revoke(exchange, tokens);
            } else if (path.equals("/health")) {
                send(exchange, 200, "application/json", "{\"status\":\"ok\"}");
            } else {
                sendError(exchange, 404, "invalid_request", "Unknown endpoint");
            }
        } catch (OAuthException oauthError) {
            sendError(exchange, oauthError.getStatusCode(), oauthError.getError(),
                    oauthError.getErrorDescription());
        } catch (RuntimeException unexpected) {
            // Interna (Pfade, Zustände) nie an den Client durchreichen.
            log.accept("OAuth request failed: " + unexpected.getClass().getSimpleName());
            sendError(exchange, 500, "server_error", null);
        } finally {
            exchange.close();
        }
    }

    private void authorize(HttpExchange exchange, AuthorizationCodeService codes) throws IOException {
        Map<String, String> query = parseForm(exchange.getRequestURI().getRawQuery());
        String location = codes.authorize(
                query.get("response_type"),
                query.get("client_id"),
                query.get("redirect_uri"),
                query.get("state"),
                query.get("scope"),
                query.get("code_challenge"),
                query.get("code_challenge_method"));
        exchange.getResponseHeaders().set("Location", location);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(302, -1);
    }

    private void token(HttpExchange exchange, OAuthTokenService tokens) throws IOException {
        Map<String, String> form = parseForm(readBody(exchange));
        String[] credentials = clientCredentials(exchange, form);
        TokenResponse response = tokens.exchangeToken(
                credentials[0], credentials[1],
                form.get("grant_type"), form.get("code"), form.get("refresh_token"),
                form.get("redirect_uri"), form.get("code_verifier"));
        ObjectNode body = json.createObjectNode()
                .put("access_token", response.getAccessToken())
                .put("token_type", response.getTokenType())
                .put("expires_in", response.getExpiresIn())
                .put("refresh_token", response.getRefreshToken())
                .put("scope", response.getScope());
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("Pragma", "no-cache");
        sendJson(exchange, 200, body);
    }

    private void revoke(HttpExchange exchange, OAuthTokenService tokens) throws IOException {
        Map<String, String> form = parseForm(readBody(exchange));
        String[] credentials = clientCredentials(exchange, form);
        tokens.revokeRefreshToken(credentials[0], credentials[1], form.get("token"));
        exchange.sendResponseHeaders(200, -1);
    }

    private ObjectNode authorizationServerMetadata(String issuer) {
        ObjectNode metadata = json.createObjectNode()
                .put("issuer", issuer)
                .put("authorization_endpoint", issuer + "/oauth/authorize")
                .put("token_endpoint", issuer + "/oauth/token")
                .put("revocation_endpoint", issuer + "/oauth/revoke")
                .put("jwks_uri", issuer + "/.well-known/jwks.json");
        metadata.putArray("response_types_supported").add("code");
        metadata.putArray("grant_types_supported").add("authorization_code").add("refresh_token");
        metadata.putArray("token_endpoint_auth_methods_supported")
                .add("client_secret_basic").add("client_secret_post");
        metadata.putArray("code_challenge_methods_supported").add("S256");
        metadata.putArray("scopes_supported").add("mcp");
        return metadata;
    }

    private ObjectNode protectedResourceMetadata(String issuer, String audience) {
        ObjectNode metadata = json.createObjectNode()
                .put("resource", audience);
        metadata.putArray("authorization_servers").add(issuer);
        metadata.putArray("scopes_supported").add("mcp");
        metadata.putArray("bearer_methods_supported").add("header");
        return metadata;
    }

    private String[] clientCredentials(HttpExchange exchange, Map<String, String> form) {
        String clientId = form.get("client_id");
        String clientSecret = form.get("client_secret");
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (authorization != null && authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
            try {
                String decoded = new String(Base64.getDecoder()
                        .decode(authorization.substring(6).trim()), StandardCharsets.UTF_8);
                int separator = decoded.indexOf(':');
                if (separator >= 0) {
                    clientId = urlDecode(decoded.substring(0, separator));
                    clientSecret = urlDecode(decoded.substring(separator + 1));
                }
            } catch (IllegalArgumentException ignored) {
                // Fehlerhafte Basic-Header fallen auf die Form-Credentials zurück.
            }
        }
        return new String[]{clientId, clientSecret};
    }

    private static boolean isMethod(HttpExchange exchange, String method) {
        return method.equalsIgnoreCase(exchange.getRequestMethod());
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static Map<String, String> parseForm(String encoded) {
        Map<String, String> values = new HashMap<>();
        if (encoded == null || encoded.isEmpty()) {
            return values;
        }
        for (String pair : encoded.split("&")) {
            int separator = pair.indexOf('=');
            if (separator > 0) {
                values.put(urlDecode(pair.substring(0, separator)),
                        urlDecode(pair.substring(separator + 1)));
            }
        }
        return values;
    }

    private static String urlDecode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private void sendJson(HttpExchange exchange, int status, ObjectNode body) throws IOException {
        send(exchange, status, "application/json", body.toString());
    }

    private void sendError(HttpExchange exchange, int status, String error, String description)
            throws IOException {
        ObjectNode body = json.createObjectNode().put("error", error);
        if (description != null && !description.isBlank()) {
            body.put("error_description", description);
        }
        sendJson(exchange, status, body);
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
