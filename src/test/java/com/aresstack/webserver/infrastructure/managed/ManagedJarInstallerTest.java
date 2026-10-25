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
        assertTrue(Files.isDirectory(directory.resolve("services/app-1/data")),
                "Persist application data independently of installed JAR versions");
        assertEquals(copy, Path.of(installer.install(installed)
                .managedApplication().orElseThrow().jarPath()));
    }

    @Test
    void usesOneFixedFileNamePerServiceAndOverwritesIt() throws Exception {
        // Vorher legte jedes Speichern einen neuen versions/<UUID>-Ordner an und
        // nichts raeumte sie weg; nach ein paar Anlaeufen stand im Service-Ordner ein
        // Haufen unlesbar benannter Verzeichnisse, von denen eines benutzt wurde.
        ManagedJarInstaller installer = new ManagedJarInstaller(directory);
        Path first = directory.resolve("build-1.jar");
        Files.write(first, new byte[]{1, 1, 1});
        Site managed = managedSite(first);

        Site installed = installer.install(managed);
        Path copy = Path.of(installed.managedApplication().orElseThrow().jarPath());
        assertEquals(directory.resolve("services").resolve("argos").resolve("application.jar"),
                copy);

        // Ein anderes Quell-JAR ersetzt die Datei, statt daneben zu liegen.
        Path second = directory.resolve("build-2.jar");
        Files.write(second, new byte[]{2, 2, 2, 2});
        Path replaced = Path.of(installer.install(managedSite(second))
                .managedApplication().orElseThrow().jarPath());

        assertEquals(copy, replaced);
        assertArrayEquals(Files.readAllBytes(second), Files.readAllBytes(replaced));
        try (var entries = Files.list(directory.resolve("services").resolve("argos"))) {
            assertEquals(List.of("application.jar", "data"),
                    entries.map(path -> path.getFileName().toString()).sorted().toList(),
                    "ein Dienst, ein Ordner, eine Datei - keine Versionsordner");
        }
    }

    private Site managedSite(Path jar) {
        Site basic = Site.of("argos.aresstack.de", "http://localhost:8085");
        return new Site(basic.host(), basic.upstream(), List.of(), true,
                Optional.of(ManagedApplication.defaults("argos", jar.toString())));
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
