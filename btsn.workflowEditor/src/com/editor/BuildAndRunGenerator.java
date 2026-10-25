package com.editor;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

/**
 * Writes a BuildAndRun launcher for a saved, deployed process. The launcher holds only the choices
 * that differ between processes and imports btsn.services/process-runtime.xml for the shared phases.
 * Missing supporting files are written too: the deployment profile and a token schedule (CSV) per event
 * generator. Existing files are reused. The launcher lists the hosts the process runs on (hosts=P1,P2,P7);
 * each is a node of the infrastructure running the one host program, and the run generates their
 * initialiser and collector.
 * Every event generator in the process runs, each under its own rule version, so their tokens stay apart.
 */
public final class BuildAndRunGenerator {
    public static final List<String> VERSIONS = Arrays.asList("v001", "v002", "v003");
    /** A host node: P1 ... P99 (the runtime numbers each host's administrative ports and tokens by it). */
    static boolean isHost(String node) { return node != null && node.matches("P[1-9]\\d?"); }

    /** One event generator's run: its rule version and the spacing of its tokens. */
    public static final class Run {
        public String generatorId = "";
        public String version = "v001";
        public int intervalMs = 1000;
        public boolean enabled = true;
        Run copy() { Run r = new Run(); r.generatorId = generatorId; r.version = version; r.intervalMs = intervalMs; r.enabled = enabled; return r; }
    }

    /** Choices the user makes; defaults come from the process. */
    public static final class Options {
        public String launcherName = "";
        public File folder;
        public final List<Run> runs = new ArrayList<>();   // one per event generator, in canvas order
        public int tokens = 10;                            // per generator
        public int completionSeconds = 10;
        public List<Run> enabled() { List<Run> on = new ArrayList<>(); for (Run r : runs) if (r.enabled) on.add(r); return on; }
    }

    /** What the launcher will use, worked out from the process, its deployment and the options. */
    public static final class Plan {
        public final List<String> problems = new ArrayList<>();
        public File root, processFile;
        public String processName = "", domain = "";
        public final List<String> nodes = new ArrayList<>();
        public final Map<String, String> targets = new LinkedHashMap<>();   // generator id -> runtime place
        public final Map<String, String> operations = new LinkedHashMap<>(); // generator id -> operation
        public final Map<String, Integer> rates = new LinkedHashMap<>();
        public final Map<String, String> versions = new LinkedHashMap<>();
        public final Map<String, String> data = new LinkedHashMap<>();       // generator id -> fields every token carries (name=value,...)
        public final Map<String, Integer> joins = new LinkedHashMap<>();     // generator id -> tokens of the initial marking it sets, once
        public final Map<String, String> markings = new LinkedHashMap<>();   // generator id -> marking over several places (place.operation=input,...;...)
        public final Map<String, String> services = new LinkedHashMap<>();  // generator id -> service at its target place
        public String tokenGenerator = "";  // the profile's "tokenGenerator", when it names one (else the runtime's default)
        public File profile; public boolean profileExists;
        public String serviceDeployment = "", catalogue = "";
        public final List<File> folders = new ArrayList<>();
        public Options defaults = new Options();

        public File launcher(Options o) { return new File(o.folder, o.launcherName.endsWith(".xml") ? o.launcherName : o.launcherName + ".xml"); }
        /** One schedule per generator; a single generator keeps the plain launcher name. */
        public File schedule(Options o, Run run) {
            String suffix = o.enabled().size() > 1 ? "_" + run.generatorId.replaceAll("[^A-Za-z0-9_.-]", "_") : "";
            return new File(root, "btsn.common.eventgenerators/EventTriggeringFile/" + baseName(o) + suffix + ".csv");
        }
        public String baseName(Options o) { return launcher(o).getName().replaceFirst("\\.xml$", ""); }
    }

    /**
     * A combined process: a drawing named after its launcher (&lt;name&gt;_LiveChange.json and &lt;name&gt;_LiveChange.xml), an amalgam
     * of independent processes that the launcher deploys and runs, each under its own version. Null for an ordinary process.
     */
    public static File combinedLauncher(File processFile) {
        if (processFile == null) return null;
        // A picture of a combined run names the launcher it shows: "launcher": "btsn.healthcare.ProjectLoader/....xml".
        File declared = declaredLauncher(processFile);
        if (declared != null) return declared;
        String base = processFile.getName().replaceFirst("\\.json$", "");
        if (!base.endsWith("_LiveChange")) return null;
        File common = ServiceRegistry.findCommon(processFile);
        File[] loaders = common == null ? null : common.getParentFile().listFiles(f -> f.isDirectory() && f.getName().matches("btsn\\..+\\.ProjectLoader"));
        if (loaders != null) for (File loader : loaders) { File xml = new File(loader, base + ".xml"); if (xml.isFile()) return xml; }
        return null;
    }

    /** The launcher a process definition names with "launcher" (relative to the repository), when it exists. */
    static File declaredLauncher(File processFile) {
        try {
            if (!processFile.isFile()) return null;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"launcher\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(new String(java.nio.file.Files.readAllBytes(processFile.toPath()), java.nio.charset.StandardCharsets.UTF_8));
            File common = ServiceRegistry.findCommon(processFile);
            if (!m.find() || common == null) return null;
            File launcher = new File(common.getParentFile(), m.group(1));
            return launcher.isFile() ? launcher : null;
        } catch (java.io.IOException unreadable) { return null; }
    }

    /** What a combined launcher runs, one line per event generator: "EG2: v002, P1_P3_P4_P6_..._Workflow, 30 tokens, deployed at 45 s". */
    public static List<String> combinedSummary(File launcher) {
        List<String> lines = new ArrayList<>();
        Map<String, String> p = new LinkedHashMap<>();
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("<property name=\"([^\"]+)\" (?:value|location)=\"([^\"]*)\"")
                .matcher(new String(java.nio.file.Files.readAllBytes(launcher.toPath()), java.nio.charset.StandardCharsets.UTF_8));
            while (m.find()) p.putIfAbsent(m.group(1), m.group(2));
        } catch (java.io.IOException ex) { lines.add("Cannot read " + launcher.getName() + ": " + ex.getMessage()); return lines; }
        for (int i = 1; i <= 3; i++) {
            String slot = "eg" + i;
            if (!p.containsKey(slot + ".id")) continue;
            String process = p.getOrDefault(slot + ".process", p.getOrDefault("workflow.process.name", ""));
            String when = !p.containsKey(slot + ".publish.at.seconds") ? "from the start"
                : "published to the running hosts at " + p.get(slot + ".publish.at.seconds") + " s"
                  + (p.containsKey(slot + ".fire.after.seconds") ? ", tokens " + p.get(slot + ".fire.after.seconds") + " s after every host commits" : "");
            lines.add("EG" + i + ": " + p.getOrDefault(slot + ".version", "") + ", " + process.substring(process.lastIndexOf('/') + 1)
                + ", " + p.getOrDefault(slot + ".tokens", p.getOrDefault("token.count", "?")) + " tokens, " + when);
        }
        return lines;
    }

    public static Plan plan(Canvas canvas, File processFile) {
        Plan plan = new Plan();
        plan.processFile = processFile;
        if (processFile == null) { plan.problems.add("Save the process first."); return plan; }
        File common = ServiceRegistry.findCommon(processFile);
        if (common == null) { plan.problems.add("The process is not inside a project with btsn.common."); return plan; }
        plan.root = common.getParentFile();
        Path folder = new File(common, "ProcessDefinitionFolder").toPath().toAbsolutePath().normalize();
        Path location = processFile.toPath().toAbsolutePath().normalize();
        if (!location.startsWith(folder)) { plan.problems.add("Save the process under btsn.common/ProcessDefinitionFolder."); return plan; }
        plan.processName = folder.relativize(location).toString().replace(File.separatorChar, '/').replaceFirst("\\.json$", "");
        plan.domain = plan.processName.contains("/") ? plan.processName.substring(0, plan.processName.indexOf('/')) : "";

        ServiceRegistry registry = canvas.getServiceRegistry();
        if (canvas.isAdministrativeProcess()) { plan.problems.add(Canvas.ADMINISTRATIVE); return plan; }
        if (!registry.hasDeployment()) { plan.problems.add("Deploy the process first: no service deployment is linked."); return plan; }
        plan.serviceDeployment = canvas.getServiceDeploymentReference();
        plan.catalogue = registry.catalogueReference();
        Set<String> used = new TreeSet<>(Comparator.comparingInt((String n) -> Integer.parseInt(n.substring(1))));
        for (ProcessElement place : canvas.getPlaces()) {
            String node = registry.nodeFor(place);
            if (node.isEmpty() || canvas.hasPendingNode(place)) plan.problems.add("Place " + place.getLabel() + " is not deployed; use Deploy and save the deployment.");
            else if (!isHost(node)) plan.problems.add("Place " + place.getLabel() + " runs on " + node + "; a host node is named P1, P2, ... (up to P99).");
            else used.add(node);
        }
        plan.problems.addAll(canvas.nodeConflicts());
        plan.nodes.addAll(used);

        for (ProcessElement element : canvas.getElements()) {
            if (element.getType() != ProcessElement.Type.EVENT_GENERATOR) continue;
            String data = tokenData(element.getTokenData());
            if (data == null) plan.problems.add(element.getLabel() + "'s Token data is not name=value pairs separated by commas: " + element.getTokenData());
            else if (!data.isEmpty()) plan.data.put(element.getId(), data);
            Canvas.GeneratorRole role = canvas.generatorRole(element);
            if (role.marking()) { planMarking(plan, registry, element, role); continue; }
            ProcessElement place = firstPlaceAfter(canvas, element);
            if (place == null || place.getServiceOperations().isEmpty() || registry.nodeFor(place).isEmpty()) continue;
            plan.targets.put(element.getId(), registry.nodeFor(place) + "_Place");
            plan.operations.put(element.getId(), place.getServiceOperations().get(0).getName());
            if (place.getService() != null && !place.getService().isEmpty()) plan.services.put(element.getId(), place.getService());
            plan.rates.put(element.getId(), parse(element.getGeneratorRate(), 1000));
            plan.versions.put(element.getId(), VERSIONS.contains(element.getTokenVersion()) ? element.getTokenVersion() : "v001");
            int inputs = joinInputs(canvas, element, place);
            if (inputs > 1) plan.joins.put(element.getId(), inputs);
            else if (inputs >= 0) plan.problems.add(element.getId() + " feeds the join before " + place.getLabel()
                + ", but " + place.getLabel() + "'s operation names " + inputs + " input(s); a join needs one argument for each input.");
        }
        plan.problems.addAll(canvas.joinsMarkedTwice());
        if (plan.targets.isEmpty()) plan.problems.add("Add an event generator connected through a T_in transition to a deployed place.");
        if (!plan.problems.isEmpty()) return plan;

        // Deployment profile: reuse one that already selects this service deployment.
        File deployments = new File(plan.root, "btsn.services/deployments");
        for (File candidate : jsonFiles(deployments)) {
            try {
                JSONObject profile = (JSONObject)new JSONParser().parse(read(candidate));
                if (plan.serviceDeployment.equals(profile.get("serviceDeployment"))) {
                    plan.profile = candidate; plan.profileExists = true;
                    // A profile may name the token generator its services expect (e.g. one that fills tokens from formats).
                    if (profile.get("tokenGenerator") instanceof String) plan.tokenGenerator = (String)profile.get("tokenGenerator");
                    break;
                }
            } catch (Exception ignored) { }
        }
        String base = processFile.getName().replaceFirst("(?i)\\.json$", "");
        if (plan.profile == null) plan.profile = new File(deployments, ("petrinet".equals(plan.domain) || plan.domain.isEmpty() ? "models" : plan.domain) + "/" + base + "Deployment.json");

        File[] loaders = plan.root.listFiles(f -> f.isDirectory() && f.getName().matches("btsn\\..+\\.ProjectLoader"));
        if (loaders != null) { Arrays.sort(loaders); plan.folders.addAll(Arrays.asList(loaders)); }
        File preferred = new File(plan.root, "btsn." + (plan.domain.isEmpty() ? "petrinet" : plan.domain) + ".ProjectLoader");
        // A domain without its own loader folder (e.g. one you add) uses the general models folder.
        File models = new File(plan.root, "btsn.petrinet.ProjectLoader");
        Options o = plan.defaults;
        o.folder = preferred.isDirectory() ? preferred : models.isDirectory() ? models : plan.folders.isEmpty() ? plan.root : plan.folders.get(0);
        o.launcherName = base + "_BuildAndRun.xml";
        for (String id : plan.targets.keySet()) {
            Run run = new Run(); run.generatorId = id; run.intervalMs = plan.rates.get(id); run.version = plan.versions.get(id);
            o.runs.add(run);
        }
        return plan;
    }

    /**
     * A generator that sets the initial marking. One join with one token per input keeps the join form (eg.join.inputs);
     * any other marking is written out place by place (eg.marking), and the generator fires it once as one workflow.
     */
    static void planMarking(Plan plan, ServiceRegistry registry, ProcessElement generator, Canvas.GeneratorRole role) {
        List<String> entries = new ArrayList<>();
        for (Canvas.Mark m : role.marks) {
            if (m.place == null || m.place.getServiceOperations().isEmpty() || registry.nodeFor(m.place).isEmpty()) return;  // reported by the canvas checks or the deployment
            entries.add(registry.nodeFor(m.place) + "_Place." + m.place.getServiceOperations().get(0).getName() + "=" + String.join(",", m.inputs));
        }
        Canvas.Mark first = role.marks.get(0);
        String id = generator.getId();
        plan.targets.put(id, registry.nodeFor(first.place) + "_Place");
        plan.operations.put(id, first.place.getServiceOperations().get(0).getName());
        if (first.place.getService() != null && !first.place.getService().isEmpty()) plan.services.put(id, first.place.getService());
        plan.rates.put(id, parse(generator.getGeneratorRate(), 1000));
        plan.versions.put(id, VERSIONS.contains(generator.getTokenVersion()) ? generator.getTokenVersion() : "v001");
        boolean oneJoin = role.marks.size() == 1 && first.join != null && first.count == first.available.size();
        if (oneJoin && first.count < 2) {
            plan.problems.add(id + " feeds the join before " + first.place.getLabel() + ", but " + first.place.getLabel() + "'s operation names "
                + first.count + " input(s); a join needs one argument for each input.");
            return;
        }
        plan.joins.put(id, role.tokens());
        if (!oneJoin) plan.markings.put(id, String.join(";", entries));
    }

    /** "a = 1, b=x" -> "a=1,b=x" (as the generator's -data takes it); "" when blank; null when not name=value pairs. */
    static String tokenData(String text) {
        if (text == null || text.trim().isEmpty()) return "";
        List<String> pairs = new ArrayList<>();
        for (String pair : text.split(",")) {
            int eq = pair.indexOf('=');
            if (eq <= 0 || pair.substring(0, eq).trim().isEmpty() || !pair.substring(0, eq).trim().matches("[A-Za-z_][A-Za-z0-9_.-]*")) return null;
            pairs.add(pair.substring(0, eq).trim() + "=" + pair.substring(eq + 1).trim());
        }
        return String.join(",", pairs);
    }

    /** Choices that cannot run: no generator chosen, or two generators sharing a version (their tokens would collide). */
    public static List<String> problems(Plan plan, Options o) {
        List<String> problems = new ArrayList<>();
        List<Run> on = o.enabled();
        if (on.isEmpty()) problems.add("Choose at least one event generator to run.");
        Map<String, String> byVersion = new LinkedHashMap<>();
        for (Run run : on) {
            String other = byVersion.put(run.version, run.generatorId);
            if (other != null) problems.add(other + " and " + run.generatorId + " both use " + run.version + "; give each event generator its own version.");
        }
        return problems;
    }

    /** Files the launcher needs, in the order they will be written ("reuse" when they exist). */
    public static List<String> summary(Plan plan, Options o) {
        List<String> lines = new ArrayList<>();
        lines.add((plan.launcher(o).exists() ? "Replace  " : "Write    ") + relative(plan.root, plan.launcher(o)));
        lines.add((plan.profileExists ? "Reuse    " : "Write    ") + relative(plan.root, plan.profile));
        for (Run run : o.enabled()) lines.add((plan.schedule(o, run).exists() ? "Replace  " : "Write    ") + relative(plan.root, plan.schedule(o, run)));
        lines.add("Hosts    " + String.join(", ", plan.nodes) + " and Monitor (each host is initialised and collected by the run)");
        if (!plan.tokenGenerator.isEmpty()) lines.add("Tokens   made by " + plan.tokenGenerator + " (named by the deployment profile), each from its target service's token format");
        for (String packaged : packagedHosts(plan)) lines.add("Package  " + packaged + " (Build host packages; start it on its machine)");
        for (Run run : o.enabled())
            if (plan.markings.containsKey(run.generatorId))
                lines.add("Tokens   initial marking from " + run.generatorId + ": " + plan.joins.get(run.generatorId) + " tokens, once, version " + run.version
                    + ": " + plan.markings.get(run.generatorId).replace(";", "; ") + " (they start as one workflow)");
            else if (plan.joins.containsKey(run.generatorId))
                lines.add("Tokens   initial marking from " + run.generatorId + ": " + plan.joins.get(run.generatorId) + " tokens, once, into the join before "
                    + plan.targets.get(run.generatorId) + "." + plan.operations.get(run.generatorId) + ", version " + run.version
                    + " (a closed net needs all its join's inputs marked to start)");
            else lines.add("Tokens   " + o.tokens + " from " + run.generatorId + " to " + plan.targets.get(run.generatorId) + "." + plan.operations.get(run.generatorId)
                + ", one every " + run.intervalMs + " ms, version " + run.version);
        for (Run run : o.enabled())
            if (plan.data.containsKey(run.generatorId)) lines.add("Data     every token from " + run.generatorId + " carries " + plan.data.get(run.generatorId));
        if (o.enabled().size() > 1) lines.add("         The generators fire together; the analysis reports each version separately.");
        for (String problem : problems(plan, o)) lines.add("Problem  " + problem);
        return lines;
    }

    /**
     * The packages for machines other than the launcher's (ip0) under the editor's Machines, one per machine,
     * named after the hosts it starts: "P2 for 192.168.1.152", "P3P6 for 192.168.1.90".
     * Build host packages (in the Build dialog) packages these after writing the launcher.
     */
    public static List<String> packagedHosts(Plan plan) {
        List<ServiceRegistry.Node> nodes = ServiceRegistry.infrastructureNodes(plan.profile);
        String launcherMachine = null;
        for (ServiceRegistry.Node node : nodes) if ("ip0".equals(node.channel)) launcherMachine = node.address;
        List<String> packaged = new ArrayList<>();
        if (launcherMachine == null || !ServiceRegistry.severalMachines(nodes)) return packaged;
        // One package per machine, named after the hosts it starts: "P3P6 for 192.168.1.90".
        Map<String, StringBuilder> byMachine = new LinkedHashMap<>();
        for (String used : plan.nodes)
            for (ServiceRegistry.Node node : nodes)
                if (node.node.equals(used) && !node.address.equals(launcherMachine))
                    byMachine.computeIfAbsent(node.address, a -> new StringBuilder()).append(used);
        for (Map.Entry<String, StringBuilder> m : byMachine.entrySet()) packaged.add(m.getValue() + " for " + m.getKey());
        return packaged;
    }

    /** Writes the launcher and any missing supporting files; returns the launcher. */
    public static File write(Plan plan, Options o) throws IOException {
        if (!plan.problems.isEmpty()) throw new IOException(String.join("\n", plan.problems));
        List<String> choices = problems(plan, o);
        if (!choices.isEmpty()) throw new IOException(String.join("\n", choices));
        if (!plan.profileExists) writeText(plan.profile, profileJson(plan));
        for (Run run : o.enabled()) {
            StringBuilder csv = new StringBuilder();
            Integer join = plan.joins.get(run.generatorId);
            if (join != null) csv.append("0,0,").append(join).append("\n"); // the join's inputs arrive together
            else for (int i = 0; i < o.tokens; i++) csv.append((long)i * run.intervalMs).append(",0,1\n");
            writeText(plan.schedule(o, run), csv.toString());
        }
        File launcher = plan.launcher(o);
        writeText(launcher, launcherXml(plan, o));
        return launcher;
    }

    static String launcherXml(Plan plan, Options o) {
        File dir = o.folder;
        String name = plan.baseName(o).replaceAll("[^A-Za-z0-9_.-]", "_");
        StringBuilder x = new StringBuilder();
        x.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        x.append("<!-- Generated by ProcessEditor (Create Build and Run) for ").append(xml(plan.processName)).append(".\n");
        x.append("     The shared phases live in btsn.services/process-runtime.xml; regenerate this file rather than editing the phases here.\n");
        String machines = ServiceRegistry.selectedInfrastructure();
        boolean single = ServiceRegistry.DEFAULT_INFRASTRUCTURE.equals(machines);
        if (single) x.append("     Run:     ant -f ").append(xml(plan.launcher(o).getName())).append("        (add -Dhost.address=127.0.0.1 to run every host on this machine)\n");
        else x.append("     Hosts:   ant -f ").append(xml(plan.launcher(o).getName())).append(" host-packages   (start each package on its machine first)\n")
              .append("     Run:     ant -f ").append(xml(plan.launcher(o).getName())).append("        (on ").append(xml(machines)).append("; -Dinfrastructure=SingleHost runs on one machine)\n");
        x.append("     Analyse: ant -f ").append(xml(plan.launcher(o).getName())).append(" analyse -->\n");
        x.append("<project name=\"").append(xml(name)).append("\" default=\"run-complete-workflow\" basedir=\".\">\n");
        x.append("    <property name=\"process.title\" value=\"").append(xml(plan.processName)).append("\"/>\n");
        x.append("    <property name=\"launcher.profile.file\" location=\"").append(xml(relative(dir, plan.profile))).append("\"/>\n");
        if (!plan.tokenGenerator.isEmpty())
            x.append("    <property name=\"token.generator.class\" value=\"").append(xml(plan.tokenGenerator)).append("\"/>\n");
        x.append("    <property name=\"workflow.process.name\" value=\"").append(xml(plan.processName)).append("\"/>\n");
        List<Run> on = o.enabled();
        List<String> versions = new ArrayList<>(); for (Run run : on) versions.add(run.version);
        x.append("    <property name=\"rule.version\" value=\"").append(on.get(0).version).append("\"/>\n");
        x.append("    <property name=\"query.version\" value=\"").append(String.join(",", versions)).append("\"/>\n\n");
        x.append("    <!-- Tokens: one block per event generator, each with its own rule version, target place and schedule.\n");
        x.append("         The generators fire together. -->\n");
        for (int i = 0; i < on.size(); i++) {
            Run run = on.get(i); String slot = "eg" + (i + 1);
            x.append("    <property name=\"").append(slot).append(".id\" value=\"").append(xml(run.generatorId)).append("\"/>\n");
            x.append("    <property name=\"").append(slot).append(".version\" value=\"").append(run.version).append("\"/>\n");
            x.append("    <property name=\"").append(slot).append(".place\" value=\"").append(plan.targets.get(run.generatorId)).append("\"/>\n");
            x.append("    <property name=\"").append(slot).append(".operation\" value=\"").append(xml(plan.operations.get(run.generatorId))).append("\"/>\n");
            x.append("    <property name=\"").append(slot).append(".trigger.file\" location=\"").append(xml(relative(dir, plan.schedule(o, run)))).append("\"/>\n");
            if (!plan.tokenGenerator.isEmpty() && plan.services.containsKey(run.generatorId))
                x.append("    <property name=\"").append(slot).append(".format.service\" value=\"").append(xml(plan.services.get(run.generatorId))).append("\"/>\n");
            if (plan.data.containsKey(run.generatorId))
                x.append("    <property name=\"").append(slot).append(".data\" value=\"").append(xml(plan.data.get(run.generatorId))).append("\"/>\n");
            Integer join = plan.joins.get(run.generatorId);
            if (join != null && plan.markings.containsKey(run.generatorId)) {
                x.append("    <!-- ").append(xml(run.generatorId)).append(" sets the initial marking, once: these tokens, in these places, as one workflow. -->\n");
                x.append("    <property name=\"").append(slot).append(".tokens\" value=\"").append(join).append("\"/>\n");
                x.append("    <property name=\"").append(slot).append(".marking\" value=\"").append(xml(plan.markings.get(run.generatorId))).append("\"/>\n");
            } else if (join != null) {
                x.append("    <!-- ").append(xml(run.generatorId)).append(" feeds a join: it fires one token for each of the join's inputs. -->\n");
                x.append("    <property name=\"").append(slot).append(".tokens\" value=\"").append(join).append("\"/>\n");
                x.append("    <property name=\"").append(slot).append(".join.inputs\" value=\"").append(join).append("\"/>\n");
            }
        }
        x.append("    <property name=\"token.count\" value=\"").append(o.tokens).append("\"/>\n");
        x.append("    <property name=\"workflow.completion.seconds\" value=\"").append(o.completionSeconds).append("\"/>\n\n");
        x.append("    <!-- Hosts used by the service deployment: nodes of the infrastructure, each running the host program -->\n");
        x.append("    <property name=\"hosts\" value=\"").append(String.join(",", plan.nodes)).append("\"/>\n\n");
        if (!single) {
            x.append("    <!-- The machines chosen in the editor: InfrastructureDefinitionFolder/").append(xml(machines)).append(".json -->\n");
            x.append("    <property name=\"infrastructure\" value=\"").append(xml(machines)).append("\"/>\n\n");
        }
        x.append("    <import file=\"").append(xml(relative(dir, new File(plan.root, "btsn.services/process-runtime.xml")))).append("\"/>\n");
        x.append("</project>\n");
        return x.toString();
    }

    static String profileJson(Plan plan) {
        return "{\n"
            + "  \"catalog\": \"" + plan.catalogue + "\",\n"
            + "  \"infrastructure\": \"InfrastructureDefinitionFolder/SingleHost.json\",\n"
            + "  \"deploymentRules\": \"RuleBase/Generated/InfrastructureDeployment.ruleml.xml\",\n"
            + "  \"directServiceRules\": [\n"
            + "    \"RuleBase/Generated/HostAdministration.ruleml.xml\"\n"
            + "  ],\n"
            + "  \"serviceDeployment\": \"" + plan.serviceDeployment + "\"\n"
            + "}\n";
    }

    /** The place reached from an event generator through its transition (generator -> T_in -> place). */
    /** The number of inputs of the join a generator feeds through its T_in (its place's arguments), or -1 when that T_in is not a join. */
    static int joinInputs(Canvas canvas, ProcessElement generator, ProcessElement place) {
        for (Arrow out : canvas.getArrows()) {
            ProcessElement next = out.getTarget();
            if (out.getSource() != generator || next == null || !"JoinNode".equals(next.getNodeType())) continue;
            boolean feedsPlace = false;
            for (Arrow onward : canvas.getArrows()) if (onward.getSource() == next && onward.getTarget() == place) feedsPlace = true;
            if (feedsPlace) return place.getServiceOperations().get(0).getArguments().size();
        }
        return -1;
    }

    static ProcessElement firstPlaceAfter(Canvas canvas, ProcessElement generator) {
        for (Arrow out : canvas.getArrows()) {
            if (out.getSource() != generator) continue;
            ProcessElement next = out.getTarget();
            if (next != null && next.getType() == ProcessElement.Type.PLACE) return next;
            for (Arrow onward : canvas.getArrows())
                if (onward.getSource() == next && onward.getTarget() != null && onward.getTarget().getType() == ProcessElement.Type.PLACE) return onward.getTarget();
        }
        return null;
    }

    private static List<File> jsonFiles(File dir) {
        List<File> files = new ArrayList<>();
        File[] children = dir.listFiles();
        if (children == null) return files;
        Arrays.sort(children);
        for (File child : children) if (child.isDirectory()) files.addAll(jsonFiles(child)); else if (child.getName().endsWith(".json")) files.add(child);
        return files;
    }
    static String relative(File from, File to) {
        return from.toPath().toAbsolutePath().normalize().relativize(to.toPath().toAbsolutePath().normalize()).toString().replace(File.separatorChar, '/');
    }
    private static int parse(String value, int fallback) {
        try { int v = Integer.parseInt(value == null ? "" : value.trim()); return v > 0 ? v : fallback; } catch (NumberFormatException ex) { return fallback; }
    }
    private static String read(File file) throws IOException { return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8); }
    private static void writeText(File file, String text) throws IOException {
        Files.createDirectories(file.getParentFile().toPath());
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }
    private static String xml(String s) { return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;"); }
}
