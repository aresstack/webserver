package com.aresstack.webserver.domain;

import java.util.List;
import java.util.Objects;

/**
 * Authentifizierungsrichtlinie einer veröffentlichten Site: vorhanden heißt
 * "geschützt durch den zentralen OAuth-Dienst". Die Backends validieren die
 * ausgestellten JWTs selbst.
 *
 * <p>{@code requiredScopes} ist der Basissatz, den der Dienst mindestens kennt --
 * historisch die einzige Angabe und deshalb weiterhin die Rückfallebene.
 * {@code scopePolicy} ergänzt den Berechtigungskatalog samt Allow-/Deny-Entscheidungen.
 * Fehlt die Richtlinie in einer älteren Konfiguration, wird sie aus
 * {@code requiredScopes} mit {@code Allow all} abgeleitet: bestehende Dienste
 * verhalten sich damit unverändert, ohne dass eine Migration nötig ist.
 */
public record SiteAuthentication(List<String> requiredScopes, ScopePolicy scopePolicy) {

    public SiteAuthentication {
        Objects.requireNonNull(requiredScopes, "requiredScopes");
        requiredScopes = List.copyOf(requiredScopes);
        for (String scope : requiredScopes) {
            if (scope.isBlank() || scope.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException("Invalid scope: '" + scope + "'");
            }
        }
        scopePolicy = scopePolicy == null ? ScopePolicy.allowAll(requiredScopes) : scopePolicy;
    }

    /** Legacy-Form: nur der Basissatz, Richtlinie wird abgeleitet. */
    public SiteAuthentication(List<String> requiredScopes) {
        this(requiredScopes, null);
    }

    public static SiteAuthentication centralOAuth() {
        return new SiteAuthentication(List.of("mcp"));
    }

    /**
     * Die tatsächlich gewährbaren Scopes.
     *
     * <p>Nur ein <em>leerer Katalog</em> faellt auf {@code requiredScopes} und
     * weiter auf {@code mcp} zurueck, damit eine Site ohne jede Scope-Angabe wie
     * bisher funktioniert -- insbesondere solange Backends wie Pyloros das feste
     * Scope {@code mcp} erwarten.
     *
     * <p>Ein leeres <em>Ergebnis</em> bei vorhandenem Katalog ist dagegen eine
     * bewusste Entscheidung ({@code Deny all} ohne Allow-Override) und wird nicht
     * ueberschrieben -- sonst wuerde gerade das Sperren alles wieder freigeben.
     */
    public List<String> effectiveScopes() {
        if (!scopePolicy.entries().isEmpty()) {
            return scopePolicy.effectiveScopes();
        }
        return requiredScopes.isEmpty() ? List.of("mcp") : requiredScopes;
    }

    public SiteAuthentication withScopePolicy(ScopePolicy policy) {
        return new SiteAuthentication(requiredScopes, policy);
    }
}
