package com.editor.animator;

import java.util.*;

import org.apache.log4j.Logger;

/**
 * EventParser - Parses analyzer output text into MarkingEvents.
 * 
 * Single responsibility: text → structured events.
 * No animation logic, no topology awareness, just parsing.
 */
public class EventParser {
    
    private static final Logger logger = Logger.getLogger(EventParser.class.getName());
    
    /**
     * Parse result containing events and time bounds
     */
    public static class ParseResult {
        public final List<MarkingEvent> events;
        public final long startTime;
        public final long endTime;
        
        public ParseResult(List<MarkingEvent> events, long startTime, long endTime) {
            this.events = events;
            this.startTime = startTime;
            this.endTime = endTime;
        }
    }
    
    /**
     * Parse analyzer output text into events.
     * 
     * Expected format:
     * Time=xxx Token=xxx Place=xxx Marking=x Buffer=x ToPlace=xxx EventType=xxx TransitionId=xxx
     * 
     * @param text Raw analyzer output
     * @return ParseResult with events sorted by timestamp and time bounds
     */
    public ParseResult parse(String text) {
        List<MarkingEvent> events = new ArrayList<>();
        Set<String> seenEvents = new HashSet<>();
        long startTime = Long.MAX_VALUE;
        long endTime = Long.MIN_VALUE;
        
        String[] lines = text.split("\n");
        
        for (String line : lines) {
            line = line.trim();
            
            if (line.startsWith("Time=") && line.contains("Marking=")) {
                MarkingEvent event = parseLine(line, seenEvents);
                if (event != null) {
                    events.add(event);
                    
                    if (event.timestamp < startTime) startTime = event.timestamp;
                    if (event.timestamp > endTime) endTime = event.timestamp;
                }
            }
        }
        
        // Sort by timestamp
        Collections.sort(events);
        
        int duplicates = seenEvents.size() - events.size();
        logger.info("Parsed " + events.size() + " unique events" + 
            (duplicates > 0 ? " (filtered " + duplicates + " duplicates)" : ""));
        
        return new ParseResult(events, startTime, endTime);
    }
    
    private MarkingEvent parseLine(String line, Set<String> seenEvents) {
        try {
            long timestamp = 0;
            String tokenId = "";
            String placeId = "";
            int marking = 0;
            int buffer = 0;
            String toPlace = null;
            String eventType = null;
            String transitionId = null;
            
            String[] parts = line.split(" ");
            for (String part : parts) {
                if (part.startsWith("Time=")) {
                    timestamp = Long.parseLong(part.substring(5));
                } else if (part.startsWith("Token=")) {
                    tokenId = part.substring(6);
                } else if (part.startsWith("Place=")) {
                    placeId = part.substring(6);
                } else if (part.startsWith("Marking=")) {
                    marking = Integer.parseInt(part.substring(8));
                } else if (part.startsWith("Buffer=")) {
                    buffer = Integer.parseInt(part.substring(7));
                } else if (part.startsWith("ToPlace=")) {
                    toPlace = part.substring(8);
                } else if (part.startsWith("EventType=")) {
                    eventType = part.substring(10);
                } else if (part.startsWith("TransitionId=")) {
                    transitionId = part.substring(13);
                }
            }
            
            // Validation
            if (tokenId.isEmpty()) return null;
            if (placeId.isEmpty() && !"GENERATED".equals(eventType)) return null;
            if (marking < 0) return null;
            
            // Deduplication
            String eventKey = timestamp + "|" + tokenId + "|" + placeId + "|" + 
                marking + "|" + eventType + "|" + toPlace;
            if (seenEvents.contains(eventKey)) {
                return null;
            }
            seenEvents.add(eventKey);
            
            // Derive version and entering flag
            String version = getVersionFromTokenId(tokenId);
            boolean entering = (marking == 1);
            
            if (eventType == null) {
                eventType = entering ? "ENTER" : "EXIT";
            }
            
            return new MarkingEvent(timestamp, tokenId, version,
                placeId, entering, buffer, toPlace, eventType, transitionId);
                
        } catch (Exception e) {
            // Skip malformed lines
            return null;
        }
    }
    
    private String getVersionFromTokenId(String tokenId) {
        try {
            long id = Long.parseLong(tokenId);
            int versionNum = (int)(id / 1000000);
            return String.format("v%03d", versionNum);
        } catch (NumberFormatException e) {
            return "v001";
        }
    }
    
    /**
     * Group events by token ID
     */
    public static Map<String, List<MarkingEvent>> groupByToken(List<MarkingEvent> events) {
        Map<String, List<MarkingEvent>> grouped = new HashMap<>();
        for (MarkingEvent event : events) {
            grouped.computeIfAbsent(event.tokenId, k -> new ArrayList<>()).add(event);
        }
        return grouped;
    }
}
