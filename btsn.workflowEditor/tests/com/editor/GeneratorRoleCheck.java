package com.editor;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.swing.*;

/**
 * An event generator's role follows from its one arc, and the author is told: into an ordinary T_in it issues arrivals;
 * into a JoinNode T_in (the traffic light's NS_Green) it sets the initial marking, one token per join input, once. That
 * shows as a badge on the diagram, a Role box (with Rate greyed out), a Validate reminder and the Build dialog's row;
 * two generators marking one join are reported; the runtime's own initialisers may reach every host from one generator.
 */
public final class GeneratorRoleCheck {
    public static void main(String[] args) throws Exception {
        File root = new File(args[0]).getCanonicalFile();
        File common = new File(root, "btsn.common");
        File traffic = new File(common, "ProcessDefinitionFolder/petrinet/Workflow/TrafficLight_Workflow.json");
        File tutorial = new File(common, "ProcessDefinitionFolder/petrinet/Workflow/P1_Tutorial_Workflow.json");
        File initializer = new File(common, "ProcessDefinitionFolder/common/Initializers/P1_P2_Initialization.json");
        Throwable[] failure = new Throwable[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                Canvas canvas = load(traffic);
                ProcessElement generator = canvas.findEventGeneratorByLabel("TRAFFICLIGHT_EVENTGENERATOR");
                Canvas.GeneratorRole role = canvas.generatorRole(generator);
                check(role.marking() && role.tokens() == 2 && role.inputs.equals(Arrays.asList("token_branch1", "token_branch2")) && "NS_Green".equals(role.place.getLabel()),
                    "Traffic light generator not an initial marking: " + role.inputs);
                check("M₀ ×2".equals(canvas.generatorBadge(generator)), "Badge: " + canvas.generatorBadge(generator));
                check(canvas.generatorRoleText(generator).startsWith("Initial marking: 2 tokens, once, into NS_Green's join (token_branch1, token_branch2)"), canvas.generatorRoleText(generator));
                String reminder = "TRAFFICLIGHT_EVENTGENERATOR sets the initial marking: 2 tokens, once, into NS_Green's join.";
                check(canvas.serviceNotes().contains(reminder) && !canvas.serviceBlockers().contains(reminder), "Reminder: " + canvas.serviceNotes());

                // On the diagram: the badge is painted above the generator.
                canvas.setSize(1200, 700);
                BufferedImage picture = new BufferedImage(1200, 700, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = picture.createGraphics(); canvas.paint(g); g.dispose();
                boolean badge = false;
                for (int x = generator.getX() - 20; x < generator.getX() + generator.getWidth() + 20; x++)
                    for (int y = Math.max(0, generator.getY() - 20); y < generator.getY(); y++)
                        if (picture.getRGB(x, y) == new Color(237, 228, 255).getRGB()) badge = true;
                check(badge, "No badge painted above the generator");

                // Its Attributes: a Role box, and the rate greyed out.
                EditorFrame attributes = new EditorFrame(canvas); attributes.updateSelection(generator);
                check(texts(attributes).stream().anyMatch(t -> t.startsWith("Initial marking: 2 tokens, once")), "No Role box: " + texts(attributes));
                check(fields(attributes).stream().anyMatch(f -> "1000".equals(f.getText()) && !f.isEnabled()), "Rate not greyed out for an initial marking");

                // Build: the summary and the dialog's row say it; the interval cannot be edited.
                BuildAndRunGenerator.Plan plan = BuildAndRunGenerator.plan(canvas, traffic);
                check(BuildAndRunGenerator.summary(plan, plan.defaults).stream().anyMatch(l -> l.startsWith("Tokens   initial marking from TRAFFICLIGHT_EVENTGENERATOR: 2 tokens, once")),
                    "Summary: " + BuildAndRunGenerator.summary(plan, plan.defaults));
                BuildAndRunDialog dialog = new BuildAndRunDialog(null, plan);
                check(String.valueOf(dialog.generators.getValueAt(0, 2)).endsWith("— initial marking: 2 tokens, once") && !dialog.generators.isCellEditable(0, 4),
                    "Build row: " + dialog.generators.getValueAt(0, 2));
                dialog.dispose();

                // Two generators on one join: each would start its own copy of the net.
                ProcessElement second = new ProcessElement(ProcessElement.Type.EVENT_GENERATOR, 20, 20); second.setLabel("EG2");
                canvas.getElements().add(second);
                canvas.getArrows().add(new Arrow(second, canvas.findTransitionByLabel("T_in_NS_Green") != null ? canvas.findTransitionByLabel("T_in_NS_Green") : role.join));
                check(canvas.serviceBlockers().stream().anyMatch(n -> n.startsWith("T_in_NS_Green is marked by TRAFFICLIGHT_EVENTGENERATOR and EG2")), "Two markings not reported: " + canvas.serviceBlockers());

                // An arrival generator: no badge, its role says so.
                Canvas open = load(tutorial);
                ProcessElement arrivals = open.findEventGeneratorByLabel("P1_EVENTGENERATOR");
                check(open.generatorBadge(arrivals) == null && "Arrivals: one token per arrival into P1.".equals(open.generatorRoleText(arrivals)), open.generatorRoleText(arrivals));
                check(open.generatorMarkings().isEmpty(), "Arrivals reported as a marking");

                // The runtime's own initialiser reaches every host from one generator; the same net elsewhere is reported.
                Canvas admin = load(initializer);
                check(admin.isAdministrativeProcess() && admin.generatorsWithSeveralArcs().isEmpty(), "Initialiser reported: " + admin.generatorsWithSeveralArcs());
                Canvas copy = new Canvas(); copy.loadFromJSON(Files.readString(initializer.toPath()));
                check(!copy.generatorsWithSeveralArcs().isEmpty(), "A business process with a several-arc generator not reported");
            } catch (Throwable t) { failure[0] = t; }
        });
        if (failure[0] != null) throw new AssertionError("Generator roles", failure[0]);
        System.out.println("PASS: a generator into a JoinNode T_in sets the initial marking (traffic light: 2 tokens, once) and the author is told: badge on the diagram, Role box with Rate greyed out, Validate reminder, Build summary and row; two generators on one join reported; an arrival generator says so; initialisers exempt from the one-arc rule");
    }

    private static Canvas load(File process) throws Exception {
        Canvas canvas = new Canvas(); canvas.setDefinitionLocation(process); canvas.loadFromJSON(Files.readString(process.toPath()));
        return canvas;
    }
    private static List<String> texts(Container parent) {
        List<String> result = new ArrayList<>();
        for (Component c : parent.getComponents()) {
            if (c instanceof javax.swing.text.JTextComponent) result.add(((javax.swing.text.JTextComponent) c).getText());
            if (c instanceof Container) result.addAll(texts((Container) c));
        }
        return result;
    }
    private static List<JTextField> fields(Container parent) {
        List<JTextField> result = new ArrayList<>();
        for (Component c : parent.getComponents()) {
            if (c instanceof JTextField) result.add((JTextField) c);
            if (c instanceof Container) result.addAll(fields((Container) c));
        }
        return result;
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
