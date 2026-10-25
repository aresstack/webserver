package com.aresstack.webserver.ui;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Der DynDNS-Updater beherrscht mehrere Namen.
 *
 * <p>Bisher wurde genau ein Name aktualisiert. Fuehrt man pro Dienst einen eigenen
 * A-Record - weil der Anbieter keine CNAMEs kann oder man es so eingerichtet hat -
 * blieben alle uebrigen Namen nach einem IP-Wechsel auf der alten Adresse stehen,
 * und ihre Zertifikatserneuerung scheiterte.
 */
class DynDnsMultipleDomainsTest {

    private HttpServer server;
    private final List<String> requested = new CopyOnWriteArrayList<>();
    private volatile java.util.function.Predicate<String> failFor = domain -> false;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/update", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            String domain = query == null ? "" : query.replaceFirst(".*host=([^&]*).*", "$1");
            requested.add(domain);
            int status = failFor.test(domain) ? 500 : 200;
            byte[] body = ("good " + domain).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void everyConfiguredNameGetsItsOwnRequest() {
        DynDnsClient.Result result = DynDnsClient.updateAll(template(),
                List.of("pyloros.aresstack.de", "argos.aresstack.de", "askai.aresstack.de"),
                "203.0.113.7");

        assertEquals(List.of("pyloros.aresstack.de", "argos.aresstack.de", "askai.aresstack.de"),
                requested);
        assertTrue(result.success(), result.message());
        assertTrue(result.message().contains("pyloros.aresstack.de: HTTP 200"), result.message());
    }

    @Test
    void oneFailingNameMakesTheWholeRunFailButTheOthersStillRan() {
        failFor = domain -> domain.startsWith("argos");

        DynDnsClient.Result result = DynDnsClient.updateAll(template(),
                List.of("pyloros.aresstack.de", "argos.aresstack.de", "askai.aresstack.de"),
                "203.0.113.7");

        assertEquals(3, requested.size(), "ein Fehlschlag darf die restlichen nicht abschneiden");
        assertFalse(result.success());
        assertTrue(result.message().contains("argos.aresstack.de: HTTP 500"), result.message());
        assertTrue(result.message().contains("askai.aresstack.de: HTTP 200"), result.message());
    }

    @Test
    void aSingleNameStillWorksExactlyAsBefore() {
        DynDnsClient.Result result = DynDnsClient.updateAll(template(),
                List.of("pyloros.aresstack.de"), "203.0.113.7");

        assertEquals(List.of("pyloros.aresstack.de"), requested);
        assertTrue(result.success(), result.message());
    }

    @Test
    void theAddressIsSubstitutedPerRequest() {
        DynDnsClient.updateAll("http://127.0.0.1:" + server.getAddress().getPort()
                + "/update?host=<domain>&ip=<ipaddr>", List.of("a.example.org"), "198.51.100.3");

        assertEquals(List.of("a.example.org"), requested);
    }

    @Test
    void theParsingAcceptsLinesCommasAndSemicolons() {
        String previous = AppPreferences.dynDnsDomain();
        try {
            AppPreferences.setDynDnsDomain("pyloros.aresstack.de\nargos.aresstack.de,"
                    + " askai.aresstack.de; pyloros.aresstack.de\n\n");

            assertEquals(List.of("pyloros.aresstack.de", "argos.aresstack.de",
                            "askai.aresstack.de"),
                    AppPreferences.dynDnsDomains(),
                    "Leerzeilen weg, Duplikate einmal, Reihenfolge erhalten");
        } finally {
            AppPreferences.setDynDnsDomain(previous);
        }
    }

    @Test
    void anEmptySettingYieldsNoNames() {
        String previous = AppPreferences.dynDnsDomain();
        try {
            AppPreferences.setDynDnsDomain("   \n  ");
            assertEquals(Collections.emptyList(), AppPreferences.dynDnsDomains());
        } finally {
            AppPreferences.setDynDnsDomain(previous);
        }
    }

    private String template() {
        return "http://127.0.0.1:" + server.getAddress().getPort()
                + "/update?host=<domain>&myip=<ipaddr>";
    }
}
