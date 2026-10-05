import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.*;
import java.util.zip.*;

/** Tests actual java -jar startup from a copied ZIP without development runtime dependencies. */
public final class ManualDistributionCheck {
    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private static String java() { return Path.of(System.getProperty("java.home"), "bin", "java").toString(); }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("btsn manual distribution ");
        unzip(Path.of(args[0]), root);
        try (java.util.stream.Stream<Path> files = Files.walk(root)) {
            require(files.filter(p -> p.toString().endsWith(".jar")).count() == 2, "ZIP must contain exactly two JAR files");
        }
        Path business = root.resolve("btsn-business-services.jar"), infrastructure = root.resolve("btsn-infrastructure.jar");
        try (JarFile jar = new JarFile(business.toFile())) {
            require(jar.getManifest().getMainAttributes().getValue("Main-Class").equals("org.btsn.launch.BusinessServicesMain"), "Business JAR not executable");
            require(jar.stream().noneMatch(e -> e.getName().startsWith("org/btsn/handlers/")), "Business JAR contains handlers");
            require(jar.getJarEntry("org/btsn/business/financial/ValidationService.class") != null, "Business implementation missing");
        }
        try (JarFile jar = new JarFile(infrastructure.toFile())) {
            require(jar.getManifest().getMainAttributes().getValue("Main-Class").equals("org.btsn.launch.InfrastructureMain"), "Infrastructure JAR not executable");
            require(jar.stream().noneMatch(e -> e.getName().startsWith("org/btsn/business/")), "Infrastructure JAR contains business implementations");
        }
        List<Process> programs = new ArrayList<>();
        try {
            for (String component : List.of("p1", "p2", "p3", "p4", "p5", "p6", "monitor")) {
                Path adminLog = root.resolve(component + "-infrastructure.log");
                Process admin = start(infrastructure, component, adminLog); programs.add(admin);
                awaitWorkers(admin, adminLog, component, "infrastructure", true);
                if (!component.equals("monitor")) {
                    Path businessLog = root.resolve(component + "-business.log");
                    Process worker = start(business, component, businessLog); programs.add(worker);
                    awaitWorkers(worker, businessLog, component, "business", hasActiveBusiness(root, component));
                    require(!Files.readString(businessLog).contains("Setting up P" + component.substring(1) + "_CollectorService"), "Business launcher started collector");
                    invocationChecks(root, component, Path.of(args[1]).toAbsolutePath());
                    require(!Files.readString(adminLog).contains("Setting up P" + component.substring(1) + "_Place."), "Infrastructure launcher started business worker");
                }
                System.out.println("PASS: manual executable startup " + component);
            }
            Path duplicateLog = root.resolve("duplicate.log");
            Process duplicate = start(business, "p1", duplicateLog);
            require(duplicate.waitFor(10, TimeUnit.SECONDS) && duplicate.exitValue() != 0, "Duplicate startup accepted");
            require(Files.readString(duplicateLog).contains("already running"), "Duplicate startup did not report its cause");
            System.out.println("PASS: two executable JARs, copied ZIP, paths with spaces, business/admin separation, Monitor startup, six invocation regressions and duplicate-start protection");
        } finally {
            for (Process p : programs) p.destroy();
            for (Process p : programs) if (!p.waitFor(10, TimeUnit.SECONDS)) p.destroyForcibly();
            System.out.println("Manual distribution test directory: " + root);
        }
    }
    private static Process start(Path jar, String component, Path log) throws IOException {
        return new ProcessBuilder(java(), "-jar", jar.toString(), component).directory(jar.getParent().toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }
    private static boolean hasActiveBusiness(Path root, String component) throws IOException {
        String service = "P" + component.substring(1) + "_Place";
        java.util.regex.Pattern fact = java.util.regex.Pattern.compile("<Rel>activeService</Rel>\\s*<Ind>" + service + "</Ind>");
        try (java.util.stream.Stream<Path> files = Files.walk(root.resolve("config/btsn.common/RuleBase"))) {
            for (Path file : (Iterable<Path>)files.filter(Files::isRegularFile)::iterator) {
                if (fact.matcher(Files.readString(file)).find()) return true;
            }
        }
        return false;
    }
    private static void awaitWorkers(Process p, Path log, String component, String mode, boolean expectWorkers) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            require(p.isAlive(), "Launcher exited: " + log + "\n" + Files.readString(log));
            String output = Files.readString(log);
            if (output.contains("=== ServiceLoader Startup Complete ===")) {
                require(!output.contains("Failed to create") && !output.contains("Error loading"), "Startup error: " + log);
                long expected = output.lines().filter(line -> line.contains("Setting up ") && line.contains(" -> EventReactor:")).count();
                long started = output.lines().filter(line -> line.contains("Successfully created") && line.contains("UDP ServiceThread for port")).count();
                if (expected > 0 && started >= expected) return;
                if (expected == 0 && !expectWorkers) {
                    System.out.println("PASS: " + component + " has no bound business operation; no worker invented");
                    return;
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("No workers started: " + component + " " + mode + "; " + log);
    }
    private static void invocationChecks(Path root, String component, Path checks) throws Exception {
        Path runtime;
        try (java.util.stream.Stream<Path> dirs = Files.list(root.resolve(".runtime"))) {
            runtime = dirs.filter(Files::isDirectory).findFirst().orElseThrow();
        }
        Path common = root.resolve(".run/infrastructure/btsn.common");
        List<String> cp = new ArrayList<>(List.of(checks.toString(), runtime.resolve("components/" + component + ".jar").toString(),
            runtime.resolve("common.jar").toString(), root.resolve("btsn-business-services.jar").toString()));
        try (java.util.stream.Stream<Path> files = Files.list(runtime.resolve("lib"))) {
            files.filter(p -> p.toString().endsWith(".jar")).sorted().forEach(p -> cp.add(p.toString()));
        }
        try (URLClassLoader loader = new URLClassLoader(cp.stream().skip(1).map(path -> {
                try { return Path.of(path).toUri().toURL(); } catch (Exception e) { throw new RuntimeException(e); }
            }).toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
            for (String name : List.of("org.btsn.business.financial.ValidationService", "org.btsn.business.financial.CreditCheckService",
                    "org.btsn.services.StochasticPlaceService", "org.btsn.business.BaseBusinessService")) {
                Class<?> type = Class.forName(name, false, loader);
                require(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).equals(root.resolve("btsn-business-services.jar")), "Wrong business origin: " + name);
                require(Collections.list(loader.getResources(name.replace('.', '/') + ".class")).size() == 1, "Duplicate business class: " + name);
            }
            require(Path.of(Class.forName("org.btsn.handlers.ServiceHelper", false, loader).getProtectionDomain().getCodeSource().getLocation().toURI())
                .equals(runtime.resolve("components/" + component + ".jar")), "Wrong helper origin");
        }
        // Fixture rules are written separately from the live service directories.
        Path fixture = Files.createTempDirectory(root, "invocation-fixture-");
        Process check = new ProcessBuilder(java(), "-cp", String.join(File.pathSeparator, cp),
            "org.btsn.invocation.BusinessCapabilityResolverTest", common.toString(), "org.btsn.places.P" + component.substring(1) + "_Place")
            .directory(fixture.toFile()).inheritIO().start();
        require(check.waitFor() == 0, "Two-JAR invocation checks failed: " + component);
    }
    private static void unzip(Path zip, Path root) throws IOException {
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                Path target = root.resolve(entry.getName()).normalize();
                require(target.startsWith(root), "Invalid ZIP path");
                if (entry.isDirectory()) Files.createDirectories(target);
                else { Files.createDirectories(target.getParent()); Files.copy(in, target); }
            }
        }
    }
}
