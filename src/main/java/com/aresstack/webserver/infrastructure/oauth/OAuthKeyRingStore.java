package com.aresstack.webserver.infrastructure.oauth;

import com.aresstack.anauthentia.jwt.JwtKeyRing;
import com.aresstack.webserver.infrastructure.managed.WindowsDpapiSecrets;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Persistiert den RSA-Signatur-Schlüsselring des OAuth-Dienstes unter
 * {@code data/oauth/signing-keys.dpapi}. Der Dateiinhalt (JWKS inklusive
 * privater Parameter, erster Schlüssel = aktueller Signaturschlüssel) wird
 * unter Windows DPAPI-geschützt abgelegt.
 */
public final class OAuthKeyRingStore {

    private final Path file;

    public OAuthKeyRingStore(Path root) {
        this.file = root.resolve("data").resolve("oauth").resolve("signing-keys.dpapi");
    }

    /** Lädt den Schlüsselring oder erzeugt und persistiert einen neuen. */
    public synchronized JwtKeyRing loadOrCreate() {
        if (Files.notExists(file)) {
            JwtKeyRing ring = JwtKeyRing.generate(newKeyId());
            save(ring);
            return ring;
        }
        try {
            String contents = Files.readString(file, StandardCharsets.UTF_8);
            if (WindowsDpapiSecrets.isProtected(contents.trim())) {
                contents = WindowsDpapiSecrets.reveal(contents.trim());
            }
            JWKSet keys = JWKSet.parse(contents);
            List<JWK> list = keys.getKeys();
            if (list.isEmpty()) {
                throw new IllegalStateException("OAuth key store contains no keys");
            }
            RSAKey current = (RSAKey) list.get(0);
            List<RSAKey> retired = new ArrayList<>();
            for (int index = 1; index < list.size(); index++) {
                retired.add((RSAKey) list.get(index));
            }
            return JwtKeyRing.of(current, retired);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read OAuth signing keys " + file, e);
        } catch (java.text.ParseException e) {
            throw new IllegalStateException("Invalid OAuth signing-key store " + file, e);
        }
    }

    /** Rotiert auf einen frischen Schlüssel und persistiert das Ergebnis. */
    public synchronized JwtKeyRing rotate(JwtKeyRing ring) {
        JwtKeyRing rotated = ring.rotate(newKeyId());
        save(rotated);
        return rotated;
    }

    private void save(JwtKeyRing ring) {
        List<JWK> keys = new ArrayList<>();
        keys.add(ring.currentKey());
        keys.addAll(ring.retiredKeys());
        // toString(false) enthält bewusst die privaten Parameter — genau dieser
        // Inhalt wird DPAPI-geschützt.
        String contents = new JWKSet(keys).toString(false);
        if (isWindows()) {
            contents = WindowsDpapiSecrets.protect(contents);
        }
        try {
            Files.createDirectories(file.getParent());
            Path temp = Files.createTempFile(file.getParent(), "signing-keys", ".tmp");
            Files.writeString(temp, contents, StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot persist OAuth signing keys " + file, e);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static String newKeyId() {
        return "ares-" + UUID.randomUUID();
    }
}
