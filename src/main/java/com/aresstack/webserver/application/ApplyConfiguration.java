package com.aresstack.webserver.application;

import com.aresstack.webserver.domain.WebServerConfiguration;

/**
 * Zentraler Use Case: validate domain → render → validate caddy → apply → persist.
 * Eine ungültige Konfiguration erreicht niemals den laufenden Caddy-Prozess.
 */
public class ApplyConfiguration {

    private final CaddyConfigurationWriter writer;
    private final CaddyAdmin admin;
    private final ConfigurationRepository repository;

    public ApplyConfiguration(CaddyConfigurationWriter writer, CaddyAdmin admin,
                              ConfigurationRepository repository) {
        this.writer = writer;
        this.admin = admin;
        this.repository = repository;
    }

    public void apply(WebServerConfiguration configuration) {
        // Aus den Service-Einstellungen ableitbare Umgebungsvariablen werden hier
        // neu berechnet, damit sie nie hinter Port, Scope oder Issuer zuruecfallen.
        configuration = com.aresstack.webserver.domain.DerivedEnvironment.refresh(configuration);
        configuration.validate();
        String caddyfile = writer.render(configuration);
        writer.validateWithCaddy(caddyfile);
        admin.loadCaddyfile(caddyfile);
        // Erst nach erfolgreichem Reload wird der neue Zustand persistiert.
        writer.persistGenerated(caddyfile);
        repository.save(configuration);
    }
}
