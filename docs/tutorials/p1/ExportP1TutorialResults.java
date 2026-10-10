package org.btsn.derby.Analysis;

import org.btsn.derby.Analysis.helper.CombinedWorkflowMetrics;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.apache.log4j.PropertyConfigurator;
import org.json.simple.JSONObject;

/** Exports an already collected P1 run using the repository's existing analyzers and views. */
public final class ExportP1TutorialResults {
    private ExportP1TutorialResults() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            throw new IllegalArgumentException("Expected: output-directory repository-root version token-count source-commit");
        }
        Path output = Path.of(args[0]).toAbsolutePath();
        Path repository = Path.of(args[1]).toAbsolutePath();
        int base = Integer.parseInt(args[2].replaceFirst("^v", "")) * 1000000;
        int expected = Integer.parseInt(args[3]);
        if (!Files.isDirectory(Path.of("ServiceAnalysisDataBase"))) {
            throw new IllegalStateException("Run from btsn.common.Monitor after collection has completed.");
        }
        Files.createDirectories(output);
        Properties logging = new Properties();
        logging.setProperty("log4j.rootLogger", "INFO, capture");
        logging.setProperty("log4j.appender.capture", "org.apache.log4j.ConsoleAppender");
        logging.setProperty("log4j.appender.capture.Follow", "true");
        logging.setProperty("log4j.appender.capture.layout", "org.apache.log4j.PatternLayout");
        logging.setProperty("log4j.appender.capture.layout.ConversionPattern", "%5p [%t] (%C{1}.java:%L) - %m%n");
        PropertyConfigurator.configure(logging);
        Class.forName("org.apache.derby.jdbc.EmbeddedDriver");
        PetriNetAnalyzer.CanonicalWorkflowAnalysis canonical = new PetriNetAnalyzer().analyzeCanonicalWorkflows(base);
        List<CombinedWorkflowMetrics.Workflow> rows;
        try (Connection connection = DriverManager.getConnection("jdbc:derby:./ServiceAnalysisDataBase")) {
            rows = CombinedWorkflowMetrics.load(connection);
        }
        if (rows.stream().anyMatch(row -> row.workflowBase != base)) {
            throw new IllegalStateException("Expected only the tutorial version in Monitor; start a freshly initialized P1 run.");
        }
        if (canonical.generatedWorkflows != expected || canonical.completedWorkflows != expected
                || rows.size() != expected || canonical.forkChildren != 0 || canonical.successfulJoins != 0
                || canonical.placeExecutions.size() != 1 || !canonical.placeExecutions.containsKey("P1_Place")) {
            throw new IllegalStateException("Expected a completed P1-only run with " + expected + " roots; inspect the analyzer output.");
        }
        StringBuilder csv = new StringBuilder("root_token_id,workflow_base,generated_at_epoch_ms,completed_at_epoch_ms,elapsed_ms,max_visit_queue_ms,p1_visits,repeat_visits,valid_queue_visits,invalid_queue_visits,services,process\n");
        for (CombinedWorkflowMetrics.Workflow row : rows) {
            if (!row.hasDuration()) throw new IllegalStateException("Missing duration for root " + row.rootTokenId);
            if (!"petrinet/Workflow/P1_Tutorial_Workflow".equals(row.process)) {
                throw new IllegalStateException("This export target expects the supplied P1 tutorial process.");
            }
            int visits = canonical.instances.get(row.rootTokenId).placeExecutions.getOrDefault("P1_Place", 0);
            csv.append(row.rootTokenId).append(',').append(row.workflowBase).append(',')
                .append(row.generatedAt).append(',').append(row.completedAt).append(',').append(row.durationMs()).append(',')
                .append(row.hasQueueMaximum() ? Long.toString(row.maxQueueMs) : "").append(',')
                .append(visits).append(',').append(Math.max(0, visits - 1)).append(',')
                .append(row.validQueueVisits).append(',').append(row.invalidQueueVisits).append(',')
                .append(quote(row.services)).append(',').append(quote(row.process)).append('\n');
        }
        write(output.resolve("workflows.csv"), csv.toString());
        PrintStream console = System.out;
        PrintStream errors = System.err;
        try (PrintStream capture = new PrintStream(Files.newOutputStream(output.resolve("analysis.txt")), true, "UTF-8")) {
            System.setOut(capture);
            System.setErr(capture);
            PetriNetAnalyzer.main(new String[] {"--all"});
        } finally {
            System.setOut(console);
            System.setErr(errors);
        }
        write(output.resolve("analysis.txt"), Files.readString(output.resolve("analysis.txt")));
        SwingUtilities.invokeAndWait(() -> {
            try {
                SwingGanttChart_WithLatency_v1d timing = new SwingGanttChart_WithLatency_v1d();
                write(output.resolve("workflow-summary.txt"), timing.generateWorkflowSummaryReport());
                write(output.resolve("workflow-timing.tex"), timing.generateLaTeXFigure());
                write(output.resolve("workflow-table.tex"), timing.generateLaTeXTable());
                paint(timing, output.resolve("workflow-timing.png"));
                WorkflowSpatialView spatial = new WorkflowSpatialView();
                spatial.filterByWorkflow(base);
                write(output.resolve("workflow-spatial-summary.txt"), spatial.generateSummaryReport());
                paint(spatial, output.resolve("workflow-spatial.png"));
            } catch (Exception exception) {
                throw new IllegalStateException("Could not export the existing views", exception);
            }
        });
        JSONObject summary = new JSONObject();
        summary.put("capturedAtUtc", Instant.now().toString());
        summary.put("sourceCommit", args[4]);
        summary.put("javaVersion", System.getProperty("java.version"));
        summary.put("ruleVersion", args[2]);
        summary.put("workflowBase", base);
        summary.put("generatedRoots", canonical.generatedWorkflows);
        summary.put("completedRoots", canonical.completedWorkflows);
        summary.put("incompleteRoots", canonical.incompleteWorkflows);
        summary.put("businessTerminations", canonical.businessTerminations);
        summary.put("p1Visits", canonical.placeExecutions.get("P1_Place"));
        summary.put("repeatVisits", canonical.placeExecutions.get("P1_Place") - canonical.generatedWorkflows);
        summary.put("forkChildren", canonical.forkChildren);
        summary.put("successfulJoins", canonical.successfulJoins);
        summary.put("queueMetric", CombinedWorkflowMetrics.CAPTION);
        JSONObject hashes = new JSONObject();
        for (String name : new String[] {
                "btsn.common/ProcessDefinitionFolder/petrinet/Workflow/P1_Tutorial_Workflow.json",
                "btsn.common/InfrastructureDefinitionFolder/SingleHost.json",
                "btsn.common/BusinessServiceDefinitions/petrinet/PetriNetModels.json",
                "btsn.services/deployments/models/P1_Tutorial_LocalDeployment.json",
                "btsn.common/ServiceDeploymentFolder/petrinet/P1_Tutorial.json",
                "btsn.petrinet.ProjectLoader/P1_Tutorial_BuildAndRun.xml",
                "btsn.petrinet.ProjectLoader/P1_Tutorial_Local_BuildAndRun.xml",
                "btsn.common.eventgenerators/EventTriggeringFile/V001_EventTriggeringFile.csv",
                "btsn.common.Monitor/src/org/btsn/derby/Analysis/SwingGanttChart_WithLatency_v1d.java"}) {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(repository.resolve(name)));
            StringBuilder hex = new StringBuilder();
            for (byte value : digest) hex.append(String.format("%02x", value & 0xff));
            hashes.put(name, hex.toString());
        }
        summary.put("inputSha256", hashes);
        write(output.resolve("summary.json"), summary.toJSONString() + "\n");
        System.out.println("Exported " + rows.size() + " completed P1 roots to " + output);
    }

    private static String quote(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static void write(Path file, String value) throws Exception {
        String normalized = value.lines().map(String::stripTrailing).collect(Collectors.joining("\n"));
        Files.writeString(file, normalized.stripTrailing() + "\n", StandardCharsets.UTF_8);
    }

    private static void paint(JPanel panel, Path file) throws Exception {
        Dimension preferred = panel.getPreferredSize();
        panel.setSize(Math.max(1200, preferred.width), Math.max(400, preferred.height));
        panel.doLayout();
        BufferedImage image = new BufferedImage(panel.getWidth(), panel.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try { panel.paint(graphics); } finally { graphics.dispose(); }
        ImageIO.write(image, "png", file.toFile());
    }
}
