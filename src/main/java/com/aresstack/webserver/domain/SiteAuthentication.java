package com.aresstack.webserver.domain;

import java.util.List;
import java.util.Objects;

/**
 * Authentifizierungsrichtlinie einer veröffentlichten Site: vorhanden heißt
 * "geschützt durch den zentralen OAuth-Dienst". Die Backends validieren die
 * ausgestellten JWTs selbst; {@code requiredScopes} dokumentiert die vom
 * Dienst erwarteten Scopes und wird an dessen Metadaten durchgereicht.
 */
public record SiteAuthentication(List<String> requiredScopes) {

    public SiteAuthentication {
        Objects.requireNonNull(requiredScopes, "requiredScopes");
        requiredScopes = List.copyOf(requiredScopes);
        for (String scope : requiredScopes) {
            if (scope.isBlank() || scope.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException("Invalid scope: '" + scope + "'");
            }
        }
    }

    public static SiteAuthentication centralOAuth() {
        return new SiteAuthentication(List.of("mcp"));
    }
}
