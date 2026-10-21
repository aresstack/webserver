package com.aresstack.webserver.ui;

import com.aresstack.webserver.domain.Site;
import com.aresstack.webserver.infrastructure.caddy.RuntimeDirectories;
import com.aresstack.webserver.infrastructure.configuration.JsonConfigurationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Concurrent publication changes must compose instead of overwriting snapshots. */
class WebServerControllerConcurrentMutationsTest {
    @TempDir Path root;

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void concurrentPublishesKeepBothServicesInThePersistedConfiguration() throws Exception {
        RuntimeDirectories directories = new RuntimeDirectories(root);
        directories.ensureExist();
        Path executable = Path.of(System.getProperty("user.dir"), "runtime", "caddy", "bin",
                "caddy.exe");
        assertTrue(Files.isRegularFile(executable), "Windows CI must prepare the Caddy test binary");
        Files.createDirectories(directories.caddyBinary().getParent());
        Files.copy(executable, directories.caddyBinary());
        WebServerController controller = new WebServerController(directories, new UserLog());

        CountDownLatch begin = new CountDownLatch(1);
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<?> first = workers.submit(() -> {
                try {
                    begin.await();
                    controller.addSite(Site.of("first.example.org", "http://127.0.0.1:8101"));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            });
            Future<?> second = workers.submit(() -> {
                try {
                    begin.await();
                    controller.addSite(Site.of("second.example.org", "http://127.0.0.1:8102"));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            });
            begin.countDown();
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);
        }
        var loaded = new JsonConfigurationRepository(directories.configFile()).load();
        assertEquals(2, loaded.sites().size());
        assertTrue(loaded.sites().stream().anyMatch(site -> site.host().value()
                .equals("first.example.org")));
        assertTrue(loaded.sites().stream().anyMatch(site -> site.host().value()
                .equals("second.example.org")));
        assertEquals(loaded, controller.configuration());
    }
}
