package org.btsn.launch;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.*;

/** Local executable bootstrap only. No upload endpoint or remote startup protocol. */
public final class ManualServiceLauncher {
    private static final String INFRASTRUCTURE = "btsn-infrastructure.jar";
    private static final String BUSINESS = "btsn-business-services.jar";
    public static void run(String mode, String[] args) throws Exception {
        if (args.length < 1 || args.length > 2 || !(args[0].matches("p[1-6]") || args[0].equals("monitor")))
            throw new IllegalArgumentException("Usage: java -jar " + (mode.equals("business") ? BUSINESS : INFRASTRUCTURE) + " p1|p2|p3|p4|p5|p6|monitor [v001]");
        String component = args[0], version = args.length == 2 ? args[1] : "v001";
        if (!version.matches("v[0-9]{3}")) throw new IllegalArgumentException("Version must be v followed by three digits");
        if (mode.equals("business") && component.equals("monitor"))
            throw new IllegalArgumentException("Monitor is started by the infrastructure JAR");
        Path infrastructure = Path.of(ManualServiceLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path root = infrastructure.getParent(), business = root.resolve(BUSINESS);
        if (!Files.isRegularFile(business)) throw new IOException("Place both JARs together: missing " + business);
        Path runtime = unpackRuntime(infrastructure, root);
        String directory = component.equals("monitor") ? "btsn.common.Monitor" : "btsn.petrinet.places." + component;
        Path instanceRoot = root.resolve(".run").resolve(mode);
        Files.createDirectories(instanceRoot);
        Path work = instanceRoot.resolve(directory);
        Files.createDirectories(work);
        // File locks prevent duplicate manual starts. Configuration is copied only
        // once so later rule deployments and recorded data survive subsequent starts.
        try (FileChannel channel = FileChannel.open(work.resolve("launcher.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock = channel.tryLock();
            if (lock == null) throw new IOException(mode + " " + component + " is already running");
            try (FileLock ignored = lock) {
                copyMissing(root.resolve("config/btsn.common"), instanceRoot.resolve("btsn.common"));
                copyMissing(root.resolve("config").resolve(directory), work);
                List<String> cp = new ArrayList<>();
                cp.add(runtime.resolve("components/" + component + ".jar").toString());
                cp.add(runtime.resolve("common.jar").toString()); cp.add(business.toString());
                try (java.util.stream.Stream<Path> files = Files.list(runtime.resolve("lib"))) {
                    files.filter(p -> p.toString().endsWith(".jar")).sorted().forEach(p -> cp.add(p.toString()));
                }
                String filter = mode.equals("business") ? "P" + component.substring(1) + "_Place" : "Service";
                Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Dbtsn.common.dir=" + instanceRoot.resolve("btsn.common"), "-cp", String.join(File.pathSeparator, cp),
                    "org.btsn.handlers.ServiceLoader", work.toString(), "-version", version, "-service", filter)
                    .directory(work.toFile()).inheritIO().start();
                Thread hook = new Thread(() -> stop(process), "Stop-" + mode + "-" + component);
                Runtime.getRuntime().addShutdownHook(hook);
                System.out.println("Starting " + mode + " " + component + " from the two-JAR distribution; runtime directory=" + work);
                try {
                    int exit = process.waitFor();
                    if (exit != 0) throw new IOException("Service launcher exited with status " + exit);
                } finally {
                    stop(process);
                    try { Runtime.getRuntime().removeShutdownHook(hook); } catch (IllegalStateException shutdown) { }
                }
            }
        }
    }
    private static void stop(Process process) {
        process.destroy();
        try { if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly(); }
        catch (InterruptedException e) { process.destroyForcibly(); Thread.currentThread().interrupt(); }
    }
    private static Path unpackRuntime(Path jar, Path root) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(jar)) {
            byte[] b = new byte[65536]; int n; while ((n = in.read(b)) != -1) md.update(b, 0, n);
        }
        StringBuilder hash = new StringBuilder(); for (byte b : md.digest()) hash.append(String.format("%02x", b & 255));
        Path parent = root.resolve(".runtime"); Files.createDirectories(parent);
        Path destination = parent.resolve(hash.toString());
        try (FileChannel channel = FileChannel.open(parent.resolve("unpack.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            if (Files.isRegularFile(destination.resolve("ready"))) return destination;
            Files.createDirectories(destination);
            try (JarFile input = new JarFile(jar.toFile())) {
                Enumeration<JarEntry> entries = input.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry.isDirectory() || !entry.getName().startsWith("runtime/")) continue;
                    Path target = destination.resolve(entry.getName().substring("runtime/".length())).normalize();
                    if (!target.startsWith(destination)) throw new IOException("Invalid embedded runtime path");
                    Files.createDirectories(target.getParent());
                    try (InputStream in = input.getInputStream(entry)) { Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING); }
                }
            }
            Files.writeString(destination.resolve("ready"), hash.toString());
        }
        return destination;
    }
    private static void copyMissing(Path source, Path target) throws IOException {
        if (!Files.isDirectory(source)) throw new IOException("Missing distribution configuration: " + source);
        try (java.util.stream.Stream<Path> paths = Files.walk(source)) {
            for (Path file : (Iterable<Path>)paths::iterator) {
                Path destination = target.resolve(source.relativize(file));
                if (Files.isDirectory(file)) Files.createDirectories(destination);
                else if (!Files.exists(destination)) {
                    Path temporary = Files.createTempFile(destination.getParent(), "copy-", ".tmp");
                    try {
                        Files.copy(file, temporary, StandardCopyOption.REPLACE_EXISTING);
                        try { Files.move(temporary, destination); } catch (FileAlreadyExistsException concurrentStart) { }
                    } finally { Files.deleteIfExists(temporary); }
                }
            }
        }
    }
}
