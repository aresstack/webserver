package com.aresstack.webserver.infrastructure.managed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

/** Protect secrets using the current Windows user's DPAPI key, not a shared project key. */
public final class WindowsDpapiSecrets {
    private static final String PREFIX = "dpapi:";
    private static final String PROTECT_SCRIPT =
            "$ErrorActionPreference='Stop';"
            + "$bytes=[Convert]::FromBase64String([Console]::In.ReadToEnd());"
            + "$result=[Security.Cryptography.ProtectedData]::Protect($bytes,$null,"
            + "[Security.Cryptography.DataProtectionScope]::CurrentUser);"
            + "[Console]::Out.Write([Convert]::ToBase64String($result))";
    private static final String UNPROTECT_SCRIPT =
            "$ErrorActionPreference='Stop';"
            + "$bytes=[Convert]::FromBase64String([Console]::In.ReadToEnd());"
            + "$result=[Security.Cryptography.ProtectedData]::Unprotect($bytes,$null,"
            + "[Security.Cryptography.DataProtectionScope]::CurrentUser);"
            + "[Console]::Out.Write([Convert]::ToBase64String($result))";

    private WindowsDpapiSecrets() {
    }

    public static boolean isProtected(String value) {
        return value.startsWith(PREFIX);
    }

    public static String protect(String secret) {
        if (isProtected(secret)) {
            return secret;
        }
        return PREFIX + invoke(PROTECT_SCRIPT,
                Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8)));
    }

    public static String reveal(String protectedValue) {
        if (!isProtected(protectedValue)) {
            throw new IllegalArgumentException("Secret is not DPAPI protected");
        }
        byte[] bytes = Base64.getDecoder().decode(invoke(UNPROTECT_SCRIPT,
                protectedValue.substring(PREFIX.length())));
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String invoke(String script, String input) {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            throw new IllegalStateException("Protected secrets require Windows DPAPI");
        }
        try {
            Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                    "-Command", script).start();
            try (java.io.OutputStream stdin = process.getOutputStream()) {
                stdin.write(input.getBytes(StandardCharsets.US_ASCII));
            }
            String result = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.US_ASCII).trim();
            String errors = new String(process.getErrorStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            if (process.waitFor() != 0 || !errors.isBlank()) {
                throw new IllegalStateException("Windows DPAPI operation failed");
            }
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot access Windows DPAPI", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted Windows DPAPI operation", e);
        }
    }
}
