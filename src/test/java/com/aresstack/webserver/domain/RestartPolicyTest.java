package com.aresstack.webserver.domain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RestartPolicyTest {
    @Test
    void defaultsNeverStopRetryingAndCapExponentialBackoff() {
        RestartPolicy policy = RestartPolicy.defaults();
        assertEquals(1, policy.delayAfter(1));
        assertEquals(2, policy.delayAfter(2));
        assertEquals(4, policy.delayAfter(3));
        assertEquals(300, policy.delayAfter(1000));
        assertTrue(policy.permits(1000));
    }

    @Test
    void respectsEditableAttemptLimit() {
        RestartPolicy policy = new RestartPolicy(3, 2, 60, 120, 4);
        assertTrue(policy.permits(4));
        assertFalse(policy.permits(5));
        assertEquals(24, policy.delayAfter(4));
    }

    @Test
    void rejectsUnsafeConfiguration() {
        assertThrows(IllegalArgumentException.class,
                () -> new RestartPolicy(10, 0.5, 60, 100, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new RestartPolicy(10, 2, 5, 100, 0));
    }
}
