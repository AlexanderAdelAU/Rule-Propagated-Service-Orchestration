package org.btsn.rulecontroller.model;

import java.util.*;

public class WorkflowEdge {
    public final String fromNode;
    public final String toNode;
    public final Map<String, String> attributes;
    
    public WorkflowEdge(String fromNode, String toNode, Map<String, String> attributes) {
        this.fromNode = Objects.requireNonNull(fromNode, "fromNode cannot be null");
        this.toNode = Objects.requireNonNull(toNode, "toNode cannot be null");
        this.attributes = Collections.unmodifiableMap(new HashMap<>(attributes));
    }
    
    // === DECISION DETECTION ===
    
    /**
     * Check if this edge represents a decision routing path
     * @return true if edge has both condition and decision_value attributes
     */
    public boolean isDecisionEdge() {
        return hasAttribute("condition") && hasAttribute("decision_value");
    }
    
    /**
     * Get the decision condition type
     * @return "DECISION_EQUAL", "DECISION_NOT_EQUAL", etc., or null if not set
     */
    public String getCondition() {
        return getAttribute("condition");
    }
    
    /**
     * Get the decision value to compare against
     * @return the decision value (e.g., "DIRECT_TO_TREATMENT"), or null if not set
     */
    public String getDecisionValue() {
        return getAttribute("decision_value");
    }
    
    // === TARGET SERVICE INFO ===
    
    /**
     * Get the target service name for this edge
     * @return target service name (e.g., "TreatmentService"), or null if not set
     */
    public String getTargetService() {
        return getAttribute("target_service");
    }
    
    /**
     * Get the target operation name for this edge
     * @return target operation name (e.g., "executeDirectTreatment"), or null if not set
     */
    public String getTargetOperation() {
        return getAttribute("target_operation");
    }
    
    /**
     * Check if this edge has explicit target service information
     * @return true if both target_service and target_operation are specified
     */
    public boolean hasTargetService() {
        return hasAttribute("target_service") && hasAttribute("target_operation");
    }
    
    // === DECISION TYPE CLASSIFICATION ===
    
    /**
     * Check if this is a positive decision path (condition matches)
     * @return true if condition is "DECISION_EQUAL"
     */
    public boolean isPositiveDecisionPath() {
        return "DECISION_EQUAL".equals(getCondition());
    }
    
    /**
     * Check if this is a negative decision path (condition doesn't match)
     * @return true if condition is "DECISION_NOT_EQUAL"
     */
    public boolean isNegativeDecisionPath() {
        return "DECISION_NOT_EQUAL".equals(getCondition());
    }
    
    // === EDGE LABELING ===
    
    /**
     * Get the edge label for visualization
     * @return the label attribute, or null if not set
     */
    public String getLabel() {
        return getAttribute("label");
    }
    
    // === HELPER METHODS ===
    
    /**
     * Check if the edge has a non-null attribute
     * @param key the attribute key to check
     * @return true if attribute exists and is not null
     */
    private boolean hasAttribute(String key) {
        return attributes.containsKey(key) && attributes.get(key) != null;
    }
    
    /**
     * Get an attribute value safely
     * @param key the attribute key
     * @return the attribute value, or null if not found
     */
    public String getAttribute(String key) {
        return attributes.get(key);
    }
    
    /**
     * Get all attributes for debugging
     * @return read-only view of all attributes
     */
    public Map<String, String> getAllAttributes() {
        return attributes; // Already unmodifiable
    }
    
    @Override
    public String toString() {
        if (isDecisionEdge()) {
            return String.format("WorkflowEdge{%s -> %s, condition=%s, decision_value=%s}", 
                fromNode, toNode, getCondition(), getDecisionValue());
        } else {
            return String.format("WorkflowEdge{%s -> %s}", fromNode, toNode);
        }
    }
}