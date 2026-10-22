package com.aresstack.webserver.infrastructure.managed;

import com.aresstack.webserver.domain.ManagedApplication;
import com.aresstack.webserver.domain.ReadinessPolicy;
import com.aresstack.webserver.domain.RestartPolicy;
import com.aresstack.webserver.domain.Site;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;

class ManagedProcessCrashRecoveryTest {
    @TempDir Path root;

    @Test
    void recoversWhenJvmCrashesImmediatelyAfterWritingPendingMarker() throws Exception {
        verifyCrashRecovery("after-reservation", 71, false);
    }

    @Test
    void recoversWhenJvmCrashesAfterChildStartsButBeforePidRegistration() throws Exception {
        verifyCrashRecovery("after-process-start", 72, true);
    }

    private void verifyCrashRecovery(String crashPoint, int expectedExitCode,
                                     boolean childStartsBeforeCrash) throws Exception {
        Path fixtureJar = createCrashFixtureJar();
        Path previousJar = root.resolve("previous.jar");
        Path candidateJar = root.resolve("candidate.jar");
        Files.copy(fixtureJar, previousJar);
        Files.copy(fixtureJar, candidateJar);
        Path childPidFile = root.resolve("child.pid");
        Path identityFile = root.resolve("data/service-runtime/crash-app.txt");
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win")
                        ? "java.exe" : "java").toString();

        Process helper = new ProcessBuilder(javaExecutable, "-cp",
                childClassPath(),
                ManagedProcessCrashHarness.class.getName(),
                root.toString(), previousJar.toString(), candidateJar.toString(),
                childPidFile.toString(), crashPoint)
                .redirectErrorStream(true)
                .start();
        long orphanPid = -1;
        ManagedProcessManager recoveryRuntime = null;
        try {
            assertTrue(helper.waitFor(20, TimeUnit.SECONDS),
                    "The helper JVM must halt at the requested crash point");
            assertEquals(expectedExitCode, helper.exitValue());
            assertTrue(Files.isRegularFile(identityFile), "The PENDING marker must survive the crash");

            if (childStartsBeforeCrash) {
                await(() -> Files.isRegularFile(childPidFile), Duration.ofSeconds(10),
                        "Child PID file");
                orphanPid = Long.parseLong(Files.readString(childPidFile).trim());
                long pidToTerminate = orphanPid;
                assertTrue(ProcessHandle.of(pidToTerminate).map(ProcessHandle::isAlive).orElse(false),
                        "The child process must survive the manager JVM crash");
                ProcessHandle.of(pidToTerminate).orElseThrow().destroyForcibly();
                await(() -> ProcessHandle.of(pidToTerminate)
                        .map(handle -> !handle.isAlive()).orElse(true),
                        Duration.ofSeconds(10), "Orphan child termination");
            }

            ManagedApplication previous = application(previousJar, childPidFile);
            Site previousSite = Site.of("example.org", "http://127.0.0.1:8181")
                    .withManagedApplication(previous);
            ManagedProcessManager runtime = new ManagedProcessManager(root, ignored -> { });
            recoveryRuntime = runtime;
            FileManagedUpdateJournal journal = new FileManagedUpdateJournal(root, runtime);

            assertDoesNotThrow(journal::recover,
                    "An unresolved process identity must not prevent webserver recovery");
            assertEquals("previous configuration",
                    Files.readString(root.resolve("config/webserver.json")));
            assertTrue(Files.exists(identityFile),
                    "Retain the marker until the operator confirms process ownership");

            runtime.reconcile(List.of(previousSite));
            assertEquals("PENDING_RECOVERY", runtime.status(previous.id()).state());
            assertThrows(IllegalStateException.class, () -> runtime.start(previous.id()));

            runtime.resolvePendingIdentity(previous.id(), previous.jarPath());
            assertFalse(Files.exists(identityFile));
            runtime.start(previous.id());
            await(() -> runtime.status(previous.id()).pid() > 0,
                    Duration.ofSeconds(10), "Managed process start after recovery");
            assertEquals("RUNNING", runtime.status(previous.id()).state());
        } finally {
            if (helper.isAlive()) {
                helper.destroyForcibly();
            }
            if (orphanPid <= 0 && Files.isRegularFile(childPidFile)) {
                try {
                    orphanPid = Long.parseLong(Files.readString(childPidFile).trim());
                } catch (RuntimeException | java.io.IOException ignored) {
                    // Keep cleanup best-effort if the child was killed before writing its PID.
                }
            }
            if (orphanPid > 0) {
                ProcessHandle.of(orphanPid).filter(ProcessHandle::isAlive)
                        .ifPresent(ProcessHandle::destroyForcibly);
            }
            if (recoveryRuntime != null) {
                recoveryRuntime.stopAll();
            }
        }
    }

    private static String childClassPath() throws Exception {
        String testClasses = Path.of(
                ManagedProcessCrashRecoveryTest.class.getProtectionDomain()
                        .getCodeSource().getLocation().toURI()).toString();
        String mainClasses = Path.of(
                ManagedProcessManager.class.getProtectionDomain()
                        .getCodeSource().getLocation().toURI()).toString();
        return String.join(java.io.File.pathSeparator,
                testClasses, mainClasses, System.getProperty("java.class.path", ""));
    }

    private Path createCrashFixtureJar() throws Exception {
        Path source = root.resolve("CrashFixture.java");
        Files.writeString(source,
                "import java.nio.file.Files; import java.nio.file.Path;"
                        + "public final class CrashFixture {"
                        + "public static void main(String[] args) throws Exception {"
                        + "Files.writeString(Path.of(args[0]), Long.toString(ProcessHandle.current().pid()));"
                        + "Thread.sleep(300000L);"
                        + "}}");
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Tests require a JDK");
        assertEquals(0, compiler.run(null, null, null, source.toString()));

        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "CrashFixture");
        Path jar = root.resolve("crash-fixture.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            output.putNextEntry(new JarEntry("CrashFixture.class"));
            Files.copy(root.resolve("CrashFixture.class"), output);
            output.closeEntry();
        }
        return jar;
    }

    private static ManagedApplication application(Path jar, Path childPidFile) {
        return new ManagedApplication("crash-app", jar.toString(),
                ManagedApplication.defaultJavaExecutable(), "", List.of(),
                List.of(childPidFile.toString()), Map.of(), true, true,
                RestartPolicy.defaults(), ReadinessPolicy.defaults(), 2, 2);
    }

    private static void await(BooleanSupplier condition, Duration timeout, String operation)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(50);
        }
        assertTrue(condition.getAsBoolean(), operation + " did not complete before timeout");
    }
}
