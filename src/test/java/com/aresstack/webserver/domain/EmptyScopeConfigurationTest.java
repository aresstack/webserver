package com.aresstack.webserver.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An empty scope configuration must approve nothing, not a hardcoded scope.
 *
 * <p>{@code effectiveScopes()} used to fall back to the literal {@code mcp} whenever
 * the catalogue was empty. Clearing the scope field and removing every catalogue
 * entry - the obvious way to express "allow nothing" - therefore re-approved exactly
 * the scope an operator had just blocked, and did so silently.
 */
class EmptyScopeConfigurationTest {

    @Test
    void anEmptyCatalogueAndEmptyBaseSetApproveNothing() {
        SiteAuthentication authentication =
                new SiteAuthentication(List.of(), new ScopePolicy(true, List.of()));

        assertTrue(authentication.effectiveScopes().isEmpty(),
                "nothing configured must mean nothing requestable");
    }

    @Test
    void theRemovedFallbackScopeIsNotReintroduced() {
        SiteAuthentication authentication =
                new SiteAuthentication(List.of(), new ScopePolicy(true, List.of()));

        assertTrue(!authentication.effectiveScopes().contains("mcp"),
                "the hardcoded fallback must be gone");
    }

    @Test
    void anEmptyCatalogueFallsBackToTheConfiguredBaseSetUnderAllowAll() {
        // The legacy shape: only requiredScopes, no catalogue. Those stay the known
        // scopes so an older configuration keeps working.
        SiteAuthentication authentication =
                new SiteAuthentication(List.of("pyloros"), new ScopePolicy(true, List.of()));

        assertEquals(List.of("pyloros"), authentication.effectiveScopes());
    }

    @Test
    void denyAllOverridesTheBaseSetEvenWithoutACatalogue() {
        SiteAuthentication authentication =
                new SiteAuthentication(List.of("pyloros"), new ScopePolicy(false, List.of()));

        assertTrue(authentication.effectiveScopes().isEmpty(),
                "deny all must win over the base set as well");
    }

    @Test
    void anExplicitDenyStillWinsInsideAPopulatedCatalogue() {
        SiteAuthentication authentication = new SiteAuthentication(List.of("pyloros"),
                new ScopePolicy(true, List.of(
                        ScopeEntry.manual("pyloros"),
                        ScopeEntry.manual("mcp").withDecision(ScopeDecision.DENY))));

        assertEquals(List.of("pyloros"), authentication.effectiveScopes());
    }

    @Test
    void anAllowOverrideWorksUnderDenyAll() {
        SiteAuthentication authentication = new SiteAuthentication(List.of(),
                new ScopePolicy(false, List.of(
                        ScopeEntry.manual("pyloros").withDecision(ScopeDecision.ALLOW),
                        ScopeEntry.manual("mcp"))));

        assertEquals(List.of("pyloros"), authentication.effectiveScopes());
    }

    @Test
    void aKnownScopeIsApprovedByDefault() {
        // The agreed semantics: Allow all means a known scope may be requested.
        SiteAuthentication authentication = new SiteAuthentication(List.of(),
                new ScopePolicy(true, List.of(
                        ScopeEntry.manual("pyloros"), ScopeEntry.registered("pyloros:read", ""))));

        assertEquals(List.of("pyloros", "pyloros:read"), authentication.effectiveScopes());
    }
}
