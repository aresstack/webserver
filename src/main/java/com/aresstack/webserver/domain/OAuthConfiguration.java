package com.aresstack.webserver.domain;

import java.util.List;
import java.util.Objects;

/**
 * Zentraler OAuth-Dienst (Authorization Server) der Installation.
 *
 * <p>{@code issuer} ist die öffentliche https-Basis-URL, unter der Clients
 * die Discovery-, Authorize- und Token-Endpunkte erreichen. Deaktiviert ist
 * der Dienst vollständig abgeschaltet; die Konfiguration bleibt erhalten.
 */
public record OAuthConfiguration(
        boolean enabled,
        String issuer,
        List<OAuthClientConfiguration> clients) {

    public OAuthConfiguration {
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(clients, "clients");
        clients = List.copyOf(clients);
        issuer = stripTrailingSlashes(issuer.trim());
        if (enabled) {
            if (!issuer.startsWith("https://")
                    || issuer.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException(
                        "OAuth issuer must be an https URL without whitespace: " + issuer);
            }
            if (clients.isEmpty()) {
                throw new IllegalArgumentException(
                        "Enabled OAuth service requires at least one registered client");
            }
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (OAuthClientConfiguration client : clients) {
            if (!seen.add(client.clientId())) {
                throw new IllegalArgumentException("Duplicate OAuth client id: " + client.clientId());
            }
        }
    }

    public static OAuthConfiguration disabled() {
        return new OAuthConfiguration(false, "https://disabled.invalid", List.of());
    }

    private static String stripTrailingSlashes(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
