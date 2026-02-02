package org.btsn.json;

import java.util.HashMap;
import java.util.Map;

import org.btsn.base.TokenInfo;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

/**
 * Generic JSON Token Parser
 * 
 * Parses token data in JSON format for Petri Net simulation.
 * Completely domain-agnostic - works with any token structure.
 * 
 * Expected Token Format:
 * ======================
 * {
 *   "tokenId": "token_001",
 *   "version": "v001",
 *   "notAfter": 1234567890000,
 *   "currentPlace": "P1",
 *   "data": {
 *     "key1": "value1",
 *     "key2": "value2"
 *   }
 * }
 * 
 * @author ACameron
 */
public class JsonTokenParser {
    
    private final String placeId;
    private final JSONParser jsonParser;
    
    public JsonTokenParser(String placeId) {
        this.placeId = placeId;
        this.jsonParser = new JSONParser();
    }
    
    /**
     * Parse incoming token data - handles multiple input formats
     * 
     * @param tokenData JSON string containing token information
     * @return TokenInfo object with parsed data
     */
    public TokenInfo parseIncomingToken(String tokenData) {
        TokenInfo info = new TokenInfo();
        
        if (tokenData == null || tokenData.trim().isEmpty()) {
            System.out.printf("[%s] Warning: Empty token data, using defaults\n", placeId);
            return info;
        }
        
        try {
            // Try parsing as JSON object
            Object parsed = jsonParser.parse(tokenData);
            
            if (parsed instanceof JSONObject) {
                JSONObject json = (JSONObject) parsed;
                parseFromJsonObject(json, info);
            } else {
                System.out.printf("[%s] Warning: Token data is not a JSON object, using defaults\n", placeId);
            }
            
        } catch (Exception e) {
            System.err.printf("[%s] Error parsing token data: %s\n", placeId, e.getMessage());
            System.err.printf("[%s] Token data: %s\n", placeId, 
                tokenData.length() > 200 ? tokenData.substring(0, 200) + "..." : tokenData);
        }
        
        return info;
    }
    
    /**
     * Parse from JSON object
     * 
     * Handles two token formats:
     * 1. Direct token: {"tokenId": "...", "workflow_start_time": ..., ...}
     * 2. Place-wrapped token: {"P1": {"tokenId": "...", "workflow_start_time": ..., ...}}
     * 
     * Place-wrapped tokens are automatically unwrapped before parsing.
     */
    private void parseFromJsonObject(JSONObject json, TokenInfo info) {
        // Check if this is a place-wrapped token (e.g., {"P1": {...}})
        // Place-wrapped tokens have a single key containing a JSONObject
        JSONObject tokenData = unwrapPlaceResponse(json);
        
        // Parse core token attributes
        if (tokenData.containsKey("tokenId")) {
            info.setTokenId(tokenData.get("tokenId").toString());
        }
        
        if (tokenData.containsKey("version")) {
            info.setVersion(tokenData.get("version").toString());
        }
        
        if (tokenData.containsKey("notAfter")) {
            try {
                info.setNotAfter(Long.parseLong(tokenData.get("notAfter").toString()));
            } catch (NumberFormatException e) {
                System.err.printf("[%s] Invalid notAfter value: %s\n", placeId, tokenData.get("notAfter"));
            }
        }
        
        if (tokenData.containsKey("currentPlace")) {
            info.setCurrentPlace(tokenData.get("currentPlace").toString());
        }
        
        // Parse data map (will be merged with top-level fields)
        Map<String, String> dataMap = new HashMap<>();
        if (tokenData.containsKey("data")) {
            Object dataObj = tokenData.get("data");
            if (dataObj instanceof JSONObject) {
                JSONObject dataJson = (JSONObject) dataObj;
                
                for (Object key : dataJson.keySet()) {
                    String keyStr = key.toString();
                    String valueStr = dataJson.get(key).toString();
                    dataMap.put(keyStr, valueStr);
                }
            }
        }
        
        // CRITICAL: Parse workflow_start_time from the unwrapped token data
        // After unwrapping, workflow_start_time should be at the top level
        if (tokenData.containsKey("workflow_start_time")) {
            String workflowStartTime = tokenData.get("workflow_start_time").toString();
            dataMap.put("workflow_start_time", workflowStartTime);
            System.out.printf("[%s] Preserved workflow_start_time: %s\n", placeId, workflowStartTime);
        }
        
        // Set the complete data map
        if (!dataMap.isEmpty()) {
            info.setData(dataMap);
        }
        
        System.out.printf("[%s] Parsed token: id=%s, version=%s, place=%s\n", 
            placeId, info.getTokenId(), info.getVersion(), info.getCurrentPlace());
    }

    /**
     * Unwrap place-wrapped token responses
     * 
     * Tokens flowing through the Petri Net get wrapped by each place:
     * - Original: {"tokenId": "...", "workflow_start_time": ...}
     * - After P1: {"P1": {"tokenId": "...", "workflow_start_time": ...}}
     * - After P2: {"P2": {"tokenId": "...", "workflow_start_time": ...}}
     * 
     * This method detects and unwraps these place responses to access
     * the actual token data inside.
     * 
     * @param json The potentially wrapped JSON object
     * @return The unwrapped token data (or original if not wrapped)
     */
    private JSONObject unwrapPlaceResponse(JSONObject json) {
        if (json == null) return json;
        
        // Check if this looks like a place-wrapped response:
        // - Has exactly one key
        // - That key's value is a JSONObject
        // - The inner object has token fields (tokenId, version, etc.)
        if (json.keySet().size() == 1) {
            String key = json.keySet().iterator().next().toString();
            Object value = json.get(key);
            
            if (value instanceof JSONObject) {
                JSONObject inner = (JSONObject) value;
                
                // Verify this is a token wrapper by checking for token fields
                if (inner.containsKey("tokenId") || 
                    inner.containsKey("version") || 
                    inner.containsKey("workflow_start_time") ||
                    inner.containsKey("status")) {
                    
                    System.out.printf("[%s] Unwrapped place response from key: %s\n", placeId, key);
                    return inner;
                }
            }
        }
        
        // Not a wrapped response, return as-is
        return json;
    }
    
    /**
     * Parse token with strict validation - throws exceptions on error
     * 
     * @param tokenData JSON string
     * @return TokenInfo object
     * @throws Exception if parsing fails
     */
    public TokenInfo parseIncomingTokenStrict(String tokenData) throws Exception {
        if (tokenData == null || tokenData.trim().isEmpty()) {
            throw new IllegalArgumentException("Token data is null or empty");
        }
        
        TokenInfo info = new TokenInfo();
        
        try {
            Object parsed = jsonParser.parse(tokenData);
            
            if (!(parsed instanceof JSONObject)) {
                throw new IllegalArgumentException("Token data is not a valid JSON object");
            }
            
            JSONObject json = (JSONObject) parsed;
            parseFromJsonObject(json, info);
            
            // Validate required fields
            if (info.getTokenId() == null || info.getTokenId().isEmpty()) {
                throw new IllegalArgumentException("Token ID is required");
            }
            
            if (info.getVersion() == null || info.getVersion().isEmpty()) {
                throw new IllegalArgumentException("Version is required");
            }
            
        } catch (Exception e) {
            throw new Exception("Failed to parse token: " + e.getMessage(), e);
        }
        
        return info;
    }
    
    /**
     * Create a simple token from minimal data
     * Useful for testing or simple workflows
     * 
     * @param tokenId Token identifier
     * @param version Version string
     * @return TokenInfo object
     */
    public static TokenInfo createSimpleToken(String tokenId, String version) {
        return new TokenInfo(
            tokenId,
            new HashMap<>(),
            version,
            System.currentTimeMillis() + 3600000, // 1 hour validity
            null
        );
    }
    
    /**
     * Create a token with data
     * 
     * @param tokenId Token identifier
     * @param version Version string
     * @param data Data map
     * @return TokenInfo object
     */
    public static TokenInfo createToken(String tokenId, String version, Map<String, String> data) {
        return new TokenInfo(
            tokenId,
            data,
            version,
            System.currentTimeMillis() + 3600000, // 1 hour validity
            null
        );
    }
}