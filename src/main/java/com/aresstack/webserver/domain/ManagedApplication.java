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
        int stopTimeoutSeconds) {

    public ManagedApplication {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(jarPath, "jarPath");
        Objects.requireNonNull(javaExecutable, "javaExecutable");
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        Objects.requireNonNull(restartPolicy, "restartPolicy");
        jvmArguments = List.copyOf(jvmArguments);
        applicationArguments = List.copyOf(applicationArguments);
        environment = Map.copyOf(environment);
        if (!id.matches("[a-zA-Z0-9-]{1,64}") || jarPath.isBlank()
                || javaExecutable.isBlank() || stopTimeoutSeconds < 1) {
            throw new IllegalArgumentException("Invalid managed application settings");
        }
        for (String key : environment.keySet()) {
            if (!key.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException("Invalid environment variable name: " + key);
            }
        }
    }

    public ManagedApplication withJarPath(String installedJar) {
        return new ManagedApplication(id, installedJar, javaExecutable, workingDirectory,
                jvmArguments, applicationArguments, environment, startAutomatically,
                restartAutomatically, restartPolicy, stopTimeoutSeconds);
    }

    public static ManagedApplication defaults(String id, String jarPath) {
        return new ManagedApplication(id, jarPath, "java", "", List.of(), List.of(),
                Map.of(), true, true, RestartPolicy.defaults(), 10);
    }
}
