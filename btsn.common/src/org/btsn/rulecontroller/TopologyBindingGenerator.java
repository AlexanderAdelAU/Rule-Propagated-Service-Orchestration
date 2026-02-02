package org.btsn.rulecontroller;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import org.btsn.utils.StringFileIO;

import org.btsn.rulecontroller.model.WorkflowModel;
import org.btsn.rulecontroller.model.ServiceNode;
import org.btsn.rulecontroller.model.TransitionNode;
import org.btsn.rulecontroller.model.WorkflowEdge;

/**
 * TopologyBindingGenerator - Build-time canonical binding generation
 * 
 * Parses workflow JSON topology and generates canonical binding files
 * that define input/output attribute mappings for each service.
 * 
 * Called by Ant during build phase via main().
 * 
 * Key responsibilities:
 * - Parse workflow JSON to understand topology
 * - Analyze join/fork structure to determine slot assignments
 * - Generate canonical binding XML files for each service
 * - Determine correct return attributes based on downstream joins
 */
public class TopologyBindingGenerator {
    private static final Logger logger = Logger.getLogger(TopologyBindingGenerator.class);

    // Configuration
    private static final class Config {
        static final String COMMON_FOLDER = deriveCommonFolder();
        static final String PROCESS_DEFINITION_FOLDER = COMMON_FOLDER + "/ProcessDefinitionFolder";
        static final String SERVICE_ATTRIBUTE_BINDINGS_FOLDER = COMMON_FOLDER + "/ServiceAttributeBindings";
        
        static final Set<String> VALID_NODE_TYPES = Set.of("DecisionNode", "TerminateNode", "JoinNode", "XorNode",
                "MergeNode", "EdgeNode", "MonitorNode", "FeedFwdNode", "GatewayNode", "ForkNode", "EventGenerator");
        
        private static String deriveCommonFolder() {
            String packageName = TopologyBindingGenerator.class.getPackage().getName();
            String[] parts = packageName.split("\\.");
            
            if (parts.length >= 2) {
                String commonFolder = parts[1] + ".common";
                Logger.getLogger(TopologyBindingGenerator.class).info(
                    "Derived common folder from package: " + packageName + " → " + commonFolder);
                return commonFolder;
            } else {
                try {
                    File parentDir = new File("../");
                    if (parentDir.exists()) {
                        for (File sibling : parentDir.listFiles()) {
                            if (sibling.isDirectory() && sibling.getName().contains(".common")) {
                                return sibling.getName();
                            }
                        }
                    }
                } catch (Exception e) {
                    Logger.getLogger(TopologyBindingGenerator.class).warn(
                        "Could not scan for common folder: " + e.getMessage());
                }
                return "btsn.common";
            }
        }
    }

    // Instance variables
    private final String buildVersion;
    private final String processName;
    private String processType = null;
    
    private final WorkflowModel workflowModel = new WorkflowModel();
    private Map<String, Map<String, JoinSlotInfo>> petriNetJoinSlotAssignments = new HashMap<>();

    // Join slot info helper class
    private static class JoinSlotInfo {
        final String argName;
        final int slotIndex;
        
        JoinSlotInfo(String argName, int slotIndex) {
            this.argName = argName;
            this.slotIndex = slotIndex;
        }
        
        @Override
        public String toString() {
            return "slot" + slotIndex + "=" + argName;
        }
    }

    public TopologyBindingGenerator(String processName, String buildVersion) {
        this.processName = processName;
        this.buildVersion = buildVersion;
    }

    /**
     * Main entry point for Ant build
     */
    public static void main(String[] args) {
        // Configure logging
        Logger rootLogger = Logger.getRootLogger();
        if (!rootLogger.getAllAppenders().hasMoreElements()) {
            rootLogger.addAppender(new ConsoleAppender(
                new PatternLayout("%5p [%t] (%F:%L) - %m%n")));
        }
        
        if (args.length < 2) {
            logger.error("Usage: TopologyBindingGenerator <processName> <buildVersion>");
            System.err.println("Usage: TopologyBindingGenerator <processName> <buildVersion>");
            System.exit(1);
        }
        
        String processName = args[0];
        String buildVersion = args[1];
        
        logger.info("=== TOPOLOGY BINDING GENERATOR ===");
        logger.info("Process: " + processName);
        logger.info("Version: " + buildVersion);
        
        try {
            TopologyBindingGenerator generator = new TopologyBindingGenerator(processName, buildVersion);
            generator.generate();
            logger.info("=== BINDING GENERATION COMPLETE ===");
            System.out.println("=== Bindings generated successfully ===");
        } catch (Exception e) {
            logger.error("Binding generation failed", e);
            System.err.println("ERROR: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Generate canonical bindings for all services in the workflow
     */
    public void generate() throws TopologyBindingException {
        logger.info("=== GENERATING WORKFLOW BINDINGS (Build Phase) ===");
        
        try {
            // Load and parse workflow
            loadWorkflow();
            
            // Generate bindings based on process type
            if ("PetriNet".equalsIgnoreCase(this.processType)) {
                generatePetriNetCanonicalBindings();
            } else if ("SOA".equalsIgnoreCase(this.processType)) {
                generateSOACanonicalBindings();
            } else {
                throw new TopologyBindingException(
                    "Unknown process type: " + this.processType + 
                    ". Must be 'PetriNet' or 'SOA'.");
            }
            
            logger.info("Bindings generated successfully for process type: " + this.processType);
            
        } catch (Exception e) {
            logger.error("Failed to generate bindings", e);
            throw new TopologyBindingException("Binding generation failed: " + e.getMessage(), e);
        }
    }

    // ========================================================================
    // WORKFLOW LOADING AND PARSING
    // ========================================================================

    private void loadWorkflow() throws TopologyBindingException {
        try {
            File commonBase = new File("../");
            String commonPath = commonBase.getCanonicalPath();
            String jsonFileName = commonPath + "/" + Config.PROCESS_DEFINITION_FOLDER + "/" + processName + ".json";
            
            if (Files.exists(Path.of(jsonFileName))) {
                logger.info("Loading JSON workflow from: " + jsonFileName);
                String jsonContent = StringFileIO.readFileAsString(jsonFileName);
                logger.info("Successfully loaded JSON workflow: " + jsonContent.length() + " characters");
                parseJsonWorkflow(jsonContent);
            } else {
                throw new TopologyBindingException("Workflow file not found: " + jsonFileName);
            }
        } catch (IOException e) {
            throw new TopologyBindingException("Failed to load workflow file: " + e.getMessage(), e);
        }
    }

    private void parseJsonWorkflow(String jsonContent) throws TopologyBindingException {
        logger.info("=== PARSING JSON WORKFLOW ===");
        
        try {
            // Extract processType - REQUIRED
            String extractedProcessType = extractJsonValue(jsonContent, "processType");
            if (extractedProcessType != null && !extractedProcessType.isEmpty()) {
                if (!"PetriNet".equalsIgnoreCase(extractedProcessType) && 
                    !"SOA".equalsIgnoreCase(extractedProcessType)) {
                    throw new TopologyBindingException(
                        "Invalid processType: '" + extractedProcessType + "'. Must be 'PetriNet' or 'SOA'.");
                }
                this.processType = extractedProcessType;
                logger.info("Process type: " + this.processType);
            } else {
                throw new TopologyBindingException(
                    "REQUIRED field 'processType' not found in workflow JSON.");
            }
            
            // Parse elements
            String elementsSection = extractJsonSection(jsonContent, "\"elements\"");
            String[] elementBlocks = splitJsonObjects(elementsSection);
            
            for (String block : elementBlocks) {
                if (block.trim().isEmpty()) continue;
                
                String type = extractJsonValue(block, "type");
                String id = extractJsonValue(block, "id");
                String label = extractJsonValue(block, "label");
                
                if ("PLACE".equals(type)) {
                    parsePlace(block, id, label);
                } else if ("TRANSITION".equals(type)) {
                    parseTransition(block, id, label);
                } else if ("EVENT_GENERATOR".equals(type)) {
                    parseEventGenerator(block, id, label);
                }
            }
            
            // Parse arrows
            String arrowsSection = extractJsonSection(jsonContent, "\"arrows\"");
            String[] arrowBlocks = splitJsonObjects(arrowsSection);
            
            for (String block : arrowBlocks) {
                if (block.trim().isEmpty()) continue;
                parseArrow(block);
            }
            
            logger.info("JSON parsing complete - " + workflowModel.getServiceNodes().size() + " services, " +
                workflowModel.getTransitionNodes().size() + " transitions, " + 
                workflowModel.getWorkflowEdges().size() + " edges");
                
        } catch (Exception e) {
            throw new TopologyBindingException("Failed to parse JSON workflow: " + e.getMessage(), e);
        }
    }

    private void parsePlace(String block, String id, String label) {
        String service = extractJsonValue(block, "service");
        
        List<String> operationsList = new ArrayList<>();
        List<String> ops = extractJsonOperations(block, "operations");
        if (!ops.isEmpty()) {
            operationsList.addAll(ops);
        } else {
            String operation = extractJsonValue(block, "operation");
            if (operation != null && !operation.isEmpty()) {
                operationsList.add(operation);
            }
        }
        
        if (service != null && !service.isEmpty() && !operationsList.isEmpty()) {
            String primaryOperation = operationsList.get(0);
            List<String> operationArguments = extractOperationArguments(block, primaryOperation);
            String returnAttribute = extractOperationReturnAttribute(block, primaryOperation);
            
            Map<String, String> attributes = new HashMap<>();
            attributes.put("label", label != null ? label : "");
            attributes.put("service", service);
            attributes.put("operation", primaryOperation);
            
            if (!operationArguments.isEmpty()) {
                attributes.put("operationArguments", String.join(",", operationArguments));
            }
            if (returnAttribute != null && !returnAttribute.isEmpty()) {
                attributes.put("returnAttribute", returnAttribute);
            }
            if (operationsList.size() > 1) {
                attributes.put("operations", String.join(",", operationsList));
            }
            
            ServiceNode serviceNode = new ServiceNode(id, service, primaryOperation, attributes);
            workflowModel.addServiceNode(serviceNode);
            logger.debug("Parsed Place: " + id + " -> " + service + ":" + primaryOperation);
        }
    }

    private void parseTransition(String block, String id, String label) {
        String nodeType = extractJsonValue(block, "node_type");
        String nodeValue = extractJsonValue(block, "node_value");
        String transitionType = extractJsonValue(block, "transition_type");
        String buffer = extractJsonValue(block, "buffer");
        
        if (nodeType != null && !nodeType.isEmpty()) {
            Map<String, String> attributes = new HashMap<>();
            attributes.put("label", label != null ? label : "");
            attributes.put("node_type", nodeType);
            if (nodeValue != null) {
                attributes.put("node_value", nodeValue);
            }
            
            if (buffer != null && !buffer.isEmpty() && transitionType != null) {
                if (transitionType.equals("T_in") || transitionType.equals("Other")) {
                    attributes.put("buffer", buffer);
                }
            }
            
            TransitionNode transitionNode = new TransitionNode(id, nodeType, 
                nodeValue != null ? nodeValue : "", attributes);
            workflowModel.addTransitionNode(transitionNode);
            logger.debug("Parsed Transition: " + id + " -> " + nodeType);
        }
    }

    private void parseEventGenerator(String block, String id, String label) {
        Map<String, String> attributes = new HashMap<>();
        attributes.put("label", label != null ? label : "");
        attributes.put("node_type", "EventGenerator");
        attributes.put("elementType", "EVENT_GENERATOR");
        
        TransitionNode transitionNode = new TransitionNode(id, "EventGenerator", "EVENT_GENERATOR", attributes);
        workflowModel.addTransitionNode(transitionNode);
        logger.info("Registered EVENT_GENERATOR: " + id);
    }

    private void parseArrow(String block) {
        String sourceId = extractJsonValue(block, "source");
        String targetId = extractJsonValue(block, "target");
        String label = extractJsonValue(block, "label");
        String condition = extractJsonValue(block, "guardCondition");
        if (condition == null || condition.isEmpty()) {
            condition = extractJsonValue(block, "condition");
        }
        String decisionValue = extractJsonValue(block, "decision_value");
        String endpoint = extractJsonValue(block, "endpoint");
        
        if (sourceId != null && targetId != null) {
            Map<String, String> attributes = new HashMap<>();
            if (label != null && !label.isEmpty()) attributes.put("label", label);
            if (condition != null && !condition.isEmpty()) attributes.put("condition", condition);
            if (decisionValue != null && !decisionValue.isEmpty()) attributes.put("decision_value", decisionValue);
            if (endpoint != null && !endpoint.isEmpty()) attributes.put("endpoint", endpoint);
            
            WorkflowEdge edge = new WorkflowEdge(sourceId, targetId, attributes);
            workflowModel.addWorkflowEdge(edge);
        }
    }

    // ========================================================================
    // JSON PARSING HELPERS
    // ========================================================================

    private String extractJsonSection(String json, String key) {
        int keyIndex = json.indexOf(key);
        if (keyIndex < 0) return "";
        
        int colonIndex = json.indexOf(":", keyIndex);
        if (colonIndex < 0) return "";
        
        int bracketStart = json.indexOf("[", colonIndex);
        if (bracketStart < 0) return "";
        
        int depth = 1;
        int pos = bracketStart + 1;
        while (pos < json.length() && depth > 0) {
            char c = json.charAt(pos);
            if (c == '[') depth++;
            else if (c == ']') depth--;
            pos++;
        }
        
        return json.substring(bracketStart + 1, pos - 1);
    }

    private String[] splitJsonObjects(String section) {
        List<String> objects = new ArrayList<>();
        int depth = 0;
        int start = -1;
        
        for (int i = 0; i < section.length(); i++) {
            char c = section.charAt(i);
            if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    objects.add(section.substring(start, i + 1));
                    start = -1;
                }
            }
        }
        
        return objects.toArray(new String[0]);
    }

    private String extractJsonValue(String block, String key) {
        String searchKey = "\"" + key + "\"";
        int keyIndex = block.indexOf(searchKey);
        if (keyIndex < 0) return null;
        
        int colonIndex = block.indexOf(":", keyIndex);
        if (colonIndex < 0) return null;
        
        int valueStart = colonIndex + 1;
        while (valueStart < block.length() && Character.isWhitespace(block.charAt(valueStart))) {
            valueStart++;
        }
        
        if (valueStart >= block.length()) return null;
        
        char firstChar = block.charAt(valueStart);
        
        if (firstChar == '"') {
            int endQuote = block.indexOf("\"", valueStart + 1);
            if (endQuote > valueStart) {
                return block.substring(valueStart + 1, endQuote);
            }
        } else if (firstChar == '[') {
            return null; // Array - handled separately
        } else {
            int endPos = valueStart;
            while (endPos < block.length()) {
                char c = block.charAt(endPos);
                if (c == ',' || c == '}' || c == ']') break;
                endPos++;
            }
            String value = block.substring(valueStart, endPos).trim();
            if ("null".equals(value)) return null;
            return value;
        }
        
        return null;
    }

    private List<String> extractJsonOperations(String block, String key) {
        List<String> operations = new ArrayList<>();
        String searchKey = "\"" + key + "\"";
        int keyIndex = block.indexOf(searchKey);
        if (keyIndex < 0) return operations;
        
        int colonIndex = block.indexOf(":", keyIndex);
        if (colonIndex < 0) return operations;
        
        int valueStart = colonIndex + 1;
        while (valueStart < block.length() && Character.isWhitespace(block.charAt(valueStart))) {
            valueStart++;
        }
        
        if (valueStart >= block.length()) return operations;
        
        char firstChar = block.charAt(valueStart);
        
        if (firstChar == '"') {
            int endQuote = block.indexOf("\"", valueStart + 1);
            if (endQuote > valueStart) {
                operations.add(block.substring(valueStart + 1, endQuote));
            }
        } else if (firstChar == '[') {
            int bracketEnd = block.indexOf("]", valueStart);
            if (bracketEnd > valueStart) {
                String arrayContent = block.substring(valueStart + 1, bracketEnd);
                String[] parts = arrayContent.split(",");
                for (String part : parts) {
                    String trimmed = part.trim();
                    if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                        operations.add(trimmed.substring(1, trimmed.length() - 1));
                    }
                }
            }
        }
        
        return operations;
    }

    private List<String> extractOperationArguments(String block, String operationName) {
        List<String> arguments = new ArrayList<>();
        
        String argsKey = "\"" + operationName + "_args\"";
        int keyIndex = block.indexOf(argsKey);
        if (keyIndex < 0) {
            argsKey = "\"args\"";
            keyIndex = block.indexOf(argsKey);
        }
        if (keyIndex < 0) return arguments;
        
        int colonIndex = block.indexOf(":", keyIndex);
        if (colonIndex < 0) return arguments;
        
        int bracketStart = block.indexOf("[", colonIndex);
        if (bracketStart < 0) return arguments;
        
        int bracketEnd = block.indexOf("]", bracketStart);
        if (bracketEnd < 0) return arguments;
        
        String arrayContent = block.substring(bracketStart + 1, bracketEnd);
        String[] parts = arrayContent.split(",");
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                arguments.add(trimmed.substring(1, trimmed.length() - 1));
            }
        }
        
        return arguments;
    }

    private String extractOperationReturnAttribute(String block, String operationName) {
        String returnKey = "\"" + operationName + "_return\"";
        int keyIndex = block.indexOf(returnKey);
        if (keyIndex < 0) {
            returnKey = "\"returnAttribute\"";
            keyIndex = block.indexOf(returnKey);
        }
        if (keyIndex < 0) return null;
        
        int colonIndex = block.indexOf(":", keyIndex);
        if (colonIndex < 0) return null;
        
        int valueStart = colonIndex + 1;
        while (valueStart < block.length() && Character.isWhitespace(block.charAt(valueStart))) {
            valueStart++;
        }
        
        if (valueStart < block.length() && block.charAt(valueStart) == '"') {
            int endQuote = block.indexOf("\"", valueStart + 1);
            if (endQuote > valueStart) {
                return block.substring(valueStart + 1, endQuote);
            }
        }
        
        return null;
    }

    // ========================================================================
    // PETRINET BINDING GENERATION
    // ========================================================================

    private void generatePetriNetCanonicalBindings() throws TopologyBindingException {
        logger.info("=== GENERATING PETRINET CANONICAL BINDINGS ===");
        
        // Build join slot assignments first
        buildPetriNetJoinSlotAssignments();
        
        String bindingsBasePath;
        try {
            File commonBase = new File("../");
            String commonPath = commonBase.getCanonicalPath();
            bindingsBasePath = commonPath + "/" + Config.SERVICE_ATTRIBUTE_BINDINGS_FOLDER;
        } catch (IOException e) {
            throw new TopologyBindingException("Failed to resolve ServiceAttributeBindings path", e);
        }
        
        logger.info("ServiceAttributeBindings base path: " + bindingsBasePath);
        
        Map<String, ServiceNode> services = workflowModel.getServiceNodes();
        int generatedCount = 0;
        List<String> generatedBindingFiles = new ArrayList<>();
        
        for (ServiceNode serviceNode : services.values()) {
            try {
                List<TransitionNode> incomingTransitions = workflowModel.findIncomingTransitions(serviceNode);
                
                int joinInputCount = 1;
                String incomingNodeType = "EdgeNode";
                
                if (!incomingTransitions.isEmpty()) {
                    TransitionNode incomingTransition = incomingTransitions.get(0);
                    incomingNodeType = incomingTransition.nodeType;
                    
                    if ("JoinNode".equals(incomingNodeType)) {
                        String joinInputCountStr = incomingTransition.attributes.get("joinInputCount");
                        if (joinInputCountStr != null) {
                            joinInputCount = Integer.parseInt(joinInputCountStr);
                        } else {
                            joinInputCount = countIncomingEdgesToTransition(incomingTransition);
                        }
                    }
                }
                
                List<String> argumentNames = null;
                String operationArgsStr = serviceNode.attributes.get("operationArguments");
                if (operationArgsStr != null && !operationArgsStr.isEmpty()) {
                    argumentNames = Arrays.asList(operationArgsStr.split(","));
                    logger.info("Using topology arguments for " + serviceNode.service + ": " + argumentNames);
                }
                
                String returnAttribute = determineReturnAttributeForService(serviceNode);
                
                generateCanonicalBindingFile(bindingsBasePath, serviceNode.service, 
                    serviceNode.operation, joinInputCount, argumentNames, returnAttribute);
                
                String folderName = serviceNode.service.contains("_") 
                    ? serviceNode.service.substring(0, serviceNode.service.indexOf("_"))
                    : serviceNode.service;
                String bindingFilePath = bindingsBasePath + "/" + folderName + "/" + 
                    serviceNode.service + "-CanonicalBindings.ruleml.xml";
                generatedBindingFiles.add(bindingFilePath);
                
                generatedCount++;
                logger.info("Generated canonical binding for " + serviceNode.service + 
                    " (incoming: " + incomingNodeType + ", inputs: " + joinInputCount + 
                    (argumentNames != null ? ", args: " + argumentNames : "") + ")");
                
            } catch (Exception e) {
                throw new TopologyBindingException(
                    "Failed to generate canonical binding for " + serviceNode.service, e);
            }
        }
        
        try {
            appendBindingsToServiceRuleml(generatedBindingFiles);
        } catch (IOException e) {
            throw new TopologyBindingException("Failed to append bindings to Service.ruleml", e);
        }
        
        logger.info("=== GENERATED " + generatedCount + " CANONICAL BINDING FILES ===");
    }

    private void generateSOACanonicalBindings() throws TopologyBindingException {
        logger.info("=== GENERATING SOA CANONICAL BINDINGS ===");
        // SOA binding generation - simplified version
        // Similar to PetriNet but with SOA-specific rules
        logger.warn("SOA binding generation not yet implemented - using PetriNet logic");
        generatePetriNetCanonicalBindings();
    }

    // ========================================================================
    // JOIN SLOT ASSIGNMENT
    // ========================================================================

    private void buildPetriNetJoinSlotAssignments() {
        logger.info("=== BUILDING PETRINET JOIN SLOT ASSIGNMENTS ===");
        
        petriNetJoinSlotAssignments.clear();
        
        for (TransitionNode transition : workflowModel.getTransitionNodes().values()) {
            if (!"JoinNode".equals(transition.nodeType)) {
                continue;
            }
            
            String joinNodeId = transition.nodeId;
            logger.info("Processing JoinNode: " + joinNodeId);
            
            ServiceNode destService = findServiceAfterTransition(transition);
            if (destService == null) {
                logger.warn("JoinNode " + joinNodeId + " has no destination service - skipping");
                continue;
            }
            
            List<String> argNames = getDestinationArguments(destService.service);
            if (argNames == null || argNames.isEmpty()) {
                int inputCount = countIncomingEdgesToTransition(transition);
                argNames = new ArrayList<>();
                for (int i = 1; i <= inputCount; i++) {
                    argNames.add("token_branch" + i);
                }
                logger.info("Using default arg names for " + joinNodeId + ": " + argNames);
            } else {
                logger.info("Using topology arg names for " + joinNodeId + ": " + argNames);
            }
            
            // Find incoming arcs, excluding EventGenerator and feedback loops
            List<WorkflowEdge> incomingArcs = new ArrayList<>();
            
            for (WorkflowEdge edge : workflowModel.getWorkflowEdges()) {
                if (edge.toNode.equals(joinNodeId)) {
                    // Exclude EventGenerator transitions
                    TransitionNode fromTransition = workflowModel.getTransitionNodes().get(edge.fromNode);
                    if (fromTransition != null && "EventGenerator".equals(fromTransition.nodeType)) {
                        logger.info("JOIN-SLOT: Skipping EventGenerator edge " + edge.fromNode + " -> " + joinNodeId);
                        continue;
                    }
                    
                    // Exclude feedback loops
                    if (isFeedbackLoop(edge.fromNode, joinNodeId)) {
                        logger.info("JOIN-SLOT: Excluding FEEDBACK LOOP: " + edge.fromNode + " -> " + joinNodeId);
                        continue;
                    }
                    
                    incomingArcs.add(edge);
                }
            }
            
            logger.info("JoinNode " + joinNodeId + " has " + incomingArcs.size() + 
                " valid incoming arcs, " + argNames.size() + " argument slots");
            
            // Assign slots
            Map<String, JoinSlotInfo> slotMap = new HashMap<>();
            int slotIndex = 0;
            
            for (WorkflowEdge arc : incomingArcs) {
                if (slotIndex >= argNames.size()) {
                    logger.error("JoinNode " + joinNodeId + ": More incoming arcs than argument slots");
                    continue;
                }
                
                String argName = argNames.get(slotIndex);
                JoinSlotInfo slotInfo = new JoinSlotInfo(argName, slotIndex + 1);
                slotMap.put(arc.fromNode, slotInfo);
                
                logger.info("  Assigned: " + arc.fromNode + " -> " + joinNodeId + 
                    " = slot " + (slotIndex + 1) + " (" + argName + ")");
                slotIndex++;
            }
            
            petriNetJoinSlotAssignments.put(joinNodeId, slotMap);
        }
        
        logger.info("=== BUILT JOIN SLOT ASSIGNMENTS FOR " + petriNetJoinSlotAssignments.size() + " JOINNODES ===");
    }

    private String determineReturnAttributeForService(ServiceNode serviceNode) {
        logger.info("RETURN-ATTR: Determining return attribute for service: " + serviceNode.service);
        
        // Find outgoing transition
        TransitionNode outgoingTransition = null;
        for (WorkflowEdge edge : workflowModel.getWorkflowEdges()) {
            if (edge.fromNode.equals(serviceNode.nodeId)) {
                TransitionNode transition = workflowModel.getTransitionNodes().get(edge.toNode);
                if (transition != null) {
                    outgoingTransition = transition;
                    break;
                }
            }
        }
        
        if (outgoingTransition == null) {
            logger.info("RETURN-ATTR: No outgoing transition - using 'token'");
            return "token";
        }
        
        String outgoingTransitionId = outgoingTransition.nodeId;
        logger.info("RETURN-ATTR: " + serviceNode.service + " has outgoing transition: " + outgoingTransitionId);
        
        // Check if outgoing transition feeds into a JoinNode
        for (Map.Entry<String, Map<String, JoinSlotInfo>> entry : petriNetJoinSlotAssignments.entrySet()) {
            Map<String, JoinSlotInfo> slotMap = entry.getValue();
            
            if (slotMap.containsKey(outgoingTransitionId)) {
                JoinSlotInfo slotInfo = slotMap.get(outgoingTransitionId);
                logger.info("RETURN-ATTR: MATCH! " + serviceNode.service + " feeds into " + 
                    entry.getKey() + " -> returnAttribute='" + slotInfo.argName + "'");
                return slotInfo.argName;
            }
        }
        
        logger.info("RETURN-ATTR: No JoinNode match - using 'token'");
        return "token";
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    private ServiceNode findServiceAfterTransition(TransitionNode transition) {
        for (WorkflowEdge edge : workflowModel.getWorkflowEdges()) {
            if (edge.fromNode.equals(transition.nodeId)) {
                ServiceNode service = workflowModel.getServiceNodes().get(edge.toNode);
                if (service != null) {
                    return service;
                }
            }
        }
        return null;
    }

    private List<String> getDestinationArguments(String serviceName) {
        for (ServiceNode service : workflowModel.getServiceNodes().values()) {
            if (service.service.equals(serviceName)) {
                String argsStr = service.attributes.get("operationArguments");
                if (argsStr != null && !argsStr.isEmpty()) {
                    return Arrays.asList(argsStr.split(","));
                }
            }
        }
        return null;
    }

    private int countIncomingEdgesToTransition(TransitionNode transition) {
        int count = 0;
        for (WorkflowEdge edge : workflowModel.getWorkflowEdges()) {
            if (edge.toNode.equals(transition.nodeId)) {
                // Exclude EventGenerator
                TransitionNode fromTransition = workflowModel.getTransitionNodes().get(edge.fromNode);
                if (fromTransition != null && "EventGenerator".equals(fromTransition.nodeType)) {
                    continue;
                }
                // Exclude feedback loops
                if (!isFeedbackLoop(edge.fromNode, transition.nodeId)) {
                    logger.debug("JOIN-COUNT: Counting edge " + edge.fromNode + " -> " + transition.nodeId);
                    count++;
                }
            }
        }
        return count;
    }

    private boolean isFeedbackLoop(String sourceTransitionId, String targetTransitionId) {
        if (sourceTransitionId == null || targetTransitionId == null) return false;
        
        String sourceSuffix = extractTransitionSuffix(sourceTransitionId);
        String targetSuffix = extractTransitionSuffix(targetTransitionId);
        
        if (sourceSuffix != null && sourceSuffix.equals(targetSuffix)) {
            logger.info("FEEDBACK-LOOP DETECTED: " + sourceTransitionId + " -> " + targetTransitionId);
            return true;
        }
        return false;
    }

    private String extractTransitionSuffix(String transitionId) {
        if (transitionId == null) return null;
        
        if (transitionId.startsWith("T_in_")) {
            return transitionId.substring(5);
        } else if (transitionId.startsWith("T_out_")) {
            return transitionId.substring(6);
        }
        return null;
    }

    // ========================================================================
    // FILE GENERATION
    // ========================================================================

    private void generateCanonicalBindingFile(String basePath, String serviceName, 
            String operationName, int inputCount, List<String> argumentNames, String returnAttribute) 
            throws IOException {
        
        String folderName = serviceName.contains("_") 
            ? serviceName.substring(0, serviceName.indexOf("_")) 
            : serviceName;
        
        String serviceDir = basePath + "/" + folderName;
        File serviceDirFile = new File(serviceDir);
        if (!serviceDirFile.exists()) {
            serviceDirFile.mkdirs();
        }
        
        String fileName = serviceName + "-CanonicalBindings.ruleml.xml";
        String fullPath = serviceDir + "/" + fileName;
        
        boolean hasExplicitArgs = argumentNames != null && !argumentNames.isEmpty();
        
        logger.info("Writing canonical binding file: " + fullPath);
        
        try (PrintWriter pw = new PrintWriter(new FileWriter(fullPath))) {
            pw.println("<!-- Canonical Binding for " + serviceName + " -->");
            pw.println("<!-- Auto-generated by TopologyBindingGenerator - DO NOT EDIT -->");
            pw.println("<!-- Input count: " + inputCount + " -->");
            if (hasExplicitArgs) {
                pw.println("<!-- Arguments from topology: " + argumentNames + " -->");
            }
            if (returnAttribute != null) {
                pw.println("<!-- Return attribute from topology: " + returnAttribute + " -->");
            }
            pw.println();
            
            pw.println("<Atom>");
            pw.println("\t<Rel>localDefined</Rel>");
            pw.println("\t<Ind>" + serviceName + "</Ind>");
            pw.println("</Atom>");
            
            if (hasExplicitArgs) {
                String returnAttr = (returnAttribute != null && !returnAttribute.isEmpty()) 
                    ? returnAttribute 
                    : (argumentNames.size() == 1 ? argumentNames.get(0) : "token");
                
                for (String argName : argumentNames) {
                    pw.println("<Atom>");
                    pw.println("\t<Rel>canonicalBinding</Rel>");
                    pw.println("\t<Ind>" + operationName + "</Ind>");
                    pw.println("\t<Ind>" + returnAttr + "</Ind>");
                    pw.println("\t<Ind>" + argName + "</Ind>");
                    pw.println("</Atom>");
                }
            } else {
                String inputAttr = "token";
                String outputAttr = (returnAttribute != null && !returnAttribute.isEmpty()) 
                    ? returnAttribute : "token";
                
                for (int i = 0; i < inputCount; i++) {
                    pw.println("<Atom>");
                    pw.println("\t<Rel>canonicalBinding</Rel>");
                    pw.println("\t<Ind>" + operationName + "</Ind>");
                    pw.println("\t<Ind>" + outputAttr + "</Ind>");
                    pw.println("\t<Ind>" + inputAttr + "</Ind>");
                    pw.println("</Atom>");
                }
            }
        }
        
        logger.debug("Successfully wrote canonical binding file: " + fullPath);
    }

    private void appendBindingsToServiceRuleml(List<String> bindingFilePaths) throws IOException {
        String serviceRulemlPath = "../" + Config.COMMON_FOLDER + "/RuleFolder." + buildVersion + "/Service.ruleml";
        File serviceRulemlFile = new File(serviceRulemlPath);
        
        if (!serviceRulemlFile.exists()) {
            logger.warn("Service.ruleml not found - skipping binding append");
            return;
        }
        
        String content;
        try (BufferedReader reader = new BufferedReader(new FileReader(serviceRulemlFile))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            content = sb.toString();
        }
        
        if (content.contains("PETRINET CANONICAL BINDINGS")) {
            logger.info("PetriNet canonical bindings already exist in Service.ruleml - skipping append");
            return;
        }
        
        StringBuilder bindingContent = new StringBuilder();
        bindingContent.append("\n<!-- ================================================================ -->\n");
        bindingContent.append("<!-- PETRINET CANONICAL BINDINGS (Auto-generated)                    -->\n");
        bindingContent.append("<!-- ================================================================ -->\n\n");
        
        for (String bindingFilePath : bindingFilePaths) {
            File bindingFile = new File(bindingFilePath);
            if (!bindingFile.exists()) continue;
            
            try (BufferedReader reader = new BufferedReader(new FileReader(bindingFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.trim().startsWith("<!--")) continue;
                    if (line.contains("<Atom>") || line.contains("</Atom>") || 
                        line.contains("<Rel>") || line.contains("<Ind>")) {
                        bindingContent.append(line).append("\n");
                    }
                }
            }
        }
        
        int insertPoint = content.lastIndexOf("</Rulebase>");
        if (insertPoint < 0) {
            logger.error("Could not find </Rulebase> in Service.ruleml");
            return;
        }
        
        String newContent = content.substring(0, insertPoint) + bindingContent.toString() + content.substring(insertPoint);
        
        try (PrintWriter writer = new PrintWriter(new FileWriter(serviceRulemlFile))) {
            writer.print(newContent);
        }
        
        logger.info("Appended " + bindingFilePaths.size() + " PetriNet canonical bindings to Service.ruleml");
    }

    // ========================================================================
    // EXCEPTION CLASS
    // ========================================================================

    public static class TopologyBindingException extends Exception {
        public TopologyBindingException(String message) {
            super(message);
        }
        
        public TopologyBindingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}