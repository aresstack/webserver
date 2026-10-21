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
        try (java.net.ServerSocket occupied = new java.net.ServerSocket(0)) {
            manager.reconcile(List.of());
            Site candidate = Site.of("example.org",
                    "http://127.0.0.1:" + occupied.getLocalPort())
                    .withManagedApplication(app);
            assertThrows(IllegalStateException.class, () ->
                    manager.stageUpdate(List.of(candidate), "occupied",
                            candidate.upstream().orElseThrow(), app.readinessPolicy()));
            assertEquals(-1, manager.status("occupied").pid());
        } finally {
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
