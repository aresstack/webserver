package com.aresstack.webserver.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Umgebungsvariablen, die sich vollstaendig aus den Service-Einstellungen ergeben.
 *
 * <p>Port, oeffentliche Adresse, Authentifizierungsmodus, Issuer und Dienst-Scope
 * stehen bereits in der Site. Sie zusaetzlich von Hand als Umgebungsvariable zu
 * pflegen, war doppelte Arbeit und eine Fehlerquelle: aenderte man den Port oder
 * das Scope im Service, blieb die Umgebungsvariable auf dem alten Wert stehen und
 * der Dienst startete mit einer Konfiguration, die der Oberflaeche widersprach.
 *
 * <p>Diese Werte werden deshalb bei jedem Lesen und jedem Anwenden der
 * Konfiguration neu abgeleitet. Sie bleiben in der Konfiguration sichtbar, damit
 * nachvollziehbar ist, was der Prozess bekommt, lassen sich aber nicht umbenennen
 * oder loeschen. Abschalten geht: dann wird die Variable beim Start weggelassen.
 */
public final class DerivedEnvironment {

    public static final String REQUIRED_SCOPE = "REQUIRED_SCOPE";
    public static final String SECURITY_MODE = "SECURITY_MODE";
    public static final String SERVER_PORT = "SERVER_PORT";
    public static final String PUBLIC_ORIGIN = "PUBLIC_ORIGIN";
    public static final String CENTRAL_OAUTH_ISSUER = "CENTRAL_OAUTH_ISSUER";

    /** Alle Namen, die dieser Mechanismus verwaltet -- in Anzeigereihenfolge. */
    public static final List<String> NAMES = List.of(
            REQUIRED_SCOPE, SECURITY_MODE, SERVER_PORT, PUBLIC_ORIGIN, CENTRAL_OAUTH_ISSUER);

    private static final Set<String> MANAGED = new LinkedHashSet<>(NAMES);

    private DerivedEnvironment() {
    }

    public static boolean isDerived(String name) {
        return MANAGED.contains(name);
    }

    /**
     * Die aus der Site ableitbaren Werte.
     *
     * <p>Nur was fuer diese Site auch gilt: ohne Central OAuth gibt es weder einen
     * Issuer noch ein Dienst-Scope, und ein erfundener Wert waere schlechter als
     * gar keiner. Ohne Upstream fehlt der Port.
     */
    public static Map<String, String> valuesFor(Site site) {
        Map<String, String> derived = new LinkedHashMap<>();
        Optional<SiteAuthentication> authentication = site.authentication();

        authentication.flatMap(DerivedEnvironment::serviceScope)
                .ifPresent(scope -> derived.put(REQUIRED_SCOPE, scope));
        derived.put(SECURITY_MODE, authentication.isPresent() ? "central-oauth" : "none");
        site.upstream().ifPresent(upstream ->
                derived.put(SERVER_PORT, String.valueOf(upstream.port())));
        String origin = origin(site);
        derived.put(PUBLIC_ORIGIN, origin);
        if (authentication.isPresent()) {
            // Per-Site-Issuer: die Site ist ihr eigener Issuer.
            derived.put(CENTRAL_OAUTH_ISSUER, origin);
        }
        return derived;
    }

    /**
     * Site mit frisch abgeleiteten Umgebungsvariablen.
     *
     * <p>Bestehende Enabled-Entscheidungen bleiben erhalten, eigene Variablen
     * bleiben unveraendert. Ist ein abgeleiteter Name fuer diese Site nicht mehr
     * anwendbar (etwa der Issuer nach dem Abschalten von Central OAuth), faellt er
     * weg, statt mit einem veralteten Wert stehen zu bleiben.
     */
    public static Site refresh(Site site) {
        if (site.managedApplication().isEmpty()) {
            return site;
        }
        ManagedApplication application = site.managedApplication().get();
        Map<String, String> derived = valuesFor(site);
        Map<String, ManagedEnvironmentVariable> merged = new LinkedHashMap<>();

        derived.forEach((name, value) -> {
            ManagedEnvironmentVariable existing = application.environment().get(name);
            boolean active = existing == null || existing.enabled();
            merged.put(name, new ManagedEnvironmentVariable(value, false, active));
        });
        application.environment().forEach((name, variable) -> {
            if (!isDerived(name)) {
                merged.put(name, variable);
            }
        });

        if (merged.equals(application.environment())) {
            return site;
        }
        return site.withManagedApplication(application.withEnvironment(merged));
    }

    /** Dieselbe Auffrischung fuer jede Site einer Konfiguration. */
    public static WebServerConfiguration refresh(WebServerConfiguration configuration) {
        List<Site> sites = new ArrayList<>();
        boolean changed = false;
        for (Site site : configuration.sites()) {
            Site refreshed = refresh(site);
            changed |= refreshed != site;
            sites.add(refreshed);
        }
        if (!changed) {
            return configuration;
        }
        return new WebServerConfiguration(configuration.domain(), configuration.acme(),
                configuration.defaultUpstream(), sites, configuration.httpPort(),
                configuration.httpsPort(), configuration.oauth());
    }

    private static Optional<String> serviceScope(SiteAuthentication authentication) {
        if (!authentication.requiredScopes().isEmpty()) {
            return Optional.of(authentication.requiredScopes().get(0));
        }
        List<String> effective = authentication.effectiveScopes();
        return effective.isEmpty() ? Optional.empty() : Optional.of(effective.get(0));
    }

    private static String origin(Site site) {
        return (site.httpsEnabled() ? "https://" : "http://") + site.host().value();
    }
}
