package com.aresstack.webserver.domain;

import java.util.List;
import java.util.Objects;

/**
 * Registrierter OAuth-Client des zentralen Authorization Servers.
 *
 * <p>{@code redirectUris}-Einträge sind entweder exakte URIs oder reine
 * Origins ({@code https://host} ohne Pfad) — ein Origin erlaubt jeden Pfad
 * auf genau diesem Host (heutiges ChatGPT-Verhalten). Das Secret darf
 * DPAPI-geschützt in der Konfigurationsdatei stehen.
 */
public record OAuthClientConfiguration(
        String clientId,
        String clientSecret,
        List<String> redirectUris) {

    public OAuthClientConfiguration {
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(clientSecret, "clientSecret");
        Objects.requireNonNull(redirectUris, "redirectUris");
        redirectUris = List.copyOf(redirectUris);
        if (clientId.isBlank() || clientId.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("Invalid OAuth client id");
        }
        if (clientSecret.isBlank()) {
            throw new IllegalArgumentException(
                    "OAuth client " + clientId + " requires a non-empty secret");
        }
        if (redirectUris.isEmpty()) {
            throw new IllegalArgumentException(
                    "OAuth client " + clientId + " requires at least one redirect URI");
        }
        for (String redirectUri : redirectUris) {
            if (!redirectUri.startsWith("https://")
                    || redirectUri.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException(
                        "OAuth redirect URIs must be https and free of whitespace: " + redirectUri);
            }
        }
    }
}
