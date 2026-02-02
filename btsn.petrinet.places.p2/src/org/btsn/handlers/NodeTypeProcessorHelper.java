package org.btsn.handlers;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.apache.log4j.Logger;
import org.btsn.json.jsonLibrary;
import org.json.simple.JSONObject;

/**
 * NodeTypeProcessorHelper - Handles processing logic for different workflow node types.
 * 
 * Extracted from ServiceThread to improve maintainability.
 * Each processXxxType method handles the specific logic for that node type.
 * 
 * This helper maintains a reference to ServiceThread for:
 * - Accessing shared state (serviceName, operationName, sequenceID, etc.)
 * - Calling back to routing methods (callNextOperation)
 * - Service invocation (callServiceWithCanonicalBinding)
 * 
 * REFACTORED: Added missing T_out EXIT instrumentation to:
 *   - processJoinType()
 *   - processDecisionType()
 *   - processFeedFwdNode()
 *   - processExpiredType()
 */
class NodeTypeProcessorHelper {

    private final ServiceThread serviceThread;
    private final Logger logger;

    // Decision condition constants
    private static final String DECISION_GREATER_THAN = "DECISION_GREATER_THAN";
    private static final String DECISION_LESS_THAN = "DECISION_LESS_THAN";
    private static final String DECISION_NOT_EQUAL = "DECISION_NOT_EQUAL";
    private static final String DECISION_EQUAL_TO = "DECISION_EQUAL_TO";
    private static final String DECISION_TRUE = "DECISION_TRUE";
    private static final String DECISION_FALSE = "DECISION_FALSE";

    NodeTypeProcessorHelper(ServiceThread serviceThread) {
        this.serviceThread = serviceThread;
        this.logger = Logger.getLogger(NodeTypeProcessorHelper.class.getName());
    }

    // ========================================================================
    // DECISION NODE PROCESSING
    // ========================================================================

    void processDecisionType(String service, String operation, ArrayList<?> inputArgs2) {
        String condition = null;
        String inference_condition = null;
        String val = null;

        try {
            logger.info("ORCHESTRATOR: Processing DecisionNode for " + service + "." + operation);

            ServiceHelper.ServiceResult serviceResult = serviceThread.callServiceWithCanonicalBinding(
                    service, operation, inputArgs2, serviceThread.getReturnAttributeName());
            val = serviceResult.getResult();

            logger.info("ORCHESTRATOR: Service returned: " + (val != null ? val : "null"));

            if (val == null) {
                System.err.println("Invalid arguments or null return from service invocation.");
                return;
            }

            String returnType = serviceResult.getReturnType();
            String attributeValue = val;
            String comparisonValue = val;
            Double numericValue = null;
            boolean isJson = false;

            if ("String".equals(returnType)) {
                JSONObject parsedValue = jsonLibrary.parseString(val);

                if (parsedValue != null) {
                    isJson = true;
                    logger.debug("DECISION: Parsed JSON from service response");

                    String routingPath = extractRoutingPath(parsedValue);

                    if (routingPath != null) {
                        attributeValue = routingPath;
                        comparisonValue = routingPath;
                        logger.info("DECISION: Extracted routing_path = '" + routingPath + "' from routing_decision");

                    } else if (serviceThread.getReturnAttributeName() != null && 
                               parsedValue.containsKey(serviceThread.getReturnAttributeName())) {
                        attributeValue = parsedValue.get(serviceThread.getReturnAttributeName()).toString();
                        comparisonValue = attributeValue;
                        logger.debug("DECISION: Using returnAttributeName '" + 
                                    serviceThread.getReturnAttributeName() + "' = " + attributeValue);

                    } else {
                        logger.warn("DECISION: No routing_decision.routing_path found, using raw response");
                    }

                    try {
                        numericValue = Double.parseDouble(attributeValue);
                    } catch (NumberFormatException e) {
                        logger.debug("DECISION: Attribute value is non-numeric: " + attributeValue);
                    }
                }
            }

            TreeMap<Integer, String> decisionValueCollection = serviceThread.getDecisionValueCollection();
            TreeMap<Integer, String> nextServiceMap = serviceThread.getNextServiceMap();

            for (int solutionIndex = 0; solutionIndex < decisionValueCollection.size(); solutionIndex++) {
                String nextCondition = nextServiceMap.get(solutionIndex);
                if (nextCondition == null) {
                    logger.warn("No condition found for solutionIndex: " + solutionIndex);
                    continue;
                }

                logger.debug("DECISION: Comparing '" + comparisonValue + "' with decision value '"
                        + decisionValueCollection.get(solutionIndex) + "' for condition " + nextCondition);

                if (returnType == null) {
                    comparisonValue = String.valueOf(comparisonValue);
                    if (serviceThread.guardsMatch(decisionValueCollection.get(solutionIndex), comparisonValue))
                        condition = DECISION_EQUAL_TO;
                    else
                        condition = DECISION_NOT_EQUAL;
                } else {
                    switch (returnType) {
                    case "String":
                        if (isJson && numericValue != null && (nextCondition.equals(DECISION_LESS_THAN)
                                || nextCondition.equals(DECISION_GREATER_THAN))) {
                            double decisionValue;
                            try {
                                String cleanDecisionValue = cleanQuotes(decisionValueCollection.get(solutionIndex));
                                decisionValue = Double.parseDouble(cleanDecisionValue);
                            } catch (NumberFormatException e) {
                                logger.debug("Skipping non-numeric decision value: "
                                        + decisionValueCollection.get(solutionIndex));
                                continue;
                            }
                            if (numericValue < decisionValue)
                                condition = DECISION_LESS_THAN;
                            else if (numericValue > decisionValue)
                                condition = DECISION_GREATER_THAN;
                            else
                                condition = DECISION_EQUAL_TO;
                            inference_condition = condition.equals(DECISION_EQUAL_TO) ? null : DECISION_NOT_EQUAL;
                        } else {
                            String cleanAttributeValue = cleanQuotes(comparisonValue);
                            String cleanDecisionValue = cleanQuotes(decisionValueCollection.get(solutionIndex));

                            logger.debug("DECISION: String comparison - '" + cleanAttributeValue + "' vs '"
                                    + cleanDecisionValue + "'");

                            if (serviceThread.guardsMatch(cleanAttributeValue, cleanDecisionValue)) {
                                condition = DECISION_EQUAL_TO;
                                inference_condition = null;
                                logger.info("DECISION: MATCH FOUND - '" + cleanAttributeValue + "' equals '"
                                        + cleanDecisionValue + "'");
                            } else {
                                int comparisonResult = cleanAttributeValue.compareTo(cleanDecisionValue);
                                if (comparisonResult > 0) {
                                    condition = DECISION_GREATER_THAN;
                                } else {
                                    condition = DECISION_LESS_THAN;
                                }
                                inference_condition = DECISION_NOT_EQUAL;
                            }
                        }
                        break;
                    case "boolean":
                        boolean bval = Boolean.parseBoolean(comparisonValue);
                        boolean bdval = Boolean.parseBoolean(decisionValueCollection.get(solutionIndex));
                        if (bval == bdval)
                            condition = DECISION_TRUE;
                        if (bval != bdval)
                            condition = DECISION_FALSE;
                        comparisonValue = String.valueOf(bval);
                        break;
                    case "int":
                        int ival = Integer.parseInt(comparisonValue);
                        int idval = Integer.parseInt(decisionValueCollection.get(solutionIndex));
                        comparisonValue = String.valueOf(ival);
                        if (ival > idval)
                            condition = DECISION_GREATER_THAN;
                        if (ival == idval)
                            condition = DECISION_EQUAL_TO;
                        if (ival < idval)
                            condition = DECISION_LESS_THAN;
                        break;
                    case "long":
                        long lval = Long.parseLong(comparisonValue);
                        Long ldvalObject = Long.valueOf(decisionValueCollection.get(solutionIndex));
                        long ldval = ldvalObject;
                        comparisonValue = String.valueOf(lval);
                        if (lval > ldval)
                            condition = DECISION_GREATER_THAN;
                        if (lval == ldval)
                            condition = DECISION_EQUAL_TO;
                        if (lval < ldval)
                            condition = DECISION_LESS_THAN;
                        break;
                    case "double":
                        Double dvalObject = Double.valueOf(comparisonValue);
                        double dval = dvalObject;
                        double ddval = Double.parseDouble(decisionValueCollection.get(solutionIndex));
                        comparisonValue = String.valueOf(dval);
                        if (dval > ddval)
                            condition = DECISION_GREATER_THAN;
                        if (dval == ddval)
                            condition = DECISION_EQUAL_TO;
                        if (dval < ddval)
                            condition = DECISION_LESS_THAN;
                        break;
                    default:
                        comparisonValue = String.valueOf(comparisonValue);
                        if (serviceThread.guardsMatch(decisionValueCollection.get(solutionIndex), comparisonValue))
                            condition = DECISION_EQUAL_TO;
                        else
                            condition = DECISION_NOT_EQUAL;
                        break;
                    }
                }

                logger.debug("DECISION: Condition determined: " + condition + ", Expected: "
                        + nextServiceMap.get(solutionIndex));

                if (inference_condition != null) {
                    if (condition.equals(nextServiceMap.get(solutionIndex))) {
                        logger.info("DECISION: ROUTING MATCH - Condition " + condition + " matches expected "
                                + nextServiceMap.get(solutionIndex));
                        serviceThread.setAttributeValidity(serviceThread.getReturnAttributeName(), attributeValue);
                        // PETRI NET: Record T_out EXIT event before routing
                        instrumentDecisionExit(solutionIndex, attributeValue);
                        serviceThread.callNextOperation(attributeValue, solutionIndex, false);
                        return;
                    } else {
                        if (nextServiceMap.get(solutionIndex) != null) {
                            if (condition.equals(nextServiceMap.get(solutionIndex))) {
                                logger.info("DECISION: ROUTING MATCH - Condition " + condition + " matches expected "
                                        + nextServiceMap.get(solutionIndex));
                                serviceThread.setAttributeValidity(serviceThread.getReturnAttributeName(), attributeValue);
                                // PETRI NET: Record T_out EXIT event before routing
                                instrumentDecisionExit(solutionIndex, attributeValue);
                                serviceThread.callNextOperation(attributeValue, solutionIndex, false);
                                return;
                            }
                        } else {
                            logger.debug("In Service: " + serviceThread.getServiceName() + 
                                        " : Ignoring based on decision outcome. " + serviceThread.getNodeType());
                            return;
                        }
                    }
                } else {
                    if (nextServiceMap.get(solutionIndex) != null) {
                        if (condition.equals(nextServiceMap.get(solutionIndex))) {
                            logger.info("DECISION: ROUTING MATCH - Condition " + condition + " matches expected "
                                    + nextServiceMap.get(solutionIndex));
                            serviceThread.setAttributeValidity(serviceThread.getReturnAttributeName(), attributeValue);
                            // PETRI NET: Record T_out EXIT event before routing
                            instrumentDecisionExit(solutionIndex, attributeValue);
                            serviceThread.callNextOperation(attributeValue, solutionIndex, false);
                            return;
                        }
                    } else {
                        System.err.println("Ignoring based on decision outcome or missing nextService. " + 
                                          serviceThread.getNodeType());
                        return;
                    }
                }
            }

            System.err.println("DECISION: No matching condition found.");
            System.err.println("  Comparison value: '" + comparisonValue + "'");
            System.err.println("  Available decision values: " + decisionValueCollection.values());
            System.err.println("  Available conditions: " + nextServiceMap.values());

        } catch (RuntimeException e) {
            e.printStackTrace();
            System.err.println("Decision Node error processing evaluation.");
        }
    }

    // ========================================================================
    // XOR NODE PROCESSING
    // ========================================================================

    /**
     * Process XorNode - Conditional routing gateway (XOR or Fork depending on guards)
     * 
     * FIXED: Now evaluates EACH branch individually against its own (condition_type, expected_value).
     */
    void processXorType(String service, String operation, ArrayList<?> sargs) {
        logger.info("ORCHESTRATOR: Processing XorNode for " + service + "." + operation);
        logger.info("XOR-DIAG: === START XOR ROUTING ===");

        serviceThread.reloadKnowledgeBase();

        ServiceHelper.ServiceResult serviceResult = serviceThread.callServiceWithCanonicalBinding(
                service, operation, sargs, serviceThread.getReturnAttributeName());
        String val = serviceResult.getResult();
        logger.info("ORCHESTRATOR: Service returned: " + (val != null ? val : "null"));

        if (val == null) {
            return;
        }

        String routingPath = extractRoutingDecision(serviceResult);
        if (routingPath == null) {
            logger.error("XOR: Could not extract routing decision from service response");
            return;
        }

        String cleanRoutingPath = cleanQuotes(routingPath);
        logger.info("XOR-DIAG: Routing decision = '" + cleanRoutingPath + "'");

        ArrayList<String> nextServiceCollection = serviceThread.getNextServiceCollection();
        TreeMap<Integer, String> nextServiceMap = serviceThread.getNextServiceMap();
        TreeMap<Integer, String> decisionValueCollection = serviceThread.getDecisionValueCollection();

        logger.info("XOR-DIAG: Collection sizes: services=" + nextServiceCollection.size() +
                   ", conditions=" + nextServiceMap.size() +
                   ", decisionValues=" + decisionValueCollection.size());

        if (nextServiceCollection.size() != decisionValueCollection.size()) {
            logger.warn("XOR-DIAG: WARNING - Collection sizes don't match! May have alignment issues.");
            logger.warn("XOR-DIAG: nextServiceCollection=" + nextServiceCollection);
            logger.warn("XOR-DIAG: decisionValueCollection=" + decisionValueCollection.values());
        }

        logger.info("XOR-DIAG: Evaluating " + nextServiceCollection.size() + " branches:");

        List<Integer> matchingBranchIndices = new ArrayList<>();
        List<Integer> defaultBranchIndices = new ArrayList<>();

        for (int i = 0; i < nextServiceCollection.size(); i++) {
            String branchConditionType = nextServiceMap.get(i);
            String branchExpectedValue = decisionValueCollection.get(i);

            if (branchConditionType == null || branchConditionType.isEmpty()) {
                logger.info("XOR-DIAG:   Branch " + i + " -> " + nextServiceCollection.get(i) +
                           ": DEFAULT (no condition) - will use if no other match");
                defaultBranchIndices.add(i);
                continue;
            }

            if (branchExpectedValue == null && !decisionValueCollection.isEmpty()) {
                branchExpectedValue = decisionValueCollection.get(0);
                logger.warn("XOR-DIAG: Branch " + i + " missing expected value, using first: '" + branchExpectedValue + "'");
            }

            String cleanExpectedValue = branchExpectedValue != null ? cleanQuotes(branchExpectedValue) : null;

            boolean conditionSatisfied = evaluateGuardCondition(
                cleanRoutingPath,
                cleanExpectedValue,
                branchConditionType
            );

            logger.info("XOR-DIAG:   Branch " + i + " -> " + nextServiceCollection.get(i) +
                       ": '" + cleanRoutingPath + "' " + branchConditionType + " '" + cleanExpectedValue +
                       "' => " + (conditionSatisfied ? "MATCH" : "no match"));

            if (conditionSatisfied) {
                matchingBranchIndices.add(i);
            }
        }

        if (matchingBranchIndices.isEmpty() && !defaultBranchIndices.isEmpty()) {
            logger.info("XOR-DIAG: No conditions matched, using default branch(es): " + defaultBranchIndices);
            matchingBranchIndices.addAll(defaultBranchIndices);
        }

        logger.info("XOR-DIAG: Total matching branches: " + matchingBranchIndices.size());

        if (matchingBranchIndices.isEmpty()) {
            logger.warn("XOR: No branches matched routing decision '" + cleanRoutingPath + "' - event dropped");
            logger.info("XOR-DIAG: === END XOR ROUTING (NO MATCH) ===");
            return;
        }

        serviceThread.setAttributeValidity(serviceThread.getReturnAttributeName(), val);

        boolean isMultiMatch = matchingBranchIndices.size() > 1;
        int joinCount = matchingBranchIndices.size();
        
        // FIX: Use phaseSequenceID - the actual token that entered this place (from XML payload)
        // getSequenceID() returns joinID which is WRONG for instrumentation
        int actualTokenId = serviceThread.getPhaseSequenceID();
        int workflowBase = (actualTokenId / 100) * 100;  // Parent for fork operations
        
        // For fork: always start at branch 1; for single-match: not used
        int branchNumber = 1;

        ArrayList<String> nextOperationCollection = serviceThread.getNextOperationCollection();
        ArrayList<String> nextChannelCollection = serviceThread.getNextChannelCollection();
        ArrayList<String> nextPortCollection = serviceThread.getNextPortCollection();

        // PETRI NET: For multi-match (fork), record T_out for PARENT token
        if (isMultiMatch && !matchingBranchIndices.isEmpty()) {
            int firstIndex = matchingBranchIndices.get(0);
            String firstService = nextServiceCollection.get(firstIndex);
            String firstOp = nextOperationCollection.get(firstIndex);
            serviceThread.instrumentTokenExit(workflowBase, serviceThread.getServiceName(),
                                              firstService, firstOp, "ForkNode", cleanRoutingPath);
        }

        for (int branchIndex : matchingBranchIndices) {
            // For fork: create child token ID; for single-match: same token continues
            int newSequenceId = isMultiMatch ? (workflowBase + branchNumber) : actualTokenId;
            serviceThread.getHeaderMap().put("sequenceId", Integer.toString(newSequenceId));

            String nextService = nextServiceCollection.get(branchIndex);
            String nextOp = nextOperationCollection.get(branchIndex);
            String channel = nextChannelCollection.get(branchIndex);
            String port = nextPortCollection.get(branchIndex);

            logger.info("XOR-DIAG: Routing to " + nextService + "." + nextOp +
                       " with sequenceId=" + newSequenceId +
                       (isMultiMatch ? " (encoded: branch=" + branchNumber + ")" : ""));

            serviceThread.getServiceMap().put("serviceName", nextService);
            serviceThread.getServiceMap().put("operation", nextOp);

            // PETRI NET: Record genealogy and exit for fork children
            if (isMultiMatch) {
                serviceThread.instrumentForkChild(workflowBase, newSequenceId, serviceThread.getServiceName());
                serviceThread.instrumentTokenExit(newSequenceId, serviceThread.getServiceName(),
                                                  nextService, nextOp, "ForkNode", cleanRoutingPath);
            } else {
                // Single-match XOR: record T_out EXIT event with ACTUAL token that entered
                serviceThread.instrumentTokenExit(actualTokenId, serviceThread.getServiceName(),
                                                  nextService, nextOp, "EdgeNode", cleanRoutingPath);
            }

            serviceThread.updateSequenceIdInPayload(newSequenceId);
            
            // Route to next service
            serviceThread.callNextOperation(val, branchIndex, false);

            branchNumber++;
        }

        logger.info("XOR-DIAG: === END XOR ROUTING ===");
    }

    // ========================================================================
    // GATEWAY NODE PROCESSING
    // ========================================================================

    /**
     * Process GatewayNode - Dynamic routing based on decision_value matching
     */
    void processGatewayType(String service, String operation, ArrayList<?> sargs) {
        logger.info("ORCHESTRATOR: Processing GatewayNode for " + service + "." + operation);
        logger.info("GATEWAY-DIAG: === START GATEWAY ROUTING ===");

        serviceThread.reloadKnowledgeBase();

        ServiceHelper.ServiceResult serviceResult = serviceThread.callServiceWithCanonicalBinding(
                service, operation, sargs, serviceThread.getReturnAttributeName());
        String val = serviceResult.getResult();
        logger.info("ORCHESTRATOR: Service returned: " + (val != null ? val : "null"));

        if (val == null) {
            logger.error("GATEWAY: Service returned null - cannot route");
            return;
        }

        String routingPath = extractRoutingDecision(serviceResult);
        if (routingPath == null) {
            logger.error("GATEWAY: Could not extract routing_path from service response");
            return;
        }

        String cleanRoutingPath = cleanQuotes(routingPath);
        logger.info("GATEWAY-DIAG: Service routing_path = '" + cleanRoutingPath + "'");

        ArrayList<String> nextServiceCollection = serviceThread.getNextServiceCollection();
        TreeMap<Integer, String> decisionValueCollection = serviceThread.getDecisionValueCollection();

        logger.info("GATEWAY-DIAG: Available routes: " + nextServiceCollection.size());
        for (int i = 0; i < nextServiceCollection.size(); i++) {
            String svc = nextServiceCollection.get(i);
            String decVal = decisionValueCollection.get(i);
            logger.info("GATEWAY-DIAG:   [" + i + "] " + svc + " (decision_value='" + decVal + "')");
        }

        List<Integer> matchingIndices = new ArrayList<>();
        for (int i = 0; i < nextServiceCollection.size(); i++) {
            String expectedValue = decisionValueCollection.get(i);
            String cleanExpected = expectedValue != null ? cleanQuotes(expectedValue) : "";

            if (serviceThread.guardsMatch(cleanRoutingPath, cleanExpected)) {
                matchingIndices.add(i);
                logger.info("GATEWAY-DIAG: MATCH - '" + cleanRoutingPath + "' == '" + cleanExpected +
                           "' -> " + nextServiceCollection.get(i));
            }
        }

        logger.info("GATEWAY-DIAG: Total matches: " + matchingIndices.size());

        if (matchingIndices.isEmpty()) {
            logger.error("GATEWAY: No destinations match routing_path '" + cleanRoutingPath + "' - token dropped");
            logger.info("GATEWAY-DIAG: === END GATEWAY ROUTING (NO MATCH) ===");
            return;
        }

        serviceThread.setAttributeValidity(serviceThread.getReturnAttributeName(), val);

        if (matchingIndices.size() > 1) {
            logger.info("GATEWAY-DIAG: Multiple matches (" + matchingIndices.size() + ") -> FORK strategy");
            executeGatewayForkByIndices(matchingIndices, val, cleanRoutingPath);
        } else {
            logger.info("GATEWAY-DIAG: Single match -> EDGE strategy");
            executeGatewayEdgeByIndex(matchingIndices.get(0), val, cleanRoutingPath);
        }

        logger.info("GATEWAY-DIAG: === END GATEWAY ROUTING ===");
    }

    /**
     * Execute FORK routing by indices - parallel split with child tokens
     */
    private void executeGatewayForkByIndices(List<Integer> matchingIndices, String attributeValue, String arcValue) {
        int joinCount = matchingIndices.size();
        
        // FIX: Use phaseSequenceID - the actual token that entered this place (from XML payload)
        // getSequenceID() returns joinID which is WRONG for instrumentation
        int actualTokenId = serviceThread.getPhaseSequenceID();
        int workflowBase = (actualTokenId / 100) * 100;  // Parent for fork operations
        
        logger.info("GATEWAY-FORK: Parallel split to " + joinCount + " destinations, parent token " + workflowBase + " [arc=" + arcValue + "]");

        ArrayList<String> nextServiceCollection = serviceThread.getNextServiceCollection();
        ArrayList<String> nextOperationCollection = serviceThread.getNextOperationCollection();
        ArrayList<String> nextChannelCollection = serviceThread.getNextChannelCollection();
        ArrayList<String> nextPortCollection = serviceThread.getNextPortCollection();

        int firstIndex = matchingIndices.get(0);
        String firstService = nextServiceCollection.get(firstIndex);
        String firstOp = nextOperationCollection.get(firstIndex);

        // PETRI NET: Record parent token exit
        serviceThread.instrumentTokenExit(workflowBase, serviceThread.getServiceName(),
                                          firstService, firstOp, "ForkNode", arcValue);

        // Fork always creates children starting at branch 1
        int branchNumber = 1;
        for (int branchIndex : matchingIndices) {
            // Create child token ID for fork
            int childTokenId = workflowBase + branchNumber;
            serviceThread.getHeaderMap().put("sequenceId", Integer.toString(childTokenId));

            String nextService = nextServiceCollection.get(branchIndex);
            String nextOp = nextOperationCollection.get(branchIndex);
            String channel = nextChannelCollection.get(branchIndex);
            String port = nextPortCollection.get(branchIndex);

            logger.info("GATEWAY-FORK: Routing child token " + childTokenId + " to " + nextService + "." + nextOp +
                       " (encoded: branch=" + branchNumber + ")");

            serviceThread.getServiceMap().put("serviceName", nextService);
            serviceThread.getServiceMap().put("operation", nextOp);

            // PETRI NET: Record fork child genealogy and exit
            serviceThread.instrumentForkChild(workflowBase, childTokenId, serviceThread.getServiceName());
            serviceThread.instrumentTokenExit(childTokenId, serviceThread.getServiceName(),
                                              nextService, nextOp, "ForkNode", arcValue);

            serviceThread.updateSequenceIdInPayload(childTokenId);
            serviceThread.callNextOperation(attributeValue, branchIndex, false);

            branchNumber++;
        }

        logger.info("GATEWAY-FORK: Created " + joinCount + " child tokens from parent " + workflowBase);
    }

    /**
     * Execute EDGE routing by index - single path, same token ID
     */
    private void executeGatewayEdgeByIndex(int matchingIndex, String attributeValue, String arcValue) {
        ArrayList<String> nextServiceCollection = serviceThread.getNextServiceCollection();
        ArrayList<String> nextOperationCollection = serviceThread.getNextOperationCollection();
        ArrayList<String> nextChannelCollection = serviceThread.getNextChannelCollection();
        ArrayList<String> nextPortCollection = serviceThread.getNextPortCollection();

        String nextService = nextServiceCollection.get(matchingIndex);
        String nextOp = nextOperationCollection.get(matchingIndex);

        if ("TERMINATE".equals(nextService) && "TERMINATE".equals(nextOp)) {
            logger.info("GATEWAY-TERMINATE: Token " + serviceThread.getSequenceID() + " terminated via GatewayNode decision [arc=" + arcValue + "]");

            serviceThread.instrumentTokenTerminate(serviceThread.getSequenceID(), serviceThread.getServiceName(), "GatewayNode");
            return;
        }

        String channel = nextChannelCollection.get(matchingIndex);
        String port = nextPortCollection.get(matchingIndex);

        logger.info("GATEWAY-EDGE: Single path to " + nextService + "." + nextOp + " with token " + serviceThread.getPhaseSequenceID() + " [arc=" + arcValue + "]");

        serviceThread.getServiceMap().put("serviceName", nextService);
        serviceThread.getServiceMap().put("operation", nextOp);

        // PETRI NET: Record T_out EXIT event - use phaseSequenceID (actual token that entered)
        serviceThread.instrumentTokenExit(serviceThread.getPhaseSequenceID(), serviceThread.getServiceName(),
                                          nextService, nextOp, serviceThread.getNodeType(), arcValue);

        serviceThread.callNextOperation(attributeValue, matchingIndex, false);
    }

    // ========================================================================
    // FORK NODE PROCESSING
    // ========================================================================

    /**
     * Process ForkNode - Unconditional parallel split (Parallel Gateway)
     */
    void processForkType(String service, String operation, ArrayList<?> sargs) {
        logger.info("ORCHESTRATOR: Processing ForkNode (Parallel Gateway) for " + service + "." + operation);

        serviceThread.reloadKnowledgeBase();

        String val = null;
        if (service != null && !service.isEmpty() && !service.equals("null")) {
            ServiceHelper.ServiceResult serviceResult = serviceThread.callServiceWithCanonicalBinding(
                    service, operation, sargs, serviceThread.getReturnAttributeName());
            val = serviceResult.getResult();
            logger.info("FORK: Transformation service returned: " + (val != null ? val : "null"));
        } else {
            val = sargs != null && !sargs.isEmpty() ? sargs.get(0).toString() : "";
            logger.info("FORK: No transformation service - using input directly");
        }

        if (val == null) {
            val = "";
        }

        List<ServiceThread.ServiceRoute> allServices = serviceThread.collectParallelServices();

        if (allServices.isEmpty()) {
            logger.warn("FORK: No parallel services configured - event dropped");
            return;
        }

        serviceThread.setAttributeValidity(serviceThread.getReturnAttributeName(), val);

        int joinCount = allServices.size();
        
        // FIX: Use phaseSequenceID - the actual token that entered this place (from XML payload)
        // getSequenceID() returns joinID which is WRONG for instrumentation
        int actualTokenId = serviceThread.getPhaseSequenceID();
        int workflowBase = (actualTokenId / 100) * 100;  // Parent for fork operations

        logger.info("FORK: Parallel split to " + joinCount + " service(s)");

        // PETRI NET: Record parent token exit
        if (!allServices.isEmpty()) {
            ServiceThread.ServiceRoute firstRoute = allServices.get(0);
            serviceThread.instrumentTokenExitSimple(workflowBase, serviceThread.getServiceName(),
                                                    firstRoute.serviceName, firstRoute.operationName,
                                                    serviceThread.getNodeType());
        }

        // Fork always creates children starting at branch 1
        int branchNumber = 1;
        for (ServiceThread.ServiceRoute route : allServices) {
            // Create child token ID for fork
            int childTokenId = workflowBase + branchNumber;
            serviceThread.getHeaderMap().put("sequenceId", Integer.toString(childTokenId));

            logger.info("FORK: Routing child token " + childTokenId + " to " + route.serviceName +
                       " (encoded: branch=" + branchNumber + ")");

            // PETRI NET: Record fork child genealogy
            serviceThread.instrumentForkChild(workflowBase, childTokenId, serviceThread.getServiceName());

            serviceThread.routeToServiceNoRecord(route, val);
            branchNumber++;
        }

        logger.info("FORK: Parallel routing complete - " + joinCount + " child tokens created");
    }

    // ========================================================================
    // JOIN NODE PROCESSING
    // ========================================================================

    /**
     * Process JoinNode - Synchronizes parallel execution paths
     * 
     * FIXED: Now records T_out EXIT event for the reconstituted parent token.
     * NOTE: For JoinNode, we use getSequenceID() (not getPhaseSequenceID()) because
     * after join processing, sequenceID contains the reconstituted parent token ID,
     * while phaseSequenceID still contains the last-arrived child token ID.
     */
    void processJoinType(String service, String operation, ArrayList<?> sargs) {
        logger.info("ORCHESTRATOR: Processing JoinNode for " + service + "." + operation);

        ServiceHelper.ServiceResult serviceResult = serviceThread.callServiceWithCanonicalBinding(
                service, operation, sargs, serviceThread.getReturnAttributeName());
        String val = serviceResult.getResult();
        logger.info("ORCHESTRATOR: Service returned: " + (val != null ? val : "null"));

        if (val == null) {
            return;
        }

        int solutionIndex = 0;
        serviceThread.setAttributeValidity(serviceThread.getReturnAttributeName(), val);

        // PETRI NET: Record T_out EXIT event before routing
        // The reconstituted parent token exits this place to the next service
        ArrayList<String> nextServiceCollection = serviceThread.getNextServiceCollection();
        ArrayList<String> nextOperationCollection = serviceThread.getNextOperationCollection();
        TreeMap<Integer, String> decisionValueCollection = serviceThread.getDecisionValueCollection();
        if (!nextServiceCollection.isEmpty()) {
            String nextService = nextServiceCollection.get(solutionIndex);
            String nextOp = (nextOperationCollection != null && solutionIndex < nextOperationCollection.size()) 
                            ? nextOperationCollection.get(solutionIndex) : null;
            String arcValue = (decisionValueCollection != null) ? decisionValueCollection.get(solutionIndex) : null;
            // Use getSequenceID() - after join processing, this contains the reconstituted parent token ID
            // (phaseSequenceID still contains the last-arrived child token ID, which is incorrect here)
            serviceThread.instrumentTokenExit(serviceThread.getSequenceID(), serviceThread.getServiceName(),
                                              nextService, nextOp, "JoinNode", arcValue);
        }

        serviceThread.callNextOperation(val, solutionIndex, false);
    }

    // ========================================================================
    // EDGE NODE PROCESSING
    // ========================================================================

    /**
     * Process EdgeNode/MergeNode - Clean business logic only
     */
    void processEdgeType(String service, String operation, ArrayList<?> sargs) {
        logger.info("ORCHESTRATOR: Processing EdgeNode/MergeNode for " + service + "." + operation);

        ServiceHelper.ServiceResult serviceResult = serviceThread.callServiceWithCanonicalBinding(
                service, operation, sargs, serviceThread.getReturnAttributeName());
        String val = serviceResult.getResult();
        logger.info("ORCHESTRATOR: Service returned: " + (val != null ? val : "null"));

        int solutionIndex = 0;
        serviceThread.setAttributeValidity(serviceThread.getReturnAttributeName(), val);
        
        // PETRI NET: Record T_out EXIT event before routing
        ArrayList<String> nextServiceCollection = serviceThread.getNextServiceCollection();
        ArrayList<String> nextOperationCollection = serviceThread.getNextOperationCollection();
        TreeMap<Integer, String> decisionValueCollection = serviceThread.getDecisionValueCollection();
        if (!nextServiceCollection.isEmpty()) {
            String nextService = nextServiceCollection.get(solutionIndex);
            String nextOp = nextOperationCollection.get(solutionIndex);
            String arcValue = (decisionValueCollection != null) ? decisionValueCollection.get(solutionIndex) : null;
            // Use phaseSequenceID - the actual token that entered this place (could be forked child)
            serviceThread.instrumentTokenExit(serviceThread.getPhaseSequenceID(), serviceThread.getServiceName(),
                                              nextService, nextOp, serviceThread.getNodeType(), arcValue);
        }
        
        serviceThread.callNextOperation(val, solutionIndex, false);
    }

    // ========================================================================
    // TERMINATE NODE PROCESSING
    // ========================================================================

    /**
     * Process TerminateNode - Executes service then records T_out termination
     */
    void processTerminateType(String service, String operation, ArrayList<?> inputArgs2) {
        String val = null;
        try {
            logger.info("ORCHESTRATOR: Invoking " + service + "." + operation + " via ServiceHelper");
            String fullClassName = serviceThread.getServicePackage() + "." + service;

            if (serviceThread.getRuleBaseVersion() == null) {
                logger.error("CRITICAL: ruleBaseVersion is null - event missing version header");
                throw new RuntimeException("Cannot invoke service without ruleBaseVersion");
            }

            ServiceHelper.ServiceResult serviceResult = serviceThread.getServiceHelper().process(
                    serviceThread.getSequenceID().toString(),
                    fullClassName, operation, inputArgs2, 
                    serviceThread.getReturnAttributeName(), 
                    serviceThread.getRuleBaseVersion());
            val = serviceResult.getResult();
            logger.info("ORCHESTRATOR: Service returned: " + (val != null ? val : "null"));

            // PETRI NET: Record token termination
            serviceThread.instrumentTokenTerminate(serviceThread.getSequenceID(), serviceThread.getServiceName(), "TerminateNode");

            logger.info("ORCHESTRATOR: Token " + serviceThread.getSequenceID() + " terminated after " + service + "." + operation);

        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("Process Termination ->Failed to invoke service: " + service + ":" + operation);
        }
        logger.debug("Processing Terminate Type complete.");
    }

    // ========================================================================
    // FEED FORWARD NODE PROCESSING
    // ========================================================================

    /**
     * Process FeedFwdNode - Forward processing with sequence ID increment
     * 
     * FIXED: Now records T_out EXIT event before routing.
     */
    void processFeedFwdNode(String service, String operation, ArrayList<?> sargs) {
        logger.info("ORCHESTRATOR: Invoking " + service + "." + operation + " via ServiceHelper");
        String fullClassName = serviceThread.getServicePackage() + "." + service;

        if (serviceThread.getRuleBaseVersion() == null) {
            logger.error("CRITICAL: ruleBaseVersion is null - event missing version header");
            throw new RuntimeException("Cannot invoke service without ruleBaseVersion");
        }

        ServiceHelper.ServiceResult serviceResult = serviceThread.getServiceHelper().process(
                serviceThread.getSequenceID().toString(), fullClassName,
                operation, sargs, serviceThread.getReturnAttributeName(), 
                serviceThread.getRuleBaseVersion());
        String val = serviceResult.getResult();
        logger.info("ORCHESTRATOR: Service returned: " + (val != null ? val : "null"));
        if (val == null)
            return;
        
        int fid = 1;
        int solutionIndex = 0;
        int currentSequenceId = serviceThread.getSequenceID();
        int newSequenceId = currentSequenceId + fid;
        
        serviceThread.setAttributeValidity(serviceThread.getReturnAttributeName(), val);
        
        // PETRI NET: Record T_out EXIT event before routing
        ArrayList<String> nextServiceCollection = serviceThread.getNextServiceCollection();
        ArrayList<String> nextOperationCollection = serviceThread.getNextOperationCollection();
        if (!nextServiceCollection.isEmpty()) {
            String nextService = nextServiceCollection.get(solutionIndex);
            String nextOp = (nextOperationCollection != null && solutionIndex < nextOperationCollection.size()) 
                            ? nextOperationCollection.get(solutionIndex) : null;
            serviceThread.instrumentTokenExit(currentSequenceId, serviceThread.getServiceName(),
                                              nextService, nextOp, "FeedFwdNode", null);
        }
        
        serviceThread.getHeaderMap().put("sequenceId", Integer.toString(newSequenceId));
        serviceThread.callNextOperation(val, solutionIndex, false);
    }

    // ========================================================================
    // MONITOR NODE PROCESSING
    // ========================================================================

    void processMonitorNodeType(int sequenceID) {
        logger.info("ORCHESTRATOR: Monitor node - sequenceID = " + sequenceID);

        try {
            TreeMap<String, String> monitorDataMap = serviceThread.getMonitorDataMap();
            long processStartTime = Long.parseLong(monitorDataMap.get("processStartTime"));
            long eventArrivalTime = Long.parseLong(monitorDataMap.get("eventArrivalTime"));
            long currentTime = System.currentTimeMillis();

            long elapsedTime = currentTime - processStartTime;
            long totalProcessTime = currentTime - eventArrivalTime;

            logger.info("=== MONITOR NODE TIMING ===");
            logger.info("Sequence ID: " + sequenceID);
            logger.info("Process Start Time: " + processStartTime);
            logger.info("Event Arrival Time: " + eventArrivalTime);
            logger.info("Current Time: " + currentTime);
            logger.info("Elapsed Time: " + elapsedTime + "ms");
            logger.info("Total Process Time: " + totalProcessTime + "ms");
            logger.info("Lost Events: " + monitorDataMap.get("lostEvents"));
            logger.info("===========================");

        } catch (Exception e) {
            logger.error("Error processing monitor node for sequenceID: " + sequenceID, e);
        }
    }

    // ========================================================================
    // EXPIRED NODE PROCESSING
    // ========================================================================

    /**
     * Process ExpiredType - Handles expired tokens
     * 
     * FIXED: Now records T_out EXIT event before routing.
     */
    void processExpiredType(String service, String operation, ArrayList<?> sargs) {
        int solutionIndex = 0;
        
        // PETRI NET: Record T_out EXIT event for expired token
        ArrayList<String> nextServiceCollection = serviceThread.getNextServiceCollection();
        ArrayList<String> nextOperationCollection = serviceThread.getNextOperationCollection();
        if (!nextServiceCollection.isEmpty()) {
            String nextService = nextServiceCollection.get(solutionIndex);
            String nextOp = (nextOperationCollection != null && solutionIndex < nextOperationCollection.size()) 
                            ? nextOperationCollection.get(solutionIndex) : null;
            serviceThread.instrumentTokenExit(serviceThread.getPhaseSequenceID(), serviceThread.getServiceName(),
                                              nextService, nextOp, "ExpiredNode", null);
        }
        
        serviceThread.setAttributeValidity(serviceThread.getReturnAttributeName(), "null");
        serviceThread.callNextOperation("null", 0, false);
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    /**
     * Helper to record EXIT for decision node routing
     */
    private void instrumentDecisionExit(int solutionIndex, String attributeValue) {
        ArrayList<String> nextServiceCollection = serviceThread.getNextServiceCollection();
        ArrayList<String> nextOperationCollection = serviceThread.getNextOperationCollection();
        TreeMap<Integer, String> decisionValueCollection = serviceThread.getDecisionValueCollection();
        
        if (!nextServiceCollection.isEmpty() && solutionIndex < nextServiceCollection.size()) {
            String nextService = nextServiceCollection.get(solutionIndex);
            String nextOp = (nextOperationCollection != null && solutionIndex < nextOperationCollection.size()) 
                            ? nextOperationCollection.get(solutionIndex) : null;
            String arcValue = (decisionValueCollection != null) ? decisionValueCollection.get(solutionIndex) : null;
            serviceThread.instrumentTokenExit(serviceThread.getPhaseSequenceID(), serviceThread.getServiceName(),
                                              nextService, nextOp, "DecisionNode", arcValue);
        }
    }

    private String extractRoutingPath(JSONObject jsonObj) {
        try {
            if (jsonObj == null) {
                return null;
            }

            JSONObject routingDecision = findRoutingDecisionObject(jsonObj);

            if (routingDecision != null && routingDecision.containsKey("routing_path")) {
                String routingPath = routingDecision.get("routing_path").toString();
                logger.debug("DECISION: Found routing_path = '" + routingPath + "' in routing_decision");
                return routingPath;
            }

            logger.debug("DECISION: No routing_decision.routing_path found in JSON structure");
            return null;

        } catch (Exception e) {
            logger.error("DECISION: Error extracting routing_path from JSON: " + e.getMessage());
            return null;
        }
    }

    private JSONObject findRoutingDecisionObject(JSONObject jsonObj) {
        try {
            if (jsonObj == null) {
                return null;
            }

            if (jsonObj.containsKey("routing_decision")) {
                Object routingDecisionObj = jsonObj.get("routing_decision");
                if (routingDecisionObj instanceof JSONObject) {
                    logger.debug("DECISION: Found routing_decision object");
                    return (JSONObject) routingDecisionObj;
                }
            }

            for (Object key : jsonObj.keySet()) {
                Object value = jsonObj.get(key);

                if (value instanceof JSONObject) {
                    JSONObject nestedRoutingDecision = findRoutingDecisionObject((JSONObject) value);
                    if (nestedRoutingDecision != null) {
                        return nestedRoutingDecision;
                    }
                }
            }

            return null;

        } catch (Exception e) {
            logger.error("DECISION: Error searching for routing_decision object: " + e.getMessage());
            return null;
        }
    }

    private String extractRoutingDecision(ServiceHelper.ServiceResult serviceResult) {
        String val = serviceResult.getResult();

        if ("String".equals(serviceResult.getReturnType())) {
            JSONObject parsedValue = jsonLibrary.parseString(val);
            if (parsedValue != null) {
                String routingPath = extractRoutingPath(parsedValue);
                if (routingPath != null) {
                    return routingPath;
                }

                if (serviceThread.getReturnAttributeName() != null && 
                    parsedValue.containsKey(serviceThread.getReturnAttributeName())) {
                    return parsedValue.get(serviceThread.getReturnAttributeName()).toString();
                }
            }
        }

        return val;
    }

    /**
     * Evaluate a guard condition for XOR/Gateway routing
     */
    private boolean evaluateGuardCondition(String routingValue, String expectedValue, String conditionType) {
        if (routingValue == null) {
            return false;
        }

        if (expectedValue != null) {
            switch (conditionType) {
                case "DECISION_EQUAL_TO":
                    return serviceThread.guardsMatch(routingValue, expectedValue);

                case "DECISION_NOT_EQUAL":
                    return !serviceThread.guardsMatch(routingValue, expectedValue);

                case "DECISION_GREATER_THAN":
                    try {
                        return Double.parseDouble(routingValue) > Double.parseDouble(expectedValue);
                    } catch (NumberFormatException e) {
                        return routingValue.compareTo(expectedValue) > 0;
                    }

                case "DECISION_LESS_THAN":
                    try {
                        return Double.parseDouble(routingValue) < Double.parseDouble(expectedValue);
                    } catch (NumberFormatException e) {
                        return routingValue.compareTo(expectedValue) < 0;
                    }
            }
        }

        switch (conditionType) {
            case "DECISION_TRUE":
                return Boolean.parseBoolean(routingValue);

            case "DECISION_FALSE":
                return !Boolean.parseBoolean(routingValue);
        }

        logger.warn("XOR: Unknown condition type: " + conditionType);
        return false;
    }

    private String cleanQuotes(String value) {
        if (value != null && value.startsWith("\"") && value.endsWith("\"") && value.length() > 1) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}