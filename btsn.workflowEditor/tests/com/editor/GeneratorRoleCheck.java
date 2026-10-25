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
        File rendezvous = new File(common, "ProcessDefinitionFolder/petrinet/Workflow/Rendezvous_Workflow.json");
        File initializer = new File(root, "btsn.workflowEditor/tests/fixtures/admin/Initializers/Hosts_Initialization.json"); // as a run generates it
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
                check(canvas.saveToJSON().split("\"joinInputCount\": 2", -1).length == 3, "Saving dropped the joins' joinInputCount");
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
                check(titled(attributes, "Network") == 1, "Network boxes for a generator: " + titled(attributes, "Network"));

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

                // Token data: typed in the generator's Attributes, saved with the process, written to the launcher as egN.data.
                EditorFrame egAttributes = new EditorFrame(open); egAttributes.updateSelection(arrivals);
                JTextField dataField = null;
                for (JTextField f : fields(egAttributes)) if (f.getToolTipText() != null && f.getToolTipText().contains("name=value")) dataField = f;
                check(dataField != null && dataField.getText().isEmpty(), "No Token data field on a generator");
                dataField.setText("application_id = LOAN-1, annual_income=85000");
                check("application_id = LOAN-1, annual_income=85000".equals(arrivals.getTokenData()), "Token data not set: " + arrivals.getTokenData());
                Canvas reopened = new Canvas(); reopened.setDefinitionLocation(tutorial); reopened.loadFromJSON(open.saveToJSON());
                check("application_id = LOAN-1, annual_income=85000".equals(reopened.findEventGeneratorByLabel("P1_EVENTGENERATOR").getTokenData()), "Token data not saved");
                BuildAndRunGenerator.Plan dplan = BuildAndRunGenerator.plan(open, tutorial);
                check(dplan.problems.isEmpty() && "application_id=LOAN-1,annual_income=85000".equals(dplan.data.get(arrivals.getId())), "Plan data: " + dplan.problems + " " + dplan.data);
                check(BuildAndRunGenerator.launcherXml(dplan, dplan.defaults).contains("name=\"eg1.data\" value=\"application_id=LOAN-1,annual_income=85000\""), "Launcher has no eg1.data");
                check(BuildAndRunGenerator.summary(dplan, dplan.defaults).stream().anyMatch(l -> l.startsWith("Data     every token from " + arrivals.getId() + " carries application_id=LOAN-1")), "Summary has no data line");
                arrivals.setTokenData("not pairs");
                check(BuildAndRunGenerator.plan(open, tutorial).problems.stream().anyMatch(m -> m.contains("Token data is not name=value pairs")), "Bad token data not reported");

                // The financial processes carry the application their services validate.
                File preScreen = new File(common, "ProcessDefinitionFolder/financial/Workflow/FinancialSystem_Stage3_PreScreen_Workflow.json");
                Canvas fin = load(preScreen);
                BuildAndRunGenerator.Plan fplan = BuildAndRunGenerator.plan(fin, preScreen);
                check(fplan.problems.isEmpty() && String.join(" ", fplan.data.values()).contains("application_id=PRESCREEN-1"), "Pre-screen data: " + fplan.problems + " " + fplan.data);

                // The runtime's own initialiser reaches every host from one generator and is never checked as a marking;
                // the same net in a business process reads as a marking of one token per host place.
                Canvas admin = load(initializer);
                check(admin.isAdministrativeProcess() && admin.joinsMarkedTwice().isEmpty(), "Initialiser reported: " + admin.joinsMarkedTwice());
                Canvas copy = new Canvas(); copy.loadFromJSON(Files.readString(initializer.toPath()));
                ProcessElement adminGenerator = null;
                for (ProcessElement e : copy.getElements()) if (e.getType() == ProcessElement.Type.EVENT_GENERATOR) adminGenerator = e;
                check(copy.generatorRole(adminGenerator).marks.size() > 1, "A several-arc generator not read as a marking");

                // A marking over several places (Rendezvous: one token each into Left and Right, which meet at a join).
                Canvas meet = load(rendezvous);
                ProcessElement start = meet.findEventGeneratorByLabel("START");
                Canvas.GeneratorRole marking = meet.generatorRole(start);
                check(marking.marks.size() == 2 && marking.tokens() == 2 && marking.inputs.equals(Arrays.asList("token", "token")), "Rendezvous marking: " + marking.inputs);
                check("M₀ ×2".equals(meet.generatorBadge(start)), "Rendezvous badge: " + meet.generatorBadge(start));
                check(meet.generatorRoleText(start).startsWith("Initial marking: 2 tokens, once: 1 into Left (token); 1 into Right (token)"), meet.generatorRoleText(start));
                check(meet.joinsMarkedTwice().isEmpty(), "Rendezvous reported: " + meet.joinsMarkedTwice());
                check(meet.saveToJSON().split("\"tokens\": 1", -1).length == 3, "Saving dropped the arcs' token counts");
                BuildAndRunGenerator.Plan rplan = BuildAndRunGenerator.plan(meet, rendezvous);
                check(rplan.problems.isEmpty() && "P1_Place.processToken=token;P2_Place.processToken=token".equals(rplan.markings.get("START")) && rplan.joins.get("START") == 2,
                    "Rendezvous plan: " + rplan.problems + " " + rplan.markings);
                String launcher = BuildAndRunGenerator.launcherXml(rplan, rplan.defaults);
                check(launcher.contains("name=\"eg1.marking\" value=\"P1_Place.processToken=token;P2_Place.processToken=token\"") && launcher.contains("name=\"eg1.tokens\" value=\"2\"")
                    && !launcher.contains("join.inputs"), "Rendezvous launcher: " + launcher);
                check(BuildAndRunGenerator.summary(rplan, rplan.defaults).stream().anyMatch(l -> l.startsWith("Tokens   initial marking from START: 2 tokens, once, version v001: P1_Place")),
                    "Rendezvous summary: " + BuildAndRunGenerator.summary(rplan, rplan.defaults));

                // Each arc's count is set in its Attributes; bad counts are reported.
                Arrow left = marking.marks.get(0).arc;
                EditorFrame arcAttributes = new EditorFrame(meet); arcAttributes.updateSelection(left);
                JTextField count = null;
                for (JTextField f : fields(arcAttributes)) if (f.getToolTipText() != null && f.getToolTipText().contains("initial marking")) count = f;
                check(count != null && "1".equals(count.getText()), "No Initial tokens field on a generator's arc");
                count.setText("3");
                check(Integer.valueOf(3).equals(left.getTokens()) && meet.generatorRole(start).tokens() == 4, "Initial tokens field did not set the arc: " + left.getTokens());
                left.setTokens(0);
                check(meet.joinsMarkedTwice().stream().anyMatch(n -> n.startsWith("START \u2192 T_in_Left puts 0 tokens")), "Count 0 not reported: " + meet.joinsMarkedTwice());
                left.setTokens(120);
                check(meet.joinsMarkedTwice().stream().anyMatch(n -> n.startsWith("START marks 121 tokens")), "Over 99 not reported: " + meet.joinsMarkedTwice());
                left.setTokens(1);

                // More tokens into a join than it has inputs.
                Canvas light = load(traffic);
                ProcessElement tl = light.findEventGeneratorByLabel("TRAFFICLIGHT_EVENTGENERATOR");
                light.generatorRole(tl).marks.get(0).arc.setTokens(3);
                check(light.joinsMarkedTwice().stream().anyMatch(n -> n.contains("puts 3 tokens into NS_Green's join, which takes 2 inputs")), "Overfull join not reported: " + light.joinsMarkedTwice());
            } catch (Throwable t) { failure[0] = t; }
        });
        if (failure[0] != null) throw new AssertionError("Generator roles", failure[0]);
        System.out.println("PASS: a generator into a JoinNode T_in sets the initial marking (traffic light: 2 tokens, once) and the author is told: badge on the diagram, Role box with Rate greyed out, Validate reminder, Build summary and row; two generators on one join reported; an arrival generator says so; initialisers exempt; a marking over several places (Rendezvous) is shown, planned and written as eg1.marking; each arc's count is set in its Attributes and bad counts are reported; saving keeps joinInputCount and arc counts; a generator's Token data is saved and written as egN.data, malformed data reported");
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
    private static int titled(Container parent, String title) {
        int n = 0;
        for (Component c : parent.getComponents()) {
            if (c instanceof JComponent && ((JComponent) c).getBorder() instanceof javax.swing.border.TitledBorder
                    && title.equals(((javax.swing.border.TitledBorder) ((JComponent) c).getBorder()).getTitle())) n++;
            if (c instanceof Container) n += titled((Container) c, title);
        }
        return n;
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
