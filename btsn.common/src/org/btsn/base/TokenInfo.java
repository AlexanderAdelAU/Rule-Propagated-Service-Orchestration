package org.btsn.base;

import java.util.HashMap;
import java.util.Map;

/**
 * Generic Token Information Structure
 * 
 * Represents a token in a Petri Net with minimal, domain-agnostic metadata.
 * 
 * Token Structure:
 * ================
 * τ = (id, data, version, notAfter, currentPlace, originalToken)
 * 
 * Where:
 * - id: Unique token identifier
 * - data: Arbitrary payload (key-value pairs)
 * - version: Version identifier (for isolation)
 * - notAfter: Validity timestamp (bounded waiting)
 * - currentPlace: Current place identifier (P1, P2, P3, etc.)
 * - originalToken: Raw input token (for audit/debugging)
 * 
 * This structure is completely generic and can represent tokens in ANY Petri Net.
 * 
 * @author ACameron
 */
public class TokenInfo {
    
    // Core token attributes (from paper: τ = (id, data, version, notAfter))
    private String tokenId;
    private Map<String, String> data;
    private String version;
    private long notAfter;
    private String currentPlace;
    private String processType;  // NEW: Workflow type (PetriNet, SOA, Healthcare, etc.)
    
    // Optional: track token history through the net
    private String previousPlace;
    
    // Original raw token (for audit/debugging/data provenance)
    private String originalToken;
    
    /**
     * Default constructor
     */
    public TokenInfo() {
        this.tokenId = "token_default";
        this.data = new HashMap<>();
        this.version = "v001";
        this.notAfter = System.currentTimeMillis() + 3600000; // 1 hour default
        this.currentPlace = "P1";
        this.previousPlace = null;
        this.originalToken = null;
        this.processType = null;
    }
    
    /**
     * Constructor with basic attributes
     */
    public TokenInfo(String tokenId, String version, long notAfter) {
        this.tokenId = tokenId;
        this.data = new HashMap<>();
        this.version = version;
        this.notAfter = notAfter;
        this.currentPlace = null;
        this.previousPlace = null;
        this.originalToken = null;
        this.processType = null;
    }
    
    /**
     * Constructor with all attributes
     */
    public TokenInfo(String tokenId, Map<String, String> data, String version, 
                     long notAfter, String currentPlace) {
        this.tokenId = tokenId;
        this.data = data != null ? new HashMap<>(data) : new HashMap<>();
        this.version = version;
        this.notAfter = notAfter;
        this.currentPlace = currentPlace;
        this.previousPlace = null;
        this.originalToken = null;
        this.processType = null;
    }
    
    // ========== Core Getters/Setters ==========
    
    public String getTokenId() {
        return tokenId;
    }
    
    public void setTokenId(String tokenId) {
        this.tokenId = tokenId;
    }
    
    public Map<String, String> getData() {
        return new HashMap<>(data);
    }
    
    public void setData(Map<String, String> data) {
        this.data = data != null ? new HashMap<>(data) : new HashMap<>();
    }
    
    public String getVersion() {
        return version;
    }
    
    public void setVersion(String version) {
        this.version = version;
    }
    
    public long getNotAfter() {
        return notAfter;
    }
    
    public void setNotAfter(long notAfter) {
        this.notAfter = notAfter;
    }
    
    public String getCurrentPlace() {
        return currentPlace;
    }
    
    public void setCurrentPlace(String currentPlace) {
        this.previousPlace = this.currentPlace;
        this.currentPlace = currentPlace;
    }
    
    public String getPreviousPlace() {
        return previousPlace;
    }
    
    /**
     * Get original raw token (for audit/debugging)
     * 
     * @return The original token string as received, before any parsing/cleaning
     */
    public String getOriginalToken() {
        return originalToken;
    }
    
    /**
     * Set original raw token
     * 
     * Should be called by ServiceHelper/TokenParser when first receiving the token,
     * before any cleaning or transformation is applied.
     * 
     * @param originalToken The raw token string as received
     */
    public void setOriginalToken(String originalToken) {
        this.originalToken = originalToken;
    }
    
    /**
     * Get process type (workflow type: PetriNet, SOA, Healthcare, etc.)
     * 
     * @return The process type string, or null if not set
     */
    public String getProcessType() {
        return processType;
    }
    
    /**
     * Set process type (workflow type)
     * 
     * Common values: "PetriNet", "SOA", "Healthcare"
     * Used by ServiceThread to determine join completion strategy.
     * 
     * @param processType The workflow type identifier
     */
    public void setProcessType(String processType) {
        this.processType = processType;
    }
    
    // ========== Data Accessors (key-value pairs) ==========
    
    /**
     * Get data value by key
     */
    public String getDataValue(String key) {
        return data.get(key);
    }
    
    /**
     * Set data value by key
     */
    public void setDataValue(String key, String value) {
        data.put(key, value);
    }
    
    /**
     * Check if data contains key
     */
    public boolean hasDataKey(String key) {
        return data.containsKey(key);
    }
    
    /**
     * Remove data value by key
     */
    public String removeDataValue(String key) {
        return data.remove(key);
    }
    
    // ========== Healthcare Convenience Methods ==========
    // These provide easy access to common healthcare data fields
    
    /**
     * Get patient ID from token data
     * Looks for "patientId" or "patient_id" keys
     */
    public String getPatientId() {
        String patientId = data.get("patientId");
        if (patientId == null) {
            patientId = data.get("patient_id");
        }
        return patientId != null ? patientId : "unknown";
    }
    
    /**
     * Set patient ID in token data
     */
    public void setPatientId(String patientId) {
        data.put("patientId", patientId);
    }
    
    /**
     * Get indication/condition from token data
     * Looks for "indication", "condition", or "diagnosis" keys
     */
    public String getIndication() {
        String indication = data.get("indication");
        if (indication == null) {
            indication = data.get("condition");
        }
        if (indication == null) {
            indication = data.get("diagnosis");
        }
        return indication != null ? indication : "unspecified";
    }
    
    /**
     * Set indication in token data
     */
    public void setIndication(String indication) {
        data.put("indication", indication);
    }
    
    /**
     * Get provider ID from token data
     */
    public String getProviderId() {
        String providerId = data.get("providerId");
        if (providerId == null) {
            providerId = data.get("provider_id");
        }
        return providerId != null ? providerId : "unknown";
    }
    
    /**
     * Set provider ID in token data
     */
    public void setProviderId(String providerId) {
        data.put("providerId", providerId);
    }
    
    /**
     * Clear all data
     */
    public void clearData() {
        data.clear();
    }
    
    // ========== Convenience Methods ==========
    
    /**
     * Check if token is expired
     */
    public boolean isExpired() {
        return System.currentTimeMillis() > notAfter;
    }
    
    /**
     * Get remaining time before expiry (in milliseconds)
     */
    public long getRemainingTime() {
        return notAfter - System.currentTimeMillis();
    }
    
    /**
     * Check if token is valid (not expired)
     */
    public boolean isValid() {
        return !isExpired();
    }
    
    // ========== String Representation ==========
    
    @Override
    public String toString() {
        return String.format(
            "Token{id='%s', version='%s', place='%s', processType='%s', data=%s, valid=%b}",
            tokenId, version, currentPlace, processType, data, isValid()
        );
    }
    
    /**
     * Get detailed string representation
     */
    public String toDetailedString() {
        return String.format(
            "Token{\n" +
            "  id: %s\n" +
            "  version: %s\n" +
            "  processType: %s\n" +
            "  currentPlace: %s\n" +
            "  previousPlace: %s\n" +
            "  notAfter: %d\n" +
            "  remainingTime: %dms\n" +
            "  valid: %b\n" +
            "  data: %s\n" +
            "  originalToken: %s\n" +
            "}",
            tokenId, version, processType, currentPlace, previousPlace, 
            notAfter, getRemainingTime(), isValid(), data,
            originalToken != null ? (originalToken.length() > 50 ? originalToken.substring(0, 50) + "..." : originalToken) : "null"
        );
    }
    
    /**
     * Clone this token
     */
    public TokenInfo clone() {
        TokenInfo cloned = new TokenInfo(tokenId, data, version, notAfter, currentPlace);
        cloned.previousPlace = this.previousPlace;
        cloned.originalToken = this.originalToken;
        cloned.processType = this.processType;
        return cloned;
    }
}