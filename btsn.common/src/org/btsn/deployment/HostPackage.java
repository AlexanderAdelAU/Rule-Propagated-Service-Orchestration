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
 * Lays out everything one machine needs to run its hosts, which reach the rest of the deployment only
 * through UDP: the host JARs and their libraries, the business service JARs placed on those nodes (kept as
 * separate JARs, outside the host JARs), the master ruleset and bindings generated for this deployment (the
 * same files the local hosts read), each host's own configuration, and launch scripts.
 *
 * One package per machine: the nodes being packaged are grouped by address, and each group becomes
 * {@code <launcher>_<nodes>} (for example {@code P1_P2_P3_P4_ForkRebalance_Workflow_LiveChange_P3P6}), as a ZIP; the working folder is removed.
 * {@code launch} starts every node in it; {@code launch-Pn} starts one, for a node that joins later.
 *
 * Arguments (name=value): nodes (P2,P3,P6), version, launcher, runtime (the launcher's runtime folder),
 * source (repository root), bundle (service deployment bundle), infrastructureJar, libs, libExcludes, out.
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
        List<String> nodes = new ArrayList<>();
        for (String n : a.getOrDefault("nodes", a.getOrDefault("node", "")).split(",")) if (!n.isBlank()) nodes.add(n.trim());
        Path out = Path.of(a.get("out"));
        List<Path> packages = buildMachines(nodes, a.get("version"), a.get("launcher"), Path.of(a.get("runtime")), Path.of(a.get("source")),
            Path.of(a.get("bundle")), Path.of(a.get("infrastructureJar")), Path.of(a.get("libs")), Path.of(a.get("libExcludes")), out);
        if (packages.isEmpty()) System.out.println("Every host this deployment uses runs on this machine; nothing to package (-Dhost.packages=all packages them anyway).");
    }

    /** One package ZIP per machine for the given nodes; the output folder holds only these ZIPs, so no package from an earlier layout is left. */
    public static List<Path> buildMachines(List<String> nodeNames, String version, String launcher, Path runtime, Path source, Path bundle,
                                          Path infrastructureJar, Path libs, Path libExcludes, Path out) throws Exception {
        DeploymentConfiguration config = new DeploymentConfiguration(runtime.resolve("btsn.common"));
        Map<String, List<String>> byAddress = new java.util.LinkedHashMap<>();
        for (String name : nodeNames) {
            String node = name.toUpperCase(Locale.ROOT);
            require(node.matches("P\\d+"), "Host node is P1 ... P6: " + name);
            byAddress.computeIfAbsent(DeploymentConfiguration.text(config.node(node), "address"), k -> new ArrayList<>()).add(node);
        }
        // Keep the folder and replace what is in it, so an Explorer window open on it shows the new packages.
        Files.createDirectories(out);
        try (Stream<Path> old = Files.list(out)) { for (Path p : (Iterable<Path>)old::iterator) deleteTree(p); }
        List<Path> packages = new ArrayList<>();
        for (Map.Entry<String, List<String>> machine : byAddress.entrySet()) {
            Path pkg = build(machine.getValue(), version, launcher, runtime, source, bundle, infrastructureJar, libs, libExcludes, out);
            Path zip = out.resolve(pkg.getFileName() + ".zip");
            zip(pkg, zip);
            deleteTree(pkg); // only the ZIP is left: one file per machine
            System.out.println("  " + String.join("", machine.getValue()) + ": " + zip + "  (copy to " + machine.getKey() + " and run launch.bat or launch.sh)");
            packages.add(zip);
        }
        return packages;
    }

    /** The package folder for one machine's nodes (all on one address). */
    public static Path build(List<String> nodes, String version, String launcher, Path runtime, Path source, Path bundle,
                             Path infrastructureJar, Path libs, Path libExcludes, Path out) throws Exception {
        require(!nodes.isEmpty(), "No nodes to package");
        require(version != null && version.matches("v\\d+"), "Rule version is v001, v002, ...: " + version);
        Path common = runtime.resolve("btsn.common");
        DeploymentConfiguration config = new DeploymentConfiguration(common);
        String address = DeploymentConfiguration.text(config.node(nodes.get(0)), "address");
        require(Files.isRegularFile(infrastructureJar), "Infrastructure JAR not built: " + infrastructureJar);

        Path pkg = out.resolve(launcher + "_" + String.join("", nodes));
        deleteTree(pkg);
        Files.createDirectories(pkg);

        // The master ruleset and everything else the hosts read from ../btsn.common.
        for (String folder : COMMON_FOLDERS) copyTree(common.resolve(folder), pkg.resolve("btsn.common").resolve(folder));
        require(Files.isRegularFile(pkg.resolve("btsn.common/RuleBase/Generated/InfrastructureDeployment.ruleml.xml")),
            "The launcher's runtime has no generated deployment rules; prepare the runtime first");

        Set<String> excluded = new LinkedHashSet<>();
        if (Files.isRegularFile(libExcludes)) for (String line : Files.readAllLines(libExcludes)) if (!line.isBlank()) excluded.add(line.trim());
        Map<String, String> channels = new TreeMap<>(), hostProjects = new java.util.LinkedHashMap<>();
        Map<String, List<String>> servicesByNode = new java.util.LinkedHashMap<>();
        Map<String, Map<String, String>> portsByNode = new java.util.LinkedHashMap<>();
        for (String node : nodes) {
            JSONObject physical = config.node(node);
            require(address.equals(DeploymentConfiguration.text(physical, "address")), node + " is not on " + address);
            channels.put(node, DeploymentConfiguration.text(physical, "channel"));
            String hostProject = "btsn.rpso.places." + node.toLowerCase(Locale.ROOT);
            hostProjects.put(node, hostProject);
            Path hostJar = source.resolve(hostProject).resolve("target").resolve(hostProject + ".jar");
            require(Files.isRegularFile(hostJar), "Host JAR not built: " + hostJar);
            // The host's working folder.
            Path work = pkg.resolve(hostProject);
            Files.createDirectories(work);
            for (String folder : HOST_FOLDERS) copyTree(source.resolve(hostProject).resolve(folder), work.resolve(folder));
            require(Files.isDirectory(work.resolve("ServiceLoaderQueries")), "No ServiceLoaderQueries for " + hostProject);

            // Class path, in the order the local launcher uses: infrastructure, libraries, service libraries, services, host.
            List<String> classpath = new ArrayList<>();
            classpath.add(copyJar(infrastructureJar, pkg, "jars/host"));
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
            servicesByNode.put(node, services);
            portsByNode.put(node, ports(pkg.resolve("btsn.common"), config, node));
            writeNodeScripts(pkg, node, hostProject, version, classpath);
        }
        writeMachineScripts(pkg, nodes);
        String digest = writeRulesetDigest(pkg);
        writeReadme(pkg, nodes, hostProjects, version, launcher, channels, address, servicesByNode, portsByNode, digest);
        Set<String> allServices = new LinkedHashSet<>();
        for (List<String> s : servicesByNode.values()) allServices.addAll(s);
        System.out.println(String.join("", nodes) + " -> " + address + " " + new java.util.TreeSet<>(channels.values()) + ", rules " + version
            + ", master ruleset " + digest.substring(0, 16) + (allServices.isEmpty() ? ", no business services" : ", services " + allServices));
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

    /** A node's own scripts: launch-Pn (output in the window) and launch-to-file-Pn (output in Pn_Place.out.txt). */
    private static void writeNodeScripts(Path pkg, String node, String hostProject, String version, List<String> classpath) throws IOException {
        StringBuilder unix = new StringBuilder(), windows = new StringBuilder();
        for (String entry : classpath) {
            unix.append(unix.length() == 0 ? "" : ":").append("../").append(entry);
            windows.append(windows.length() == 0 ? "" : ";").append("..\\").append(entry.replace('/', '\\'));
        }
        String log = node + "_Place.out.txt";
        Files.writeString(pkg.resolve("launch-" + node + ".sh"), "#!/bin/sh\n"
            + "# Starts " + node + " (" + hostProject + ") with the rules and services packaged beside it. Extra arguments go to the ServiceLoader.\n"
            + "cd \"$(dirname \"$0\")/" + hostProject + "\" || exit 1\n"
            + "exec java -cp \"" + unix + "\" org.btsn.handlers.ServiceLoader . -version " + version + " \"$@\"\n");
        Files.writeString(pkg.resolve("launch-" + node + ".bat"), ("@echo off\n"
            + "rem Starts " + node + " (" + hostProject + ") with the rules and services packaged beside it. Extra arguments go to the ServiceLoader.\n"
            + "cd /d \"%~dp0" + hostProject + "\"\n"
            + "java -cp \"" + windows + "\" org.btsn.handlers.ServiceLoader . -version " + version + " %*\n").replace("\n", "\r\n"));
        Files.writeString(pkg.resolve("launch-to-file-" + node + ".sh"), "#!/bin/sh\n"
            + "# Starts " + node + " as launch-" + node + ".sh does, writing everything it prints to " + log + ".\n"
            + "out=\"$(cd \"$(dirname \"$0\")\" && pwd)/" + log + "\"\n"
            + "echo \"" + node + ": output in $out (follow it with: tail -f " + log + "). Ctrl+C stops it.\"\n"
            + "exec sh \"$(dirname \"$0\")/launch-" + node + ".sh\" \"$@\" > \"$out\" 2>&1\n");
        Files.writeString(pkg.resolve("launch-to-file-" + node + ".bat"), ("@echo off\n"
            + "rem Starts " + node + " as launch-" + node + ".bat does, writing everything it prints to " + log + ".\n"
            + "echo " + node + ": output in \"%~dp0" + log + "\" - open it in Notepad to follow the run. Ctrl+C here stops it.\n"
            + "call \"%~dp0launch-" + node + ".bat\" %* > \"%~dp0" + log + "\" 2>&1\n").replace("\n", "\r\n"));
    }

    /** launch and launch-to-file start every node of the machine: in this window for one node, one window each for several. */
    private static void writeMachineScripts(Path pkg, List<String> nodes) throws IOException {
        String all = String.join(", ", nodes);
        StringBuilder sh = new StringBuilder("#!/bin/sh\n# Starts " + all + " on this machine (launch-Pn.sh starts one).\nd=\"$(dirname \"$0\")\"\n");
        StringBuilder shFile = new StringBuilder("#!/bin/sh\n# Starts " + all + " on this machine, each writing Pn_Place.out.txt here (launch-to-file-Pn.sh starts one).\nd=\"$(dirname \"$0\")\"\n");
        StringBuilder bat = new StringBuilder("@echo off\nrem Starts " + all + " on this machine (launch-Pn.bat starts one).\n");
        StringBuilder batFile = new StringBuilder("@echo off\nrem Starts " + all + " on this machine, each writing Pn_Place.out.txt here (launch-to-file-Pn.bat starts one).\n");
        if (nodes.size() == 1) {
            String n = nodes.get(0);
            sh.append("exec sh \"$d/launch-").append(n).append(".sh\" \"$@\"\n");
            shFile.append("exec sh \"$d/launch-to-file-").append(n).append(".sh\" \"$@\"\n");
            bat.append("call \"%~dp0launch-").append(n).append(".bat\" %*\n");
            batFile.append("call \"%~dp0launch-to-file-").append(n).append(".bat\" %*\n");
        } else {
            for (String n : nodes) {
                sh.append("sh \"$d/launch-").append(n).append(".sh\" \"$@\" &\n");
                shFile.append("sh \"$d/launch-to-file-").append(n).append(".sh\" \"$@\" &\n");
                bat.append("start \"").append(n).append("\" cmd /k call \"%~dp0launch-").append(n).append(".bat\" %*\n");
                batFile.append("start \"").append(n).append("\" /min cmd /c call \"%~dp0launch-to-file-").append(n).append(".bat\" %*\n");
            }
            sh.append("wait\n");
            shFile.append("wait\n");
            bat.append("echo Started " + all + ", each in its own window. Close a window to stop that node.\n");
            batFile.append("echo Started " + all + ", each in its own minimised window, writing Pn_Place.out.txt in \"%~dp0\". Close a window to stop that node.\n");
        }
        Files.writeString(pkg.resolve("launch.sh"), sh.toString());
        Files.writeString(pkg.resolve("launch-to-file.sh"), shFile.toString());
        Files.writeString(pkg.resolve("launch.bat"), bat.toString().replace("\n", "\r\n"));
        Files.writeString(pkg.resolve("launch-to-file.bat"), batFile.toString().replace("\n", "\r\n"));
    }

    /** "P3_Place.out.txt": a node's output file. */
    static String logName(String node) { return node + "_Place.out.txt"; }

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

    private static void writeReadme(Path pkg, List<String> nodes, Map<String, String> hostProjects, String version, String launcher,
                                    Map<String, String> channels, String address, Map<String, List<String>> services,
                                    Map<String, Map<String, String>> ports, String digest) throws IOException {
        String all = String.join(", ", nodes);
        StringBuilder r = new StringBuilder();
        r.append(all).append(" for ").append(launcher).append("\n\n")
         .append("This folder is everything ").append(all).append(nodes.size() == 1 ? " needs" : " need").append(" on ").append(address)
         .append(". The hosts talk to the rest of the\ndeployment only by UDP, so the rules, bindings and services they use are all here.\n\n")
         .append("  Address     ").append(address).append("\n")
         .append("  Rules       ").append(version).append(", master ruleset ").append(digest).append("\n");
        for (String node : nodes)
            r.append("  ").append(String.format("%-12s", node)).append("channel ").append(channels.get(node)).append(", services ")
             .append(services.get(node).isEmpty() ? "none (administrative services only)" : String.join(", ", services.get(node))).append("\n");
        r.append("\nRun it\n")
         .append("  1. Install Java 15 or later on this machine. Ant and the source repository are not needed.\n")
         .append("  2. Make sure this machine's address is ").append(address).append(".\n")
         .append("  3. Allow Java to receive UDP on the ports below (Windows asks the first time; choose Private networks).\n")
         .append("  4. Start the hosts (Linux or macOS: sh with the .sh of the same name):\n")
         .append("       launch.bat                 ").append(nodes.size() == 1 ? all + ", output in the window" : "every node, each in its own window").append("\n")
         .append("       launch-to-file.bat         ").append(nodes.size() == 1 ? all + ", output in " + logName(nodes.get(0)) : "every node, each writing its Pn_Place.out.txt here").append("\n");
        if (nodes.size() > 1)
            r.append("       launch-Pn.bat / launch-to-file-Pn.bat   one node only, for a node that joins a run later\n");
        r.append("     Wait for \"Service Handler is listening\", then run the launcher on the ip0 machine.\n")
         .append("  5. Nothing stops a host on a timer: it runs until you press Ctrl+C or close its window. Keep the machine from sleeping.\n\n")
         .append("Ports each host listens on (event port and rule port per operation)\n");
        for (String node : nodes)
            for (Map.Entry<String, String> p : ports.get(node).entrySet()) r.append("  ").append(String.format("%-46s", p.getKey())).append(p.getValue()).append('\n');
        r.append("\nThe ip0 machine must accept UDP from this one: Monitor's ports, and 35000+ for rule commitments.\n\n")
         .append("Rebuild this package whenever the deployment or the infrastructure changes: the rules are generated for\n")
         .append("one deployment. The launcher prints the same master ruleset digest when it builds the packages;\n")
         .append("MASTER-RULESET.sha256 lists every rule file's hash.\n\n")
         .append("Contents\n");
        for (String node : nodes) r.append("  ").append(String.format("%-29s", hostProjects.get(node) + "/")).append(node).append("'s working folder (service queries, logging, payload)\n");
        r.append("  btsn.common/                 master ruleset, bindings, contracts and deployment, generated by the launcher\n")
         .append("  jars/host/                   host and shared infrastructure JARs (no business classes)\n")
         .append("  jars/lib/, jars/service-lib/ runtime libraries\n")
         .append("  jars/services/               the business services placed on ").append(all).append(", as separate JARs\n");
        Files.writeString(pkg.resolve("README.txt"), r.toString());
    }

    private static void zip(Path folder, Path zip) throws IOException {
        Files.deleteIfExists(zip);
        String top = folder.getFileName().toString();
        try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(Files.newOutputStream(zip));
             Stream<Path> files = Files.walk(folder)) {
            for (Path f : (Iterable<Path>)files.sorted()::iterator) {
                String name = top + "/" + folder.relativize(f).toString().replace('\\', '/');
                if (Files.isDirectory(f)) { if (!f.equals(folder)) { z.putNextEntry(new java.util.zip.ZipEntry(name + "/")); z.closeEntry(); } continue; }
                z.putNextEntry(new java.util.zip.ZipEntry(name));
                Files.copy(f, z);
                z.closeEntry();
            }
        }
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
