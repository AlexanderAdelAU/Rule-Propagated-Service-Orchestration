package org.btsn.handlers;

/**
 * TokenIdManager - Centralized token ID encoding/decoding for workflow systems.
 * 
 * This class manages the token ID encoding scheme used for fork/join patterns.
 * All token ID manipulation should go through this class to ensure consistency.
 * 
 * ENCODING SCHEME:
 *   childTokenId = parentTokenId + branchNumber
 *   
 *   - Parent tokens: end in 00 (e.g., 1000000, 2000000)
 *   - Child tokens: branch 1-99 (e.g., 1000001, 1000002, ..., 1000099)
 *   - Maximum 99 branches per fork
 *   
 * EXAMPLES:
 *   Parent 1000000 forks to 2 branches:
 *     - Branch 1: 1000001
 *     - Branch 2: 1000002
 *   
 *   Decoding 1000002:
 *     - Parent: 1000000
 *     - Branch: 2
 *     - IsForked: true
 * 
 * NOTE: This is a modelling convenience for PetriNet workflows that allows
 * fork/join patterns to work without requiring explicit branch-specific 
 * attribute names in the topology. SOA/Healthcare workflows use explicit
 * canonical bindings instead.
 */
public class TokenIdManager {
    
    /** Maximum number of branches supported per fork (1-99) */
    public static final int MAX_BRANCHES = 99;
    
    /** Modulus used for branch encoding */
    private static final int BRANCH_MODULUS = 100;
    
    // ========================================================================
    // ENCODING - Creating child token IDs from parent
    // ========================================================================
    
    /**
     * Create a child token ID for a fork branch.
     * 
     * @param parentTokenId The parent token ID (must end in 00)
     * @param branchNumber The branch number (1 to MAX_BRANCHES)
     * @return The child token ID
     * @throws IllegalArgumentException if branchNumber is out of range
     */
    public static int createChildTokenId(int parentTokenId, int branchNumber) {
        if (branchNumber < 1 || branchNumber > MAX_BRANCHES) {
            throw new IllegalArgumentException(
                "Branch number must be between 1 and " + MAX_BRANCHES + ", got: " + branchNumber);
        }
        return parentTokenId + branchNumber;
    }
    
    // ========================================================================
    // DECODING - Extracting information from token IDs
    // ========================================================================
    
    /**
     * Get the parent token ID from a child token ID.
     * For parent tokens, returns the same ID.
     * 
     * @param tokenId Any token ID
     * @return The parent token ID
     */
    public static int getParentTokenId(int tokenId) {
        return tokenId - getBranchNumber(tokenId);
    }
    
    /**
     * Get the branch number from a token ID.
     * Returns 0 for parent tokens.
     * 
     * @param tokenId Any token ID
     * @return Branch number (0 for parent, 1-99 for children)
     */
    public static int getBranchNumber(int tokenId) {
        return tokenId % BRANCH_MODULUS;
    }
    
    /**
     * Check if a token is a forked child token.
     * 
     * @param tokenId Any token ID
     * @return true if this is a forked child token (branch > 0)
     */
    public static boolean isForkedToken(int tokenId) {
        return getBranchNumber(tokenId) > 0;
    }
    
    /**
     * Check if a token is a parent/base token.
     * 
     * @param tokenId Any token ID
     * @return true if this is a parent token (branch == 0)
     */
    public static boolean isParentToken(int tokenId) {
        return getBranchNumber(tokenId) == 0;
    }
    
    // ========================================================================
    // STORAGE KEY GENERATION - For join point storage
    // ========================================================================
    
    /**
     * Generate a storage key for join point accumulation.
     * For forked tokens, appends "_branchN" to distinguish branches.
     * For parent tokens, returns the attribute name unchanged.
     * 
     * @param tokenId The token ID
     * @param attributeName The base attribute name (e.g., "token")
     * @return Storage key (e.g., "token" or "token_branch1")
     */
    public static String getStorageKey(int tokenId, String attributeName) {
        int branchNumber = getBranchNumber(tokenId);
        if (branchNumber > 0) {
            return attributeName + "_branch" + branchNumber;
        }
        return attributeName;
    }
    
    // ========================================================================
    // UTILITY METHODS
    // ========================================================================
    
    /**
     * Get the token family (workflow base) for grouping related tokens.
     * This is equivalent to getParentTokenId but named for clarity in
     * visualization/reporting contexts.
     * 
     * @param tokenId Any token ID
     * @return The token family ID (same as parent token ID)
     */
    public static int getTokenFamily(int tokenId) {
        return getParentTokenId(tokenId);
    }
    
    /**
     * Format a token ID for logging/display, showing parent and branch.
     * 
     * @param tokenId Any token ID
     * @return Formatted string (e.g., "1000001 (parent=1000000, branch=1)")
     */
    public static String formatTokenId(int tokenId) {
        int branch = getBranchNumber(tokenId);
        if (branch > 0) {
            return tokenId + " (parent=" + getParentTokenId(tokenId) + ", branch=" + branch + ")";
        }
        return tokenId + " (parent)";
    }
}