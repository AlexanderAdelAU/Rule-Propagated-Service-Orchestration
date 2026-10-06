package org.btsn.observation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import org.btsn.constants.VersionConstants;
import org.json.simple.JSONObject;

/** Observation provenance only; never changes tokens, routing or execution. */
public final class WorkflowRunMetadata {
    public static final String DIRECTORY = "WorkflowRunMetadata";
    private WorkflowRunMetadata() { }

    public static Path directory() {
        String configured = System.getProperty("btsn.workflow.metadata.dir");
        if (configured != null && !configured.isBlank()) return Path.of(configured).toAbsolutePath().normalize();
        Path working = Path.of("").toAbsolutePath().normalize();
        for (Path parent = working; parent != null; parent = parent.getParent()) {
            if (parent.getFileName() != null && parent.getFileName().toString().equals("btsn.common.Monitor"))
                return parent.resolve(DIRECTORY);
            Path monitor = parent.resolve("btsn.common.Monitor");
            if (Files.isRegularFile(monitor.resolve(".project"))) return monitor.resolve(DIRECTORY);
        }
        return working.resolve(DIRECTORY);
    }

    /** Called after UDP submission, using the timestamp already present in that payload. */
    public static void recordPayload(String process, String payload) {
        try {
            String token = element(payload, "sequenceId"), timestamp = element(payload, "eventGeneratorTimestamp");
            if (token == null || timestamp == null) return;
            record(process, element(payload, "ruleBaseVersion"), Integer.parseInt(token), Long.parseLong(timestamp));
        } catch (Exception e) {
            System.err.println("Could not record diagram process metadata: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    public static void record(String process, String version, int token, long generatedAt) throws Exception {
        if (process == null || process.isBlank() || token <= 0 || token / 1000000 == 999 || generatedAt <= 0) return;
        if (!VersionConstants.getVersionFromSequenceId(token).equals(version)) return;
        JSONObject data = new JSONObject();
        data.put("process", process); data.put("version", version);
        data.put("tokenId", token); data.put("generatedAt", generatedAt);
        Path directory = directory();
        Files.createDirectories(directory);
        String name = generatedAt + "-" + token + "-" + UUID.randomUUID();
        Path temporary = directory.resolve(name + ".tmp"), output = directory.resolve(name + ".json");
        try {
            Files.writeString(temporary, data.toJSONString() + "\n");
            try { Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(temporary, output); }
        } finally { Files.deleteIfExists(temporary); }
    }

    private static String element(String payload, String name) {
        String open = "<" + name + ">", close = "</" + name + ">";
        int start = payload.indexOf(open);
        if (start < 0) return null;
        int end = payload.indexOf(close, start + open.length());
        return end < 0 ? null : payload.substring(start + open.length(), end).trim();
    }
}
