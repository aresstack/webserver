package com.aresstack.webserver.ui.publication;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Sucht installierte Java-Laufzeiten an den üblichen Orten (JAVA_HOME, PATH,
 * ~/.jdks, Herstellerverzeichnisse unter Program Files, Scoop) und liefert
 * die Pfade der gefundenen {@code java}-Executables.
 */
final class JavaRuntimeDetector {

    private JavaRuntimeDetector() {
    }

    static List<String> detect() {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String executable = windows ? "java.exe" : "java";
        Set<String> found = new LinkedHashSet<>();

        // Laufende JVM und JAVA_HOME zuerst — die wahrscheinlichsten Kandidaten.
        addExecutable(found, Path.of(System.getProperty("java.home", ""), "bin", executable));
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) {
            addExecutable(found, Path.of(javaHome, "bin", executable));
        }

        String path = System.getenv("PATH");
        if (path != null) {
            for (String entry : path.split(File.pathSeparator)) {
                if (!entry.isBlank()) {
                    addExecutable(found, Path.of(entry.trim(), executable));
                }
            }
        }

        List<Path> roots = new ArrayList<>();
        String userHome = System.getProperty("user.home", "");
        if (!userHome.isBlank()) {
            roots.add(Path.of(userHome, ".jdks"));
            roots.add(Path.of(userHome, "scoop", "apps"));
        }
        if (windows) {
            for (String programFiles : new String[]{
                    System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)")}) {
                if (programFiles == null || programFiles.isBlank()) {
                    continue;
                }
                for (String vendor : new String[]{"Java", "Eclipse Adoptium", "Zulu",
                        "Microsoft", "Amazon Corretto", "BellSoft", "SapMachine"}) {
                    roots.add(Path.of(programFiles, vendor));
                }
            }
        } else {
            roots.add(Path.of("/usr/lib/jvm"));
            roots.add(Path.of("/Library/Java/JavaVirtualMachines"));
        }
        for (Path root : roots) {
            scanInstallRoot(found, root, executable);
        }
        return List.copyOf(found);
    }

    private static void scanInstallRoot(Set<String> found, Path root, String executable) {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> children = Files.list(root)) {
            children.filter(Files::isDirectory).sorted().forEach(child -> {
                addExecutable(found, child.resolve("bin").resolve(executable));
                // macOS-Bundles und Scoop legen das JDK eine Ebene tiefer ab.
                addExecutable(found, child.resolve("Contents").resolve("Home")
                        .resolve("bin").resolve(executable));
                addExecutable(found, child.resolve("current").resolve("bin").resolve(executable));
            });
        } catch (Exception ignored) {
            // Nicht lesbare Verzeichnisse überspringen.
        }
    }

    private static void addExecutable(Set<String> found, Path candidate) {
        try {
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                found.add(candidate.toRealPath().toString());
            }
        } catch (Exception ignored) {
            // Kaputte Links o. Ä. ignorieren.
        }
    }
}
