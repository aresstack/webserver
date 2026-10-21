package com.aresstack.webserver.domain;

import java.util.Objects;

/** Preserve whether a value requires Windows user-scoped secret protection. */
public record ManagedEnvironmentVariable(String value, boolean secret) {
    public ManagedEnvironmentVariable {
        Objects.requireNonNull(value, "value");
    }
}
