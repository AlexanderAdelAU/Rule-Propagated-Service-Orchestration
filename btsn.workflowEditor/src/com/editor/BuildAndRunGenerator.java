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
 * Missing supporting files are written too: the deployment profile, the token schedule (CSV) and an
 * initialiser/collector pair for the hosts the deployment uses. Existing files are reused.
 */
public final class BuildAndRunGenerator {
    public static final List<String> VERSIONS = Arrays.asList("v001", "v002", "v003");
    private static final List<String> NODES = Arrays.asList("P1", "P2", "P3", "P4", "P5", "P6");

    /** Choices the user makes; defaults come from the process. */
    public static final class Options {
        public String launcherName = "";
        public File folder;
        public String generatorId = "";
        public String version = "v001";
        public int tokens = 10;
        public int intervalMs = 1000;
        public int completionSeconds = 10;
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
        public File profile; public boolean profileExists;
        public String serviceDeployment = "", catalogue = "";
        public String nodeSetName = "";
        public File initializer, collector; public boolean initializerExists, collectorExists;
        public final List<File> folders = new ArrayList<>();
        public Options defaults = new Options();

        public File launcher(Options o) { return new File(o.folder, o.launcherName.endsWith(".xml") ? o.launcherName : o.launcherName + ".xml"); }
        public File schedule(Options o) { return new File(root, "btsn.common.eventgenerators/EventTriggeringFile/" + baseName(o) + ".csv"); }
        public String baseName(Options o) { return launcher(o).getName().replaceFirst("\\.xml$", ""); }
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
        if ("healthcare".equals(plan.domain)) plan.problems.add("Healthcare processes use their own token generator; generating their launchers is not supported yet.");

        ServiceRegistry registry = canvas.getServiceRegistry();
        if (!registry.hasDeployment()) { plan.problems.add("Deploy the process first: no service deployment is linked."); return plan; }
        plan.serviceDeployment = canvas.getServiceDeploymentReference();
        plan.catalogue = registry.catalogueReference();
        Set<String> used = new TreeSet<>(Comparator.comparingInt(NODES::indexOf));
        for (ProcessElement place : canvas.getPlaces()) {
            String node = registry.nodeFor(place);
            if (node.isEmpty() || canvas.hasPendingNode(place)) plan.problems.add("Place " + place.getLabel() + " is not deployed; use Deploy and save the deployment.");
            else if (!NODES.contains(node)) plan.problems.add("Place " + place.getLabel() + " runs on " + node + "; launchers start hosts P1 to P6 only.");
            else used.add(node);
        }
        plan.problems.addAll(canvas.nodeConflicts());
        plan.nodes.addAll(used);

        for (ProcessElement element : canvas.getElements()) {
            if (element.getType() != ProcessElement.Type.EVENT_GENERATOR) continue;
            ProcessElement place = firstPlaceAfter(canvas, element);
            if (place == null || place.getServiceOperations().isEmpty() || registry.nodeFor(place).isEmpty()) continue;
            plan.targets.put(element.getId(), registry.nodeFor(place) + "_Place");
            plan.operations.put(element.getId(), place.getServiceOperations().get(0).getName());
            plan.rates.put(element.getId(), parse(element.getGeneratorRate(), 1000));
            plan.versions.put(element.getId(), VERSIONS.contains(element.getTokenVersion()) ? element.getTokenVersion() : "v001");
        }
        if (plan.targets.isEmpty()) plan.problems.add("Add an event generator connected through a T_in transition to a deployed place.");
        if (!plan.problems.isEmpty()) return plan;

        // Deployment profile: reuse one that already selects this service deployment.
        File deployments = new File(plan.root, "btsn.services/deployments");
        for (File candidate : jsonFiles(deployments)) {
            try {
                JSONObject profile = (JSONObject)new JSONParser().parse(read(candidate));
                if (plan.serviceDeployment.equals(profile.get("serviceDeployment"))) { plan.profile = candidate; plan.profileExists = true; break; }
            } catch (Exception ignored) { }
        }
        String base = processFile.getName().replaceFirst("(?i)\\.json$", "");
        if (plan.profile == null) plan.profile = new File(deployments, ("petrinet".equals(plan.domain) || plan.domain.isEmpty() ? "models" : plan.domain) + "/" + base + "Deployment.json");

        plan.nodeSetName = plan.nodes.size() == NODES.size() ? "P1_to_P6" : String.join("_", plan.nodes);
        plan.initializer = new File(common, "ProcessDefinitionFolder/common/Initializers/" + plan.nodeSetName + "_Initialization.json");
        plan.collector = new File(common, "ProcessDefinitionFolder/common/Collectors/" + plan.nodeSetName + "_Collector.json");
        plan.initializerExists = plan.initializer.isFile();
        plan.collectorExists = plan.collector.isFile();

        File[] loaders = plan.root.listFiles(f -> f.isDirectory() && f.getName().matches("btsn\\..+\\.ProjectLoader"));
        if (loaders != null) { Arrays.sort(loaders); plan.folders.addAll(Arrays.asList(loaders)); }
        File preferred = new File(plan.root, "btsn." + (plan.domain.isEmpty() ? "petrinet" : plan.domain) + ".ProjectLoader");
        Options o = plan.defaults;
        o.folder = preferred.isDirectory() ? preferred : plan.folders.isEmpty() ? plan.root : plan.folders.get(0);
        o.launcherName = base + "_BuildAndRun.xml";
        o.generatorId = plan.targets.keySet().iterator().next();
        o.intervalMs = plan.rates.get(o.generatorId);
        o.version = plan.versions.get(o.generatorId);
        return plan;
    }

    /** Files the launcher needs, in the order they will be written ("reuse" when they exist). */
    public static List<String> summary(Plan plan, Options o) {
        List<String> lines = new ArrayList<>();
        lines.add((plan.launcher(o).exists() ? "Replace  " : "Write    ") + relative(plan.root, plan.launcher(o)));
        lines.add((plan.profileExists ? "Reuse    " : "Write    ") + relative(plan.root, plan.profile));
        lines.add((plan.schedule(o).exists() ? "Replace  " : "Write    ") + relative(plan.root, plan.schedule(o)));
        lines.add((plan.initializerExists ? "Reuse    " : "Write    ") + relative(plan.root, plan.initializer));
        lines.add((plan.collectorExists ? "Reuse    " : "Write    ") + relative(plan.root, plan.collector));
        lines.add("Hosts    " + String.join(", ", plan.nodes) + " and Monitor");
        lines.add("Tokens   " + o.tokens + " from " + o.generatorId + " to " + plan.targets.get(o.generatorId) + "." + plan.operations.get(o.generatorId)
            + ", one every " + o.intervalMs + " ms, version " + o.version);
        return lines;
    }

    /** Writes the launcher and any missing supporting files; returns the launcher. */
    public static File write(Plan plan, Options o) throws IOException {
        if (!plan.problems.isEmpty()) throw new IOException(String.join("\n", plan.problems));
        if (!plan.profileExists) writeText(plan.profile, profileJson(plan));
        StringBuilder csv = new StringBuilder();
        for (int i = 0; i < o.tokens; i++) csv.append((long)i * o.intervalMs).append(",0,1\n");
        writeText(plan.schedule(o), csv.toString());
        if (!plan.initializerExists) writeText(plan.initializer, adminProcess(plan.nodes, true, plan.nodeSetName));
        if (!plan.collectorExists) writeText(plan.collector, adminProcess(plan.nodes, false, plan.nodeSetName));
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
        x.append("     Run:     ant -f ").append(xml(plan.launcher(o).getName())).append("        (add -Dhost.address=127.0.0.1 to run every host on this machine)\n");
        x.append("     Analyse: ant -f ").append(xml(plan.launcher(o).getName())).append(" analyse -->\n");
        x.append("<project name=\"").append(xml(name)).append("\" default=\"run-complete-workflow\" basedir=\".\">\n");
        x.append("    <property name=\"process.title\" value=\"").append(xml(plan.processName)).append("\"/>\n");
        x.append("    <property name=\"launcher.profile.file\" location=\"").append(xml(relative(dir, plan.profile))).append("\"/>\n");
        x.append("    <property name=\"workflow.process.name\" value=\"").append(xml(plan.processName)).append("\"/>\n");
        x.append("    <property name=\"init.process.name\" value=\"common/Initializers/").append(plan.nodeSetName).append("_Initialization\"/>\n");
        x.append("    <property name=\"collector.process.name\" value=\"common/Collectors/").append(plan.nodeSetName).append("_Collector\"/>\n");
        x.append("    <property name=\"rule.version\" value=\"").append(o.version).append("\"/>\n\n");
        x.append("    <!-- Tokens: event generator, target place and schedule -->\n");
        x.append("    <property name=\"event.generator.id\" value=\"").append(xml(o.generatorId)).append("\"/>\n");
        x.append("    <property name=\"target.place\" value=\"").append(plan.targets.get(o.generatorId)).append("\"/>\n");
        x.append("    <property name=\"target.operation\" value=\"").append(xml(plan.operations.get(o.generatorId))).append("\"/>\n");
        x.append("    <property name=\"token.count\" value=\"").append(o.tokens).append("\"/>\n");
        x.append("    <property name=\"trigger.file\" location=\"").append(xml(relative(dir, plan.schedule(o)))).append("\"/>\n");
        x.append("    <property name=\"workflow.completion.seconds\" value=\"").append(o.completionSeconds).append("\"/>\n\n");
        x.append("    <!-- Hosts used by the service deployment -->\n");
        for (String node : plan.nodes) x.append("    <property name=\"run.").append(node.toLowerCase(Locale.ROOT)).append("\" value=\"true\"/>\n");
        x.append("    <property name=\"first.node\" value=\"").append(plan.nodes.get(0).toLowerCase(Locale.ROOT)).append("\"/>\n\n");
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
            + "    \"RuleBase/PetriNet/ListofPetriNetInitializationServices.ruleml.xml\",\n"
            + "    \"RuleBase/PetriNet/ListofPetrinetCollectorServices.ruleml.xml\"\n"
            + "  ],\n"
            + "  \"serviceDeployment\": \"" + plan.serviceDeployment + "\"\n"
            + "}\n";
    }

    /** Initialiser (purgeAndInitialize on each host and Monitor) or collector (each host reports to Monitor). */
    static String adminProcess(List<String> nodes, boolean initializer, String nodeSetName) {
        List<String> columns = new ArrayList<>(nodes); columns.add("Monitor");
        String generator = initializer ? "INTIALISATION_GENERATOR" : "COLLECTION_GENERATOR";
        List<String> elements = new ArrayList<>(), arrows = new ArrayList<>();
        int generatorX = 110 + (columns.size() - 1) * 40;
        elements.add(element(generator, "EVENT_GENERATOR", generatorX, 40, 24, 60,
            "      \"rotation\": 90.0,\n      \"generator_rate\": \"1000\",\n      \"token_version\": \"v001\""));
        for (int i = 0; i < columns.size(); i++) {
            String column = columns.get(i);
            boolean monitor = "Monitor".equals(column);
            int x = 110 + i * 80;
            elements.add(element("T_in_" + column, "TRANSITION", x, 150, 20, 60,
                "      \"rotation\": 90.0,\n      \"node_type\": \"EdgeNode\",\n      \"node_value\": \"EDGE_NODE\",\n      \"transition_type\": \"T_in\",\n      \"buffer\": \"10\""));
            String service = initializer ? (monitor ? "Monitor_InitializationService" : column + "_InitializationService")
                                         : (monitor ? "MonitorService" : column + "_CollectorService");
            String operation = initializer ? "purgeAndInitialize" : monitor ? "writeCollectorData" : "collectAllData";
            elements.add(element(column, "PLACE", x - 15, 255, 50, 50,
                "      \"service\": \"" + service + "\",\n      \"operations\": [\n        {\n          \"name\": \"" + operation + "\"\n        }\n      ]"));
            boolean terminates = initializer || monitor;
            elements.add(element("T_out_" + column, "TRANSITION", x, 360, 20, 60,
                "      \"rotation\": 90.0,\n      \"node_type\": \"" + (terminates ? "TerminateNode" : "EdgeNode") + "\",\n      \"node_value\": \""
                + (terminates ? "TERMINATE_NODE" : "EDGE_NODE") + "\",\n      \"transition_type\": \"T_out\""));
            arrows.add(arrow("T_in_" + column, column));
            arrows.add(arrow(column, "T_out_" + column));
            if (initializer || !monitor) arrows.add(arrow(generator, "T_in_" + column));
            if (!initializer && !monitor) arrows.add(arrow("T_out_" + column, "T_in_Monitor"));
        }
        return "{\n   \"processType\":\"PetriNet\",\n  \"elements\": [\n" + String.join(",\n", elements) + "\n  ],\n  \"arrows\": [\n"
            + String.join(",\n", arrows) + "\n  ],\n  \"textElements\": [\n    {\n      \"x\": 110,\n      \"y\": 20,\n      \"text\": \""
            + nodeSetName + (initializer ? "_Initialization" : "_Collector") + " (generated)\",\n      \"fontSize\": 12,\n      \"fontStyle\": 0,\n      \"color\": -16777216\n    }\n  ]\n}\n";
    }

    private static String element(String id, String type, int x, int y, int w, int h, String extra) {
        return "    {\n      \"id\": \"" + id + "\",\n      \"type\": \"" + type + "\",\n      \"x\": " + x + ",\n      \"y\": " + y
            + ",\n      \"width\": " + w + ",\n      \"height\": " + h + ",\n      \"label\": \"" + id + "\",\n" + extra + "\n    }";
    }
    private static String arrow(String source, String target) {
        return "    {\n      \"source\": \"" + source + "\",\n      \"target\": \"" + target
            + "\",\n      \"label\": \"\",\n      \"guardCondition\": \"\",\n      \"decision_value\": \"\",\n      \"endpoint\": \"\"\n    }";
    }

    /** The place reached from an event generator through its transition (generator -> T_in -> place). */
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
