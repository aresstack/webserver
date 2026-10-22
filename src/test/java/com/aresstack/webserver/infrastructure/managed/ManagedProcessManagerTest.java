package com.aresstack.webserver.infrastructure.managed;

import com.aresstack.webserver.domain.ManagedApplication;
import com.aresstack.webserver.domain.Site;
import com.aresstack.webserver.domain.RestartPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class ManagedProcessManagerTest {
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

    @TempDir Path directory;

    @Test
    void restartsUnexpectedExitButNeverRestartsManuallyStoppedProcess() throws Exception {
        Path jar = createTestJar();
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java")
                .toString();
        ManagedApplication config = new ManagedApplication("fixture", jar.toString(), javaExecutable,
                "", List.of(), List.of(), java.util.Map.of(), true, true,
                new RestartPolicy(1, 2.0, 3, 10, 0), 2);
        Site basic = Site.of("example.org", "http://localhost:8181");
        Site site = basic.withManagedApplication(config);
        ManagedProcessManager manager = new ManagedProcessManager(directory, message -> { });
        try {
            manager.reconcile(List.of(site));
            await(() -> manager.status("fixture").pid() > 0, "Initial launch");
            long firstPid = manager.status("fixture").pid();
            ProcessHandle.of(firstPid).orElseThrow().destroyForcibly();
            await(() -> manager.status("fixture").pid() > 0
                    && manager.status("fixture").pid() != firstPid, "Automatic restart");
            manager.stop("fixture");
            assertEquals("STOPPED", manager.status("fixture").state());
            assertEquals(-1, manager.status("fixture").pid());
            TimeUnit.SECONDS.sleep(3);
            assertEquals("STOPPED", manager.status("fixture").state());
            assertEquals(-1, manager.status("fixture").pid());
        } finally {
            manager.stopAll();
        }
    }

    @Test
    void neverStartsADuplicateWhenAnEarlierProcessIsAlreadyRegistered() throws Exception {
        Path jar = createTestJar();
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java")
                .toString();
        Process existing = new ProcessBuilder(javaExecutable, "-jar", jar.toString()).start();
        ManagedProcessManager manager = new ManagedProcessManager(directory, message -> { });
        try {
            Path identity = directory.resolve("data/service-runtime/adoption.txt");
            Files.createDirectories(identity.getParent());
            Files.writeString(identity, existing.pid() + "\n"
                    + existing.toHandle().info().startInstant().orElseThrow().toEpochMilli()
                    + "\n" + jar.toAbsolutePath().normalize());

            ManagedApplication app = ManagedApplication.defaults("adoption", jar.toString());
            manager.reconcile(List.of(Site.of("example.org", "http://localhost:8181")
                    .withManagedApplication(app)));
            await(() -> !"STOPPED".equals(manager.status("adoption").state())
                    && !"STARTING".equals(manager.status("adoption").state()),
                    "Ownership evaluation");
            long observedPid = manager.status("adoption").pid();
            assertTrue(observedPid == -1 || observedPid == existing.pid(),
                    "An unverifiable process must never result in a duplicate launch");
        } finally {
            manager.stopAll();
            if (existing.isAlive()) {
                existing.destroyForcibly();
                existing.waitFor();
            }
        }
    }

    @Test
    void refusesCandidateWhenBackendPortIsHeldByAnotherProcess() throws Exception {
        Path jar = createTestJar();
        ManagedApplication app = ManagedApplication.defaults("occupied", jar.toString());
        ManagedProcessManager manager = new ManagedProcessManager(directory, message -> { });
        try (java.net.ServerSocket occupied = new java.net.ServerSocket(0, 50,
                java.net.InetAddress.getByName("127.0.0.1"))) {
            manager.reconcile(List.of());
            Site candidate = Site.of("example.org",
                    "http://127.0.0.1:" + occupied.getLocalPort())
                    .withManagedApplication(app);
            assertThrows(IllegalStateException.class, () ->
                    manager.stageUpdate(List.of(candidate), "occupied",
                            candidate.upstream().orElseThrow(),
                            new com.aresstack.webserver.domain.ReadinessPolicy(1, 25, 50)));
            assertEquals(-1, manager.status("occupied").pid());
        } finally {
            manager.stopAll();
        }
    }

    @Test
    void failedIdentityPersistenceStopsNewChildInsteadOfLeavingAnOrphan() throws Exception {
        Path jar = createTestJar();
        AtomicLong attemptedPid = new AtomicLong(-1);
        ManagedProcessManager manager = new ManagedProcessManager(directory, ignored -> { },
                (file, contents) -> {
                    attemptedPid.set(Long.parseLong(contents.substring(0, contents.indexOf('\n'))));
                    throw new IOException("Simulated disk failure after process creation");
                });
        try {
            Site site = Site.of("example.org", "http://localhost:8181")
                    .withManagedApplication(ManagedApplication.defaults("identity-failure", jar.toString()));
            manager.reconcile(List.of(site));
            await(() -> attemptedPid.get() > 0, "Child launched before identity-write failure");
            await(() -> ProcessHandle.of(attemptedPid.get())
                    .map(handle -> !handle.isAlive()).orElse(true),
                    "Unregistered child terminated");
            assertFalse(Files.isRegularFile(directory.resolve(
                    "data/service-runtime/identity-failure.txt")),
                    "A confirmed dead child should not retain a pending identity");
        } finally {
            manager.stopAll();
            ProcessHandle.of(attemptedPid.get()).ifPresent(handle -> {
                if (handle.isAlive()) {
                    handle.destroyForcibly();
                }
            });
        }
    }

    @Test
    void failedReconcileReportsFailureToCallerBeforeItCanCommitConfig() throws Exception {
        Path jar = createTestJar();
        Path replacement = directory.resolve("replacement.jar");
        Files.copy(jar, replacement);
        AtomicBoolean rejectExitConfirmation = new AtomicBoolean(false);
        ManagedProcessManager manager = new ManagedProcessManager(directory, ignored -> { },
                ManagedProcessManager::writeIdentityAtomically,
                (handle, seconds) -> !rejectExitConfirmation.get() && !handle.isAlive());
        // On this fixture, process termination is intentionally reported as unconfirmed.
        // The old process record must not be silently replaced by a new configuration.
        ManagedApplication old = ManagedApplication.defaults("reconcile-failure", jar.toString());
        Site original = Site.of("example.org", "http://localhost:8181")
                .withManagedApplication(old);
        try {
            manager.reconcile(List.of(original));
            await(() -> manager.status(old.id()).pid() > 0, "First child started");
            rejectExitConfirmation.set(true);
            Site updated = original.withManagedApplication(old.withJarPath(replacement.toString()));
            assertThrows(IllegalStateException.class, () -> manager.reconcile(List.of(updated)));
            assertEquals("STOP_FAILED", manager.status(old.id()).state());
            assertEquals(old.jarPath(), manager.status(old.id()).jarPath());
        } finally {
            rejectExitConfirmation.set(false);
            manager.stopAll();
        }
    }

    @Test
    void pendingIdentityBlocksDuplicateStartUntilExplicitRecovery() throws Exception {
        Path jar = createTestJar();
        ManagedApplication app = ManagedApplication.defaults("pending-recovery", jar.toString());
        Path identity = directory.resolve("data/service-runtime/" + app.id() + ".txt");
        Files.createDirectories(identity.getParent());
        Files.writeString(identity, "PENDING\n" + jar.toAbsolutePath().normalize());

        ManagedProcessManager manager = new ManagedProcessManager(directory, ignored -> { });
        Site site = Site.of("example.org", "http://127.0.0.1:8181")
                .withManagedApplication(app);
        try {
            assertDoesNotThrow(() -> manager.terminateInterruptedUpdate(
                    app.id(), jar.toString(), 1, 1));
            assertTrue(Files.exists(identity), "Keep the marker until ownership is confirmed");

            manager.reconcile(List.of(site));
            assertEquals("PENDING_RECOVERY", manager.status(app.id()).state());
            assertThrows(IllegalStateException.class, () -> manager.start(app.id()));
            assertTrue(Files.exists(identity), "Do not clear an uncertain identity on Start");

            assertDoesNotThrow(() -> manager.resolvePendingIdentity(app.id()));
            assertFalse(Files.exists(identity));
            manager.start(app.id());
            await(() -> manager.status(app.id()).pid() > 0, "Start after explicit recovery");
            assertEquals("RUNNING", manager.status(app.id()).state());
        } finally {
            manager.stopAll();
        }
    }

    @Test
    void suspendsAutomaticRestartAfterUnexpectedExitWhenLogDrainFails() throws Exception {
        Path jar = createTestJar();
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java")
                .toString();
        ManagedApplication app = new ManagedApplication("unexpected-exit-drain", jar.toString(),
                javaExecutable, "", List.of(), List.of(), java.util.Map.of(), true, true,
                new RestartPolicy(1, 2.0, 3, 10, 0), 2);
        AtomicReference<CompletableFuture<Void>> activeDrain = new AtomicReference<>();
        AtomicInteger captureCount = new AtomicInteger();
        ManagedProcessManager.OutputCapture outputCapture = (configuration, process) -> {
            captureCount.incrementAndGet();
            CompletableFuture<Void> drain = new CompletableFuture<>();
            activeDrain.set(drain);
            return drain;
        };
        ManagedProcessManager.ProcessLaunchHooks hooks = new ManagedProcessManager.ProcessLaunchHooks() {
            @Override
            public void afterIdentityReserved(ManagedApplication configuration) {
            }

            @Override
            public void afterProcessStarted(ManagedApplication configuration, Process process) {
            }
        };
        ManagedProcessManager manager = new ManagedProcessManager(directory, ignored -> { },
                ManagedProcessManager::writeIdentityAtomically,
                (handle, seconds) -> {
                    if (!handle.isAlive()) {
                        return true;
                    }
                    try {
                        handle.onExit().get(seconds, TimeUnit.SECONDS);
                        return !handle.isAlive();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return false;
                    } catch (ExecutionException | TimeoutException timeout) {
                        return !handle.isAlive();
                    }
                }, hooks, outputCapture);
        Site site = Site.of("example.org", "http://127.0.0.1:8181")
                .withManagedApplication(app);
        try {
            manager.reconcile(List.of(site));
            await(() -> manager.status(app.id()).pid() > 0 && captureCount.get() == 1,
                    "Initial managed process and output capture");
            long originalPid = manager.status(app.id()).pid();

            assertTrue(ProcessHandle.of(originalPid).orElseThrow().destroyForcibly(),
                    "Terminate the child process unexpectedly");
            await(() -> "LOG_DRAIN_FAILED".equals(manager.status(app.id()).state()),
                    "Log-drain failure after unexpected child exit");
            assertEquals(-1, manager.status(app.id()).pid());

            TimeUnit.SECONDS.sleep(1);
            assertEquals("LOG_DRAIN_FAILED", manager.status(app.id()).state());
            assertEquals(1, captureCount.get(),
                    "Do not automatically restart while the old log drain is unresolved");

            activeDrain.get().complete(null);
            assertEquals("LOG_DRAIN_FAILED", manager.status(app.id()).state(),
                    "Completing the drain alone must not trigger an automatic restart");

            manager.start(app.id());
            await(() -> manager.status(app.id()).pid() > 0
                            && manager.status(app.id()).pid() != originalPid
                            && captureCount.get() == 2,
                    "Manual start after the previous log drain completes");
            assertEquals("RUNNING", manager.status(app.id()).state());
        } finally {
            CompletableFuture<Void> drain = activeDrain.get();
            if (drain != null) {
                drain.complete(null);
            }
            manager.stopAll();
        }
    }

    @Test
    void refusesReplacementWhilePreviousLogWriterIsStillActive() throws Exception {
        Path jar = createTestJar();
        AtomicReference<CompletableFuture<Void>> activeDrain = new AtomicReference<>();
        CountDownLatch replacementRefused = new CountDownLatch(1);
        CountDownLatch shutdownContinued = new CountDownLatch(1);
        ManagedProcessManager.OutputCapture outputCapture = (app, process) -> {
            CompletableFuture<Void> drain = new CompletableFuture<>();
            activeDrain.set(drain);
            return drain;
        };
        ManagedProcessManager.ProcessLaunchHooks hooks = new ManagedProcessManager.ProcessLaunchHooks() {
            @Override
            public void afterIdentityReserved(ManagedApplication app) {
            }

            @Override
            public void afterProcessStarted(ManagedApplication app, Process process) {
            }
        };
        ManagedProcessManager manager = new ManagedProcessManager(directory, message -> {
            if (message.contains("still has an active log writer; refusing replacement start")) {
                replacementRefused.countDown();
            }
            if (message.contains("continuing server shutdown")) {
                shutdownContinued.countDown();
            }
        }, ManagedProcessManager::writeIdentityAtomically,
                (handle, seconds) -> {
                    if (!handle.isAlive()) {
                        return true;
                    }
                    try {
                        handle.onExit().get(seconds, TimeUnit.SECONDS);
                        return !handle.isAlive();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    } catch (ExecutionException | TimeoutException e) {
                        return !handle.isAlive();
                    }
                }, hooks, outputCapture);
        ManagedApplication app = ManagedApplication.defaults("blocked-log-drain", jar.toString());
        Site site = Site.of("example.org", "http://127.0.0.1:8181").withManagedApplication(app);
        long originalPid = -1;
        try {
            manager.reconcile(List.of(site));
            await(() -> manager.status(app.id()).pid() > 0, "Initial managed process");
            originalPid = manager.status(app.id()).pid();
            assertNotNull(activeDrain.get(), "The lifecycle must register the output drain");

            assertThrows(IllegalStateException.class, () -> manager.stop(app.id()));
            assertEquals("LOG_DRAIN_FAILED", manager.status(app.id()).state());
            assertFalse(ProcessHandle.of(originalPid).map(ProcessHandle::isAlive).orElse(false));

            manager.start(app.id());
            assertTrue(replacementRefused.await(5, TimeUnit.SECONDS),
                    "Lifecycle must explicitly refuse the replacement start");
            assertEquals("LOG_DRAIN_FAILED", manager.status(app.id()).state());
            assertEquals(-1, manager.status(app.id()).pid(),
                    "No replacement PID may be published while the old writer is active");

            activeDrain.get().complete(null);
            manager.stop(app.id());
            manager.start(app.id());
            long previousPid = originalPid;
            await(() -> manager.status(app.id()).pid() > 0
                            && manager.status(app.id()).pid() != previousPid,
                    "Replacement starts only after the old writer completes");
            assertEquals("RUNNING", manager.status(app.id()).state());

            assertDoesNotThrow(manager::stopAll,
                    "An unresolved log drain must not abort overall server shutdown");
            assertTrue(shutdownContinued.await(5, TimeUnit.SECONDS),
                    "Shutdown must report the unresolved drain and continue");
            assertEquals("LOG_DRAIN_FAILED", manager.status(app.id()).state());
            activeDrain.get().complete(null);
            assertDoesNotThrow(manager::stopAll);
        } finally {
            CompletableFuture<Void> drain = activeDrain.get();
            if (drain != null) {
                drain.complete(null);
            }
            manager.stopAll();
        }
    }

    private Path createTestJar() throws Exception {
        Path source = directory.resolve("FixtureServer.java");
        Files.writeString(source, "public final class FixtureServer {"
                + " public static void main(String[] args) throws Exception {"
                + " Thread.sleep(300000L); } }");
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Tests require a JDK");
        assertEquals(0, compiler.run(null, null, null, source.toString()));
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "FixtureServer");
        Path jar = directory.resolve("fixture.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            output.putNextEntry(new JarEntry("FixtureServer.class"));
            Files.copy(directory.resolve("FixtureServer.class"), output);
            output.closeEntry();
        }
        return jar;
    }

    private static void await(BooleanSupplier condition, String operation) throws InterruptedException {
        long deadline = System.nanoTime() + AWAIT_TIMEOUT.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(POLL_INTERVAL.toMillis());
        }
        assertTrue(condition.getAsBoolean(), operation + " did not complete before the timeout");
    }
}
