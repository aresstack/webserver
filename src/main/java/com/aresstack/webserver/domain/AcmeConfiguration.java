package com.aresstack.webserver.domain;

import java.util.Objects;

/**
 * ACME-Einstellungen; der CA-Endpunkt wird explizit gesetzt statt sich auf
 * Caddy-Defaults zu verlassen. Die E-Mail ist optional — Caddy empfiehlt sie,
 * benötigt sie aber nicht zwingend.
 */
public record AcmeConfiguration(String email, String ca) {

    public static final String LETS_ENCRYPT_PRODUCTION = "https://acme-v02.api.letsencrypt.org/directory";
    public static final String LETS_ENCRYPT_STAGING = "https://acme-staging-v02.api.letsencrypt.org/directory";

    public AcmeConfiguration {
        email = email == null ? "" : email.trim();
        Objects.requireNonNull(ca, "ca");
        // Beide Werte landen wörtlich im Caddyfile-Global-Block; Whitespace
        // innerhalb des Werts würde Tokens trennen oder Direktiven injizieren.
        if (!email.isBlank() && (!email.contains("@") || containsWhitespace(email))) {
            throw new IllegalArgumentException("Invalid ACME email: " + email);
        }
        if (!ca.startsWith("https://") || containsWhitespace(ca)) {
            throw new IllegalArgumentException("ACME CA must be an https URL without spaces: " + ca);
        }
    }

    private static boolean containsWhitespace(String value) {
        return value.chars().anyMatch(Character::isWhitespace);
    }

    public static AcmeConfiguration letsEncrypt(String email) {
        return new AcmeConfiguration(email, LETS_ENCRYPT_PRODUCTION);
    }

    public static AcmeConfiguration withoutEmail() {
        return new AcmeConfiguration("", LETS_ENCRYPT_PRODUCTION);
    }
}
