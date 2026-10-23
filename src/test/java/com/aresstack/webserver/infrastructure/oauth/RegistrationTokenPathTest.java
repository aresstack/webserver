package com.aresstack.webserver.infrastructure.oauth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The registration token file must stay inside the registration directory.
 *
 * <p>The host came straight from the request body of {@code POST /internal/scopes}
 * and was checked against a character blocklist whose backslash entry was escaped
 * wrongly - {@code contains("\\\\")} looks for TWO backslashes, so a single one
 * passed. {@link Path#resolve(String)} returns the other path unchanged when it is
 * absolute, so a host of {@code c:\\users\\x} produced {@code c:\\users\\x.token} and
 * left the data directory entirely. The endpoint reached this before comparing the
 * caller's token, which made it a file-creation primitive for any local process.
 */
class RegistrationTokenPathTest {

    @TempDir Path root;

    private static final String BACKSLASH = String.valueOf((char) 92);

    @Test
    void acceptsAPlainDnsHostAndStaysInsideTheRegistrationDirectory() {
        OAuthRuntime runtime = new OAuthRuntime(root, message -> { }, 0);

        Path tokenFile = runtime.registrationTokenFile("pyloros.aresstack.de");

        assertEquals(registrationDirectory(), tokenFile.getParent());
        assertEquals("pyloros.aresstack.de.token", tokenFile.getFileName().toString());
    }

    @Test
    void refusesAnAbsolutePathWithADriveLetter() {
        OAuthRuntime runtime = new OAuthRuntime(root, message -> { }, 0);

        for (String host : List.of(
                "c:" + BACKSLASH + "users" + BACKSLASH + "angel" + BACKSLASH + "evil",
                "c:/users/angel/evil",
                "/etc/cron.d/evil",
                BACKSLASH + BACKSLASH + "server" + BACKSLASH + "share" + BACKSLASH + "evil")) {
            assertThrows(IllegalArgumentException.class,
                    () -> runtime.registrationTokenFile(host),
                    "must refuse an absolute path: " + host);
        }
    }

    @Test
    void refusesSeparatorsTraversalAndOtherNonDnsCharacters() {
        OAuthRuntime runtime = new OAuthRuntime(root, message -> { }, 0);

        for (String host : List.of(
                "sub" + BACKSLASH + "nested",
                "sub/nested",
                ".." + BACKSLASH + "..",
                "../../escaped",
                "host" + BACKSLASH,
                "host:8080",
                "ho st",
                "host" + BACKSLASH + "..",
                "-leading-dash",
                "trailing-dash-",
                ".leading-dot",
                "host..double-dot",
                "host\u0000.token",
                "")) {
            assertThrows(IllegalArgumentException.class,
                    () -> runtime.registrationTokenFile(host),
                    "must refuse: '" + host + "'");
        }
    }

    @Test
    void refusesAnOverlongName() {
        OAuthRuntime runtime = new OAuthRuntime(root, message -> { }, 0);

        assertThrows(IllegalArgumentException.class,
                () -> runtime.registrationTokenFile("a".repeat(254)));
    }

    @Test
    void creatingATokenForARefusedHostWritesNoFileAnywhere() throws Exception {
        OAuthRuntime runtime = new OAuthRuntime(root, message -> { }, 0);
        Path outside = root.resolve("outside");
        Files.createDirectories(outside);
        String host = outside.toString().replace("/", BACKSLASH) + BACKSLASH + "evil";

        assertThrows(IllegalArgumentException.class, () -> runtime.registrationToken(host));

        assertTrue(isEmptyDirectory(outside),
                "a refused host must not create a file outside the registration directory");
        assertTrue(!Files.exists(registrationDirectory())
                        || isEmptyDirectory(registrationDirectory()),
                "and none inside it either");
    }

    @Test
    void anAcceptedHostGetsAStableTokenInsideTheDirectory() throws Exception {
        OAuthRuntime runtime = new OAuthRuntime(root, message -> { }, 0);

        String first = runtime.registrationToken("pyloros.aresstack.de");
        String second = runtime.registrationToken("pyloros.aresstack.de");

        assertEquals(first, second, "the token must not be rotated on every read");
        assertTrue(first.length() >= 40, "the token must be long enough to be unguessable");
        Path expected = registrationDirectory().resolve("pyloros.aresstack.de.token");
        assertEquals(first, Files.readString(expected).trim());
    }

    @Test
    void theHostIsCaseInsensitiveButNeverEscapesTheDirectory() {
        OAuthRuntime runtime = new OAuthRuntime(root, message -> { }, 0);

        assertEquals(runtime.registrationTokenFile("pyloros.aresstack.de"),
                runtime.registrationTokenFile("  PYLOROS.AresStack.DE  "));
    }

    private Path registrationDirectory() {
        return root.resolve("data").resolve("oauth").resolve("registration")
                .toAbsolutePath().normalize();
    }

    private static boolean isEmptyDirectory(Path directory) throws java.io.IOException {
        try (var entries = Files.list(directory)) {
            return entries.findAny().isEmpty();
        }
    }
}
