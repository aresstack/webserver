package com.aresstack.webserver.application;

import com.aresstack.webserver.domain.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class WaitForManagedApplicationTest {
    @Test
    void acceptsConfigurableHttpHealthStatusFromNewProcess() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            ReadinessPolicy policy = new ReadinessPolicy(2, 20, 200, "/health", 204);
            ManagedApplication managed = new ManagedApplication("http-test", "/tmp/example.jar",
                    "java", "", List.of(), List.of(), Map.of(),
                    true, true, RestartPolicy.defaults(), policy, 2);
            Site site = Site.of("example.org", "http://127.0.0.1:" + port)
                    .withManagedApplication(managed);
            ManagedProcessStatus running = new ManagedProcessStatus("RUNNING", 6789, 0,
                    null, null, Instant.now(), managed.jarPath());
            ManagedApplicationRuntime runtime = new ManagedApplicationRuntime() {
                @Override public ManagedProcessStatus status(String id) { return running; }
                @Override public void reconcile(List<Site> sites) { }
                @Override public void stageUpdate(List<Site> sites, String id,
                        Upstream backend, ReadinessPolicy policy) { }
                @Override public void terminateInterruptedUpdate(String id, String jar,
                        int graceful, int forced) { }
            };
            assertDoesNotThrow(() -> new WaitForManagedApplication(runtime)
                    .await(site, Upstream.parse("http://127.0.0.1:" + port),
                            1111, running.startedAt().minusSeconds(1)));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsWrongHttpHealthPath() {
        assertThrows(IllegalArgumentException.class,
                () -> new ReadinessPolicy(2, 20, 200, "health", 200));
    }
}
