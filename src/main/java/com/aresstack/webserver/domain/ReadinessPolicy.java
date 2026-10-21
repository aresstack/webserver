package com.aresstack.webserver.domain;

/** Configure how to recognize a newly deployed HTTP backend as ready. */
public record ReadinessPolicy(int startupTimeoutSeconds, int pollIntervalMillis,
                              int connectionTimeoutMillis) {
    public ReadinessPolicy {
        if (startupTimeoutSeconds < 1 || pollIntervalMillis < 1 || connectionTimeoutMillis < 1) {
            throw new IllegalArgumentException("Readiness timeouts and interval must be positive");
        }
    }

    public static ReadinessPolicy defaults() {
        return new ReadinessPolicy(30, 200, 500);
    }
}
