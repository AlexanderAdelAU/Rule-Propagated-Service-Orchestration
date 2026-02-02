package org.btsn.rulecontroller.validation;

import java.util.Objects;

public class ValidationError {
    public final String type;
    public final String message;
    public final String nodeId;
    public final String context;
    
    public ValidationError(String type, String message, String nodeId, String context) {
        this.type = Objects.requireNonNull(type, "type cannot be null");
        this.message = Objects.requireNonNull(message, "message cannot be null");
        this.nodeId = nodeId; // Can be null
        this.context = context; // Can be null
    }
    
    @Override
    public String toString() {
        return String.format("[Node %s] %s: %s (Context: %s)", 
                           nodeId != null ? nodeId : "UNKNOWN", type, message, 
                           context != null ? context : "N/A");
    }
}