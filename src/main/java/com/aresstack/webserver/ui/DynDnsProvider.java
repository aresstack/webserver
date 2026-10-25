package com.aresstack.webserver.ui;

import java.util.List;
import java.util.Optional;

/**
 * Bekannte DynDNS-Anbieter mit ihrer Update-Adresse.
 *
 * <p>Die Update-URL von Hand zusammenzusetzen war die haeufigste Fehlerquelle: ein
 * falscher Parametername faellt erst auf, wenn nach einem IP-Wechsel das Zertifikat
 * nicht mehr erneuert wird. Wer seinen Anbieter in der Liste findet, muss nur noch
 * Zugangsdaten und Namen eintragen.
 *
 * <p>Die Vorlagen stammen aus der jeweiligen Anbieterdokumentation, nicht aus dem
 * Gedaechtnis - die Stellen sind unten vermerkt. Platzhalter sind dieselben wie bei
 * einer eigenen URL: {@code <domain> <username> <password> <ipaddr> <ip6addr>}.
 */
record DynDnsProvider(String id, String displayName, String website, String updateUrl,
                      String usernameLabel, String passwordLabel, String hint) {

    /** Freie Eingabe: die URL gehoert dann dem Benutzer. */
    static final DynDnsProvider CUSTOM = new DynDnsProvider("custom", "Custom…", "",
            "", "Username", "Password",
            "Enter the update URL of your provider. Placeholders: <domain> <username>"
                    + " <password> <ipaddr> <ip6addr>");

    /** https://desec.readthedocs.io/en/latest/dyndns/update-api.html */
    static final DynDnsProvider DESEC = new DynDnsProvider("desec", "deSEC (dedyn.io)",
            "https://desec.io/",
            "https://update.dedyn.io/?hostname=<domain>&myipv4=<ipaddr>",
            "Username (your domain)", "Token",
            "deSEC authenticates with your domain name as the user and an API token as"
                    + " the password - not your account password.");

    /** https://www.dynu.com/DynamicDNS/IP-Update-Protocol */
    static final DynDnsProvider DYNU = new DynDnsProvider("dynu", "Dynu",
            "https://www.dynu.com/en-US/ControlPanel/DDNS",
            "https://api.dynu.com/nic/update?hostname=<domain>&myip=<ipaddr>",
            "Username", "Password",
            "Dynu takes your account name and either the account password or the"
                    + " separate IP update password.");

    /** https://developer.noip.com/docs/dynamic-dns-quickstart */
    static final DynDnsProvider NOIP = new DynDnsProvider("noip", "No-IP",
            "https://www.noip.com/members/dns/",
            "https://dynupdate.no-ip.com/nic/update?hostname=<domain>&myip=<ipaddr>",
            "DDNS key username", "DDNS key password",
            "No-IP is moving from account credentials to DDNS keys - create one in the"
                    + " member area and use it here.");

    /** https://www.duckdns.org/spec.jsp */
    static final DynDnsProvider DUCKDNS = new DynDnsProvider("duckdns", "Duck DNS",
            "https://www.duckdns.org/domains",
            "https://www.duckdns.org/update?domains=<domain>&token=<password>&ip=<ipaddr>",
            "", "Token",
            "Duck DNS uses only a token, no user name. Enter the sub-name without"
                    + " .duckdns.org as the domain.");

    /** https://freedns.afraid.org/dynamic/v2/ */
    static final DynDnsProvider FREEDNS = new DynDnsProvider("freedns", "FreeDNS (afraid.org)",
            "https://freedns.afraid.org/dynamic/v2/",
            "https://freedns.afraid.org/nic/update?hostname=<domain>&myip=<ipaddr>",
            "Username", "Password",
            "This is the dyndns2 interface with your afraid.org account. The per-name"
                    + " sync tokens are an alternative - use Custom for those.");

    static List<DynDnsProvider> all() {
        return List.of(DESEC, DYNU, NOIP, DUCKDNS, FREEDNS, CUSTOM);
    }

    static DynDnsProvider byId(String id) {
        return all().stream()
                .filter(provider -> provider.id().equals(id))
                .findFirst()
                .orElse(CUSTOM);
    }

    /** Der Anbieter, dessen Vorlage zu dieser URL passt - fuer bestehende Eingaben. */
    static Optional<DynDnsProvider> matching(String updateUrl) {
        String normalized = updateUrl == null ? "" : updateUrl.trim();
        return all().stream()
                .filter(provider -> !provider.updateUrl().isEmpty())
                .filter(provider -> provider.updateUrl().equals(normalized))
                .findFirst();
    }

    boolean needsUsername() {
        return !usernameLabel.isEmpty();
    }

    boolean isCustom() {
        return CUSTOM.id().equals(id);
    }

    @Override
    public String toString() {
        return displayName;
    }
}
