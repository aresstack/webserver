package com.aresstack.webserver.domain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReadinessPolicyTest {
    @Test
    void suppliesEditableDefaultTimeouts() {
        ReadinessPolicy defaults = ReadinessPolicy.defaults();
        assertTrue(defaults.startupTimeoutSeconds() > 0);
        assertTrue(defaults.pollIntervalMillis() > 0);
        assertTrue(defaults.connectionTimeoutMillis() > 0);
    }

    @Test
    void rejectsNonPositiveValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new ReadinessPolicy(0, 100, 100));
        assertThrows(IllegalArgumentException.class,
                () -> new ReadinessPolicy(10, 0, 100));
        assertThrows(IllegalArgumentException.class,
                () -> new ReadinessPolicy(10, 100, 0));
    }
}
