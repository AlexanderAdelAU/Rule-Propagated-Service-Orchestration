package org.btsn.json;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

/**
 * JSON validation utilities
 */
public class JsonValidator {
    
    private static final JSONParser parser = new JSONParser();
    
    /**
     * Validate that a string is valid JSON
     */
    public static boolean isValidJson(String jsonString) {
        if (jsonString == null || jsonString.trim().isEmpty()) {
            return false;
        }
        
        try {
            parser.parse(jsonString);
            return true;
        } catch (ParseException e) {
            return false;
        }
    }
    
    /**
     * Validate and parse JSON, returning null if invalid
     */
    public static JSONObject parseJsonSafely(String jsonString) {
        try {
            return (JSONObject) parser.parse(jsonString);
        } catch (ParseException | ClassCastException e) {
            return null;
        }
    }
    
    /**
     * Get detailed validation error for debugging
     */
    public static String getValidationError(String jsonString) {
        try {
            parser.parse(jsonString);
            return "Valid JSON";
        } catch (ParseException e) {
            return String.format("JSON Parse Error at position %d: %s", 
                e.getPosition(), e.getMessage());
        }
    }
    
    /**
     * Validate that JSON contains required fields
     */
    public static boolean hasRequiredFields(String jsonString, String... requiredFields) {
        JSONObject json = parseJsonSafely(jsonString);
        if (json == null) {
            return false;
        }
        
        for (String field : requiredFields) {
            if (!json.containsKey(field)) {
                return false;
            }
        }
        
        return true;
    }
}