package com.aresstack.webserver.infrastructure.managed;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import static org.junit.jupiter.api.Assertions.*;

class WindowsDpapiSecretsTest {
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void encryptsSecretsForTheCurrentWindowsUser() {
        String secret = "sample-credential-with-special-ä-characters";
        String protectedValue = WindowsDpapiSecrets.protect(secret);
        assertTrue(WindowsDpapiSecrets.isProtected(protectedValue));
        assertNotEquals(secret, protectedValue);
        assertFalse(protectedValue.contains(secret));
        assertEquals(secret, WindowsDpapiSecrets.reveal(protectedValue));
    }
}
