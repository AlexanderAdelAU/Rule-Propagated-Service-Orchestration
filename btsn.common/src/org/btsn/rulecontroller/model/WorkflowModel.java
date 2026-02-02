package org.btsn.rulecontroller.model;

import java.util.*;
import org.apache.log4j.Logger;

public class WorkflowModel {
    private static final Logger logger = Logger.getLogger(WorkflowModel.class);
    
    private final Map<String, ServiceNode> serviceNodes = new HashMap<>();
    private final Map<String, TransitionNode> transitionNodes = new HashMap<>();
    private final List<WorkflowEdge> workflowEdges = new ArrayList<>();
    
    // === SERVICE NODE MANAGEMENT ===
    
    public void addServiceNode(ServiceNode serviceNode) {
        serviceNodes.put(serviceNode.nodeId, serviceNode);
    }
    
    public Map<String, ServiceNode> getServiceNodes() {
        return Collections.unmodifiableMap(serviceNodes);
    }
    
    /**
     * Get individual service node by ID
     */
    public ServiceNode getServiceNode(String nodeId) {
        return serviceNodes.get(nodeId);
    }
    
    // === TRANSITION NODE MANAGEMENT ===
    
    public void addTransitionNode(TransitionNode transitionNode) {
        transitionNodes.put(transitionNode.nodeId, transitionNode);
    }
    
    public Map<String, TransitionNode> getTransitionNodes() {
        return Collections.unmodifiableMap(transitionNodes);
    }
    
    /**
     * Get individual transition node by ID
     */
    public TransitionNode getTransitionNode(String nodeId) {
        return transitionNodes.get(nodeId);
    }
    
    // === WORKFLOW EDGE MANAGEMENT ===
    
    public void addWorkflowEdge(WorkflowEdge workflowEdge) {
        workflowEdges.add(workflowEdge);
    }
    
    public List<WorkflowEdge> getWorkflowEdges() {
        return Collections.unmodifiableList(workflowEdges);
    }
    
    // === ASSOCIATION LOGIC - FIXED ===
    
    /**
     * FIXED: Find transitions that come BEFORE a service (what triggers it)
     * This determines the NodeType that should be written to the rule
     * 
     * For P_Diagnosis, this finds T_in_Diagnosis (JoinNode), not T_out_Diagnosis (EdgeNode)
     */
    public List<TransitionNode> findIncomingTransitions(ServiceNode serviceNode) {
        List<TransitionNode> incomingTransitions = new ArrayList<>();
        
        logger.info("Finding INCOMING transitions for service: " + serviceNode.nodeId);
        
        // Find edges TO this service FROM transition nodes
        for (WorkflowEdge edge : workflowEdges) {
            if (edge.toNode.equals(serviceNode.nodeId)) {
                TransitionNode sourceTransition = transitionNodes.get(edge.fromNode);
                if (sourceTransition != null) {
                    incomingTransitions.add(sourceTransition);
                    logger.info("Found incoming transition: " + sourceTransition.nodeId + 
                               " → " + serviceNode.nodeId + " (" + sourceTransition.nodeType + ")");
                } else {
                    logger.debug("Edge " + edge.fromNode + " → " + serviceNode.nodeId + 
                               " - source is not a transition node");
                }
            }
        }
        
        logger.info("Service " + serviceNode.nodeId + " has " + 
                    incomingTransitions.size() + " incoming transitions");
        
        return incomingTransitions;
    }
    
    /**
     * FIXED: Find transitions that come AFTER a service (where results go)
     * This is used to determine downstream routing (meetsCondition rules)
     * 
     * For P_Diagnosis, this finds T_out_Diagnosis (EdgeNode)
     */
    public List<TransitionNode> findOutgoingTransitions(ServiceNode serviceNode) {
        List<TransitionNode> outgoingTransitions = new ArrayList<>();
        
        logger.info("Finding OUTGOING transitions for service: " + serviceNode.nodeId);
        
        // Find edges FROM this service TO transition nodes
        for (WorkflowEdge edge : workflowEdges) {
            if (edge.fromNode.equals(serviceNode.nodeId)) {
                TransitionNode targetTransition = transitionNodes.get(edge.toNode);
                if (targetTransition != null) {
                    outgoingTransitions.add(targetTransition);
                    logger.info("Found outgoing transition: " + serviceNode.nodeId + 
                               " → " + targetTransition.nodeId + " (" + targetTransition.nodeType + ")");
                } else {
                    logger.debug("Edge " + serviceNode.nodeId + " → " + edge.toNode + 
                               " - target is not a transition node");
                }
            }
        }
        
        logger.info("Service " + serviceNode.nodeId + " has " + 
                    outgoingTransitions.size() + " outgoing transitions");
        
        return outgoingTransitions;
    }
    
    /**
     * DEPRECATED: Use findIncomingTransitions() and findOutgoingTransitions() instead
     * Kept for backward compatibility but should not be used for rule generation
     */
    @Deprecated
    public List<TransitionNode> findAssociatedTransitions(ServiceNode serviceNode) {
        logger.warn("DEPRECATED: findAssociatedTransitions() called for " + serviceNode.nodeId + 
                   " - this only returns OUTGOING transitions. Use findIncomingTransitions() for NodeType determination.");
        return findOutgoingTransitions(serviceNode);
    }
    
    /**
     * Find destination services from a transition node
     */
    public List<ServiceNode> findDestinationServices(ServiceNode sourceService, TransitionNode transition) {
        List<ServiceNode> destinationServices = new ArrayList<>();
        
        logger.debug("Finding destination services from transition: " + transition.nodeId + " (" + transition.nodeType + ")");
        
        // Special handling for DecisionNode
        if ("DecisionNode".equals(transition.nodeType)) {
            return findDecisionDestinationServices(transition);
        }
        
        // Standard handling - find services reachable from this transition
        for (WorkflowEdge edge : workflowEdges) {
            if (edge.fromNode.equals(transition.nodeId)) {
                ServiceNode targetService = findReachableService(edge.toNode);
                if (targetService != null) {
                    destinationServices.add(targetService);
                    logger.debug("Found destination service: " + targetService.nodeId + " via " + edge.toNode);
                }
            }
        }
        
        return destinationServices;
    }
    
    /**
     * Find destination services for DecisionNode via decision edges
     */
    private List<ServiceNode> findDecisionDestinationServices(TransitionNode decisionNode) {
        List<ServiceNode> destinationServices = new ArrayList<>();
        
        logger.debug("Finding decision destination services for: " + decisionNode.nodeId);
        
        for (WorkflowEdge edge : workflowEdges) {
            if (edge.fromNode.equals(decisionNode.nodeId) && edge.isDecisionEdge()) {
                
                ServiceNode targetService = null;
                
                // Check for explicit target service in edge attributes
                if (edge.hasTargetService()) {
                    targetService = new ServiceNode(
                        edge.getTargetService() + "_" + edge.getTargetOperation(),
                        edge.getTargetService(),
                        edge.getTargetOperation(),
                        new HashMap<>()
                    );
                    logger.debug("Found explicit target service from edge: " + targetService.service + ":" + targetService.operation);
                } else {
                    targetService = findReachableService(edge.toNode);
                }
                
                if (targetService != null) {
                    destinationServices.add(targetService);
                    logger.debug("Added decision destination: " + targetService.service + ":" + targetService.operation + 
                               " via " + edge.getCondition() + " " + edge.getDecisionValue());
                } else if ("END".equals(edge.toNode)) {
                    logger.debug("Found termination edge from " + decisionNode.nodeId + " to END");
                }
            }
        }
        
        return destinationServices;
    }
    
    /**
     * Find a service reachable from a given node (traverse intermediate transitions)
     */
    private ServiceNode findReachableService(String startNode) {
        // Direct service node
        ServiceNode directService = serviceNodes.get(startNode);
        if (directService != null) {
            return directService;
        }
        
        // Traverse through intermediate transition nodes
        TransitionNode intermediateTransition = transitionNodes.get(startNode);
        if (intermediateTransition != null) {
            // NEW: Check if this is a TerminateNode - return synthetic TERMINATE service
            if ("TerminateNode".equals(intermediateTransition.nodeType)) {
                logger.info("Found TerminateNode: " + startNode + " - returning synthetic TERMINATE service");
                return new ServiceNode("TERMINATE", "TERMINATE", "TERMINATE", new HashMap<>());
            }
            
            for (WorkflowEdge edge : workflowEdges) {
                if (edge.fromNode.equals(startNode)) {
                    ServiceNode reachableService = findReachableService(edge.toNode);
                    if (reachableService != null) {
                        return reachableService;
                    }
                }
            }
        }
        
        return null; // Not found
    }
    
    // === UTILITY METHODS ===
    
    public List<TransitionNode> findStandaloneMonitorNodes() {
        List<TransitionNode> standaloneMonitorNodes = new ArrayList<>();
        
        for (TransitionNode transitionNode : transitionNodes.values()) {
            if ("MonitorNode".equals(transitionNode.nodeType)) {
                boolean hasCorrespondingService = serviceNodes.values().stream()
                    .anyMatch(service -> service.service.equals("MonitorService"));
                
                if (!hasCorrespondingService) {
                    standaloneMonitorNodes.add(transitionNode);
                }
            }
        }
        
        return standaloneMonitorNodes;
    }
    
    public boolean hasEdge(String fromNode, String toNode) {
        return workflowEdges.stream()
            .anyMatch(edge -> edge.fromNode.equals(fromNode) && edge.toNode.equals(toNode));
    }
    
    public boolean nodeExists(String nodeId) {
        return serviceNodes.containsKey(nodeId) || transitionNodes.containsKey(nodeId);
    }
    
    public Map<String, List<String>> buildAdjacencyList() {
        Map<String, List<String>> adjacencyList = new HashMap<>();
        
        for (WorkflowEdge edge : workflowEdges) {
            adjacencyList.computeIfAbsent(edge.fromNode, k -> new ArrayList<>()).add(edge.toNode);
        }
        
        return adjacencyList;
    }
    
    // === DIAGNOSTIC METHODS ===
    
    public void debugWorkflowEdges() {
        logger.info("=== WORKFLOW EDGES DEBUG ===");
        logger.info("Total edges: " + workflowEdges.size());
        
        for (WorkflowEdge edge : workflowEdges) {
            logger.info("Edge: " + edge.fromNode + " → " + edge.toNode);
            if (edge.isDecisionEdge()) {
                logger.info("  Decision Edge: " + edge.getCondition() + " = " + edge.getDecisionValue());
            }
            if (edge.hasTargetService()) {
                logger.info("  Target Service: " + edge.getTargetService() + ":" + edge.getTargetOperation());
            }
        }
        
        logger.info("=== SERVICE NODES ===");
        for (ServiceNode service : serviceNodes.values()) {
            logger.info("Service: " + service.nodeId + " (" + service.service + ":" + service.operation + ")");
        }
        
        logger.info("=== TRANSITION NODES ===");  
        for (TransitionNode transition : transitionNodes.values()) {
            logger.info("Transition: " + transition.nodeId + " (" + transition.nodeType + ")");
        }
        
        logger.info("=== END DEBUG ===");
    }
}