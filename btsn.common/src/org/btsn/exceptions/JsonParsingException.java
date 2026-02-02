package org.btsn.exceptions;

/**
 * Specific exception for JSON parsing and formatting errors
 * Extends ServiceProcessingException to provide more specific error handling
 */
public class JsonParsingException extends ServiceProcessingException {
    
    private final String invalidJson;
    private final int errorPosition;
    private final String expectedFormat;
    
    public JsonParsingException(String message) {
        super(message, null, null, "JSON_PARSE_ERROR");
        this.invalidJson = null;
        this.errorPosition = -1;
        this.expectedFormat = null;
    }
    
    public JsonParsingException(String message, Throwable cause) {
        super(message, cause, null, null, "JSON_PARSE_ERROR");
        this.invalidJson = null;
        this.errorPosition = -1;
        this.expectedFormat = null;
    }
    
    public JsonParsingException(String message, String invalidJson) {
        super(message, null, null, "JSON_PARSE_ERROR");
        this.invalidJson = invalidJson;
        this.errorPosition = -1;
        this.expectedFormat = null;
    }
    
    public JsonParsingException(String message, String invalidJson, int errorPosition) {
        super(message, null, null, "JSON_PARSE_ERROR");
        this.invalidJson = invalidJson;
        this.errorPosition = errorPosition;
        this.expectedFormat = null;
    }
    
    public JsonParsingException(String message, String invalidJson, String expectedFormat) {
        super(message, null, null, "JSON_PARSE_ERROR");
        this.invalidJson = invalidJson;
        this.errorPosition = -1;
        this.expectedFormat = expectedFormat;
    }
    
    public JsonParsingException(String message, Throwable cause, String serviceType, String sequenceId, 
                              String invalidJson, int errorPosition) {
        super(message, cause, serviceType, sequenceId, "JSON_PARSE_ERROR");
        this.invalidJson = invalidJson;
        this.errorPosition = errorPosition;
        this.expectedFormat = null;
    }
    
    public String getInvalidJson() { 
        return invalidJson; 
    }
    
    public int getErrorPosition() { 
        return errorPosition; 
    }
    
    public String getExpectedFormat() { 
        return expectedFormat; 
    }
    
    /**
     * Get a snippet around the error position for debugging
     */
    public String getJsonSnippet() {
        if (invalidJson == null || errorPosition < 0) {
            return null;
        }
        
        int start = Math.max(0, errorPosition - 50);
        int end = Math.min(invalidJson.length(), errorPosition + 50);
        
        StringBuilder snippet = new StringBuilder();
        if (start > 0) snippet.append("...");
        snippet.append(invalidJson, start, end);
        if (end < invalidJson.length()) snippet.append("...");
        
        return snippet.toString();
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("JsonParsingException");
        
        if (getServiceType() != null) {
            sb.append(" [").append(getServiceType());
            if (getSequenceId() != null) {
                sb.append(":").append(getSequenceId());
            }
            sb.append("]");
        }
        
        sb.append(": ").append(getMessage());
        
        if (errorPosition >= 0) {
            sb.append(" at position ").append(errorPosition);
        }
        
        if (expectedFormat != null) {
            sb.append(" (expected format: ").append(expectedFormat).append(")");
        }
        
        return sb.toString();
    }
    
    /**
     * Create a detailed error message for logging/debugging
     */
    public String getDetailedErrorMessage() {
        StringBuilder sb = new StringBuilder();
        sb.append("JSON Parsing Error: ").append(getMessage()).append("\n");
        
        if (errorPosition >= 0) {
            sb.append("Error Position: ").append(errorPosition).append("\n");
        }
        
        if (expectedFormat != null) {
            sb.append("Expected Format: ").append(expectedFormat).append("\n");
        }
        
        String snippet = getJsonSnippet();
        if (snippet != null) {
            sb.append("JSON Snippet: ").append(snippet).append("\n");
        }
        
        if (getCause() != null) {
            sb.append("Underlying Cause: ").append(getCause().getMessage());
        }
        
        return sb.toString();
    }
}