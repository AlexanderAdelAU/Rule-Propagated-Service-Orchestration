package org.btsn.rulecontroller.model;

import java.util.*;

public class ServiceNode {
    public final String nodeId;
    public final String service;
    public final String operation;  // Primary operation
    public final Map<String, String> attributes;
    
    // NEW: Support for multiple operations
    private Set<String> allOperations;
    
    public ServiceNode(String nodeId, String service, String operation, Map<String, String> attributes) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId cannot be null");
        this.service = Objects.requireNonNull(service, "service cannot be null");
        this.operation = Objects.requireNonNull(operation, "operation cannot be null");
        // FIX: Handle null attributes safely
        this.attributes = Collections.unmodifiableMap(new HashMap<>(attributes != null ? attributes : new HashMap<>()));
        
        // Initialize with primary operation
        this.allOperations = new HashSet<>();
        this.allOperations.add(operation);
    }
    
    // === NEW: Multi-operation support ===
    
    /**
     * Add additional operations this service node can handle
     */
    public void addOperation(String operationName) {
        if (operationName != null && !operationName.trim().isEmpty()) {
            this.allOperations.add(operationName);
        }
    }
    
    /**
     * Set all operations this service node can handle
     */
    public void setAllOperations(Set<String> operations) {
        if (operations != null) {
            this.allOperations = new HashSet<>(operations);
            // Ensure primary operation is always included
            this.allOperations.add(this.operation);
        }
    }
    
    /**
     * Get all operations this service node can handle
     */
    public Set<String> getAllOperations() {
        return new HashSet<>(allOperations);
    }
    
    /**
     * Check if this service node supports a specific operation
     */
    public boolean supportsOperation(String operationName) {
        return allOperations.contains(operationName);
    }
    
    /**
     * Check if this is a multi-operation service
     */
    public boolean isMultiOperation() {
        return allOperations.size() > 1;
    }
    
    @Override
    public String toString() {
        if (isMultiOperation()) {
            return String.format("ServiceNode{id=%s, service=%s, operations=%s}", 
                               nodeId, service, allOperations);
        } else {
            return String.format("ServiceNode{id=%s, service=%s, operation=%s}", 
                               nodeId, service, operation);
        }
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ServiceNode that = (ServiceNode) o;
        return Objects.equals(nodeId, that.nodeId) &&
               Objects.equals(service, that.service) &&
               Objects.equals(operation, that.operation);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(nodeId, service, operation);
    }
}