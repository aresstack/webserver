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
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java")
                .toString();
        ManagedApplication config = new ManagedApplication("fixture", jar.toString(), java,
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
