package com.aresstack.webserver.ui;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Die hinterlegten Anbieter-Adressen muessen stimmen.
 *
 * <p>Eine falsche Update-URL faellt nicht beim Speichern auf, sondern erst Wochen
 * spaeter: nach einem IP-Wechsel zeigt der Name ins Leere und die
 * Zertifikatserneuerung scheitert. Die Vorlagen stammen aus der jeweiligen
 * Anbieterdokumentation; dieser Test haelt sie fest, damit sie niemand beilaeufig
 * veraendert.
 */
class DynDnsProviderTest {

    @Test
    void everyProviderCarriesTheDocumentedUpdateUrl() {
        assertEquals("https://update.dedyn.io/?hostname=<domain>&myipv4=<ipaddr>",
                DynDnsProvider.DESEC.updateUrl());
        assertEquals("https://api.dynu.com/nic/update?hostname=<domain>&myip=<ipaddr>",
                DynDnsProvider.DYNU.updateUrl());
        assertEquals("https://dynupdate.no-ip.com/nic/update?hostname=<domain>&myip=<ipaddr>",
                DynDnsProvider.NOIP.updateUrl());
        assertEquals("https://www.duckdns.org/update?domains=<domain>&token=<password>"
                + "&ip=<ipaddr>", DynDnsProvider.DUCKDNS.updateUrl());
        assertEquals("https://freedns.afraid.org/nic/update?hostname=<domain>&myip=<ipaddr>",
                DynDnsProvider.FREEDNS.updateUrl());
    }

    @Test
    void everyTemplateUsesOnlyKnownPlaceholdersAndCarriesTheDomain() {
        for (DynDnsProvider provider : DynDnsProvider.all()) {
            String url = provider.updateUrl();
            if (url.isEmpty()) {
                continue;
            }
            assertTrue(url.contains("<domain>"),
                    provider.displayName() + " must substitute the name being updated");
            assertTrue(url.startsWith("https://"),
                    provider.displayName() + " must not send credentials unencrypted");
            String leftovers = url.replace("<domain>", "").replace("<username>", "")
                    .replace("<password>", "").replace("<ipaddr>", "").replace("<ip6addr>", "");
            assertFalse(leftovers.contains("<"),
                    provider.displayName() + " uses an unknown placeholder: " + url);
        }
    }

    @Test
    void duckDnsNeedsNoUsernameButTheOthersDo() {
        assertFalse(DynDnsProvider.DUCKDNS.needsUsername(),
                "Duck DNS authenticates with a token only");
        assertTrue(DynDnsProvider.DUCKDNS.updateUrl().contains("<password>"),
                "so the token has to travel in the URL");

        for (DynDnsProvider provider : List.of(DynDnsProvider.DESEC, DynDnsProvider.DYNU,
                DynDnsProvider.NOIP, DynDnsProvider.FREEDNS)) {
            assertTrue(provider.needsUsername(), provider.displayName());
        }
    }

    @Test
    void everyProviderExplainsItsCredentialsAndLinksSomewhere() {
        for (DynDnsProvider provider : DynDnsProvider.all()) {
            assertFalse(provider.hint().isBlank(), provider.displayName() + " needs a hint");
            assertFalse(provider.passwordLabel().isBlank(), provider.displayName());
            if (!provider.isCustom()) {
                assertTrue(provider.website().startsWith("https://"),
                        provider.displayName() + " needs a link to its control panel");
            }
        }
    }

    @Test
    void anExistingUrlFindsItsProviderAgain() {
        // Sonst stuende der Dialog nach einem Update auf "Custom", obwohl alles
        // eingerichtet ist.
        assertEquals(Optional.of(DynDnsProvider.DESEC),
                DynDnsProvider.matching(DynDnsProvider.DESEC.updateUrl()));
        assertEquals(Optional.of(DynDnsProvider.DUCKDNS),
                DynDnsProvider.matching("  " + DynDnsProvider.DUCKDNS.updateUrl() + "  "));
        assertEquals(Optional.empty(),
                DynDnsProvider.matching("https://example.org/update?x=<domain>"));
        assertEquals(Optional.empty(), DynDnsProvider.matching(""));
    }

    @Test
    void anUnknownIdFallsBackToCustomRatherThanFailing() {
        assertEquals(DynDnsProvider.CUSTOM, DynDnsProvider.byId("does-not-exist"));
        assertEquals(DynDnsProvider.CUSTOM, DynDnsProvider.byId(null));
        assertEquals(DynDnsProvider.DESEC, DynDnsProvider.byId("desec"));
    }

    @Test
    void customCarriesNoUrlSoNothingIsOverwritten() {
        assertTrue(DynDnsProvider.CUSTOM.isCustom());
        assertTrue(DynDnsProvider.CUSTOM.updateUrl().isEmpty());
    }
}
