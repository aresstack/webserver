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
import java.util.concurrent.CompletableFuture;
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
    private static final String PENDING_IDENTITY = "PENDING";
    private final Path root;
    private final Consumer<String> log;
    private final ManagedSecretStore secrets;
    private final ManagedApplicationLogWriter logWriter = new ManagedApplicationLogWriter();
    private final IdentityWriter identityWriter;
    private final ExitWaiter exitWaiter;
    private final ProcessLaunchHooks launchHooks;
    private final OutputCapture outputCapture;
    /**
     * Extra environment per service id, merged in at launch. Used to hand a managed
     * application its scope-registration endpoint and token, so reporting scopes
     * needs no manual copying of a secret. Configured variables win, so an operator
     * can always override.
     */
    private java.util.function.Function<String, Map<String, String>> extraEnvironment =
            id -> Map.of();

    @FunctionalInterface
    interface OutputCapture {
        CompletableFuture<Void> capture(ManagedApplication app, Process process);
    }

    @FunctionalInterface
    interface ExitWaiter {
        boolean await(ProcessHandle handle, long seconds);
    }

    @FunctionalInterface
    interface IdentityWriter {
        void write(Path file, String content) throws IOException;
    }

    interface ProcessLaunchHooks {
        void afterIdentityReserved(ManagedApplication app);

        void afterProcessStarted(ManagedApplication app, Process process);
    }

    private static final ProcessLaunchHooks NO_OP_LAUNCH_HOOKS = new ProcessLaunchHooks() {
        @Override
        public void afterIdentityReserved(ManagedApplication app) {
        }

        @Override
        public void afterProcessStarted(ManagedApplication app, Process process) {
        }
    };
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "managed-jar-lifecycle");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, Service> services = new HashMap<>();
    private final Map<String, ManagedProcessStatus> snapshots = new ConcurrentHashMap<>();
    private boolean active;

    /** Injects environment that is derived at runtime rather than configured. */
    public void extraEnvironment(java.util.function.Function<String, Map<String, String>> extra) {
        this.extraEnvironment = extra == null ? id -> Map.of() : extra;
    }

    public ManagedProcessManager(Path root, Consumer<String> log) {
        this(root, log, ManagedProcessManager::writeIdentityAtomically,
                ManagedProcessManager::awaitExitReal);
    }

    ManagedProcessManager(Path root, Consumer<String> log, IdentityWriter identityWriter) {
        this(root, log, identityWriter, ManagedProcessManager::awaitExitReal);
    }

    ManagedProcessManager(Path root, Consumer<String> log,
                          IdentityWriter identityWriter, ExitWaiter exitWaiter) {
        this(root, log, identityWriter, exitWaiter, NO_OP_LAUNCH_HOOKS);
    }

    ManagedProcessManager(Path root, Consumer<String> log, IdentityWriter identityWriter,
                          ExitWaiter exitWaiter, ProcessLaunchHooks launchHooks) {
        this(root, log, identityWriter, exitWaiter, launchHooks, null);
    }

    ManagedProcessManager(Path root, Consumer<String> log, IdentityWriter identityWriter,
                          ExitWaiter exitWaiter, ProcessLaunchHooks launchHooks,
                          OutputCapture outputCapture) {
        this.root = root.toAbsolutePath().normalize();
        this.log = log;
        this.secrets = new ManagedSecretStore(root);
        this.identityWriter = identityWriter;
        this.exitWaiter = exitWaiter;
        this.launchHooks = launchHooks;
        this.outputCapture = outputCapture == null ? this::captureOutput : outputCapture;
    }

    @Override
    public void reconcile(List<Site> sites) {
        Map<String, ManagedApplication> wanted = new HashMap<>();
        for (Site site : List.copyOf(sites)) {
            site.managedApplication()
                    .filter(ManagedApplication::isEnabled)
                    .ifPresent(app -> wanted.put(app.id(), app));
        }
        executeAndAwait(() -> reconcileOnWorker(wanted));
    }

    @Override
    public void stageUpdate(List<Site> sites, String previousId, Upstream backend,
                            ReadinessPolicy readiness) {
        stageUpdate(sites, previousId, previousId, backend, readiness, true);
    }

    @Override
    public void stageUpdate(List<Site> sites, String previousId, String candidateId,
                            Upstream backend, ReadinessPolicy readiness, boolean startCandidate) {
        Map<String, ManagedApplication> wanted = new HashMap<>();
        for (Site site : List.copyOf(sites)) {
            site.managedApplication()
                    .filter(ManagedApplication::isEnabled)
                    .ifPresent(app -> wanted.put(app.id(), app));
        }
        executeAndAwait(() -> {
            Service previous = services.get(previousId);
            if (previous != null) {
                stopService(previous);
                services.remove(previousId);
                snapshots.remove(previousId);
            }
            awaitBackendPortReleased(backend, readiness);
            reconcileOnWorker(wanted);
            if (startCandidate) {
                Service candidate = services.get(candidateId);
                if (candidate == null) {
                    throw new IllegalStateException("Managed update candidate was not reconciled: "
                            + candidateId);
                }
                if (hasPendingIdentity(candidate.config)) {
                    throw new IllegalStateException("Managed application " + candidateId
                            + " requires manual process-ownership recovery before deployment");
                }
                if (candidate.pending == null
                        && (candidate.handle == null || !candidate.handle.isAlive())) {
                    candidate.requested = true;
                    schedule(candidate, 0);
                }
            }
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
            if (activeService.handle == null && hasPendingIdentity(activeService.config)) {
                log.accept("Interrupted update for " + id
                        + " has no verifiable PID; preserving its PENDING identity for manual recovery");
                return;
            }
            stopService(activeService);
            services.remove(id);
            snapshots.remove(id);
            return;
        }
        Path file = identityPath(id);
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(file);
            if (hasPendingIdentity(file)) {
                log.accept("Interrupted update for " + id
                        + " left an unverified process identity; preserving it for manual recovery");
                return;
            }
            if (lines.size() != 3) {
                return;
            }
            long pid = Long.parseLong(lines.get(0));
            long recordedStart = Long.parseLong(lines.get(1));
            Path recordedJar = Path.of(lines.get(2)).toAbsolutePath().normalize();
            if (!recordedJar.equals(expectedJar)) {
                return;
            }
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
        } catch (IOException | RuntimeException e) {
            log.accept("Could not verify interrupted process identity for " + id
                    + "; preserving it for manual recovery: " + e.getMessage());
        }
    }

    private void awaitBackendPortReleased(Upstream backend, ReadinessPolicy readiness) {
        long deadline = System.nanoTime()
                + java.time.Duration.ofSeconds(readiness.startupTimeoutSeconds()).toNanos();
        while (isPortOccupied(backend, readiness.connectionTimeoutMillis())) {
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("Backend port " + backend.port()
                        + " is still occupied after the previous application stopped");
            }
            try {
                Thread.sleep(readiness.pollIntervalMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted waiting for backend port to close",
                        interrupted);
            }
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
                if (service.handle == null && hasPendingIdentity(service.config)) {
                    cancelPending(service);
                    service.requested = false;
                    service.state = "PENDING_RECOVERY";
                    publish(service);
                    continue;
                }
                try {
                    stopService(service);
                } catch (RuntimeException failure) {
                    // Keep unresolved ownership in memory so a later start cannot bypass
                    // the log-drain gate; continue stopping other services and the proxy.
                    service.requested = false;
                    publish(service);
                    log.accept("Could not fully stop managed application " + service.config.id()
                            + "; continuing server shutdown: " + failure.getMessage());
                }
            }
            services.entrySet().removeIf(entry -> "STOPPED".equals(entry.getValue().state));
        });
    }

    public void start(String id) {
        executeAndAwait(() -> {
            Service service = required(id);
            if (hasPendingIdentity(service.config)) {
                service.requested = false;
                service.state = "PENDING_RECOVERY";
                publish(service);
                throw new IllegalStateException("Managed application " + id
                        + " has an unresolved PENDING process identity. Verify that no orphan "
                        + "process remains, then use Resolve pending process.");
            }
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
            if (next != null && next.sameLaunchSettings(existing.config)) {
                // Nur Lifecycle-/Policy-Einstellungen geändert: Konfiguration
                // übernehmen, ohne den laufenden Prozess zu stoppen.
                if (!next.equals(existing.config)) {
                    existing.config = next;
                    publish(existing);
                }
                continue;
            }
            if (next == null || !next.equals(existing.config)) {
                if (existing.handle == null && hasPendingIdentity(existing.config)) {
                    cancelPending(existing);
                    existing.requested = false;
                    existing.state = "PENDING_RECOVERY";
                    publish(existing);
                    services.remove(id);
                    snapshots.remove(id);
                    continue;
                }
                stopService(existing);
                services.remove(id);
                snapshots.remove(id);
            }
        }
        for (ManagedApplication config : wanted.values()) {
            if (!services.containsKey(config.id())) {
                Service service = new Service(config);
                services.put(config.id(), service);
                if (hasPendingIdentity(config)) {
                    service.requested = false;
                    service.state = "PENDING_RECOVERY";
                    publish(service);
                    log.accept("Managed application " + config.id()
                            + " has a PENDING identity; automatic start is suspended until recovery");
                    continue;
                }
                try {
                    ProcessHandle adopted = previouslyOwned(config);
                    if (adopted != null) {
                        service.handle = adopted;
                        service.startedAt = adopted.info().startInstant().orElse(Instant.now());
                        service.state = "RUNNING";
                        publish(service);
                        observe(service, adopted, null);
                        log.accept("Reconnected managed application " + config.id());
                        continue;
                    }
                } catch (RuntimeException e) {
                    service.requested = false;
                    service.state = "OWNERSHIP_UNVERIFIED";
                    publish(service);
                    log.accept("Cannot verify saved process identity for " + config.id()
                            + "; automatic start is suspended");
                    continue;
                }
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
        if (service.logCapture != null && !service.logCapture.isDone()) {
            service.requested = false;
            service.state = "LOG_DRAIN_FAILED";
            publish(service);
            log.accept("Managed application " + service.config.id()
                    + " still has an active log writer; refusing replacement start");
            return;
        }
        if (hasPendingIdentity(service.config)) {
            service.requested = false;
            service.state = "PENDING_RECOVERY";
            publish(service);
            log.accept("Managed application " + service.config.id()
                    + " remains in PENDING_RECOVERY; refusing to start a duplicate process");
            return;
        }
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
            launchHooks.afterIdentityReserved(service.config);
            Process process = builder.start();
            processStarted = true;
            service.process = process;
            service.handle = process.toHandle();
            service.startedAt = Instant.now();
            launchHooks.afterProcessStarted(service.config, process);
            service.logCapture = outputCapture.capture(service.config, process);
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
            service.requested = false;
            service.state = "PENDING_RECOVERY";
            publish(service);
            return;
        }
        try {
            handle.destroy();
            if (!awaitExit(handle, service.config.stopTimeoutSeconds())) {
                handle.destroyForcibly();
                if (!awaitExit(handle, service.config.forceStopTimeoutSeconds())) {
                    service.requested = false;
                    service.state = "PENDING_RECOVERY";
                    publish(service);
                    log.accept("Cannot confirm termination of unregistered process "
                            + handle.pid() + "; automatic relaunch suspended");
                    return;
                }
            }
            awaitLogCapture(service, service.process);
            service.process = null;
            service.handle = null;
            deleteIdentity(service.config);
            failed(service, null);
        } catch (RuntimeException e) {
            service.requested = false;
            service.state = "PENDING_RECOVERY";
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
        Files.writeString(file, PENDING_IDENTITY + "\n"
                + Path.of(app.jarPath()).toAbsolutePath().normalize(), StandardOpenOption.CREATE_NEW);
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
        applyEnvironment(builder.environment(), app,
                extraEnvironment.apply(app.id()),
                value -> secrets.resolve(app.id(), value));
        builder.redirectErrorStream(true);
        return builder;
    }

    /**
     * Baut die Umgebung des Kindprozesses.
     *
     * <p>Abgeschaltete Variablen werden <em>entfernt</em>, nicht leer gesetzt - fuer
     * die meisten Anwendungen ist "nicht gesetzt" etwas anderes als "leer". Das gilt
     * auch gegenueber der zur Laufzeit beigesteuerten Umgebung: eine Variable, die
     * der Betreiber abgeschaltet hat, darf nicht durch die Hintertuer wieder
     * auftauchen.
     */
    static void applyEnvironment(Map<String, String> environment, ManagedApplication app,
                                 Map<String, String> extra,
                                 java.util.function.UnaryOperator<String> revealSecret) {
        environment.putAll(extra);
        for (Map.Entry<String, ManagedEnvironmentVariable> entry : app.environment().entrySet()) {
            ManagedEnvironmentVariable variable = entry.getValue();
            if (!variable.enabled()) {
                environment.remove(entry.getKey());
                continue;
            }
            environment.put(entry.getKey(), variable.secret()
                    ? revealSecret.apply(variable.value()) : variable.value());
        }
    }

    private CompletableFuture<Void> captureOutput(ManagedApplication app, Process process) {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        Path logFile = root.resolve("logs").resolve("services").resolve(app.id() + ".log");
        Thread captureThread = new Thread(() -> {
            try {
                logWriter.capture(process.getInputStream(), logFile, log);
            } finally {
                completion.complete(null);
            }
        }, "managed-jar-log-" + app.id());
        captureThread.setDaemon(true);
        captureThread.start();
        return completion;
    }

    private void observe(Service service, ProcessHandle handle, Process ownedProcess) {
        handle.onExit().thenRun(() -> worker.execute(() -> {
            if (services.get(service.config.id()) != service || service.handle != handle) {
                return;
            }
            if (!awaitLogCapture(service, ownedProcess)) {
                service.state = "LOG_DRAIN_FAILED";
                publish(service);
                log.accept("Managed application " + service.config.id()
                        + " exited, but its log writer is still active; automatic replacement is suspended");
                return;
            }
            Integer exit = ownedProcess == null ? null : ownedProcess.exitValue();
            service.process = null;
            service.handle = null;
            deleteIdentity(service.config);
            failed(service, exit);
        }));
    }

    private boolean awaitLogCapture(Service service, Process process) {
        if (service.logCapture == null || service.logCapture.isDone()) {
            return true;
        }
        try {
            service.logCapture.get(2, TimeUnit.SECONDS);
            return true;
        } catch (TimeoutException e) {
            if (process != null) {
                try {
                    process.getInputStream().close();
                } catch (IOException closeFailure) {
                    log.accept("Could not close completed log stream for "
                            + service.config.id() + ": " + closeFailure.getMessage());
                }
            }
            try {
                service.logCapture.get(1, TimeUnit.SECONDS);
                return true;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            } catch (ExecutionException | TimeoutException captureFailure) {
                log.accept("Managed application log stream did not close promptly for "
                        + service.config.id());
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException e) {
            log.accept("Managed application log capture failed for " + service.config.id());
            return false;
        }
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
        if (handle == null && hasPendingIdentity(service.config)) {
            service.state = "PENDING_RECOVERY";
            publish(service);
            throw new IllegalStateException("Managed application " + service.config.id()
                    + " has an unresolved process identity. Verify process ownership "
                    + "before clearing the marker.");
        }
        if (handle == null) {
            try {
                handle = previouslyOwned(service.config);
                service.handle = handle;
            } catch (RuntimeException e) {
                service.state = "OWNERSHIP_UNVERIFIED";
                publish(service);
                throw new IllegalStateException("Cannot safely stop managed application "
                        + service.config.id() + " because its process identity is unverified.", e);
            }
        }
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
        if (!awaitLogCapture(service, service.process)) {
            service.state = "LOG_DRAIN_FAILED";
            publish(service);
            throw new IllegalStateException("Managed application " + service.config.id()
                    + " has an active log writer; refusing to start a replacement until the "
                    + "writer has stopped");
        }
        service.logCapture = null;
        service.process = null;
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
        return identityPath(app.id());
    }

    private Path identityPath(String id) {
        if (!id.matches("[a-zA-Z0-9-]{1,64}")) {
            throw new IllegalArgumentException("Invalid managed application id");
        }
        return root.resolve("data").resolve("service-runtime").resolve(id + ".txt");
    }

    public boolean hasPendingIdentity(String id) {
        return hasPendingIdentity(identityPath(id), null);
    }

    public boolean hasPendingIdentity(String id, String configuredJar) {
        return hasPendingIdentity(identityPath(id), configuredJar);
    }

    public void resolvePendingIdentity(String id) {
        resolvePendingIdentity(id, null);
    }

    public void resolvePendingIdentity(String id, String configuredJar) {
        executeAndAwait(() -> {
            Path file = identityPath(id);
            if (!hasPendingIdentity(file, configuredJar)) {
                throw new IllegalStateException("No unresolved process identity exists for " + id);
            }
            Service service = services.get(id);
            if ((service != null && service.handle != null && service.handle.isAlive())
                    || hasLiveIdentityProcess(file)) {
                throw new IllegalStateException("A process may still own the saved identity for " + id
                        + ". Stop it before resolving the identity.");
            }
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot clear unresolved process identity for " + id, e);
            }
            if (service != null) {
                cancelPending(service);
                service.requested = false;
                service.handle = null;
                service.startedAt = null;
                service.nextStart = null;
                service.state = "STOPPED";
                publish(service);
            } else {
                snapshots.put(id, ManagedProcessStatus.stopped());
            }
            log.accept("Cleared unresolved process identity for " + id
                    + " after manual process-ownership confirmation");
        });
    }

    private boolean hasPendingIdentity(ManagedApplication app) {
        return hasPendingIdentity(identityPath(app), app.jarPath());
    }

    private boolean hasPendingIdentity(Path file) {
        return hasPendingIdentity(file, null);
    }

    private boolean hasPendingIdentity(Path file, String expectedJar) {
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try {
            List<String> lines = Files.readAllLines(file);
            if (lines.size() != 3 || PENDING_IDENTITY.equals(lines.get(0))) {
                return true;
            }
            long pid;
            long recordedStart;
            Path recordedJar;
            try {
                pid = Long.parseLong(lines.get(0));
                recordedStart = Long.parseLong(lines.get(1));
                recordedJar = Path.of(lines.get(2)).toAbsolutePath().normalize();
            } catch (RuntimeException invalidIdentity) {
                return true;
            }
            Optional<ProcessHandle> possible = ProcessHandle.of(pid);
            if (possible.isEmpty() || !possible.get().isAlive()) {
                return false;
            }
            ProcessHandle handle = possible.get();
            Instant actualStart = handle.info().startInstant().orElse(null);
            if (actualStart == null) {
                return true;
            }
            if (actualStart.toEpochMilli() != recordedStart) {
                return false;
            }
            if (expectedJar != null
                    && !recordedJar.equals(Path.of(expectedJar).toAbsolutePath().normalize())) {
                return true;
            }
            Optional<String[]> arguments = handle.info().arguments();
            return arguments.isEmpty()
                    || !Arrays.asList(arguments.get()).contains(recordedJar.toString());
        } catch (IOException | RuntimeException e) {
            log.accept("Cannot verify process identity " + file
                    + "; refusing automatic recovery");
            return true;
        }
    }

    private boolean hasLiveIdentityProcess(Path file) {
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try {
            List<String> lines = Files.readAllLines(file);
            if (lines.size() != 3 || PENDING_IDENTITY.equals(lines.get(0))) {
                return false;
            }
            long pid = Long.parseLong(lines.get(0));
            long recordedStart = Long.parseLong(lines.get(1));
            Optional<ProcessHandle> possible = ProcessHandle.of(pid);
            if (possible.isEmpty() || !possible.get().isAlive()) {
                return false;
            }
            Instant actualStart = possible.get().info().startInstant().orElse(null);
            return actualStart == null || actualStart.toEpochMilli() == recordedStart;
        } catch (IOException | RuntimeException e) {
            return false;
        }
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
        private ManagedApplication config;
        private ProcessHandle handle;
        private Process process;
        private CompletableFuture<Void> logCapture = CompletableFuture.completedFuture(null);
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
