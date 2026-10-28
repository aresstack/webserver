package com.aresstack.webserver.infrastructure.managed;

import com.aresstack.webserver.domain.ManagedApplication;
import com.aresstack.webserver.domain.ManagedEnvironmentVariable;
import com.aresstack.webserver.domain.Site;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Keep encrypted secret payloads outside webserver.json.
 * Store only opaque references in published application configurations.
 */
public final class ManagedSecretStore {
    private static final String REFERENCE_PREFIX = "secret-ref:";
    private final Path root;

    public ManagedSecretStore(Path root) {
        this.root = root.toAbsolutePath().normalize().resolve("data").resolve("secrets");
    }

    public Site secure(Site site) {
        if (site.managedApplication().isEmpty()) {
            return site;
        }
        ManagedApplication app = site.managedApplication().orElseThrow();
        Map<String, ManagedEnvironmentVariable> prepared = new LinkedHashMap<>();
        for (Map.Entry<String, ManagedEnvironmentVariable> entry : app.environment().entrySet()) {
            ManagedEnvironmentVariable variable = entry.getValue();
            String value = variable.value();
            if (variable.secret() && !isReference(value)) {
                String encrypted = WindowsDpapiSecrets.isProtected(value)
                        ? value : WindowsDpapiSecrets.protect(value);
                value = save(app.id(), encrypted);
            }
            prepared.put(entry.getKey(),
                    new ManagedEnvironmentVariable(value, variable.secret(), variable.enabled()));
        }
        ManagedApplication protectedApp = app.withEnvironment(prepared);
        return site.withManagedApplication(protectedApp);
    }

    public String resolve(String applicationId, String reference) {
        if (!isReference(reference)) {
            throw new IllegalArgumentException("Managed secret has no secure reference");
        }
        String token = reference.substring(REFERENCE_PREFIX.length());
        UUID.fromString(token);
        Path file = root.resolve(applicationId).resolve(token + ".dpapi");
        try {
            String encrypted = Files.readString(file, StandardCharsets.US_ASCII).trim();
            return WindowsDpapiSecrets.reveal(encrypted);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read protected application credential", e);
        }
    }

    public static boolean isReference(String value) {
        return value != null && value.startsWith(REFERENCE_PREFIX);
    }

    private String save(String id, String ciphertext) {
        String token = UUID.randomUUID().toString();
        Path directory = root.resolve(id);
        try {
            Files.createDirectories(directory);
            Path temp = Files.createTempFile(directory, "secret-", ".tmp");
            Files.writeString(temp, ciphertext, StandardCharsets.US_ASCII);
            Files.move(temp, directory.resolve(token + ".dpapi"),
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot store encrypted application credential", e);
        }
        return REFERENCE_PREFIX + token;
    }
}
