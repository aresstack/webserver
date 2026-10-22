package com.aresstack.webserver.application;

import com.aresstack.webserver.domain.ManagedProcessStatus;
import com.aresstack.webserver.domain.ReadinessPolicy;
import com.aresstack.webserver.domain.Site;
import com.aresstack.webserver.domain.Upstream;
import java.util.List;

/** Restrict deployment use cases to process operations, independent of infrastructure. */
public interface ManagedApplicationRuntime {
    ManagedProcessStatus status(String id);
    void reconcile(List<Site> sites);
    void stageUpdate(List<Site> sites, String previousId, Upstream backend, ReadinessPolicy readiness);

    default void stageUpdate(List<Site> sites, String previousId, String candidateId, Upstream backend,
                             ReadinessPolicy readiness, boolean startCandidate) {
        stageUpdate(sites, previousId, backend, readiness);
    }
    void terminateInterruptedUpdate(String applicationId, String candidateJar, int gracefulSeconds,
                                    int forcedSeconds);
}
