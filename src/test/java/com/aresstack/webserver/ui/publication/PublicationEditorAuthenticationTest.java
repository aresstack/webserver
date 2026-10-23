package com.aresstack.webserver.ui.publication;

import com.aresstack.webserver.domain.DomainName;
import com.aresstack.webserver.domain.ScopeDecision;
import com.aresstack.webserver.domain.ScopeEntry;
import com.aresstack.webserver.domain.ScopePolicy;
import com.aresstack.webserver.domain.Site;
import com.aresstack.webserver.domain.SiteAuthentication;
import com.aresstack.webserver.domain.Upstream;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Speichern im Publication-Editor darf keine Allow-/Deny-Entscheidung verlieren.
 *
 * <p>Der Editor uebergab beim Speichern nur die Scope-Liste. Der Katalog wurde
 * daraus als "Allow all" neu aufgebaut - jede im Permissions-Dialog getroffene
 * Deny-Entscheidung und jeder von einem Dienst gemeldete Eintrag war damit weg,
 * ohne Hinweis. Fuer die produktive Pyloros-Site haette ein einziges Speichern das
 * gesperrte Scope {@code mcp} wieder freigegeben.
 */
class PublicationEditorAuthenticationTest {

    @Test
    void savingKeepsADenyDecision() {
        ScopePolicy policy = new ScopePolicy(true, List.of(
                ScopeEntry.manual("pyloros"),
                ScopeEntry.manual("mcp").withDecision(ScopeDecision.DENY)));

        Site saved = PublicationEditor.withAuthentication(
                site(), true, List.of("pyloros", "mcp"), policy);

        SiteAuthentication authentication = saved.authentication().orElseThrow();
        assertEquals(ScopeDecision.DENY, authentication.scopePolicy().entries().stream()
                        .filter(entry -> entry.name().equals("mcp")).findFirst().orElseThrow()
                        .decision(),
                "ein gesperrtes Scope darf durch Speichern nicht wieder freigegeben werden");
        assertEquals(List.of("pyloros"), authentication.effectiveScopes());
    }

    @Test
    void savingKeepsADenyAllDefault() {
        ScopePolicy policy = new ScopePolicy(false, List.of(ScopeEntry.manual("pyloros")));

        Site saved = PublicationEditor.withAuthentication(
                site(), true, List.of("pyloros"), policy);

        assertFalse(saved.authentication().orElseThrow().scopePolicy().allowByDefault());
        assertTrue(saved.authentication().orElseThrow().effectiveScopes().isEmpty());
    }

    @Test
    void savingKeepsAReportedEntry() {
        ScopePolicy policy = new ScopePolicy(true, List.of(
                ScopeEntry.registered("pyloros", "Pyloros MCP gateway access")));

        Site saved = PublicationEditor.withAuthentication(
                site(), true, List.of("pyloros"), policy);

        ScopeEntry entry = saved.authentication().orElseThrow().scopePolicy().entries().get(0);
        assertTrue(entry.registered(), "ein gemeldeter Eintrag bleibt als gemeldet erhalten");
        assertEquals("Pyloros MCP gateway access", entry.description());
    }

    @Test
    void withoutCentralOAuthTheSiteStaysUnprotected() {
        Site saved = PublicationEditor.withAuthentication(
                site(), false, List.of("pyloros"), ScopePolicy.allowAll(List.of("pyloros")));

        assertTrue(saved.authentication().isEmpty());
    }

    private static Site site() {
        return new Site(new DomainName("pyloros.aresstack.de"),
                Optional.of(Upstream.parse("http://127.0.0.1:8081")), List.of(), true,
                Optional.empty());
    }
}
