package org.btsn.json;

import org.btsn.base.TokenInfo;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import java.util.HashMap;
import java.util.Map;

/**
 * Generic JSON Response Builder
 * 
 * Builds JSON responses for Petri Net place services.
 * Completely domain-agnostic - works with any place/token structure.
 * 
 * NEW: Routing Decision Support
 * =============================
 * Supports guard evaluation results for DecisionNode routing.
 * Use setRoutingDecision() to include routing_path in response.
 * 
 * Response Format:
 * ================
 * {
 *   "placeId": {
 *     "routing_decision": {
 *       "routing_path": "true",
 *       "guard_result": true,
 *       "probability": 0.5
 *     },
 *     "placeId": "P1",
 *     "sequenceId": "token_001",
 *     "status": "COMPLETED",
 *     "marking": 0,
 *     "executionTime": 125,
 *     "annotation": "{...}",
 *     "data": {
 *       "key1": "value1"
 *     }
 *   }
 * }
 * 
 * @author ACameron
 */
public class JsonResponseBuilder {
    
    private String placeId;
    private String sequenceId;
    private String status;
    private int marking;
    private long executionTime;
    private String annotation;
    private Map<String, String> data;
    
    // Token preservation fields
    private String tokenId;
    private String version;
    private long notAfter;
    private String currentPlace;
    
    // Healthcare-specific fields
    private String serviceType;
    private Object assessment;  // Generic - works with any assessment type
    
    // NEW: Assessment JSON fields (parsed from toJsonFields())
    private String assessmentJsonFields;
    
    // NEW: Routing decision fields for guard evaluation
    private JSONObject routingDecision;
    
    public JsonResponseBuilder() {
        this.data = new HashMap<>();
        this.status = "PENDING";
        this.marking = 0;
        this.executionTime = 0;
        this.routingDecision = null;
        this.assessmentJsonFields = null;
    }
    
    // ========== Builder Methods ==========
    
    public JsonResponseBuilder setPlaceId(String placeId) {
        this.placeId = placeId;
        return this;
    }
    
    public JsonResponseBuilder setSequenceId(String sequenceId) {
        this.sequenceId = sequenceId;
        return this;
    }
    
    public JsonResponseBuilder setStatus(String status) {
        this.status = status;
        return this;
    }
    
    public JsonResponseBuilder setMarking(int marking) {
        this.marking = marking;
        return this;
    }
    
    public JsonResponseBuilder setExecutionTime(long executionTime) {
        this.executionTime = executionTime;
        return this;
    }
    
    public JsonResponseBuilder setAnnotation(String annotation) {
        this.annotation = annotation;
        return this;
    }
    
    public JsonResponseBuilder setData(Map<String, String> data) {
        this.data = new HashMap<>(data);
        return this;
    }
    
    public JsonResponseBuilder addDataValue(String key, String value) {
        this.data.put(key, value);
        return this;
    }
    
    /**
     * Set the service type key for the response
     * e.g., "triageResults", "cardiologyResults", etc.
     */
    public JsonResponseBuilder setServiceType(String serviceType) {
        this.serviceType = serviceType;
        return this;
    }
    
    /**
     * Set the assessment results from a healthcare service
     * The assessment data will be included in the response
     * 
     * Accepts any assessment type - uses reflection to extract data
     * Handles (in order of priority):
     * 1. Objects with toJsonFields() method - returns JSON string fragment
     * 2. Objects with toMap() method - returns Map<String,String>
     * 3. Objects with getResult() method  
     * 4. Falls back to toString() for any object
     * 
     * IMPORTANT: If assessment has a getRoutingDecision() method that returns
     * an object with getRoutingPath(), the routing_decision will be automatically
     * extracted and added to the response for DecisionNode/GatewayNode routing.
     * 
     * @param assessment Any assessment object
     * @return this builder for chaining
     */
    @SuppressWarnings("unchecked")
    public JsonResponseBuilder setAssessment(Object assessment) {
        this.assessment = assessment;
        if (assessment == null) {
            return this;
        }
        
        // FIRST: Try to extract routing decision from assessment
        extractRoutingDecisionFromAssessment(assessment);
        
        // SECOND: Try to get assessment data for inclusion in response
        boolean dataExtracted = false;
        
        // Priority 1: Try toJsonFields() - returns JSON string fragment
        if (!dataExtracted) {
            try {
                java.lang.reflect.Method toJsonFieldsMethod = assessment.getClass().getMethod("toJsonFields");
                String jsonFields = (String) toJsonFieldsMethod.invoke(assessment);
                if (jsonFields != null && !jsonFields.isEmpty()) {
                    this.assessmentJsonFields = jsonFields;
                    dataExtracted = true;
                    System.out.println("[JsonResponseBuilder] Extracted assessment via toJsonFields()");
                }
            } catch (NoSuchMethodException e) {
                // toJsonFields() doesn't exist - try next method
            } catch (Exception e) {
                System.err.println("[JsonResponseBuilder] Error calling toJsonFields(): " + e.getMessage());
            }
        }
        
        // Priority 2: Try toMap() - returns Map<String,String>
        if (!dataExtracted) {
            try {
                java.lang.reflect.Method toMapMethod = assessment.getClass().getMethod("toMap");
                Map<String, String> assessmentData = (Map<String, String>) toMapMethod.invoke(assessment);
                if (assessmentData != null) {
                    this.data.putAll(assessmentData);
                    dataExtracted = true;
                    System.out.println("[JsonResponseBuilder] Extracted assessment via toMap()");
                }
            } catch (NoSuchMethodException e) {
                // toMap() doesn't exist - try next method
            } catch (Exception e) {
                System.err.println("[JsonResponseBuilder] Error calling toMap(): " + e.getMessage());
            }
        }
        
        // Priority 3: Try getResult()
        if (!dataExtracted) {
            try {
                java.lang.reflect.Method getResultMethod = assessment.getClass().getMethod("getResult");
                Object result = getResultMethod.invoke(assessment);
                if (result != null) {
                    this.data.put("assessmentResult", result.toString());
                    dataExtracted = true;
                    System.out.println("[JsonResponseBuilder] Extracted assessment via getResult()");
                }
            } catch (NoSuchMethodException e) {
                // getResult() doesn't exist - fall back to toString
            } catch (Exception e) {
                System.err.println("[JsonResponseBuilder] Error calling getResult(): " + e.getMessage());
            }
        }
        
        // Priority 4: Fall back to toString()
        if (!dataExtracted) {
            this.data.put("assessment", assessment.toString());
            System.out.println("[JsonResponseBuilder] WARNING: Fell back to toString() for assessment");
        }
        
        return this;
    }
    
    /**
     * Extract routing_decision from assessment if it has getRoutingDecision() method
     * 
     * Looks for:
     *   assessment.getRoutingDecision() -> routingDecision object
     *   routingDecision.getRoutingPath() or routingDecision.routingPath -> String
     *   routingDecision.isBypassDiagnostics() or routingDecision.bypassDiagnostics -> boolean
     */
    @SuppressWarnings("unchecked")
    private void extractRoutingDecisionFromAssessment(Object assessment) {
        try {
            // Try to get routing decision object
            java.lang.reflect.Method getRoutingDecisionMethod = assessment.getClass().getMethod("getRoutingDecision");
            Object routingDecisionObj = getRoutingDecisionMethod.invoke(assessment);
            
            if (routingDecisionObj == null) {
                return;
            }
            
            // Extract routing_path from the routing decision object
            String routingPath = null;
            
            // Try getRoutingPath() method first
            try {
                java.lang.reflect.Method getRoutingPathMethod = routingDecisionObj.getClass().getMethod("getRoutingPath");
                routingPath = (String) getRoutingPathMethod.invoke(routingDecisionObj);
            } catch (NoSuchMethodException e) {
                // Try public field access
                try {
                    java.lang.reflect.Field routingPathField = routingDecisionObj.getClass().getField("routingPath");
                    routingPath = (String) routingPathField.get(routingDecisionObj);
                } catch (NoSuchFieldException e2) {
                    // No routing_path found
                }
            }
            
            if (routingPath != null) {
                // Build routing_decision JSON object
                this.routingDecision = new JSONObject();
                this.routingDecision.put("routing_path", routingPath);
                
                // Try to extract additional fields (bypass_diagnostics, rationale, etc.)
                try {
                    java.lang.reflect.Field bypassField = routingDecisionObj.getClass().getField("bypassDiagnostics");
                    boolean bypass = bypassField.getBoolean(routingDecisionObj);
                    this.routingDecision.put("bypass_diagnostics", bypass);
                    this.routingDecision.put("guard_result", bypass);
                } catch (Exception e) {
                    // Optional field - ignore if not present
                }
                
                try {
                    java.lang.reflect.Field rationaleField = routingDecisionObj.getClass().getField("rationale");
                    String rationale = (String) rationaleField.get(routingDecisionObj);
                    if (rationale != null) {
                        this.routingDecision.put("rationale", rationale);
                    }
                } catch (Exception e) {
                    // Optional field - ignore if not present
                }
                
                System.out.println("[JsonResponseBuilder] Extracted routing_decision: routing_path=" + routingPath);
            }
            
        } catch (NoSuchMethodException e) {
            // Assessment doesn't have getRoutingDecision() - that's OK
        } catch (Exception e) {
            System.err.println("[JsonResponseBuilder] Error extracting routing decision: " + e.getMessage());
        }
    }
    
    /**
     * Preserve token information - including workflow_start_time, version, notAfter, etc.
     * This ensures token fields flow through the entire Petri Net workflow
     */
    public JsonResponseBuilder setTokenInfo(TokenInfo tokenInfo) {
        if (tokenInfo != null) {
            this.tokenId = tokenInfo.getTokenId();
            this.version = tokenInfo.getVersion();
            this.notAfter = tokenInfo.getNotAfter();
            this.currentPlace = tokenInfo.getCurrentPlace();
            
            // Preserve all data including workflow_start_time
            if (tokenInfo.getData() != null) {
                this.data.putAll(tokenInfo.getData());
            }
        }
        return this;
    }
    
    /**
     * NEW: Set routing decision for guard evaluation result
     * 
     * Creates a routing_decision object in the response that DecisionNode
     * can use for conditional routing based on guard evaluation.
     * 
     * The routing_path value ("true" or "false") is extracted by ServiceThread's
     * extractRoutingPath() method and used for decision routing rules.
     * 
     * @param routingPath "true" or "false" string for decision routing
     * @param guardResult Boolean result of guard evaluation
     * @param probability Probability used (for logging/debugging/analysis)
     * @return this builder for chaining
     */
    @SuppressWarnings("unchecked")
    public JsonResponseBuilder setRoutingDecision(String routingPath, 
                                                   boolean guardResult, 
                                                   double probability) {
        this.routingDecision = new JSONObject();
        this.routingDecision.put("routing_path", routingPath);
        this.routingDecision.put("guard_result", guardResult);
        this.routingDecision.put("probability", probability);
        return this;
    }
    
    /**
     * NEW: Convenience method - set routing decision with just the result
     * Uses default probability of 0.5
     * 
     * @param guardResult Boolean result of guard evaluation
     * @return this builder for chaining
     */
    public JsonResponseBuilder setRoutingDecision(boolean guardResult) {
        return setRoutingDecision(guardResult ? "true" : "false", guardResult, 0.5);
    }
    
    /**
     * NEW: Convenience method - set routing path directly
     * 
     * @param routingPath Any string value for routing (e.g., "DIRECT_TO_TREATMENT", "STANDARD_WORKFLOW")
     * @return this builder for chaining
     */
    @SuppressWarnings("unchecked")
    public JsonResponseBuilder setRoutingPath(String routingPath) {
        this.routingDecision = new JSONObject();
        this.routingDecision.put("routing_path", routingPath);
        return this;
    }
    
    /**
     * Build JSON response
     * 
     * Response is wrapped in an object keyed by placeId for compatibility
     * with ServiceHelper token enrichment and DecisionNode routing.
     * 
     * Format: { "P1": { ... place response ... } }
     */
    @SuppressWarnings("unchecked")
    public String build() {
        JSONObject placeResponse = new JSONObject();
        
        // NEW: Add routing_decision FIRST (for DecisionNode extraction)
        if (routingDecision != null) {
            placeResponse.put("routing_decision", routingDecision);
        }
        
        // Token fields (preserve from original token)
        if (tokenId != null) {
            placeResponse.put("tokenId", tokenId);
        }
        
        if (version != null) {
            placeResponse.put("version", version);
        }
        
        if (notAfter > 0) {
            placeResponse.put("notAfter", notAfter);
        }
        
        if (currentPlace != null) {
            placeResponse.put("currentPlace", currentPlace);
        }
        
        // CRITICAL: Extract workflow_start_time from data and put at top level
        // This maintains the original token format
        String workflowStartTime = data.get("workflow_start_time");
        if (workflowStartTime != null) {
            try {
                placeResponse.put("workflow_start_time", Long.parseLong(workflowStartTime));
            } catch (NumberFormatException e) {
                placeResponse.put("workflow_start_time", workflowStartTime);
            }
        }
        
        // Place fields
        if (placeId != null) {
            placeResponse.put("placeId", placeId);
        }
        
        if (sequenceId != null) {
            placeResponse.put("sequenceId", sequenceId);
        }
        
        // Healthcare-specific: service type key
        if (serviceType != null) {
            placeResponse.put("serviceType", serviceType);
        }
        
        placeResponse.put("status", status);
        placeResponse.put("marking", marking);
        placeResponse.put("executionTime", executionTime);
        
        if (annotation != null) {
            placeResponse.put("annotation", annotation);
        }
        
        // Build data object (excluding workflow_start_time since it's at top level)
        // NEW: Include assessmentJsonFields if present
        if (!data.isEmpty() || assessmentJsonFields != null) {
            JSONObject dataJson = new JSONObject();
            
            // Add data map entries
            for (Map.Entry<String, String> entry : data.entrySet()) {
                // Skip workflow_start_time since it's already at top level
                if (!"workflow_start_time".equals(entry.getKey())) {
                    dataJson.put(entry.getKey(), entry.getValue());
                }
            }
            
            // NEW: Parse and merge assessmentJsonFields if present
            if (assessmentJsonFields != null && !assessmentJsonFields.isEmpty()) {
                try {
                    // The assessmentJsonFields is a JSON fragment like:
                    // "field1": "value1", "field2": "value2"
                    // Wrap it to make it parseable
                    String wrappedJson = "{" + assessmentJsonFields + "}";
                    JSONParser parser = new JSONParser();
                    JSONObject assessmentJson = (JSONObject) parser.parse(wrappedJson);
                    
                    // Merge all assessment fields into data
                    for (Object key : assessmentJson.keySet()) {
                        dataJson.put(key, assessmentJson.get(key));
                    }
                } catch (Exception e) {
                    // If parsing fails, add as raw string
                    System.err.println("[JsonResponseBuilder] Warning: Could not parse assessmentJsonFields: " + e.getMessage());
                    dataJson.put("assessmentData", assessmentJsonFields);
                }
            }
            
            // Only add data object if it has content
            if (!dataJson.isEmpty()) {
                placeResponse.put("data", dataJson);
            }
        }
        
        // Wrap in outer object keyed by placeId
        // This format is expected by ServiceHelper.enrichServiceResult()
        JSONObject response = new JSONObject();
        String responseKey = (placeId != null) ? placeId : "placeResults";
        response.put(responseKey, placeResponse);
        
        return response.toJSONString();
    }
    
    /**
     * Build JSON response WITHOUT wrapper (flat structure)
     * Use this when you don't need the { "placeId": { ... } } wrapper
     */
    @SuppressWarnings("unchecked")
    public String buildFlat() {
        JSONObject response = new JSONObject();
        
        // NEW: Add routing_decision FIRST (for DecisionNode extraction)
        if (routingDecision != null) {
            response.put("routing_decision", routingDecision);
        }
        
        // Token fields (preserve from original token)
        if (tokenId != null) {
            response.put("tokenId", tokenId);
        }
        
        if (version != null) {
            response.put("version", version);
        }
        
        if (notAfter > 0) {
            response.put("notAfter", notAfter);
        }
        
        if (currentPlace != null) {
            response.put("currentPlace", currentPlace);
        }
        
        // CRITICAL: Extract workflow_start_time from data and put at top level
        String workflowStartTime = data.get("workflow_start_time");
        if (workflowStartTime != null) {
            try {
                response.put("workflow_start_time", Long.parseLong(workflowStartTime));
            } catch (NumberFormatException e) {
                response.put("workflow_start_time", workflowStartTime);
            }
        }
        
        // Place fields
        if (placeId != null) {
            response.put("placeId", placeId);
        }
        
        if (sequenceId != null) {
            response.put("sequenceId", sequenceId);
        }
        
        response.put("status", status);
        response.put("marking", marking);
        response.put("executionTime", executionTime);
        
        if (annotation != null) {
            response.put("annotation", annotation);
        }
        
        // Build data object (excluding workflow_start_time since it's at top level)
        if (!data.isEmpty() || assessmentJsonFields != null) {
            JSONObject dataJson = new JSONObject();
            
            for (Map.Entry<String, String> entry : data.entrySet()) {
                if (!"workflow_start_time".equals(entry.getKey())) {
                    dataJson.put(entry.getKey(), entry.getValue());
                }
            }
            
            // NEW: Parse and merge assessmentJsonFields if present
            if (assessmentJsonFields != null && !assessmentJsonFields.isEmpty()) {
                try {
                    String wrappedJson = "{" + assessmentJsonFields + "}";
                    JSONParser parser = new JSONParser();
                    JSONObject assessmentJson = (JSONObject) parser.parse(wrappedJson);
                    for (Object key : assessmentJson.keySet()) {
                        dataJson.put(key, assessmentJson.get(key));
                    }
                } catch (Exception e) {
                    dataJson.put("assessmentData", assessmentJsonFields);
                }
            }
            
            if (!dataJson.isEmpty()) {
                response.put("data", dataJson);
            }
        }
        
        return response.toJSONString();
    }
    
    /**
     * Build error response
     */
    @SuppressWarnings("unchecked")
    public String createErrorResponse(String placeId, String errorMessage, 
                                      String sequenceId, String context) {
        JSONObject errorResponse = new JSONObject();
        
        errorResponse.put("placeId", placeId);
        errorResponse.put("sequenceId", sequenceId);
        errorResponse.put("status", "ERROR");
        errorResponse.put("errorMessage", errorMessage);
        errorResponse.put("context", context);
        errorResponse.put("timestamp", System.currentTimeMillis());
        
        // Wrap in outer object keyed by placeId for consistency
        JSONObject response = new JSONObject();
        response.put(placeId, errorResponse);
        
        return response.toJSONString();
    }
    
    /**
     * Build success response with minimal data
     */
    @SuppressWarnings("unchecked")
    public static String createSimpleResponse(String placeId, String sequenceId, String status) {
        JSONObject simpleResponse = new JSONObject();
        simpleResponse.put("placeId", placeId);
        simpleResponse.put("sequenceId", sequenceId);
        simpleResponse.put("status", status);
        simpleResponse.put("timestamp", System.currentTimeMillis());
        
        // Wrap in outer object keyed by placeId for consistency
        JSONObject response = new JSONObject();
        response.put(placeId, simpleResponse);
        
        return response.toJSONString();
    }
    
    /**
     * Reset builder to initial state
     */
    public void reset() {
        this.placeId = null;
        this.sequenceId = null;
        this.status = "PENDING";
        this.marking = 0;
        this.executionTime = 0;
        this.annotation = null;
        this.data.clear();
        
        // Reset token preservation fields
        this.tokenId = null;
        this.version = null;
        this.notAfter = 0;
        this.currentPlace = null;
        
        // Reset healthcare-specific fields
        this.serviceType = null;
        this.assessment = null;
        this.assessmentJsonFields = null;
        
        // Reset routing decision
        this.routingDecision = null;
    }
}