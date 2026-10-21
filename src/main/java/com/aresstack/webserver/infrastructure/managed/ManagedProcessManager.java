package com.aresstack.webserver.infrastructure.managed;

import com.aresstack.webserver.application.ManagedApplicationRuntime;
import com.aresstack.webserver.domain.ManagedApplication;
import com.aresstack.webserver.domain.ReadinessPolicy;
import com.aresstack.webserver.domain.Upstream;
import com.aresstack.webserver.domain.ManagedEnvironmentVariable;
import com.aresstack.webserver.domain.ManagedProcessStatus;
import com.aresstack.webserver.domain.RestartPolicy;
import com.aresstack.webserver.domain.Site;

import java.io.IOException;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Keep all mutable lifecycle state on one worker.
 * Publish immutable snapshots so UI reads never wait for a child process to stop.
 */
public final class ManagedProcessManager implements ManagedApplicationRuntime {
    private final Path root;
    private final Consumer<String> log;
    private final ManagedSecretStore secrets;
    private final IdentityWriter identityWriter;
    private final ExitWaiter exitWaiter;

    @FunctionalInterface
    interface ExitWaiter {
        boolean await(ProcessHandle handle, long seconds);
    }

    @FunctionalInterface
    interface IdentityWriter {
        void write(Path file, String content) throws IOException;
    }
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "managed-jar-lifecycle");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, Service> services = new HashMap<>();
    private final Map<String, ManagedProcessStatus> snapshots = new ConcurrentHashMap<>();
    private boolean active;

    public ManagedProcessManager(Path root, Consumer<String> log) {
        this(root, log, ManagedProcessManager::writeIdentityAtomically,
                ManagedProcessManager::awaitExitReal);
    }

    ManagedProcessManager(Path root, Consumer<String> log, IdentityWriter identityWriter) {
        this(root, log, identityWriter, ManagedProcessManager::awaitExitReal);
    }

    ManagedProcessManager(Path root, Consumer<String> log,
                          IdentityWriter identityWriter, ExitWaiter exitWaiter) {
        this.root = root.toAbsolutePath().normalize();
        this.log = log;
        this.secrets = new ManagedSecretStore(root);
        this.identityWriter = identityWriter;
        this.exitWaiter = exitWaiter;
    }

    @Override
    public void reconcile(List<Site> sites) {
        Map<String, ManagedApplication> wanted = new HashMap<>();
        for (Site site : List.copyOf(sites)) {
            site.managedApplication().ifPresent(app -> wanted.put(app.id(), app));
        }
        executeAndAwait(() -> reconcileOnWorker(wanted));
    }

    @Override
    public void stageUpdate(List<Site> sites, String previousId, Upstream backend,
                            ReadinessPolicy readiness) {
        Map<String, ManagedApplication> wanted = new HashMap<>();
        for (Site site : List.copyOf(sites)) {
            site.managedApplication().ifPresent(app -> wanted.put(app.id(), app));
        }
        executeAndAwait(() -> {
            Service previous = services.get(previousId);
            if (previous != null) {
                stopService(previous);
                services.remove(previousId);
                snapshots.remove(previousId);
            }
            if (isPortOccupied(backend, readiness.connectionTimeoutMillis())) {
                throw new IllegalStateException("Backend port " + backend.port()
                        + " is still occupied after the previous application stopped");
            }
            reconcileOnWorker(wanted);
        });
    }

    @Override
    public void terminateInterruptedUpdate(String applicationId, String candidateJar,
                                           int gracefulSeconds, int forcedSeconds) {
        executeAndAwait(() -> terminateCandidateOnWorker(
                applicationId, candidateJar, gracefulSeconds, forcedSeconds));
    }

    private void terminateCandidateOnWorker(String id, String jar, int graceful, int forced) {
        Path expectedJar = Path.of(jar).toAbsolutePath().normalize();
        Service activeService = services.get(id);
        if (activeService != null && Path.of(activeService.config.jarPath())
                .toAbsolutePath().normalize().equals(expectedJar)) {
            stopService(activeService);
            services.remove(id);
            snapshots.remove(id);
            return;
        }
        Path file = root.resolve("data").resolve("service-runtime").resolve(id + ".txt");
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(file);
            if (lines.size() != 3) {
                throw new IllegalStateException("Invalid registered process identity for " + id);
            }
            if (!Path.of(lines.get(2)).toAbsolutePath().normalize().equals(expectedJar)) {
                return;
            }
            long pid = Long.parseLong(lines.get(0));
            long recordedStart = Long.parseLong(lines.get(1));
            Optional<ProcessHandle> possible = ProcessHandle.of(pid);
            if (possible.isEmpty() || !possible.get().isAlive()) {
                Files.deleteIfExists(file);
                return;
            }
            ProcessHandle handle = possible.get();
            long actualStart = handle.info().startInstant()
                    .orElseThrow(() -> new IllegalStateException("Cannot verify orphan start time"))
                    .toEpochMilli();
            if (actualStart != recordedStart) {
                Files.deleteIfExists(file);
                return;
            }
            if (handle.info().arguments().filter(args -> Arrays.asList(args)
                    .contains(expectedJar.toString())).isEmpty()) {
                throw new IllegalStateException("Cannot safely identify interrupted update PID "
                        + pid + "; stop it manually before starting AresStack again");
            }
            handle.destroy();
            if (!awaitExit(handle, graceful)) {
                handle.destroyForcibly();
                if (!awaitExit(handle, forced)) {
                    throw new IllegalStateException("Interrupted update process still running: " + pid);
                }
            }
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot recover interrupted update process", e);
        }
    }

    private boolean isPortOccupied(Upstream backend, int timeoutMillis) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(backend.host(), backend.port()), timeoutMillis);
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    public void stopAll() {
        executeAndAwait(() -> {
            active = false;
            for (Service service : new ArrayList<>(services.values())) {
                stopService(service);
            }
            services.clear();
        });
    }

    public void start(String id) {
        executeAndAwait(() -> {
            Service service = required(id);
            service.requested = true;
            service.failures = 0;
            schedule(service, 0);
        });
    }

    public void stop(String id) {
        executeAndAwait(() -> stopService(required(id)));
    }

    public void restart(String id) {
        executeAndAwait(() -> {
            Service service = required(id);
            stopService(service);
            service.requested = true;
            service.failures = 0;
            schedule(service, 0);
        });
    }

    public ManagedProcessStatus status(String id) {
        return snapshots.getOrDefault(id, ManagedProcessStatus.stopped());
    }

    private void reconcileOnWorker(Map<String, ManagedApplication> wanted) {
        active = true;
        for (String id : new ArrayList<>(services.keySet())) {
            Service existing = services.get(id);
            ManagedApplication next = wanted.get(id);
            if (next == null || !next.equals(existing.config)) {
                stopService(existing);
                services.remove(id);
                snapshots.remove(id);
            }
        }
        for (ManagedApplication config : wanted.values()) {
            if (!services.containsKey(config.id())) {
                Service service = new Service(config);
                services.put(config.id(), service);
                publish(service);
                if (config.startAutomatically()) {
                    schedule(service, 0);
                }
            }
        }
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
        publish(service);
        service.pending = worker.schedule(() -> launch(service), delaySeconds, TimeUnit.SECONDS);
    }

    private void launch(Service service) {
        if (!active || !service.requested || services.get(service.config.id()) != service) {
            return;
        }
        service.pending = null;
        service.nextStart = null;
        if (service.handle != null && service.handle.isAlive()) {
            service.state = "RUNNING";
            publish(service);
            return;
        }
        boolean processStarted = false;
        boolean identityReserved = false;
        try {
            ProcessHandle adopted = previouslyOwned(service.config);
            if (adopted != null) {
                service.handle = adopted;
                service.startedAt = adopted.info().startInstant().orElse(Instant.now());
                service.state = "RUNNING";
                publish(service);
                observe(service, adopted, null);
                log.accept("Reconnected managed application " + service.config.id());
                return;
            }
            ProcessBuilder builder = processBuilder(service.config);
            reserveIdentity(service.config);
            identityReserved = true;
            Process process = builder.start();
            processStarted = true;
            service.handle = process.toHandle();
            service.startedAt = Instant.now();
            // Register exit monitoring BEFORE persisting identity; a storage failure
            // must never leave a live child without an exit observer.
            observe(service, service.handle, process);
            saveIdentity(service.config, service.handle);
            service.state = "RUNNING";
            publish(service);
            log.accept("Started managed application " + service.config.id());
        } catch (RuntimeException | IOException e) {
            log.accept("Managed application " + service.config.id() + " could not start: "
                    + e.getClass().getSimpleName() + " (" + e.getMessage() + ")");
            if (processStarted) {
                terminateUnregistered(service);
                return;
            }
            if (identityReserved) {
                deleteIdentity(service.config);
            }
            // A stale or ambiguous identity is deliberately NOT removed here.
            // Without positive ownership proof another process must not start.
            if (Files.exists(identityPath(service.config))) {
                service.state = "OWNERSHIP_UNVERIFIED";
                publish(service);
            } else {
                failed(service, null);
            }
        }
    }

    private void terminateUnregistered(Service service) {
        ProcessHandle handle = service.handle;
        if (handle == null) {
            service.state = "OWNERSHIP_UNVERIFIED";
            publish(service);
            return;
        }
        try {
            handle.destroy();
            if (!awaitExit(handle, service.config.stopTimeoutSeconds())) {
                handle.destroyForcibly();
                if (!awaitExit(handle, service.config.forceStopTimeoutSeconds())) {
                    service.state = "OWNERSHIP_UNVERIFIED";
                    publish(service);
                    log.accept("Cannot confirm termination of unregistered process "
                            + handle.pid() + "; automatic relaunch suspended");
                    return;
                }
            }
            service.handle = null;
            deleteIdentity(service.config);
            failed(service, null);
        } catch (RuntimeException e) {
            service.state = "OWNERSHIP_UNVERIFIED";
            publish(service);
            log.accept("Could not terminate unregistered process " + handle.pid()
                    + "; automatic relaunch suspended");
        }
    }

    private void reserveIdentity(ManagedApplication app) throws IOException {
        Path file = identityPath(app);
        Files.createDirectories(file.getParent());
        // Write-ahead marker protects against an AresStack crash in the narrow
        // window between process creation and recording its actual PID.
        Files.writeString(file, "PENDING\n" + app.jarPath(), StandardOpenOption.CREATE_NEW);
    }

    private ProcessBuilder processBuilder(ManagedApplication app) throws IOException {
        Path jar = Path.of(app.jarPath()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(jar)) {
            throw new IOException("Managed JAR missing: " + jar);
        }
        Path workingDirectory = app.workingDirectory().isBlank()
                ? root.resolve("services").resolve(app.id()).resolve("data")
                : Path.of(app.workingDirectory()).toAbsolutePath().normalize();
        if (app.workingDirectory().isBlank()) {
            Files.createDirectories(workingDirectory);
        }
        if (!Files.isDirectory(workingDirectory)) {
            throw new IOException("Working directory missing: " + workingDirectory);
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
            String value = variable.secret() ? secrets.resolve(app.id(), variable.value()) : variable.value();
            builder.environment().put(entry.getKey(), value);
        }
        Path logFile = root.resolve("logs").resolve("services").resolve(app.id() + ".log");
        Files.createDirectories(logFile.getParent());
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        return builder;
    }

    private void observe(Service service, ProcessHandle handle, Process ownedProcess) {
        handle.onExit().thenRun(() -> worker.execute(() -> {
            if (services.get(service.config.id()) != service || service.handle != handle) {
                return;
            }
            Integer exit = ownedProcess == null ? null : ownedProcess.exitValue();
            service.handle = null;
            deleteIdentity(service.config);
            failed(service, exit);
        }));
    }

    private void failed(Service service, Integer exitCode) {
        service.lastExit = exitCode;
        if (!active || !service.requested || !service.config.restartAutomatically()) {
            service.state = "STOPPED";
            publish(service);
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
            publish(service);
            return;
        }
        schedule(service, policy.delayAfter(service.failures));
    }

    private void stopService(Service service) {
        service.requested = false;
        cancelPending(service);
        ProcessHandle handle = service.handle;
        service.nextStart = null;
        if (handle != null && handle.isAlive()) {
            service.state = "STOPPING";
            publish(service);
            handle.destroy();
            if (!awaitExit(handle, service.config.stopTimeoutSeconds())) {
                handle.destroyForcibly();
                if (!awaitExit(handle, service.config.forceStopTimeoutSeconds())) {
                    service.state = "STOP_FAILED";
                    publish(service);
                    throw new IllegalStateException("Process " + handle.pid()
                            + " could not be terminated; refusing to start a replacement");
                }
            }
        }
        service.handle = null;
        service.state = "STOPPED";
        service.startedAt = null;
        publish(service);
        deleteIdentity(service.config);
    }

    private boolean awaitExit(ProcessHandle handle, long seconds) {
        return exitWaiter.await(handle, seconds);
    }

    private static boolean awaitExitReal(ProcessHandle handle, long seconds) {
        if (!handle.isAlive()) {
            return true;
        }
        try {
            handle.onExit().get(seconds, TimeUnit.SECONDS);
            return !handle.isAlive();
        } catch (TimeoutException e) {
            return !handle.isAlive();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while stopping managed process", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Cannot confirm managed process termination", e);
        }
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

    private void saveIdentity(ManagedApplication app, ProcessHandle handle) throws IOException {
        Path file = identityPath(app);
        Instant start = handle.info().startInstant()
                .orElseThrow(() -> new IllegalStateException("Cannot determine child process start time"));
        identityWriter.write(file, handle.pid() + "\n" + start.toEpochMilli() + "\n"
                + Path.of(app.jarPath()).toAbsolutePath().normalize());
    }

    static void writeIdentityAtomically(Path file, String content) throws IOException {
        Path tmp = Files.createTempFile(file.getParent(), "identity-", ".tmp");
        try {
            Files.writeString(tmp, content);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private ProcessHandle previouslyOwned(ManagedApplication app) {
        Path file = identityPath(app);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            List<String> lines = Files.readAllLines(file);
            if (lines.size() != 3) {
                throw new IllegalStateException("Invalid saved process identity for " + app.id());
            }
            long pid = Long.parseLong(lines.get(0));
            long startedAt = Long.parseLong(lines.get(1));
            Optional<ProcessHandle> possible = ProcessHandle.of(pid);
            if (possible.isEmpty() || !possible.get().isAlive()) {
                Files.deleteIfExists(file);
                return null;
            }
            ProcessHandle handle = possible.get();
            Instant start = handle.info().startInstant().orElseThrow(() ->
                    new IllegalStateException("Cannot verify start time of live managed process"));
            if (start.toEpochMilli() != startedAt) {
                Files.deleteIfExists(file);
                return null;
            }
            String installedJar = Path.of(app.jarPath()).toAbsolutePath().normalize().toString();
            Optional<String[]> args = handle.info().arguments();
            if (!lines.get(2).equals(installedJar) || args.isEmpty()
                    || !Arrays.asList(args.get()).contains(installedJar)) {
                throw new IllegalStateException("Live process " + pid
                        + " cannot be verified against managed JAR " + installedJar
                        + "; refusing to start another process");
            }
            return handle;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read managed process identity", e);
        }
    }

    private void deleteIdentity(ManagedApplication app) {
        try {
            Files.deleteIfExists(identityPath(app));
        } catch (IOException e) {
            log.accept("Could not remove process identity for " + app.id());
        }
    }

    private void publish(Service service) {
        ProcessHandle handle = service.handle;
        long pid = handle != null && handle.isAlive() ? handle.pid() : -1;
        snapshots.put(service.config.id(), new ManagedProcessStatus(service.state,
                pid, service.failures, service.lastExit, service.nextStart, service.startedAt,
                service.config.jarPath()));
    }

    private void executeAndAwait(Runnable operation) {
        try {
            worker.submit(operation).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while managing Java process", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("Could not manage Java process", e.getCause());
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
