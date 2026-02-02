package org.btsn.json;

/**
 * Nuclear option JSON cleaner - makes JSON readable again
 * Use this when you just want clean, readable JSON without escape character hell
 */
public class JsonCleaner {
    
    /**
     * The nuclear option - clean ANY JSON string to make it readable
     * Removes ALL backslashes, escape sequences, and problematic characters
     */
    public static String makeReadableJson(String jsonString) {
        if (jsonString == null || jsonString.trim().isEmpty()) {
            return "{}";
        }
        
        // Step 1: Remove all backslash escape sequences
        String cleaned = jsonString
            .replace("\\\"", "\"")      // Fix escaped quotes -> normal quotes
            .replace("\\\\", "")        // Remove double backslashes completely
            .replace("\\/", "/")        // Fix escaped slashes -> normal slashes
            .replace("\\n", " ")        // Fix escaped newlines -> spaces
            .replace("\\r", " ")        // Fix escaped returns -> spaces  
            .replace("\\t", " ")        // Fix escaped tabs -> spaces
            .replace("\\", "");         // Remove any remaining backslashes
        
        // Step 2: Clean up the content inside quoted values
        cleaned = cleanQuotedValues(cleaned);
        
        // Step 3: Format for readability
        cleaned = formatForReadability(cleaned);
        
        return cleaned;
    }
    
    /**
     * Clean content inside quoted JSON values
     */
    private static String cleanQuotedValues(String json) {
        StringBuilder result = new StringBuilder();
        boolean insideQuotes = false;
        boolean insideValue = false;
        
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            
            if (c == '"' && (i == 0 || json.charAt(i-1) != '\\')) {
                if (!insideQuotes) {
                    // Starting a quoted section
                    insideQuotes = true;
                    // Check if this is a value (after a colon) or a key
                    insideValue = lookBackForColon(json, i);
                } else {
                    // Ending a quoted section
                    insideQuotes = false;
                    insideValue = false;
                }
                result.append(c);
            } else if (insideQuotes && insideValue) {
                // Inside a quoted value - clean problematic characters
                if (c == '\\' || c == '/') {
                    // Skip these problematic characters in values
                    continue;
                } else {
                    result.append(c);
                }
            } else {
                result.append(c);
            }
        }
        
        return result.toString();
    }
    
    /**
     * Check if we're in a value position (after a colon)
     */
    private static boolean lookBackForColon(String json, int pos) {
        for (int i = pos - 1; i >= 0; i--) {
            char c = json.charAt(i);
            if (c == ':') {
                return true;
            } else if (c == ',' || c == '{' || c == '[') {
                return false;
            }
        }
        return false;
    }
    
    /**
     * Format JSON for better readability (optional)
     */
    private static String formatForReadability(String json) {
        // Just clean up spacing - don't do full pretty-printing
        return json.replace("  ", " ")      // Remove double spaces
                  .replace(" ,", ",")       // Clean space before comma
                  .replace(" }", "}")       // Clean space before closing brace
                  .replace("{ ", "{")       // Clean space after opening brace
                  .trim();
    }
    
    /**
     * Extra aggressive cleaning - removes ALL escape characters from entire string
     */
    public static String superCleanJson(String jsonString) {
        if (jsonString == null) return "{}";
        
        return jsonString
            .replaceAll("\\\\+", "")        // Remove all backslashes (one or more)
            .replace("/", "")               // Remove forward slashes  
            .replaceAll("\\s+", " ")        // Replace multiple whitespace with single space
            .replace(" ,", ",")             // Clean spacing around commas
            .replace(" }", "}")             // Clean spacing around braces
            .replace("{ ", "{")             // Clean spacing around braces
            .replace(" ]", "]")             // Clean spacing around brackets
            .replace("[ ", "[")             // Clean spacing around brackets
            .trim();
    }
    
    /**
     * Quick test method to verify cleaning
     */
    public static void main(String[] args) {
        String messyJson = "{\"patient_id\":\"\\P_Triage\\\",\"service_provider\":\"\\/RN001\\/\",\"assessment_id\":\"TRIAGE_\\\\P_Triage\\\\_123\"}";
        System.out.println("Before: " + messyJson);
        System.out.println("After:  " + makeReadableJson(messyJson));
        System.out.println("Super:  " + superCleanJson(messyJson));
    }
}