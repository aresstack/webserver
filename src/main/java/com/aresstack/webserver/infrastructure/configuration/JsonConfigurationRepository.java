package com.aresstack.webserver.infrastructure.configuration;

import com.aresstack.webserver.application.ConfigurationRepository;
import com.aresstack.webserver.domain.AcmeConfiguration;
import com.aresstack.webserver.domain.DomainName;
import com.aresstack.webserver.domain.ManagedApplication;
import com.aresstack.webserver.domain.OAuthClientConfiguration;
import com.aresstack.webserver.domain.OAuthConfiguration;
import com.aresstack.webserver.domain.Route;
import com.aresstack.webserver.domain.Site;
import com.aresstack.webserver.domain.Upstream;
import com.aresstack.webserver.domain.WebServerConfiguration;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;

/**
 * Lädt und speichert config/webserver.json und übersetzt zwischen dem
 * JSON-Schema der Datei und dem Domainmodell.
 */
public class JsonConfigurationRepository implements ConfigurationRepository {

    // Dateiformat von webserver.json — bewusst getrennt vom Domainmodell.
    record FileModel(String domain, String defaultUpstream, Integer httpPort, Integer httpsPort,
                     AcmeModel acme, List<SiteModel> sites, OAuthModel oauth) {
    }

    record AcmeModel(String email, String ca) {
    }

    record OAuthModel(Boolean enabled, String issuer, List<OAuthClientModel> clients) {
    }

    record OAuthClientModel(String clientId, String clientSecret, List<String> redirectUris) {
    }

    record SiteModel(String host, String upstream, Boolean https, List<RouteModel> routes,
                     ManagedApplication managedApplication) {
    }

    record RouteModel(String path, String upstream) {
    }

    // Unbekannte Felder tolerieren: eine von einer neueren Version (oder von
    // Hand) ergänzte Konfiguration darf das Laden niemals komplett verhindern.
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private final Path file;

    public JsonConfigurationRepository(Path file) {
        this.file = file;
    }

    @Override
    public WebServerConfiguration load() {
        FileModel model;
        try {
            model = mapper.readValue(Files.readAllBytes(file), FileModel.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read configuration " + file, e);
        }
        return toDomain(model);
    }

    @Override
    public void save(WebServerConfiguration configuration) {
        FileModel model = fromDomain(configuration);
        try {
            Files.createDirectories(file.getParent());
            Path temp = Files.createTempFile(file.getParent(), "webserver", ".json.tmp");
            mapper.writeValue(temp.toFile(), model);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write configuration " + file, e);
        }
    }

    static WebServerConfiguration toDomain(FileModel model) {
        require(model.domain() != null, "'domain' is required");

        Upstream defaultUpstream = model.defaultUpstream() != null
                ? Upstream.parse(model.defaultUpstream())
                : Upstream.parse("http://127.0.0.1:8080");
        // acme und sites sind optional: keine Mail und 0 Services sind valide.
        String email = model.acme() != null && model.acme().email() != null ? model.acme().email() : "";
        String ca = model.acme() != null && model.acme().ca() != null
                ? model.acme().ca()
                : AcmeConfiguration.LETS_ENCRYPT_PRODUCTION;
        AcmeConfiguration acme = new AcmeConfiguration(email, ca);
        List<SiteModel> siteModels = model.sites() == null ? List.of() : model.sites();

        List<Site> sites = siteModels.stream().map(site -> {
            require(site.host() != null, "each site requires a 'host'");
            Optional<Upstream> upstream = Optional.ofNullable(site.upstream()).map(Upstream::parse);
            List<Route> routes = site.routes() == null ? List.of() : site.routes().stream()
                    .map(route -> {
                        require(route.path() != null && route.upstream() != null,
                                "each route requires 'path' and 'upstream'");
                        return new Route(route.path(), Upstream.parse(route.upstream()));
                    })
                    .toList();
            boolean httpsEnabled = site.https() == null || site.https();
            return new Site(new DomainName(site.host()), upstream, routes, httpsEnabled,
                    Optional.ofNullable(site.managedApplication()));
        }).toList();

        int httpPort = model.httpPort() != null ? model.httpPort() : 80;
        int httpsPort = model.httpsPort() != null ? model.httpsPort() : 443;
        return new WebServerConfiguration(new DomainName(model.domain()), acme, defaultUpstream,
                sites, httpPort, httpsPort, toOAuth(model.oauth()));
    }

    private static OAuthConfiguration toOAuth(OAuthModel model) {
        if (model == null) {
            return OAuthConfiguration.disabled();
        }
        List<OAuthClientConfiguration> clients = model.clients() == null
                ? List.of()
                : model.clients().stream().map(client -> {
                    require(client.clientId() != null && client.clientSecret() != null,
                            "each oauth client requires 'clientId' and 'clientSecret'");
                    return new OAuthClientConfiguration(client.clientId(), client.clientSecret(),
                            client.redirectUris() == null ? List.of() : client.redirectUris());
                }).toList();
        boolean enabled = Boolean.TRUE.equals(model.enabled());
        return new OAuthConfiguration(enabled,
                model.issuer() == null ? "https://disabled.invalid" : model.issuer(), clients);
    }

    static FileModel fromDomain(WebServerConfiguration configuration) {
        List<SiteModel> sites = configuration.sites().stream().map(site -> new SiteModel(
                site.host().value(),
                site.upstream().map(Upstream::toString).orElse(null),
                site.httpsEnabled() ? null : Boolean.FALSE,
                site.routes().isEmpty() ? null : site.routes().stream()
                        .map(route -> new RouteModel(route.pathMatcher(), route.upstream().toString()))
                        .toList(),
                site.managedApplication().orElse(null)
        )).toList();
        return new FileModel(
                configuration.domain().value(),
                configuration.defaultUpstream().toString(),
                configuration.httpPort() == 80 ? null : configuration.httpPort(),
                configuration.httpsPort() == 443 ? null : configuration.httpsPort(),
                new AcmeModel(
                        configuration.acme().email().isBlank() ? null : configuration.acme().email(),
                        configuration.acme().ca()),
                sites,
                fromOAuth(configuration.oauth()));
    }

    private static OAuthModel fromOAuth(OAuthConfiguration oauth) {
        if (!oauth.enabled() && oauth.clients().isEmpty()) {
            return null;
        }
        return new OAuthModel(oauth.enabled(), oauth.issuer(),
                oauth.clients().stream().map(client -> new OAuthClientModel(
                        client.clientId(), client.clientSecret(), client.redirectUris())).toList());
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException("Invalid webserver.json: " + message);
        }
    }
}
