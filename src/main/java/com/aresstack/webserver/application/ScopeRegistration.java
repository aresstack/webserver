package com.aresstack.webserver.application;

import com.aresstack.webserver.domain.ScopeEntry;
import com.aresstack.webserver.domain.ScopePolicy;
import com.aresstack.webserver.domain.Site;
import com.aresstack.webserver.domain.WebServerConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Folds a service's self-reported scopes into a configuration.
 *
 * <p>Shared by the GUI controller and headless mode so both behave identically: a
 * registration describes available capabilities and must never change an operator's
 * Allow/Deny decision, and a manually maintained catalogue keeps working without any
 * registration.
 */
public final class ScopeRegistration {

    private ScopeRegistration() {
    }

    /**
     * @return the configuration with the merged catalogue, or empty when nothing
     *         changed or the host is not a central-OAuth site
     */
    public static Optional<WebServerConfiguration> merge(
            WebServerConfiguration configuration, String host, List<ScopeEntry> reported) {
        String normalized = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }

        List<Site> sites = new ArrayList<>();
        boolean changed = false;
        for (Site site : configuration.sites()) {
            boolean matches = site.host().value().toLowerCase(Locale.ROOT).equals(normalized)
                    && site.authentication().isPresent();
            if (!matches) {
                sites.add(site);
                continue;
            }
            ScopePolicy current = site.authentication().get().scopePolicy();
            ScopePolicy merged = current.mergeRegistered(reported);
            if (merged.equals(current)) {
                sites.add(site);
                continue;
            }
            sites.add(site.withAuthentication(
                    site.authentication().get().withScopePolicy(merged)));
            changed = true;
        }
        if (!changed) {
            return Optional.empty();
        }
        return Optional.of(new WebServerConfiguration(
                configuration.domain(), configuration.acme(), configuration.defaultUpstream(),
                sites, configuration.httpPort(), configuration.httpsPort(),
                configuration.oauth()));
    }

    /**
     * Environment a managed application needs to report its scopes.
     *
     * <p>Only handed to a service whose site is protected by central OAuth, and the
     * token is bound to that host, so a service can only describe its own resource.
     * Without this an operator would have to copy a secret out of a file for every
     * service.
     */
    public static Map<String, String> environmentFor(
            WebServerConfiguration configuration, String serviceId,
            String registrationUrl, java.util.function.Function<String, String> tokenForHost) {
        for (Site site : configuration.sites()) {
            boolean matches = site.managedApplication()
                    .map(app -> app.id().equals(serviceId)).orElse(false);
            if (!matches || site.authentication().isEmpty()) {
                continue;
            }
            String host = site.host().value();
            return Map.of(
                    "ARESSTACK_SCOPE_REGISTRATION_URL", registrationUrl,
                    "ARESSTACK_SCOPE_REGISTRATION_HOST", host,
                    "ARESSTACK_SCOPE_REGISTRATION_TOKEN", tokenForHost.apply(host));
        }
        return Map.of();
    }
}
