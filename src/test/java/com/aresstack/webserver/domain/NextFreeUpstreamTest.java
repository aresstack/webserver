package com.aresstack.webserver.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Ein neuer Dienst bekommt einen Port, den noch keiner benutzt.
 *
 * <p>Vorgeschlagen wurde immer 8080. Der zweite Dienst startete damit auf einem
 * belegten Port und endete mit "Address already in use" - ein Fehler, der erst in
 * seiner Logdatei auffiel, nicht beim Anlegen.
 */
class NextFreeUpstreamTest {

    @Test
    void withoutServicesTheConfiguredPortStays() {
        assertEquals(8080, configuration().nextFreeUpstream().port());
    }

    @Test
    void theHighestPortInUseDecidesTheSuggestion() {
        WebServerConfiguration config = configuration(
                site("pyloros.example.com", 8081),
                site("argos.example.com", 8090),
                site("alt.example.com", 8082));

        assertEquals(8091, config.nextFreeUpstream().port(),
                "einer mehr als der hoechste, nicht einer mehr als der letzte");
    }

    @Test
    void aServiceWithoutItsOwnBackendCountsAsTheDefaultPort() {
        // Solche Dienste erben den Standard-Upstream und belegen damit dessen Port.
        WebServerConfiguration config = configuration(
                new Site(new DomainName("erbe.example.com"), java.util.Optional.empty(), List.of()));

        assertEquals(8081, config.nextFreeUpstream().port());
    }

    @Test
    void schemeAndHostComeFromTheConfiguredDefault() {
        Upstream next = configuration(site("pyloros.example.com", 9000)).nextFreeUpstream();

        assertEquals("http", next.scheme());
        assertEquals("127.0.0.1", next.host());
        assertEquals(9001, next.port());
    }

    private static Site site(String host, int port) {
        return new Site(new DomainName(host),
                java.util.Optional.of(new Upstream("http", "127.0.0.1", port)), List.of());
    }

    private static WebServerConfiguration configuration(Site... sites) {
        return new WebServerConfiguration(new DomainName("example.com"),
                AcmeConfiguration.withoutEmail(), new Upstream("http", "127.0.0.1", 8080),
                List.of(sites));
    }
}
