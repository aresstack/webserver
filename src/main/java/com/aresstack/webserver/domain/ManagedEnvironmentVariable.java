package com.aresstack.webserver.domain;

import java.util.Objects;

/** Preserve secret protection and whether the variable is passed to the child process. */
public record ManagedEnvironmentVariable(String value, boolean secret, Boolean enabled) {
    public ManagedEnvironmentVariable {
        Objects.requireNonNull(value, "value");
        // Treat missing values in legacy configuration as enabled.
        enabled = enabled == null ? Boolean.TRUE : enabled;
    }

    public ManagedEnvironmentVariable(String value, boolean secret) {
        this(value, secret, Boolean.TRUE);
    }
}
