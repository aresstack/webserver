package com.aresstack.webserver.application;

import com.aresstack.webserver.domain.ManagedProcessStatus;
import com.aresstack.webserver.domain.ReadinessPolicy;
import com.aresstack.webserver.domain.Site;
import com.aresstack.webserver.domain.Upstream;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

/** Confirm that a new child is alive and the destination has become reachable. */
public final class WaitForManagedApplication {
    private final ManagedApplicationRuntime processes;

    public WaitForManagedApplication(ManagedApplicationRuntime processes) {
        this.processes = processes;
    }

    public void await(Site site, Upstream defaultUpstream, long previousPid, Instant requestedAfter) {
        String id = site.managedApplication().orElseThrow().id();
        ReadinessPolicy policy = site.managedApplication().orElseThrow().readinessPolicy();
        Upstream backend = site.effectiveUpstream(defaultUpstream);
        String installedJar = site.managedApplication().orElseThrow().jarPath();
        long deadline = System.nanoTime() + Duration.ofSeconds(policy.startupTimeoutSeconds()).toNanos();
        while (System.nanoTime() < deadline) {
            ManagedProcessStatus state = processes.status(id);
            // Der neue Prozess wird über startedAt >= requestedAfter und den
            // installierten JAR-Pfad identifiziert — NICHT über die PID:
            // Windows vergibt PIDs aggressiv neu, eine wiederverwendete PID
            // des Vorgängers darf die Readiness nicht scheitern lassen.
            if ("RUNNING".equals(state.state()) && state.pid() > 0
                    && installedJar.equals(state.jarPath())
                    && state.startedAt() != null && !state.startedAt().isBefore(requestedAfter)
                    && canConnect(backend, policy)) {
                return;
            }
            try {
                Thread.sleep(policy.pollIntervalMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while checking managed application", e);
            }
        }
        throw new IllegalStateException("New managed application did not become ready on "
                + backend.host() + ":" + backend.port() + " within "
                + policy.startupTimeoutSeconds() + " seconds");
    }

    private boolean canConnect(Upstream backend, ReadinessPolicy policy) {
        if (!policy.httpHealthPath().isBlank()) {
            return healthyHttpResponse(backend, policy);
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(backend.host(), backend.port()),
                    policy.connectionTimeoutMillis());
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private boolean healthyHttpResponse(Upstream backend, ReadinessPolicy policy) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(policy.connectionTimeoutMillis())).build();
            URI endpoint = URI.create(backend.scheme() + "://" + backend.host() + ":"
                    + backend.port() + policy.httpHealthPath());
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .GET().timeout(Duration.ofMillis(policy.connectionTimeoutMillis())).build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == policy.expectedHttpStatus();
        } catch (IOException | IllegalArgumentException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Readiness HTTP request interrupted", e);
        }
    }
}
