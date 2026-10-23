package com.aresstack.webserver.infrastructure.oauth;

import com.aresstack.anauthentia.oauth.OAuthException;
import com.aresstack.webserver.domain.AcmeConfiguration;
import com.aresstack.webserver.domain.DomainName;
import com.aresstack.webserver.domain.OAuthClientConfiguration;
import com.aresstack.webserver.domain.OAuthConfiguration;
import com.aresstack.webserver.domain.Site;
import com.aresstack.webserver.domain.SiteAuthentication;
import com.aresstack.webserver.domain.Upstream;
import com.aresstack.webserver.domain.WebServerConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The server, not the client, decides which scopes a token carries.
 *
 * <p>Previously the requested scope was only validated syntactically and then
 * handed to the code service unchanged, so a client could ask for {@code admin} and
 * receive it. Until users and roles exist, the site configuration is the only
 * authority over what may be granted, and these tests pin that boundary.
 */
class OAuthScopeGrantTest {

    private static final List<String> ALLOWED = List.of("mcp", "mcp:read");

    @Test
    void refusesARequestWithoutAnyScope() {
        // Supplying a default here would be exactly the compensation for a missing
        // client request that we do not want.
        assertThrows(OAuthException.class, () -> OAuthRuntime.grantedScope(null, ALLOWED));
        assertThrows(OAuthException.class, () -> OAuthRuntime.grantedScope("   ", ALLOWED));
    }

    @Test
    void grantsExactlyWhatWasRequestedAndApproved() {
        // A connector never receives more than it asked for: that keeps connectors
        // isolated from one another even when the catalogue knows more scopes.
        assertEquals("mcp", OAuthRuntime.grantedScope("mcp", ALLOWED));
        assertEquals("mcp:read", OAuthRuntime.grantedScope("mcp:read", ALLOWED));
        assertEquals("mcp:read mcp", OAuthRuntime.grantedScope("mcp:read mcp", ALLOWED));
    }

    @Test
    void rejectsAnyScopeTheResourceDoesNotAllow() {
        // The decisive case: a syntactically valid scope is no longer enough.
        OAuthException rejected = assertThrows(OAuthException.class,
                () -> OAuthRuntime.grantedScope("mcp admin", ALLOWED));
        assertEquals(400, rejected.getStatusCode());
        assertEquals("invalid_scope", rejected.getError());
        assertTrue(rejected.getErrorDescription().contains("admin"));

        assertThrows(OAuthException.class, () -> OAuthRuntime.grantedScope("mcp:write", ALLOWED));
        assertThrows(OAuthException.class, () -> OAuthRuntime.grantedScope("MCP", ALLOWED));
    }

    @Test
    void repeatedAndOddlySpacedRequestsAreTolerated() {
        assertEquals("mcp", OAuthRuntime.grantedScope("mcp   mcp mcp", ALLOWED));
        assertEquals("mcp", OAuthRuntime.grantedScope("  mcp  ", ALLOWED));
    }

    @Test
    void rejectsEverythingWhenNothingIsApproved() {
        // Deny all without an allow override. Returning an empty scope would fall
        // back to AnAuthentia's default "mcp" and quietly undo the deny.
        OAuthException refused = assertThrows(OAuthException.class,
                () -> OAuthRuntime.grantedScope("mcp", List.of()));
        assertEquals("invalid_scope", refused.getError());
    }

    @Test
    void resourceScopesAreTakenFromTheProtectedSites() {
        WebServerConfiguration configuration = configuration(
                protectedSite("pyloros.example.com", List.of("mcp", "mcp:write")),
                protectedSite("other.example.com", List.of("mcp")),
                unprotectedSite("public.example.com"));

        Map<String, List<String>> scopes = OAuthRuntime.resourceScopes(configuration);

        assertEquals(List.of("mcp", "mcp:write"), scopes.get("pyloros.example.com"));
        assertEquals(List.of("mcp"), scopes.get("other.example.com"));
        // An unauthenticated site is not a resource of this authorization server.
        assertTrue(scopes.containsKey("public.example.com") == false);
        assertEquals(Map.of("pyloros.example.com", List.of("mcp", "mcp:write"),
                "other.example.com", List.of("mcp")).keySet(), scopes.keySet());
    }

    @Test
    void aSiteWithoutConfiguredScopesApprovesNothingAtAll() {
        // This used to fall back to the fixed scope "mcp". A configuration that an
        // operator had emptied therefore re-approved exactly the scope they had just
        // blocked. Nothing configured now means nothing requestable.
        WebServerConfiguration configuration = configuration(
                protectedSite("pyloros.example.com", List.of()));

        Map<String, List<String>> scopes = OAuthRuntime.resourceScopes(configuration);

        assertEquals(List.of(), scopes.get("pyloros.example.com"));
        assertThrows(OAuthException.class,
                () -> OAuthRuntime.grantedScope("admin", scopes.get("pyloros.example.com")));
        assertThrows(OAuthException.class,
                () -> OAuthRuntime.grantedScope("mcp", scopes.get("pyloros.example.com")),
                "the removed fallback scope must not be grantable either");
    }

    private static Site protectedSite(String host, List<String> scopes) {
        return new Site(new DomainName(host), Optional.of(Upstream.parse("http://127.0.0.1:8081")),
                List.of(), true, Optional.empty(), Optional.of(new SiteAuthentication(scopes)));
    }

    private static Site unprotectedSite(String host) {
        return new Site(new DomainName(host), Optional.of(Upstream.parse("http://127.0.0.1:8082")),
                List.of());
    }

    private static WebServerConfiguration configuration(Site... sites) {
        return new WebServerConfiguration(
                new DomainName("example.com"),
                new AcmeConfiguration("", "https://acme-v02.api.letsencrypt.org/directory"),
                Upstream.parse("http://127.0.0.1:8080"),
                List.of(sites), 80, 443,
                new OAuthConfiguration(true, "https://pyloros.example.com",
                        List.of(new OAuthClientConfiguration("app", "secret",
                                List.of("https://chatgpt.com")))));
    }
}
