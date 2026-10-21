package com.aresstack.webserver.domain;

/** Define editable readiness checks for HTTP backends. */
public record ReadinessPolicy(int startupTimeoutSeconds, int pollIntervalMillis,
                              int connectionTimeoutMillis, String httpHealthPath,
                              int expectedHttpStatus) {
    public ReadinessPolicy {
        if (startupTimeoutSeconds < 1 || pollIntervalMillis < 1 || connectionTimeoutMillis < 1
                || expectedHttpStatus < 100 || expectedHttpStatus > 599
                || httpHealthPath == null || (!httpHealthPath.isEmpty()
                    && (!httpHealthPath.startsWith("/") || httpHealthPath.contains(" ")))) {
            throw new IllegalArgumentException("Invalid readiness policy");
        }
    }

    public ReadinessPolicy(int startupTimeoutSeconds, int pollIntervalMillis,
                           int connectionTimeoutMillis) {
        this(startupTimeoutSeconds, pollIntervalMillis, connectionTimeoutMillis, "", 200);
    }

    public static ReadinessPolicy defaults() {
        return new ReadinessPolicy(30, 200, 500, "", 200);
    }
}
