package org.btsn.deployment;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

/** Independently running host receiver. Ant never launches or shuts down this host. */
public final class DeploymentHost implements AutoCloseable {
    private final Path root;
    private final String token;
    private final ServerSocket listener;
    private final ExecutorService clients = Executors.newCachedThreadPool();
    private final Map<String, RunningService> running = new HashMap<>();
    private static final long MAX_BYTES = 1024L * 1024 * 1024;
    private static final class RunningService {
        final String release, version; final Process process;
        RunningService(String r, String v, Process p) { release = r; version = v; process = p; }
    }
    public DeploymentHost(Path root, String address, int port, String token) throws IOException {
        if (token.isEmpty()) throw new IllegalArgumentException("A deployment token is required");
        this.root = root.toAbsolutePath(); this.token = token;
        Files.createDirectories(this.root.resolve("releases"));
        listener = new ServerSocket(); listener.bind(new InetSocketAddress(address, port));
    }
    public int port() { return listener.getLocalPort(); }
    public void serve() throws IOException {
        while (!listener.isClosed()) {
            try { Socket socket = listener.accept(); clients.execute(() -> handle(socket)); }
            catch (SocketException e) { if (!listener.isClosed()) throw e; }
        }
    }
    private void handle(Socket socket) {
        try (Socket s = socket;
             DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()))) {
            s.setSoTimeout(120000);
            String protocol = in.readUTF(), supplied = in.readUTF(), command = in.readUTF(), release = in.readUTF();
            if (!DeploymentClient.PROTOCOL.equals(protocol) || !java.security.MessageDigest.isEqual(
                    token.getBytes(java.nio.charset.StandardCharsets.UTF_8), supplied.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                out.writeBoolean(false); out.writeUTF("Deployment authentication failed"); out.flush(); return;
            }
            if (!release.matches("[a-f0-9]{64}") || !(command.equals("DEPLOY") || command.equals("START"))) {
                out.writeBoolean(false); out.writeUTF("Invalid deployment request"); out.flush(); return;
            }
            out.writeBoolean(true); out.flush();
            try {
                String result = command.equals("DEPLOY") ? deploy(in, release) : start(release, in.readUTF(), in.readUTF());
                out.writeBoolean(true); out.writeUTF(result);
            } catch (Exception e) {
                out.writeBoolean(false); out.writeUTF(e.toString().substring(0, Math.min(e.toString().length(), 2000)));
            }
            out.flush();
        } catch (Exception e) { System.err.println("Deployment request failed: " + e); }
    }
    private String deploy(DataInputStream in, String release) throws Exception {
        long size = in.readLong();
        if (size <= 0 || size > MAX_BYTES) throw new IOException("Invalid archive size");
        Path zip = Files.createTempFile(root, "upload-", ".zip");
        Path staging = Files.createTempDirectory(root, "extract-");
        try {
            try (OutputStream file = Files.newOutputStream(zip)) {
                byte[] buffer = new byte[65536]; long remaining = size;
                while (remaining > 0) {
                    int n = in.read(buffer, 0, (int)Math.min(buffer.length, remaining));
                    if (n < 0) throw new EOFException("Incomplete upload");
                    file.write(buffer, 0, n); remaining -= n;
                }
            }
            if (!DeploymentClient.digest(zip).equals(release)) throw new IOException("Archive checksum mismatch");
            extract(zip, staging);
            if (!Files.isRegularFile(staging.resolve("btsn.common/target/host-runtime/btsn-infrastructure.jar")))
                throw new IOException("Archive missing infrastructure JAR");
            synchronized (this) {
                Path destination = root.resolve("releases").resolve(release);
                if (!Files.exists(destination)) Files.move(staging, destination);
            }
            return "DEPLOYED " + release;
        } finally { Files.deleteIfExists(zip); deleteTree(staging); }
    }
    static void extract(Path zip, Path destination) throws IOException {
        long total = 0; int entries = 0;
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry; byte[] buffer = new byte[65536];
            while ((entry = in.getNextEntry()) != null) {
                if (++entries > 50000 || entry.getName().contains("\\")) throw new IOException("Invalid archive entry");
                Path file = destination.resolve(entry.getName()).normalize();
                if (!file.startsWith(destination) || file.equals(destination)) throw new IOException("Archive path escapes destination");
                if (entry.isDirectory()) Files.createDirectories(file);
                else {
                    Files.createDirectories(file.getParent());
                    try (OutputStream out = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW)) {
                        int n;
                        while ((n = in.read(buffer)) != -1) {
                            if ((total += n) > MAX_BYTES) throw new IOException("Expanded archive too large");
                            out.write(buffer, 0, n);
                        }
                    }
                }
            }
        }
    }
    private synchronized String start(String release, String component, String version) throws Exception {
        if (!(component.matches("p[1-6]") || component.equals("monitor")) || !version.matches("v[0-9]{3}"))
            throw new IOException("Invalid component/version");
        RunningService current = running.get(component);
        if (current != null && current.process.isAlive()) {
            if (current.release.equals(release) && current.version.equals(version)) return "ALREADY RUNNING " + component;
            throw new IOException(component + " is running another deployment; it was not stopped or replaced");
        }
        Path bundle = root.resolve("releases").resolve(release);
        String directory = component.equals("monitor") ? "btsn.common.Monitor" : "btsn.petrinet.places." + component;
        Path work = bundle.resolve(directory);
        Path jar = work.resolve(component.equals("monitor") ? "target/jar-runtime/btsn-monitor.jar" : "target/" + directory + ".jar");
        if (!Files.isRegularFile(jar)) throw new IOException("Deployed component JAR missing: " + component);
        List<String> cp = new ArrayList<>(); cp.add(jar.toString());
        cp.add(bundle.resolve("btsn.common/target/host-runtime/btsn-infrastructure.jar").toString());
        for (String dir : List.of("btsn.common/lib", "btsn.services/target/deployment/lib", "btsn.services/target/deployment/services")) {
            try (java.util.stream.Stream<Path> files = Files.list(bundle.resolve(dir))) {
                files.filter(p -> p.toString().endsWith(".jar")).sorted().forEach(p -> cp.add(p.toString()));
            }
        }
        Path log = root.resolve(component + ".out.txt");
        Process process = new ProcessBuilder(Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
            "-Dbtsn.common.dir=" + bundle.resolve("btsn.common"), "-cp", String.join(File.pathSeparator, cp),
            "org.btsn.handlers.ServiceLoader", work.toString(), "-version", version)
            .directory(work.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (System.nanoTime() < deadline) {
                if (!process.isAlive()) throw new IOException("Service exited with " + process.exitValue() + "; see " + log);
                String output = Files.exists(log) ? Files.readString(log) : "";
                if (output.contains("=== ServiceLoader Startup Complete ===")) {
                    if (output.matches("(?s).*Services loaded: 0.*") || output.contains("Failed to create") || output.contains("Error loading"))
                        throw new IOException("Service startup failed; see " + log);
                    {
                        long expected = output.lines().filter(line -> line.contains("Setting up ") && line.contains(" -> EventReactor:")).count();
                        long workers = output.lines().filter(line -> line.contains("Successfully created") && line.contains("UDP ServiceThread for port")).count();
                        long rules = output.lines().filter(line -> line.contains("Successfully created UDP RuleHandler for")).count();
                        if (expected > 0 && workers >= expected && rules >= expected) {
                            running.put(component, new RunningService(release, version, process));
                            return "STARTED " + component + " pid=" + process.pid() + " from received JARs; log=" + log;
                        }
                    }
                }
                Thread.sleep(100);
            }
            throw new IOException("Service startup timed out; see " + log);
        } catch (Exception e) { process.destroy(); process.waitFor(5, TimeUnit.SECONDS); if (process.isAlive()) process.destroyForcibly(); throw e; }
    }
    static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) return;
        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException { Files.delete(f); return FileVisitResult.CONTINUE; }
            public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException { if (e != null) throw e; Files.delete(d); return FileVisitResult.CONTINUE; }
        });
    }
    public synchronized void close() throws IOException {
        listener.close(); clients.shutdownNow();
        for (RunningService service : running.values()) service.process.destroy();
        for (RunningService service : running.values()) {
            try { if (!service.process.waitFor(5, TimeUnit.SECONDS)) service.process.destroyForcibly(); }
            catch (InterruptedException e) { service.process.destroyForcibly(); Thread.currentThread().interrupt(); }
        }
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("Usage: java -jar btsn-deployment-host.jar bind-address port deployment-directory token");
        DeploymentHost host = new DeploymentHost(Paths.get(args[2]), args[0], Integer.parseInt(args[1]), args[3]);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { try { host.close(); } catch (IOException e) { System.err.println(e); } }));
        System.out.println("DEPLOYMENT HOST READY port=" + host.port());
        host.serve();
    }
}
