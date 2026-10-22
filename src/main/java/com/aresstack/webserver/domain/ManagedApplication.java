package com.aresstack.webserver.domain;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Optional process definition attached to a published local backend. */
public record ManagedApplication(
        String id,
        String jarPath,
        String javaExecutable,
        String workingDirectory,
        List<String> jvmArguments,
        List<String> applicationArguments,
        Map<String, ManagedEnvironmentVariable> environment,
        boolean startAutomatically,
        boolean restartAutomatically,
        RestartPolicy restartPolicy,
        ReadinessPolicy readinessPolicy,
        int stopTimeoutSeconds, int forceStopTimeoutSeconds,
        Boolean enabled) {

    public ManagedApplication {
        // Nachträglich eingeführte Felder fehlen in älteren Konfigurationen:
        // enabled → aktiviert, readinessPolicy → Defaults, forceStopTimeout →
        // stopTimeout. Laden darf daran niemals scheitern.
        enabled = enabled == null ? Boolean.TRUE : enabled;
        readinessPolicy = readinessPolicy == null ? ReadinessPolicy.defaults() : readinessPolicy;
        forceStopTimeoutSeconds = forceStopTimeoutSeconds < 1
                ? stopTimeoutSeconds : forceStopTimeoutSeconds;
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(jarPath, "jarPath");
        Objects.requireNonNull(javaExecutable, "javaExecutable");
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        Objects.requireNonNull(restartPolicy, "restartPolicy");
        Objects.requireNonNull(readinessPolicy, "readinessPolicy");
        jvmArguments = List.copyOf(jvmArguments);
        applicationArguments = List.copyOf(applicationArguments);
        environment = Map.copyOf(environment);
        if (!id.matches("[a-zA-Z0-9-]{1,64}") || jarPath.isBlank()
                || javaExecutable.isBlank() || stopTimeoutSeconds < 1 || forceStopTimeoutSeconds < 1) {
            throw new IllegalArgumentException("Invalid managed application settings");
        }
        for (String key : environment.keySet()) {
            if (!key.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException("Invalid environment variable name: " + key);
            }
        }
    }

    public ManagedApplication(String id, String jarPath, String javaExecutable,
                              String workingDirectory, List<String> jvmArguments,
                              List<String> applicationArguments,
                              Map<String, ManagedEnvironmentVariable> environment,
                              boolean startAutomatically, boolean restartAutomatically,
                              RestartPolicy restartPolicy, int stopTimeoutSeconds) {
        this(id, jarPath, javaExecutable, workingDirectory, jvmArguments,
                applicationArguments, environment, startAutomatically, restartAutomatically,
                restartPolicy, ReadinessPolicy.defaults(), stopTimeoutSeconds, stopTimeoutSeconds);
    }

    public ManagedApplication(String id, String jarPath, String javaExecutable,
                              String workingDirectory, List<String> jvmArguments,
                              List<String> applicationArguments,
                              Map<String, ManagedEnvironmentVariable> environment,
                              boolean startAutomatically, boolean restartAutomatically,
                              RestartPolicy restartPolicy, ReadinessPolicy readinessPolicy,
                              int stopTimeoutSeconds) {
        this(id, jarPath, javaExecutable, workingDirectory, jvmArguments,
                applicationArguments, environment, startAutomatically, restartAutomatically,
                restartPolicy, readinessPolicy, stopTimeoutSeconds, stopTimeoutSeconds);
    }

    public ManagedApplication(String id, String jarPath, String javaExecutable,
                              String workingDirectory, List<String> jvmArguments,
                              List<String> applicationArguments,
                              Map<String, ManagedEnvironmentVariable> environment,
                              boolean startAutomatically, boolean restartAutomatically,
                              RestartPolicy restartPolicy, ReadinessPolicy readinessPolicy,
                              int stopTimeoutSeconds, int forceStopTimeoutSeconds) {
        this(id, jarPath, javaExecutable, workingDirectory, jvmArguments,
                applicationArguments, environment, startAutomatically, restartAutomatically,
                restartPolicy, readinessPolicy, stopTimeoutSeconds, forceStopTimeoutSeconds,
                Boolean.TRUE);
    }

    public ManagedApplication withJarPath(String installedJar) {
        return new ManagedApplication(id, installedJar, javaExecutable, workingDirectory,
                jvmArguments, applicationArguments, environment, startAutomatically,
                restartAutomatically, restartPolicy, readinessPolicy,
                stopTimeoutSeconds, forceStopTimeoutSeconds, enabled);
    }

    public ManagedApplication withEnvironment(Map<String, ManagedEnvironmentVariable> newEnvironment) {
        return new ManagedApplication(id, jarPath, javaExecutable, workingDirectory,
                jvmArguments, applicationArguments, newEnvironment, startAutomatically,
                restartAutomatically, restartPolicy, readinessPolicy,
                stopTimeoutSeconds, forceStopTimeoutSeconds, enabled);
    }

    public ManagedApplication withEnabled(boolean newEnabled) {
        return new ManagedApplication(id, jarPath, javaExecutable, workingDirectory,
                jvmArguments, applicationArguments, environment, startAutomatically,
                restartAutomatically, restartPolicy, readinessPolicy,
                stopTimeoutSeconds, forceStopTimeoutSeconds, newEnabled);
    }

    /**
     * Nur diese Einstellungen erfordern einen neuen Prozess. Die übrigen
     * Optionen (startAutomatically, restartAutomatically, Policies) wirken auf
     * künftige Starts und dürfen eine laufende Anwendung nicht anfassen.
     * Ausnahme: {@code enabled=false} nimmt die Anwendung bewusst aus der
     * Prozessverwaltung und stoppt sie dabei.
     */
    public boolean sameLaunchSettings(ManagedApplication other) {
        return id.equals(other.id())
                && jarPath.equals(other.jarPath())
                && javaExecutable.equals(other.javaExecutable())
                && workingDirectory.equals(other.workingDirectory())
                && jvmArguments.equals(other.jvmArguments())
                && applicationArguments.equals(other.applicationArguments())
                && environment.equals(other.environment());
    }

    /** {@code false}: Konfiguration bleibt erhalten, aber der Prozess wird nicht verwaltet. */
    public boolean isEnabled() {
        return !Boolean.FALSE.equals(enabled);
    }

    public static String defaultJavaExecutable() {
        String executable = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "java.exe" : "java";
        return java.nio.file.Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }

    public static ManagedApplication defaults(String id, String jarPath) {
        return new ManagedApplication(id, jarPath, defaultJavaExecutable(), "", List.of(), List.of(),
                Map.of(), true, true, RestartPolicy.defaults(), ReadinessPolicy.defaults(), 10, 10);
    }
}
