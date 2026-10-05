import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.*;
import java.util.zip.*;

/** Starts each place directly from its independent extracted release, without business JARs. */
public final class IndividualPlaceReleaseCheck {
    public static void main(String[] args) throws Exception {
        Path repository = Path.of(args[0]);
        if (args.length == 2) check(repository, Integer.parseInt(args[1].substring(args[1].lastIndexOf('p') + 1)));
        else for (int n = 1; n <= 6; n++) check(repository, n);
        System.out.println("PASS: independent executable place release startup without business implementation JARs");
    }
    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private static void check(Path repository, int number) throws Exception {
        String artifact = "btsn.petrinet.places.p" + number;
        Path zip = repository.resolve(artifact + "/target/" + artifact + ".zip");
        Path root = Files.createTempDirectory("independent P" + number + " release ");
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                Path target = root.resolve(entry.getName()).normalize();
                require(target.startsWith(root), "Invalid archive path");
                if (entry.isDirectory()) Files.createDirectories(target);
                else { Files.createDirectories(target.getParent()); Files.copy(input, target); }
            }
        }
        for (int other = 1; other <= 6; other++) {
            if (other != number) require(!Files.exists(root.resolve("btsn.petrinet.places.p" + other)), "Release contains another numbered place");
        }
        Path work = root.resolve(artifact), jar = work.resolve(artifact + ".jar");
        try (JarFile input = new JarFile(jar.toFile())) {
            Attributes manifest = input.getManifest().getMainAttributes();
            require("org.btsn.handlers.ServiceLoader".equals(manifest.getValue("Main-Class")), "JAR not executable");
            for (String dependency : manifest.getValue("Class-Path").split("\\s+")) {
                require(dependency.startsWith("lib/") && Files.isRegularFile(work.resolve(dependency)), "Non-portable dependency: " + dependency);
            }
            for (JarEntry entry : Collections.list(input.entries())) {
                String name = entry.getName();
                require(!name.startsWith("org/btsn/business/") && !name.startsWith("org/btsn/services/")
                    && !name.contains("BaseStochasticPetriNetPlace") && !name.contains("BaseBusinessPetriNetPlace")
                    && !name.contains("BaseHealthcareService"), "Business code in place JAR: " + name);
                if (name.matches("org/btsn/places/P[1-6]_.*\\.class"))
                    require(name.startsWith("org/btsn/places/P" + number + "_"), "Another place class included: " + name);
            }
        }
        Path log = root.resolve("startup.log");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-jar", jar.toString(), "-version", "v001").directory(work.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
            boolean ready = false;
            while (System.nanoTime() < deadline) {
                require(process.isAlive(), "Place exited: " + log + "\n" + Files.readString(log));
                String output = Files.readString(log);
                require(!output.contains("NoClassDefFoundError") && !output.contains("ClassNotFoundException")
                    && !output.contains("Failed to create"), "Missing runtime dependency or startup error: " + log);
                if (output.contains("=== ServiceLoader Startup Complete ===")) {
                    long requested = output.lines().filter(line -> line.contains("Setting up ") && line.contains(" -> EventReactor:")).count();
                    long workers = output.lines().filter(line -> line.contains("Successfully created") && line.contains("UDP ServiceThread for port")).count();
                    long rules = output.lines().filter(line -> line.contains("Successfully created UDP RuleHandler for")).count();
                    if (requested > 0 && workers >= requested && rules >= requested) { ready = true; break; }
                }
                Thread.sleep(50);
            }
            require(ready, "Place threads did not start: " + log);
            System.out.println("PASS: P" + number + " portable JAR, own place only, no business implementations, service and rule-handler startup; " + root);
        } finally {
            process.destroy(); if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly();
        }
    }
}
