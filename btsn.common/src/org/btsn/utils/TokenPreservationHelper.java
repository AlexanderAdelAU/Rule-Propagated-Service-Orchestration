package org.btsn.utils;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.apache.log4j.Logger;
import org.btsn.json.jsonLibrary;

/**
 * TokenPreservationHelper - Common utility for preserving original_token across all services
 * UPDATED: Now stores original_token as a clean JSON object instead of escaped string
 */
public class TokenPreservationHelper {
    
    private static final Logger logger = Logger.getLogger(TokenPreservationHelper.class);
    private static final JSONParser parser = new JSONParser();
    
    /**
     * Extract original_token from incoming data
     * Returns the raw JSON string (for internal use)
     */
    public static String extractOriginalToken(String inputData) {
        if (inputData == null || !inputData.contains("original_token")) {
            return null;
        }
        
        try {
            // First try to parse as JSON to extract properly
            JSONObject jsonData = (JSONObject) parser.parse(inputData);
            
            // Look for original_token in the results objects
            for (Object key : jsonData.keySet()) {
                Object value = jsonData.get(key);
                if (value instanceof JSONObject) {
                    JSONObject resultObj = (JSONObject) value;
                    if (resultObj.containsKey("original_token")) {
                        Object tokenObj = resultObj.get("original_token");
                        // If it's already a JSONObject, convert to string
                        if (tokenObj instanceof JSONObject) {
                            return ((JSONObject) tokenObj).toJSONString();
                        }
                        // If it's a string, return as-is
                        return tokenObj.toString();
                    }
                }
            }
            
            // Fallback to string parsing if needed
            String pattern = "\"original_token\":";
            int startIndex = inputData.indexOf(pattern);
            if (startIndex >= 0) {
                startIndex += pattern.length();
                // Skip whitespace
                while (startIndex < inputData.length() && Character.isWhitespace(inputData.charAt(startIndex))) {
                    startIndex++;
                }
                
                // Check if it starts with { (JSON object) or " (string)
                if (inputData.charAt(startIndex) == '{') {
                    // It's a JSON object, find the matching }
                    int braceCount = 1;
                    int endIndex = startIndex + 1;
                    while (endIndex < inputData.length() && braceCount > 0) {
                        char c = inputData.charAt(endIndex);
                        if (c == '{') braceCount++;
                        else if (c == '}') braceCount--;
                        endIndex++;
                    }
                    return inputData.substring(startIndex, endIndex);
                } else if (inputData.charAt(startIndex) == '"') {
                    // It's a string, find the closing quote
                    startIndex++; // Skip the opening quote
                    int endIndex = startIndex;
                    while (endIndex < inputData.length()) {
                        if (inputData.charAt(endIndex) == '"' && inputData.charAt(endIndex - 1) != '\\') {
                            break;
                        }
                        endIndex++;
                    }
                    return inputData.substring(startIndex, endIndex);
                }
            }
        } catch (Exception e) {
            logger.error("Error extracting original_token: " + e.getMessage());
        }
        return null;
    }
    
    /**
     * Extract workflow_start_time from incoming data
     */
    public static Long extractWorkflowStartTime(String inputData) {
        if (inputData == null || !inputData.contains("workflow_start_time")) {
            return null;
        }
        
        try {
            // First try JSON parsing
            JSONObject jsonData = (JSONObject) parser.parse(inputData);
            
            // Check in the original_token first
            for (Object key : jsonData.keySet()) {
                Object value = jsonData.get(key);
                if (value instanceof JSONObject) {
                    JSONObject resultObj = (JSONObject) value;
                    
                    // Check if workflow_start_time is directly in the results
                    if (resultObj.containsKey("workflow_start_time")) {
                        Object timeObj = resultObj.get("workflow_start_time");
                        return Long.parseLong(timeObj.toString());
                    }
                    
                    // Check if it's in the original_token
                    if (resultObj.containsKey("original_token")) {
                        Object tokenObj = resultObj.get("original_token");
                        if (tokenObj instanceof JSONObject) {
                            JSONObject originalToken = (JSONObject) tokenObj;
                            if (originalToken.containsKey("workflow_start_time")) {
                                return Long.parseLong(originalToken.get("workflow_start_time").toString());
                            }
                        }
                    }
                }
            }
            
            // Also check if this IS the original token itself (first service)
            if (jsonData.containsKey("workflow_start_time")) {
                return Long.parseLong(jsonData.get("workflow_start_time").toString());
            }
            
            // Fallback to string parsing
            String pattern = "\"workflow_start_time\":";
            int startIndex = inputData.indexOf(pattern);
            if (startIndex >= 0) {
                startIndex += pattern.length();
                // Skip whitespace
                while (startIndex < inputData.length() && Character.isWhitespace(inputData.charAt(startIndex))) {
                    startIndex++;
                }
                // Skip quotes if present
                if (inputData.charAt(startIndex) == '"') {
                    startIndex++;
                }
                // Find the end of the number
                int endIndex = startIndex;
                while (endIndex < inputData.length() && 
                       (Character.isDigit(inputData.charAt(endIndex)) || inputData.charAt(endIndex) == '.')) {
                    endIndex++;
                }
                if (endIndex > startIndex) {
                    String timeStr = inputData.substring(startIndex, endIndex);
                    return Long.parseLong(timeStr);
                }
            }
        } catch (Exception e) {
            logger.error("Error extracting workflow_start_time: " + e.getMessage());
        }
        return null;
    }
    
    /**
     * Preserve original_token and timing in service results
     * This is the main method that all services should call
     * 
     * @param inputData The incoming token data (from previous service or EventGenerator)
     * @param serviceResult The result JSON string from current service processing
     * @param serviceResultsKey The key for this service's results (e.g., "triageResults", "radiologyResults")
     * @param serviceName The name of the calling service for logging
     * @return Updated result with original_token preserved at the top as a clean JSON object
     */
    public static String preserveTokenInResult(String inputData, String serviceResult, 
                                               String serviceResultsKey, String serviceName) {
        try {
            // Extract preserved fields from input
            String originalTokenStr = extractOriginalToken(inputData);
            Long workflowStartTime = extractWorkflowStartTime(inputData);
            
            if (originalTokenStr == null) {
                logger.warn(serviceName + ": No original_token found to preserve");
                return serviceResult;
            }
            
            // Parse the original token string into a JSON object for clean storage
            JSONObject originalTokenObj = null;
            try {
                originalTokenObj = (JSONObject) parser.parse(originalTokenStr);
            } catch (Exception e) {
                // If it's not valid JSON, store as a simple object with the value
                originalTokenObj = new JSONObject();
                originalTokenObj.put("token", originalTokenStr);
            }
            
            // Parse the service result
            JSONObject resultJson = jsonLibrary.parseString(serviceResult);
            if (resultJson == null || !resultJson.containsKey(serviceResultsKey)) {
                logger.error(serviceName + ": Invalid service result structure");
                return serviceResult;
            }
            
            Object serviceResultsObj = resultJson.get(serviceResultsKey);
            if (!(serviceResultsObj instanceof JSONObject)) {
                logger.error(serviceName + ": Service results not a JSON object");
                return serviceResult;
            }
            
            JSONObject serviceResults = (JSONObject) serviceResultsObj;
            
            // Create ordered results with original_token as a clean JSON object
            JSONObject orderedResults = new JSONObject();
            
            // Put original_token FIRST as a clean JSON object (no escaping!)
            orderedResults.put("original_token", originalTokenObj);
            logger.info(serviceName + ": Preserved original_token as clean JSON object");
            
            // Add workflow timing if available
            if (workflowStartTime != null) {
                orderedResults.put("workflow_start_time", workflowStartTime);
                
                // Calculate service processing time
                long currentTime = System.currentTimeMillis();
                long serviceProcessingTime = currentTime - workflowStartTime;
                
                orderedResults.put("service_start_time", workflowStartTime);
                orderedResults.put("service_end_time", currentTime);
                orderedResults.put("service_processing_time_ms", serviceProcessingTime);
                
                logger.info(serviceName + ": Added timing - processing time: " + serviceProcessingTime + "ms");
            }
            
            // Copy all other fields from original service results
            for (Object key : serviceResults.keySet()) {
                String keyStr = key.toString();
                // Skip if we already added it
                if (!keyStr.equals("original_token") && 
                    !keyStr.equals("workflow_start_time") &&
                    !keyStr.equals("service_start_time") &&
                    !keyStr.equals("service_end_time") &&
                    !keyStr.equals("service_processing_time_ms")) {
                    orderedResults.put(keyStr, serviceResults.get(keyStr));
                }
            }
            
            // Replace service results with the ordered version
            resultJson.put(serviceResultsKey, orderedResults);
            return resultJson.toJSONString();
            
        } catch (Exception e) {
            logger.error(serviceName + ": Error preserving token in result: " + e.getMessage(), e);
            return serviceResult; // Return unchanged if error
        }
    }
    
    /**
     * Extract JSON value by key (utility method)
     */
    public static String extractJsonValue(String json, String key) {
        try {
            String pattern = "\"" + key + "\":\"";
            int startIndex = json.indexOf(pattern);
            if (startIndex == -1) {
                // Try without quotes (for numbers/booleans)
                pattern = "\"" + key + "\":";
                startIndex = json.indexOf(pattern);
                if (startIndex == -1) {
                    return null;
                }
                startIndex += pattern.length();
                // Skip whitespace
                while (startIndex < json.length() && Character.isWhitespace(json.charAt(startIndex))) {
                    startIndex++;
                }
                // Find end of value
                int endIndex = startIndex;
                while (endIndex < json.length()) {
                    char c = json.charAt(endIndex);
                    if (c == ',' || c == '}' || c == ']') {
                        break;
                    }
                    endIndex++;
                }
                return json.substring(startIndex, endIndex).trim();
            }
            
            startIndex += pattern.length();
            int endIndex = json.indexOf("\"", startIndex);
            if (endIndex == -1) {
                return null;
            }
            
            return json.substring(startIndex, endIndex);
            
        } catch (Exception e) {
            logger.debug("Error extracting JSON value for key '" + key + "': " + e.getMessage());
            return null;
        }
    }
}