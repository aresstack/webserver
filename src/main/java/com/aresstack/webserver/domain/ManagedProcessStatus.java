package com.aresstack.webserver.domain;

import java.time.Instant;

/** Expose immutable process state without coupling the UI to infrastructure types. */
public record ManagedProcessStatus(String state, long pid, int failures,
                                   Integer lastExitCode, Instant nextStart, Instant startedAt,
                                   String jarPath) {
    public static ManagedProcessStatus stopped() {
        return new ManagedProcessStatus("STOPPED", -1, 0, null, null, null, null);
    }
}
