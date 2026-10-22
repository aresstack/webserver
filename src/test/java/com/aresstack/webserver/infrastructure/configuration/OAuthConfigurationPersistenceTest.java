package com.aresstack.webserver.infrastructure.configuration;

import com.aresstack.webserver.domain.AcmeConfiguration;
import com.aresstack.webserver.domain.DomainName;
import com.aresstack.webserver.domain.OAuthClientConfiguration;
import com.aresstack.webserver.domain.OAuthConfiguration;
import com.aresstack.webserver.domain.Site;
import com.aresstack.webserver.domain.Upstream;
import com.aresstack.webserver.domain.WebServerConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OAuthConfigurationPersistenceTest {

    @TempDir Path directory;

    private JsonConfigurationRepository repository() {
        return new JsonConfigurationRepository(directory.resolve("webserver.json"));
    }

    private static WebServerConfiguration base(OAuthConfiguration oauth) {
        return new WebServerConfiguration(new DomainName("example.org"),
                AcmeConfiguration.withoutEmail(), Upstream.parse("http://localhost:8080"),
                List.of(Site.of("example.org", "http://localhost:8080")), 80, 443, oauth);
    }

    @Test
    void persistsOAuthServiceAndClientsAcrossSaveAndLoad() {
        OAuthConfiguration oauth = new OAuthConfiguration(true, "https://auth.example.org",
                List.of(new OAuthClientConfiguration("chatgpt", "secret-value",
                        List.of("https://chatgpt.com", "https://chat.openai.com"))));
        JsonConfigurationRepository repository = repository();
        repository.save(base(oauth));

        OAuthConfiguration loaded = repository.load().oauth();
        assertTrue(loaded.enabled());
        assertEquals("https://auth.example.org", loaded.issuer());
        assertEquals(1, loaded.clients().size());
        assertEquals("chatgpt", loaded.clients().get(0).clientId());
        assertEquals(List.of("https://chatgpt.com", "https://chat.openai.com"),
                loaded.clients().get(0).redirectUris());
    }

    @Test
    void missingOAuthSectionMeansDisabledAndIsNotWritten() throws Exception {
        JsonConfigurationRepository repository = repository();
        repository.save(base(OAuthConfiguration.disabled()));

        assertFalse(repository.load().oauth().enabled());
        String json = Files.readString(directory.resolve("webserver.json"));
        assertFalse(json.contains("\"oauth\""),
                "a disabled empty oauth section must not clutter the file");
    }

    @Test
    void disabledServiceWithClientsKeepsItsConfiguration() {
        OAuthConfiguration oauth = new OAuthConfiguration(false, "https://auth.example.org",
                List.of(new OAuthClientConfiguration("chatgpt", "secret-value",
                        List.of("https://chatgpt.com"))));
        JsonConfigurationRepository repository = repository();
        repository.save(base(oauth));

        OAuthConfiguration loaded = repository.load().oauth();
        assertFalse(loaded.enabled());
        assertEquals(1, loaded.clients().size());
    }
}
