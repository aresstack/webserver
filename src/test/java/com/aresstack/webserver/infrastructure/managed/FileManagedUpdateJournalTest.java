package com.aresstack.webserver.infrastructure.managed;

import com.aresstack.webserver.application.ManagedApplicationRuntime;
import com.aresstack.webserver.domain.ManagedApplication;
import com.aresstack.webserver.domain.ManagedProcessStatus;
import com.aresstack.webserver.domain.ReadinessPolicy;
import com.aresstack.webserver.domain.Site;
import com.aresstack.webserver.domain.Upstream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FileManagedUpdateJournalTest {
    @TempDir Path root;

    @Test
    void recoversPreviousConfigurationAfterInterruptedUpdate() throws Exception {
        Path config = root.resolve("config/webserver.json");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "previous configuration");
        StubRuntime runtime = new StubRuntime();
        FileManagedUpdateJournal journal = new FileManagedUpdateJournal(root, runtime);

        journal.prepare(previousSite(), candidateSite());
        Files.writeString(config, "unfinished replacement");
        journal.recover();

        assertEquals("previous configuration", Files.readString(config));
        assertEquals("new.jar", Path.of(runtime.terminatedJar).getFileName().toString());
        assertFalse(Files.exists(root.resolve(
                "data/service-updates/service-1/transaction.properties")));
    }

    @Test
    void completedUpdateSurvivesAnotherStartup() throws Exception {
        Path config = root.resolve("config/webserver.json");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "previous configuration");
        StubRuntime runtime = new StubRuntime();
        FileManagedUpdateJournal journal = new FileManagedUpdateJournal(root, runtime);

        journal.prepare(previousSite(), candidateSite());
        Files.writeString(config, "verified replacement");
        journal.commit(candidateSite());
        journal.recover();

        assertEquals("verified replacement", Files.readString(config));
        assertNull(runtime.terminatedJar);
    }

    @Test
    void pendingIdentityDoesNotAbortJournalRecoveryOrStartAnUnverifiedChild() throws Exception {
        Path config = root.resolve("config/webserver.json");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "previous configuration");
        ManagedProcessManager runtime = new ManagedProcessManager(root, ignored -> { });
        FileManagedUpdateJournal journal = new FileManagedUpdateJournal(root, runtime);
        Site previous = previousSite();
        Site candidate = candidateSite();

        journal.prepare(previous, candidate);
        Files.writeString(config, "unfinished replacement");
        Path identity = root.resolve("data/service-runtime/service-1.txt");
        Files.createDirectories(identity.getParent());
        Files.writeString(identity, "PENDING\n"
                + candidate.managedApplication().orElseThrow().jarPath());

        assertDoesNotThrow(journal::recover);
        assertEquals("previous configuration", Files.readString(config));
        assertTrue(Files.exists(identity), "Preserve the uncertain child-process marker");
        assertFalse(Files.exists(root.resolve(
                "data/service-updates/service-1/transaction.properties")));

        runtime.reconcile(List.of(previous));
        assertEquals("PENDING_RECOVERY", runtime.status("service-1").state());
        assertDoesNotThrow(() -> runtime.resolvePendingIdentity("service-1"));
        assertFalse(Files.exists(identity));
        runtime.stopAll();
    }

    private Site previousSite() {
        return Site.of("example.org", "http://localhost:8101")
                .withManagedApplication(ManagedApplication.defaults("service-1",
                        root.resolve("previous.jar").toString()));
    }

    private Site candidateSite() {
        return Site.of("example.org", "http://localhost:8101")
                .withManagedApplication(ManagedApplication.defaults("service-1",
                        root.resolve("new.jar").toString()));
    }

    private static final class StubRuntime implements ManagedApplicationRuntime {
        private String terminatedJar;

        @Override
        public ManagedProcessStatus status(String id) {
            return ManagedProcessStatus.stopped();
        }

        @Override
        public void reconcile(List<Site> sites) {
        }

        @Override
        public void stageUpdate(List<Site> sites, String oldId, Upstream backend,
                                ReadinessPolicy policy) {
        }

        @Override
        public void terminateInterruptedUpdate(String id, String jar,
                                               int graceful, int forced) {
            terminatedJar = jar;
        }
    }
}
