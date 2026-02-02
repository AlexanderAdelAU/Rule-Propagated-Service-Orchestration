package org.btsn.rulecontroller.validation;

import java.util.*;
import org.apache.log4j.Logger;

public class ValidationResult {
    private static final Logger logger = Logger.getLogger(ValidationResult.class);
    
    private final List<ValidationError> errors = new ArrayList<>();
    
    public static class ValidationError {
        public final String type;
        public final String message;
        public final String nodeId;
        public final String context;
        
        public ValidationError(String type, String message, String nodeId, String context) {
            this.type = type;
            this.message = message;
            this.nodeId = nodeId;
            this.context = context;
        }
        
        @Override
        public String toString() {
            return String.format("[Node %s] %s: %s (Context: %s)", nodeId, type, message, context);
        }
    }
    
    public void addError(String type, String message, String nodeId, String context) {
        errors.add(new ValidationError(type, message, nodeId, context));
    }
    
    public boolean hasErrors() {
        return !errors.isEmpty();
    }
    
    public int getErrorCount() {
        return errors.size();
    }
    
    public void reportErrors() {
        if (errors.isEmpty()) {
            return;
        }
        
        logger.error("=== DOT WORKFLOW VALIDATION ERRORS ===");
        logger.error("Found " + errors.size() + " validation errors:");
        
        // Group errors by type
        Map<String, List<ValidationError>> errorsByType = new HashMap<>();
        for (ValidationError error : errors) {
            errorsByType.computeIfAbsent(error.type, k -> new ArrayList<>()).add(error);
        }
        
        // Report each type
        for (Map.Entry<String, List<ValidationError>> entry : errorsByType.entrySet()) {
            String errorType = entry.getKey();
            List<ValidationError> typeErrors = entry.getValue();
            
            logger.error("\n--- " + errorType + " (" + typeErrors.size() + " errors) ---");
            for (ValidationError error : typeErrors) {
                logger.error("  " + error.toString());
            }
        }
        
        logger.error("=== END DOT WORKFLOW VALIDATION ERRORS ===");
    }
    
    public void clear() {
        errors.clear();
    }
}