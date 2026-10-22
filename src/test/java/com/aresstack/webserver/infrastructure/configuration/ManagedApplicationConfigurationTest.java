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
    void persistsDisabledFlagAcrossSaveAndLoad() {
        ManagedApplication disabled = ManagedApplication.defaults(
                "service-2", "/tmp/example.jar").withEnabled(false);
        Site site = Site.of("example.org", "http://localhost:8080")
                .withManagedApplication(disabled);
        WebServerConfiguration config = new WebServerConfiguration(
                new DomainName("example.org"), AcmeConfiguration.withoutEmail(),
                Upstream.parse("http://localhost:8080"), List.of(site));
        JsonConfigurationRepository repository =
                new JsonConfigurationRepository(directory.resolve("webserver.json"));
        repository.save(config);

        ManagedApplication loaded = repository.load().sites().get(0)
                .managedApplication().orElseThrow();
        assertFalse(loaded.isEnabled(),
                "A disabled application must stay disabled after reload");
    }

    @Test
    void missingEnabledFieldInOlderConfigurationsMeansEnabled() {
        ManagedApplication legacy = new ManagedApplication("service-3", "/tmp/example.jar",
                "java", "", List.of(), List.of(), Map.of(), true, true,
                RestartPolicy.defaults(), ReadinessPolicy.defaults(), 10, 10, null);
        assertTrue(legacy.isEnabled());
    }

    @Test
    void unknownJsonFieldsMustNotPreventLoading() throws Exception {
        Site site = Site.of("example.org", "http://localhost:8080")
                .withManagedApplication(ManagedApplication.defaults("service-4", "/tmp/example.jar"));
        WebServerConfiguration config = new WebServerConfiguration(
                new DomainName("example.org"), AcmeConfiguration.withoutEmail(),
                Upstream.parse("http://localhost:8080"), List.of(site));
        java.nio.file.Path file = directory.resolve("webserver.json");
        JsonConfigurationRepository repository = new JsonConfigurationRepository(file);
        repository.save(config);

        // Felder einer neueren Version (oder von Hand ergänzt) simulieren.
        String json = java.nio.file.Files.readString(file)
                .replaceFirst("\\{", "{\n  \"futureTopLevelField\" : 42,")
                .replaceFirst("\"managedApplication\" : \\{",
                        "\"managedApplication\" : {\n        \"newLifecycleOption\" : true,");
        java.nio.file.Files.writeString(file, json);

        assertEquals("example.org", repository.load().sites().get(0).host().value());
    }

    @Test
    void legacyManagedApplicationWithoutReadinessAndForceStopLoads() {
        // Simuliert Jackson-Bindung einer Config von vor Einführung beider
        // Felder: readinessPolicy fehlt (null), forceStopTimeoutSeconds fehlt (0).
        ManagedApplication legacy = new ManagedApplication("service-5", "/tmp/example.jar",
                "java", "", List.of(), List.of(), Map.of(), true, true,
                RestartPolicy.defaults(), null, 10, 0, null);
        assertEquals(ReadinessPolicy.defaults(), legacy.readinessPolicy());
        assertEquals(10, legacy.forceStopTimeoutSeconds());
        assertTrue(legacy.isEnabled());
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
