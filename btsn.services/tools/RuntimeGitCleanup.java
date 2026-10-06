import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

/** One-time backup/parking and restoration; never edits Java, Git's index or user source files. */
public final class RuntimeGitCleanup {
    public static final String IGNORE_MARKER = "# Runtime Git cleanup: local-only outputs";
    private final Path root;
    private final Path stateFile;

    private RuntimeGitCleanup(Path root) throws IOException {
        this.root = root.toRealPath();
        require(Files.exists(this.root.resolve(".git")) && Files.isDirectory(this.root.resolve("btsn.common.Monitor"))
                && Files.isDirectory(this.root.resolve("btsn.services")), "Not the BTSN repository: " + root);
        stateFile = this.root.resolve("btsn.services/target/runtime-git-cleanup/state.properties");
    }

    public static void main(String[] args) {
        try {
            require(args.length == 2, "Use prepare|restore|cancel <repository-root>");
            RuntimeGitCleanup cleanup = new RuntimeGitCleanup(Path.of(args[1]));
            if ("prepare".equals(args[0])) cleanup.prepare();
            else if ("restore".equals(args[0]) || "cancel".equals(args[0])) cleanup.restore("cancel".equals(args[0]));
            else throw new IOException("Unknown action: " + args[0]);
        } catch (Exception e) {
            System.err.println("CLEANUP STOPPED: " + e.getMessage());
            System.err.println("Stop Ant, service, analyser and chart runs first. Keep all backup folders.");
            System.exit(1);
        }
    }

    private void prepare() throws Exception {
        if (Files.exists(stateFile)) {
            Properties previous = read(stateFile);
            verifyState(previous);
            require(!"preparing".equals(previous.getProperty("status")), "An interrupted preparation needs the cancel target first.");
            if ("prepared".equals(previous.getProperty("status"))) {
                verifyBackup(previous);
                for (String name : roots(previous)) require(!Files.exists(root.resolve(name)), "Runtime data reappeared after preparation: " + name);
                System.out.println("BACKUP READY: " + previous.getProperty("backup"));
                return;
            }
            throw new IOException("This one-time cleanup was already " + previous.getProperty("status") + ". Keep the saved backup.");
        }
        List<String> names = discover();
        require(!names.isEmpty(), "No runtime folders found. Nothing was changed.");
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now());
        Path backup = root.getParent().resolve(root.getFileName() + "-RuntimeBackup-" + stamp + "-" + UUID.randomUUID().toString().substring(0, 8));
        Files.createDirectory(backup);
        Path snapshot = backup.resolve("snapshot");
        Files.createDirectory(snapshot);
        List<FileChannel> channels = new ArrayList<>();
        List<FileLock> locks = new ArrayList<>();
        Properties state = new Properties();
        state.setProperty("repository", root.toString());
        state.setProperty("backup", backup.toString());
        state.setProperty("root.count", Integer.toString(names.size()));
        for (int i = 0; i < names.size(); i++) state.setProperty("root." + i, names.get(i));
        try {
            // Derby holds dbex.lck while a database is open. Hold its lock throughout the verified copy.
            for (String name : names) try (Stream<Path> paths = Files.walk(root.resolve(name))) {
                for (Path p : (Iterable<Path>) paths::iterator) {
                    require(!Files.isSymbolicLink(p), "Runtime symbolic link is not supported: " + p);
                    if (!p.getFileName().toString().equalsIgnoreCase("dbex.lck")) continue;
                    FileChannel channel = FileChannel.open(p, StandardOpenOption.READ, StandardOpenOption.WRITE);
                    channels.add(channel);
                    FileLock lock = channel.tryLock();
                    require(lock != null, "Database is still in use: " + p.getParent());
                    locks.add(lock);
                }
            }
            for (String name : names) copyTree(root.resolve(name), snapshot.resolve(name));
            Map<String, String> original = fingerprints(root, names);
            require(original.equals(fingerprints(snapshot, names)), "Runtime files changed during backup. Originals have not been moved.");
            Properties manifest = new Properties();
            manifest.putAll(original);
            write(backup.resolve("sha256.properties"), manifest);
        } finally {
            for (FileLock lock : locks) lock.close();
            for (FileChannel channel : channels) channel.close();
        }
        state.setProperty("status", "preparing");
        write(stateFile, state);
        try {
            for (String name : names) move(root.resolve(name), backup.resolve("parked").resolve(name));
        } catch (Exception failure) {
            for (String name : names) {
                Path parked = backup.resolve("parked").resolve(name), original = root.resolve(name);
                if (Files.exists(parked) && !Files.exists(original)) {
                    try { move(parked, original); } catch (Exception rollback) { failure.addSuppressed(rollback); }
                }
            }
            throw new IOException("Could not park every runtime folder. A verified copy remains at " + backup + "; use cancel to recover.", failure);
        }
        state.setProperty("status", "prepared");
        write(stateFile, state);
        System.out.println("Verified and parked " + names.size() + " runtime folders outside the repository.");
        System.out.println("Source files, Eclipse project definitions and Git's index were not changed.");
        System.out.println("BACKUP READY: " + backup);
        System.out.println("Keep all runs stopped. After the final cleanup is published, pull it and select the restore target.");
    }

    private void restore(boolean cancel) throws Exception {
        require(Files.isRegularFile(stateFile), "No preparation state found; no files were changed.");
        Properties state = read(stateFile);
        verifyState(state);
        if ("restored".equals(state.getProperty("status")) || "cancelled".equals(state.getProperty("status"))) {
            System.out.println("Already " + state.getProperty("status") + "; backup retained: " + state.getProperty("backup"));
            return;
        }
        if (!cancel) require(Files.readString(root.resolve(".gitignore")).contains(IGNORE_MARKER), "Pull the final tracking cleanup before restore, or use cancel to undo preparation.");
        verifyBackup(state);
        List<String> names = roots(state);
        Path snapshot = Path.of(state.getProperty("backup")).resolve("snapshot");
        // Preflight every destination before writing any files; never overwrite new results.
        for (String name : names) {
            Path destination = root.resolve(name);
            if (Files.exists(destination)) require(fingerprints(root, Arrays.asList(name)).equals(fingerprints(snapshot, Arrays.asList(name))),
                    "Existing runtime data differs from the backup: " + name + ". Nothing was overwritten.");
        }
        Path staging = Path.of(state.getProperty("backup")).resolve("restore-" + UUID.randomUUID());
        Files.createDirectory(staging);
        for (String name : names) if (!Files.exists(root.resolve(name))) copyTree(snapshot.resolve(name), staging.resolve(name));
        for (String name : names) if (Files.exists(staging.resolve(name))) {
            require(fingerprints(staging, Arrays.asList(name)).equals(fingerprints(snapshot, Arrays.asList(name))), "Staged restoration did not match the backup: " + name);
            require(!Files.exists(root.resolve(name)), "Runtime data appeared during restoration: " + name);
            move(staging.resolve(name), root.resolve(name));
        }
        require(fingerprints(root, names).equals(fingerprints(snapshot, names)), "Restored data did not match the verified backup. Keep the backup.");
        state.setProperty("status", cancel ? "cancelled" : "restored");
        write(stateFile, state);
        System.out.println(cancel ? "PREPARATION CANCELLED: original runtime files restored." : "RESULTS RESTORED: verified runtime files are back in their original locations.");
        System.out.println("Backup retained: " + state.getProperty("backup"));
    }

    private List<String> discover() throws IOException {
        List<String> components = new ArrayList<>(Arrays.asList("btsn.common.Monitor", "btsn.common.eventgenerators"));
        for (int n = 1; n <= 6; n++) components.add("btsn.rpso.places.p" + n);
        List<String> result = new ArrayList<>();
        for (String component : components) {
            Path dir = root.resolve(component);
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> children = Files.list(dir)) {
                for (Path child : (Iterable<Path>) children::iterator) if (runtimeName(child.getFileName().toString())) {
                    require(Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS), "Expected a regular runtime directory: " + child);
                    result.add(component + "/" + child.getFileName());
                }
            }
        }
        result.sort(String::compareTo);
        return result;
    }

    private static boolean runtimeName(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.equals("serviceanalysisdatabase") || lower.equals("servicemonitordatabase") || lower.equals("processmonitordb")
                || lower.equals("chart") || lower.equals("charts") || lower.equals("workflowrunmetadata") || name.matches("RuleFolder\\.v[0-9]+");
    }

    private void verifyState(Properties state) throws IOException {
        require(root.toString().equals(state.getProperty("repository")), "Backup belongs to a different checkout.");
        Path backup = Path.of(state.getProperty("backup")).toAbsolutePath().normalize();
        require(backup.getParent().equals(root.getParent()) && backup.getFileName().toString().startsWith(root.getFileName() + "-RuntimeBackup-"), "Backup must remain beside this repository: " + backup);
        for (String name : roots(state)) {
            Path path = Path.of(name).normalize();
            require(path.getNameCount() == 2 && !path.isAbsolute() && !name.contains("..") && runtimeName(path.getFileName().toString()), "Invalid runtime root: " + name);
            String component = path.getName(0).toString();
            require(component.equals("btsn.common.Monitor") || component.equals("btsn.common.eventgenerators") || component.matches("btsn\\.rpso\\.places\\.p[1-6]"), "Invalid component: " + component);
        }
    }

    private void verifyBackup(Properties state) throws Exception {
        Path backup = Path.of(state.getProperty("backup"));
        Properties manifest = read(backup.resolve("sha256.properties"));
        require(new TreeMap<Object, Object>(manifest).equals(fingerprints(backup.resolve("snapshot"), roots(state))), "Backup checksum verification failed. No restoration was performed.");
    }

    private static List<String> roots(Properties state) throws IOException {
        int count = Integer.parseInt(state.getProperty("root.count"));
        require(count > 0 && count < 500, "Invalid runtime folder count.");
        List<String> names = new ArrayList<>();
        for (int i = 0; i < count; i++) { String name = state.getProperty("root." + i); require(name != null, "Incomplete backup state."); names.add(name); }
        return names;
    }

    private static Map<String, String> fingerprints(Path base, List<String> names) throws Exception {
        Map<String, String> values = new TreeMap<>();
        for (String name : names) {
            Path folder = base.resolve(name);
            require(Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS), "Runtime folder is missing: " + folder);
            try (Stream<Path> paths = Files.walk(folder)) {
                for (Path file : (Iterable<Path>) paths::iterator) {
                    require(!Files.isSymbolicLink(file), "Runtime symbolic link is not supported: " + file);
                    String relative = base.relativize(file).toString().replace('\\', '/');
                    if (Files.isDirectory(file)) { values.put(relative + "/", "directory"); continue; }
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    try (InputStream input = Files.newInputStream(file)) {
                        byte[] buffer = new byte[65536]; int read;
                        while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
                    }
                    StringBuilder hex = new StringBuilder(); for (byte b : digest.digest()) hex.append(String.format("%02x", b & 255));
                    values.put(relative, hex.toString());
                }
            }
        }
        return values;
    }

    private static void copyTree(Path source, Path destination) throws IOException {
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                require(!Files.isSymbolicLink(path), "Runtime symbolic link is not supported: " + path);
                Path target = destination.resolve(source.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(target);
                else { Files.createDirectories(target.getParent()); Files.copy(path, target, StandardCopyOption.COPY_ATTRIBUTES); }
            }
        }
    }

    private static void move(Path from, Path to) throws IOException {
        Files.createDirectories(to.getParent());
        try { Files.move(from, to, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(from, to); }
    }
    private static Properties read(Path file) throws IOException { Properties p = new Properties(); try (InputStream in = Files.newInputStream(file)) { p.load(in); } return p; }
    private static void write(Path file, Properties p) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), ".cleanup-", ".tmp");
        try (OutputStream out = Files.newOutputStream(temp)) { p.store(out, "Runtime cleanup recovery state; keep the external backup"); }
        try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException e) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
    }
    private static void require(boolean condition, String message) throws IOException { if (!condition) throw new IOException(message); }
}
