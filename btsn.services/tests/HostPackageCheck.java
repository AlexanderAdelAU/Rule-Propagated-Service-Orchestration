import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.btsn.deployment.DeploymentConfiguration;
import org.btsn.deployment.GenerateDeploymentRules;
import org.btsn.deployment.HostPackage;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

/**
 * A host on another machine reaches the deployment only by UDP. Checks that its package carries the
 * launcher's own master ruleset and only its node's services, that it starts from an empty folder
 * outside the repository, and that a distributed infrastructure moves each host's administrative
 * services onto that host's channel.
 *
 * Arguments: repository-root packages-folder launcher-runtime-folder
 */
public final class HostPackageCheck {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath(), packages = Path.of(args[1]).toAbsolutePath(), runtime = Path.of(args[2]).toAbsolutePath();
        placement(root);
        String digest = HostPackage.rulesetDigest(runtime.resolve("btsn.common"), new StringBuilder());
        Path scratch = Files.createTempDirectory("host package check ");
        try {
            Path p1 = unpack(packages.resolve("Inventory_Workflow_BuildAndRun-P1.zip"), scratch);
            Path p2 = unpack(packages.resolve("Inventory_Workflow_BuildAndRun-P2.zip"), scratch);
            contents(p1, "P1", digest, runtime, "inventory/InventoryService.jar");
            contents(p2, "P2", digest, runtime, "inventory/SupplierService.jar");
            if (System.getProperty("os.name").toLowerCase().contains("win")) System.out.println("SKIP: starting a package uses sh launch.sh");
            else starts(p2, "btsn.rpso.places.p2", 3);
        } finally {
            delete(scratch);
        }
        System.out.println("PASS: " + checks + " host package checks");
    }

    /** A distributed infrastructure moves Pn's initialisation and collector services to Pn's channel; a single host changes nothing. */
    static void placement(Path root) throws Exception {
        Path common = Files.createTempDirectory("placement");
        try {
            for (String folder : new String[] {"BusinessServiceDefinitions", "InfrastructureDefinitionFolder", "ServiceDeploymentFolder", "RuleBase"})
                copyTree(root.resolve("btsn.common").resolve(folder), common.resolve(folder));
            Files.copy(root.resolve("btsn.services/deployments/inventory/Inventory_WorkflowDeployment.json"),
                common.resolve("BusinessServiceDefinitions/Deployment.json"), StandardCopyOption.REPLACE_EXISTING);
            String init = "RuleBase/PetriNet/ListofPetriNetInitializationServices.ruleml.xml";
            String collect = "RuleBase/PetriNet/ListofPetrinetCollectorServices.ruleml.xml";
            String monitor = "RuleBase/Monitor/ListofMonitorActiveServices.ruleml.xml";
            byte[] sourceInit = Files.readAllBytes(common.resolve(init));

            GenerateDeploymentRules.main(new String[] {common.toString()});
            check(java.util.Arrays.equals(sourceInit, Files.readAllBytes(common.resolve(init))), "A single host must leave the administrative services untouched");

            JSONObject infrastructure = DeploymentConfiguration.read(common.resolve("InfrastructureDefinitionFolder/SingleHost.json"));
            for (Object item : (JSONArray)infrastructure.get("nodes")) {
                JSONObject node = (JSONObject)item;
                if ("P2".equals(node.get("node"))) { node.put("channel", "ip1"); node.put("address", "192.0.2.10"); }
            }
            Files.writeString(common.resolve("InfrastructureDefinitionFolder/TwoHost.json"), infrastructure.toJSONString());
            GenerateDeploymentRules.main(new String[] {common.toString(), "", "TwoHost"});
            check(DeploymentConfiguration.read(common.resolve("BusinessServiceDefinitions/Deployment.json")).get("infrastructure")
                .equals("InfrastructureDefinitionFolder/TwoHost.json"), "The infrastructure argument must select TwoHost.json");
            String rules = Files.readString(common.resolve("RuleBase/Generated/InfrastructureDeployment.ruleml.xml"));
            check(rules.contains("<Rel>boundChannel</Rel><Ind>ip1</Ind><Ind>192.0.2.10</Ind>"), "ip1 must be bound to P2's address");
            check(rules.contains("<Ind>P2_Place</Ind><Ind>restock</Ind><Ind>ip1</Ind>"), "P2's place must use ip1");
            check(channelOf(common.resolve(init), "P2_InitializationService").equals("ip1"), "P2's initialiser must follow P2 to ip1");
            check(channelOf(common.resolve(collect), "P2_CollectorService").equals("ip1"), "P2's collector must follow P2 to ip1");
            check(channelOf(common.resolve(init), "P1_InitializationService").equals("ip0"), "P1's initialiser stays on ip0");
            check(channelOf(common.resolve(monitor), "MonitorService").equals("ip0"), "Monitor stays on ip0");

            for (Object item : (JSONArray)infrastructure.get("nodes")) {
                JSONObject node = (JSONObject)item;
                if ("P2".equals(node.get("node"))) node.put("channel", "net1");
            }
            Files.writeString(common.resolve("InfrastructureDefinitionFolder/TwoHost.json"), infrastructure.toJSONString());
            rejects(() -> GenerateDeploymentRules.main(new String[] {common.toString(), "", "TwoHost"}), "Channel must be ip0");
            rejects(() -> GenerateDeploymentRules.main(new String[] {common.toString(), "", "../TwoHost"}), "file name");
            rejects(() -> GenerateDeploymentRules.main(new String[] {common.toString(), "", "Missing"}), "No infrastructure definition");
        } finally {
            delete(common);
        }
    }

    /** The package carries the launcher's master ruleset, only its node's services and a host JAR without business classes. */
    static void contents(Path pkg, String node, String digest, Path runtime, String service) throws Exception {
        String readme = Files.readString(pkg.resolve("README.txt"));
        check(readme.contains("master ruleset " + digest), node + ": README must carry the launcher's master ruleset digest");
        check(Files.readString(pkg.resolve("MASTER-RULESET.sha256")).startsWith("# master ruleset " + digest), node + ": digest file differs");
        check(HostPackage.rulesetDigest(pkg.resolve("btsn.common"), new StringBuilder()).equals(digest), node + ": packaged rules differ from the launcher's");
        String generated = "RuleBase/Generated/InfrastructureDeployment.ruleml.xml";
        check(Files.readString(pkg.resolve("btsn.common").resolve(generated)).equals(Files.readString(runtime.resolve("btsn.common").resolve(generated))),
            node + ": generated deployment rules differ");
        List<String> services = new ArrayList<>();
        try (Stream<Path> files = Files.walk(pkg.resolve("jars/services"))) {
            files.filter(Files::isRegularFile).forEach(f -> services.add(pkg.resolve("jars/services").relativize(f).toString().replace('\\', '/')));
        }
        check(services.equals(List.of(service)), node + ": expected only " + service + ", found " + services);
        String hostJar = "jars/host/btsn.rpso.places." + node.toLowerCase() + ".jar";
        try (ZipFile jar = new ZipFile(pkg.resolve(hostJar).toFile())) {
            for (ZipEntry entry : java.util.Collections.list(jar.entries()))
                check(!entry.getName().startsWith("org/btsn/business/") && !entry.getName().startsWith("org/btsn/services/"),
                    node + ": host JAR contains business class " + entry.getName());
        }
        check(Files.readString(pkg.resolve("launch.bat")).contains("\r\n"), node + ": launch.bat needs Windows line endings");
        check(Files.readString(pkg.resolve("launch.sh")).contains("org.btsn.handlers.ServiceLoader . -version v001"), node + ": launch.sh must start the ServiceLoader");
        String toFile = Files.readString(pkg.resolve("launch-to-file.bat"));
        check(toFile.contains("\r\n") && toFile.contains("call \"%~dp0launch.bat\" %* > \"%~dp0" + node + "_Place.out.txt\" 2>&1"),
            node + ": launch-to-file.bat must run launch.bat into " + node + "_Place.out.txt");
        check(Files.readString(pkg.resolve("launch-to-file.sh")).contains(node + "_Place.out.txt"), node + ": launch-to-file.sh must write " + node + "_Place.out.txt");
        check(Files.readString(pkg.resolve("README.txt")).contains("launch-to-file.bat"), node + ": README must explain both launches");
    }

    /** Starts the host with launch-to-file.sh (which runs launch.sh) from its unpacked folder and waits for its service handlers in its output file; it must build its rules from the package. */
    static void starts(Path pkg, String hostProject, int handlers) throws Exception {
        String node = hostProject.substring(hostProject.lastIndexOf('.') + 1).toUpperCase();
        Path output = pkg.resolve(node + "_Place.out.txt");
        Process host = new ProcessBuilder("sh", "launch-to-file.sh").directory(pkg.toFile()).redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try {
            long deadline = System.currentTimeMillis() + 90_000;
            int listening = 0;
            while (System.currentTimeMillis() < deadline && host.isAlive()) {
                if (Files.isRegularFile(output)) listening = count(Files.readString(output, StandardCharsets.ISO_8859_1), "Service Handler is listening");
                if (listening >= handlers) break;
                Thread.sleep(500);
            }
            check(listening >= handlers, "Packaged host started " + listening + " of " + handlers + " service handlers:\n" + tail(output));
            Path rules = pkg.resolve(hostProject).resolve("RuleFolder.v001/Service.ruleml");
            check(Files.isRegularFile(rules), "Packaged host built no rules in its own folder");
            String text = Files.readString(rules, StandardCharsets.ISO_8859_1);
            check(text.contains("P2_Place") && text.contains("restock"), "Packaged host's rules lack its own operation");
        } finally {
            host.descendants().forEach(ProcessHandle::destroy);
            host.destroy();
            if (!host.waitFor(10, TimeUnit.SECONDS)) host.destroyForcibly();
        }
    }

    private static String channelOf(Path file, String service) throws Exception {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<Ind>\\s*" + service + "\\s*</Ind>\\s*<Ind>[^<]*</Ind>\\s*<Ind>\\s*([^<]*?)\\s*</Ind>")
            .matcher(Files.readString(file));
        check(m.find(), "No activeService for " + service + " in " + file);
        return m.group(1);
    }

    private static Path unpack(Path zip, Path into) throws Exception {
        check(Files.isRegularFile(zip), "Missing package " + zip);
        String top = null;
        try (ZipFile file = new ZipFile(zip.toFile())) {
            for (ZipEntry entry : java.util.Collections.list(file.entries())) {
                Path target = into.resolve(entry.getName()).normalize();
                check(target.startsWith(into), "Unsafe entry " + entry.getName());
                if (top == null) top = entry.getName().split("/")[0];
                if (entry.isDirectory()) { Files.createDirectories(target); continue; }
                Files.createDirectories(target.getParent());
                try (InputStream in = file.getInputStream(entry)) { Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING); }
            }
        }
        return into.resolve(top);
    }

    interface Action { void run() throws Exception; }
    private static void rejects(Action action, String message) throws Exception {
        try { action.run(); } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains(message), "Wrong rejection: " + expected.getMessage());
            return;
        }
        throw new AssertionError("Accepted, expected: " + message);
    }
    private static int count(String text, String what) {
        int n = 0;
        for (int i = text.indexOf(what); i >= 0; i = text.indexOf(what, i + 1)) n++;
        return n;
    }
    private static String tail(Path file) throws Exception {
        if (!Files.isRegularFile(file)) return "(no " + file.getFileName() + ")";
        String text = Files.readString(file, StandardCharsets.ISO_8859_1);
        return text.substring(Math.max(0, text.length() - 3000));
    }
    private static void copyTree(Path from, Path to) throws Exception {
        try (Stream<Path> files = Files.walk(from)) {
            for (Path f : (Iterable<Path>)files::iterator) {
                Path t = to.resolve(from.relativize(f).toString());
                if (Files.isDirectory(f)) Files.createDirectories(t); else Files.copy(f, t, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
    private static void delete(Path folder) throws Exception {
        if (!Files.exists(folder)) return;
        try (Stream<Path> files = Files.walk(folder)) {
            for (Path f : (Iterable<Path>)files.sorted(java.util.Comparator.reverseOrder())::iterator) Files.deleteIfExists(f);
        }
    }
    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
}
