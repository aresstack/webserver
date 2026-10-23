package com.aresstack.webserver.launcher;

import com.aresstack.webserver.domain.AcmeConfiguration;
import com.aresstack.webserver.domain.DomainName;
import com.aresstack.webserver.domain.OAuthConfiguration;
import com.aresstack.webserver.domain.Site;
import com.aresstack.webserver.domain.Upstream;
import com.aresstack.webserver.domain.WebServerConfiguration;
import com.aresstack.webserver.infrastructure.configuration.JsonConfigurationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A configuration file that is still being written must not be mistaken for a
 * rejected change.
 *
 * <p>An external editor rewrites the file in place, so the first watch event can
 * arrive while it is empty or truncated. The watcher used to let that read failure
 * fall into its rollback handler, which saved the previously applied configuration
 * back over the edit the operator had just made - observed in production: setting a
 * managed application to {@code enabled: false} by hand was silently reverted to
 * {@code true} within seconds.
 */
class ConfigurationWatcherReadTest {

    @TempDir Path directory;

    @Test
    void readsAValidConfiguration() throws Exception {
        Path file = directory.resolve("webserver.json");
        JsonConfigurationRepository repository = new JsonConfigurationRepository(file);
        repository.save(configuration());

        Object loaded = readWhenSettled(repository);

        assertEquals(configuration(), loaded);
    }

    @Test
    void reportsNothingReadableInsteadOfFailingForATruncatedFile() throws Exception {
        Path file = directory.resolve("webserver.json");
        JsonConfigurationRepository repository = new JsonConfigurationRepository(file);
        repository.save(configuration());
        // What an in-place rewrite looks like in the instant between truncate and write.
        Files.writeString(file, "");

        assertNull(readWhenSettled(repository),
                "an unreadable file must be reported as 'not yet', never as a change "
                        + "to roll back");
    }

    @Test
    void reportsNothingReadableForHalfWrittenJson() throws Exception {
        Path file = directory.resolve("webserver.json");
        JsonConfigurationRepository repository = new JsonConfigurationRepository(file);
        repository.save(configuration());
        Files.writeString(file, "{\n  \"domain\": \"example.org\",\n  \"sites\": [");

        assertNull(readWhenSettled(repository));
    }

    private static Object readWhenSettled(JsonConfigurationRepository repository)
            throws Exception {
        Method method = WebServerMain.class.getDeclaredMethod(
                "readWhenSettled", JsonConfigurationRepository.class);
        method.setAccessible(true);
        try {
            return method.invoke(null, repository);
        } catch (InvocationTargetException failure) {
            throw new AssertionError("reading must not propagate a failure",
                    failure.getCause());
        }
    }

    private static WebServerConfiguration configuration() {
        Upstream upstream = Upstream.parse("http://127.0.0.1:8081");
        return new WebServerConfiguration(new DomainName("example.org"),
                AcmeConfiguration.withoutEmail(), upstream,
                List.of(Site.of("example.org", "http://127.0.0.1:8081")), 80, 443,
                OAuthConfiguration.disabled());
    }
}
