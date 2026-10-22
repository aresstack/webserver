package com.aresstack.webserver.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Ein veröffentlichter Host. Ohne eigenen Upstream greift der
 * Default-Upstream der Gesamtkonfiguration. HTTPS ist standardmäßig aktiv;
 * ohne HTTPS wird die Site nur über HTTP :80 ausgeliefert.
 */
public record Site(DomainName host, Optional<Upstream> upstream, List<Route> routes,
                   boolean httpsEnabled, Optional<ManagedApplication> managedApplication,
                   Optional<SiteAuthentication> authentication) {

    public Site {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(upstream, "upstream");
        Objects.requireNonNull(routes, "routes");
        Objects.requireNonNull(managedApplication, "managedApplication");
        Objects.requireNonNull(authentication, "authentication");
        routes = List.copyOf(routes);
    }

    public Site(DomainName host, Optional<Upstream> upstream, List<Route> routes,
                boolean httpsEnabled, Optional<ManagedApplication> managedApplication) {
        this(host, upstream, routes, httpsEnabled, managedApplication, Optional.empty());
    }

    public Site(DomainName host, Optional<Upstream> upstream, List<Route> routes) {
        this(host, upstream, routes, true, Optional.empty(), Optional.empty());
    }

    public Site(DomainName host, Optional<Upstream> upstream, List<Route> routes, boolean httpsEnabled) {
        this(host, upstream, routes, httpsEnabled, Optional.empty(), Optional.empty());
    }

    public Site withManagedApplication(ManagedApplication application) {
        return new Site(host, upstream, routes, httpsEnabled, Optional.of(application), authentication);
    }

    public Site withAuthentication(SiteAuthentication policy) {
        return new Site(host, upstream, routes, httpsEnabled, managedApplication,
                Optional.ofNullable(policy));
    }

    public static Site of(String host) {
        return new Site(new DomainName(host), Optional.empty(), List.of());
    }

    public static Site of(String host, String upstream) {
        return new Site(new DomainName(host), Optional.of(Upstream.parse(upstream)), List.of());
    }

    public Upstream effectiveUpstream(Upstream defaultUpstream) {
        return upstream.orElse(defaultUpstream);
    }
}
