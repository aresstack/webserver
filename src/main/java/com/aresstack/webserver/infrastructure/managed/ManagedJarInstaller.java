package com.aresstack.webserver.infrastructure.managed;

import com.aresstack.webserver.domain.ManagedApplication;
import com.aresstack.webserver.domain.Site;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Kopiert das gewaehlte JAR in den Ordner des Dienstes.
 *
 * <p>Ein Dienst, ein Ordner, eine Datei: {@code services/<id>/application.jar}. Die
 * Kopie wird ueberschrieben. Vorher legte jedes Speichern einen neuen
 * {@code versions/<UUID>}-Ordner an und nichts raeumte sie je weg - nach ein paar
 * Anlaeufen stand im Service-Verzeichnis ein Haufen unlesbar benannter Ordner, von
 * denen genau einer benutzt wurde.
 *
 * <p>Kopiert wird weiterhin, damit ein Neubau im Quellordner den laufenden Dienst
 * nicht beeinflusst. Weil die Zieldatei ueberschrieben wird, muss der Aufrufer die
 * Anwendung vorher stoppen - unter Windows haelt ein laufender Prozess sein JAR
 * gesperrt.
 */
public final class ManagedJarInstaller {
    private final Path root;

    public ManagedJarInstaller(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path applicationDataDirectory(ManagedApplication app) {
        return root.resolve("services").resolve(app.id()).resolve("data");
    }

    /** Der Platz, an dem das JAR dieses Dienstes liegt. */
    public Path installedJar(ManagedApplication app) {
        return root.resolve("services").resolve(app.id()).resolve("application.jar");
    }

    public void ensureApplicationDataDirectory(ManagedApplication app) {
        try {
            Files.createDirectories(applicationDataDirectory(app));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot prepare persistent application data", e);
        }
    }

    public Site install(Site site) {
        if (site.managedApplication().isEmpty()) {
            return site;
        }
        ManagedApplication app = site.managedApplication().get();
        Path source = Path.of(app.jarPath()).toAbsolutePath().normalize();
        Path installed = installedJar(app);
        ensureApplicationDataDirectory(app);
        if (source.equals(installed)) {
            return site;
        }
        if (!Files.isRegularFile(source)
                || !source.getFileName().toString().toLowerCase().endsWith(".jar")) {
            throw new IllegalArgumentException("Choose an existing JAR file: " + source);
        }
        try {
            Files.createDirectories(installed.getParent());
            Files.copy(source, installed, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot install application JAR", e);
        }
        return site.withManagedApplication(app.withJarPath(installed.toString()));
    }
}
