package com.aresstack.webserver.infrastructure.managed;

import com.aresstack.webserver.domain.ManagedApplication;
import com.aresstack.webserver.domain.ManagedEnvironmentVariable;
import com.aresstack.webserver.domain.RestartPolicy;
import com.aresstack.webserver.domain.Site;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Supervise optional child processes independently of the HTTP reverse proxy.
 * Serialize transitions on a single worker and keep Swing callbacks nonblocking.
 */
public final class ManagedProcessManager {
    public record ProcessStatus(String state, long pid, int failures, Integer lastExitCode,
                                Instant nextStart, Instant startedAt) {
        public static ProcessStatus stopped() {
            return new ProcessStatus("STOPPED", -1, 0, null, null, null);
        }
    }

    private final Path root;
    private final Consumer<String> log;
    private final ScheduledExecutorService worker =
            Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "managed-jar-lifecycle");
                thread.setDaemon(true);
                return thread;
            });
    private final Map<String, Service> services = new HashMap<>();
    private boolean active;

    public ManagedProcessManager(Path root, Consumer<String> log) {
        this.root = root.toAbsolutePath().normalize();
        this.log = log;
    }

    public synchronized void reconcile(List<Site> sites) {
        active = true;
        Map<String, ManagedApplication> wanted = new HashMap<>();
        for (Site site : sites) {
            site.managedApplication().ifPresent(app -> wanted.put(app.id(), app));
        }
        for (String id : new ArrayList<>(services.keySet())) {
            Service service = services.get(id);
            ManagedApplication next = wanted.get(id);
            if (next == null || !next.equals(service.config)) {
                stopService(service);
                services.remove(id);
            }
        }
        for (ManagedApplication app : wanted.values()) {
            if (!services.containsKey(app.id())) {
                Service service = new Service(app);
                services.put(app.id(), service);
                if (app.startAutomatically()) {
                    schedule(service, 0);
                }
            }
        }
    }

    public synchronized void stopAll() {
        active = false;
        for (Service service : services.values()) {
            stopService(service);
        }
        services.clear();
    }

    public synchronized void start(String id) {
        Service service = required(id);
        service.requested = true;
        service.failures = 0;
        schedule(service, 0);
    }

    public synchronized void stop(String id) {
        stopService(required(id));
    }

    public synchronized void restart(String id) {
        Service service = required(id);
        stopService(service);
        service.requested = true;
        service.failures = 0;
        schedule(service, 0);
    }

    public synchronized ProcessStatus status(String id) {
        Service service = services.get(id);
        if (service == null) {
            return ProcessStatus.stopped();
        }
        ProcessHandle handle = service.handle;
        return new ProcessStatus(service.state,
                handle != null && handle.isAlive() ? handle.pid() : -1,
                service.failures, service.lastExit, service.nextStart, service.startedAt);
    }

    private Service required(String id) {
        Service service = services.get(id);
        if (!active || service == null) {
            throw new IllegalStateException("Managed application is not active: " + id);
        }
        return service;
    }

    private void schedule(Service service, long delaySeconds) {
        cancelPending(service);
        if (!active || !service.requested) {
            return;
        }
        service.state = delaySeconds == 0 ? "STARTING" : "RESTART_SCHEDULED";
        service.nextStart = Instant.now().plusSeconds(delaySeconds);
        service.pending = worker.schedule(() -> launch(service), delaySeconds, TimeUnit.SECONDS);
    }

    private void launch(Service service) {
        synchronized (this) {
            if (!active || !service.requested || services.get(service.config.id()) != service) {
                return;
            }
            service.pending = null;
            service.nextStart = null;
            if (service.handle != null && service.handle.isAlive()) {
                service.state = "RUNNING";
                return;
            }
            try {
                ProcessHandle adopted = previouslyOwned(service.config);
                if (adopted != null) {
                    service.handle = adopted;
                    service.startedAt = adopted.info().startInstant().orElse(Instant.now());
                    service.state = "RUNNING";
                    observe(service, adopted, null);
                    log.accept("Adopted managed Java application " + service.config.id());
                    return;
                }
                ProcessBuilder builder = processBuilder(service.config);
                Process process = builder.start();
                service.handle = process.toHandle();
                service.startedAt = Instant.now();
                service.state = "RUNNING";
                saveIdentity(service.config, service.handle);
                observe(service, service.handle, process);
                log.accept("Started managed Java application " + service.config.id());
            } catch (RuntimeException | IOException e) {
                log.accept("Managed application " + service.config.id()
                        + " could not start: " + e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : " (" + e.getMessage() + ")"));
                failed(service, null);
            }
        }
    }

    private ProcessBuilder processBuilder(ManagedApplication app) throws IOException {
        Path jar = Path.of(app.jarPath()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(jar)) {
            throw new IOException("Application JAR does not exist: " + jar);
        }
        Path workingDirectory = app.workingDirectory().isBlank()
                ? jar.getParent() : Path.of(app.workingDirectory()).toAbsolutePath().normalize();
        if (!Files.isDirectory(workingDirectory)) {
            throw new IOException("Working directory does not exist: " + workingDirectory);
        }
        List<String> command = new ArrayList<>();
        command.add(app.javaExecutable());
        command.addAll(app.jvmArguments());
        command.add("-jar");
        command.add(jar.toString());
        command.addAll(app.applicationArguments());
        ProcessBuilder builder = new ProcessBuilder(command).directory(workingDirectory.toFile());
        for (Map.Entry<String, ManagedEnvironmentVariable> entry : app.environment().entrySet()) {
            ManagedEnvironmentVariable variable = entry.getValue();
            builder.environment().put(entry.getKey(),
                    variable.secret() ? WindowsDpapiSecrets.reveal(variable.value()) : variable.value());
        }
        Path logFile = root.resolve("logs").resolve("services").resolve(app.id() + ".log");
        Files.createDirectories(logFile.getParent());
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        return builder;
    }

    private void observe(Service service, ProcessHandle handle, Process ownedProcess) {
        handle.onExit().thenRun(() -> {
            Integer exitCode = null;
            if (ownedProcess != null) {
                try {
                    exitCode = ownedProcess.exitValue();
                } catch (IllegalThreadStateException ignored) {
                    // Preserve the missing exit code when the platform cannot provide it.
                }
            }
            Integer finalExitCode = exitCode;
            worker.execute(() -> {
                synchronized (ManagedProcessManager.this) {
                    if (services.get(service.config.id()) != service || service.handle != handle) {
                        return;
                    }
                    service.handle = null;
                    deleteIdentity(service.config);
                    failed(service, finalExitCode);
                }
            });
        });
    }

    private void failed(Service service, Integer exitCode) {
        service.lastExit = exitCode;
        if (!active || !service.requested || !service.config.restartAutomatically()) {
            service.state = "STOPPED";
            return;
        }
        RestartPolicy policy = service.config.restartPolicy();
        if (service.startedAt != null && Instant.now().isAfter(
                service.startedAt.plusSeconds(policy.resetAfterSeconds()))) {
            service.failures = 0;
        }
        service.startedAt = null;
        service.failures++;
        if (!policy.permits(service.failures)) {
            service.state = "FAILED";
            return;
        }
        schedule(service, policy.delayAfter(service.failures));
    }

    private void stopService(Service service) {
        service.requested = false;
        cancelPending(service);
        ProcessHandle handle = service.handle;
        service.handle = null;
        service.nextStart = null;
        service.state = "STOPPED";
        if (handle != null && handle.isAlive()) {
            handle.destroy();
            long timeout = service.config.stopTimeoutSeconds();
            try {
                handle.onExit().get(timeout, TimeUnit.SECONDS);
            } catch (Exception e) {
                if (handle.isAlive()) {
                    handle.destroyForcibly();
                }
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        deleteIdentity(service.config);
    }

    private void cancelPending(Service service) {
        if (service.pending != null) {
            service.pending.cancel(false);
            service.pending = null;
        }
    }

    private Path identityPath(ManagedApplication app) {
        return root.resolve("data").resolve("service-runtime").resolve(app.id() + ".txt");
    }

    private void saveIdentity(ManagedApplication app, ProcessHandle handle) {
        try {
            Path file = identityPath(app);
            Files.createDirectories(file.getParent());
            long started = handle.info().startInstant().orElse(Instant.now()).toEpochMilli();
            Files.writeString(file, handle.pid() + "\n" + started + "\n"
                    + Path.of(app.jarPath()).toAbsolutePath().normalize());
        } catch (IOException e) {
            log.accept("Could not persist managed process identity: " + app.id());
        }
    }

    private ProcessHandle previouslyOwned(ManagedApplication app) {
        Path file = identityPath(app);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            List<String> lines = Files.readAllLines(file);
            if (lines.size() != 3
                    || !lines.get(2).equals(Path.of(app.jarPath()).toAbsolutePath().normalize().toString())) {
                return null;
            }
            long pid = Long.parseLong(lines.get(0));
            long start = Long.parseLong(lines.get(1));
            Optional<ProcessHandle> handle = ProcessHandle.of(pid);
            if (handle.isEmpty() || !handle.get().isAlive()) {
                return null;
            }
            if (handle.get().info().startInstant().map(time -> time.toEpochMilli() != start).orElse(true)) {
                return null;
            }
            String jarPath = lines.get(2);
            boolean matchingJar = handle.get().info().arguments()
                    .map(args -> java.util.Arrays.asList(args).contains(jarPath)).orElse(false);
            return matchingJar ? handle.get() : null;
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    private void deleteIdentity(ManagedApplication app) {
        try {
            Files.deleteIfExists(identityPath(app));
        } catch (IOException e) {
            log.accept("Could not remove managed process identity: " + app.id());
        }
    }

    private static final class Service {
        private final ManagedApplication config;
        private ProcessHandle handle;
        private ScheduledFuture<?> pending;
        private boolean requested = true;
        private String state = "STOPPED";
        private Instant startedAt;
        private Instant nextStart;
        private int failures;
        private Integer lastExit;

        private Service(ManagedApplication config) {
            this.config = config;
        }
    }
}
