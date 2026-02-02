package org.btsn.handlers;

import java.io.*;
import java.net.*;
import java.util.ArrayList;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.btsn.utils.StringFileIO;
import org.btsn.utils.XPathHelperCommon;

/**
 * Windows 7 Compatible EventReactor using UDP with GZIP Decompression
 * ENHANCED: Now supports automatic chunk reassembly for large payloads
 */
public class EventReactor extends Thread {

	private int MAXQUEUE = 5;
	private int maxBufferSeen = 0;
	private static int MAX_WIRE_LENGTH = 4096;
	private static int NETWORK_SERVER_THREAD_POOL_SIZE = 2;
	private static boolean COMPRESSION_ENABLED = true;

	private TreeMap<Long, String> costKeyTokenMap = new TreeMap<Long, String>();
	public static ConcurrentLinkedQueue<String> tokenQueue = new ConcurrentLinkedQueue<String>();

	private Scheduler s2 = new Scheduler();
	private TreeMap<Long, String> dataMap = new TreeMap<Long, String>();
	private XPathHelperCommon xph = new XPathHelperCommon();
	private static int lostEvents = 0;

	// UDP components
	private DatagramSocket serviceSocket;
	private volatile boolean running = true;
	private String servicePort;
	private final Logger logger = Logger.getLogger(EventReactor.class.getName());
	
	// ============================================================================
	// CHUNK REASSEMBLY SUPPORT
	// ============================================================================
	
	// Buffer to hold chunks being reassembled, keyed by correlationId
	private final ConcurrentHashMap<String, ChunkBuffer> chunkBuffers = new ConcurrentHashMap<>();
	
	// Chunk buffer expiration time (30 seconds)
	private static final long CHUNK_BUFFER_EXPIRY_MS = 30000;
	
	// Cleanup interval for expired chunk buffers
	private static final long CLEANUP_INTERVAL_MS = 10000;
	private long lastCleanupTime = System.currentTimeMillis();
	
	/**
	 * Holds chunks being reassembled for a single message
	 */
	private static class ChunkBuffer {
		final int totalChunks;
		final String[] chunks;
		final long createdTime;
		int receivedCount = 0;
		String serviceType;
		String operationName;
		
		ChunkBuffer(int totalChunks) {
			this.totalChunks = totalChunks;
			this.chunks = new String[totalChunks];
			this.createdTime = System.currentTimeMillis();
		}
		
		synchronized boolean addChunk(int index, String data) {
			if (index >= 0 && index < totalChunks && chunks[index] == null) {
				chunks[index] = data;
				receivedCount++;
				return true;
			}
			return false;
		}
		
		synchronized boolean isComplete() {
			return receivedCount == totalChunks;
		}
		
		synchronized String reassemble() {
			StringBuilder sb = new StringBuilder();
			for (String chunk : chunks) {
				if (chunk != null) {
					sb.append(chunk);
				}
			}
			return sb.toString();
		}
		
		boolean isExpired() {
			return System.currentTimeMillis() - createdTime > CHUNK_BUFFER_EXPIRY_MS;
		}
	}

	public EventReactor(String serviceChannel, String servicePortParam) throws Exception {
	    this.servicePort = servicePortParam;
	    
	    TreeMap<String, String> reactorMap = new TreeMap<String, String>();

	    File commonBase = new File("./");
	    String commonPath = commonBase.getCanonicalPath();

	    String serviceLoaderDirectory = commonPath + "/ServiceLoaderQueries/";
	    String loaderSettings = serviceLoaderDirectory + "loaderSettings.xml";
	    
	    String xmlReactorSettings = StringFileIO.readFileAsString(loaderSettings);
	    reactorMap = xph.findMultipleXMLItems(xmlReactorSettings, "//ReactorSettings/*");
	    MAXQUEUE = Integer.valueOf(reactorMap.get("maxQueue"));
	    NETWORK_SERVER_THREAD_POOL_SIZE = Integer.valueOf(reactorMap.get("poolSize"));

	    // Load compression setting if available
	    if (reactorMap.containsKey("compressionEnabled")) {
	        COMPRESSION_ENABLED = Boolean.valueOf(reactorMap.get("compressionEnabled"));
	    }

	    try {
	        // FIXED: Use the port directly as calculated by ServiceLoader
	        // ServiceLoader already calculated: 10000 + (channelNumber * 1000) + ruleBasePort
	        int finalPort = Integer.parseInt(servicePortParam);
	        
	     // Check if we're in remote mode
	        if (System.getProperty("service.remote.host") != null) {
	            // Remote mode - bind to all interfaces
	            serviceSocket = new DatagramSocket(finalPort, InetAddress.getByName("0.0.0.0"));
	            System.out.println("Service Handler is listening on UDP port: " + finalPort + 
	                              " (ALL INTERFACES, remote mode, compression " + 
	                              (COMPRESSION_ENABLED ? "enabled" : "disabled") + ")");
	        } else {
	            // Local mode - bind to localhost only  
	            serviceSocket = new DatagramSocket(finalPort, InetAddress.getLoopbackAddress());
	            System.out.println("Service Handler is listening on UDP port: " + finalPort + 
	                              " (localhost only, local mode, compression " + 
	                              (COMPRESSION_ENABLED ? "enabled" : "disabled") + ")");
	        }
	        serviceSocket.setSoTimeout(5000); // 5 second timeout to prevent blocking
	        
	        logger.info("EventReactor: Chunk reassembly enabled (buffer expiry: " + 
	                   CHUNK_BUFFER_EXPIRY_MS + "ms)");
	        
	    } catch (Exception e) {
	        System.err.println("EventReactor: Failed to create UDP socket for calculated port. Error: " + e.getMessage());
	        throw new Exception("Failed to initialize EventReactor UDP socket", e);
	    }

	    // Rest of constructor remains the same...
	    int threadPoolSize = NETWORK_SERVER_THREAD_POOL_SIZE;
	    for (int i = 0; i < threadPoolSize; i++) {
	        Thread thread = new Thread() {
	            public synchronized void run() {
	                receiveLoop();
	            }
	        };
	        thread.start();
	        logger.info("Created and started UDP network pool thread = " + thread.getName());
	    }
	}

	/**
	 * Decompress payload using GZIP decompression
	 */
	private String decompressPayload(byte[] compressedData, int length) throws IOException {
	    if (!COMPRESSION_ENABLED) {
	        return new String(compressedData, 0, length, "UTF-8");
	    }
	    
	    // First try to decompress as GZIP
	    try {
	        ByteArrayInputStream bais = new ByteArrayInputStream(compressedData, 0, length);
	        
	        // Check if this looks like GZIP data (starts with magic number 0x1f, 0x8b)
	        if (length >= 2 && 
	            (compressedData[0] & 0xff) == 0x1f && 
	            (compressedData[1] & 0xff) == 0x8b) {
	            
	            bais.reset();
	            try (GZIPInputStream gzipIn = new GZIPInputStream(bais);
	                 ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
	                
	                byte[] buffer = new byte[1024];
	                int len;
	                while ((len = gzipIn.read(buffer)) != -1) {
	                    baos.write(buffer, 0, len);
	                }
	                
	                String decompressed = baos.toString("UTF-8");
	                
	                logger.fine("EventReactor: Successfully decompressed " + length + 
	                           " bytes to " + decompressed.length() + " characters");
	                
	                return decompressed;
	            }
	        } else {
	            // Doesn't look like GZIP, treat as uncompressed
	            logger.fine("EventReactor: Data doesn't appear to be GZIP compressed, treating as plain text");
	            return new String(compressedData, 0, length, "UTF-8");
	        }
	        
	    } catch (IOException e) {
	        // Decompression failed, try treating as uncompressed data
	        logger.log(Level.WARNING, "EventReactor: Decompression failed, treating as uncompressed data", e);
	        return new String(compressedData, 0, length, "UTF-8");
	    }
	}

	/**
	 * Test compression for debugging
	 */
	private byte[] testCompressPayload(String payload) throws IOException {
	    if (!COMPRESSION_ENABLED) {
	        return payload.getBytes("UTF-8");
	    }
	    
	    ByteArrayOutputStream baos = new ByteArrayOutputStream();
	    try (GZIPOutputStream gzipOut = new GZIPOutputStream(baos)) {
	        gzipOut.write(payload.getBytes("UTF-8"));
	        gzipOut.finish();
	    }
	    
	    return baos.toByteArray();
	}

	/**
	 * Extract channel number from channel identifier
	 * UPDATED: No longer handles multicast addresses
	 */
	private int extractChannelNumber(String channel) {
	    try {
	        // Handle "ip0", "ip1", "ip2" format
	        if (channel != null && channel.startsWith("ip")) {
	            return Integer.parseInt(channel.substring(2));
	        }
	        
	        // Handle legacy "a1", "a2" format if needed
	        if (channel != null && channel.startsWith("a")) {
	            return Integer.parseInt(channel.substring(1));
	        }
	        
	    } catch (Exception e) {
	        logger.warning("EventReactor: Could not parse channel number from: " + channel);
	    }
	    return 0; // default for regular IPs
	}

	/**
	 * Extract channel number from multicast address (e.g., 224.0.1.7 → 7)
	 * SAME LOGIC AS EventPublisher
	 */
	private int extractChannelNumber_x(String channel) {
	    try {
	        String[] parts = channel.split("\\.");
	        if (parts.length >= 4) {
	            return Integer.parseInt(parts[3]);
	        }
	    } catch (Exception e) {
	        System.err.println("EventReactor: Could not parse channel number from: " + channel);
	    }
	    return 1; // default
	}
	
	/**
	 * UDP message receiving loop with decompression and chunk reassembly
	 */
	private void receiveLoop() {
	    byte[] buffer = new byte[MAX_WIRE_LENGTH];
	    
	    while (running && !Thread.currentThread().isInterrupted()) {
	        try {
	            DatagramPacket servicePacket = new DatagramPacket(buffer, buffer.length);
	            serviceSocket.receive(servicePacket);
	            
	            // Periodic cleanup of expired chunk buffers
	            cleanupExpiredBuffersIfNeeded();
	            
	            // DEBUG: Log what we received
	            logger.fine("EventReactor: Received packet - " + servicePacket.getLength() + 
	                       " bytes from " + servicePacket.getAddress() + ":" + servicePacket.getPort());
	            
	            // Try to decompress/decode
	            String incomingPayload = null;
	            try {
	                incomingPayload = decompressPayload(servicePacket.getData(), servicePacket.getLength());
	                
	                if (incomingPayload == null || incomingPayload.trim().isEmpty()) {
	                    logger.warning("EventReactor: Received empty payload after decompression");
	                    continue;
	                }
	                
	            } catch (Exception e) {
	                logger.log(Level.WARNING, "EventReactor: Failed to decompress received payload", e);
	                continue;
	            }
	            
	            // Check if this is a chunked message
	            if (isChunkedPayload(incomingPayload)) {
	                // Handle chunk - may return reassembled payload or null if still waiting
	                String reassembledPayload = handleChunk(incomingPayload);
	                
	                if (reassembledPayload != null) {
	                    // All chunks received - process the complete message
	                    logger.info("EventReactor: Chunk reassembly complete, processing full payload (" + 
	                               reassembledPayload.length() + " chars)");
	                    putScheduledToken(reassembledPayload);
	                }
	                // If null, still waiting for more chunks - continue receiving
	                
	            } else {
	                // Regular (non-chunked) message - process directly
	                logger.fine("EventReactor: Processing non-chunked payload (" + 
	                           incomingPayload.length() + " chars)");
	                putScheduledToken(incomingPayload);
	            }
	            
	        } catch (SocketTimeoutException e) {
	            // Normal timeout, continue
	        } catch (IOException | InterruptedException ex) {
	            if (running) {
	                logger.log(Level.WARNING, "EventReactor: Error in receive loop", ex);
	            }
	        }
	    }
	}
	
	/**
	 * Check if payload is a chunked message envelope
	 */
	private boolean isChunkedPayload(String payload) {
	    // Quick check for chunk envelope markers
	    return payload.contains("\"chunkIndex\"") && 
	           payload.contains("\"totalChunks\"") && 
	           payload.contains("\"correlationId\"");
	}
	
	/**
	 * Handle an incoming chunk - add to buffer and return reassembled payload if complete
	 * 
	 * @param chunkEnvelope The JSON chunk envelope
	 * @return Reassembled payload if all chunks received, null if still waiting
	 */
	private String handleChunk(String chunkEnvelope) {
	    try {
	        // Parse chunk envelope (simple JSON parsing without external library)
	        int chunkIndex = extractIntFromJson(chunkEnvelope, "chunkIndex");
	        int totalChunks = extractIntFromJson(chunkEnvelope, "totalChunks");
	        String correlationId = extractStringFromJson(chunkEnvelope, "correlationId");
	        String chunkData = extractStringFromJson(chunkEnvelope, "chunkData");
	        String serviceType = extractStringFromJson(chunkEnvelope, "serviceType");
	        String operationName = extractStringFromJson(chunkEnvelope, "operationName");
	        
	        if (correlationId == null || chunkData == null) {
	            logger.warning("EventReactor: Invalid chunk envelope - missing correlationId or chunkData");
	            return null;
	        }
	        
	        // Unescape the chunk data
	        chunkData = unescapeJsonString(chunkData);
	        
	        logger.info("EventReactor: Received chunk " + (chunkIndex + 1) + "/" + totalChunks + 
	                   " for correlation " + correlationId.substring(0, 8) + "... (" + 
	                   chunkData.length() + " chars)");
	        
	        // Get or create chunk buffer
	        ChunkBuffer buffer = chunkBuffers.computeIfAbsent(correlationId, 
	            id -> new ChunkBuffer(totalChunks));
	        
	        // Store metadata from first chunk
	        if (chunkIndex == 0) {
	            buffer.serviceType = serviceType;
	            buffer.operationName = operationName;
	        }
	        
	        // Add chunk to buffer
	        boolean added = buffer.addChunk(chunkIndex, chunkData);
	        if (!added) {
	            logger.warning("EventReactor: Duplicate or invalid chunk index: " + chunkIndex);
	        }
	        
	        // Check if all chunks received
	        if (buffer.isComplete()) {
	            // Remove from pending buffers
	            chunkBuffers.remove(correlationId);
	            
	            // Reassemble and return
	            String reassembled = buffer.reassemble();
	            logger.info("EventReactor: Successfully reassembled " + totalChunks + 
	                       " chunks into " + reassembled.length() + " char payload");
	            
	            return reassembled;
	        } else {
	            logger.fine("EventReactor: Waiting for more chunks (" + buffer.receivedCount + 
	                       "/" + totalChunks + " received)");
	            return null;
	        }
	        
	    } catch (Exception e) {
	        logger.log(Level.WARNING, "EventReactor: Error handling chunk", e);
	        return null;
	    }
	}
	
	/**
	 * Extract an integer value from JSON string
	 */
	private int extractIntFromJson(String json, String key) {
	    try {
	        String pattern = "\"" + key + "\":";
	        int startIndex = json.indexOf(pattern);
	        if (startIndex < 0) return -1;
	        
	        startIndex += pattern.length();
	        
	        // Skip whitespace
	        while (startIndex < json.length() && Character.isWhitespace(json.charAt(startIndex))) {
	            startIndex++;
	        }
	        
	        // Read digits
	        int endIndex = startIndex;
	        while (endIndex < json.length() && Character.isDigit(json.charAt(endIndex))) {
	            endIndex++;
	        }
	        
	        if (endIndex > startIndex) {
	            return Integer.parseInt(json.substring(startIndex, endIndex));
	        }
	    } catch (Exception e) {
	        logger.warning("EventReactor: Failed to extract int for key: " + key);
	    }
	    return -1;
	}
	
	/**
	 * Extract a string value from JSON string
	 */
	private String extractStringFromJson(String json, String key) {
	    try {
	        String pattern = "\"" + key + "\":\"";
	        int startIndex = json.indexOf(pattern);
	        if (startIndex < 0) return null;
	        
	        startIndex += pattern.length();
	        
	        // Find closing quote (handle escaped quotes)
	        int endIndex = startIndex;
	        while (endIndex < json.length()) {
	            char c = json.charAt(endIndex);
	            if (c == '"' && (endIndex == startIndex || json.charAt(endIndex - 1) != '\\')) {
	                break;
	            }
	            endIndex++;
	        }
	        
	        if (endIndex > startIndex) {
	            return json.substring(startIndex, endIndex);
	        }
	    } catch (Exception e) {
	        logger.warning("EventReactor: Failed to extract string for key: " + key);
	    }
	    return null;
	}
	
	/**
	 * Unescape JSON string escape sequences
	 */
	private String unescapeJsonString(String input) {
	    if (input == null) return null;
	    
	    StringBuilder sb = new StringBuilder();
	    int i = 0;
	    while (i < input.length()) {
	        char c = input.charAt(i);
	        if (c == '\\' && i + 1 < input.length()) {
	            char next = input.charAt(i + 1);
	            switch (next) {
	                case '"':  sb.append('"'); i += 2; continue;
	                case '\\': sb.append('\\'); i += 2; continue;
	                case 'b':  sb.append('\b'); i += 2; continue;
	                case 'f':  sb.append('\f'); i += 2; continue;
	                case 'n':  sb.append('\n'); i += 2; continue;
	                case 'r':  sb.append('\r'); i += 2; continue;
	                case 't':  sb.append('\t'); i += 2; continue;
	                case 'u':
	                    if (i + 5 < input.length()) {
	                        try {
	                            int codePoint = Integer.parseInt(input.substring(i + 2, i + 6), 16);
	                            sb.append((char) codePoint);
	                            i += 6;
	                            continue;
	                        } catch (NumberFormatException e) {
	                            // Fall through to append as-is
	                        }
	                    }
	                    break;
	            }
	        }
	        sb.append(c);
	        i++;
	    }
	    return sb.toString();
	}
	
	/**
	 * Periodically cleanup expired chunk buffers
	 */
	private void cleanupExpiredBuffersIfNeeded() {
	    long now = System.currentTimeMillis();
	    if (now - lastCleanupTime > CLEANUP_INTERVAL_MS) {
	        lastCleanupTime = now;
	        
	        int removed = 0;
	        for (String correlationId : new ArrayList<>(chunkBuffers.keySet())) {
	            ChunkBuffer buffer = chunkBuffers.get(correlationId);
	            if (buffer != null && buffer.isExpired()) {
	                chunkBuffers.remove(correlationId);
	                removed++;
	                logger.warning("EventReactor: Expired incomplete chunk buffer for correlation " + 
	                              correlationId + " (" + buffer.receivedCount + "/" + 
	                              buffer.totalChunks + " chunks received)");
	            }
	        }
	        
	        if (removed > 0) {
	            logger.info("EventReactor: Cleaned up " + removed + " expired chunk buffers");
	        }
	    }
	}
	
	/**
	 * Properly shutdown the EventReactor and clean up UDP resources
	 */
	public void shutdown() {
		running = false;
		
		// Clear any pending chunk buffers
		int pendingChunks = chunkBuffers.size();
		if (pendingChunks > 0) {
		    logger.warning("EventReactor: Shutting down with " + pendingChunks + " incomplete chunk buffers");
		}
		chunkBuffers.clear();
		
		if (serviceSocket != null && !serviceSocket.isClosed()) {
			serviceSocket.close();
			System.out.println("EventReactor: UDP socket closed successfully");
		}
	}

	// ===== ADD TO Book_Monitor_EventGenerator.java =====
	// In sendUDPEvent() method, add this before sending:

	private static void sendUDPEvent(String payload, String originalAddress, String originalPort) throws IOException {
	    DatagramSocket udpSocket = null;
	    try {
	        udpSocket = new DatagramSocket();
	        
	        // === DEBUG: Show what we're sending ===
	        System.out.println("=== DEBUG: SENDING EVENT PAYLOAD ===");
	        System.out.println(payload);
	        System.out.println("=== END EVENT PAYLOAD ===");
	        
	        // Send to localhost instead of multicast address
	        InetAddress targetAddress = InetAddress.getLoopbackAddress();
	        
	        // Extract channel number from original multicast address (e.g., 224.0.1.1 → 1)
	        int channelNumber = 1; // default
	        try {
	            String[] addressParts = originalAddress.split("\\.");
	            if (addressParts.length >= 4) {
	                channelNumber = Integer.parseInt(addressParts[3]);
	            }
	        } catch (Exception e) {
	            System.err.println("Could not parse channel number from: " + originalAddress + ", using default 1");
	        }
	        
	        // Calculate EventReactor port using same logic as ServiceLoader
	        // EventReactor: 10000 + (channelNumber * 100) + basePort offset
	        int basePort = Integer.parseInt(originalPort);
	        int channelOffset = channelNumber * 100;
	        int targetPort = 10000 + channelOffset + (basePort % 100);
	        
	        byte[] data = payload.getBytes();
	        DatagramPacket dp = new DatagramPacket(data, data.length, targetAddress, targetPort);
	        
	        udpSocket.send(dp);
	        System.out.println("Sent UDP event to localhost:" + targetPort + 
	            " (EventReactor for channel " + channelNumber + ", original " + originalAddress + ":" + originalPort + ")");
	        
	    } finally {
	        if (udpSocket != null) {
	            udpSocket.close();
	        }
	    }
	}

	// ===== ADD TO EventReactor.java =====  
	// In putScheduledToken() method, add this at the beginning:

	/**
	 * Same scheduling logic as original EventReactor - WITH DEBUG
	 */
	public synchronized void putScheduledToken(String servicePacket) throws InterruptedException, IOException {
		// === DEBUG: Show what we received ===
	//	System.out.println("=== DEBUG: EVENTREACTOR RECEIVED ===");
	//	System.out.println(servicePacket);
	//	System.out.println("=== END RECEIVED PACKET ===");
		
		int queueAction = MAXQUEUE - costKeyTokenMap.size();
		ArrayList<Long> pArgs = null;
		
		try {
			pArgs = s2.prioritiseToken(queueAction, servicePacket);
			
			// === ADD POST-SCHEDULER DEBUG ===
			//System.out.println("=== POST-SCHEDULER DEBUG ===");
			System.out.println("Scheduler returned pArgs: " + pArgs);
			if (pArgs != null && pArgs.size() > 0) {
				System.out.println("pArgs.get(0): " + pArgs.get(0));
			}
			System.out.println("queueAction: " + queueAction);
			System.out.println("MAXQUEUE: " + MAXQUEUE);
			System.out.println("costKeyTokenMap size before: " + costKeyTokenMap.size());
			
		} catch (Exception e) {
			System.out.println("=== EXCEPTION in prioritiseToken ===");
			e.printStackTrace();
			return;
		}
		
		TreeMap<String, String> headerMap = new TreeMap<String, String>();
		TreeMap<String, String> serviceMap = new TreeMap<String, String>();
		TreeMap<String, String> monitorDataMap = new TreeMap<String, String>();
		
		if (pArgs.get(0) == -1) {
			lostEvents++;
			System.err.println("Discarded number of events: " + lostEvents);
			System.out.println("=== EVENT REJECTED BY SCHEDULER ===");
			return;
		}
		
		// Event accepted by Scheduler - now process it
		long sid = pArgs.get(1);
		long costKey = pArgs.get(0);
		
		System.out.println("=== EVENT ACCEPTED - PROCESSING ===");
		System.out.println("costKey: " + costKey);
		System.out.println("sid: " + sid);
		
		try {
			headerMap = xph.findMultipleXMLItems(servicePacket, "//header/*");
			serviceMap = xph.findMultipleXMLItems(servicePacket, "//service/*");
			monitorDataMap = xph.findMultipleXMLItems(servicePacket, "//monitorData/*");

		} catch (Exception e) {
			System.out.println("=== EXCEPTION in XPath parsing ===");
			e.printStackTrace();
			return;
		}
		
		// Update monitoring data
		monitorDataMap.put("eventArrivalTime", Long.toString(System.currentTimeMillis()));
		monitorDataMap.put("lostEvents", Long.toString(lostEvents));
		
		// Modify the service packet with updated monitoring data
		servicePacket = xph.modifyMultipleXMLItems(servicePacket, "//monitorData/*", monitorDataMap);
		servicePacket = xph.modifyMultipleXMLItems(servicePacket, "//headerMap/*", headerMap);

		// Add to processing queue
		System.out.println("=== ADDING TO PROCESSING QUEUE ===");
		System.out.println("About to add costKey " + costKey + " to costKeyTokenMap");
		
		costKeyTokenMap.put(costKey, servicePacket);
		
		System.out.println("Successfully added to costKeyTokenMap");
		System.out.println("costKeyTokenMap size after: " + costKeyTokenMap.size());
		System.out.println("About to call notify() to wake up ServiceThread");
		
		notify();
		
	//	System.out.println("Called notify() - ServiceThread should now wake up");
		
		int bufferSize = costKeyTokenMap.size();
		if (bufferSize > maxBufferSeen) {
			maxBufferSeen = bufferSize;
			System.err.println("Maximum Buffer size seen is = " + maxBufferSeen);
		}
		
		System.out.println("=== END EVENT PROCESSING ===");
	}

	// ===== ADD TO Scheduler.java =====
	// In prioritiseToken() method, add this debug code:

	public ArrayList<Long> prioritiseToken(int queueAction, String servicePacket) throws IOException {
	    long costKey = 0;

	    ArrayList<Long> returnArgs = new ArrayList<Long>();

	    TreeMap<String, String> headerMap = new TreeMap<String, String>();
	    XPathHelperCommon xph = new XPathHelperCommon();
	    TreeMap<String, String> attrMap = new TreeMap<String, String>();
	    
	    try {
	        headerMap = xph.findMultipleXMLItems(servicePacket, "//header/*");
	        attrMap = xph.findMultipleXMLItems(servicePacket, "//joinAttribute/*");
	        
	        // === DEBUG: Show what XPath found ===
	     //   System.out.println("=== DEBUG: SCHEDULER XPATH RESULTS ===");
	     //   System.out.println("headerMap: " + headerMap);
	      //  System.out.println("attrMap: " + attrMap);
	      //  System.out.println("=== END XPATH RESULTS ===");
	        
	        boolean priorityOrder = Boolean.parseBoolean(headerMap.get("priortiseSID"));

	        // ... rest of method
	    } catch (Exception e1) {
	        System.err.println("=== DEBUG: SCHEDULER EXCEPTION ===");
	        e1.printStackTrace();
	        System.err.println("=== END SCHEDULER EXCEPTION ===");
	    }
	    return returnArgs;
	}
	/**
	 * Same token retrieval logic as original EventReactor
	 */
	public synchronized TreeMap<Long, String> getScheduledToken() throws InterruptedException {
		long costKey;
		dataMap.clear();
		notify();
		while (costKeyTokenMap.size() == 0)
			wait();
		costKey = costKeyTokenMap.firstKey();
		dataMap.put(costKey, costKeyTokenMap.remove(costKey));
		return dataMap;
	}

	/**
	 * FIFO token retrieval
	 */
	public synchronized String getFIFOToken() throws InterruptedException {
		notify();
		while (tokenQueue.size() == 0)
			wait();
		return tokenQueue.remove();
	}
	
	/**
	 * Get current buffer size for marking analysis
	 * Returns the number of tokens currently in the priority queue
	 * 
	 * @return Current number of tokens waiting in buffer
	 */
	public synchronized int getQueueSize() {
		return costKeyTokenMap.size();
	}
	
	/**
	 * Get maximum buffer size observed
	 * 
	 * @return Peak buffer size during this run
	 */
	public synchronized int getMaxBufferSeen() {
		return maxBufferSeen;
	}
	
	/**
	 * Get configured maximum queue capacity
	 * 
	 * @return MAXQUEUE configuration value
	 */
	public int getMaxQueueCapacity() {
		return MAXQUEUE;
	}
	
	/**
	 * Get number of lost events due to buffer overflow
	 * 
	 * @return Count of events rejected by scheduler
	 */
	public synchronized int getLostEvents() {
		return lostEvents;
	}
	
	/**
	 * Get count of pending chunk buffers (for monitoring)
	 * 
	 * @return Number of incomplete chunked messages being reassembled
	 */
	public int getPendingChunkCount() {
	    return chunkBuffers.size();
	}
}