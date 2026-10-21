package com.aresstack.webserver.application;

import com.aresstack.webserver.domain.*;
import com.aresstack.webserver.infrastructure.configuration.JsonConfigurationRepository;
import com.aresstack.webserver.infrastructure.managed.FileManagedUpdateJournal;
import com.aresstack.webserver.infrastructure.managed.ManagedProcessManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.net.ServerSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** Exercise a real HTTP child, a failed replacement and restoration of the old process. */
class UpdateManagedApplicationIntegrationTest {
    @TempDir Path root;

    @Test
    void failedReplacementRestoresStoredSiteAndRunningHttpBackend() throws Exception {
        Path jar = createHttpFixture();
        int port = freePort();
        ManagedApplication old = app(jar, port, false);
        ManagedApplication broken = app(jar, port, true);
        // Deploy distinct JAR paths so the update is treated as an application replacement.
        Path failedJar = root.resolve("failed.jar");
        Files.copy(jar, failedJar);
        broken = new ManagedApplication(broken.id(), failedJar.toString(),
                broken.javaExecutable(), broken.workingDirectory(), broken.jvmArguments(),
                broken.applicationArguments(), broken.environment(), true, true,
                broken.restartPolicy(), broken.readinessPolicy(), broken.stopTimeoutSeconds(),
                broken.forceStopTimeoutSeconds());
        Site original = Site.of("example.org", "http://127.0.0.1:" + port)
                .withManagedApplication(old);
        Site candidate = original.withManagedApplication(broken);
        Upstream defaultBackend = original.upstream().orElseThrow();
        JsonConfigurationRepository repository = new JsonConfigurationRepository(
                root.resolve("config/webserver.json"));
        repository.save(configuration(original, defaultBackend));
        ManagedProcessManager manager = new ManagedProcessManager(root, ignored -> { });
        try {
            manager.reconcile(List.of(original));
            await(() -> isOpen(port), Duration.ofSeconds(20), "Original HTTP server");

            UpdateManagedApplication operation = new UpdateManagedApplication(
                    manager, new FileManagedUpdateJournal(root, manager));
            assertThrows(IllegalStateException.class, () -> operation.execute(
                    original, candidate, defaultBackend,
                    List.of(original), List.of(candidate),
                    sites -> {
                        repository.save(configuration(sites.get(0), defaultBackend));
                        manager.reconcile(sites);
                    }));

            assertEquals(old.jarPath(), repository.load().sites().get(0)
                    .managedApplication().orElseThrow().jarPath());
            await(() -> isOpen(port), Duration.ofSeconds(20), "Restored HTTP server");
            assertFalse(Files.exists(root.resolve(
                    "data/service-updates/app-test/transaction.properties")));
        } finally {
            manager.stopAll();
        }
    }

    private static WebServerConfiguration configuration(Site site, Upstream backend) {
        return new WebServerConfiguration(new DomainName("example.org"),
                AcmeConfiguration.withoutEmail(), backend, List.of(site));
    }

    private ManagedApplication app(Path jar, int port, boolean broken) {
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java")
                .toString();
        return new ManagedApplication("app-test", jar.toString(), java, "", List.of(),
                broken ? List.of(String.valueOf(port), "broken")
                        : List.of(String.valueOf(port)),
                Map.of(), true, true, new RestartPolicy(1, 2, 2, 60, 0),
                new ReadinessPolicy(3, 50, 100, "/health", 200), 2, 2);
    }

    private Path createHttpFixture() throws Exception {
        String sourceCode = "import java.net.*; import java.io.*;"
                + "public final class HttpFixture {"
                + "public static void main(String[] args) throws Exception {"
                + "if(args.length > 1){Thread.sleep(30000);return;}"
                + "try(ServerSocket server = new ServerSocket(Integer.parseInt(args[0]),"
                + "50, InetAddress.getByName(\"127.0.0.1\"))){"
                + "while(true){try(Socket client=server.accept()){"
                + "byte[] output=(\"HTTP/1.1 200 OK\\r\\nContent-Length: 2"
                + "\\r\\nConnection: close\\r\\n\\r\\nOK\").getBytes("
                + "java.nio.charset.StandardCharsets.US_ASCII);"
                + "client.getOutputStream().write(output);"
                + "client.getOutputStream().flush();}}}}}";
        Path source = root.resolve("HttpFixture.java");
        Files.writeString(source, sourceCode);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "JDK required");
        assertEquals(0, compiler.run(null, null, null, source.toString()));

        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "HttpFixture");
        Path jar = root.resolve("original.jar");
        try (JarOutputStream zip = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            zip.putNextEntry(new JarEntry("HttpFixture.class"));
            Files.copy(root.resolve("HttpFixture.class"), zip);
            zip.closeEntry();
        }
        return jar;
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static boolean isOpen(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 100);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void await(BooleanSupplier condition, Duration maxWait, String name)
            throws InterruptedException {
        long deadline = System.nanoTime() + maxWait.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(50);
        }
        assertTrue(condition.getAsBoolean(), name + " did not start");
    }
}
