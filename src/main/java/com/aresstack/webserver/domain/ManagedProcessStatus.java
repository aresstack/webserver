package com.aresstack.webserver.domain;

import java.time.Instant;

/** Expose process state without coupling the GUI to operating-system adapters. */
public record ManagedProcessStatus(String state, long pid, int failures,
                                   Integer lastExitCode, Instant nextStart, Instant startedAt) {
    public static ManagedProcessStatus stopped() {
        return new ManagedProcessStatus("STOPPED", -1, 0, null, null, null);
    }
}
