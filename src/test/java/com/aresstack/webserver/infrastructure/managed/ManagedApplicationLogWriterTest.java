package com.aresstack.webserver.infrastructure.managed;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ManagedApplicationLogWriterTest {
    @TempDir Path root;

    @Test
    void rotatesOutputDuringCaptureAndRetainsOnlyConfiguredArchives() throws Exception {
        Path activeLog = root.resolve("services/app.log");
        ManagedApplicationLogWriter writer = new ManagedApplicationLogWriter(8, 2);
        byte[] output = "abcdefghijklmnopqrstuv".getBytes(StandardCharsets.UTF_8);

        writer.capture(new ByteArrayInputStream(output), activeLog, ignored -> { });

        assertEquals("qrstuv", Files.readString(activeLog));
        assertEquals("ijklmnop", Files.readString(root.resolve("services/app.log.1")));
        assertEquals("abcdefgh", Files.readString(root.resolve("services/app.log.2")));
        assertFalse(Files.exists(root.resolve("services/app.log.3")));
    }

    @Test
    void rotatesAnOversizedExistingLogBeforeAppendingNewOutput() throws Exception {
        Path activeLog = root.resolve("services/app.log");
        Files.createDirectories(activeLog.getParent());
        Files.writeString(activeLog, "12345678");
        ManagedApplicationLogWriter writer = new ManagedApplicationLogWriter(8, 2);

        writer.capture(new ByteArrayInputStream("new".getBytes(StandardCharsets.UTF_8)),
                activeLog, ignored -> { });

        assertEquals("new", Files.readString(activeLog));
        assertEquals("12345678", Files.readString(root.resolve("services/app.log.1")));
    }

    @Test
    void supportsDisablingArchivedLogs() throws Exception {
        Path activeLog = root.resolve("services/app.log");
        ManagedApplicationLogWriter writer = new ManagedApplicationLogWriter(4, 0);

        writer.capture(new ByteArrayInputStream("abcdefgh".getBytes(StandardCharsets.UTF_8)),
                activeLog, ignored -> { });

        assertEquals("efgh", Files.readString(activeLog));
        assertFalse(Files.exists(root.resolve("services/app.log.1")));
    }
}
