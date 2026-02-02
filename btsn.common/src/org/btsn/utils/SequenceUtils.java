package org.btsn.utils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Utility class for generating sequence IDs and assessment IDs
 */
public class SequenceUtils {
    
    private static final DateTimeFormatter ID_TIMESTAMP_FORMAT = 
        DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    
    /**
     * Generate a unique assessment ID
     * Format: PREFIX_PATIENT_TIMESTAMP_RANDOM
     */
    public static String generateAssessmentId(String prefix, String patientId) {
        String timestamp = LocalDateTime.now().format(ID_TIMESTAMP_FORMAT);
        int random = ThreadLocalRandom.current().nextInt(1000, 9999);
        return String.format("%s_%s_%s_%d", prefix, patientId, timestamp, random);
    }
    
    /**
     * Generate a unique place ID
     */
    public static String generatePlaceId(String serviceType, String sequenceId) {
        return String.format("%s_PLACE_%s", serviceType.toUpperCase(), sequenceId);
    }
    
    /**
     * Generate a provider ID based on service type and sequence
     */
    public static String generateProviderId(String serviceType, String sequenceId) {
        int providerNumber = Integer.parseInt(sequenceId) % 1000;
        return String.format("DR_%s_%d", serviceType.toUpperCase().substring(0, 4), providerNumber);
    }
    
    /**
     * Extract service type from assessment ID
     */
    public static String extractServiceTypeFromAssessmentId(String assessmentId) {
        if (assessmentId == null || !assessmentId.contains("_")) {
            return "UNKNOWN";
        }
        return assessmentId.split("_")[0];
    }
    
    /**
     * Extract patient ID from assessment ID
     */
    public static String extractPatientIdFromAssessmentId(String assessmentId) {
        if (assessmentId == null) {
            return null;
        }
        String[] parts = assessmentId.split("_");
        if (parts.length >= 2) {
            return parts[1];
        }
        return null;
    }
}
