package com.aresstack.webserver.ui;

import java.util.prefs.Preferences;

/**
 * Anwendungseinstellungen, die kein Teil der Serverkonfiguration sind
 * (z.B. ob der Server mit der Anwendung startet, DynDNS-Zugangsdaten).
 */
public final class AppPreferences {

    private static final Preferences NODE =
            Preferences.userRoot().node("com/aresstack/webserver");
    private static final String AUTOSTART_SERVER = "autostartServer";
    private static final String DYNDNS_ENABLED = "dynDnsEnabled";
    private static final String DYNDNS_UPDATE_URL = "dynDnsUpdateUrl";
    private static final String DYNDNS_DOMAIN = "dynDnsDomain";
    private static final String DYNDNS_USERNAME = "dynDnsUsername";
    private static final String DYNDNS_PASSWORD = "dynDnsPassword";
    private static final String BASE_DOMAIN = "baseDomain";
    private static final String DYNDNS_PROVIDER = "dynDnsProvider";
    private static final String OAUTH_FOR_NEW_SERVICES = "oauthForNewServices";

    private AppPreferences() {
    }

    /**
     * Die Domain, unter der neue Dienste veroeffentlicht werden.
     *
     * <p>Aus ihr und dem Dienstnamen entsteht die oeffentliche Adresse, damit man
     * {@code pyloros.aresstack.de} nicht jedes Mal tippt.
     *
     * <p>Voreinstellung {@code dedyn.io}: deSEC ist der bekannteste kostenlose
     * Anbieter, bei dem man unter dem eigenen Namen beliebige Unterdomains per CNAME
     * anlegen kann - genau das, was ein Dienst pro Subdomain braucht.
     */
    /**
     * Ob ein neuer Dienst gleich auf Central OAuth steht.
     *
     * <p>Wer seine Dienste ohnehin alle schuetzt, musste das Dropdown jedes Mal
     * umstellen - und ein vergessener Umschalter veroeffentlicht einen ungeschuetzten
     * Dienst. Voreingestellt an, weil "geschuetzt" das sicherere Versehen ist.
     */
    public static boolean oauthForNewServices() {
        return NODE.getBoolean(OAUTH_FOR_NEW_SERVICES, true);
    }

    public static void setOauthForNewServices(boolean enabled) {
        NODE.putBoolean(OAUTH_FOR_NEW_SERVICES, enabled);
    }

    /** Der zuletzt gewaehlte DynDNS-Anbieter; steuert nur die Oberflaeche. */
    public static String dynDnsProvider() {
        return NODE.get(DYNDNS_PROVIDER, "custom");
    }

    public static void setDynDnsProvider(String id) {
        NODE.put(DYNDNS_PROVIDER, id == null ? "custom" : id);
    }

    public static String baseDomain() {
        return NODE.get(BASE_DOMAIN, "dedyn.io");
    }

    public static void setBaseDomain(String domain) {
        NODE.put(BASE_DOMAIN, domain == null ? "" : domain.trim());
    }

    public static boolean autostartServer() {
        return NODE.getBoolean(AUTOSTART_SERVER, true);
    }

    public static void setAutostartServer(boolean enabled) {
        NODE.putBoolean(AUTOSTART_SERVER, enabled);
    }

    public static boolean dynDnsEnabled() {
        return NODE.getBoolean(DYNDNS_ENABLED, false);
    }

    public static void setDynDnsEnabled(boolean enabled) {
        NODE.putBoolean(DYNDNS_ENABLED, enabled);
    }

    public static String dynDnsUpdateUrl() {
        return NODE.get(DYNDNS_UPDATE_URL, "");
    }

    public static void setDynDnsUpdateUrl(String url) {
        NODE.put(DYNDNS_UPDATE_URL, url);
    }

    public static String dynDnsDomain() {
        return NODE.get(DYNDNS_DOMAIN, "");
    }

    /**
     * Die zu aktualisierenden Namen, einer pro Zeile oder durch Komma getrennt.
     *
     * <p>Ob mehr als einer noetig ist, haengt am DNS-Aufbau: zeigt eine Subdomain
     * per CNAME auf den DynDNS-Namen, genuegt dieser eine Name - der CNAME folgt
     * von selbst. Traegt man dagegen pro Dienst einen eigenen A-Record, muss auch
     * jeder einzeln aktualisiert werden.
     */
    public static java.util.List<String> dynDnsDomains() {
        return java.util.Arrays.stream(dynDnsDomain().split("[,;\\s]+"))
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .distinct()
                .toList();
    }

    public static void setDynDnsDomain(String domain) {
        NODE.put(DYNDNS_DOMAIN, domain);
    }

    public static String dynDnsUsername() {
        return NODE.get(DYNDNS_USERNAME, "");
    }

    public static void setDynDnsUsername(String username) {
        NODE.put(DYNDNS_USERNAME, username);
    }

    public static String dynDnsPassword() {
        return NODE.get(DYNDNS_PASSWORD, "");
    }

    public static void setDynDnsPassword(String password) {
        NODE.put(DYNDNS_PASSWORD, password);
    }
}
