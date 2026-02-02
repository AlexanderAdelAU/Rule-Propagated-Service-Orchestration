package org.btsn.base;

import org.btsn.utils.TimeStampUtils;

/**
 * Abstract base implementation of ServiceAssessment
 * Provides common functionality that most assessments will need
 */
public abstract class BaseServiceAssessment implements ServiceAssessment {
    
    protected String assessmentId;
    protected String completionTime;
    
    public BaseServiceAssessment() {
        this.completionTime = TimeStampUtils.getCurrentTimestamp();
    }
    
    public BaseServiceAssessment(String assessmentId) {
        this.assessmentId = assessmentId;
        this.completionTime = TimeStampUtils.getCurrentTimestamp();
    }
    
    @Override
    public String getAssessmentId() {
        return assessmentId;
    }
    
    public void setAssessmentId(String assessmentId) {
        this.assessmentId = assessmentId;
    }
    
    @Override
    public String getCompletionTime() {
        return completionTime;
    }
    
    public void setCompletionTime(String completionTime) {
        this.completionTime = completionTime;
    }
    
    /**
     * Helper method to clean JSON values
     */
    protected String cleanJsonValue(String value) {
        if (value == null) return "";
        return value.replace("\"", "'")
                   .replace("\\", "/")
                   .replace("\n", " ")
                   .replace("\r", " ")
                   .replace("\t", " ")
                   .trim();
    }
    
    /**
     * Helper method to build JSON field
     */
    protected String jsonField(String key, String value) {
        return String.format("\"%s\":\"%s\"", key, cleanJsonValue(value));
    }
    
    /**
     * Helper method to build JSON field with numeric value
     */
    protected String jsonField(String key, Number value) {
        return String.format("\"%s\":%s", key, value);
    }
    
    /**
     * Helper method to build JSON object field
     */
    protected String jsonObjectField(String key, String objectContent) {
        return String.format("\"%s\":{%s}", key, objectContent);
    }
    
    /**
     * Helper method to build JSON array field
     */
    protected String jsonArrayField(String key, String arrayContent) {
        return String.format("\"%s\":[%s]", key, arrayContent);
    }
}