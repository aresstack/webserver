package com.aresstack.webserver.infrastructure.managed;

import com.aresstack.webserver.domain.*;
import com.aresstack.webserver.infrastructure.configuration.JsonConfigurationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class ManagedSecretStoreTest {
    @TempDir Path root;

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void putsOnlyOpaqueSecretReferencesIntoWebserverConfiguration() throws Exception {
        String secret = "token-should-not-be-in-json";
        ManagedApplication app = new ManagedApplication("service-1",
                root.resolve("sample.jar").toString(), "java", "", List.of(), List.of(),
                Map.of("API_KEY", new ManagedEnvironmentVariable(secret, true)),
                true, true, RestartPolicy.defaults(), 10);
        Site secured = new ManagedSecretStore(root).secure(Site.of(
                "example.org", "http://localhost:8101").withManagedApplication(app));
        String reference = secured.managedApplication().orElseThrow()
                .environment().get("API_KEY").value();
        assertTrue(ManagedSecretStore.isReference(reference));
        assertEquals(secret, new ManagedSecretStore(root).resolve("service-1", reference));

        Path configFile = root.resolve("config/webserver.json");
        new JsonConfigurationRepository(configFile).save(new WebServerConfiguration(
                new DomainName("example.org"), AcmeConfiguration.withoutEmail(),
                Upstream.parse("http://localhost:8101"), List.of(secured)));
        String json = Files.readString(configFile);
        assertTrue(json.contains(reference));
        assertFalse(json.contains(secret));
        assertFalse(json.contains("dpapi:"));
    }
}
