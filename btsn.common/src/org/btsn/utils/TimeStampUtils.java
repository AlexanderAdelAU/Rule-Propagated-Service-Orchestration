package org.btsn.utils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Utility class for timestamp formatting and handling
 */
public class TimeStampUtils {
    
    // Standard formats used across the healthcare system
    public static final DateTimeFormatter STANDARD_TIMESTAMP_FORMAT = 
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    public static final DateTimeFormatter ISO_TIMESTAMP_FORMAT = 
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");
    
    public static final DateTimeFormatter COMPACT_TIMESTAMP_FORMAT = 
        DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    
    /**
     * Get current timestamp in standard format
     */
    public static String getCurrentTimestamp() {
        return LocalDateTime.now().format(STANDARD_TIMESTAMP_FORMAT);
    }
    
    /**
     * Get current timestamp in ISO format
     */
    public static String getCurrentTimestampISO() {
        return ZonedDateTime.now(ZoneId.of("UTC")).format(ISO_TIMESTAMP_FORMAT);
    }
    
    /**
     * Get current timestamp in compact format (for IDs)
     */
    public static String getCurrentTimestampCompact() {
        return LocalDateTime.now().format(COMPACT_TIMESTAMP_FORMAT);
    }
    
    /**
     * Format a LocalDateTime to standard format
     */
    public static String formatTimestamp(LocalDateTime dateTime) {
        return dateTime.format(STANDARD_TIMESTAMP_FORMAT);
    }
    
    /**
     * Parse timestamp from standard format
     */
    public static LocalDateTime parseTimestamp(String timestamp) {
        return LocalDateTime.parse(timestamp, STANDARD_TIMESTAMP_FORMAT);
    }
    
    /**
     * Get timestamp for database storage (always UTC)
     */
    public static String getDatabaseTimestamp() {
        return getCurrentTimestampISO();
    }
}