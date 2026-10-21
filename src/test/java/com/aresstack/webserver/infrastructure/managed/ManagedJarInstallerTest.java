package com.aresstack.webserver.infrastructure.managed;

import com.aresstack.webserver.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class ManagedJarInstallerTest {
    @TempDir Path directory;

    @Test
    void installsFileOnlyWhenSiteHasManagedApplication() throws Exception {
        ManagedJarInstaller installer = new ManagedJarInstaller(directory);
        Site existing = Site.of("example.org", "http://localhost:8080");
        assertEquals(existing, installer.install(existing));

        Path jar = directory.resolve("external.jar");
        Files.write(jar, new byte[]{1, 2, 3});
        Site managed = new Site(existing.host(), existing.upstream(),
                List.of(), true, Optional.of(ManagedApplication.defaults("app-1", jar.toString())));
        Site installed = installer.install(managed);
        Path copy = Path.of(installed.managedApplication().orElseThrow().jarPath());
        assertTrue(Files.exists(copy));
        assertArrayEquals(Files.readAllBytes(jar), Files.readAllBytes(copy));
        assertTrue(copy.startsWith(directory.resolve("services").resolve("app-1")));
        assertEquals(copy, Path.of(installer.install(installed)
                .managedApplication().orElseThrow().jarPath()));
    }

    @Test
    void rejectsMissingJar() {
        ManagedJarInstaller installer = new ManagedJarInstaller(directory);
        Site basic = Site.of("example.org", "http://localhost:8080");
        Site managed = new Site(basic.host(), basic.upstream(), List.of(), true,
                Optional.of(ManagedApplication.defaults("app-1",
                        directory.resolve("missing.jar").toString())));
        assertThrows(IllegalArgumentException.class, () -> installer.install(managed));
    }
}
