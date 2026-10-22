package com.aresstack.webserver.ui;

import com.aresstack.webserver.domain.ManagedApplication;
import com.aresstack.webserver.domain.ManagedProcessStatus;
import com.aresstack.webserver.domain.Site;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebServerControllerManagedDeploymentTest {

    @Test
    void changedJavaExecutableRequiresDeploymentEvenWhenJarPathIsUnchanged() {
        ManagedApplication previousApp = ManagedApplication.defaults(
                "runtime-change", "C:/services/runtime-change/app.jar");
        ManagedApplication updatedApp = withJavaExecutable(previousApp,
                "C:/missing-runtime/bin/java.exe");
        Site previous = Site.of("example.org", "http://127.0.0.1:8181")
                .withManagedApplication(previousApp);
        Site updated = previous.withManagedApplication(updatedApp);
        ManagedProcessStatus running = new ManagedProcessStatus(
                "RUNNING", 12345, 0, null, null, Instant.now(), previousApp.jarPath());

        assertTrue(WebServerController.requiresManagedDeployment(
                previous, updated, running, true));
    }

    @Test
    void changedRuntimeRequiresDeploymentForAnAlreadyRunningManualApplication() {
        ManagedApplication previousApp = withAutostart(
                ManagedApplication.defaults("manual-runtime", "C:/services/manual/app.jar"), false);
        ManagedApplication updatedApp = withJavaExecutable(previousApp,
                "C:/missing-runtime/bin/java.exe");
        Site previous = Site.of("example.org", "http://127.0.0.1:8181")
                .withManagedApplication(previousApp);
        Site updated = previous.withManagedApplication(updatedApp);
        ManagedProcessStatus running = new ManagedProcessStatus(
                "RUNNING", 12346, 0, null, null, Instant.now(), previousApp.jarPath());

        assertTrue(WebServerController.requiresManagedDeployment(
                previous, updated, running, true));
    }

    @Test
    void disablingAutostartMustNotStopARunningApplication() {
        ManagedApplication previousApp = ManagedApplication.defaults(
                "autostart-off", "C:/services/autostart/app.jar");
        ManagedApplication updatedApp = withAutostart(previousApp, false);
        Site previous = Site.of("example.org", "http://127.0.0.1:8181")
                .withManagedApplication(previousApp);
        Site updated = previous.withManagedApplication(updatedApp);
        ManagedProcessStatus running = new ManagedProcessStatus(
                "RUNNING", 12347, 0, null, null, Instant.now(), previousApp.jarPath());

        assertFalse(WebServerController.requiresManagedDeployment(
                previous, updated, running, true));
    }

    @Test
    void enablingAutostartMustNotRedeployAnAlreadyRunningApplication() {
        ManagedApplication previousApp = withAutostart(ManagedApplication.defaults(
                "autostart-on", "C:/services/autostart/app.jar"), false);
        ManagedApplication updatedApp = withAutostart(previousApp, true);
        Site previous = Site.of("example.org", "http://127.0.0.1:8181")
                .withManagedApplication(previousApp);
        Site updated = previous.withManagedApplication(updatedApp);
        ManagedProcessStatus running = new ManagedProcessStatus(
                "RUNNING", 12348, 0, null, null, Instant.now(), previousApp.jarPath());

        assertFalse(WebServerController.requiresManagedDeployment(
                previous, updated, running, true));
    }

    @Test
    void enablingAutostartDeploysAStoppedApplication() {
        ManagedApplication previousApp = withAutostart(ManagedApplication.defaults(
                "autostart-start", "C:/services/autostart/app.jar"), false);
        ManagedApplication updatedApp = withAutostart(previousApp, true);
        Site previous = Site.of("example.org", "http://127.0.0.1:8181")
                .withManagedApplication(previousApp);
        Site updated = previous.withManagedApplication(updatedApp);

        assertTrue(WebServerController.requiresManagedDeployment(
                previous, updated, ManagedProcessStatus.stopped(), true));
    }

    @Test
    void disabledApplicationNeverTriggersDeployment() {
        ManagedApplication previousApp = ManagedApplication.defaults(
                "disabled", "C:/services/disabled/app.jar");
        ManagedApplication updatedApp = withJavaExecutable(previousApp,
                "C:/missing-runtime/bin/java.exe").withEnabled(false);
        Site previous = Site.of("example.org", "http://127.0.0.1:8181")
                .withManagedApplication(previousApp);
        Site updated = previous.withManagedApplication(updatedApp);
        ManagedProcessStatus running = new ManagedProcessStatus(
                "RUNNING", 12349, 0, null, null, Instant.now(), previousApp.jarPath());

        assertFalse(WebServerController.requiresManagedDeployment(
                previous, updated, running, true));
    }

    private static ManagedApplication withJavaExecutable(ManagedApplication source, String executable) {
        return new ManagedApplication(source.id(), source.jarPath(), executable,
                source.workingDirectory(), source.jvmArguments(), source.applicationArguments(),
                source.environment(), source.startAutomatically(), source.restartAutomatically(),
                source.restartPolicy(), source.readinessPolicy(),
                source.stopTimeoutSeconds(), source.forceStopTimeoutSeconds());
    }

    private static ManagedApplication withAutostart(ManagedApplication source, boolean enabled) {
        return new ManagedApplication(source.id(), source.jarPath(), source.javaExecutable(),
                source.workingDirectory(), source.jvmArguments(), source.applicationArguments(),
                source.environment(), enabled, source.restartAutomatically(),
                source.restartPolicy(), source.readinessPolicy(),
                source.stopTimeoutSeconds(), source.forceStopTimeoutSeconds());
    }
}
