package com.aresstack.webserver.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Fachliche Gesamtkonfiguration — die Source of Truth des Systems.
 * Kennt weder Caddy noch JSON noch Prozesse.
 */
public record WebServerConfiguration(
        DomainName domain,
        AcmeConfiguration acme,
        Upstream defaultUpstream,
        List<Site> sites,
        int httpPort,
        int httpsPort) {

    public WebServerConfiguration {
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(acme, "acme");
        Objects.requireNonNull(defaultUpstream, "defaultUpstream");
        Objects.requireNonNull(sites, "sites");
        sites = List.copyOf(sites);
        if (httpPort < 1 || httpPort > 65535 || httpsPort < 1 || httpsPort > 65535) {
            throw new IllegalArgumentException(
                    "Ports out of range: http=" + httpPort + " https=" + httpsPort);
        }
        if (httpPort == httpsPort) {
            throw new IllegalArgumentException("HTTP and HTTPS port must differ: " + httpPort);
        }
    }

    public WebServerConfiguration(DomainName domain, AcmeConfiguration acme,
                                  Upstream defaultUpstream, List<Site> sites) {
        this(domain, acme, defaultUpstream, sites, 80, 443);
    }

    /**
     * Prüft konfigurationsübergreifende Invarianten. Wird vor jedem Rendern
     * und Anwenden aufgerufen. Eine leere Konfiguration (0 veröffentlichte
     * Services) ist ein legitimer Zustand; jede FQDN darf veröffentlicht
     * werden — das {@code domain}-Feld ist rein informativ.
     */
    public void validate() {
        Set<String> seenHosts = new HashSet<>();
        Set<String> seenApplications = new HashSet<>();
        for (Site site : sites) {
            site.managedApplication().ifPresent(app -> {
                if (!seenApplications.add(app.id())) {
                    throw new IllegalArgumentException("Duplicate managed application id: " + app.id());
                }
                Upstream backend = site.effectiveUpstream(defaultUpstream);
                // URI.getHost() liefert IPv6-Adressen in Klammern ("[::1]").
                if (!backend.host().equalsIgnoreCase("localhost")
                        && !backend.host().equals("127.0.0.1")
                        && !backend.host().equals("::1")
                        && !backend.host().equals("[::1]")) {
                    throw new IllegalArgumentException("Managed application requires a local backend");
                }
            });
            if (!seenHosts.add(site.host().value())) {
                throw new IllegalArgumentException("Duplicate site host: " + site.host());
            }
            Set<String> seenPaths = new HashSet<>();
            for (Route route : site.routes()) {
                if (!seenPaths.add(route.pathMatcher())) {
                    throw new IllegalArgumentException(
                            "Duplicate path matcher " + route.pathMatcher() + " for host " + site.host());
                }
            }
        }
    }
}
