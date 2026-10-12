package org.btsn.deployment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

/**
 * Lays out everything one host needs to run on another machine, which reaches the rest of the
 * deployment only through UDP: the host JAR and its libraries, the business service JARs placed on
 * that node (kept as separate JARs, outside the host JAR), the master ruleset and bindings generated
 * for this launcher's deployment (the same files the local hosts read), the host's own configuration,
 * and launch scripts. The Ant target zips the folder.
 *
 * Arguments (name=value): node, version, launcher, runtime (the launcher's runtime folder),
 * source (repository root), bundle (service deployment bundle), infrastructureJar, libs,
 * libExcludes, out.
 */
public final class HostPackage {
    /** Folders of the launcher's generated btsn.common that a host reads (rules, bindings, contracts, placement). */
    static final String[] COMMON_FOLDERS = {"RuleBase", "RulePayLoad", "ServiceAttributeBindings", "BusinessServiceDefinitions",
        "ProcessDefinitionFolder", "InfrastructureDefinitionFolder", "ServiceDeploymentFolder", "PayLoad"};
    /** A host's own configuration folders, as the launcher copies them for local hosts. */
    static final String[] HOST_FOLDERS = {"ServiceLoaderQueries", "resources", "PayLoad", "TokenFormats", "EventTriggeringFile"};

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && "digest".equals(args[0])) {
            // The launcher prints this before a distributed run, to compare with each package's README.
            System.out.println("Master ruleset " + rulesetDigest(Path.of(args[1]), new StringBuilder()));
            return;
        }
        Map<String, String> a = new TreeMap<>();
        for (String arg : args) {
            int eq = arg.indexOf('=');
            if (eq < 1) throw new IllegalArgumentException("Expected name=value: " + arg);
            a.put(arg.substring(0, eq), arg.substring(eq + 1));
        }
        Path folder = build(a.get("node"), a.get("version"), a.get("launcher"), Path.of(a.get("runtime")), Path.of(a.get("source")),
            Path.of(a.get("bundle")), Path.of(a.get("infrastructureJar")), Path.of(a.get("libs")), Path.of(a.get("libExcludes")), Path.of(a.get("out")));
        System.out.println("Host package folder: " + folder);
    }

    public static Path build(String nodeName, String version, String launcher, Path runtime, Path source, Path bundle,
                             Path infrastructureJar, Path libs, Path libExcludes, Path out) throws Exception {
        String node = nodeName.toUpperCase(Locale.ROOT);
        require(node.matches("P\\d+"), "Host node is P1 ... P6: " + nodeName);
        require(version != null && version.matches("v\\d+"), "Rule version is v001, v002, ...: " + version);
        Path common = runtime.resolve("btsn.common");
        DeploymentConfiguration config = new DeploymentConfiguration(common);
        JSONObject physical = config.node(node);
        String channel = DeploymentConfiguration.text(physical, "channel"), address = DeploymentConfiguration.text(physical, "address");
        String hostProject = "btsn.rpso.places." + node.toLowerCase(Locale.ROOT);
        Path hostJar = source.resolve(hostProject).resolve("target").resolve(hostProject + ".jar");
        require(Files.isRegularFile(hostJar), "Host JAR not built: " + hostJar);
        require(Files.isRegularFile(infrastructureJar), "Infrastructure JAR not built: " + infrastructureJar);

        Path pkg = out.resolve(launcher + "-" + node);
        deleteTree(pkg);
        Files.createDirectories(pkg);

        // The master ruleset and everything else the host reads from ../btsn.common.
        for (String folder : COMMON_FOLDERS) copyTree(common.resolve(folder), pkg.resolve("btsn.common").resolve(folder));
        require(Files.isRegularFile(pkg.resolve("btsn.common/RuleBase/Generated/InfrastructureDeployment.ruleml.xml")),
            "The launcher's runtime has no generated deployment rules; prepare the runtime first");
        // The host's working folder.
        Path work = pkg.resolve(hostProject);
        Files.createDirectories(work);
        for (String folder : HOST_FOLDERS) copyTree(source.resolve(hostProject).resolve(folder), work.resolve(folder));
        require(Files.isDirectory(work.resolve("ServiceLoaderQueries")), "No ServiceLoaderQueries for " + hostProject);

        // Class path, in the order the local launcher uses: infrastructure, libraries, service libraries, services, host.
        List<String> classpath = new ArrayList<>();
        classpath.add(copyJar(infrastructureJar, pkg, "jars/host"));
        Set<String> excluded = new LinkedHashSet<>();
        if (Files.isRegularFile(libExcludes)) for (String line : Files.readAllLines(libExcludes)) if (!line.isBlank()) excluded.add(line.trim());
        try (Stream<Path> jars = Files.list(libs)) {
            for (Path jar : (Iterable<Path>)jars.filter(p -> p.toString().endsWith(".jar")).sorted()::iterator)
                if (!excluded.contains(jar.getFileName().toString())) classpath.add(copyJar(jar, pkg, "jars/lib"));
        }
        try (Stream<Path> jars = Files.list(bundle.resolve("lib"))) {
            for (Path jar : (Iterable<Path>)jars.filter(p -> p.toString().endsWith(".jar")).sorted()::iterator)
                classpath.add(copyJar(jar, pkg, "jars/service-lib"));
        }
        List<String> services = new ArrayList<>();
        for (String jar : serviceJars(config, node, bundle)) {
            Path file = bundle.resolve(jar);
            require(Files.isRegularFile(file), "Service JAR missing from the bundle: " + file);
            Path target = pkg.resolve("jars").resolve(jar);
            Files.createDirectories(target.getParent());
            Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
            classpath.add("jars/" + jar.replace('\\', '/'));
            services.add(jar.replace('\\', '/'));
        }
        classpath.add(copyJar(hostJar, pkg, "jars/host"));

        writeScripts(pkg, hostProject, version, classpath);
        String digest = writeRulesetDigest(pkg);
        writeReadme(pkg, node, hostProject, version, launcher, channel, address, services, ports(pkg.resolve("btsn.common"), config, node), digest);
        System.out.println(node + " -> " + address + " (" + channel + "), rules " + version + ", master ruleset " + digest.substring(0, 16)
            + (services.isEmpty() ? ", no business services" : ", services " + services));
        return pkg;
    }

    /** The service JARs (relative to the bundle) for every business capability the deployment places on this node. */
    static List<String> serviceJars(DeploymentConfiguration config, String node, Path bundle) throws Exception {
        JSONArray index = DeploymentConfiguration.array(DeploymentConfiguration.read(bundle.resolve("deployment-index.json")), "services");
        Set<String> jars = new LinkedHashSet<>();
        for (Object item : DeploymentConfiguration.array(config.serviceDeployment, "capabilities")) {
            JSONObject placement = (JSONObject)item;
            if (!node.equals(DeploymentConfiguration.text(placement, "node"))) continue;
            String service = DeploymentConfiguration.text(placement, "service"), operation = DeploymentConfiguration.text(placement, "operation");
            String jar = null;
            for (Object entry : index) {
                JSONObject e = (JSONObject)entry;
                if (service.equals(e.get("service")) && (operation.equals(e.get("operation")) || jar == null)) jar = String.valueOf(e.get("jar"));
            }
            require(jar != null, "The service bundle has no JAR for " + service + "." + operation + " (placed on " + node + ")");
            jars.add(jar);
        }
        return new ArrayList<>(jars);
    }

    /** UDP ports this host listens on: for each operation, its event port and its rule port. */
    static Map<String, String> ports(Path common, DeploymentConfiguration config, String node) throws IOException {
        Map<String, String> ports = new TreeMap<>();
        List<Path> files = new ArrayList<>();
        files.add(common.resolve(DeploymentConfiguration.text(config.profile, "deploymentRules")));
        for (Object f : DeploymentConfiguration.array(config.profile, "directServiceRules")) files.add(common.resolve(f.toString()));
        Pattern atom = Pattern.compile("<Rel>\\s*activeService\\s*</Rel>\\s*<Ind>\\s*(" + node + "_\\w+)\\s*</Ind>\\s*<Ind>\\s*([^<]*?)\\s*</Ind>"
            + "\\s*<Ind>\\s*ip(\\d+)\\s*</Ind>\\s*<Ind>\\s*(\\d+)\\s*</Ind>");
        for (Path file : files) {
            if (!Files.isRegularFile(file)) continue;
            Matcher m = atom.matcher(Files.readString(file));
            while (m.find()) {
                int channel = Integer.parseInt(m.group(3)), base = Integer.parseInt(m.group(4));
                ports.put(m.group(1) + "." + m.group(2), (10000 + channel * 1000 + base) + " and " + (20000 + channel * 1000 + base));
            }
        }
        return ports;
    }

    private static String copyJar(Path jar, Path pkg, String folder) throws IOException {
        Path target = pkg.resolve(folder).resolve(jar.getFileName().toString());
        Files.createDirectories(target.getParent());
        Files.copy(jar, target, StandardCopyOption.REPLACE_EXISTING);
        return folder + "/" + jar.getFileName();
    }

    private static void writeScripts(Path pkg, String hostProject, String version, List<String> classpath) throws IOException {
        StringBuilder unix = new StringBuilder(), windows = new StringBuilder();
        for (String entry : classpath) {
            unix.append(unix.length() == 0 ? "" : ":").append("../").append(entry);
            windows.append(windows.length() == 0 ? "" : ";").append("..\\").append(entry.replace('/', '\\'));
        }
        Files.writeString(pkg.resolve("launch.sh"), "#!/bin/sh\n"
            + "# Starts " + hostProject + " with the rules and services packaged beside it. Extra arguments go to the ServiceLoader.\n"
            + "cd \"$(dirname \"$0\")/" + hostProject + "\" || exit 1\n"
            + "exec java -cp \"" + unix + "\" org.btsn.handlers.ServiceLoader . -version " + version + " \"$@\"\n");
        Files.writeString(pkg.resolve("launch.bat"), ("@echo off\n"
            + "rem Starts " + hostProject + " with the rules and services packaged beside it. Extra arguments go to the ServiceLoader.\n"
            + "cd /d \"%~dp0" + hostProject + "\"\n"
            + "java -cp \"" + windows + "\" org.btsn.handlers.ServiceLoader . -version " + version + " %*\n").replace("\n", "\r\n"));
        // The same host with its output in a file beside the scripts, as the launcher keeps it for local hosts.
        String log = logName(hostProject);
        Files.writeString(pkg.resolve("launch-to-file.sh"), "#!/bin/sh\n"
            + "# Starts " + hostProject + " as launch.sh does, writing everything it prints to " + log + ".\n"
            + "out=\"$(cd \"$(dirname \"$0\")\" && pwd)/" + log + "\"\n"
            + "echo \"Writing output to $out (follow it with: tail -f " + log + "). Ctrl+C stops the host.\"\n"
            + "exec sh \"$(dirname \"$0\")/launch.sh\" \"$@\" > \"$out\" 2>&1\n");
        Files.writeString(pkg.resolve("launch-to-file.bat"), ("@echo off\n"
            + "rem Starts " + hostProject + " as launch.bat does, writing everything it prints to " + log + ".\n"
            + "echo Writing output to \"%~dp0" + log + "\" - open it in Notepad to follow the run. Ctrl+C here stops the host.\n"
            + "call \"%~dp0launch.bat\" %* > \"%~dp0" + log + "\" 2>&1\n").replace("\n", "\r\n"));
    }

    /** P3's output file is P3_Place.out.txt, the name the launcher uses for a local P3. */
    static String logName(String hostProject) {
        return hostProject.substring(hostProject.lastIndexOf('.') + 1).toUpperCase(Locale.ROOT) + "_Place.out.txt";
    }

    /** SHA-256 of each rule and binding file, and one digest over them all: compare it with the launcher's. */
    static String writeRulesetDigest(Path pkg) throws Exception {
        StringBuilder lines = new StringBuilder();
        String digest = rulesetDigest(pkg.resolve("btsn.common"), lines);
        Files.writeString(pkg.resolve("MASTER-RULESET.sha256"), "# master ruleset " + digest + "\n" + lines);
        return digest;
    }

    /** One digest over the rule and binding files of a btsn.common folder; lists each file's hash in lines. */
    public static String rulesetDigest(Path common, StringBuilder lines) throws Exception {
        MessageDigest all = MessageDigest.getInstance("SHA-256");
        for (String folder : new String[] {"RuleBase", "ServiceAttributeBindings"}) {
            if (!Files.isDirectory(common.resolve(folder))) continue;
            try (Stream<Path> files = Files.walk(common.resolve(folder))) {
                for (Path file : (Iterable<Path>)files.filter(Files::isRegularFile).sorted()::iterator) {
                    byte[] bytes = Files.readAllBytes(file);
                    String name = common.relativize(file).toString().replace('\\', '/');
                    lines.append(hex(MessageDigest.getInstance("SHA-256").digest(bytes))).append("  ").append(name).append('\n');
                    all.update(name.getBytes(StandardCharsets.UTF_8));
                    all.update((byte)0);
                    all.update(bytes);
                }
            }
        }
        return hex(all.digest());
    }

    private static void writeReadme(Path pkg, String node, String hostProject, String version, String launcher, String channel,
                                    String address, List<String> services, Map<String, String> ports, String digest) throws IOException {
        StringBuilder r = new StringBuilder();
        r.append(node).append(" host for ").append(launcher).append("\n\n")
         .append("This folder is everything ").append(node).append(" needs on another machine. It talks to the rest of the\n")
         .append("deployment only by UDP, so the rules, bindings and services it uses are all here.\n\n")
         .append("  Address     ").append(address).append(" (channel ").append(channel).append(")\n")
         .append("  Rules       ").append(version).append(", master ruleset ").append(digest).append("\n")
         .append("  Services    ").append(services.isEmpty() ? "none (administrative services only)" : String.join(", ", services)).append("\n\n")
         .append("Run it\n")
         .append("  1. Install Java 15 or later on this machine. Ant and the source repository are not needed.\n")
         .append("  2. Make sure this machine's address is ").append(address).append(".\n")
         .append("  3. Allow Java to receive UDP on the ports below (Windows asks the first time; choose Private networks).\n")
         .append("  4. Start it one of two ways (Linux or macOS: sh launch.sh / sh launch-to-file.sh):\n")
         .append("       launch.bat           output in the window\n")
         .append("       launch-to-file.bat   output in ").append(logName(hostProject)).append(" in this folder (open it in Notepad)\n")
         .append("     Wait for \"Service Handler is listening\", then run the launcher on the ip0 machine.\n")
         .append("  5. Nothing stops it on a timer: it runs until you press Ctrl+C. Keep the machine from sleeping.\n\n")
         .append("Ports this host listens on (event port and rule port per operation)\n");
        for (Map.Entry<String, String> p : ports.entrySet()) r.append("  ").append(String.format("%-46s", p.getKey())).append(p.getValue()).append('\n');
        r.append("\nThe ip0 machine must accept UDP from this one: Monitor's ports, and 35000+ for rule commitments.\n\n")
         .append("Rebuild this package whenever the process, its deployment or the infrastructure changes: the rules\n")
         .append("are generated for one deployment. The launcher prints the same master ruleset digest when it builds\n")
         .append("the packages; MASTER-RULESET.sha256 lists every rule file's hash.\n\n")
         .append("Contents\n")
         .append("  ").append(hostProject).append("/   the host's working folder (service queries, logging, payload)\n")
         .append("  btsn.common/                 master ruleset, bindings, contracts and deployment, generated by the launcher\n")
         .append("  jars/host/                   host and shared infrastructure JARs (no business classes)\n")
         .append("  jars/lib/, jars/service-lib/ runtime libraries\n")
         .append("  jars/services/               the business services placed on ").append(node).append(", as separate JARs\n");
        Files.writeString(pkg.resolve("README.txt"), r.toString());
    }

    private static void copyTree(Path from, Path to) throws IOException {
        if (!Files.isDirectory(from)) return;
        try (Stream<Path> files = Files.walk(from)) {
            for (Path file : (Iterable<Path>)files::iterator) {
                String name = file.getFileName().toString();
                if (name.endsWith(".class") || name.endsWith(".log") || name.endsWith(".running")) continue;
                Path target = to.resolve(from.relativize(file).toString());
                if (Files.isDirectory(file)) Files.createDirectories(target);
                else { Files.createDirectories(target.getParent()); Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING); }
            }
        }
    }

    private static void deleteTree(Path folder) throws IOException {
        if (!Files.exists(folder)) return;
        try (Stream<Path> files = Files.walk(folder)) {
            for (Path file : (Iterable<Path>)files.sorted(java.util.Comparator.reverseOrder())::iterator) Files.delete(file);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder s = new StringBuilder();
        for (byte b : bytes) s.append(String.format("%02x", b));
        return s.toString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
