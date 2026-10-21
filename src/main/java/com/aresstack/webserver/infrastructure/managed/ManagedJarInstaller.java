package com.aresstack.webserver.infrastructure.managed;

import com.aresstack.webserver.domain.ManagedApplication;
import com.aresstack.webserver.domain.Site;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/** Install a selected local JAR into immutable service-specific version folders. */
public final class ManagedJarInstaller {
    private final Path root;

    public ManagedJarInstaller(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path applicationDataDirectory(ManagedApplication app) {
        return root.resolve("services").resolve(app.id()).resolve("data");
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
        Path serviceRoot = root.resolve("services").resolve(app.id());
        Path versionRoot = serviceRoot.resolve("versions");
        ensureApplicationDataDirectory(app);
        if (source.startsWith(versionRoot)) {
            return site;
        }
        if (!Files.isRegularFile(source) || !source.getFileName().toString().toLowerCase().endsWith(".jar")) {
            throw new IllegalArgumentException("Choose an existing JAR file: " + source);
        }
        Path version = versionRoot.resolve(UUID.randomUUID().toString());
        Path installed = version.resolve("application.jar");
        try {
            Files.createDirectories(version);
            Files.copy(source, installed, StandardCopyOption.COPY_ATTRIBUTES);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot install application JAR", e);
        }
        return site.withManagedApplication(app.withJarPath(installed.toString()));
    }
}
