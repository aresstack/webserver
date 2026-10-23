package com.aresstack.webserver.infrastructure.managed;

import com.aresstack.webserver.domain.DerivedEnvironment;
import com.aresstack.webserver.domain.ManagedApplication;
import com.aresstack.webserver.domain.ManagedEnvironmentVariable;
import com.aresstack.webserver.domain.ReadinessPolicy;
import com.aresstack.webserver.domain.RestartPolicy;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Eine abgeschaltete Umgebungsvariable wird weggelassen, nicht leer gesetzt.
 *
 * <p>Das ist der Unterschied, auf den es ankommt: viele Anwendungen - Pyloros
 * eingeschlossen - behandeln eine gesetzte, leere Variable anders als eine
 * fehlende. Eine Oberflaeche, die "Enabled" nur ausgraut, waehrend der Wert im
 * Hintergrund weiter gesetzt wird, waere eine Luege.
 */
class ManagedEnvironmentAssemblyTest {

    @Test
    void aDisabledVariableIsAbsentRatherThanEmpty() {
        Map<String, String> environment = new HashMap<>();

        ManagedProcessManager.applyEnvironment(environment, application(Map.of(
                "KEEP", new ManagedEnvironmentVariable("yes", false, true),
                "DROP", new ManagedEnvironmentVariable("no", false, false))),
                Map.of(), value -> value);

        assertEquals("yes", environment.get("KEEP"));
        assertFalse(environment.containsKey("DROP"),
                "abgeschaltet heisst nicht gesetzt, nicht auf leer gesetzt");
    }

    @Test
    void aDisabledVariableAlsoOverridesTheRuntimeSuppliedEnvironment() {
        // Die Registrierungs-Credentials kommen zur Laufzeit dazu. Was der Betreiber
        // abgeschaltet hat, darf nicht durch die Hintertuer wieder auftauchen.
        Map<String, String> environment = new HashMap<>();

        ManagedProcessManager.applyEnvironment(environment, application(Map.of(
                "ARESSTACK_SCOPE_REGISTRATION_TOKEN",
                new ManagedEnvironmentVariable("", false, false))),
                Map.of("ARESSTACK_SCOPE_REGISTRATION_TOKEN", "injected"), value -> value);

        assertFalse(environment.containsKey("ARESSTACK_SCOPE_REGISTRATION_TOKEN"));
    }

    @Test
    void aSecretIsRevealedOnlyWhileItIsEnabled() {
        Map<String, String> enabled = new HashMap<>();
        ManagedProcessManager.applyEnvironment(enabled, application(Map.of(
                "TOKEN", new ManagedEnvironmentVariable("stored", true, true))),
                Map.of(), value -> "revealed:" + value);
        assertEquals("revealed:stored", enabled.get("TOKEN"));

        Map<String, String> disabled = new HashMap<>();
        ManagedProcessManager.applyEnvironment(disabled, application(Map.of(
                "TOKEN", new ManagedEnvironmentVariable("stored", true, false))),
                Map.of(), value -> {
                    throw new AssertionError("a disabled secret must not be revealed");
                });
        assertFalse(disabled.containsKey("TOKEN"));
    }

    @Test
    void configuredVariablesStillWinOverTheRuntimeSuppliedOnes() {
        Map<String, String> environment = new HashMap<>();

        ManagedProcessManager.applyEnvironment(environment, application(Map.of(
                "SERVER_PORT", new ManagedEnvironmentVariable("8081", false, true))),
                Map.of("SERVER_PORT", "9999"), value -> value);

        assertEquals("8081", environment.get("SERVER_PORT"));
    }

    @Test
    void aLegacyVariableWithoutTheFlagIsPassedOn() {
        // Alte Konfigurationen kennen das Feld nicht; sie duerfen nicht stillschweigend
        // ihre gesamte Umgebung verlieren.
        Map<String, String> environment = new HashMap<>();

        ManagedProcessManager.applyEnvironment(environment, application(Map.of(
                "LEGACY", new ManagedEnvironmentVariable("value", false))),
                Map.of(), value -> value);

        assertEquals("value", environment.get("LEGACY"));
    }

    @Test
    void aDisabledDerivedVariableIsOmittedToo() {
        Map<String, ManagedEnvironmentVariable> configured = new LinkedHashMap<>();
        configured.put(DerivedEnvironment.SERVER_PORT,
                new ManagedEnvironmentVariable("8081", false, true));
        configured.put(DerivedEnvironment.CENTRAL_OAUTH_ISSUER,
                new ManagedEnvironmentVariable("https://pyloros.aresstack.de", false, false));
        Map<String, String> environment = new HashMap<>();

        ManagedProcessManager.applyEnvironment(environment, application(configured),
                Map.of(), value -> value);

        assertTrue(environment.containsKey(DerivedEnvironment.SERVER_PORT));
        assertFalse(environment.containsKey(DerivedEnvironment.CENTRAL_OAUTH_ISSUER));
    }

    private static ManagedApplication application(
            Map<String, ManagedEnvironmentVariable> environment) {
        return new ManagedApplication("pyloros", "app.jar", "java", "", List.of(), List.of(),
                environment, false, false, RestartPolicy.defaults(), ReadinessPolicy.defaults(),
                5, 5, Boolean.TRUE);
    }
}
