package com.aresstack.webserver.domain;

/** Calculate retry delays without a permanent failure state unless explicitly configured. */
public record RestartPolicy(long initialDelaySeconds, double multiplier,
                            long maxDelaySeconds, long resetAfterSeconds,
                            int maximumAttempts) {
    public RestartPolicy {
        if (initialDelaySeconds < 0 || !Double.isFinite(multiplier) || multiplier < 1.0
                || maxDelaySeconds < initialDelaySeconds || resetAfterSeconds < 0
                || maximumAttempts < 0) {
            throw new IllegalArgumentException("Invalid restart policy");
        }
    }

    public static RestartPolicy defaults() {
        return new RestartPolicy(1, 2.0, 300, 300, 0);
    }

    public long delayAfter(int failures) {
        if (failures < 1) {
            throw new IllegalArgumentException("Failures must be positive");
        }
        double delay = initialDelaySeconds * Math.pow(multiplier, failures - 1);
        return !Double.isFinite(delay) || delay >= maxDelaySeconds
                ? maxDelaySeconds : (long) Math.ceil(delay);
    }

    public boolean permits(int failures) {
        return maximumAttempts == 0 || failures <= maximumAttempts;
    }
}
