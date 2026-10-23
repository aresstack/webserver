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
     * Die tatsaechlich gewaehrbaren Scopes.
     *
     * <p>Fehlt der Katalog ganz (aeltere Konfiguration), gelten {@code requiredScopes}
     * als die bekannten Scopes -- und auch nur dann, wenn {@code Allow all} gilt. Ein
     * fest verdrahtetes Fallback-Scope gibt es nicht mehr: es hat eine vollstaendig
     * geleerte Konfiguration auf {@code mcp} zurueckfallen lassen und damit genau das
     * Scope freigegeben, das ein Betreiber zuvor gesperrt hatte.
     *
     * <p>Ein leeres Ergebnis ist eine gueltige Antwort und bedeutet "nichts
     * anforderbar". Die Token-Ausstellung lehnt dann jede Anforderung mit
     * {@code invalid_scope} ab.
     */
    public List<String> effectiveScopes() {
        if (!scopePolicy.entries().isEmpty()) {
            return scopePolicy.effectiveScopes();
        }
        // Kein Katalog: die Basisangabe ist die Menge der bekannten Scopes. Ohne
        // Allow all ist auch davon nichts freigegeben.
        return scopePolicy.allowByDefault() ? requiredScopes : List.of();
    }

    public SiteAuthentication withScopePolicy(ScopePolicy policy) {
        return new SiteAuthentication(requiredScopes, policy);
    }
}
