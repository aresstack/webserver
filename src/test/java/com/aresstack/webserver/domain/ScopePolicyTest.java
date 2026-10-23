package com.aresstack.webserver.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Allow/Deny semantics of the permission catalogue.
 *
 * <p>Automatic discovery is optional: a manually maintained catalogue must behave
 * exactly like a reported one, and a service that reports nothing must keep working.
 */
class ScopePolicyTest {

    @Test
    void allowAllGrantsEveryKnownScope() {
        ScopePolicy policy = ScopePolicy.allowAll(List.of("mcp", "mcp:read"));

        assertTrue(policy.allowByDefault());
        assertEquals(List.of("mcp", "mcp:read"), policy.effectiveScopes());
    }

    @Test
    void allowAllDoesNotCoverScopesOutsideTheCatalogue() {
        ScopePolicy policy = ScopePolicy.allowAll(List.of("mcp"));

        // The decisive guarantee: "allow all" means everything known, not everything
        // a client might ask for.
        assertFalse(policy.knows("admin"));
        assertFalse(policy.effectiveScopes().contains("admin"));
    }

    @Test
    void denyAllBlocksEverythingUntilSomethingIsAllowedExplicitly() {
        ScopePolicy denied = ScopePolicy.allowAll(List.of("mcp", "mcp:write"))
                .withAllowByDefault(false);

        assertEquals(List.of(), denied.effectiveScopes());

        ScopePolicy withOverride = denied.withDecision("mcp", ScopeDecision.ALLOW);
        assertEquals(List.of("mcp"), withOverride.effectiveScopes());
    }

    @Test
    void anIndividualDenyOverridesAllowAll() {
        ScopePolicy policy = ScopePolicy.allowAll(List.of("mcp", "mcp:write"))
                .withDecision("mcp:write", ScopeDecision.DENY);

        assertEquals(List.of("mcp"), policy.effectiveScopes());
    }

    @Test
    void aManuallyAddedScopeNeedsNoRegistration() {
        ScopePolicy policy = new ScopePolicy(true, List.of(ScopeEntry.manual("git:write")));

        assertTrue(policy.knows("git:write"));
        assertEquals(List.of("git:write"), policy.effectiveScopes());
        assertFalse(policy.entries().get(0).registered());
    }

    @Test
    void registrationAddsNewScopesWithoutTouchingExistingDecisions() {
        ScopePolicy policy = ScopePolicy.allowAll(List.of("mcp"))
                .withDecision("mcp", ScopeDecision.DENY);

        ScopePolicy merged = policy.mergeRegistered(List.of(
                ScopeEntry.registered("mcp", "Base access"),
                ScopeEntry.registered("mcp:read", "Read-only tools")));

        // The deny decision survives a registration that reports the same scope.
        assertEquals(ScopeDecision.DENY, entry(merged, "mcp").decision());
        assertEquals("Base access", entry(merged, "mcp").description());
        assertTrue(entry(merged, "mcp").registered());
        // A new scope follows the service-wide rule.
        assertEquals(ScopeDecision.DEFAULT, entry(merged, "mcp:read").decision());
        assertEquals(List.of("mcp:read"), merged.effectiveScopes());
    }

    @Test
    void registrationKeepsManualEntriesThatAreNoLongerReported() {
        ScopePolicy policy = new ScopePolicy(true, List.of(
                ScopeEntry.manual("git:write"), ScopeEntry.registered("mcp", "Base")));

        ScopePolicy merged = policy.mergeRegistered(List.of(ScopeEntry.registered("mcp", "Base")));

        assertTrue(merged.knows("git:write"),
                "removing an operator's own configuration because a plugin went quiet "
                        + "would be destructive");
        assertEquals(List.of("git:write", "mcp"), merged.effectiveScopes());
    }

    @Test
    void anEmptyRegistrationChangesNothing() {
        ScopePolicy policy = ScopePolicy.allowAll(List.of("mcp"));

        assertEquals(policy, policy.mergeRegistered(List.of()));
    }

    @Test
    void siteWithoutAPolicyBehavesLikeBefore() {
        // Legacy configuration: only requiredScopes, no catalogue.
        SiteAuthentication legacy = new SiteAuthentication(List.of("mcp"));

        assertEquals(List.of("mcp"), legacy.effectiveScopes());
        assertTrue(legacy.scopePolicy().allowByDefault());
    }

    @Test
    void siteWithoutAnyScopeApprovesNothing() {
        // This asserted a fallback to the literal "mcp". That fallback let a
        // configuration an operator had emptied re-approve exactly the scope they
        // had blocked, so it is gone: nothing configured means nothing requestable.
        SiteAuthentication empty = new SiteAuthentication(List.of());

        assertEquals(List.of(), empty.effectiveScopes());
    }

    @Test
    void denyAllYieldsAnEmptyResultInsteadOfFallingBackToRequiredScopes() {
        SiteAuthentication denied = new SiteAuthentication(List.of("mcp"),
                ScopePolicy.allowAll(List.of("mcp")).withAllowByDefault(false));

        // Without this the fallback would silently undo the operator's deny.
        assertEquals(List.of(), denied.effectiveScopes());
    }

    private static ScopeEntry entry(ScopePolicy policy, String name) {
        return policy.entries().stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing scope " + name));
    }
}
