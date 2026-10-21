package com.aresstack.webserver.application;

import com.aresstack.webserver.domain.ReadinessPolicy;
import com.aresstack.webserver.domain.Site;
import com.aresstack.webserver.domain.Upstream;
import com.aresstack.webserver.infrastructure.managed.ManagedProcessManager;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;

/**
 * Verify the new child process and the configured backend port after an update.
 * Leave first-time deployment and steady-state supervision to their own workflows.
 */
public final class WaitForManagedApplication {
    private final ManagedProcessManager processes;

    public WaitForManagedApplication(ManagedProcessManager processes) {
        this.processes = processes;
    }

    public void await(Site site, Upstream defaultUpstream) {
        String id = site.managedApplication().orElseThrow().id();
        ReadinessPolicy policy = site.managedApplication().orElseThrow().readinessPolicy();
        Upstream backend = site.effectiveUpstream(defaultUpstream);
        long deadline = System.nanoTime() + Duration.ofSeconds(policy.startupTimeoutSeconds()).toNanos();
        while (System.nanoTime() < deadline) {
            if (processes.status(id).pid() > 0 && canConnect(backend, policy.connectionTimeoutMillis())) {
                return;
            }
            try {
                Thread.sleep(policy.pollIntervalMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for managed JAR startup", e);
            }
        }
        throw new IllegalStateException("Managed application did not open "
                + backend.host() + ":" + backend.port() + " within "
                + policy.startupTimeoutSeconds() + " seconds");
    }

    private boolean canConnect(Upstream backend, int connectTimeoutMillis) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(backend.host(), backend.port()),
                    connectTimeoutMillis);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
