package com.aresstack.webserver.infrastructure.managed;

import com.aresstack.webserver.domain.ManagedApplication;
import com.aresstack.webserver.domain.ReadinessPolicy;
import com.aresstack.webserver.domain.RestartPolicy;
import com.aresstack.webserver.domain.Site;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Run the lifecycle manager in a separate JVM and halt it at a controlled crash point. */
public final class ManagedProcessCrashHarness {

    private ManagedProcessCrashHarness() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Path previousJar = Path.of(args[1]);
        Path candidateJar = Path.of(args[2]);
        Path childPidFile = Path.of(args[3]);
        String crashPoint = args[4];

        ManagedApplication previous = application(previousJar, childPidFile);
        ManagedApplication candidate = application(candidateJar, childPidFile);
        Site previousSite = Site.of("example.org", "http://127.0.0.1:8181")
                .withManagedApplication(previous);
        Site candidateSite = Site.of("example.org", "http://127.0.0.1:8181")
                .withManagedApplication(candidate);

        Files.createDirectories(root.resolve("config"));
        Files.writeString(root.resolve("config/webserver.json"), "previous configuration");

        ManagedProcessManager.ProcessLaunchHooks hooks = new ManagedProcessManager.ProcessLaunchHooks() {
            @Override
            public void afterIdentityReserved(ManagedApplication app) {
                if ("after-reservation".equals(crashPoint)) {
                    Runtime.getRuntime().halt(71);
                }
            }

            @Override
            public void afterProcessStarted(ManagedApplication app, Process process) {
                if ("after-process-start".equals(crashPoint)) {
                    Runtime.getRuntime().halt(72);
                }
            }
        };
        ManagedProcessManager manager = new ManagedProcessManager(root, ignored -> { },
                ManagedProcessManager::writeIdentityAtomically,
                (handle, seconds) -> !handle.isAlive(), hooks);
        new FileManagedUpdateJournal(root, manager).prepare(previousSite, candidateSite);
        manager.reconcile(List.of(candidateSite));

        Thread.sleep(30000L);
    }

    private static ManagedApplication application(Path jar, Path childPidFile) {
        return new ManagedApplication("crash-app", jar.toString(),
                ManagedApplication.defaultJavaExecutable(), "", List.of(),
                List.of(childPidFile.toString()), Map.of(), true, true,
                RestartPolicy.defaults(), ReadinessPolicy.defaults(), 2, 2);
    }
}
