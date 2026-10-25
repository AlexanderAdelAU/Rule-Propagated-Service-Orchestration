package com.editor;

import java.io.File;
import java.util.List;

/** A combined process (&lt;name&gt;_LiveChange.json) is built and run through the launcher of the same name. */
public final class CombinedProcessCheck {
    public static void main(String[] args) throws Exception {
        File root = new File(args[0]).getCanonicalFile();
        String name = "P1_P2_P3_P4_ForkRebalance_Workflow_LiveChange";
        File drawing = new File(root, "btsn.common/ProcessDefinitionFolder/petrinet/LiveChange/" + name + ".json");
        File launcher = BuildAndRunGenerator.combinedLauncher(drawing);
        check(launcher != null && launcher.getName().equals(name + ".xml"), "Combined process did not find its launcher: " + launcher);
        List<String> lines = BuildAndRunGenerator.combinedSummary(launcher);
        check(lines.size() == 2, "Expected two event generators: " + lines);
        check(lines.get(0).startsWith("EG1: v001, P1_P2_P3_P4_ForkRebalance_Workflow, 90 tokens, from the start"), "EG1: " + lines.get(0));
        check(lines.get(1).startsWith("EG2: v002, P1_P3_P4_P6_ForkRebalance_Workflow, 30 tokens, published to the running hosts at 10 s, tokens 30 s after every host commits"), "EG2: " + lines.get(1));
        check(LauncherRun.launchersFor(root, "petrinet/LiveChange/" + name).stream().anyMatch(f -> f.getName().equals(name + ".xml")),
            "Run on hosts does not find the combined launcher");
        check(BuildAndRunGenerator.combinedLauncher(new File(root, "btsn.common/ProcessDefinitionFolder/petrinet/LiveChange/P1_P2_P3_P4_ForkRebalance_Workflow.json")) == null,
            "An ordinary process was taken for a combined one");
        // A picture of a combined run names its launcher: Build and Run on hosts use it, and saving keeps the name.
        String[][] pictures = {
            {"healthcare/Workflow/Emergency_Department_With_Federated_Radiology", "Triple_Workflow_Emergencey_Department_Concurrent.xml", "EG1: v003", "EG2: v002", "EG3: v001"},
            {"petrinet/Workflow/P1_P2_P3_P4_Concurrent_Workflow", "P1_P2_P3_P4_Concurrent_BuildAndRun.xml", "EG1: v001", "EG2: v002"}};
        for (String[] p : pictures) {
            File design = new File(root, "btsn.common/ProcessDefinitionFolder/" + p[0] + ".json");
            File named = BuildAndRunGenerator.combinedLauncher(design);
            check(named != null && named.getName().equals(p[1]), p[0] + " did not find its launcher: " + named);
            List<String> run = BuildAndRunGenerator.combinedSummary(named);
            check(run.size() == p.length - 2, p[0] + " summary: " + run);
            for (int i = 2; i < p.length; i++) check(run.get(i - 2).startsWith(p[i]), p[0] + " summary: " + run);
            check(LauncherRun.launchersFor(root, p[0]).contains(named.getCanonicalFile()) || LauncherRun.launchersFor(root, p[0]).contains(named), p[0] + ": Run on hosts does not find " + p[1]);
            Canvas canvas = new Canvas(); canvas.setDefinitionLocation(design); canvas.loadFromJSON(java.nio.file.Files.readString(design.toPath()));
            check(canvas.saveToJSON().contains("\"launcher\": \"" + named.getParentFile().getName() + "/" + p[1] + "\""), p[0] + ": saving dropped its launcher");
        }
        // Replay: a token going to a terminate node follows the arc's bends, not a straight line.
        File healthcare = new File(root, "btsn.common/ProcessDefinitionFolder/healthcare/Workflow/Emergency_Department_With_Federated_Radiology.json");
        Canvas drawn = new Canvas(); drawn.setDefinitionLocation(healthcare); drawn.loadFromJSON(java.nio.file.Files.readString(healthcare.toPath()));
        ProcessElement from = drawn.findTransitionByLabel("T_out_Radiology"), end = drawn.findTransitionByLabel("Terminate");
        Arrow arc = null;
        for (Arrow a : drawn.getArrows()) if (a.getSource() == from && a.getTarget() == end) arc = a;
        check(arc != null && !arc.getWaypoints().isEmpty(), "Fixture: no bent arc from T_out_Radiology to Terminate");
        List<java.awt.Point> path = new java.util.ArrayList<>(); path.add(from.getCenter()); path.addAll(arc.getWaypoints()); path.add(end.getCenter());
        for (double t : new double[] {0.25, 0.5, 0.75}) {
            java.awt.Point at = drawn.travelAlong(from, end, t);
            double nearest = Double.MAX_VALUE;
            for (int i = 0; i + 1 < path.size(); i++) nearest = Math.min(nearest, java.awt.geom.Line2D.ptSegDist(path.get(i).x, path.get(i).y, path.get(i + 1).x, path.get(i + 1).y, at.x, at.y));
            check(nearest <= 1.5, "Token at " + t + " is off the arc to Terminate: " + at + " (" + nearest + " px away)");
        }
        if (args.length > 1 && !java.awt.GraphicsEnvironment.isHeadless()) {
            File picture = new File(args[1], "combined-build.png");
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                CombinedBuildDialog dialog = new CombinedBuildDialog(null, root, "petrinet/LiveChange/" + name, launcher);
                check(dialog.getTitle().equals("Build"), "Dialog title: " + dialog.getTitle());
                java.awt.Container content = dialog.getContentPane();
                java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(content.getWidth(), content.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
                content.paint(image.getGraphics());
                try { javax.imageio.ImageIO.write(image, "png", picture); } catch (java.io.IOException ex) { throw new RuntimeException(ex); }
                dialog.dispose();
            });
        }
        System.out.println("PASS: combined process finds its launcher, summarises both versions, and Run on hosts finds it; the two combined pictures (healthcare triple run, petrinet concurrent run) name theirs, which Build and Run use and saving keeps; a replayed token follows the bends of its arc to Terminate");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
