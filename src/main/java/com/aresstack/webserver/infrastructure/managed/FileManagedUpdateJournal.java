package com.aresstack.webserver.infrastructure.managed;

import com.aresstack.webserver.application.ManagedApplicationRuntime;
import com.aresstack.webserver.application.ManagedUpdateJournal;
import com.aresstack.webserver.domain.ManagedApplication;
import com.aresstack.webserver.domain.Site;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * Journal a managed JAR replacement before touching the process.
 * Recover the old persisted configuration if power is lost before commit.
 */
public final class FileManagedUpdateJournal implements ManagedUpdateJournal {
    private static final String STATE_PREPARING = "PREPARING";
    private static final String STATE_COMMITTED = "COMMITTED";
    private static final String METADATA_FILE = "transaction.properties";
    private static final String BACKUP_FILE = "previous-webserver.json";
    private final Path configFile;
    private final Path root;
    private final ManagedApplicationRuntime runtime;

    public FileManagedUpdateJournal(Path applicationRoot, ManagedApplicationRuntime runtime) {
        Path normalized = applicationRoot.toAbsolutePath().normalize();
        this.configFile = normalized.resolve("config").resolve("webserver.json");
        this.root = normalized.resolve("data").resolve("service-updates");
        this.runtime = runtime;
    }

    @Override
    public synchronized void prepare(Site previous, Site candidate) {
        ManagedApplication app = candidate.managedApplication().orElseThrow();
        Path directory = transactionDirectory(app.id());
        if (Files.exists(directory.resolve(METADATA_FILE))) {
            throw new IllegalStateException("An application update is already pending: " + app.id());
        }
        try {
            Files.createDirectories(directory);
            Files.copy(configFile, directory.resolve(BACKUP_FILE),
                    StandardCopyOption.REPLACE_EXISTING);
            Properties properties = new Properties();
            properties.setProperty("state", STATE_PREPARING);
            properties.setProperty("id", app.id());
            properties.setProperty("candidateJar", app.jarPath());
            properties.setProperty("stopTimeoutSeconds", Integer.toString(app.stopTimeoutSeconds()));
            properties.setProperty("forceStopTimeoutSeconds",
                    Integer.toString(app.forceStopTimeoutSeconds()));
            store(directory, properties);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot prepare durable application update", e);
        }
    }

    @Override
    public synchronized void commit(Site candidate) {
        Path directory = transactionDirectory(candidate.managedApplication().orElseThrow().id());
        Properties state = load(directory);
        state.setProperty("state", STATE_COMMITTED);
        store(directory, state);
        cleanup(directory);
    }

    @Override
    public synchronized void rollback(Site candidate) {
        Path directory = transactionDirectory(candidate.managedApplication().orElseThrow().id());
        if (Files.exists(directory.resolve(METADATA_FILE))) {
            restore(directory);
        }
    }

    @Override
    public synchronized void recover() {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> directories = Files.list(root)) {
            directories.filter(Files::isDirectory).forEach(directory -> {
                if (Files.isRegularFile(directory.resolve(METADATA_FILE))) {
                    restore(directory);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot recover interrupted managed updates", e);
        }
    }

    private void restore(Path directory) {
        Properties state = load(directory);
        if (STATE_COMMITTED.equals(state.getProperty("state"))) {
            cleanup(directory);
            return;
        }
        if (!STATE_PREPARING.equals(state.getProperty("state"))) {
            throw new IllegalStateException("Unknown application update state: "
                    + state.getProperty("state"));
        }
        runtime.terminateInterruptedUpdate(state.getProperty("id"),
                state.getProperty("candidateJar"),
                Integer.parseInt(state.getProperty("stopTimeoutSeconds")),
                Integer.parseInt(state.getProperty("forceStopTimeoutSeconds")));
        Path previous = directory.resolve(BACKUP_FILE);
        if (!Files.isRegularFile(previous)) {
            throw new IllegalStateException("Previous configuration backup is missing: " + previous);
        }
        try {
            Path staged = Files.createTempFile(configFile.getParent(), "webserver-rollback-", ".tmp");
            Files.copy(previous, staged, StandardCopyOption.REPLACE_EXISTING);
            Files.move(staged, configFile, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            cleanup(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot restore previous publication settings", e);
        }
    }

    private Path transactionDirectory(String id) {
        if (!id.matches("[a-zA-Z0-9-]{1,64}")) {
            throw new IllegalArgumentException("Invalid managed application id");
        }
        return root.resolve(id);
    }

    private Properties load(Path directory) {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(directory.resolve(METADATA_FILE))) {
            properties.load(input);
            return properties;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read managed application update journal", e);
        }
    }

    private void store(Path directory, Properties state) {
        try {
            Path staging = Files.createTempFile(directory, "transaction-", ".tmp");
            try (OutputStream output = Files.newOutputStream(staging)) {
                state.store(output, "Managed JAR update transaction");
            }
            Files.move(staging, directory.resolve(METADATA_FILE),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot persist managed application update journal", e);
        }
    }

    private void cleanup(Path directory) {
        try {
            Files.deleteIfExists(directory.resolve(METADATA_FILE));
            Files.deleteIfExists(directory.resolve(BACKUP_FILE));
            Files.deleteIfExists(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot finish managed application update transaction", e);
        }
    }
}
