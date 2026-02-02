package org.btsn.base;
/**
 * Base interface that all service assessment results must implement
 */
public interface ServiceAssessment {
    
    /**
     * Get the unique assessment ID
     */
    String getAssessmentId();
    
    /**
     * Get the completion timestamp
     */
    String getCompletionTime();
    
    /**
     * Convert service-specific assessment data to JSON fields
     * This method should return the middle part of the JSON (without outer braces)
     * Example: "field1":"value1","field2":{"nested":"data"}
     */
    String toJsonFields();
    
    /**
     * Get a brief summary of the assessment for logging
     */
    String getSummary();
}