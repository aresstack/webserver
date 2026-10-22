package com.aresstack.webserver.infrastructure.caddy;

import com.aresstack.webserver.domain.AcmeConfiguration;
import com.aresstack.webserver.domain.DomainName;
import com.aresstack.webserver.domain.OAuthClientConfiguration;
import com.aresstack.webserver.domain.OAuthConfiguration;
import com.aresstack.webserver.domain.Route;
import com.aresstack.webserver.domain.Site;
import com.aresstack.webserver.domain.SiteAuthentication;
import com.aresstack.webserver.domain.Upstream;
import com.aresstack.webserver.domain.WebServerConfiguration;
import com.aresstack.webserver.infrastructure.oauth.OAuthRuntime;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaddyfileOAuthRenderingTest {

    private static final OAuthConfiguration OAUTH = new OAuthConfiguration(true,
            "https://auth.example.org", List.of(new OAuthClientConfiguration(
            "chatgpt", "secret", List.of("https://chatgpt.com"))));

    private static WebServerConfiguration config(Site site, OAuthConfiguration oauth) {
        return new WebServerConfiguration(new DomainName("example.org"),
                AcmeConfiguration.withoutEmail(), Upstream.parse("http://127.0.0.1:8080"),
                List.of(site), 80, 443, oauth);
    }

    @Test
    void protectedSiteRoutesOAuthAndDiscoveryToTheLoopbackRuntime() {
        Site site = Site.of("askai.example.org", "http://127.0.0.1:8081")
                .withAuthentication(SiteAuthentication.centralOAuth());
        String caddyfile = new CaddyfileRenderer().render(config(site, OAUTH));

        String oauthBackend = "127.0.0.1:" + OAuthRuntime.DEFAULT_PORT;
        assertTrue(caddyfile.contains("handle /oauth/* {\n        reverse_proxy " + oauthBackend));
        assertTrue(caddyfile.contains("handle /.well-known/* {\n        reverse_proxy " + oauthBackend));
        // Die OAuth-Routen stehen vor dem Backend-Fallback.
        assertTrue(caddyfile.indexOf("/oauth/*") < caddyfile.indexOf("127.0.0.1:8081"));
        // Der Fallback bleibt erhalten (als handle-Block).
        assertTrue(caddyfile.contains("handle {\n        reverse_proxy 127.0.0.1:8081"));
    }

    @Test
    void protectedSiteWithPathRoutesKeepsThemAfterTheOAuthRoutes() {
        Site site = new Site(new DomainName("askai.example.org"),
                Optional.of(Upstream.parse("http://127.0.0.1:8081")),
                List.of(new Route("/api/*", Upstream.parse("http://127.0.0.1:9000"))), true,
                Optional.empty(), Optional.of(SiteAuthentication.centralOAuth()));
        String caddyfile = new CaddyfileRenderer().render(config(site, OAUTH));

        assertTrue(caddyfile.indexOf("/oauth/*") < caddyfile.indexOf("/api/*"));
        assertTrue(caddyfile.contains("handle /api/*"));
    }

    @Test
    void unprotectedSiteRendersByteIdenticalWhetherOAuthIsConfiguredOrNot() {
        Site site = Site.of("example.org", "http://127.0.0.1:8080");
        String withOAuth = new CaddyfileRenderer().render(config(site, OAUTH));
        String withoutOAuth = new CaddyfileRenderer().render(
                config(site, OAuthConfiguration.disabled()));

        assertEquals(withoutOAuth, withOAuth);
        assertFalse(withOAuth.contains("/oauth/"));
    }

    @Test
    void protectedSiteWithDisabledOAuthServiceIsRejectedByValidation() {
        Site site = Site.of("askai.example.org", "http://127.0.0.1:8081")
                .withAuthentication(SiteAuthentication.centralOAuth());
        WebServerConfiguration configuration = config(site, OAuthConfiguration.disabled());
        assertThrows(IllegalArgumentException.class, configuration::validate);
    }
}
