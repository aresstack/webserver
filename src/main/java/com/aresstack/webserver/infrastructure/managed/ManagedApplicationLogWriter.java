package com.aresstack.webserver.infrastructure.managed;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.function.Consumer;

/** Capture child output and rotate its log while the process is still running. */
final class ManagedApplicationLogWriter {
    private static final long DEFAULT_MAX_FILE_SIZE_BYTES = 10L * 1024L * 1024L;
    private static final int DEFAULT_ARCHIVE_COUNT = 5;

    private final long maxFileSizeBytes;
    private final int archiveCount;

    ManagedApplicationLogWriter() {
        this(DEFAULT_MAX_FILE_SIZE_BYTES, DEFAULT_ARCHIVE_COUNT);
    }

    ManagedApplicationLogWriter(long maxFileSizeBytes, int archiveCount) {
        if (maxFileSizeBytes < 1 || archiveCount < 0) {
            throw new IllegalArgumentException("Invalid log rotation policy");
        }
        this.maxFileSizeBytes = maxFileSizeBytes;
        this.archiveCount = archiveCount;
    }

    void capture(InputStream processOutput, Path logFile, Consumer<String> log) {
        Objects.requireNonNull(processOutput, "processOutput");
        Objects.requireNonNull(logFile, "logFile");
        Objects.requireNonNull(log, "log");

        try (InputStream source = processOutput;
             OutputStream target = new RotatingFileOutputStream(
                     logFile, maxFileSizeBytes, archiveCount)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = source.read(buffer)) != -1) {
                target.write(buffer, 0, bytesRead);
            }
        } catch (IOException e) {
            log.accept("Could not capture managed application output for " + logFile
                    + ": " + e.getMessage());
        }
    }

    private static final class RotatingFileOutputStream extends OutputStream {
        private final Path logFile;
        private final long maxFileSizeBytes;
        private final int archiveCount;
        private OutputStream output;
        private long bytesWritten;

        private RotatingFileOutputStream(Path logFile, long maxFileSizeBytes,
                                         int archiveCount) throws IOException {
            this.logFile = logFile.toAbsolutePath().normalize();
            this.maxFileSizeBytes = maxFileSizeBytes;
            this.archiveCount = archiveCount;
            Files.createDirectories(this.logFile.getParent());
            if (Files.exists(this.logFile) && Files.size(this.logFile) >= maxFileSizeBytes) {
                rotateFiles();
            }
            bytesWritten = Files.exists(this.logFile) ? Files.size(this.logFile) : 0;
            output = Files.newOutputStream(this.logFile,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }

        @Override
        public void write(int value) throws IOException {
            byte[] singleByte = {(byte) value};
            write(singleByte, 0, 1);
        }

        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            if (buffer == null) {
                throw new NullPointerException("buffer");
            }
            if (offset < 0 || length < 0 || offset + length > buffer.length) {
                throw new IndexOutOfBoundsException();
            }
            int currentOffset = offset;
            int remaining = length;
            while (remaining > 0) {
                if (bytesWritten >= maxFileSizeBytes) {
                    rotate();
                }
                int chunkLength = (int) Math.min(remaining, maxFileSizeBytes - bytesWritten);
                output.write(buffer, currentOffset, chunkLength);
                bytesWritten += chunkLength;
                currentOffset += chunkLength;
                remaining -= chunkLength;
            }
        }

        @Override
        public void flush() throws IOException {
            output.flush();
        }

        @Override
        public void close() throws IOException {
            output.close();
        }

        private void rotate() throws IOException {
            output.close();
            rotateFiles();
            output = Files.newOutputStream(logFile,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            bytesWritten = 0;
        }

        private void rotateFiles() throws IOException {
            if (archiveCount == 0) {
                Files.deleteIfExists(logFile);
                return;
            }
            for (int index = archiveCount; index >= 1; index--) {
                Path source = index == 1 ? logFile : archiveFile(index - 1);
                Path target = archiveFile(index);
                if (Files.exists(source)) {
                    Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }

        private Path archiveFile(int index) {
            return logFile.resolveSibling(logFile.getFileName() + "." + index);
        }
    }
}
