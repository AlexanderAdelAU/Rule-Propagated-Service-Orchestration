package org.btsn.handlers;

import java.io.*;
import java.net.*;
import java.util.TreeMap;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPOutputStream;

import org.btsn.utils.StringFileIO;
import org.btsn.utils.XPathHelperCommon;

import java.util.zip.GZIPInputStream;

/**
 * EventPublisher with local/remote support and automatic chunking
 * Expects ServiceThread to resolve "ip0" to actual IP addresses
 * 
 * ENHANCED: Now automatically chunks large payloads that exceed MAX_WIRE_LENGTH
 */
public class EventPublisher {

	private static final Logger logger = Logger.getLogger(EventPublisher.class.getName());

	// Configuration
	private static int MAX_WIRE_LENGTH = 4096;
	private static int SOCKET_TIMEOUT_MS = 5000;
	private static boolean COMPRESSION_ENABLED = true;
	
	// Chunking configuration
	// Reserve space for chunk envelope (JSON wrapper with metadata)
	// Envelope adds: {"chunkIndex":X,"totalChunks":XX,"correlationId":"UUID","chunkData":"..."}
	// Approximate overhead: ~120 bytes + base64 expansion (4/3 ratio for binary safety)
	private static final int CHUNK_ENVELOPE_OVERHEAD = 150;
	private static final int CHUNK_DELAY_MS = 10; // Small delay between chunks to avoid flooding

	// Shared socket for all publishes (avoids rapid socket creation/destruction issues with FORK)
	private DatagramSocket sharedSocket = null;
	private final Object socketLock = new Object();

	// Configuration helpers
	private XPathHelperCommon xph = new XPathHelperCommon();

	public EventPublisher() throws Exception {
		logger.info("=== Initializing EventPublisher ===");
		loadPublisherConfiguration();
		logger.info("EventPublisher: Ready for local/remote publishing");
		logger.info("EventPublisher: Compression enabled: " + COMPRESSION_ENABLED);
		logger.info("EventPublisher: Max wire length: " + MAX_WIRE_LENGTH + " bytes");
		logger.info("EventPublisher: Chunking enabled for payloads > " + MAX_WIRE_LENGTH + " bytes");
	}

	/**
	 * Main publishing method - expects resolved addresses from ServiceThread
	 * 
	 * @param targetChannel     Either IP address (192.168.1.152) or multicast (224.0.1.5)
	 * @param originalChannelId Original channel ID like "ip0", "ip1" before resolution
	 */
	public boolean publishServiceEvent(String serviceType, String operationName, String eventPayload,
			String targetChannel, String targetPort, String ruleVersion, String originalChannelId) {
		try {
			String eventId = generateEventId(serviceType, operationName);

			logger.info("EventPublisher: Publishing " + eventId + " to " + targetChannel + ":" + targetPort);

			// Check if payload needs chunking BEFORE compression
			// We check the raw payload size to determine if chunking is needed
			byte[] rawPayloadBytes = eventPayload.getBytes("UTF-8");
			
			if (rawPayloadBytes.length > getEffectivePayloadLimit()) {
				// Large payload - use chunking
				logger.info("EventPublisher: Payload size (" + rawPayloadBytes.length + 
				           " bytes) exceeds limit, using chunked transfer");
				return publishChunked(serviceType, operationName, eventPayload, 
				                      targetChannel, targetPort, eventId, originalChannelId);
			} else {
				// Small payload - send directly
				return publishDirectly(serviceType, operationName, eventPayload, 
				                       targetChannel, targetPort, eventId, originalChannelId);
			}

		} catch (Exception e) {
			logger.log(Level.SEVERE, "EventPublisher: Error publishing event", e);
			return false;
		}
	}
	
	/**
	 * Calculate effective payload limit accounting for compression overhead
	 * Compression typically achieves 3-5x reduction on JSON, so we're conservative
	 */
	private int getEffectivePayloadLimit() {
		// If compression is enabled, we can accept larger payloads
		// JSON typically compresses to 20-30% of original size
		// We use a conservative 3x factor
		if (COMPRESSION_ENABLED) {
			return MAX_WIRE_LENGTH * 3;
		}
		return MAX_WIRE_LENGTH - CHUNK_ENVELOPE_OVERHEAD;
	}

	/**
	 * Publish a large payload by splitting into chunks
	 * Each chunk is wrapped with metadata for reassembly on the receiver side
	 */
	private boolean publishChunked(String serviceType, String operationName, String eventPayload,
			String targetChannel, String targetPort, String eventId, String originalChannelId) {
		
		try {
			// Generate a unique correlation ID for this chunked transfer
			String correlationId = UUID.randomUUID().toString();
			
			// Calculate chunk size for the actual data portion
			// Account for the envelope overhead after compression
			int maxChunkDataSize = calculateMaxChunkDataSize();
			
			// Split payload into chunks
			byte[] payloadBytes = eventPayload.getBytes("UTF-8");
			int totalChunks = (int) Math.ceil((double) payloadBytes.length / maxChunkDataSize);
			
			logger.info("EventPublisher: Splitting payload (" + payloadBytes.length + 
			           " bytes) into " + totalChunks + " chunks of ~" + maxChunkDataSize + " bytes each");
			logger.info("EventPublisher: Correlation ID: " + correlationId);
			
			// Send each chunk
			for (int chunkIndex = 0; chunkIndex < totalChunks; chunkIndex++) {
				int startPos = chunkIndex * maxChunkDataSize;
				int endPos = Math.min(startPos + maxChunkDataSize, payloadBytes.length);
				
				// Extract chunk data
				byte[] chunkBytes = new byte[endPos - startPos];
				System.arraycopy(payloadBytes, startPos, chunkBytes, 0, chunkBytes.length);
				String chunkData = new String(chunkBytes, "UTF-8");
				
				// Build chunk envelope
				String chunkPayload = buildChunkEnvelope(chunkIndex, totalChunks, correlationId, 
				                                          chunkData, serviceType, operationName);
				
				// Send this chunk
				boolean sent = publishDirectly(serviceType, operationName, chunkPayload,
				                               targetChannel, targetPort, 
				                               eventId + "_chunk" + chunkIndex, originalChannelId);
				
				if (!sent) {
					logger.severe("EventPublisher: Failed to send chunk " + chunkIndex + "/" + totalChunks);
					return false;
				}
				
				logger.fine("EventPublisher: Sent chunk " + (chunkIndex + 1) + "/" + totalChunks + 
				           " (" + chunkBytes.length + " bytes)");
				
				// Small delay between chunks to avoid overwhelming the receiver
				if (chunkIndex < totalChunks - 1 && CHUNK_DELAY_MS > 0) {
					Thread.sleep(CHUNK_DELAY_MS);
				}
			}
			
			logger.info("EventPublisher: Successfully sent all " + totalChunks + " chunks for " + eventId);
			return true;
			
		} catch (Exception e) {
			logger.log(Level.SEVERE, "EventPublisher: Error in chunked publish", e);
			return false;
		}
	}
	
	/**
	 * Calculate the maximum size for chunk data
	 * Must account for: envelope JSON, compression ratio, and wire limit
	 */
	private int calculateMaxChunkDataSize() {
		// Start with wire limit
		int available = MAX_WIRE_LENGTH;
		
		// Subtract envelope overhead (the JSON wrapper for chunk metadata)
		available -= CHUNK_ENVELOPE_OVERHEAD;
		
		// If compression is enabled, we can fit more raw data
		// But we need to be conservative since compression ratio varies
		// JSON typically compresses well (3-5x), but we use 2x to be safe
		if (COMPRESSION_ENABLED) {
			available = available * 2;
		}
		
		// Ensure we have a reasonable minimum
		return Math.max(available, 1024);
	}
	
	/**
	 * Build JSON envelope for a chunk
	 * Format matches what MonitorService.handleChunkedData() expects
	 */
	private String buildChunkEnvelope(int chunkIndex, int totalChunks, String correlationId,
	                                   String chunkData, String serviceType, String operationName) {
		// Escape the chunk data for JSON embedding
		String escapedData = escapeJsonString(chunkData);
		
		StringBuilder sb = new StringBuilder();
		sb.append("{");
		sb.append("\"chunkIndex\":").append(chunkIndex).append(",");
		sb.append("\"totalChunks\":").append(totalChunks).append(",");
		sb.append("\"correlationId\":\"").append(correlationId).append("\",");
		sb.append("\"serviceType\":\"").append(serviceType).append("\",");
		sb.append("\"operationName\":\"").append(operationName).append("\",");
		sb.append("\"chunkData\":\"").append(escapedData).append("\"");
		sb.append("}");
		
		return sb.toString();
	}
	
	/**
	 * Escape special characters for JSON string embedding
	 */
	private String escapeJsonString(String input) {
		if (input == null) return "";
		
		StringBuilder sb = new StringBuilder();
		for (char c : input.toCharArray()) {
			switch (c) {
				case '"':  sb.append("\\\""); break;
				case '\\': sb.append("\\\\"); break;
				case '\b': sb.append("\\b"); break;
				case '\f': sb.append("\\f"); break;
				case '\n': sb.append("\\n"); break;
				case '\r': sb.append("\\r"); break;
				case '\t': sb.append("\\t"); break;
				default:
					if (c < ' ') {
						sb.append(String.format("\\u%04x", (int) c));
					} else {
						sb.append(c);
					}
			}
		}
		return sb.toString();
	}

	private boolean publishDirectly(String serviceType, String operationName, String eventPayload, 
	        String targetChannel, String targetPort, String eventId, String originalChannelId) {

	    try {
	        InetAddress targetAddress;
	        int finalPort;

	        // SIMPLIFIED: Only handle IP addresses now
	        if (!isIPAddress(targetChannel)) {
	            logger.severe("EventPublisher: Invalid target channel (must be IP address): " + targetChannel);
	            return false;
	        }
	        
	        targetAddress = InetAddress.getByName(targetChannel);
	        int basePort = Integer.parseInt(targetPort);
	        
	        // Extract channel number from original channel ID
	        int channelNumber = extractChannelNumberFromId(originalChannelId);
	        finalPort = 10000 + (channelNumber * 1000) + basePort;
	        
	        logger.fine("EventPublisher: Target " + targetAddress.getHostAddress() + ":" + finalPort + 
	                   " (from " + originalChannelId + ")");

	        // Compress payload
	        byte[] payloadBytes = preparePayload(eventPayload, eventId);

	        if (payloadBytes.length > MAX_WIRE_LENGTH) {
	            // This shouldn't happen if chunking is working correctly
	            // But as a safety net, log an error
	            logger.severe("EventPublisher: Compressed payload still too large (" + 
	                         payloadBytes.length + " bytes > " + MAX_WIRE_LENGTH + " limit). " +
	                         "This indicates a chunking calculation error.");
	            return false;
	        }

	        // Use shared socket (thread-safe) - avoids rapid socket creation issues with FORK
	        synchronized (socketLock) {
	            if (sharedSocket == null || sharedSocket.isClosed()) {
	                sharedSocket = new DatagramSocket();
	                sharedSocket.setSoTimeout(SOCKET_TIMEOUT_MS);
	                logger.info("EventPublisher: Created shared socket on port " + sharedSocket.getLocalPort());
	            }

	            // Send packet
	            DatagramPacket packet = new DatagramPacket(payloadBytes, payloadBytes.length, targetAddress, finalPort);
	            sharedSocket.send(packet);
	        }

	        logger.fine("EventPublisher: Sent " + eventId + " to " + targetAddress.getHostAddress() + ":" + finalPort
	                + " (" + payloadBytes.length + " bytes)");
	        return true;

	    } catch (Exception e) {
	        logger.log(Level.SEVERE, "EventPublisher: Failed to send " + eventId, e);
	        return false;
	    }
	}
	
	private int extractChannelNumberFromId(String channelId) {
		if (channelId != null && channelId.startsWith("ip")) {
			try {
				return Integer.parseInt(channelId.substring(2));
			} catch (NumberFormatException e) {
				logger.warning("EventPublisher: Could not parse channel number from ID: " + channelId);
			}
		}
		return 0;
	}

	private boolean isIPAddress(String value) {
	    if (value == null) return false;

	    String[] parts = value.split("\\.");
	    if (parts.length != 4) return false;

	    try {
	        for (String part : parts) {
	            int num = Integer.parseInt(part);
	            if (num < 0 || num > 255) return false;
	        }
	        return true;
	    } catch (NumberFormatException e) {
	        return false;
	    }
	}
	
	private int extractChannelNumber(String channel) {
		try {
			String[] parts = channel.split("\\.");
			if (parts.length >= 4) {
				return Integer.parseInt(parts[3]);
			}
		} catch (Exception e) {
			logger.warning("EventPublisher: Could not parse channel number from: " + channel);
		}
		return 1;
	}

	private byte[] preparePayload(String eventPayload, String eventId) throws IOException {
		if (COMPRESSION_ENABLED) {
			try {
				byte[] compressed = compressPayload(eventPayload);

				// Validate compression
				String testDecompressed = decompressPayload(compressed);
				if (!testDecompressed.equals(eventPayload)) {
					logger.warning("EventPublisher: Compression validation failed");
					return eventPayload.getBytes("UTF-8");
				}
				return compressed;

			} catch (IOException e) {
				logger.warning("EventPublisher: Compression failed, sending uncompressed");
				return eventPayload.getBytes("UTF-8");
			}
		} else {
			return eventPayload.getBytes("UTF-8");
		}
	}

	private byte[] compressPayload(String payload) throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try (GZIPOutputStream gzipOut = new GZIPOutputStream(baos)) {
			gzipOut.write(payload.getBytes("UTF-8"));
			gzipOut.finish();
		}
		return baos.toByteArray();
	}

	private String decompressPayload(byte[] compressedData) throws IOException {
		ByteArrayInputStream bais = new ByteArrayInputStream(compressedData);
		try (GZIPInputStream gzipIn = new GZIPInputStream(bais);
				ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

			byte[] buffer = new byte[1024];
			int len;
			while ((len = gzipIn.read(buffer)) != -1) {
				baos.write(buffer, 0, len);
			}
			return baos.toString("UTF-8");
		}
	}

	private void loadPublisherConfiguration() {
		try {
			File commonBase = new File("./");
			String commonPath = commonBase.getCanonicalPath();
			String serviceLoaderDirectory = commonPath + "/ServiceLoaderQueries/";
			String loaderSettings = serviceLoaderDirectory + "loaderSettings.xml";

			String xmlSettings = StringFileIO.readFileAsString(loaderSettings);
			TreeMap<String, String> settingsMap = xph.findMultipleXMLItems(xmlSettings, "//PublisherSettings/*");

			if (settingsMap.containsKey("maxWireLength")) {
				MAX_WIRE_LENGTH = Integer.valueOf(settingsMap.get("maxWireLength"));
			}
			if (settingsMap.containsKey("socketTimeout")) {
				SOCKET_TIMEOUT_MS = Integer.valueOf(settingsMap.get("socketTimeout"));
			}
			if (settingsMap.containsKey("compressionEnabled")) {
				COMPRESSION_ENABLED = Boolean.valueOf(settingsMap.get("compressionEnabled"));
			}

			logger.info("EventPublisher: Config - MaxWireLength: " + MAX_WIRE_LENGTH + ", SocketTimeout: "
					+ SOCKET_TIMEOUT_MS + "ms");

		} catch (Exception e) {
			logger.info("EventPublisher: Using default configuration");
			MAX_WIRE_LENGTH = 4096;
			SOCKET_TIMEOUT_MS = 5000;
			COMPRESSION_ENABLED = true;
		}
	}

	private String generateEventId(String serviceType, String operationName) {
		return "EVT_" + serviceType.toUpperCase() + "_" + operationName.toUpperCase() + "_" + System.currentTimeMillis()
				+ "_" + (int) (Math.random() * 1000);
	}

	public void shutdown() {
		synchronized (socketLock) {
			if (sharedSocket != null && !sharedSocket.isClosed()) {
				sharedSocket.close();
				sharedSocket = null;
				logger.info("EventPublisher: Closed shared socket");
			}
		}
		logger.info("EventPublisher: Shutdown complete");
	}
}