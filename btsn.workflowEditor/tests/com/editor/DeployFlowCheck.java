package com.editor;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.*;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

/**
 * Designs a process from the catalogue only, places it on nodes from the canvas and the Deploy panel,
 * saves the deployment and checks that the process is linked to it (requires a display or xvfb-run).
 */
public final class DeployFlowCheck {
    public static void main(String[] args) {
        // Always exit: a failed check can leave Swing windows open, which would keep the JVM running.
        try { run(args); System.exit(0); }
        catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }

    private static void run(String[] args) throws Exception {
        File repository = new File(args[0]).getCanonicalFile(), common = new File(repository, "btsn.common");
        File output = new File(args[1]);
        // A scratch project, so the check never writes into the repository's definition folders.
        Path scratch = Files.createTempDirectory("deploy-flow-");
        Path scratchCommon = scratch.resolve("btsn.common");
        copy(common.toPath().resolve("BusinessServiceDefinitions"), scratchCommon.resolve("BusinessServiceDefinitions"));
        copy(common.toPath().resolve("InfrastructureDefinitionFolder"), scratchCommon.resolve("InfrastructureDefinitionFolder"));
        copy(common.toPath().resolve("ProcessDefinitionFolder/common"), scratchCommon.resolve("ProcessDefinitionFolder/common"));
        Files.createDirectories(scratch.resolve("btsn.services/deployments/models"));
        Files.createDirectories(scratch.resolve("btsn.petrinet.ProjectLoader"));
        Files.createDirectories(scratchCommon.resolve("ServiceDeploymentFolder/petrinet"));
        Path processFile = scratchCommon.resolve("ProcessDefinitionFolder/petrinet/Workflow/TrafficLight_Design.json");
        Files.createDirectories(processFile.getParent());

        // A design that names its places by business meaning and has never been deployed.
        String design = read(common.toPath().resolve("ProcessDefinitionFolder/petrinet/Workflow/TrafficLight_Workflow.json"))
            .replaceAll(",\\s*\"serviceDeployment\"\\s*:\\s*\"[^\"]*\"", "")
            .replaceAll(",\\s*\"serviceInstance\"\\s*:\\s*\"[^\"]*\"", "");
        check(!design.contains("serviceDeployment") && !design.contains("serviceInstance"), "Design fixture still deployed");
        Files.write(processFile, design.getBytes(StandardCharsets.UTF_8));

        final Throwable[] failure = new Throwable[1];
        SwingUtilities.invokeAndWait(() -> {
            InfrastructureDefinitionFrame panel = null;
            try {
                Canvas canvas = new Canvas();
                canvas.setDefinitionLocation(processFile.toFile());
                canvas.loadFromJSON(read(processFile));
                ServiceRegistry registry = canvas.getServiceRegistry();
                check(!registry.hasDeployment() && registry.problem() == null, "Domain catalogue not selected: " + registry.problem());
                check("PetriNetModels.json".equals(registry.catalogueFile().getName()), "Wrong catalogue: " + registry.catalogueFile());
                check(canvas.validateServiceContracts().isEmpty(), "Undeployed design rejected: " + canvas.validateServiceContracts());
                check(canvas.validatePetriNet().stream().noneMatch(w -> w.contains("not in the linked service deployment")), "Undeployed design reported as partially deployed");
                JSONObject saved = (JSONObject)new JSONParser().parse(canvas.saveToJSON());
                check("BusinessServiceDefinitions/petrinet/PetriNetModels.json".equals(saved.get("catalog")) && !saved.containsKey("serviceDeployment"), "Design does not record its catalogue");

                Map<String, ProcessElement> places = new LinkedHashMap<>();
                for (ProcessElement place : canvas.getPlaces()) { places.put(place.getLabel(), place); check(canvas.nodeFor(place).isEmpty(), "Undeployed place has a node"); }
                check(places.size() == 6, "Expected six places");

                // Right-click a place: Deploy to lists every infrastructure node; choose P6 for NS_Green.
                ProcessElement green = places.get("NS_Green");
                JPopupMenu popup = new JPopupMenu();
                Method addDeployMenu = Canvas.class.getDeclaredMethod("addDeployMenu", JPopupMenu.class, ProcessElement.class); addDeployMenu.setAccessible(true);
                addDeployMenu.invoke(canvas, popup, green);
                JMenu deployTo = (JMenu)popup.getComponent(0);
                check("Deploy to".equals(deployTo.getText()) && deployTo.getItemCount() == 6, "Deploy to menu does not list six nodes");
                check(deployTo.getItem(5).getText().startsWith("P6"), "Nodes out of order: " + deployTo.getItem(5).getText());
                deployTo.getItem(5).doClick();
                check("P6".equals(canvas.nodeFor(green)) && canvas.hasPendingNode(green), "Right-click choice not recorded");

                // Deploy: one row per place, Place column first, nodes suggested around the canvas choice.
                panel = InfrastructureDefinitionFrame.openForProcess(null, canvas, processFile.toFile());
                JTable table = (JTable)field(panel, "capabilityTable");
                check("Place".equals(table.getColumnName(0)) && table.getRowCount() == 6, "Deploy panel rows/columns: " + table.getRowCount());
                Map<String, String> nodeByPlace = new HashMap<>(), instanceByPlace = new HashMap<>();
                for (int row = 0; row < table.getRowCount(); row++) {
                    String place = String.valueOf(table.getValueAt(row, 0));
                    nodeByPlace.put(place, String.valueOf(table.getModel().getValueAt(row, 0)));
                    instanceByPlace.put(place, String.valueOf(table.getModel().getValueAt(row, 5)));
                    check("boolean-token".equals(table.getModel().getValueAt(row, 6)), "Boolean adapter not selected for " + place);
                }
                check("P6".equals(nodeByPlace.get("NS_Green")), "Canvas choice not used: " + nodeByPlace);
                check(new HashSet<>(nodeByPlace.values()).size() == 6, "Suggested nodes collide: " + nodeByPlace);
                check("NSGreen".equals(instanceByPlace.get("NS_Green")) && "EWRedHold".equals(instanceByPlace.get("EW_Red_Hold")), "Instances not named after places: " + instanceByPlace);
                check(((List<?>)call(panel, "validateDefinition")).isEmpty(), "Filled deployment invalid: " + call(panel, "validateDefinition"));
                check(panel.getTitle().endsWith("*"), "New deployment not marked unsaved");

                // Editing a node in the panel moves the label on the canvas; a canvas choice updates the panel.
                int greenRow = rowOf(table, "NS_Green"), yellowRow = rowOf(table, "NS_Yellow");
                String yellowNode = String.valueOf(table.getModel().getValueAt(yellowRow, 0));
                table.getModel().setValueAt("P6", yellowRow, 0);
                table.getModel().setValueAt(yellowNode, greenRow, 0);
                check("P6".equals(canvas.nodeFor(places.get("NS_Yellow"))) && yellowNode.equals(canvas.nodeFor(green)), "Panel edits not reflected on canvas");

                // Save (the file chooser step is skipped): the process links to the deployment and its instances.
                Path deploymentFile = scratchCommon.resolve("ServiceDeploymentFolder/petrinet/TrafficLight_Design.json");
                Files.write(deploymentFile, ((String)call(panel, "toJson")).getBytes(StandardCharsets.UTF_8));
                Field current = InfrastructureDefinitionFrame.class.getDeclaredField("currentFile"); current.setAccessible(true); current.set(panel, deploymentFile.toFile());
                Method link = InfrastructureDefinitionFrame.class.getDeclaredMethod("linkProcess", File.class); link.setAccessible(true);
                link.invoke(panel, deploymentFile.toFile());
                JSONObject deployment = (JSONObject)new JSONParser().parse(read(deploymentFile));
                check("BusinessServiceDefinitions/petrinet/PetriNetModels.json".equals(deployment.get("catalog")), "Deployment does not name its catalogue");
                check("ServiceDeploymentFolder/petrinet/TrafficLight_Design.json".equals(canvas.getServiceDeploymentReference()), "Process not linked: " + canvas.getServiceDeploymentReference());
                for (ProcessElement place : canvas.getPlaces()) {
                    check(registry.isDeployed(place) && !canvas.hasPendingNode(place), "Place not deployed after save: " + place.getLabel());
                    check(instanceByPlace.get(place.getLabel()).equals(place.getServiceInstance()), "Instance not recorded on " + place.getLabel());
                }
                check("P6".equals(canvas.nodeFor(places.get("NS_Yellow"))), "Saved node lost");
                check(canvas.validateServiceContracts().isEmpty() && canvas.deploymentGaps().isEmpty(), "Linked process invalid: " + canvas.validateServiceContracts() + canvas.deploymentGaps());
                String linked = canvas.saveToJSON();
                JSONObject linkedJson = (JSONObject)new JSONParser().parse(linked);
                check(linkedJson.containsKey("serviceDeployment") && !linkedJson.containsKey("catalog"), "Linked process should name its deployment only");

                // Create Build and Run: the launcher, profile and schedule come from the saved, deployed process.
                Files.write(processFile, linked.getBytes(StandardCharsets.UTF_8));
                BuildAndRunGenerator.Plan plan = BuildAndRunGenerator.plan(canvas, processFile.toFile());
                check(plan.problems.isEmpty(), "Deployed process not ready for Build and Run: " + plan.problems);
                check("petrinet/Workflow/TrafficLight_Design".equals(plan.processName) && plan.nodes.size() == 6 && "P1_to_P6".equals(plan.nodeSetName), "Plan: " + plan.processName + plan.nodes);
                check(plan.initializerExists && plan.collectorExists && !plan.profileExists, "Existing P1_to_P6 admin processes not reused / profile not new");
                String target = canvas.nodeFor(green) + "_Place";
                check(target.equals(plan.targets.get("TRAFFICLIGHT_EVENTGENERATOR")), "Generator target: " + plan.targets);
                BuildAndRunDialog dialog = new BuildAndRunDialog(null, plan);
                dialog.tokens.setValue(4); dialog.interval.setValue(250);
                File launcher = dialog.createFiles(); dialog.dispose();
                check(launcher.equals(scratch.resolve("btsn.petrinet.ProjectLoader/TrafficLight_Design_BuildAndRun.xml").toFile()), "Launcher location: " + launcher);
                String xml = read(launcher.toPath());
                for (String expected : new String[] {"value=\"petrinet/Workflow/TrafficLight_Design\"", "value=\"common/Initializers/P1_to_P6_Initialization\"",
                        "name=\"target.place\" value=\"" + target + "\"", "name=\"token.count\" value=\"4\"", "name=\"run.p6\" value=\"true\"",
                        "location=\"../btsn.services/deployments/models/TrafficLight_DesignDeployment.json\"", "<import file=\"../btsn.services/process-runtime.xml\"/>"})
                    check(xml.contains(expected), "Launcher lacks " + expected + "\n" + xml);
                javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(launcher);
                check(read(scratch.resolve("btsn.common.eventgenerators/EventTriggeringFile/TrafficLight_Design_BuildAndRun.csv")).equals("0,0,1\n250,0,1\n500,0,1\n750,0,1\n"), "Token schedule");
                JSONObject profile = (JSONObject)new JSONParser().parse(read(scratch.resolve("btsn.services/deployments/models/TrafficLight_DesignDeployment.json")));
                check("ServiceDeploymentFolder/petrinet/TrafficLight_Design.json".equals(profile.get("serviceDeployment"))
                    && "BusinessServiceDefinitions/petrinet/PetriNetModels.json".equals(profile.get("catalog")), "Profile: " + profile);
                // A host set without admin processes gets a generated initialiser and collector.
                JSONObject collector = (JSONObject)new JSONParser().parse(BuildAndRunGenerator.adminProcess(Arrays.asList("P3", "P5"), false, "P3_P5"));
                JSONObject initializer = (JSONObject)new JSONParser().parse(BuildAndRunGenerator.adminProcess(Arrays.asList("P3", "P5"), true, "P3_P5"));
                check(collector.toJSONString().contains("P5_CollectorService") && collector.toJSONString().contains("writeCollectorData")
                    && initializer.toJSONString().contains("P3_InitializationService") && initializer.toJSONString().contains("Monitor_InitializationService"), "Generated admin processes");
                Canvas undeployed = new Canvas(); undeployed.setDefinitionLocation(processFile.toFile()); undeployed.loadFromJSON(design);
                check(BuildAndRunGenerator.plan(undeployed, processFile.toFile()).problems.stream().anyMatch(p -> p.startsWith("Deploy the process first")), "Undeployed process accepted for Build and Run");

                // Reopening shows the deployed nodes; a new place is reported until it is deployed.
                Canvas reopened = new Canvas(); reopened.setDefinitionLocation(processFile.toFile()); reopened.loadFromJSON(linked);
                for (ProcessElement place : reopened.getPlaces()) check(!reopened.nodeFor(place).isEmpty(), "Reopened place lost its node: " + place.getLabel());
                ProcessElement extra = reopened.getPlaces().get(0); extra.setServiceInstance("");
                check(!reopened.deploymentGaps().isEmpty() && reopened.validateServiceContracts().isEmpty(), "Undeployed new place should warn, not block saving");

                // Attributes panel offers the catalogue and shows where the place runs.
                EditorFrame attributes = new EditorFrame(canvas); attributes.updateSelection(places.get("NS_Yellow"));
                List<String> texts = texts(attributes);
                check(texts.contains("Choose catalogue...") && !texts.contains("Choose service deployment..."), "Attributes still ask for a deployment first");
                check(texts.stream().anyMatch(t -> t.startsWith("P6")), "Attributes do not show the node: " + texts);

                // Pictures for review: the canvas with node labels, and the Deploy panel.
                canvas.setSize(1150, 560); canvas.setPreferredSize(new Dimension(1150, 560));
                BufferedImage picture = new BufferedImage(1150, 560, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = picture.createGraphics(); g.setColor(Color.WHITE); g.fillRect(0, 0, 1150, 560); canvas.paint(g); g.dispose();
                ImageIO.write(picture, "png", new File(output, "deploy-canvas.png"));
                InfrastructureDefinitionFrame fresh = InfrastructureDefinitionFrame.openForProcess(null, canvas, processFile.toFile());
                fresh.setSize(1050, 640); fresh.validate();
                BufferedImage window = new BufferedImage(fresh.getContentPane().getWidth(), fresh.getContentPane().getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D wg = window.createGraphics(); fresh.getContentPane().paint(wg); wg.dispose();
                ImageIO.write(window, "png", new File(output, "deploy-panel.png"));
                check(!fresh.getTitle().endsWith("*"), "Reopened linked deployment marked unsaved");
                fresh.dispose();
            } catch (Throwable ex) {
                failure[0] = ex instanceof InvocationTargetException ? ex.getCause() : ex;
            } finally {
                if (panel != null) panel.dispose();
            }
        });
        if (failure[0] != null) throw new AssertionError("Deploy flow regression", failure[0]);

        // An existing deployed process: every row shows its place, and nothing is added.
        SwingUtilities.invokeAndWait(() -> {
            InfrastructureDefinitionFrame panel = null;
            try {
                File traffic = new File(common, "ProcessDefinitionFolder/petrinet/Workflow/TrafficLight_Workflow.json");
                Canvas canvas = new Canvas(); canvas.setDefinitionLocation(traffic); canvas.loadFromJSON(read(traffic.toPath()));
                // A real right-click in the middle of a place, where its arrows also pass, opens the place menu.
                JFrame window = new JFrame(); window.add(new JScrollPane(canvas)); window.setSize(1200, 650); window.setVisible(true);
                for (ProcessElement place : canvas.getPlaces()) {
                    Point centre = place.getCenter();
                    canvas.dispatchEvent(new java.awt.event.MouseEvent(canvas, java.awt.event.MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(),
                        java.awt.event.InputEvent.BUTTON3_DOWN_MASK, centre.x, centre.y, 1, true, java.awt.event.MouseEvent.BUTTON3));
                    check(canvas.lastContextMenu != null && canvas.lastContextMenu.getComponent(0) instanceof JMenu
                        && "Deploy to".equals(((JMenu)canvas.lastContextMenu.getComponent(0)).getText()), "Right-click on " + place.getLabel() + " opened the arrow menu");
                    canvas.lastContextMenu.setVisible(false);
                }
                window.dispose();
                canvas.setDefinitionLocation(traffic);
                panel = InfrastructureDefinitionFrame.openForProcess(null, canvas, traffic);
                JTable table = (JTable)field(panel, "capabilityTable");
                check(table.getRowCount() == 6 && !panel.getTitle().endsWith("*"), "Existing deployment changed by Deploy");
                int green = rowOf(table, "NS_Green");
                check(green >= 0 && "P1".equals(table.getModel().getValueAt(green, 0)), "NS_Green not shown on P1");
                for (int row = 0; row < table.getRowCount(); row++) check(!String.valueOf(table.getValueAt(row, 0)).isEmpty(), "Row without place");
                List<String> buttons = texts(panel.getContentPane());
                check(buttons.contains("Save deployment...") && !buttons.contains("Generate Bindings"), "Deploy panel should offer Save deployment only: " + buttons);
            } catch (Throwable ex) { failure[0] = ex; }
            finally { if (panel != null) panel.dispose(); }
        });
        if (failure[0] != null) throw new AssertionError("Existing deployment regression", failure[0]);
        // An analysis file with an earlier run appended in front replays only its last run.
        String older = "Auto-detected 1 workflow bases: [1000000]\nTime=1770648319905 Token=1000000 Place=P1_Place Marking=0 Buffer=0 ToPlace=P1_Place TransitionId=EG EventType=GENERATED\n";
        String latest = "Auto-detected 1 workflow bases: [1000000]\nTime=1791616256378 Token=1000000 Place=P2_Place Marking=0 Buffer=0 ToPlace=P2_Place TransitionId=EG11 EventType=GENERATED\n";
        check(TokenAnimator.lastAnalyzerRun(older + latest).equals(latest) && TokenAnimator.lastAnalyzerRun(latest).equals(latest), "Appended analyzer runs not separated");
        // Output copied from an Ant console ("[java]" on every line) replays like the analyzer's own output.
        String analysis = read(common.toPath().resolve("AnalysisFolder/PetriNet/Analysis_P2_Tutorial_Workflow.txt"));
        String prefixed = analysis.replaceAll("(?m)^", "     [java] ");
        String lastRun = TokenAnimator.lastAnalyzerRun(prefixed + prefixed);
        check(lastRun.length() < prefixed.length() && lastRun.trim().startsWith("[java] Auto-detected"), "Ant-prefixed analyzer runs not separated");
        TokenAnimator clean = new TokenAnimator(), ant = new TokenAnimator();
        clean.parseOutput(analysis); ant.parseOutput(prefixed);
        check(!clean.getEvents().isEmpty() && clean.getEvents().size() == ant.getEvents().size(), "Ant-prefixed analysis parsed differently: " + clean.getEvents().size() + " vs " + ant.getEvents().size());
        System.out.println("PASS: catalogue-only design, right-click Deploy to, filled Deploy panel with Place column, two-way node edits, save links process and instances, reopen, existing deployment unchanged, Create Build and Run files, Ant-prefixed analysis replay");
    }

    private static int rowOf(JTable table, String place) {
        for (int row = 0; row < table.getRowCount(); row++) if (place.equals(String.valueOf(table.getValueAt(row, 0)))) return row;
        return -1;
    }
    private static List<String> texts(Container parent) {
        List<String> result = new ArrayList<>();
        for (Component c : parent.getComponents()) {
            if (c instanceof AbstractButton) result.add(((AbstractButton)c).getText());
            if (c instanceof javax.swing.text.JTextComponent) result.add(((javax.swing.text.JTextComponent)c).getText());
            if (c instanceof Container) result.addAll(texts((Container)c));
        }
        return result;
    }
    private static Object field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target);
    }
    private static Object call(Object target, String name) throws Exception {
        Method m = target.getClass().getDeclaredMethod(name); m.setAccessible(true); return m.invoke(target);
    }
    private static String read(Path path) throws Exception { return new String(Files.readAllBytes(path), StandardCharsets.UTF_8); }
    private static void copy(Path from, Path to) throws Exception {
        try (java.util.stream.Stream<Path> files = Files.walk(from)) {
            for (Path source : (Iterable<Path>)files::iterator) {
                Path target = to.resolve(from.relativize(source).toString());
                if (Files.isDirectory(source)) Files.createDirectories(target); else Files.copy(source, target);
            }
        }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
