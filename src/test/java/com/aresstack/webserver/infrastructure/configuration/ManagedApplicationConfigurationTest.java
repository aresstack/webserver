package com.aresstack.webserver.infrastructure.configuration;

import com.aresstack.webserver.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class ManagedApplicationConfigurationTest {
    @TempDir Path directory;

    @Test
    void persistsOptionalProcessAndItsRestartConfiguration() {
        ManagedApplication app = new ManagedApplication("service-1", "/tmp/example.jar",
                "java", "", List.of("-Xmx128m"), List.of("--hello"),
                Map.of("PUBLIC_ORIGIN", new ManagedEnvironmentVariable("https://example.org", false)),
                true, true, new RestartPolicy(2, 2.0, 100, 120, 0), 12);
        Site site = new Site(new DomainName("example.org"),
                Optional.of(Upstream.parse("http://localhost:8080")),
                List.of(), true, Optional.of(app));
        WebServerConfiguration config = new WebServerConfiguration(
                new DomainName("example.org"), AcmeConfiguration.withoutEmail(),
                Upstream.parse("http://localhost:8080"), List.of(site));
        JsonConfigurationRepository repository =
                new JsonConfigurationRepository(directory.resolve("webserver.json"));
        repository.save(config);
        assertEquals(config, repository.load());
    }

    @Test
    void acceptsOrdinarySiteWithoutManagedApplication() {
        Site basic = Site.of("example.org", "http://localhost:8080");
        WebServerConfiguration config = new WebServerConfiguration(
                new DomainName("example.org"), AcmeConfiguration.withoutEmail(),
                Upstream.parse("http://localhost:8080"), List.of(basic));
        JsonConfigurationRepository repository =
                new JsonConfigurationRepository(directory.resolve("webserver.json"));
        repository.save(config);
        assertTrue(repository.load().sites().get(0).managedApplication().isEmpty());
    }
}
