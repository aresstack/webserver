package com.aresstack.webserver.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Was sich aus den Service-Einstellungen ergibt, wird nicht zweimal gepflegt.
 *
 * <p>Port, oeffentliche Adresse, Modus, Issuer und Dienst-Scope mussten bisher von
 * Hand als Umgebungsvariable eingetragen werden, obwohl sie bereits in der Site
 * stehen. Aenderte man den Port im Service, blieb die Umgebungsvariable auf dem
 * alten Wert - der Dienst startete dann mit einer Konfiguration, die der
 * Oberflaeche widersprach.
 */
class DerivedEnvironmentTest {

    private static final String HOST = "pyloros.aresstack.de";

    @Test
    void derivesEveryValueFromAProtectedSite() {
        Map<String, String> derived = DerivedEnvironment.valuesFor(site(true, List.of("pyloros")));

        assertEquals("pyloros", derived.get(DerivedEnvironment.REQUIRED_SCOPE));
        assertEquals("central-oauth", derived.get(DerivedEnvironment.SECURITY_MODE));
        assertEquals("8081", derived.get(DerivedEnvironment.SERVER_PORT));
        assertEquals("https://" + HOST, derived.get(DerivedEnvironment.PUBLIC_ORIGIN));
        assertEquals("https://" + HOST, derived.get(DerivedEnvironment.CENTRAL_OAUTH_ISSUER));
    }

    @Test
    void anUnprotectedSiteGetsNeitherScopeNorIssuer() {
        // Ein erfundener Issuer waere schlechter als keiner.
        Map<String, String> derived = DerivedEnvironment.valuesFor(site(true, null));

        assertEquals("none", derived.get(DerivedEnvironment.SECURITY_MODE));
        assertFalse(derived.containsKey(DerivedEnvironment.REQUIRED_SCOPE));
        assertFalse(derived.containsKey(DerivedEnvironment.CENTRAL_OAUTH_ISSUER));
        assertEquals("8081", derived.get(DerivedEnvironment.SERVER_PORT));
    }

    @Test
    void theOriginFollowsTheHttpsSetting() {
        assertEquals("http://" + HOST,
                DerivedEnvironment.valuesFor(site(false, List.of("pyloros")))
                        .get(DerivedEnvironment.PUBLIC_ORIGIN));
    }

    @Test
    void refreshReplacesAStaleValueInsteadOfKeepingIt() {
        // Genau der Fall, der die doppelte Pflege gefaehrlich machte: der Port im
        // Service wurde geaendert, die Umgebungsvariable blieb auf dem alten Wert.
        Site stale = site(true, List.of("pyloros")).withManagedApplication(
                application(Map.of(DerivedEnvironment.SERVER_PORT,
                        new ManagedEnvironmentVariable("9999", false))));

        Site refreshed = DerivedEnvironment.refresh(stale);

        assertEquals("8081", refreshed.managedApplication().orElseThrow()
                .environment().get(DerivedEnvironment.SERVER_PORT).value());
    }

    @Test
    void refreshKeepsTheEnabledDecision() {
        Site site = site(true, List.of("pyloros")).withManagedApplication(
                application(Map.of(DerivedEnvironment.SERVER_PORT,
                        new ManagedEnvironmentVariable("8081", false, false))));

        Site refreshed = DerivedEnvironment.refresh(site);

        assertFalse(refreshed.managedApplication().orElseThrow()
                .environment().get(DerivedEnvironment.SERVER_PORT).enabled(),
                "eine abgeschaltete Variable darf durch das Auffrischen nicht zurueckkehren");
        assertTrue(refreshed.managedApplication().orElseThrow()
                .environment().get(DerivedEnvironment.PUBLIC_ORIGIN).enabled(),
                "nicht angefasste Variablen bleiben aktiv");
    }

    @Test
    void refreshKeepsCustomVariablesUntouched() {
        Site site = site(true, List.of("pyloros")).withManagedApplication(
                application(Map.of("MY_OWN", new ManagedEnvironmentVariable("value", true, true))));

        Map<String, ManagedEnvironmentVariable> environment =
                DerivedEnvironment.refresh(site).managedApplication().orElseThrow().environment();

        assertEquals("value", environment.get("MY_OWN").value());
        assertTrue(environment.get("MY_OWN").secret());
        assertTrue(environment.containsKey(DerivedEnvironment.SERVER_PORT));
    }

    @Test
    void refreshDropsADerivedNameThatNoLongerApplies() {
        // Central OAuth abgeschaltet: der Issuer gilt nicht mehr und darf nicht als
        // Altlast stehen bleiben.
        Site site = site(true, null).withManagedApplication(
                application(Map.of(DerivedEnvironment.CENTRAL_OAUTH_ISSUER,
                        new ManagedEnvironmentVariable("https://" + HOST, false))));

        Map<String, ManagedEnvironmentVariable> environment =
                DerivedEnvironment.refresh(site).managedApplication().orElseThrow().environment();

        assertFalse(environment.containsKey(DerivedEnvironment.CENTRAL_OAUTH_ISSUER));
        assertEquals("none", environment.get(DerivedEnvironment.SECURITY_MODE).value());
    }

    @Test
    void refreshIsANoOpForASiteWithoutAManagedApplication() {
        Site plain = site(true, List.of("pyloros"));

        assertEquals(plain, DerivedEnvironment.refresh(plain));
    }

    @Test
    void theManagedNamesAreRecognised() {
        for (String name : DerivedEnvironment.NAMES) {
            assertTrue(DerivedEnvironment.isDerived(name), name);
        }
        assertFalse(DerivedEnvironment.isDerived("MY_OWN"));
        assertFalse(DerivedEnvironment.isDerived("server_port"), "exakte Schreibweise");
    }

    private static Site site(boolean https, List<String> scopes) {
        Upstream upstream = Upstream.parse("http://127.0.0.1:8081");
        Site site = new Site(new DomainName(HOST), Optional.of(upstream), List.of(), https,
                Optional.empty());
        return scopes == null ? site : site.withAuthentication(new SiteAuthentication(scopes));
    }

    private static ManagedApplication application(
            Map<String, ManagedEnvironmentVariable> environment) {
        return new ManagedApplication("pyloros", "app.jar", "java", "", List.of(), List.of(),
                environment, false, false, RestartPolicy.defaults(), ReadinessPolicy.defaults(),
                5, 5, Boolean.TRUE);
    }
}
