package com.editor;
import java.awt.Component;
import java.awt.Container;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import javax.swing.*;
import org.json.simple.*;
import org.json.simple.parser.JSONParser;

/** Verifies catalogue selection, real Swing controls and lossless contract round trips. */
public final class ServiceRegistryCheck {
    public static void main(String[] args) throws Exception {
        File root = new File(args[0]).getCanonicalFile(), common = new File(root, "btsn.common");
        ServiceRegistry registry = new ServiceRegistry();
        registry.loadDeployment(new File(common, "ServiceDeploymentFolder/petrinet/TrafficLightModels.json"));
        check(registry.services().equals(Arrays.asList("StochasticService")), "Petri-net dropdown contains another service");
        check(registry.instances("StochasticService", "processToken").size() == 6, "Repeated instances missing");
        check(!registry.validateEndpoint("TypoService", "processToken", "token", Arrays.asList("token"), "boolean-token").isEmpty(), "Unknown service accepted");
        check(!registry.validateEndpoint("StochasticService", "typo", "token", Arrays.asList("token"), "boolean-token").isEmpty(), "Unknown operation accepted");
        check(!registry.validateEndpoint("StochasticService", "processToken", "decision", Arrays.asList("data"), "").isEmpty(), "Boolean adapted implicitly");
        check(!registry.validateEndpoint("StochasticService", "processToken", "token", Arrays.asList("token", "token"), "boolean-token").isEmpty(), "Duplicate adapter inputs accepted");
        registry.loadDeployment(new File(common, "ServiceDeploymentFolder/financial/FinancialSystem.json"));
        check(registry.catalogueAssociationError(new File(common, "ServiceDeploymentFolder/petrinet/TrafficLightModels.json")) != null, "Wrong deployment catalogue association accepted");
        ServiceRegistry.Contract c = registry.contract("ValidationService", "processToken");
        check(registry.validateEndpoint(c.service, c.operation, c.output, c.inputs, "").isEmpty(), "Ordinary JSON service rejected");
        check(!registry.validateEndpoint(c.service, c.operation, c.output, c.inputs, "boolean-token").isEmpty(), "JSON service accepted Boolean adapter");
        Canvas canvas = new Canvas();
        String[] models = {"TrafficLight_Workflow", "P1_P2_Deterministic_Workflow", "P1_to_P6_Double_Join_Workflow", "P1_Tutorial_Workflow", "P1_P2_Workflow", "P1_P2_ForkCompanion_Workflow", "P1_P2_P3_P4_Fork_Join_Workflow"};
        for (String model : models) {
            File file = new File(common, "ProcessDefinitionFolder/petrinet/Workflow/" + model + ".json");
            canvas.setDefinitionLocation(file);
            canvas.loadFromJSON(new String(Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8));
            check(canvas.validateServiceContracts().isEmpty(), model + ": " + canvas.validateServiceContracts());
            String saved = canvas.saveToJSON();
            Canvas reloaded = new Canvas(); reloaded.setDefinitionLocation(file); reloaded.loadFromJSON(saved);
            check(reloaded.validateServiceContracts().isEmpty(), "Round trip lost contract: " + model);
            check(((JSONObject)new JSONParser().parse(saved)).get("serviceDeployment") != null, "Deployment association lost");
        }
        File traffic = new File(common, "ProcessDefinitionFolder/petrinet/Workflow/TrafficLight_Workflow.json");
        canvas.setDefinitionLocation(traffic); canvas.loadFromJSON(new String(Files.readAllBytes(traffic.toPath()), java.nio.charset.StandardCharsets.UTF_8));
        ProcessElement place = null;
        for (ProcessElement element : canvas.getElements()) if (element.getType() == ProcessElement.Type.PLACE) { place = element; break; }
        final ProcessElement selected = place;
        selected.setService("StochasticEntryJoinService");
        check(!canvas.validateServiceContracts().isEmpty(), "Legacy service reference accepted");
        SwingUtilities.invokeAndWait(() -> {
            EditorFrame frame = new EditorFrame(canvas); frame.updateSelection(selected);
            List<JComboBox<?>> controls = combos(frame);
            check(controls.size() >= 3, "Missing service selectors");
            JComboBox service = controls.get(0);
            check(!service.isEditable(), "Service selector allows typing");
            Component renderer = service.getRenderer().getListCellRendererComponent(new JList(), service.getSelectedItem(), -1, false, false);
            check(((JLabel)renderer).getText().startsWith("Unresolved:"), "Legacy service not flagged");
            service.setSelectedItem("StochasticService");
            controls = combos(frame); controls.get(1).setSelectedItem("processToken");
            controls = combos(frame); controls.get(2).setSelectedItem("StochasticInstance8");
            check(canvas.validateServiceContracts().isEmpty(), "Dropdown repair did not apply deployment contract: " + canvas.validateServiceContracts());
            check(selected.getServiceOperations().get(0).getArgumentCount() == 2, "Join transport inputs lost");
            check("token".equals(selected.getServiceOperations().get(0).getReturnAttribute()), "Result contract not applied");
            ProcessElement transition = new ProcessElement(ProcessElement.Type.TRANSITION, 0, 0);
            transition.setLabel("T_in_custom"); transition.setTransitionType("T_in"); transition.setNodeType("ForkNode"); transition.setNodeValue("FORK_NODE");
            frame.updateSelection(transition);
            check("ForkNode".equals(transition.getNodeType()), "Inspecting transition rewrote routing");
            check(combos(frame).stream().anyMatch(combo -> contains(combo, "DecisionNode")), "Full routing type vocabulary unavailable");
        });
        selected.getServiceOperations().get(0).getArguments().get(0).setName("wrong");
        check(!canvas.validateServiceContracts().isEmpty(), "Process input mismatch accepted");
        canvas.getServiceRegistry().apply(selected, "processToken", "StochasticInstance8");
        selected.getServiceOperations().get(0).setReturnAttribute("wrong");
        check(!canvas.validateServiceContracts().isEmpty(), "Process result mismatch accepted");
        canvas.getServiceRegistry().apply(selected, "processToken", "StochasticInstance8");
        SwingUtilities.invokeAndWait(() -> {
            try {
                EditorFrame frame = new EditorFrame(canvas); frame.updateSelection(selected); frame.setSize(420, 780); layout(frame);
                java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(420, 780, java.awt.image.BufferedImage.TYPE_INT_RGB);
                frame.paint(image.getGraphics());
                javax.imageio.ImageIO.write(image, "png", new File(args[1]));
            } catch (Exception ex) { throw new RuntimeException(ex); }
        });
        System.out.println("PASS: seven workflow contract round trips, real dropdown repair, legacy/unknown references, JSON/Boolean adapter isolation, argument/result rejection and routing preservation");
    }
    private static boolean contains(JComboBox<?> combo, String value) { for (int i=0;i<combo.getItemCount();i++) if(value.equals(combo.getItemAt(i))) return true; return false; }
    private static List<JComboBox<?>> combos(Container parent) { List<JComboBox<?>> result = new ArrayList<>(); for(Component c:parent.getComponents()) { if(c instanceof JComboBox) result.add((JComboBox<?>)c); else if(c instanceof Container) result.addAll(combos((Container)c)); } return result; }
    private static void layout(Container parent) { parent.doLayout(); for(Component c:parent.getComponents()) if(c instanceof Container) layout((Container)c); }
    private static void check(boolean condition, String message) { if(!condition) throw new AssertionError(message); }
}
