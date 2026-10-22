package com.aresstack.webserver.application;

import com.aresstack.webserver.domain.ManagedProcessStatus;
import com.aresstack.webserver.domain.Site;
import com.aresstack.webserver.domain.Upstream;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

/**
 * Stage a new application process without persisting the new publication first.
 * Commit routing and persisted configuration only after readiness succeeds.
 */
public final class UpdateManagedApplication {
    private final ManagedApplicationRuntime runtime;
    private final ManagedUpdateJournal journal;
    private final WaitForManagedApplication readiness;

    public UpdateManagedApplication(ManagedApplicationRuntime runtime,
                                    ManagedUpdateJournal journal) {
        this.runtime = runtime;
        this.journal = journal;
        this.readiness = new WaitForManagedApplication(runtime);
    }

    public void execute(Site previous, Site candidate, Upstream defaultBackend,
                        List<Site> previousSites, List<Site> candidateSites,
                        Consumer<List<Site>> applySites) {
        if (candidate.managedApplication().isEmpty()) {
            applySites.accept(candidateSites);
            return;
        }
        String candidateId = candidate.managedApplication().orElseThrow().id();
        String previousId = previous.managedApplication()
                .map(app -> app.id()).orElse(candidateId);
        ManagedProcessStatus previousStatus = runtime.status(previousId);
        boolean startCandidate = candidate.managedApplication().orElseThrow().startAutomatically()
                || previousStatus.pid() > 0;
        if (!startCandidate) {
            applySites.accept(candidateSites);
            return;
        }
        journal.prepare(previous, candidate);
        try {
            Instant startingAfter = Instant.now();
            runtime.stageUpdate(candidateSites, previousId, candidateId,
                    candidate.effectiveUpstream(defaultBackend),
                    candidate.managedApplication().orElseThrow().readinessPolicy(), startCandidate);
            readiness.await(candidate, defaultBackend, previousStatus.pid(), startingAfter);
            applySites.accept(candidateSites);
            journal.commit(candidate);
        } catch (RuntimeException failure) {
            try {
                journal.rollback(candidate);
                applySites.accept(previousSites);
            } catch (RuntimeException recoveryFailure) {
                failure.addSuppressed(recoveryFailure);
            }
            throw new IllegalStateException("Managed application update failed. "
                    + "Previous configuration remains selected.", failure);
        }
    }
}
