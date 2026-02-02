package org.btsn.handlers;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import org.apache.log4j.Logger;
import org.btsn.json.jsonLibrary;
import org.json.simple.JSONObject;

/**
 * ServiceHelper - Smart Service Invocation with Token Enrichment
 * 
 * RESPONSIBILITY:
 * - Invoke services via reflection (existing)
 * - Extract infrastructure metadata from tokens (NEW)
 * - Provide clean business data to services (NEW)
 * - Enrich service results with metadata (NEW)
 * 
 * PRINCIPLE: Services receive ONLY business data, return ONLY business results.
 *            All infrastructure concerns handled here in the invocation layer.
 * 
 * ARCHITECTURE:
 * ServiceThread (orchestrator) 
 *   ServiceHelper (smart wrapper - handles token enrichment)
 *     Service (pure business logic)
 *     
 * FIX: Now properly handles JOIN nodes with multiple arguments (e.g., 3 tokens)
 * 
 * @author ACameron
 */
public class ServiceHelper {
	private static final Logger logger = Logger.getLogger(ServiceHelper.class);
	
	private static final ThreadLocal<String> returnType = new ThreadLocal<>();

	private static final Map<Class<?>, String> TYPE_TO_NAME = new HashMap<>();
	private static final Map<String, Class<?>> NAME_TO_TYPE = new HashMap<>();

	// Infrastructure metadata field names
	private static final String ORIGINAL_TOKEN = "original_token";
	private static final String WORKFLOW_START_TIME = "workflow_start_time";
	private static final String SERVICE_START_TIME = "service_start_time";
	private static final String SERVICE_END_TIME = "service_end_time";
	private static final String SERVICE_PROCESSING_TIME = "service_processing_time_ms";
	
	// Fork/Join synchronization now uses token ID encoding instead of payload fields
	// Token ID format: parentTokenId + (joinCount * 100) + branchNumber

	static {
		TYPE_TO_NAME.put(boolean.class, "boolean");
		TYPE_TO_NAME.put(byte.class, "byte");
		TYPE_TO_NAME.put(short.class, "short");
		TYPE_TO_NAME.put(int.class, "int");
		TYPE_TO_NAME.put(long.class, "long");
		TYPE_TO_NAME.put(float.class, "float");
		TYPE_TO_NAME.put(double.class, "double");
		TYPE_TO_NAME.put(String.class, "String");

		NAME_TO_TYPE.put("boolean", boolean.class);
		NAME_TO_TYPE.put("byte", byte.class);
		NAME_TO_TYPE.put("short", short.class);
		NAME_TO_TYPE.put("int", int.class);
		NAME_TO_TYPE.put("long", long.class);
		NAME_TO_TYPE.put("float", float.class);
		NAME_TO_TYPE.put("double", double.class);
		NAME_TO_TYPE.put("String", String.class);
	}

	public static String getReturnType() {
		return returnType.get();
	}

	/**
	 * ServiceResult - Enhanced to include timing metadata
	 */
	public static class ServiceResult {
		private final String result;
		private final String returnType;
		private final TokenMetadata metadata;  // NEW: Include metadata for timing recording

		public ServiceResult(String result, String returnType) {
			this(result, returnType, null);
		}
		
		public ServiceResult(String result, String returnType, TokenMetadata metadata) {
			this.result = result;
			this.returnType = returnType;
			this.metadata = metadata;
		}

		public String getResult() {
			return result;
		}

		public String getReturnType() {
			return returnType;
		}
		
		public TokenMetadata getMetadata() {
			return metadata;
		}
	}

	/**
	 * Main service invocation entry point - NOW WITH TOKEN ENRICHMENT
	 * FIXED: Properly handles JOIN nodes with multiple arguments
	 * 
	 * @param sequenceID Workflow sequence ID
	 * @param service Service class name
	 * @param operation Operation name
	 * @param inputArgs Input arguments (can be 1 for EdgeNode, 3+ for JoinNode)
	 * @param outputAttributeName Output attribute name from canonicalBinding (e.g., "triageResults")
	 * @return ServiceResult with enriched output
	 */
	public ServiceResult process(String sequenceID, String service, String operation, 
	                            ArrayList<?> inputArgs, String outputAttributeName) {
		String returnTypeStr = null;
		TokenMetadata metadata = null;
		
		try {
			logger.info("=== SERVICE INVOCATION START ===");
			logger.info("Service: " + service + "." + operation);
			logger.info("SequenceID: " + sequenceID);
			logger.info("Input arguments count: " + inputArgs.size());
			
			// ================================================================
			// PHASE 1: EXTRACT METADATA AND CLEAN BUSINESS DATA (ALL ARGUMENTS)
			// ================================================================
			
			// FIX: Process ALL input arguments (critical for JOIN nodes with multiple inputs)
			ArrayList<String> cleanedArguments = new ArrayList<>();
			
			for (int i = 0; i < inputArgs.size(); i++) {
				String rawToken = inputArgs.get(i).toString();
				logger.debug("Processing argument " + (i+1) + "/" + inputArgs.size() + 
				            ", raw length: " + rawToken.length());
				
				// Extract infrastructure metadata (from first token primarily)
				if (i == 0) {
					metadata = extractMetadata(rawToken);
					
					// Initialize workflow start time if this is first service
					if (metadata.workflowStartTime == null) {
						metadata.workflowStartTime = System.currentTimeMillis();
						logger.info("ENRICHMENT: Initialized workflow start time (first service)");
					}
				} else {
					// For additional tokens (JOIN case), verify/preserve metadata consistency
					TokenMetadata additionalMetadata = extractMetadata(rawToken);
					if (additionalMetadata.workflowStartTime != null && metadata.workflowStartTime == null) {
						metadata.workflowStartTime = additionalMetadata.workflowStartTime;
						logger.debug("ENRICHMENT: Using workflow start time from token " + (i+1));
					}
				}
				
				// Extract CLEAN business data (remove all infrastructure fields)
				String cleanBusinessData = extractBusinessData(rawToken);
				cleanedArguments.add(cleanBusinessData);
				logger.debug("ENRICHMENT: Cleaned argument " + (i+1) + ", length: " + cleanBusinessData.length() + 
				            " (removed " + (rawToken.length() - cleanBusinessData.length()) + " bytes of metadata)");
			}
			
			logger.info("ENRICHMENT: Extracted " + cleanedArguments.size() + 
			           " clean business data arguments (removed infrastructure metadata)");
			
			// ================================================================
			// PHASE 2: INVOKE SERVICE WITH CLEAN DATA
			// ================================================================
			
			// Capture service start time
			long serviceStartTime = System.currentTimeMillis();
			metadata.serviceStartTime = serviceStartTime;
			
			logger.info("SERVICE INVOKE: Calling " + service + "." + operation + 
			           " with " + cleanedArguments.size() + " CLEAN business data arguments");
			
			// FIX: Invoke service with ALL CLEAN data arguments (supports JOIN with multiple inputs)
			String serviceResult = invokeServiceMethod(sequenceID, service, operation, cleanedArguments);
			
			// Capture service end time
			long serviceEndTime = System.currentTimeMillis();
			metadata.serviceEndTime = serviceEndTime;
			
			long processingTime = serviceEndTime - serviceStartTime;
			logger.info("SERVICE COMPLETE: " + service + "." + operation + 
			           " completed in " + processingTime + "ms");
			
			// ================================================================
			// PHASE 3: ENRICH RESULT WITH INFRASTRUCTURE METADATA
			// ================================================================
			
			String enrichedResult = enrichServiceResult(serviceResult, metadata, outputAttributeName);
			logger.info("ENRICHMENT: Successfully enriched service result with metadata");
			
			logger.info("=== SERVICE INVOCATION COMPLETE ===");
			
			return new ServiceResult(enrichedResult, "String", metadata);

		} catch (Exception e) {
			logger.error("SERVICE ERROR: Failed to invoke " + service + "." + operation, e);
			throw new RuntimeException("Service invocation failed", e);
		} finally {
			returnType.remove();
		}
	}

	/**
	 * LEGACY METHOD - for backward compatibility
	 * Calls new enriched version with inferred outputAttributeName
	 */
	public ServiceResult process(String sequenceID, String service, String operation, 
	                            ArrayList<?> inputArgs, String outputAttributeName, String buildVersion) {
		// buildVersion is preserved for constructor patterns but outputAttributeName takes precedence
		// If outputAttributeName is null or "null", try to infer it
		String effectiveOutputAttr = outputAttributeName;
		if (effectiveOutputAttr == null || "null".equals(effectiveOutputAttr)) {
			effectiveOutputAttr = inferOutputAttributeName(operation);
		}
		return process(sequenceID, service, operation, inputArgs, effectiveOutputAttr);
	}

	// ========================================================================
	// TOKEN ENRICHMENT METHODS
	// ========================================================================

	/**
	 * Extract infrastructure metadata from incoming token
	 * 
	 * Handles both direct tokens and place-wrapped tokens:
	 * - Direct: {"tokenId": "...", "workflow_start_time": ...}
	 * - Wrapped: {"P1": {"tokenId": "...", "workflow_start_time": ...}}
	 */
	private TokenMetadata extractMetadata(String rawToken) {
		TokenMetadata metadata = new TokenMetadata();
		
		try {
			JSONObject tokenJson = jsonLibrary.parseString(rawToken);
			if (tokenJson == null) {
				logger.warn("ENRICHMENT: Failed to parse token, creating empty metadata");
				return metadata;
			}
			
			// Unwrap place response if needed
			JSONObject tokenData = unwrapPlaceResponse(tokenJson);
			
			// Extract original token (if exists)
			if (tokenData.containsKey(ORIGINAL_TOKEN)) {
				metadata.originalToken = tokenData.get(ORIGINAL_TOKEN).toString();
				logger.debug("ENRICHMENT: Found existing original_token to preserve");
			} else {
				// First service - current token becomes original token
				metadata.originalToken = rawToken;
				logger.debug("ENRICHMENT: First service - raw token IS original_token");
			}
			
			// Extract workflow start time from unwrapped token
			if (tokenData.containsKey(WORKFLOW_START_TIME)) {
				Object wfStart = tokenData.get(WORKFLOW_START_TIME);
				if (wfStart instanceof Long) {
					metadata.workflowStartTime = (Long) wfStart;
				} else if (wfStart != null) {
					metadata.workflowStartTime = Long.parseLong(wfStart.toString());
				}
				logger.debug("ENRICHMENT: Extracted workflow_start_time=" + metadata.workflowStartTime);
			}
			
			// Fork/Join synchronization now uses token ID encoding - no payload fields to extract
				
			} catch (Exception e) {
				logger.error("ENRICHMENT: Error extracting metadata", e);
			}
			
			return metadata;
		}

	/**
	 * Unwrap place-wrapped token responses
	 * 
	 * Tokens flowing through the Petri Net get wrapped by each place:
	 * - After P1: {"P1": {"workflow_start_time": ..., ...}}
	 * - After P2: {"P2": {"workflow_start_time": ..., ...}}
	 * 
	 * This method detects and unwraps these to access the actual token data.
	 * 
	 * @param json The potentially wrapped JSON object
	 * @return The unwrapped token data (or original if not wrapped)
	 */
	private JSONObject unwrapPlaceResponse(JSONObject json) {
		if (json == null) return json;
		
		// Check if this looks like a place-wrapped response:
		// - Has exactly one key
		// - That key's value is a JSONObject
		if (json.keySet().size() == 1) {
			String key = json.keySet().iterator().next().toString();
			Object value = json.get(key);
			
			if (value instanceof JSONObject) {
				JSONObject inner = (JSONObject) value;
				
				// Verify this is a token wrapper by checking for common token fields
				if (inner.containsKey("tokenId") || 
				    inner.containsKey("version") || 
				    inner.containsKey(WORKFLOW_START_TIME) ||
				    inner.containsKey(ORIGINAL_TOKEN) ||
				    inner.containsKey("status")) {
					
					logger.debug("ENRICHMENT: Unwrapped place response from key: " + key);
					return inner;
				}
			}
		}
		
		// Not a wrapped response, return as-is
		return json;
	}

	/**
	 * Extract clean business data (remove all infrastructure metadata)
	 */
	private String extractBusinessData(String rawToken) {
		try {
			JSONObject tokenJson = jsonLibrary.parseString(rawToken);
			if (tokenJson == null) {
				logger.warn("ENRICHMENT: Failed to parse token, returning as-is");
				return rawToken;
			}
			
			// Create clean copy without infrastructure fields
			JSONObject cleanToken = new JSONObject();
			for (Object key : tokenJson.keySet()) {
				String keyStr = key.toString();
				
				// Skip infrastructure metadata fields
				if (!isInfrastructureField(keyStr)) {
					cleanToken.put(keyStr, tokenJson.get(keyStr));
				}
			}
			
			return cleanToken.toJSONString();
			
		} catch (Exception e) {
			logger.error("ENRICHMENT: Error extracting business data", e);
			return rawToken;
		}
	}

	/**
	 * Enrich service result with infrastructure metadata
	 */
	@SuppressWarnings("unchecked")
	private String enrichServiceResult(String serviceResult, TokenMetadata metadata, 
	                                   String outputAttributeName) {
		try {
			JSONObject resultJson = jsonLibrary.parseString(serviceResult);
			if (resultJson == null) {
				logger.error("ENRICHMENT: Failed to parse service result");
				return serviceResult;
			}
			
			// Get the business results object
			// Try the specified outputAttributeName first
			String actualKey = outputAttributeName;
			if (!resultJson.containsKey(outputAttributeName)) {
				// Fallback: If there's exactly one key, use it (wrapped place response like {"P2":{...}})
				if (resultJson.size() == 1) {
					actualKey = resultJson.keySet().iterator().next().toString();
					logger.debug("ENRICHMENT: Using wrapped response key '" + actualKey + 
					            "' instead of '" + outputAttributeName + "'");
				} else {
					logger.warn("ENRICHMENT: Output attribute '" + outputAttributeName + 
					           "' not found in service result, cannot enrich");
					return serviceResult;
				}
			}
			
			Object innerObj = resultJson.get(actualKey);
			if (!(innerObj instanceof JSONObject)) {
				logger.warn("ENRICHMENT: Inner object is not a JSONObject, cannot enrich");
				return serviceResult;
			}
			
			JSONObject businessResults = (JSONObject) innerObj;
			
			// Create enriched results with metadata FIRST
			JSONObject enrichedResults = new JSONObject();
			
			// 1. Add original token (FIRST - most important)
			if (metadata.originalToken != null) {
				enrichedResults.put(ORIGINAL_TOKEN, metadata.originalToken);
			}
			
			// 2. Add workflow timing
			if (metadata.workflowStartTime != null) {
				enrichedResults.put(WORKFLOW_START_TIME, metadata.workflowStartTime);
			}
			
			// 3. Add service timing
			if (metadata.serviceStartTime != null && metadata.serviceEndTime != null) {
				enrichedResults.put(SERVICE_START_TIME, metadata.serviceStartTime);
				enrichedResults.put(SERVICE_END_TIME, metadata.serviceEndTime);
				enrichedResults.put(SERVICE_PROCESSING_TIME, 
				                   metadata.serviceEndTime - metadata.serviceStartTime);
			}
			
			// Fork/Join synchronization now uses token ID encoding - no payload fields to preserve
				
			// 4. Add business results (preserving service output)
			for (Object key : businessResults.keySet()) {
				String keyStr = key.toString();
				if (!isInfrastructureField(keyStr)) {
					enrichedResults.put(keyStr, businessResults.get(keyStr));
				}
			}
			
			// Replace with enriched version
			resultJson.put(actualKey, enrichedResults);
			
			return resultJson.toJSONString();
			
		} catch (Exception e) {
			logger.error("ENRICHMENT: Error enriching result", e);
			return serviceResult;
		}
	}

	/**
	 * Check if field is infrastructure metadata
	 * 
	 * NOTE: workflow_start_time is NOT stripped - it must flow through to MonitorService
	 * so that acknowledgeTokenArrival can extract it and write to PROCESSMEASUREMENTS.
	 * Only fields added by ServiceHelper itself during processing are stripped.
	 */
	private boolean isInfrastructureField(String fieldName) {
		return fieldName.equals(ORIGINAL_TOKEN) ||
		       // REMOVED: fieldName.equals(WORKFLOW_START_TIME) - needed by MonitorService.acknowledgeTokenArrival
		       fieldName.equals(SERVICE_START_TIME) ||
		       fieldName.equals(SERVICE_END_TIME) ||
		       fieldName.equals(SERVICE_PROCESSING_TIME);
	}

	/**
	 * Infer output attribute name from operation name
	 * Convention: processXxxAssessment → xxxResults
	 */
	private String inferOutputAttributeName(String operation) {
		// processTriageAssessment → triageResults
		// processClinicalDecision → clinicalResults
		// fireTransition → transitionResults (Petri Net specific)
		// etc.
		
		if (operation.startsWith("process")) {
			String base = operation.substring(7); // Remove "process"
			if (base.endsWith("Assessment")) {
				base = base.substring(0, base.length() - 10); // Remove "Assessment"
			} else if (base.endsWith("Decision")) {
				base = base.substring(0, base.length() - 8); // Remove "Decision"
			} else if (base.endsWith("Request")) {
				base = base.substring(0, base.length() - 7); // Remove "Request"
			}
			// Convert to camelCase and add "Results"
			return base.substring(0, 1).toLowerCase() + base.substring(1) + "Results";
		}
		
		// Petri Net specific patterns
		if (operation.startsWith("fire")) {
			String base = operation.substring(4); // Remove "fire"
			return base.substring(0, 1).toLowerCase() + base.substring(1) + "Results";
		}
		
		if (operation.startsWith("collect")) {
			String base = operation.substring(7); // Remove "collect"
			return base.substring(0, 1).toLowerCase() + base.substring(1) + "Results";
		}
		
		// Default fallback
		return "results";
	}

	// ========================================================================
	// SERVICE INVOCATION (REFLECTION LOGIC - FIXED FOR MULTI-ARGUMENT SUPPORT)
	// ========================================================================

	/**
	 * Invoke service method via reflection
	 * FIXED: Now handles multiple clean business data arguments for JOIN nodes
	 * 
	 * @param sequenceID Workflow sequence ID
	 * @param service Service class name
	 * @param operation Operation name
	 * @param cleanedArguments List of CLEAN business data (1 for EdgeNode, 3+ for JoinNode)
	 * @return Service result as String
	 */
	private String invokeServiceMethod(String sequenceID, String service, 
	                                   String operation, ArrayList<String> cleanedArguments) 
			throws Exception {
		
		logger.debug("INVOKE: Reflecting on " + service + "." + operation);
		logger.debug("INVOKE: Looking for method with " + cleanedArguments.size() + " String parameters");
		
		Class<?> serviceClass = Class.forName(service);
		
		// Check for singleton pattern first
		Object serviceInstance = null;
		boolean isSingleton = false;
		try {
			Method getInstanceMethod = serviceClass.getMethod("getInstance");
			if (java.lang.reflect.Modifier.isStatic(getInstanceMethod.getModifiers())) {
				serviceInstance = getInstanceMethod.invoke(null);
				isSingleton = true;
				logger.debug("INVOKE: Using singleton instance");
			}
		} catch (NoSuchMethodException e) {
			// Not a singleton, use constructor
		}
		
		// Create instance if not singleton
		if (serviceInstance == null) {
			serviceInstance = createServiceInstance(serviceClass, sequenceID, service);
		}

		// Find target method
		Method targetMethod = null;
		
		// FIX: For singleton, try (sequenceID, arg1, arg2, ...) signature first
		if (isSingleton) {
			int expectedParams = cleanedArguments.size() + 1; // +1 for sequenceID
			for (Method method : serviceClass.getMethods()) {
				if (method.getName().equals(operation)) {
					Class<?>[] paramTypes = method.getParameterTypes();
					// Check if all params are String and count matches
					if (paramTypes.length == expectedParams && allParamsAreString(paramTypes)) {
						targetMethod = method;
						logger.debug("INVOKE: Found singleton method (sequenceID, " + cleanedArguments.size() + " args)");
						break;
					}
				}
			}
		}
		
		// FIX: Find method with N String parameters (standard pattern for N arguments)
		if (targetMethod == null) {
			for (Method method : serviceClass.getMethods()) {
				if (method.getName().equals(operation)) {
					Class<?>[] paramTypes = method.getParameterTypes();
					// Check if parameter count matches and all are Strings
					if (paramTypes.length == cleanedArguments.size() && allParamsAreString(paramTypes)) {
						targetMethod = method;
						logger.debug("INVOKE: Found standard method (" + cleanedArguments.size() + " String args)");
						break;
					}
				}
			}
		}

		if (targetMethod == null) {
			throw new NoSuchMethodException("Operation " + operation + 
			                               " not found in service " + service + 
			                               " with " + cleanedArguments.size() + " String parameters");
		}

		// Prepare arguments
		Object[] args;
		Class<?>[] paramTypes = targetMethod.getParameterTypes();
		
		if (isSingleton && paramTypes.length == cleanedArguments.size() + 1) {
			// Singleton with (sequenceID, arg1, arg2, ...)
			args = new Object[paramTypes.length];
			args[0] = sequenceID;
			for (int i = 0; i < cleanedArguments.size(); i++) {
				args[i + 1] = cleanedArguments.get(i);
			}
		} else {
			// Standard with (arg1, arg2, ...)
			args = new Object[cleanedArguments.size()];
			for (int i = 0; i < cleanedArguments.size(); i++) {
				args[i] = cleanedArguments.get(i);
			}
		}

		// Invoke method
		logger.debug("INVOKE: Calling " + targetMethod.getName() + " with " + args.length + " arguments");
		Object result = targetMethod.invoke(serviceInstance, args);
		
		if (result == null) {
			throw new IllegalStateException("Service returned null result");
		}

		return result.toString();
	}
	
	/**
	 * Create service instance using multiple constructor patterns
	 * Supports:
	 * - 3-param (sequenceID, placeName, buildVersion) - for collector services
	 * - 2-param (sequenceID, serviceName) - for places with rule base config
	 * - 1-param (sequenceID) - for regular services
	 * - no-arg () - fallback
	 */
	private Object createServiceInstance(Class<?> serviceClass, String sequenceID, String service) 
			throws Exception {
		
		Object serviceInstance = null;
		boolean instanceCreated = false;
		String serviceName = deriveServiceName(service);
		
		// PATTERN 1: Try 3-parameter constructor (sequenceID, placeName, buildVersion) - for collectors
		// Note: buildVersion is derived from serviceName convention or uses default
		try {
			Constructor<?> constructor = serviceClass.getConstructor(String.class, String.class, String.class);
			
			// Derive placeName and buildVersion for collector pattern
			String placeName = derivePlaceNameFromCollector(serviceName);
			String buildVersion = "v001";  // Default version - collectors query rule base themselves
			
			serviceInstance = constructor.newInstance(sequenceID, placeName, buildVersion);
			instanceCreated = true;
			logger.debug("INVOKE: Created instance using 3-param constructor (sequenceID, placeName, buildVersion) for " + service);
		} catch (NoSuchMethodException e) {
			// Pattern 1 not available, try next pattern
		}
		
		// PATTERN 2: Try 2-parameter constructor (sequenceID, serviceName) - for places with rule base config
		if (!instanceCreated) {
			try {
				Constructor<?> constructor = serviceClass.getConstructor(String.class, String.class);
				serviceInstance = constructor.newInstance(sequenceID, serviceName);
				instanceCreated = true;
				logger.debug("INVOKE: Created instance using 2-param constructor (sequenceID, serviceName) for " + service);
			} catch (NoSuchMethodException e) {
				// Pattern 2 not available, try next pattern
			}
		}
		
		// PATTERN 3: Try 1-parameter constructor (sequenceID) - for regular services
		if (!instanceCreated) {
			try {
				Constructor<?> constructor = serviceClass.getConstructor(String.class);
				serviceInstance = constructor.newInstance(sequenceID);
				instanceCreated = true;
				logger.debug("INVOKE: Created instance using 1-param constructor (sequenceID) for " + service);
			} catch (NoSuchMethodException e) {
				// Pattern 3 not available
			}
		}
		
		// PATTERN 4: Try no-arg constructor as last resort
		if (!instanceCreated) {
			try {
				Constructor<?> constructor = serviceClass.getConstructor();
				serviceInstance = constructor.newInstance();
				instanceCreated = true;
				logger.debug("INVOKE: Created instance using no-arg constructor for " + service);
			} catch (NoSuchMethodException e) {
				// No suitable constructor found
			}
		}
		
		if (!instanceCreated) {
			throw new NoSuchMethodException("No suitable constructor found for " + service + 
				". Tried: (String,String,String), (String,String), (String), and ()");
		}
		
		return serviceInstance;
	}
	
	/**
	 * Derive place name from collector service name
	 * e.g., P1_CollectorService -> P1_Place
	 * e.g., P2_CollectorService -> P2_Place
	 */
	private String derivePlaceNameFromCollector(String serviceName) {
		// Pattern: X_CollectorService -> X_Place
		if (serviceName.endsWith("_CollectorService")) {
			String prefix = serviceName.substring(0, serviceName.indexOf("_CollectorService"));
			return prefix + "_Place";
		}
		
		// Pattern: XCollectorService -> XPlace
		if (serviceName.endsWith("CollectorService")) {
			String prefix = serviceName.substring(0, serviceName.indexOf("CollectorService"));
			return prefix + "Place";
		}
		
		// Fallback: use service name as-is
		return serviceName;
	}
	
	/**
	 * Helper: Check if all parameter types are String
	 */
	private boolean allParamsAreString(Class<?>[] paramTypes) {
		for (Class<?> paramType : paramTypes) {
			if (paramType != String.class) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Derive service name from fully qualified class name
	 * e.g., org.btsn.places.P1_Place -> P1_Place
	 * e.g., org.btsn.services.P2_CollectorService -> P2_CollectorService
	 */
	private String deriveServiceName(String serviceClassName) {
		// Get simple class name (without package)
		String simpleName = serviceClassName;
		if (serviceClassName.contains(".")) {
			simpleName = serviceClassName.substring(serviceClassName.lastIndexOf('.') + 1);
		}
		return simpleName;
	}

	// ========================================================================
	// INNER CLASSES
	// ========================================================================

	/**
	 * TokenMetadata - Infrastructure metadata container
	 * 
	 * Note: Fork/Join synchronization now uses token ID encoding instead of payload fields
	 * Token ID format: parentTokenId + (joinCount * 100) + branchNumber
	 */
	public static class TokenMetadata {
		public String originalToken;
		public Long workflowStartTime;
		public Long serviceStartTime;
		public Long serviceEndTime;
		
		public TokenMetadata() {
			// Empty constructor
		}
		
		@Override
		public String toString() {
			return String.format("TokenMetadata[workflow=%d, service=%d-%d, processing=%dms]",
				workflowStartTime, serviceStartTime, serviceEndTime,
				(serviceStartTime != null && serviceEndTime != null) ? 
					(serviceEndTime - serviceStartTime) : 0);
		}
	}
}