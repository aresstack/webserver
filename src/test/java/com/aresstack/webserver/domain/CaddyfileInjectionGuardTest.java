package com.aresstack.webserver.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Werte, die wörtlich ins Caddyfile gerendert werden, dürfen keinen
 * Whitespace enthalten — sonst wären Direktiven-Injection oder
 * Syntaxbruch über die Konfiguration möglich.
 */
class CaddyfileInjectionGuardTest {

    @Test
    void acmeEmailRejectsEmbeddedWhitespaceAndNewlines() {
        assertThrows(IllegalArgumentException.class,
                () -> new AcmeConfiguration("a@b\n    admin 0.0.0.0:2019",
                        AcmeConfiguration.LETS_ENCRYPT_PRODUCTION));
        assertThrows(IllegalArgumentException.class,
                () -> new AcmeConfiguration("a @b", AcmeConfiguration.LETS_ENCRYPT_PRODUCTION));
        assertDoesNotThrow(() -> new AcmeConfiguration("admin@example.org",
                AcmeConfiguration.LETS_ENCRYPT_PRODUCTION));
    }

    @Test
    void acmeCaRejectsEmbeddedWhitespaceAndNewlines() {
        assertThrows(IllegalArgumentException.class,
                () -> new AcmeConfiguration("", "https://x\n    admin 0.0.0.0:2019"));
        assertThrows(IllegalArgumentException.class,
                () -> new AcmeConfiguration("", "https://x y"));
        assertDoesNotThrow(() -> new AcmeConfiguration("",
                AcmeConfiguration.LETS_ENCRYPT_STAGING));
    }

    @Test
    void routePathMatcherRejectsAllWhitespaceVariants() {
        Upstream backend = Upstream.parse("http://localhost:8080");
        assertThrows(IllegalArgumentException.class,
                () -> new Route("/x\nrespond\t403", backend));
        assertThrows(IllegalArgumentException.class, () -> new Route("/x\ty", backend));
        assertDoesNotThrow(() -> new Route("/api/*", backend));
    }

    @Test
    void ipv6LoopbackCountsAsLocalBackend() {
        Site site = Site.of("example.org", "http://[::1]:8080")
                .withManagedApplication(ManagedApplication.defaults("ipv6", "/tmp/app.jar"));
        WebServerConfiguration config = new WebServerConfiguration(
                new DomainName("example.org"), AcmeConfiguration.withoutEmail(),
                Upstream.parse("http://localhost:8080"), java.util.List.of(site));
        assertDoesNotThrow(config::validate);
    }
}
