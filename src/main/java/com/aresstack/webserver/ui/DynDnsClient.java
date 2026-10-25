package com.aresstack.webserver.ui;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

/**
 * Optionaler DynDNS-Client nach Router-Prinzip: eine Update-URL mit den
 * Platzhaltern {@code <domain> <username> <password> <ipaddr> <ip6addr>},
 * die bei Adressaenderung aufgerufen wird - einmal je konfiguriertem Namen. Ist die Funktion in den
 * Einstellungen deaktiviert, passiert hier nichts — z.B. wenn der Router
 * das Update bereits übernimmt.
 */
public final class DynDnsClient {

    public record Result(boolean success, String message) {
    }

    private static volatile String lastSentIp;
    private static volatile String lastUpdateInfo = "—";

    private DynDnsClient() {
    }

    public static String lastUpdateInfo() {
        return lastUpdateInfo;
    }

    /** Fuehrt sofort ein Update aus (auch fuer "Test configuration"). */
    public static Result updateNow() {
        String template = AppPreferences.dynDnsUpdateUrl().trim();
        if (template.isEmpty()) {
            return new Result(false, "No update URL configured.");
        }
        java.util.List<String> domains = AppPreferences.dynDnsDomains();
        if (domains.isEmpty()) {
            return new Result(false, "No domain configured.");
        }
        String ip = ConnectivityCheck.publicAddress();
        if (ip == null) {
            return new Result(false, "The public IPv4 address could not be determined.");
        }

        Result result = updateAll(template, domains, ip);
        if (result.success()) {
            // Nur bei vollstaendigem Erfolg merken, sonst wird der naechste Lauf
            // uebersprungen und ein fehlgeschlagener Name bliebe auf der alten IP.
            lastSentIp = ip;
        }
        return result;
    }

    /**
     * Ein Aufruf je Name.
     *
     * <p>Manche Anbieter nehmen auch eine Kommaliste in einem Request entgegen - wer
     * das will, traegt sie als einen Eintrag ein. Ein einzelner Fehlschlag macht den
     * ganzen Lauf zum Fehlschlag, damit der naechste Durchgang es erneut versucht.
     */
    static Result updateAll(String template, java.util.List<String> domains, String ip) {
        java.util.List<String> reports = new java.util.ArrayList<>();
        int updated = 0;
        for (String domain : domains) {
            Result single = update(template, domain, ip);
            if (single.success()) {
                updated++;
            }
            reports.add(domain + ": " + single.message());
        }
        lastUpdateInfo = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
                + " · " + ip + " · " + updated + "/" + domains.size() + " updated";
        return new Result(updated == domains.size(), String.join("\n", reports));
    }

    /** Ein einzelner Update-Aufruf fuer genau einen Namen. */
    private static Result update(String template, String domain, String ip) {
        String url = template
                .replace("<domain>", encode(domain))
                .replace("<username>", encode(AppPreferences.dynDnsUsername()))
                .replace("<password>", encode(AppPreferences.dynDnsPassword()))
                .replace("<ipaddr>", ip)
                .replace("<ip6addr>", "");
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .GET();
            String username = AppPreferences.dynDnsUsername();
            if (!username.isBlank()) {
                String credentials = username + ":" + AppPreferences.dynDnsPassword();
                request.header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
            }
            HttpResponse<String> response = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build()
                    .send(request.build(), HttpResponse.BodyHandlers.ofString());
            boolean success = response.statusCode() >= 200 && response.statusCode() < 300;
            String body = response.body() == null ? "" : response.body().trim();
            return new Result(success, "HTTP " + response.statusCode()
                    + (body.isEmpty() ? "" : " — " + body.substring(0, Math.min(body.length(), 200))));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, "Interrupted.");
        } catch (Exception e) {
            return new Result(false,
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    /** Hintergrundlauf: nur updaten, wenn sich die öffentliche Adresse geändert hat. */
    public static void updateIfAddressChanged(UserLog log) {
        if (!AppPreferences.dynDnsEnabled()) {
            return;
        }
        String ip = ConnectivityCheck.publicAddress();
        if (ip == null || ip.equals(lastSentIp)) {
            return;
        }
        Result result = updateNow();
        log.info(result.success()
                ? "Dynamic DNS updated to " + ip
                : "Dynamic DNS update failed: " + result.message());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
