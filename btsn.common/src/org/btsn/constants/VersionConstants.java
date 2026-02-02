package org.btsn.constants;

/**
 * Centralized version management for workflow sequence ranges
 * 
 * Token ID Encoding for Fork/Join:
 *   childTokenId = parentTokenId + branchNumber
 *   Example: 1010000 with 2-way fork -> 1010001, 1010002
 * 
 * With TOKEN_INCREMENT = 10000, each workflow has room for:
 *   - Up to 9999 child tokens per parent (branch numbers 1-9999)
 *   - Clean separation between workflows
 * 
 * SPECIAL VERSIONS:
 *   v999 - Admin/Collection workflows (instrumentation disabled)
 *          Used for initialization, data collection, and other admin tasks
 *          that should not be recorded in Petri Net analysis
 */
public class VersionConstants {
    // Version ranges (10x larger - 1,000,000 per version)
    public static final int V001_BASE = 1000000;
    public static final int V001_MAX = 1999999;
    
    public static final int V002_BASE = 2000000;
    public static final int V002_MAX = 2999999;
    
    public static final int V003_BASE = 3000000;
    public static final int V003_MAX = 3999999;
    
    public static final int V004_BASE = 4000000;
    public static final int V004_MAX = 4999999;
    
    public static final int V005_BASE = 5000000;
    public static final int V005_MAX = 5999999;
    
    // =============================================================================
    // SPECIAL ADMIN VERSION - Instrumentation disabled for these workflows
    // =============================================================================
    public static final int V999_BASE = 999000000;
    public static final int V999_MAX = 999999999;
    public static final String V999 = "v999";
    
    // Default block size for future versions
    public static final int VERSION_BLOCK_SIZE = 1000000;
    
    // Token increment between workflows
    // Child tokens use: parent + branchNumber (1, 2, 3, etc.)
    // 10000 gives room for up to 9999 branches per parent
    public static final int TOKEN_INCREMENT = 10000;
    
    /**
     * Check if a sequence ID belongs to an admin/collection version
     * that should skip instrumentation
     */
    public static boolean isAdminVersion(int sequenceId) {
        return sequenceId >= V999_BASE && sequenceId <= V999_MAX;
    }
    
    /**
     * Check if a version string is an admin version
     */
    public static boolean isAdminVersion(String version) {
        return V999.equalsIgnoreCase(version);
    }
    
    /**
     * Get workflow base from sequence ID directly
     * This is what ServiceThread.calculateWorkflowBase() needs
     */
    public static int getWorkflowBaseFromSequenceId(int sequenceId) {
        if (sequenceId >= V001_BASE && sequenceId <= V001_MAX) {
            return V001_BASE;
        } else if (sequenceId >= V002_BASE && sequenceId <= V002_MAX) {
            return V002_BASE;
        } else if (sequenceId >= V003_BASE && sequenceId <= V003_MAX) {
            return V003_BASE;
        } else if (sequenceId >= V004_BASE && sequenceId <= V004_MAX) {
            return V004_BASE;
        } else if (sequenceId >= V005_BASE && sequenceId <= V005_MAX) {
            return V005_BASE;
        } else if (sequenceId >= V999_BASE && sequenceId <= V999_MAX) {
            return V999_BASE;
        } else {
            return (sequenceId / VERSION_BLOCK_SIZE) * VERSION_BLOCK_SIZE;
        }
    }
    
    /**
     * Get parent token ID from a potentially forked token ID
     * Uses TOKEN_INCREMENT to find the workflow base
     */
    public static int getParentTokenId(int sequenceId) {
        int remainder = sequenceId % TOKEN_INCREMENT;
        return sequenceId - remainder;
    }
    
    /**
     * Determine version from sequence ID
     */
    public static String getVersionFromSequenceId(int sequenceId) {
        if (sequenceId >= V001_BASE && sequenceId <= V001_MAX) {
            return "v001";
        } else if (sequenceId >= V002_BASE && sequenceId <= V002_MAX) {
            return "v002";
        } else if (sequenceId >= V003_BASE && sequenceId <= V003_MAX) {
            return "v003";
        } else if (sequenceId >= V004_BASE && sequenceId <= V004_MAX) {
            return "v004";
        } else if (sequenceId >= V005_BASE && sequenceId <= V005_MAX) {
            return "v005";
        } else if (sequenceId >= V999_BASE && sequenceId <= V999_MAX) {
            return V999;
        } else {
            int calculatedBase = (sequenceId / VERSION_BLOCK_SIZE) * VERSION_BLOCK_SIZE;
            return getVersionString(calculatedBase);
        }
    }
    
    /**
     * Get the end of the sequence range for a given workflow base
     */
    public static int getVersionRangeEnd(int workflowBase) {
        if (workflowBase == V001_BASE) return V001_MAX + 1;
        if (workflowBase == V002_BASE) return V002_MAX + 1;
        if (workflowBase == V003_BASE) return V003_MAX + 1;
        if (workflowBase == V004_BASE) return V004_MAX + 1;
        if (workflowBase == V005_BASE) return V005_MAX + 1;
        if (workflowBase == V999_BASE) return V999_MAX + 1;
        
        return workflowBase + VERSION_BLOCK_SIZE;
    }
    
    /**
     * Get version string from workflow base
     */
    public static String getVersionString(int workflowBase) {
        if (workflowBase == V001_BASE) return "v001";
        if (workflowBase == V002_BASE) return "v002";
        if (workflowBase == V003_BASE) return "v003";
        if (workflowBase == V004_BASE) return "v004";
        if (workflowBase == V005_BASE) return "v005";
        if (workflowBase == V999_BASE) return V999;
        
        int versionNumber = workflowBase / VERSION_BLOCK_SIZE;
        return String.format("v%03d", versionNumber);
    }
    
    /**
     * Get workflow base from version string
     */
    public static int getWorkflowBase(String version) {
        switch (version.toLowerCase()) {
            case "v001": return V001_BASE;
            case "v002": return V002_BASE;
            case "v003": return V003_BASE;
            case "v004": return V004_BASE;
            case "v005": return V005_BASE;
            case "v999": return V999_BASE;
            default:
                if (version.matches("v\\d{3}")) {
                    int versionNum = Integer.parseInt(version.substring(1));
                    return versionNum * VERSION_BLOCK_SIZE;
                }
                return V001_BASE;
        }
    }
}