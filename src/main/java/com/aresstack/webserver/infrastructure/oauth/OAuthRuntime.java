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

    /** Fallback scope when a protected site configures none. */
    static final String DEFAULT_SCOPE = "mcp";

    private static final Duration CODE_LIFETIME = Duration.ofMinutes(5);
    private static final Duration ACCESS_TOKEN_LIFETIME = Duration.ofMinutes(10);
    private static final Duration REFRESH_TOKEN_LIFETIME = Duration.ofDays(30);

    private final Path root;
    private final Consumer<String> log;
    private final int requestedPort;
    /** Optional sink for self-reported scopes; absent means registration is refused. */
    private ScopeRegistrationSink registrationSink;
    private final ObjectMapper json = new ObjectMapper();

    private HttpServer server;
    private OAuthConfiguration activeConfiguration;
    private java.util.Set<String> activeResourceHosts = java.util.Set.of();
    private java.util.Map<String, java.util.List<String>> activeResourceScopes = java.util.Map.of();
    private JwtKeyRing keyRing;

    public OAuthRuntime(Path root, Consumer<String> log) {
        this(root, log, DEFAULT_PORT);
    }

    public OAuthRuntime(Path root, Consumer<String> log, int port) {
        this.root = root;
        this.log = log;
        this.requestedPort = port;
    }

    /**
     * Receives a service's self-reported scope catalogue.
     *
     * <p>Deliberately an interface rather than a direct dependency on the
     * configuration: the OAuth service must not own persistence, and registration
     * stays optional - without a sink the endpoint simply refuses.
     */
    public interface ScopeRegistrationSink {
        /** @return true when the catalogue changed */
        boolean register(String host, java.util.List<com.aresstack.webserver.domain.ScopeEntry> scopes);
    }

    public synchronized void registrationSink(ScopeRegistrationSink sink) {
        this.registrationSink = sink;
    }

    /**
     * Per-service secret for {@code POST /internal/scopes}, created on first use.
     *
     * <p>Bound to the host so a service can only describe its own resource: the
     * endpoint compares the presented token against the token of the host named in
     * the request. Stored next to the key ring, readable only by the Windows user the
     * webserver runs as -- the same user its managed applications run as.
     */
    public synchronized String registrationToken(String host) {
        String normalized = host == null ? "" : host.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.isEmpty() || normalized.contains("/") || normalized.contains("\\\\")
                || normalized.contains("..")) {
            throw new IllegalArgumentException("Invalid host for a registration token: " + host);
        }
        Path tokenFile = root.resolve("data").resolve("oauth").resolve("registration")
                .resolve(normalized + ".token");
        try {
            if (java.nio.file.Files.isRegularFile(tokenFile)) {
                String existing = java.nio.file.Files.readString(tokenFile).trim();
                if (!existing.isEmpty()) {
                    return existing;
                }
            }
            java.nio.file.Files.createDirectories(tokenFile.getParent());
            byte[] raw = new byte[32];
            new SecureRandom().nextBytes(raw);
            String token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
            java.nio.file.Files.writeString(tokenFile, token);
            return token;
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Cannot access the scope registration token", failure);
        }
    }

    /** Loopback URL a managed application posts its catalogue to. */
    public String registrationUrl() {
        return "http://127.0.0.1:" + requestedPort + "/internal/scopes";
    }

    /** Die öffentlichen Hosts aller per Central OAuth geschützten Sites. */
    public static java.util.Set<String> resourceHosts(
            com.aresstack.webserver.domain.WebServerConfiguration configuration) {
        return resourceScopes(configuration).keySet();
    }

    /**
     * Host -> erlaubte Scopes je per Central OAuth geschuetzter Site.
     *
     * <p>Das ist die serverseitige Berechtigungsgrenze: ein Token fuer eine
     * Ressource kann nie mehr Scopes tragen als die Site konfiguriert hat,
     * unabhaengig davon, was der Client anfordert. Solange es keine Benutzer-
     * und Rollenverwaltung gibt, ist die Site-Konfiguration die einzige
     * Autoritaet darueber, welche Rechte vergeben werden duerfen.
     *
     * <p>Eine Site ohne konfigurierte Scopes faellt auf {@link #DEFAULT_SCOPE}
     * zurueck, damit eine leere Liste nicht versehentlich alles erlaubt.
     */
    public static java.util.Map<String, java.util.List<String>> resourceScopes(
            com.aresstack.webserver.domain.WebServerConfiguration configuration) {
        java.util.Map<String, java.util.List<String>> scopes = new java.util.LinkedHashMap<>();
        for (com.aresstack.webserver.domain.Site site : configuration.sites()) {
            if (site.authentication().isEmpty()) {
                continue;
            }
            java.util.List<String> approved = site.authentication().get().effectiveScopes();
            // Deny all ohne Allow-Override ergibt bewusst eine leere Liste: dann darf
            // kein Token mehr Rechte tragen. Das ist kein Konfigurationsfehler.
            scopes.put(site.host().value().toLowerCase(java.util.Locale.ROOT),
                    java.util.List.copyOf(approved));
        }
        return scopes;
    }

    /** Bringt den Dienst in den Zustand der Konfiguration (Start/Stop/Neustart). */
    public synchronized void apply(OAuthConfiguration configuration) {
        apply(configuration, java.util.Map.of());
    }

    /**
     * Start/Stop/Neustart mit expliziter Allowlist der öffentlichen Hosts, für
     * die dieser Dienst Ressourcen-Identitäten (Issuer/Audience) prägen darf —
     * die Hosts der per Central OAuth geschützten Sites. Ein Host-Header
     * außerhalb dieser Menge (und ungleich dem Issuer-Host) wird abgelehnt.
     */
    public synchronized void apply(OAuthConfiguration configuration,
                                   java.util.Map<String, java.util.List<String>> resourceScopes) {
        java.util.Map<String, java.util.List<String>> normalized = new java.util.LinkedHashMap<>();
        resourceScopes.forEach((host, scopes) -> normalized.put(
                host.toLowerCase(java.util.Locale.ROOT),
                scopes == null ? java.util.List.of(DEFAULT_SCOPE) : java.util.List.copyOf(scopes)));
        if (configuration.equals(activeConfiguration)
                && normalized.equals(activeResourceScopes)
                && (server != null) == configuration.enabled()) {
            return;
        }
        stop();
        activeResourceScopes = java.util.Map.copyOf(normalized);
        activeResourceHosts = java.util.Set.copyOf(normalized.keySet());
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

    /**
     * Öffentliche Origin der Anfrage: {@code https://<Host>} aus dem von Caddy
     * durchgereichten Host-Header — aber NUR für Hosts aus der konfigurierten
     * Allowlist (geschützte Sites) oder den Issuer-Host selbst. Loopback-/
     * IP-/leere Hosts (Direktzugriff, Tests) fallen auf den konfigurierten
     * Issuer zurück; jeder andere Host liefert {@code null} und wird vom
     * Aufrufer abgewiesen. Ein manipulierter Host-Header kann damit keine
     * Ressourcen-Identität außerhalb der Konfiguration prägen.
     */
    private String requestOrigin(HttpExchange exchange, String fallbackIssuer) {
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (host == null) {
            return fallbackIssuer;
        }
        host = host.trim().toLowerCase(java.util.Locale.ROOT);
        int portSeparator = host.indexOf(':');
        if (portSeparator >= 0) {
            host = host.substring(0, portSeparator);
        }
        if (host.isEmpty() || host.equals("localhost") || host.equals("127.0.0.1")
                || host.matches("[0-9.]+")) {
            return fallbackIssuer;
        }
        if (("https://" + host).equals(fallbackIssuer)) {
            return fallbackIssuer;
        }
        if (activeResourceHosts.contains(host)
                && host.matches("[a-z0-9]([a-z0-9.-]*[a-z0-9])?") && host.contains(".")) {
            return "https://" + host;
        }
        return null;
    }

    /**
     * RFC 8707: sendet der Client einen {@code resource}-Indikator, muss er
     * die über den vertrauenswürdigen Host bestimmte Ressource bezeichnen —
     * Widerspruch wird abgelehnt statt still ignoriert (Fehlercode
     * {@code invalid_target}); fehlt er, gilt die Host-Bindung allein.
     *
     * <p>Vergleich nach RFC 3986: Schema und Host case-insensitiv, der Pfad
     * case-SENSITIV. Die gebundene Ressource ist immer die pfadlose Origin
     * ({@code https://<host>/}); akzeptiert wird der Indikator daher nur mit
     * leerem oder "/"-Pfad, ohne Query/Fragment, auf exakt diesem Host.
     */
    private static void requireMatchingResource(String requestedResource, String boundResource) {
        if (requestedResource == null || requestedResource.isBlank()) {
            return;
        }
        URI requested;
        try {
            requested = URI.create(requestedResource.trim());
        } catch (IllegalArgumentException unparseable) {
            throw invalidTarget();
        }
        URI bound = URI.create(boundResource);
        boolean sameOrigin = "https".equalsIgnoreCase(requested.getScheme())
                && requested.getHost() != null
                && requested.getHost().equalsIgnoreCase(bound.getHost())
                && requested.getPort() == bound.getPort();
        String path = requested.getRawPath();
        boolean originOnly = (path == null || path.isEmpty() || path.equals("/"))
                && requested.getRawQuery() == null && requested.getRawFragment() == null;
        if (!sameOrigin || !originOnly) {
            throw invalidTarget();
        }
    }

    private static OAuthException invalidTarget() {
        return new OAuthException(400, "invalid_target",
                "The requested resource does not match this authorization endpoint");
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
        // Per-Site-Issuer: Caddy reicht den öffentlichen Host der Site durch.
        // Discovery, Code und Tokens werden an genau diese Ressource gebunden;
        // Direktzugriffe ohne verwertbaren Host fallen auf den konfigurierten
        // Issuer zurück.
        if (path.equals("/internal/scopes")) {
            try {
                registerScopes(exchange);
            } catch (OAuthException refused) {
                sendError(exchange, refused.getStatusCode(), refused.getError(),
                        refused.getErrorDescription());
            } catch (RuntimeException unexpected) {
                log.accept("Scope registration failed: " + unexpected.getClass().getSimpleName());
                sendError(exchange, 500, "server_error", null);
            } finally {
                exchange.close();
            }
            return;
        }
        String origin = requestOrigin(exchange, issuer);
        try {
            if (origin == null) {
                sendError(exchange, 404, "invalid_request",
                        "Unknown resource host for this authorization server");
                return;
            }
            String resource = origin + "/";
            java.util.List<String> allowedScopes = allowedScopesFor(origin);
            if (path.startsWith("/.well-known/oauth-authorization-server")
                    || path.startsWith("/.well-known/openid-configuration")) {
                sendJson(exchange, 200, authorizationServerMetadata(origin, allowedScopes));
            } else if (path.startsWith("/.well-known/oauth-protected-resource")) {
                sendJson(exchange, 200, protectedResourceMetadata(origin, resource, allowedScopes));
            } else if (path.equals("/.well-known/jwks.json")) {
                send(exchange, 200, "application/json", keyRing.publicJwksJson());
            } else if (path.equals("/oauth/authorize") && isMethod(exchange, "GET")) {
                authorize(exchange, codes, resource, allowedScopes);
            } else if (path.equals("/oauth/token") && isMethod(exchange, "POST")) {
                token(exchange, tokens, resource);
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

    /**
     * {@code POST /internal/scopes} - optionale Selbstmeldung eines Dienstes.
     *
     * <p>Nur ueber Loopback erreichbar und durch ein Bearer-Token geschuetzt; Caddy
     * leitet ausschliesslich {@code /oauth/*} und {@code /.well-known/*} weiter,
     * der Pfad ist also von aussen nicht erreichbar.
     *
     * <p>Body: {@code {"host":"...","scopes":[{"name":"...","description":"..."}]}}.
     * Eine Meldung beschreibt verfuegbare Faehigkeiten und aendert keine
     * Freigabeentscheidung.
     */
    private void registerScopes(HttpExchange exchange) throws IOException {
        if (!isMethod(exchange, "POST")) {
            throw new OAuthException(405, "invalid_request", "POST required");
        }
        java.net.InetAddress caller = exchange.getRemoteAddress().getAddress();
        if (caller == null || !caller.isLoopbackAddress()) {
            throw new OAuthException(403, "access_denied", "Loopback only");
        }
        ScopeRegistrationSink sink = registrationSink;
        if (sink == null) {
            throw new OAuthException(503, "temporarily_unavailable",
                    "Scope registration is not available");
        }
        com.fasterxml.jackson.databind.JsonNode body;
        try (java.io.InputStream in = exchange.getRequestBody()) {
            body = json.readTree(in.readAllBytes());
        } catch (RuntimeException | java.io.IOException malformed) {
            throw new OAuthException(400, "invalid_request", "Malformed registration body");
        }
        String host = body.path("host").asText("").trim().toLowerCase(java.util.Locale.ROOT);
        if (host.isEmpty()) {
            throw new OAuthException(400, "invalid_request", "Missing host");
        }
        // The token is bound to the host, so a service cannot describe another one.
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        String expected;
        try {
            expected = "Bearer " + registrationToken(host);
        } catch (IllegalArgumentException invalidHost) {
            throw new OAuthException(400, "invalid_request", "Invalid host");
        }
        if (authorization == null
                || !java.security.MessageDigest.isEqual(
                        authorization.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        expected.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            throw new OAuthException(401, "invalid_token", "Invalid registration token");
        }
        java.util.List<com.aresstack.webserver.domain.ScopeEntry> reported =
                new java.util.ArrayList<>();
        for (com.fasterxml.jackson.databind.JsonNode scope : body.path("scopes")) {
            String name = scope.path("name").asText("").trim();
            if (name.isEmpty()) {
                continue;
            }
            try {
                reported.add(com.aresstack.webserver.domain.ScopeEntry.registered(
                        name, scope.path("description").asText("")));
            } catch (IllegalArgumentException invalid) {
                throw new OAuthException(400, "invalid_request",
                        "Invalid scope name: '" + name + "'");
            }
        }

        boolean changed = sink.register(host, reported);
        ObjectNode response = json.createObjectNode()
                .put("host", host)
                .put("accepted", reported.size())
                .put("changed", changed);
        sendJson(exchange, 200, response);
    }

    /** Erlaubte Scopes der angesprochenen Ressource; unbekannter Host -> Default. */
    java.util.List<String> allowedScopesFor(String origin) {
        String host = origin == null ? "" : origin.replaceFirst("^https?://", "")
                .toLowerCase(java.util.Locale.ROOT);
        java.util.List<String> configured = activeResourceScopes.get(host);
        // Unbekannter Host -> Default. Bekannter Host mit leerer Liste bedeutet
        // "alles gesperrt" und wird nicht aufgefuellt.
        return configured == null ? java.util.List.of(DEFAULT_SCOPE) : configured;
    }

    /**
     * Berechnet den tatsaechlich gewaehrten Scope.
     *
     * <p>Ausgestellt wird genau das, was der Client angefordert hat <em>und</em> was
     * genehmigt ist. {@code Allow all} heisst: jeder bekannte Scope <em>darf
     * angefordert</em> werden -- nicht, dass der Server alle bekannten Scopes in
     * jedes Token legt. Ein Connector erhaelt damit nie mehr Rechte, als er selbst
     * verlangt hat, und unterschiedliche Connectoren bleiben voneinander isoliert.
     *
     * <p>Ein angeforderter Scope, der unbekannt oder gesperrt ist, fuehrt zu
     * {@code invalid_scope} und wird nicht stillschweigend entfernt: der Client soll
     * das am Autorisierungsendpunkt erfahren, statt spaeter unerklaerlich an einer
     * Tool-Pruefung zu scheitern.
     *
     * <p>Fehlt die Angabe ganz, wird ebenfalls abgelehnt. Einen Default zu ergaenzen
     * waere genau das Kompensieren fehlender Client-Anforderungen, das wir nicht
     * wollen -- und ein leerer Scope wuerde in AnAuthentias {@code normalizeScope()}
     * ohnehin auf {@code mcp} zurueckfallen und damit eine Sperre aushebeln.
     */
    static String grantedScope(String requested, java.util.List<String> allowed) {
        if (requested == null || requested.isBlank()) {
            throw new OAuthException(400, "invalid_scope",
                    "A scope must be requested for this resource");
        }
        java.util.LinkedHashSet<String> granted = new java.util.LinkedHashSet<>();
        for (String token : requested.trim().split("\s+")) {
            if (token.isEmpty()) {
                continue;
            }
            if (!allowed.contains(token)) {
                throw new OAuthException(400, "invalid_scope",
                        "Scope '" + token + "' is not approved for this resource");
            }
            granted.add(token);
        }
        if (granted.isEmpty()) {
            throw new OAuthException(400, "invalid_scope",
                    "A scope must be requested for this resource");
        }
        return String.join(" ", granted);
    }

    private void authorize(HttpExchange exchange, AuthorizationCodeService codes, String resource,
                           java.util.List<String> allowedScopes) throws IOException {
        Map<String, String> query = parseForm(exchange.getRequestURI().getRawQuery());
        requireMatchingResource(query.get("resource"), resource);
        String location = codes.authorize(
                query.get("response_type"),
                query.get("client_id"),
                query.get("redirect_uri"),
                query.get("state"),
                grantedScope(query.get("scope"), allowedScopes),
                query.get("code_challenge"),
                query.get("code_challenge_method"),
                resource);
        exchange.getResponseHeaders().set("Location", location);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(302, -1);
    }

    private void token(HttpExchange exchange, OAuthTokenService tokens, String resource)
            throws IOException {
        Map<String, String> form = parseForm(readBody(exchange));
        // Gilt für Code-Exchange UND Refresh-Grant gleichermaßen.
        requireMatchingResource(form.get("resource"), resource);
        String[] credentials = clientCredentials(exchange, form);
        TokenResponse response = tokens.exchangeToken(
                credentials[0], credentials[1],
                form.get("grant_type"), form.get("code"), form.get("refresh_token"),
                form.get("redirect_uri"), form.get("code_verifier"), resource);
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

    private ObjectNode authorizationServerMetadata(String issuer, java.util.List<String> scopes) {
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
        com.fasterxml.jackson.databind.node.ArrayNode supported = metadata.putArray("scopes_supported");
        scopes.forEach(supported::add);
        return metadata;
    }

    private ObjectNode protectedResourceMetadata(String issuer, String audience,
                                                 java.util.List<String> scopes) {
        ObjectNode metadata = json.createObjectNode()
                .put("resource", audience);
        metadata.putArray("authorization_servers").add(issuer);
        com.fasterxml.jackson.databind.node.ArrayNode supported = metadata.putArray("scopes_supported");
        scopes.forEach(supported::add);
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
