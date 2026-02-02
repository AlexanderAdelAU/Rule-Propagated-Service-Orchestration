package org.btsn.exceptions;

/**
 * Base exception for all healthcare service processing errors
 * FIXED VERSION - matches the constructors that JsonParsingException is calling
 */
public class ServiceProcessingException extends Exception {
    
    private final String serviceType;
    private final String sequenceId;
    private final String errorCode;
    
    // Constructor 1: JsonParsingException calls super(message, null, null, "JSON_PARSE_ERROR")
    public ServiceProcessingException(String message, String serviceType, String sequenceId, String errorCode) {
        super(message);
        this.serviceType = serviceType;
        this.sequenceId = sequenceId;
        this.errorCode = errorCode;
    }
    
    // Constructor 2: JsonParsingException calls super(message, cause, null, null, "JSON_PARSE_ERROR")  
    public ServiceProcessingException(String message, Throwable cause, String serviceType, String sequenceId, String errorCode) {
        super(message, cause);
        this.serviceType = serviceType;
        this.sequenceId = sequenceId;
        this.errorCode = errorCode;
    }
    
    // Constructor 3: Simple constructor
    public ServiceProcessingException(String message) {
        super(message);
        this.serviceType = null;
        this.sequenceId = null;
        this.errorCode = "GENERAL_ERROR";
    }
    
    // Constructor 4: Simple constructor with cause
    public ServiceProcessingException(String message, Throwable cause) {
        super(message, cause);
        this.serviceType = null;
        this.sequenceId = null;
        this.errorCode = "GENERAL_ERROR";
    }
    
    // Getters
    public String getServiceType() { 
        return serviceType; 
    }
    
    public String getSequenceId() { 
        return sequenceId; 
    }
    
    public String getErrorCode() { 
        return errorCode; 
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("ServiceProcessingException");
        if (serviceType != null) {
            sb.append(" [").append(serviceType);
            if (sequenceId != null) {
                sb.append(":").append(sequenceId);
            }
            if (errorCode != null) {
                sb.append(" - ").append(errorCode);
            }
            sb.append("]");
        }
        sb.append(": ").append(getMessage());
        return sb.toString();
    }
}
