package dev.codex.watchworkshop;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.stream.Stream;

/** Desktop package validation. Never executes plugin code or accesses a device. */
final class PluginPackageCheck {
    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory(Paths.get(args[1]), "package-");
        try {
            PluginRepository repository = new PluginRepository(temporary.toFile());
            PluginRepository.Pending pending = repository.stage(Files.newInputStream(Paths.get(args[0])));
            PluginManifest manifest = pending.manifest;
            System.out.println("VALID: " + manifest.id + " " + manifest.versionName
                    + " (" + manifest.versionCode + ") " + manifest.runtime);
            System.out.println("Package structure and manifest only; plugin code was not executed.");
        } finally {
            Path[] cleanup;
            try (Stream<Path> paths = Files.walk(temporary)) {
                cleanup = paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new);
            }
            for (Path path : cleanup) Files.delete(path);
        }
    }
}
