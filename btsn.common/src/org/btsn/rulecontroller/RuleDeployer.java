package org.btsn.rulecontroller;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import org.btsn.utils.OOjdrewAPI;
import org.btsn.utils.StringFileIO;
import org.btsn.utils.XPathHelper;

// These imports will show as red until you create the package structure
import org.btsn.rulecontroller.model.WorkflowModel;
import org.btsn.rulecontroller.model.ServiceNode;
import org.btsn.rulecontroller.model.TransitionNode;
import org.btsn.rulecontroller.model.WorkflowEdge;
import org.btsn.rulecontroller.validation.ValidationResult;

/**
 * RuleDeployer - Runtime deployment of workflow rules to services
 * 
 * Handles the runtime phase of workflow deployment:
 * - Loads and parses workflow JSON
 * - Validates services against OOjDREW knowledge base
 * - Sends rule payloads to services via UDP
 * - Tracks deployment commitments
 * 
 * Called by DeployProcessRuleSet at runtime.
 * 
 * Note: Binding generation is handled separately by TopologyBindingGenerator
 * during the Ant build phase.
 */
public class RuleDeployer {
	private static final Logger logger = Logger.getLogger(RuleDeployer.class);

	// Configuration - Easy to modify
	private static final class Config {
		static final int COMMITMENT_TIMEOUT_MS = 5000;
		static final int MAX_RETRIES = 3;
		static final int MAX_SOCKET_RETRIES = 5;
		static final int SOCKET_TIMEOUT_MS = 5000;
		static final int BASE_RULE_PORT = 20000;
		static final int BASE_SYNC_PORT = 30000;
		
		
		// Rule ports: BASE_RULE_PORT + (channel x 1000) + service_port  
		static final int CHANNEL_OFFSET_MULTIPLIER = 1000;
		
		// Sync ports: BASE_SYNC_PORT + (channel x 100) + (service_port % 100)
		// Different multiplier to avoid port conflicts between rule and sync handlers
		static final int SYNC_CHANNEL_OFFSET_MULTIPLIER = 100;
		
		static final int RETRY_BACKOFF_BASE_MS = 100;


		// Folder locations - DYNAMICALLY DERIVED from package name
		// Package: org.btsn.healthcare.rulecontroller → Common: btsn.healthcare.common
		static final String COMMON_FOLDER = deriveCommonFolder();
		static final String PROCESS_DEFINITION_FOLDER = COMMON_FOLDER + "/ProcessDefinitionFolder";
		static final String RULE_PAYLOAD_FOLDER = COMMON_FOLDER + "/RulePayLoad";
		static final String SERVICE_ATTRIBUTE_BINDINGS_FOLDER = COMMON_FOLDER + "/ServiceAttributeBindings";
		
		static final Set<String> VALID_NODE_TYPES = Set.of("DecisionNode", "TerminateNode", "JoinNode", "XorNode",
				"MergeNode", "EdgeNode", "MonitorNode", "FeedFwdNode", "GatewayNode", "ForkNode", "EventGenerator");
		
		/**
		 * Derive the common folder path from this class's package name
		 * 
		 * Package: org.btsn.rulecontroller (or org.btsn.healthcare.rulecontroller, etc.)
		 * Derived: btsn.common (unified for all projects)
		 * 
		 * This ensures the path is always correct for whatever project
		 * this class is compiled into.
		 */
		private static String deriveCommonFolder() {
			String packageName = RuleDeployer.class.getPackage().getName();
			// org.btsn.healthcare.rulecontroller → ["org", "btsn", "healthcare", "rulecontroller"]
			String[] parts = packageName.split("\\.");
			
			if (parts.length >= 2) {
				// Unified common folder: btsn.common (not project-specific)
				String commonFolder = parts[1] + ".common";
				Logger.getLogger(RuleDeployer.class).info(
					"Derived common folder from package: " + packageName + " → " + commonFolder);
				return commonFolder;
			} else {
				// Fallback: try to derive from current directory structure
				try {
					File currentDir = new File(".");
					String currentPath = currentDir.getCanonicalPath();
					
					// Look for pattern like "btsn.*.common" in sibling directories
					File parentDir = new File("../");
					if (parentDir.exists()) {
						for (File sibling : parentDir.listFiles()) {
							if (sibling.isDirectory() && sibling.getName().contains(".common")) {
								Logger.getLogger(RuleDeployer.class).info(
									"Found common folder from directory scan: " + sibling.getName());
								return sibling.getName();
							}
						}
					}
				} catch (Exception e) {
					Logger.getLogger(RuleDeployer.class).warn(
						"Could not scan for common folder: " + e.getMessage());
				}
				
				// Ultimate fallback - use package-based guess
				Logger.getLogger(RuleDeployer.class).warn(
					"Could not derive common folder, using fallback based on package: " + packageName);
				return "btsn.common";
			}
		}
	}

	// Instance variables
	private final String buildVersion;
	private final String processName;
	private String ruleChannel;
	private String rulePort;
	private Integer serviceOperationPairCount = 0;
	private DatagramSocket commitmentListenerSocket;
	private Thread commitmentListenerThread;
	
	// Add these instance variables near the top (around line 80)
	private String resolvedChannelAddress = null;  // Store the actual IP from boundChannel
	private boolean useRemoteHost = false;         // Flag for remote deployment
	
	// Process type for PetriNet vs SOA mode - REQUIRED in workflow JSON
	// PetriNet mode: JoinNodes use orchestrator-merged single input (token_branch1, token_branch2, etc.)
	// SOA mode: JoinNodes require explicit named inputs from canonicalBinding files
	// Set during parseJsonWorkflow() - will throw exception if not specified
	private String processType = null;  // No default - must be specified in JSON


	// Static resources
	private static final OOjdrewAPI oojdrew = new OOjdrewAPI();
	private static final ConcurrentHashMap<String, Boolean> commitmentReceived = new ConcurrentHashMap<>();

	// Deployment status tracking (for EventGenerators)
	public static boolean deployed = false;
	public static int deployedProcessesCount = 0;
	private static final ConcurrentHashMap<String, java.util.TreeSet<Integer>> ruleArrivalRateMapByVersion = new ConcurrentHashMap<>();

	// Components - using extracted classes
	private final WorkflowModel workflowModel = new WorkflowModel();
	private final ValidationResult validationResult = new ValidationResult();
	
	// NEW: Track multi-operation services
	private final Map<String, List<String>> multiOpServices = new HashMap<>();
	
	// NEW: PetriNet Join Slot Assignments
	// Key: JoinNode transition ID (e.g., "T_in_P6")
	// Value: Map of source T_out ID -> JoinSlotInfo (argName, slotIndex)
	private Map<String, Map<String, JoinSlotInfo>> petriNetJoinSlotAssignments = new HashMap<>();
	
	/**
	 * Holds the slot assignment for a single incoming arc to a JoinNode
	 */
	private static class JoinSlotInfo {
		final String argName;    // e.g., "token_branch1"
		final int slotIndex;     // e.g., 1
		
		JoinSlotInfo(String argName, int slotIndex) {
			this.argName = argName;
			this.slotIndex = slotIndex;
		}
		
		@Override
		public String toString() {
			return argName + ":" + slotIndex;
		}
	}

	public RuleDeployer(String processName, String buildVersion) {
	    this.buildVersion = buildVersion;
	    this.processName = processName;
	    startCommitmentListener(); // Added this line
	//    logger.setLevel(org.apache.log4j.Level.ERROR);  // ADD THIS LINE
	    logger.info("JSON-Based RuleDeployer initialized for process: " + processName + " version: " + buildVersion);

	}

	/**
	 * Main deployment method - JSON format only
	 * Deploys rules to services and tracks commitment status.
	 */
	public int deploy() throws RuleDeployerException {
		logger.info("=== STARTING RULE DEPLOYMENT ===");
		logger.info("Process: " + processName);
		logger.info("Version: " + buildVersion);

		// Initialize version tracking
		ruleArrivalRateMapByVersion.putIfAbsent(buildVersion, new java.util.TreeSet<>());

		// Commitment listener already started in constructor

		try {
			// Phase 1: Detect file type and load workflow
			String fileContent = loadWorkflow();
			
			// Phase 2: Validate workflow (OOjDREW validates services)
			validateWorkflow();

			// Phase 3: Check validation results
			if (validationResult.hasErrors()) {
				validationResult.reportErrors();
				throw new RuleDeployerException(String.format("Workflow validation failed with %d errors",
						validationResult.getErrorCount()));
			}

			logger.info("Workflow validation passed! Proceeding with deployment...");

			// Phase 4: Deploy validated workflow
			int ruleCommitmentValue = deployValidatedWorkflow();

			// Update deployment status
			if (ruleCommitmentValue > 0) {
				deployed = true;
				deployedProcessesCount = ruleCommitmentValue;
				logger.info("=== DEPLOYMENT SUCCESSFUL ===");
				logger.info("Commitments: " + ruleCommitmentValue);
			} else {
				logger.warn("No commitments received, but keeping deployment registered");
			}

			return ruleCommitmentValue;

		} catch (RuleDeployerException e) {
			throw e;
		} catch (Exception e) {
			logger.error("Unexpected error during deployment", e);
			throw new RuleDeployerException("Deployment failed", e);
		}
	}

	/**
	 * DEBUG: Show what was actually parsed
	 */
	private void debugParsedContent() {
		logger.info("=== POST-PARSING DEBUG ===");
		logger.info("Service nodes parsed: " + workflowModel.getServiceNodes().size());
		logger.info("Multi-op services: " + multiOpServices.size());
		logger.info("Transition nodes parsed: " + workflowModel.getTransitionNodes().size());
		logger.info("Workflow edges parsed: " + workflowModel.getWorkflowEdges().size());

		// Debug service nodes
		logger.info("=== SERVICE NODES ===");
		for (ServiceNode serviceNode : workflowModel.getServiceNodes().values()) {
			logger.info("  " + serviceNode.nodeId + " -> " + serviceNode.service + ":" + serviceNode.operation);
			if (multiOpServices.containsKey(serviceNode.nodeId)) {
				logger.info("    Multi-op: " + String.join(",", multiOpServices.get(serviceNode.nodeId)));
			}
		}

		// Debug transition nodes
		logger.info("=== TRANSITION NODES ===");
		for (TransitionNode transitionNode : workflowModel.getTransitionNodes().values()) {
			logger.info(
					"  " + transitionNode.nodeId + " -> " + transitionNode.nodeType + ":" + transitionNode.nodeValue);
		}

		// Debug edges with decision attributes and endpoints
		logger.info("=== WORKFLOW EDGES ===");
		for (WorkflowEdge edge : workflowModel.getWorkflowEdges()) {
			String endpoint = edge.attributes.get("endpoint");
			if (edge.isDecisionEdge()) {
				logger.info("  DECISION EDGE: " + edge.fromNode + " -> " + edge.toNode + " [" + edge.getCondition()
						+ "=" + edge.getDecisionValue() + "]" + 
						(endpoint != null ? " endpoint=" + endpoint : ""));
			} else if (endpoint != null) {
				logger.info("  ENDPOINT EDGE: " + edge.fromNode + " -> " + edge.toNode + " [endpoint=" + endpoint + "]");
			} else {
				logger.info("  " + edge.fromNode + " -> " + edge.toNode);
			}
		}
	}

	/**
	 * Load workflow file - JSON format only
	 */
	private String loadWorkflow() throws RuleDeployerException {
		try {
			File commonBase = new File("../");
			String commonPath = commonBase.getCanonicalPath();
			String baseFileName = commonPath + "/" + Config.PROCESS_DEFINITION_FOLDER + "/" + processName;
			
			// Normalize path to OS-native separators
			File jsonFile = new File(baseFileName + ".json");
			String jsonFileName = jsonFile.getAbsolutePath();
			
			if (jsonFile.exists()) {
				logger.info("Loading JSON workflow from: " + jsonFileName);
				String jsonContent = StringFileIO.readFileAsString(jsonFileName);
				logger.info("Successfully loaded JSON workflow: " + jsonContent.length() + " characters");
				parseJsonWorkflow(jsonContent);
				debugParsedContent();
				return jsonContent;
			} 
			else {
				throw new RuleDeployerException(
					"Workflow file not found: " + jsonFileName + 
					". JSON is the only supported workflow format.");
			}

		} catch (IOException e) {
			throw new RuleDeployerException("Failed to load workflow file: " + e.getMessage(), e);
		}
	}
	
	/**
	 * Parse JSON workflow file from Petri net editor
	 */

	// ============================================================================
	// COMPLETE REFACTORED METHODS FOR BUFFER ATTRIBUTE SUPPORT
	// ============================================================================
	// These are the complete methods to replace in RuleDeployer.java
	// Buffer is ONLY valid for transition_type "T_in" or "Other"
	// ============================================================================

	/**
	 * Parse JSON workflow file from Petri net editor
	 * REFACTORED: Added buffer attribute support for T_in and Other transitions
	 */
	private void parseJsonWorkflow(String jsonContent) throws RuleDeployerException {
	    logger.info("=== PARSING JSON WORKFLOW ===");
	    
	    try {
	        // Extract processType - REQUIRED field
	        String extractedProcessType = extractJsonValue(jsonContent, "processType");
	        if (extractedProcessType != null && !extractedProcessType.isEmpty()) {
	            // Validate processType is a known value
	            if (!"PetriNet".equalsIgnoreCase(extractedProcessType) && 
	                !"SOA".equalsIgnoreCase(extractedProcessType)) {
	                throw new RuleDeployerException(
	                    "Invalid processType: '" + extractedProcessType + "'. " +
	                    "Must be 'PetriNet' or 'SOA'.");
	            }
	            this.processType = extractedProcessType;
	            logger.info("Process type: " + this.processType);
	        } else {
	            // FAIL-FAST: processType is now required
	            throw new RuleDeployerException(
	                "REQUIRED field 'processType' not found in workflow JSON. " +
	                "Please add \"processType\": \"PetriNet\" or \"processType\": \"SOA\" " +
	                "to your workflow definition file.");
	        }
	        
	        // Simple JSON parser for our specific format
	        // Parse elements section
	        String elementsSection = extractJsonSection(jsonContent, "\"elements\"");
	        String[] elementBlocks = splitJsonObjects(elementsSection);
	        
	        for (String block : elementBlocks) {
	            if (block.trim().isEmpty()) continue;
	            
	            String type = extractJsonValue(block, "type");
	            String id = extractJsonValue(block, "id");
	            String label = extractJsonValue(block, "label");
	            
	            if ("PLACE".equals(type)) {
	                // Parse place (service node)
	                String service = extractJsonValue(block, "service");
	                
	                // Try to extract operations (handles array, single string, or old "operation")
	                List<String> operationsList = new ArrayList<>();
	                
	                // First try "operations" (new format - can be array or string)
	                List<String> ops = extractJsonOperations(block, "operations");
	                if (!ops.isEmpty()) {
	                    operationsList.addAll(ops);
	                } else {
	                    // Fallback to "operation" (old format - single string)
	                    String operation = extractJsonValue(block, "operation");
	                    if (operation != null && !operation.isEmpty()) {
	                        operationsList.add(operation);
	                    }
	                }
	                
	                // Create ServiceNode if we have service and at least one operation
	                if (service != null && !service.isEmpty() && !operationsList.isEmpty()) {
	                    // Use first operation as the primary operation for the ServiceNode
	                    String primaryOperation = operationsList.get(0);
	                    
	                    // Extract arguments for the primary operation from the JSON
	                    List<String> operationArguments = extractOperationArguments(block, primaryOperation);
	                    
	                    // Extract return attribute for the primary operation from the JSON
	                    String returnAttribute = extractOperationReturnAttribute(block, primaryOperation);
	                    
	                    // Build attributes map for ServiceNode
	                    Map<String, String> attributes = new HashMap<>();
	                    attributes.put("label", label != null ? label : "");
	                    attributes.put("service", service);
	                    attributes.put("operation", primaryOperation);
	                    
	                    // Store operation arguments as comma-separated string
	                    if (!operationArguments.isEmpty()) {
	                        attributes.put("operationArguments", String.join(",", operationArguments));
	                        logger.info("Stored operation arguments for " + service + "." + primaryOperation + 
	                                   ": " + operationArguments);
	                    }
	                    
	                    // Store return attribute if specified
	                    if (returnAttribute != null && !returnAttribute.isEmpty()) {
	                        attributes.put("returnAttribute", returnAttribute);
	                        logger.info("Stored return attribute for " + service + "." + primaryOperation + 
	                                   ": " + returnAttribute);
	                    }
	                    
	                    // If multiple operations, store them as comma-separated in "operations" attribute
	                    if (operationsList.size() > 1) {
	                        attributes.put("operations", String.join(",", operationsList));
	                        multiOpServices.put(id, new ArrayList<>(operationsList)); 
	                    }
	                    
	                    ServiceNode serviceNode = new ServiceNode(id, service, primaryOperation, attributes);
	                    workflowModel.addServiceNode(serviceNode);
	                    
	                    String opsDisplay = operationsList.size() > 1 ? 
	                        "[" + String.join(", ", operationsList) + "]" : primaryOperation;
	                    String argsDisplay = operationArguments.isEmpty() ? "" : " args=" + operationArguments;
	                    logger.debug("Parsed JSON Place: " + id + " -> " + service + ":" + opsDisplay + argsDisplay);
	                }
	            }
	            else if ("TRANSITION".equals(type)) {
	                // Parse transition
	                String nodeType = extractJsonValue(block, "node_type");
	                String nodeValue = extractJsonValue(block, "node_value");
	                String transitionType = extractJsonValue(block, "transition_type");
	                String buffer = extractJsonValue(block, "buffer");
	                
	                if (nodeType != null && !nodeType.isEmpty()) {
	                    // Build attributes map for TransitionNode
	                    Map<String, String> attributes = new HashMap<>();
	                    attributes.put("label", label != null ? label : "");
	                    attributes.put("node_type", nodeType);
	                    if (nodeValue != null) {
	                        attributes.put("node_value", nodeValue);
	                    }
	                    
	                    // CRITICAL: Only capture buffer for T_in and Other transition types
	                    if (buffer != null && !buffer.isEmpty() && transitionType != null) {
	                        if (transitionType.equals("T_in") || transitionType.equals("Other")) {
	                            attributes.put("buffer", buffer);
	                            logger.info("Captured buffer=" + buffer + " for " + transitionType + 
	                                       " transition: " + id);
	                        } else {
	                            logger.debug("Ignoring buffer for " + transitionType + 
	                                        " transition (only T_in/Other supported): " + id);
	                        }
	                    }
	                    
	                    TransitionNode transitionNode = new TransitionNode(id, nodeType, 
	                                                    nodeValue != null ? nodeValue : "", attributes);
	                    workflowModel.addTransitionNode(transitionNode);
	                    logger.debug("Parsed JSON Transition: " + id + " -> " + nodeType + ":" + nodeValue);
	                }
	            }
            else if ("EVENT_GENERATOR".equals(type)) {
                // EVENT_GENERATOR is NOT a service - it's a visual marker in the workflow
                // Token generation is handled by external GenericHealthcareTokenGenerator process
                // The label (e.g., TRIAGE_EVENTGENERATOR) is passed to the generator via -generator arg
                // and included in token payloads for instrumentation tracking
                
                // Register as a TransitionNode with EventGenerator type for graph validation
                Map<String, String> attributes = new HashMap<>();
                attributes.put("label", label != null ? label : "");
                attributes.put("node_type", "EventGenerator");
                attributes.put("elementType", "EVENT_GENERATOR");
                
                TransitionNode transitionNode = new TransitionNode(id, "EventGenerator", "EVENT_GENERATOR", attributes);
                workflowModel.addTransitionNode(transitionNode);
                
                logger.info("Registered EVENT_GENERATOR '" + id + "' as EventGenerator transition (token generation handled externally)");
            }

	        }
	        
	        // Parse arrows section
	        String arrowsSection = extractJsonSection(jsonContent, "\"arrows\"");
	        String[] arrowBlocks = splitJsonObjects(arrowsSection);
	        
	        for (String block : arrowBlocks) {
	            if (block.trim().isEmpty()) continue;
	            
	            String sourceId = extractJsonValue(block, "source");
	            String targetId = extractJsonValue(block, "target");
	            String label = extractJsonValue(block, "label");
	            // Try guardCondition first (new), fall back to condition (old) for compatibility
	            String condition = extractJsonValue(block, "guardCondition");
	            if (condition == null || condition.isEmpty()) {
	                condition = extractJsonValue(block, "condition");
	            }
	            String decisionValue = extractJsonValue(block, "decision_value");
	            String endpoint = extractJsonValue(block, "endpoint");
	            
	            if (sourceId != null && targetId != null) {
	                Map<String, String> attributes = new HashMap<>();
	                if (label != null && !label.isEmpty()) {
	                    attributes.put("label", label);
	                }
	                if (condition != null && !condition.isEmpty()) {
	                    attributes.put("condition", condition);
	                }
	                if (decisionValue != null && !decisionValue.isEmpty()) {
	                    attributes.put("decision_value", decisionValue);
	                }
	                if (endpoint != null && !endpoint.isEmpty()) {
	                    attributes.put("endpoint", endpoint);
	                }
	                
	                WorkflowEdge edge = new WorkflowEdge(sourceId, targetId, attributes);
	                workflowModel.addWorkflowEdge(edge);
	                
	                if (edge.isDecisionEdge()) {
	                    logger.debug("Parsed JSON Decision Edge: " + sourceId + " -> " + targetId + 
	                        " [" + condition + "=" + decisionValue + "]");
	                } else {
	                    logger.debug("Parsed JSON Edge: " + sourceId + " -> " + targetId);
	                }
	            }
	        }
	        
	        logger.info("JSON parsing complete - " + workflowModel.getServiceNodes().size() + " services, " +
	            workflowModel.getTransitionNodes().size() + " transitions, " + 
	            workflowModel.getWorkflowEdges().size() + " edges");
	            
	    } catch (Exception e) {
	        logger.error("Error parsing JSON workflow", e);
	        throw new RuleDeployerException("Failed to parse JSON workflow: " + e.getMessage(), e);
	    }
	}

	/**
	 * Create service payload
	 * REFACTORED: Added buffer attribute injection into XML payload
	 */
	private String createServicePayload(ServiceNode sourceService, ServiceNode ruleTarget, String ruleContent,
	        String rulePayLoadFileName) throws IOException {

	    // Get the actual file to read from (creates from template if needed)
	    String sourceFilePath = getPayloadFilePath(rulePayLoadFileName);
	    
	    String xmlrulePayload = StringFileIO.readFileAsString(sourceFilePath);

	    // Update XML payload with service-specific information
	    xmlrulePayload = XPathHelper.modifyXMLItem(xmlrulePayload, "//rulepayload/rulefiledata/data/text()", ruleContent);
	    xmlrulePayload = XPathHelper.modifyXMLItem(xmlrulePayload, "//rulepayload/header/ruleBaseVersion/text()",
	            buildVersion);
	    xmlrulePayload = XPathHelper.modifyXMLItem(xmlrulePayload, "//rulepayload/header/ruleBaseCommitment/text()",
	            String.valueOf(serviceOperationPairCount));

	    // Target the rule target service
	    xmlrulePayload = XPathHelper.modifyXMLItem(xmlrulePayload, "//rulepayload/targetservice/serviceName/text()",
	            ruleTarget.service);
	    xmlrulePayload = XPathHelper.modifyXMLItem(xmlrulePayload,
	            "//rulepayload/targetservice/operationName/text()", ruleTarget.operation);

	    // Update buffer value
	    String bufferValue = findBufferForService(ruleTarget);
	    if (bufferValue != null) {
	        xmlrulePayload = XPathHelper.modifyXMLItem(xmlrulePayload, 
	            "//rulepayload/targetservice/buffer/text()", bufferValue);
	    }

	    // Write modified payload back (using normalized path)
	    File payloadFile = new File(rulePayLoadFileName);
	    StringFileIO.writeStringToFile(xmlrulePayload, payloadFile.getAbsolutePath(), xmlrulePayload.length());

	    return xmlrulePayload;
	}
	/**
	 * Find buffer value from incoming T_in or Other transition to a service
	 * NEW METHOD: Searches for buffer attribute in transitions feeding this service
	 */
	private String findBufferForService(ServiceNode serviceNode) {
	    logger.debug("Searching for buffer value for service: " + serviceNode.nodeId);
	    
	    // Find incoming edges to this service
	    for (WorkflowEdge edge : workflowModel.getWorkflowEdges()) {
	        if (edge.toNode.equals(serviceNode.nodeId)) {
	            // Check if source is a transition node with buffer attribute
	            TransitionNode sourceTransition = workflowModel.getTransitionNode(edge.fromNode);
	            if (sourceTransition != null) {
	                String buffer = sourceTransition.attributes.get("buffer");
	                if (buffer != null && !buffer.isEmpty()) {
	                    logger.debug("Found buffer=" + buffer + " from transition: " + 
	                               sourceTransition.nodeId);
	                    return buffer;
	                }
	            }
	        }
	    }
	    
	    // Also check for two-hop paths (transition -> intermediate -> service)
	    for (WorkflowEdge edge1 : workflowModel.getWorkflowEdges()) {
	        TransitionNode transition1 = workflowModel.getTransitionNode(edge1.fromNode);
	        if (transition1 != null && transition1.attributes.containsKey("buffer")) {
	            // Check if this transition eventually leads to our service
	            for (WorkflowEdge edge2 : workflowModel.getWorkflowEdges()) {
	                if (edge2.fromNode.equals(edge1.toNode) && edge2.toNode.equals(serviceNode.nodeId)) {
	                    String buffer = transition1.attributes.get("buffer");
	                    logger.debug("Found buffer=" + buffer + " from two-hop transition: " + 
	                               transition1.nodeId);
	                    return buffer;
	                }
	            }
	        }
	    }
	    
	    logger.debug("No buffer attribute found for service: " + serviceNode.nodeId);
	    return null;
	}

	/**
	 * Add buffer element to XML payload
	 * NEW METHOD: Injects <buffer> element into targetservice section
	 */
	private String addBufferToPayload(String xmlPayload, String bufferValue) {
	    try {
	        // Insert buffer element after operationName in targetservice section
	        String operationCloseTag = "</operationName>";
	        int insertPos = xmlPayload.indexOf(operationCloseTag);
	        
	        if (insertPos != -1) {
	            insertPos += operationCloseTag.length();
	            String bufferElement = "\n\t\t<buffer>" + bufferValue + "</buffer>";
	            
	            // Check if buffer element already exists (avoid duplicates)
	            if (!xmlPayload.contains("<buffer>")) {
	                xmlPayload = xmlPayload.substring(0, insertPos) + 
	                           bufferElement + 
	                           xmlPayload.substring(insertPos);
	                logger.debug("Successfully inserted buffer element into payload");
	            } else {
	                logger.debug("Buffer element already exists in payload, skipping insertion");
	            }
	        } else {
	            logger.warn("Could not find </operationName> tag to insert buffer element");
	        }
	        
	        return xmlPayload;
	        
	    } catch (Exception e) {
	        logger.error("Error adding buffer to payload: " + e.getMessage(), e);
	        return xmlPayload; // Return original payload if insertion fails
	    }
	}

	// ============================================================================
	// END OF REFACTORED METHODS
	// ============================================================================
	/**
	 * Extract JSON section by key (e.g., "elements" or "arrows")
	 */
	private String extractJsonSection(String json, String key) {
		int start = json.indexOf(key + ": [");
		if (start == -1) return "";
		start = json.indexOf("[", start);
		
		// Count brackets to handle nested arrays
		int bracketCount = 0;
		int end = start;
		for (int i = start; i < json.length(); i++) {
			if (json.charAt(i) == '[') {
				bracketCount++;
			} else if (json.charAt(i) == ']') {
				bracketCount--;
				if (bracketCount == 0) {
					end = i;
					break;
				}
			}
		}
		
		return json.substring(start + 1, end);
	}
	
	/**
	 * Split JSON array into individual objects
	 */
	private String[] splitJsonObjects(String section) {
		List<String> objects = new ArrayList<>();
		int braceCount = 0;
		int start = 0;
		
		for (int i = 0; i < section.length(); i++) {
			char c = section.charAt(i);
			if (c == '{') {
				if (braceCount == 0) start = i;
				braceCount++;
			} else if (c == '}') {
				braceCount--;
				if (braceCount == 0) {
					objects.add(section.substring(start, i + 1));
				}
			}
		}
		
		return objects.toArray(new String[0]);
	}
	
	/**
	 * Extract value from JSON object by key
	 */
	private String extractJsonValue(String block, String key) {
		String pattern = "\"" + key + "\":";
		int start = block.indexOf(pattern);
		if (start == -1) return null;
		
		start = block.indexOf(":", start) + 1;
		while (start < block.length() && Character.isWhitespace(block.charAt(start))) start++;
		
		if (start >= block.length()) return null;
		
		if (block.charAt(start) == '"') {
			// String value
			start++;
			int end = start;
			while (end < block.length()) {
				if (block.charAt(end) == '"' && (end == start || block.charAt(end - 1) != '\\')) break;
				end++;
			}
			if (end < block.length()) {
				return block.substring(start, end)
					.replace("\\n", "\n")
					.replace("\\r", "\r")
					.replace("\\t", "\t")
					.replace("\\\"", "\"")
					.replace("\\\\", "\\");
			}
		} else {
			// Number or other value
			int end = start;
			while (end < block.length() && !Character.isWhitespace(block.charAt(end)) && 
				   block.charAt(end) != ',' && block.charAt(end) != '}') {
				end++;
			}
			return block.substring(start, end).trim();
		}
		
		return null;
	}

	/**
	 * Extract operations from JSON - handles both array format and single string format
	 * Returns a list of operations (may be empty if not found)
	 * 
	 * Supports:
	 * - Simple array: "operations": ["op1", "op2"]
	 * - Object array: "operations": [{"name": "op1", ...}, {"name": "op2", ...}]
	 * - Single string: "operation": "op1"
	 */
	private List<String> extractJsonOperations(String block, String key) {
		List<String> operations = new ArrayList<>();
		String pattern = "\"" + key + "\":";
		int start = block.indexOf(pattern);
		if (start == -1) return operations;
		
		start = block.indexOf(":", start) + 1;
		while (start < block.length() && Character.isWhitespace(block.charAt(start))) start++;
		
		if (start >= block.length()) return operations;
		
		if (block.charAt(start) == '[') {
			// Array format - could be strings or objects
			start++; // skip '['
			StringBuilder arrayContent = new StringBuilder();
			int bracketCount = 1;
			
			while (start < block.length() && bracketCount > 0) {
				char c = block.charAt(start);
				if (c == '[') bracketCount++;
				else if (c == ']') bracketCount--;
				
				if (bracketCount > 0) {
					arrayContent.append(c);
				}
				start++;
			}
			
			String content = arrayContent.toString().trim();
			
			// Check if this is an object array (starts with '{') or string array
			if (content.startsWith("{")) {
				// Object array format: [{"name": "op1", ...}, {"name": "op2", ...}]
				// Extract only TOP-LEVEL "name" values from operation objects.
				// IMPORTANT: Strip out nested "arguments":[...] blocks first so that
				// argument names (e.g. "token") are not mistakenly picked up as operations.
				String contentWithoutArgs = removeNestedArgumentsBlocks(content);
				java.util.regex.Pattern namePattern = java.util.regex.Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
				java.util.regex.Matcher matcher = namePattern.matcher(contentWithoutArgs);
				
				while (matcher.find()) {
					String opName = matcher.group(1);
					operations.add(opName);
					logger.debug("Extracted operation name from object: " + opName);
				}
			} else {
				// Simple string array format: ["op1", "op2"]
				boolean inQuotes = false;
				StringBuilder currentOp = new StringBuilder();
				
				for (int i = 0; i < content.length(); i++) {
					char c = content.charAt(i);
					
					if (c == '"' && (i == 0 || content.charAt(i - 1) != '\\')) {
						if (inQuotes) {
							// End of string - add operation
							String op = currentOp.toString().trim();
							if (!op.isEmpty()) {
								operations.add(op);
							}
							currentOp = new StringBuilder();
						}
						inQuotes = !inQuotes;
					} else if (inQuotes) {
						currentOp.append(c);
					}
				}
			}
		} else if (block.charAt(start) == '"') {
			// Single string format: "operation"
			String singleOp = extractJsonValue(block, key);
			if (singleOp != null && !singleOp.trim().isEmpty()) {
				operations.add(singleOp.trim());
			}
		}
		
		return operations;
	}

	/**
	 * Extract argument names from operations array in JSON.
	 * Parses the "arguments" array within each operation object.
	 * 
	 * Example JSON structure:
	 * "operations": [
	 *   {
	 *     "name": "processToken",
	 *     "arguments": [
	 *       {"name": "token_branch1", "type": "String"},
	 *       {"name": "token_branch2", "type": "String"}
	 *     ]
	 *   }
	 * ]
	 * 
	 * @param block The JSON block containing the operations
	 * @param operationName The operation name to find arguments for
	 * @return List of argument names (may be empty if not found)
	 */
	private List<String> extractOperationArguments(String block, String operationName) {
		List<String> argumentNames = new ArrayList<>();
		
		// Find the operations array
		int opsStart = block.indexOf("\"operations\"");
		if (opsStart == -1) return argumentNames;
		
		// Find the opening bracket of operations array
		int arrayStart = block.indexOf("[", opsStart);
		if (arrayStart == -1) return argumentNames;
		
		// Find matching closing bracket
		int bracketCount = 1;
		int pos = arrayStart + 1;
		int arrayEnd = -1;
		
		while (pos < block.length() && bracketCount > 0) {
			char c = block.charAt(pos);
			if (c == '[') bracketCount++;
			else if (c == ']') {
				bracketCount--;
				if (bracketCount == 0) arrayEnd = pos;
			}
			pos++;
		}
		
		if (arrayEnd == -1) return argumentNames;
		
		String operationsContent = block.substring(arrayStart + 1, arrayEnd);
		
		// Find the operation object with matching name
		String searchPattern = "\"name\":\\s*\"" + operationName + "\"";
		java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(searchPattern);
		java.util.regex.Matcher matcher = pattern.matcher(operationsContent);
		
		if (!matcher.find()) return argumentNames;
		
		// Find the arguments array for this operation
		int opNamePos = matcher.start();
		int argsStart = operationsContent.indexOf("\"arguments\"", opNamePos);
		if (argsStart == -1) return argumentNames;
		
		// Find the opening bracket of arguments array
		int argsArrayStart = operationsContent.indexOf("[", argsStart);
		if (argsArrayStart == -1) return argumentNames;
		
		// Find matching closing bracket for arguments
		bracketCount = 1;
		pos = argsArrayStart + 1;
		int argsArrayEnd = -1;
		
		while (pos < operationsContent.length() && bracketCount > 0) {
			char c = operationsContent.charAt(pos);
			if (c == '[') bracketCount++;
			else if (c == ']') {
				bracketCount--;
				if (bracketCount == 0) argsArrayEnd = pos;
			}
			pos++;
		}
		
		if (argsArrayEnd == -1) return argumentNames;
		
		String argumentsContent = operationsContent.substring(argsArrayStart + 1, argsArrayEnd);
		
		// Extract all "name" values from arguments
		java.util.regex.Pattern argNamePattern = java.util.regex.Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
		java.util.regex.Matcher argMatcher = argNamePattern.matcher(argumentsContent);
		
		while (argMatcher.find()) {
			String argName = argMatcher.group(1);
			argumentNames.add(argName);
			logger.debug("Found argument: " + argName + " for operation: " + operationName);
		}
		
		return argumentNames;
	}

	/**
	 * Remove all "arguments":[...] blocks from a JSON string so that
	 * regex matches on "name" fields only hit top-level operation names,
	 * not argument names nested inside the arguments arrays.
	 * 
	 * Handles nested brackets correctly (e.g. arrays within argument objects).
	 * 
	 * @param content The JSON content from the operations array
	 * @return The same content with all "arguments":[...] blocks replaced with empty string
	 */
	private String removeNestedArgumentsBlocks(String content) {
		StringBuilder result = new StringBuilder();
		int i = 0;
		
		while (i < content.length()) {
			// Look for "arguments" key
			int argsKeyStart = content.indexOf("\"arguments\"", i);
			
			if (argsKeyStart == -1) {
				// No more arguments blocks - append rest and done
				result.append(content.substring(i));
				break;
			}
			
			// Append everything before this "arguments" key
			result.append(content, i, argsKeyStart);
			
			// Find the '[' that starts the arguments array value
			int bracketStart = content.indexOf("[", argsKeyStart);
			if (bracketStart == -1) {
				// Malformed - no array bracket found, append rest and done
				result.append(content.substring(argsKeyStart));
				break;
			}
			
			// Find the matching ']' using bracket counting
			int bracketCount = 1;
			int pos = bracketStart + 1;
			while (pos < content.length() && bracketCount > 0) {
				char c = content.charAt(pos);
				if (c == '[') bracketCount++;
				else if (c == ']') bracketCount--;
				pos++;
			}
			
			// Skip past the entire "arguments":[...] block
			i = pos;
		}
		
		return result.toString();
	}

	/**
	 * 
	 * Expected JSON format:
	 * "operations": [
	 *   {
	 *     "name": "processClinicalDecision",
	 *     "returnAttribute": "diagnosisResults",
	 *     "arguments": [...]
	 *   }
	 * ]
	 * 
	 * @param block The JSON block containing the operations array
	 * @param operationName The operation name to find returnAttribute for
	 * @return The return attribute name, or null if not specified
	 */
	private String extractOperationReturnAttribute(String block, String operationName) {
		// Find the operations array
		int opsStart = block.indexOf("\"operations\"");
		if (opsStart == -1) return null;
		
		// Find the opening bracket of operations array
		int arrayStart = block.indexOf("[", opsStart);
		if (arrayStart == -1) return null;
		
		// Find matching closing bracket
		int bracketCount = 1;
		int pos = arrayStart + 1;
		int arrayEnd = -1;
		
		while (pos < block.length() && bracketCount > 0) {
			char c = block.charAt(pos);
			if (c == '[') bracketCount++;
			else if (c == ']') {
				bracketCount--;
				if (bracketCount == 0) arrayEnd = pos;
			}
			pos++;
		}
		
		if (arrayEnd == -1) return null;
		
		String operationsContent = block.substring(arrayStart + 1, arrayEnd);
		
		// Find the operation object with matching name
		String searchPattern = "\"name\"\\s*:\\s*\"" + operationName + "\"";
		java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(searchPattern);
		java.util.regex.Matcher matcher = pattern.matcher(operationsContent);
		
		if (!matcher.find()) return null;
		
		// Find the returnAttribute for this operation
		// Look for "returnAttribute" : "value" pattern after the operation name
		int opNamePos = matcher.start();
		
		// Find the end of this operation object (next '}' at same level)
		int braceCount = 0;
		int opStart = operationsContent.lastIndexOf("{", opNamePos);
		int opEnd = -1;
		
		for (int i = opStart; i < operationsContent.length(); i++) {
			char c = operationsContent.charAt(i);
			if (c == '{') braceCount++;
			else if (c == '}') {
				braceCount--;
				if (braceCount == 0) {
					opEnd = i;
					break;
				}
			}
		}
		
		if (opEnd == -1) return null;
		
		String opContent = operationsContent.substring(opStart, opEnd + 1);
		
		// Extract returnAttribute value
		java.util.regex.Pattern returnAttrPattern = java.util.regex.Pattern.compile(
			"\"returnAttribute\"\\s*:\\s*\"([^\"]+)\"");
		java.util.regex.Matcher returnMatcher = returnAttrPattern.matcher(opContent);
		
		if (returnMatcher.find()) {
			String returnAttr = returnMatcher.group(1);
			logger.debug("Found returnAttribute: " + returnAttr + " for operation: " + operationName);
			return returnAttr;
		}
		
		return null;
	}

	
	/**
	 * Helper to write individual service entry with channel info
	 */
	private String writeServiceEntry(String serviceName, String operation) {
	    try {
	        // Query for this service's deployment info
	        getServiceRuleChannel(serviceName, operation, buildVersion);
	        
	        // Store the RESOLVED address, not the channel ID
	        String channelToStore = ruleChannel;
	        String portToStore = rulePort;
	        
	        // If we have a resolved IP address, use that instead of the channel ID
	        if (this.useRemoteHost && this.resolvedChannelAddress != null) {
	            channelToStore = this.resolvedChannelAddress;  // Store the actual IP
	            
	            // CALCULATE THE TRANSFORMED PORT that EventPublisher will use
	            //int channelNumber = extractChannelNumber(ruleChannel);
	            //int basePort = Integer.parseInt(rulePort);
	            //int transformedPort = 10000 + (channelNumber * 1000) + basePort;
	            //portToStore = String.valueOf(transformedPort);
	            
	            //logger.info("Transformed port for remote service " + serviceName + ": " + 
	              //         basePort + " -> " + transformedPort + " (channel " + channelNumber + ")");
	        }
	        
	        // Return formatted entry with resolved address and transformed port
	        return String.format("%s:%s:%s:%s", 
	            serviceName, 
	            operation, 
	            channelToStore,  
	            portToStore);  // Now uses the transformed port
	            
	    } catch (Exception e) {
	        logger.warn("Could not get channel info for " + serviceName + ":" + operation);
	        return String.format("%s:%s:unknown:unknown", serviceName, operation);
	    }
	}
	/**
	 * Start UDP listener for commitment confirmations from SharedRuleCorrelator
	 * VERSION-AGNOSTIC: Dynamically calculates port based on version string
	 */
	private void startCommitmentListener() {
	    try {
	        // Calculate port dynamically based on version
	        int listenPort = calculateCommitmentListenerPort(buildVersion);
	        
	        commitmentListenerSocket = new DatagramSocket(null);  // Create unbound first
	        commitmentListenerSocket.setReuseAddress(true);        // Allow reuse of TIME_WAIT ports
	        commitmentListenerSocket.bind(new java.net.InetSocketAddress(listenPort));
	        commitmentListenerSocket.setSoTimeout(100); // 100ms timeout for non-blocking
	        
	        logger.info("Started commitment listener on port " + listenPort + " for version " + buildVersion);
	        
	        commitmentListenerThread = new Thread(new Runnable() {
	            @Override
	            public void run() {
	                byte[] buffer = new byte[256];
	                while (!Thread.currentThread().isInterrupted()) {
	                    try {
	                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
	                        commitmentListenerSocket.receive(packet);
	                        String received = new String(packet.getData(), 0, packet.getLength()).trim();
	                        
	                        // Format: "CONFIRMED:version:count"
	                        if (received.startsWith("CONFIRMED:")) {
	                            String[] parts = received.split(":");
	                            if (parts.length == 3 && parts[1].equals(buildVersion)) {
	                                String key = parts[1] + ":" + parts[2];
	                                commitmentReceived.put(key, Boolean.TRUE);
	                                logger.info("[OK] UDP commitment confirmation received: " + key);
	                            }
	                        }
	                    } catch (SocketTimeoutException e) {
	                        // Normal timeout, continue
	                    } catch (Exception e) {
	                        // Only log if not a socket closed message
	                        String msg = e.getMessage();
	                        if (msg != null && !msg.contains("socket closed")) {
	                            logger.debug("Commitment listener: " + msg);
	                        }
	                    }
	                }
	                logger.info("Commitment listener stopped for version " + buildVersion);
	            }
	        });
	        commitmentListenerThread.setDaemon(true);
	        commitmentListenerThread.setName("CommitmentListener-" + buildVersion);
	        commitmentListenerThread.start();
	        
	    } catch (Exception e) {
	        logger.error("Failed to start commitment listener for version " + buildVersion, e);
	    }
	}

	/**
	 * Calculate commitment listener port dynamically
	 * Port calculation for commitment listener
	 */
	private int calculateCommitmentListenerPort(String version) {
	    int BASE_CONFIRMATION_PORT = 35000;
	    
	    // Extract numeric part from version string for port offset
	    int offset = extractVersionOffset(version);
	    int port = BASE_CONFIRMATION_PORT + offset;
	    
	    logger.info("Calculated commitment listener port for version '" + version + "': " + port + 
	                " (base: " + BASE_CONFIRMATION_PORT + ", offset: " + offset + ")");
	    
	    return port;
	}
	
	/**
	 * Extract offset from version string
	 * Matches the port services send commitments to
	 */
	private int extractVersionOffset(String version) {
	    // Try to extract numeric part from version (e.g., "v001" -> 1, "v002" -> 2)
	    try {
	        // Remove all non-digits and parse
	        String numericPart = version.replaceAll("[^0-9]", "");
	        if (!numericPart.isEmpty()) {
	            int versionNumber = Integer.parseInt(numericPart);
	            logger.debug("Extracted version number " + versionNumber + " from '" + version + "'");
	            return versionNumber;
	        }
	    } catch (NumberFormatException e) {
	        logger.debug("Could not extract numeric version from '" + version + "', using hash-based offset");
	    }
	    
	    // Fallback: Use hash-based offset for non-numeric versions
	    // This ensures consistent port assignment even for versions like "dev", "test", etc.
	    int hashOffset = Math.abs(version.hashCode() % 100) + 1; // +1 to avoid 0
	    logger.debug("Using hash-based offset " + hashOffset + " for version '" + version + "'");
	    return hashOffset;
	}

	
	/**
	 * Stop the commitment listener (fixed recursive call bug)
	 */
	private void stopCommitmentListener() {
	    // Fixed: removed recursive call to stopCommitmentListener()
	    if (commitmentListenerThread != null) {
	        commitmentListenerThread.interrupt();
	        try {
	            commitmentListenerThread.join(1000);
	        } catch (InterruptedException e) {
	            // Ignore
	        }
	    }
	    if (commitmentListenerSocket != null && !commitmentListenerSocket.isClosed()) {
	        commitmentListenerSocket.close();
	        logger.info("Closed commitment listener socket for version " + buildVersion);
	    }
	}
	
	/**
	 * Optional: Add this static method to allow external verification of port calculation
	 * Useful for debugging and testing
	 */
	public static int getExpectedCommitmentPort(String version) {
	    int BASE_CONFIRMATION_PORT = 35000;
	    
	    try {
	        String numericPart = version.replaceAll("[^0-9]", "");
	        if (!numericPart.isEmpty()) {
	            return BASE_CONFIRMATION_PORT + Integer.parseInt(numericPart);
	        }
	    } catch (NumberFormatException e) {
	        // Fall through to hash-based calculation
	    }
	    
	    return BASE_CONFIRMATION_PORT + Math.abs(version.hashCode() % 100) + 1;
	}

	/**
	 * Validate workflow structure and services
	 */
	private void validateWorkflow() {
		logger.info("Performing comprehensive workflow validation...");

		validateServiceNodesWithOOjDREW();
		validateWorkflowStructure();
		validateTransitionNodeTypes();
		validateGraphConnectivity();
		validateJoinNodeConfigurations();  // NEW: Layer 2a & 2b validation

		logger.info("Workflow validation complete. Found " + validationResult.getErrorCount() + " issues");
	}

	/**
	 * ENHANCED: Validate all operations for multi-op services
	 */
	private void validateServiceNodesWithOOjDREW() {
		logger.info("Step 1: Validating service:operation pairs with OOjDREW...");

		for (ServiceNode serviceNode : workflowModel.getServiceNodes().values()) {
			// Check if this is a multi-op service
			if (multiOpServices.containsKey(serviceNode.nodeId)) {
				// Validate all operations
				for (String operation : multiOpServices.get(serviceNode.nodeId)) {
					validateServiceOperationWithOOjDREW(serviceNode.service, operation, serviceNode.nodeId);
				}
			} else {
				// Single operation
				validateServiceOperationWithOOjDREW(serviceNode.service, serviceNode.operation, serviceNode.nodeId);
			}
		}
	}

	private void validateServiceOperationWithOOjDREW(String serviceName, String operationName, String nodeId) {
	    try {
	        // TRY ACTIVESERVICE FIRST
	        String activeServiceQuery = String.format(
	            "<Query><Atom><Rel>activeService</Rel><Ind>%s</Ind><Ind>%s</Ind><Var>channelId</Var><Var>port</Var></Atom></Query>",
	            serviceName, operationName);
	        
	        logger.debug("Validating service with OOjDREW: " + serviceName + ":" + operationName + " (node: " + nodeId + ")");
	        
	        // Use dynamically derived common folder path
	        oojdrew.parseKnowledgeBase("../" + Config.COMMON_FOLDER + "/RuleFolder." + buildVersion + "/Service.ruleml", true);
	        oojdrew.issueRuleMLQuery(activeServiceQuery);
	        
	        // If not found in activeService, try hasOperation
	        if (oojdrew.rowsReturned == 0) {
	            logger.debug("Not found in activeService, trying hasOperation...");
	            
	            String hasOperationQuery = String.format(
	                "<Query><Atom><Rel>hasOperation</Rel><Ind>%s</Ind><Ind>%s</Ind><Var>channelId</Var><Var>port</Var></Atom></Query>",
	                serviceName, operationName);
	            
	            oojdrew.issueRuleMLQuery(hasOperationQuery);
	        }
	        
	        if (oojdrew.rowsReturned == 0) {
	            validationResult.addError("SERVICE_NOT_FOUND",
	                String.format("Service:operation not found in activeService or hasOperation: %s:%s", 
	                    serviceName, operationName),
	                nodeId, String.format("%s:%s (service_node)", serviceName, operationName));
	        } else {
	            validateOOjDREWResults(serviceName, operationName, nodeId);
	        }
	        
	    } catch (Exception e) {
	        validationResult.addError("OOJDREW_QUERY_ERROR",
	            String.format("Error querying knowledge base for %s:%s: %s", serviceName, operationName, e.getMessage()),
	            nodeId, String.format("%s:%s (service_node)", serviceName, operationName));
	    }
	}
	private void validateOOjDREWResults(String serviceName, String operationName, String nodeId) {
		boolean foundChannel = false;
		boolean foundPort = false;

		for (int i = 0; i < oojdrew.rowsReturned; i++) {
			String key = String.valueOf(oojdrew.rowData[i][0]);

			if ("?channelId".equals(key)) {
				foundChannel = true;
			} else if ("?port".equals(key)) {
				foundPort = true;
			}
		}

		if (!foundChannel || !foundPort) {
			validationResult.addError("SERVICE_NOT_FOUND",
					String.format("Incomplete service configuration for %s:%s (missing %s)", serviceName,
							operationName, !foundChannel ? "channelId" : "port"),
					nodeId, String.format("%s:%s (service_node)", serviceName, operationName));
		} else {
			logger.debug("[OK] OOjDREW validation passed for " + serviceName + ":" + operationName);
		}
	}

	private void validateWorkflowStructure() {
		logger.info("Step 2: Validating workflow structure...");

		for (WorkflowEdge edge : workflowModel.getWorkflowEdges()) {
		//	boolean fromExists = workflowModel.nodeExists(edge.fromNode);
			boolean fromExists = workflowModel.nodeExists(edge.fromNode) || "EVENT_GENERATOR".equals(edge.fromNode);
			boolean toExists = workflowModel.nodeExists(edge.toNode) || "END".equals(edge.toNode)
					|| "START".equals(edge.toNode);

			if (!fromExists) {
				validationResult.addError("WORKFLOW_INCONSISTENCY",
						"Edge references non-existent source node: " + edge.fromNode, edge.fromNode,
						"edge: " + edge);
			}

			if (!toExists) {
				validationResult.addError("WORKFLOW_INCONSISTENCY",
						"Edge references non-existent target node: " + edge.toNode, edge.toNode, "edge: " + edge);
			}
		}
	}

	private void validateTransitionNodeTypes() {
		logger.info("Step 3: Validating transition node types...");

		for (TransitionNode transitionNode : workflowModel.getTransitionNodes().values()) {
			if (!Config.VALID_NODE_TYPES.contains(transitionNode.nodeType)) {
				validationResult.addError("INVALID_NODE_TYPE",
						String.format("Unknown transition node type: %s (valid types: %s)", transitionNode.nodeType,
								Config.VALID_NODE_TYPES),
						transitionNode.nodeId, transitionNode.nodeType + ":" + transitionNode.nodeValue);
			}
		}
	}

	private void validateGraphConnectivity() {
	    logger.info("Step 4: Validating graph connectivity...");

	    Map<String, List<String>> adjacencyList = workflowModel.buildAdjacencyList();

	    for (ServiceNode serviceNode : workflowModel.getServiceNodes().values()) {
	        // Skip connectivity validation for floating nodes
	        boolean isFloating = "true".equals(serviceNode.attributes.get("floating"));
	        
	        if (isFloating) {
	            logger.info("Skipping connectivity validation for floating node: " + serviceNode.nodeId + 
	                       " (" + serviceNode.service + ":" + serviceNode.operation + ")");
	            continue;
	        }
	        
	        boolean hasIncoming = workflowModel.getWorkflowEdges().stream()
	                .anyMatch(e -> e.toNode.equals(serviceNode.nodeId));
	        boolean hasOutgoing = adjacencyList.containsKey(serviceNode.nodeId);

	        if (!hasIncoming && !hasOutgoing) {
	            validationResult.addError("WORKFLOW_INCOMPLETE",
	                    "Service node is disconnected (no incoming or outgoing edges): " + serviceNode.nodeId,
	                    serviceNode.nodeId, serviceNode.service + ":" + serviceNode.operation);
	        }
	    }
	}

	/**
	 * Step 5: Validate JoinNode configurations
	 * 
	 * Layer 2a: Validates that JoinNodes have 2+ incoming edges (structural validation)
	 * Layer 2b: Warns about canonical binding requirements for downstream services
	 * 
	 * JoinNodes synchronize parallel execution paths - they MUST have multiple inputs.
	 * A JoinNode with only 1 input is a workflow definition error (should be EdgeNode).
	 * 
	 * Additionally, the downstream service's canonical binding must specify the same
	 * number of inputs as the JoinNode has incoming edges, otherwise the join
	 * synchronization will fail at runtime.
	 */
	private void validateJoinNodeConfigurations() {
	    logger.info("Step 5: Validating JoinNode configurations...");
	    
	    int joinNodeCount = 0;
	    int validJoinNodes = 0;
	    
	    for (TransitionNode transition : workflowModel.getTransitionNodes().values()) {
	        if (!"JoinNode".equals(transition.nodeType)) {
	            continue;
	        }
	        
	        joinNodeCount++;
	        logger.info("Validating JoinNode: " + transition.nodeId);
	        
	        // Layer 2a: Count incoming edges to this JoinNode
	        List<WorkflowEdge> incomingEdges = workflowModel.getWorkflowEdges().stream()
	            .filter(e -> e.toNode.equals(transition.nodeId))
	            .collect(java.util.stream.Collectors.toList());
	        
	        int incomingCount = incomingEdges.size();
	        
	        // Extract source nodes for clear error messages
	        String sourceNodes = incomingEdges.stream()
	            .map(e -> e.fromNode)
	            .collect(java.util.stream.Collectors.joining(", "));
	        
	        if (incomingCount < 2) {
	            // CRITICAL ERROR: JoinNode with < 2 inputs cannot synchronize parallel paths
	            validationResult.addError("JOIN_NODE_INSUFFICIENT_INPUTS",
	                String.format(
	                    "JoinNode '%s' has only %d incoming edge(s). " +
	                    "JoinNodes synchronize parallel paths and MUST have 2 or more incoming edges.\n" +
	                    "  Current incoming edges: [%s]\n" +
	                    "  Fix: Either add more incoming edges to this JoinNode, or change it to EdgeNode if only one input is intended.",
	                    transition.nodeId, incomingCount, 
	                    sourceNodes.isEmpty() ? "none" : sourceNodes),
	                transition.nodeId, 
	                "JoinNode requires 2+ inputs for synchronization");
	        } else {
	            validJoinNodes++;
	            logger.info("  [OK] JoinNode " + transition.nodeId + " has " + incomingCount + 
	                       " incoming edges: [" + sourceNodes + "]");
	            
	            // Layer 2b: Find downstream service and VALIDATE canonical binding input count
	            ServiceNode downstreamService = findServiceDownstreamOfTransition(transition);
	            
	            if (downstreamService != null) {
	                logger.info("  Downstream service: " + downstreamService.service + ":" + downstreamService.operation);
	                
	                // Skip canonical binding validation for JoinNodes
	                // PetriNet mode: processType is carried in the token, Fork stamps joinCount
	                // The runtime (ServiceThread) will read joinCount from the token and handle synchronization
	                logger.info("  [OK] JoinNode downstream service identified. Runtime will handle synchronization.");
	                logger.info("  Note: For PetriNet mode, token will carry joinCount=" + incomingCount);
	            } else {
	                logger.warn("  Could not find downstream service for JoinNode " + transition.nodeId);
	            }
	        }
	    }
	    
	    if (joinNodeCount == 0) {
	        logger.info("  No JoinNodes found in workflow (this is OK for linear workflows)");
	    } else {
	        logger.info(String.format("  JoinNode validation complete: %d/%d JoinNodes valid", 
	                                  validJoinNodes, joinNodeCount));
	    }
	}

	/**
	 * Find the service node that is directly downstream of a transition
	 * (i.e., the transition points TO this service via an edge)
	 */
	private ServiceNode findServiceDownstreamOfTransition(TransitionNode transition) {
	    // Find edges where this transition is the source
	    for (WorkflowEdge edge : workflowModel.getWorkflowEdges()) {
	        if (edge.fromNode.equals(transition.nodeId)) {
	            // Check if target is a service node
	            ServiceNode service = workflowModel.getServiceNodes().get(edge.toNode);
	            if (service != null) {
	                return service;
	            }
	        }
	    }
	    return null;
	}

	/**
	 * Query OOjDREW for the number of inputs in a service's canonical binding.
	 * 
	 * Canonical binding format:
	 *   <Atom><Rel>canonicalBinding</Rel>
	 *     <Ind>operationName</Ind>
	 *     <Ind>returnedAttribute</Ind>
	 *     <Ind>input1</Ind>
	 *     <Ind>input2</Ind>
	 *     ...
	 *   </Atom>
	 * 
	 * The number of inputs = total <Ind> count - 2 (operation + return attribute)
	 * 
	 * @return number of inputs, or -1 if cannot determine
	 */
	private int getCanonicalBindingInputCount(String serviceName, String operationName) {
	    try {
	        // Query for canonical binding
	        // Format: canonicalBinding(operation, returnAttr, input1, input2, ...)
	        String query = String.format(
	            "<Query><Atom><Rel>canonicalBinding</Rel><Ind>%s</Ind><Var>returnAttr</Var><Var>input1</Var></Atom></Query>",
	            operationName);
	        
	        logger.debug("  Querying canonical binding for " + serviceName + ":" + operationName);
	        oojdrew.issueRuleMLQuery(query);
	        
	        if (oojdrew.rowsReturned > 0) {
	            // Found a canonical binding - now we need to count the inputs
	            // The query only matches if there's at least 1 input
	            // We need a more sophisticated approach to count all inputs
	            
	            // Try querying with increasing number of inputs until we find the right count
	            for (int inputCount = 1; inputCount <= 10; inputCount++) {
	                if (testCanonicalBindingInputCount(operationName, inputCount)) {
	                    if (!testCanonicalBindingInputCount(operationName, inputCount + 1)) {
	                        logger.debug("  Canonical binding has " + inputCount + " input(s)");
	                        return inputCount;
	                    }
	                }
	            }
	            
	            // If we get here, either 0 inputs or more than 10
	            if (testCanonicalBindingInputCount(operationName, 0)) {
	                return 0;
	            }
	            
	            logger.warn("  Could not determine exact input count for " + operationName);
	            return -1;
	        } else {
	            // No canonical binding found - this might be OK for some services
	            logger.debug("  No canonical binding found for " + operationName);
	            return -1;
	        }
	    } catch (Exception e) {
	        logger.warn("  Error querying canonical binding: " + e.getMessage());
	        return -1;
	    }
	}

	/**
	 * Test if a canonical binding exists with exactly N inputs
	 */
	private boolean testCanonicalBindingInputCount(String operationName, int inputCount) {
	    try {
	        StringBuilder queryBuilder = new StringBuilder();
	        queryBuilder.append("<Query><Atom><Rel>canonicalBinding</Rel>");
	        queryBuilder.append("<Ind>").append(operationName).append("</Ind>");
	        queryBuilder.append("<Var>returnAttr</Var>");
	        
	        for (int i = 1; i <= inputCount; i++) {
	            queryBuilder.append("<Var>input").append(i).append("</Var>");
	        }
	        
	        queryBuilder.append("</Atom></Query>");
	        
	        oojdrew.issueRuleMLQuery(queryBuilder.toString());
	        return oojdrew.rowsReturned > 0;
	    } catch (Exception e) {
	        return false;
	    }
	}

	/**
	 * Deploy validated workflow
	 */
	private int deployValidatedWorkflow() throws RuleDeployerException {
		try {
			// Phase 4a: Generate canonical bindings based on processType
			// This must happen BEFORE rule deployment so BuildRuleBase can include them
			// Note: buildPetriNetJoinSlotAssignments is called internally by generatePetriNetCanonicalBindings
			if ("PetriNet".equalsIgnoreCase(this.processType)) {
				// PetriNet mode: Always generate bindings from topology
				// Uses token_branch1, token_branch2, etc. for JoinNodes
				generatePetriNetCanonicalBindings();
			} else if ("SOA".equalsIgnoreCase(this.processType)) {
				// SOA mode: Only generate bindings if arguments are specified in JSON
				// If no arguments in JSON, leave existing binding files untouched
				generateSOACanonicalBindingsIfSpecified();
			}
			
			// Deploy service nodes defined in JSON file
			int deployedCount = deployServiceNodes();

			// Deploy standalone monitor nodes
			deployedCount += deployStandaloneMonitorNodes();
			

			logger.info("=== JSON-BASED DEPLOYMENT COMPLETED SUCCESSFULLY ===");
			logger.info("Deployed " + deployedCount + " service operations from JSON workflow");

			return deployedCount;

		} catch (Exception e) {
			logger.error("Error during workflow deployment", e);
			throw new RuleDeployerException("Workflow deployment failed", e);
		}
	}

	/**
	 * Deploy service nodes
	 * ENHANCED: Handle multi-op services AND skip floating nodes
	 */
	private int deployServiceNodes() throws RuleDeployerException {
	    logger.info("=== DEPLOYING SERVICE NODES ===");

	    Map<String, ServiceNode> services = workflowModel.getServiceNodes();
	    logger.info("Total services to deploy: " + services.size());

	    // Log all services that will be processed
	    for (ServiceNode service : services.values()) {
	        boolean isFloating = "true".equals(service.attributes.get("floating"));
	        boolean isEventGenerator = "EVENT_GENERATOR".equals(service.attributes.get("elementType"));
	        String status;
	        if (isFloating) {
	            status = "FLOATING (skip deployment)";
	        } else if (isEventGenerator) {
	            status = "EVENT_GENERATOR (skip deployment - generates tokens, doesn't receive rules)";
	        } else {
	            status = "DEPLOYABLE";
	        }
	        
	        logger.info("Will process: " + service.service + ":" + service.operation + 
	                   " (nodeId: " + service.nodeId + ") - " + status);
	        
	        if (multiOpServices.containsKey(service.nodeId)) {
	            logger.info("  Multi-op: " + String.join(",", multiOpServices.get(service.nodeId)));
	        }
	    }

	    int deployedCount = 0;
	    int skippedCount = 0;
	    int eventGeneratorCount = 0;
	    
	    for (ServiceNode serviceNode : services.values()) {
	        // CHECK FOR FLOATING NODES FIRST - skip deployment entirely
	        boolean isFloating = "true".equals(serviceNode.attributes.get("floating"));
	        
	        // CHECK FOR EVENT_GENERATOR - skip deployment (they generate tokens, don't receive rules)
	        boolean isEventGenerator = "EVENT_GENERATOR".equals(serviceNode.attributes.get("elementType"));
	        
	        if (isEventGenerator) {
	            logger.info("=== SKIPPING EVENT_GENERATOR NODE ===");
	            logger.info("Service: " + serviceNode.service + ":" + serviceNode.operation);
	            logger.info("Node ID: " + serviceNode.nodeId);
	            logger.info("Reason: Event generators produce tokens - they don't receive deployed rules");
	            eventGeneratorCount++;
	            continue; // Skip to next service
	        }
	        
	        if (isFloating) {
	            logger.info("=== SKIPPING FLOATING NODE ===");
	            logger.info("Service: " + serviceNode.service + ":" + serviceNode.operation);
	            logger.info("Node ID: " + serviceNode.nodeId);
	            logger.info("Reason: floating=\"true\" - included in manifest only");
	           /* 
	            if (multiOpServices.containsKey(serviceNode.nodeId)) {
	                skippedCount += multiOpServices.get(serviceNode.nodeId).size();
	                logger.info("Multi-op floating node: " + multiOpServices.get(serviceNode.nodeId).size() + " operations skipped");
	            } else {
	                skippedCount++;
	            }
	            continue; // Skip to next service
	            */
	        }
	        
	        // Deploy non-floating nodes normally
	        if (multiOpServices.containsKey(serviceNode.nodeId)) {
	            // Deploy each operation separately
	            for (String operation : multiOpServices.get(serviceNode.nodeId)) {
	                logger.info("=== DEPLOYING: " + serviceNode.service + ":" + operation + " ===");
	                try {
	                    // Create a temporary service node for this specific operation
	                    ServiceNode opNode = new ServiceNode(serviceNode.nodeId, serviceNode.service, operation, serviceNode.attributes);
	                    deployServiceNode(opNode);
	                    deployedCount++;
	                    logger.info("Successfully deployed: " + serviceNode.service + ":" + operation);
	                } catch (Exception e) {
	                    logger.error("Failed to deploy: " + serviceNode.service + ":" + operation, e);
	                    throw e;
	                }
	            }
	        } else {
	            // Single operation service
	            logger.info("=== STARTING DEPLOYMENT FOR: " + serviceNode.service + ":" + serviceNode.operation + " ===");
	            try {
	                deployServiceNode(serviceNode);
	                deployedCount++;
	                logger.info("Successfully deployed: " + serviceNode.service + ":" + serviceNode.operation);
	            } catch (Exception e) {
	                logger.error("Failed to deploy: " + serviceNode.service + ":" + serviceNode.operation, e);
	                throw e;
	            }
	        }
	    }

	    logger.info("=== DEPLOYMENT SUMMARY ===");
	    logger.info("Total services processed: " + services.size());
	    logger.info("Successfully deployed: " + deployedCount);
	    logger.info("Floating nodes skipped: " + skippedCount);
	    logger.info("Event generators skipped: " + eventGeneratorCount);
	    logger.info("Total in manifest: " + (deployedCount + skippedCount + eventGeneratorCount));
	    
	    return deployedCount;
	}
	/**
	 * FIXED: Simple rule target finding - rules go to the service itself
	 */
	private ServiceNode findRuleTarget(ServiceNode serviceNode) {
		logger.info("Rules for " + serviceNode.nodeId + " should go to itself: " + serviceNode.service);
		return serviceNode;
	}

	/**
	 * Deploy individual service node
	 */
	private void deployServiceNode(ServiceNode serviceNode) throws RuleDeployerException {
		// Find who should receive the rules
		ServiceNode ruleTarget = findRuleTarget(serviceNode);

		// Get service channel information for the rule target
		getServiceRuleChannel(ruleTarget.service, ruleTarget.operation, buildVersion);
		serviceOperationPairCount++;

		// Generate rules for this service - returns String
		String ruleContent = generateServiceRules(serviceNode);

		// Send payload to the rule target - accepts String
		sendServicePayload(serviceNode, ruleTarget, ruleContent);

		logger.info("Successfully deployed rule for: " + serviceNode.service + ":" + serviceNode.operation);
	}

	/**
	 * Generate service rules - FIXED VERSION
	 * 
	 * KEY CHANGE: Now uses INCOMING transitions to determine NodeType
	 * This ensures services like DiagnosisService correctly get JoinNode instead of EdgeNode
	 */
	/**
	 * Generate service rules - FIXED VERSION
	 * 
	 * KEY LOGIC:
	 * - INCOMING transition determines INPUT COORDINATION (join semantics)
	 * - OUTGOING transition determines CONTROL FLOW (routing/fork/decision behavior)
	 * - Use OUTGOING transition's NodeType for the service's routing behavior
	 */
	private String generateServiceRules(ServiceNode serviceNode) throws RuleDeployerException {
	    StringWriter stringWriter = new StringWriter();
		PrintWriter writer = new PrintWriter(stringWriter);

		logger.info("=== GENERATING RULES FOR SERVICE: " + serviceNode.service + ":" + serviceNode.operation + " ===");

		// STEP 1: Get INCOMING transition (for input coordination understanding)
		List<TransitionNode> incomingTransitions = workflowModel.findIncomingTransitions(serviceNode);

		if (incomingTransitions.isEmpty()) {
		    logger.warn("No incoming transitions found for " + serviceNode.nodeId);
		    writer.print("<!-- No incoming transitions found -->");
		writer.flush();
		return stringWriter.toString();
		}

		TransitionNode incomingTransition = incomingTransitions.get(0);
		logger.info("Input coordination from INCOMING: " + incomingTransition.nodeId + " (" + incomingTransition.nodeType + ")");

		// STEP 2: Get OUTGOING transitions (determines control flow behavior)
		List<TransitionNode> outgoingTransitions = workflowModel.findOutgoingTransitions(serviceNode);
		
		if (outgoingTransitions.isEmpty()) {
		    logger.warn("No outgoing transitions found for " + serviceNode.nodeId + " - likely a TerminateNode");
		    // For terminate nodes, use incoming transition type
		    AddNodeType(writer, new String[] { incomingTransition.nodeType, incomingTransition.nodeValue });
		    logger.info("Wrote NodeType from INCOMING (terminate node): " + incomingTransition.nodeType);
		    writer.flush();
		    return stringWriter.toString();
		}

		// STEP 3: Determine which transition controls the NodeType
		TransitionNode controllingTransition = determineControllingTransition(incomingTransition, outgoingTransitions);
		
		logger.info("CONTROLLING TRANSITION for NodeType: " + controllingTransition.nodeId + 
		           " (" + controllingTransition.nodeType + ")");

		// STEP 4: Write NodeType ONCE from controlling transition
		AddNodeType(writer, new String[] { controllingTransition.nodeType, controllingTransition.nodeValue });
		logger.info("Wrote NodeType: " + controllingTransition.nodeType);

		// STEP 4b: For JoinNode in PetriNet mode, write JoinInputCount
		if ("JoinNode".equals(controllingTransition.nodeType) && "PetriNet".equalsIgnoreCase(this.processType)) {
		    String joinInputCountStr = controllingTransition.attributes.get("joinInputCount");
		    if (joinInputCountStr != null) {
		        int joinInputCount = Integer.parseInt(joinInputCountStr);
		        AddJoinInputCount(writer, joinInputCount);
		        logger.info("Wrote JoinInputCount: " + joinInputCount + " (PetriNet mode)");
		    }
		}

		// STEP 5: Generate routing rules from OUTGOING transitions
		logger.info("Found " + outgoingTransitions.size() + " outgoing transitions for routing");

		for (TransitionNode outgoingTransition : outgoingTransitions) {
		    logger.info("Processing OUTGOING: " + outgoingTransition.nodeId + " (" + outgoingTransition.nodeType + ")");

		    // Handle GatewayNode - dynamic FORK/EDGE based on decision_value matching
		    if ("GatewayNode".equals(outgoingTransition.nodeType)) {
		        logger.info("Processing GatewayNode routing for " + outgoingTransition.nodeId);
		        processGatewayNode(serviceNode, outgoingTransition, writer);
		    }
		    // Handle ForkNode - unconditional parallel split
		    else if ("ForkNode".equals(outgoingTransition.nodeType)) {
		        logger.info("Processing ForkNode routing for " + outgoingTransition.nodeId);
		        processForkNode(serviceNode, outgoingTransition, writer);
		    }
		    // Handle decision/xor nodes with conditional edges
		    else if (("DecisionNode".equals(outgoingTransition.nodeType) || "XorNode".equals(outgoingTransition.nodeType)) 
		        && hasDecisionEdges(outgoingTransition.nodeId)) {
		        logger.info("Processing conditional routing for " + outgoingTransition.nodeType);
		        processDecisionNodeWithEdges(outgoingTransition, writer, serviceNode);
		    } else {
		        // Standard routing for EdgeNode, TerminateNode, etc.
		        List<ServiceNode> destinationServices = workflowModel.findDestinationServices(serviceNode, outgoingTransition);
		        processDestinationServices(destinationServices, outgoingTransition, writer, serviceNode);
		    }
		}

		logger.info("=== END SERVICE RULE GENERATION ===");

      writer.flush();
      return stringWriter.toString();
	}

	/**
	 * Determine which transition should control the NodeType
	 * 
	 * LOGIC:
	 * - If outgoing transition is ForkNode, GatewayNode or DecisionNode -> use outgoing (controls routing)
	 * - If incoming transition is JoinNode -> use incoming (controls input coordination)
	 * - Otherwise -> use outgoing (default to output behavior)
	 */
	private TransitionNode determineControllingTransition(TransitionNode incomingTransition, 
	                                                       List<TransitionNode> outgoingTransitions) {
	    
	    // Priority 1: ForkNode, GatewayNode or DecisionNode on output -> controls routing behavior
	    for (TransitionNode outgoing : outgoingTransitions) {
	        if ("XorNode".equals(outgoing.nodeType) || "DecisionNode".equals(outgoing.nodeType) 
	                || "GatewayNode".equals(outgoing.nodeType) || "ForkNode".equals(outgoing.nodeType)) {
	            logger.info("DECISION: Using OUTGOING " + outgoing.nodeType + 
	                       " (controls routing behavior)");
	            return outgoing;
	        }
	    }
	    
	    // Priority 2: JoinNode on input -> controls input coordination
	    if ("JoinNode".equals(incomingTransition.nodeType)) {
	        logger.info("DECISION: Using INCOMING JoinNode (controls input coordination)");
	        return incomingTransition;
	    }
	    
	    // Priority 3: Default to first outgoing transition (normal flow)
	    TransitionNode defaultTransition = outgoingTransitions.get(0);
	    logger.info("DECISION: Using OUTGOING " + defaultTransition.nodeType + " (default output behavior)");
	    return defaultTransition;
	}
	/**
	 * Process transition with decision edge detection
	 */
	private void processTransition(ServiceNode serviceNode, TransitionNode transition, PrintWriter writer) {
		logger.info("Processing transition: " + transition);

		// Check if this is a ForkNode with decision edges
		if ("XorNode".equals(transition.nodeType) && hasDecisionEdges(transition.nodeId)) {
			logger.info("DETECTED: ForkNode " + transition.nodeId + " has decision edges - using decision processing");
			processDecisionNodeWithEdges(transition, writer, serviceNode);
			return;
		}

		// Special handling for DecisionNodes with decision edges
		if ("DecisionNode".equals(transition.nodeType)) {
			processDecisionNodeWithEdges(transition, writer, serviceNode);
			return;
		}

		// GatewayNode: Service decides routing at runtime - expose ALL possible destinations
		// No guard conditions needed since the service returns "FORK:target1,target2" or "EDGE:target"
		if ("GatewayNode".equals(transition.nodeType)) {
			logger.info("DETECTED: GatewayNode " + transition.nodeId + " - exposing all destinations without guards");
			processGatewayNode(serviceNode, transition, writer);
			return;
		}

		// ForkNode: Unconditional parallel split - route to ALL destinations
		if ("ForkNode".equals(transition.nodeType)) {
			logger.info("DETECTED: ForkNode " + transition.nodeId + " - unconditional parallel split");
			processForkNode(serviceNode, transition, writer);
			return;
		}

		// Standard processing for all other node types
		AddNodeType(writer, new String[] { transition.nodeType, transition.nodeValue });
		logger.info("Added NodeType: " + transition.nodeType + ":" + transition.nodeValue);

		// Find and process destination services
		List<ServiceNode> destinationServices = workflowModel.findDestinationServices(serviceNode, transition);
		processDestinationServices(destinationServices, transition, writer, serviceNode);
	}

	/**
	 * Process GatewayNode - Dynamic routing based on service response
	 * 
	 * GatewayNode allows the service to decide at runtime whether to:
	 * - FORK to multiple targets (creates child tokens, expects JOIN)
	 * - EDGE to a single target (same token, no JOIN)
	 * 
	 * All possible destinations are exposed in the rules without guard conditions,
	 * since the service response contains the routing directive (e.g., "FORK:P2_Place,P3_Place")
	 */
	/**
	 * Process GatewayNode - Dynamic FORK/EDGE routing based on decision_value matching
	 * 
	 * GatewayNode generates meetsCondition rules WITH decision_value from edges.
	 * At runtime, ServiceThread matches the service's routing_path against decision_value:
	 * - Multiple matches → FORK (parallel split with child tokens)
	 * - Single match → EDGE (same token continues)
	 * 
	 * Example workflow edges:
	 *   T_out_P1 → P2 (decision_value="true")
	 *   T_out_P1 → P3 (decision_value="true")
	 *   T_out_P1 → Monitor (decision_value="false")
	 * 
	 * Generates:
	 *   meetsCondition(P2_Place, processToken, GATEWAY_NODE, true)
	 *   meetsCondition(P3_Place, processToken, GATEWAY_NODE, true)
	 *   meetsCondition(MonitorService, acknowledgeTokenArrival, GATEWAY_NODE, false)
	 * 
	 * At runtime:
	 *   Service returns "true" → matches P2 and P3 → FORK
	 *   Service returns "false" → matches Monitor → EDGE
	 */
	private void processGatewayNode(ServiceNode serviceNode, TransitionNode transition, PrintWriter writer) {
		logger.info("Processing GatewayNode: " + transition.nodeId);

		// Write NodeType
		AddNodeType(writer, new String[] { transition.nodeType, transition.nodeValue });
		logger.info("Added NodeType: " + transition.nodeType + ":" + transition.nodeValue);

		// Find ALL outgoing edges with their decision_values
		List<WorkflowEdge> outgoingEdges = workflowModel.getWorkflowEdges().stream()
				.filter(edge -> edge.fromNode.equals(transition.nodeId))
				.collect(java.util.stream.Collectors.toList());

		logger.info("GatewayNode has " + outgoingEdges.size() + " outgoing edges");

		for (WorkflowEdge edge : outgoingEdges) {
			// Get decision_value from edge attributes - try multiple sources
			String decisionValue = edge.attributes.get("decision_value");
			if (decisionValue == null || decisionValue.isEmpty()) {
				// Try getDecisionValue() method as fallback
				decisionValue = edge.getDecisionValue();
			}
			if (decisionValue == null || decisionValue.isEmpty()) {
				// Try label as final fallback
				decisionValue = edge.attributes.get("label");
			}
			
			logger.info("GatewayNode edge: " + edge.fromNode + " -> " + edge.toNode + 
			           " (decision_value='" + decisionValue + "', attributes=" + edge.attributes + ")");

			ServiceNode destService = findDestinationServiceForEdge(edge);
			
			if (destService != null) {
				String operationToUse = destService.operation;
				String endpoint = edge.attributes.get("endpoint");
				
				if (endpoint != null) {
					logger.info("Edge specifies endpoint: " + endpoint);
					if (multiOpServices.containsKey(destService.nodeId)) {
						if (multiOpServices.get(destService.nodeId).contains(endpoint)) {
							operationToUse = endpoint;
						}
					} else {
						operationToUse = endpoint;
					}
				}

				// Add meetsCondition WITH decision_value for matching at runtime
				AddmeetsCondition(writer, new String[] { destService.service, operationToUse },
						new String[] { transition.nodeType, transition.nodeValue }, 
						decisionValue != null ? decisionValue : "");
				logger.info("GatewayNode: Added meetsCondition for " + destService.service + ":" + operationToUse + 
				           " with decision_value='" + decisionValue + "'");
			} else {
				// Handle direct transitions (T_out -> T_in or T_out -> Terminate)
				TransitionNode nextTransition = workflowModel.getTransitionNodes().get(edge.toNode);
				if (nextTransition != null) {
					// Check if this is a TerminateNode - generate TERMINATE rule
					if ("TerminateNode".equals(nextTransition.nodeType)) {
						AddmeetsCondition(writer, new String[] { "TERMINATE", "TERMINATE" },
								new String[] { transition.nodeType, transition.nodeValue },
								decisionValue != null ? decisionValue : "");
						logger.info("GatewayNode: Added TERMINATE meetsCondition with decision_value='" + 
								   decisionValue + "' (via TerminateNode " + nextTransition.nodeId + ")");
					} else {
						// Regular transition - find service after it
						ServiceNode nextService = findServiceAfterTransition(nextTransition);
						if (nextService != null) {
							String operationToUse = nextService.operation;
							String endpoint = edge.attributes.get("endpoint");
							if (endpoint != null) {
								operationToUse = endpoint;
							}
							// Add meetsCondition WITH decision_value
							AddmeetsCondition(writer, new String[] { nextService.service, operationToUse },
									new String[] { transition.nodeType, transition.nodeValue },
									decisionValue != null ? decisionValue : "");
							logger.info("GatewayNode: Added meetsCondition for " + nextService.service + ":" + operationToUse + 
									   " with decision_value='" + decisionValue + "' (via transition " + nextTransition.nodeId + ")");
							
						}
					}
				}
			}
		}
	}

	/**
	 * Process ForkNode - Unconditional parallel split to ALL destinations
	 */
	private void processForkNode(ServiceNode serviceNode, TransitionNode transition, PrintWriter writer) {
		logger.info("Processing ForkNode: " + transition.nodeId);

		// Write NodeType
		AddNodeType(writer, new String[] { transition.nodeType, transition.nodeValue });
		logger.info("Added NodeType: " + transition.nodeType + ":" + transition.nodeValue);

		// Find ALL destination services
		List<ServiceNode> destinationServices = workflowModel.findDestinationServices(serviceNode, transition);
		
		logger.info("ForkNode routes to " + destinationServices.size() + " destinations");

		for (ServiceNode destService : destinationServices) {
			String operationToUse = findOperationForDestination(transition, destService);
			
			// Add meetsCondition - ForkNode routes to ALL without conditions
			AddmeetsCondition(writer, new String[] { destService.service, operationToUse },
					new String[] { transition.nodeType, transition.nodeValue }, "");
			logger.info("ForkNode: Added meetsCondition for " + destService.service + ":" + operationToUse);
		}
	}

	/**
	 * Check if a transition node has decision edges
	 */
	private boolean hasDecisionEdges(String nodeId) {
		for (WorkflowEdge edge : workflowModel.getWorkflowEdges()) {
			if (edge.fromNode.equals(nodeId) && edge.isDecisionEdge()) {
				logger.debug("Found decision edge from " + nodeId + ": " + edge);
				return true;
			}
		}
		return false;
	}

	/**
	 * Process destination services
	 * ENHANCED: Check for endpoint attributes and use SOA canonical bindings
	 */
	private void processDestinationServices(List<ServiceNode> destinationServices, TransitionNode transition,
			PrintWriter writer, ServiceNode sourceService) {
		// Skip DecisionNodes - they're handled specially in processDecisionNodeWithEdges
		if ("DecisionNode".equals(transition.nodeType)) {
			logger.debug("Skipping processDestinationServices for DecisionNode - handled by conditional fork logic");
			return;
		}

		if (destinationServices.isEmpty()) {
			logger.info("Transition leads to termination - no meetsCondition added");
			return;
		}

		// Standard processing for other node types
		logger.info("Found " + destinationServices.size() + " destination services");

		for (ServiceNode destService : destinationServices) {
			// Find the operation to use (check for endpoint attribute)
			String operationToUse = findOperationForDestination(transition, destService);
			
			// FIXED: Pass empty string for decisionValue (EdgeNodes don't have decision values)
			AddmeetsCondition(writer, new String[] { destService.service, operationToUse },
					new String[] { transition.nodeType, transition.nodeValue }, "");
			logger.info("Added meetsCondition: " + destService.service + ":" + operationToUse + " via "
					+ transition.nodeType + ":" + transition.nodeValue);
		}
	}

	/**
	 * Find the correct operation for a destination service
	 * NEW: Check for endpoint attributes on edges
	 */
	private String findOperationForDestination(TransitionNode fromTransition, ServiceNode toService) {
		logger.debug("Finding operation for path from " + fromTransition.nodeId + " to " + toService.nodeId);
		
		// Look for edges from the transition that might have endpoints
		for (WorkflowEdge edge : workflowModel.getWorkflowEdges()) {
			if (edge.fromNode.equals(fromTransition.nodeId)) {
				String endpoint = edge.attributes.get("endpoint");
				if (endpoint != null) {
					// Check if this edge eventually leads to our service
					if (pathLeadsToService(edge.toNode, toService.nodeId)) {
						logger.info("Found endpoint '" + endpoint + "' on path to " + toService.nodeId);
						// Validate endpoint is valid for multi-op service
						if (multiOpServices.containsKey(toService.nodeId)) {
							if (multiOpServices.get(toService.nodeId).contains(endpoint)) {
								return endpoint;
							} else {
								logger.warn("Endpoint " + endpoint + " not valid for " + toService.service);
							}
						}
						return endpoint;
					}
				}
			}
		}
		
		// No endpoint found - use default
		if (multiOpServices.containsKey(toService.nodeId)) {
			String defaultOp = multiOpServices.get(toService.nodeId).get(0);
			logger.info("No endpoint found, using default operation: " + defaultOp);
			return defaultOp;
		}
		
		return toService.operation;
	}

	/**
	 * Check if a path leads from one node to a service
	 */
	private boolean pathLeadsToService(String fromNode, String toServiceId) {
		// Direct connection
		if (fromNode.equals(toServiceId)) {
			return true;
		}
		
		// One hop
		for (WorkflowEdge edge : workflowModel.getWorkflowEdges()) {
			if (edge.fromNode.equals(fromNode) && edge.toNode.equals(toServiceId)) {
				return true;
			}
		}
		
		// Two hops
		for (WorkflowEdge edge1 : workflowModel.getWorkflowEdges()) {
			if (edge1.fromNode.equals(fromNode)) {
				for (WorkflowEdge edge2 : workflowModel.getWorkflowEdges()) {
					if (edge2.fromNode.equals(edge1.toNode) && edge2.toNode.equals(toServiceId)) {
						return true;
					}
				}
			}
		}
		
		return false;
	}

	/**
	 * Process DecisionNode OR ForkNode with decision edges using fork group support
	 * Multiple edges with same condition+decision_value = fork group
	 */
	private void processDecisionNodeWithEdges(TransitionNode decisionNode, PrintWriter writer, ServiceNode sourceService) {
	    logger.info("Processing conditional fork groups for: " + decisionNode.nodeId);

	    // Find all decision edges
	    List<WorkflowEdge> decisionEdges = workflowModel.getWorkflowEdges().stream()
	            .filter(edge -> edge.fromNode.equals(decisionNode.nodeId))
	            .filter(WorkflowEdge::isDecisionEdge)
	            .collect(java.util.stream.Collectors.toList());

	    if (decisionEdges.isEmpty()) {
	        logger.warn("No decision edges found for " + decisionNode.nodeId);
	        return;
	    }

	    // Group edges by condition+decision_value
	    Map<String, List<WorkflowEdge>> forkGroups = groupEdgesByCondition(decisionEdges);

	    // Process each fork group - DO NOT write NodeType here!
	    for (Map.Entry<String, List<WorkflowEdge>> forkGroup : forkGroups.entrySet()) {
	        processConditionalForkGroup(forkGroup.getKey(), forkGroup.getValue(), decisionNode, writer, sourceService);
	    }
	}

	private void processConditionalForkGroup(String groupKey, List<WorkflowEdge> forkGroupEdges,
	        TransitionNode decisionNode, PrintWriter writer, ServiceNode sourceService) {

	    logger.info("Processing fork group: " + groupKey + " with " + forkGroupEdges.size() + " services");

	    String[] keyParts = groupKey.split(":", 2);
	    String condition = keyParts[0];
	    String decisionValue = keyParts.length > 1 ? keyParts[1] : "";

	    // DO NOT write NodeType here - already written from incoming transition!
	    // Only write DecisionValue and meetsCondition atoms

	    // Add DecisionValue
	    if (decisionValue != null && !decisionValue.isEmpty()) {
	        AddDecisionValue(writer, new String[] { decisionNode.nodeType, decisionValue });
	        logger.info("Added DecisionValue: " + decisionValue);
	    }

	    // Add meetsCondition for each service in fork group
	    int servicesAdded = 0;
	    for (WorkflowEdge edge : forkGroupEdges) {
	        if (processConditionalForkDestination(edge, decisionNode, condition, decisionValue, writer, sourceService)) {
	            servicesAdded++;
	        }
	    }

	    logger.info("Fork group '" + groupKey + "' routes to " + servicesAdded + " services");
	}
	/**
	 * Group decision edges by condition+decision_value
	 * Same condition+value = fork group (multiple services called together)
	 */
	private Map<String, List<WorkflowEdge>> groupEdgesByCondition(List<WorkflowEdge> decisionEdges) {
		Map<String, List<WorkflowEdge>> forkGroups = new LinkedHashMap<>();

		for (WorkflowEdge edge : decisionEdges) {
			String condition = edge.getCondition();
			String decisionValue = edge.getDecisionValue();

			// Create unique key for this condition+value combination
			String groupKey = condition + ":" + decisionValue;

			forkGroups.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(edge);

			logger.debug("Added edge " + edge.fromNode + " -> " + edge.toNode + " to fork group: " + groupKey);
		}

		return forkGroups;
	}

	
	/**
	 * Handle individual service in fork group
	 * ENHANCED: Check for endpoint attribute
	 * FIXED: Now accepts decisionValue parameter for proper XOR routing
	 * FIXED: Now handles TerminateNode transitions (like T14) - not just literal "END" strings
	 */
	private boolean processConditionalForkDestination(WorkflowEdge edge, TransitionNode decisionNode, String condition,
			String decisionValue, PrintWriter writer, ServiceNode sourceService) {

		logger.debug("Processing fork destination: " + edge.fromNode + " -> " + edge.toNode);

		// Handle termination paths - check for literal strings first
		if ("END".equals(edge.toNode) || "TERMINATE_NODE".equals(edge.toNode)) {
			AddTerminationCondition(writer, new String[] { decisionNode.nodeType, condition });
			logger.info("Added termination condition: " + condition + " for path to " + edge.toNode);
			return true;
		}
		
		// FIXED: Check if target is a TerminateNode transition (like T14)
		// This mirrors the GatewayNode logic for handling TERMINATE paths
		TransitionNode targetTransition = workflowModel.getTransitionNodes().get(edge.toNode);
		if (targetTransition != null && "TerminateNode".equals(targetTransition.nodeType)) {
			// Add meetsCondition for TERMINATE with decision_value for XOR matching
			AddmeetsCondition(writer, new String[] { "TERMINATE", "TERMINATE" },
					new String[] { decisionNode.nodeType, condition }, decisionValue);
			logger.info("XorNode: Added TERMINATE meetsCondition with decision_value='" + 
					   decisionValue + "' (via TerminateNode " + targetTransition.nodeId + ")");
			return true;
		}

		// FIXED: Use merged method for finding destination service
		ServiceNode destinationService = findDestinationServiceForEdge(edge);

		if (destinationService != null) {
			// Check for endpoint attribute
			String operationToUse = destinationService.operation;
			String endpoint = edge.attributes.get("endpoint");
			
			if (endpoint != null) {
				logger.info("Edge specifies endpoint: " + endpoint);
				// Validate endpoint for multi-op service
				if (multiOpServices.containsKey(destinationService.nodeId)) {
					if (multiOpServices.get(destinationService.nodeId).contains(endpoint)) {
						operationToUse = endpoint;
						logger.info("Using endpoint: " + endpoint);
					} else {
						logger.warn("Endpoint " + endpoint + " not valid for " + destinationService.service);
					}
				} else {
					operationToUse = endpoint;
				}
			} else if (multiOpServices.containsKey(destinationService.nodeId)) {
				// No endpoint specified for multi-op service - use default
				operationToUse = multiOpServices.get(destinationService.nodeId).get(0);
				logger.info("No endpoint specified, using default: " + operationToUse);
			}
			
			// FIXED: Pass decisionValue as 4th argument for proper XOR routing alignment
			AddmeetsCondition(writer, new String[] { destinationService.service, operationToUse },
					new String[] { decisionNode.nodeType, condition }, decisionValue);

			logger.info("[OK] Added meetsCondition: " + destinationService.service + ":"
					+ operationToUse + " via " + condition + " with decisionValue=" + decisionValue);
			
			return true;
		} else {
			logger.warn("Cannot create rule - destination service not found for edge: " + edge);
			return false;
		}
	}

	/**
	 * FIXED: Merged method for finding destination services from edges
	 * Replaces both findDestinationServiceForConditionalFork and findDestinationServiceForDecisionEdge
	 */
	private ServiceNode findDestinationServiceForEdge(WorkflowEdge edge) {
		logger.debug("Finding destination service for edge: " + edge.fromNode + " -> " + edge.toNode);

		// Check for explicit target service in edge attributes
		if (edge.hasTargetService()) {
			ServiceNode explicitTarget = new ServiceNode(
					edge.getTargetService() + "_" + edge.getTargetOperation(), edge.getTargetService(),
					edge.getTargetOperation(), new HashMap<>());
			logger.debug("Found explicit target service: " + explicitTarget.service);
			return explicitTarget;
		}

		// Check if target is directly a service
		ServiceNode directService = workflowModel.getServiceNode(edge.toNode);
		if (directService != null) {
			logger.debug("Found direct service: " + directService.service);
			return directService;
		}

		// Find service reachable through intermediate transitions
		ServiceNode reachableService = findServiceReachableFrom(edge.toNode);
		if (reachableService != null) {
			logger.debug("Found reachable service: " + reachableService.service);
		}
		return reachableService;
	}

	/**
	 * Find the service node that comes after a transition node
	 * Used by GatewayNode to find destinations when edges go T_out -> T_in
	 */
	private ServiceNode findServiceAfterTransition(TransitionNode transition) {
		logger.debug("Finding service after transition: " + transition.nodeId);
		
		// Find edges FROM this transition TO services
		for (WorkflowEdge edge : workflowModel.getWorkflowEdges()) {
			if (edge.fromNode.equals(transition.nodeId)) {
				ServiceNode service = workflowModel.getServiceNode(edge.toNode);
				if (service != null) {
					logger.debug("Found service after transition: " + service.service);
					return service;
				}
			}
		}
		
		return null;
	}

	/**
	 * Find service reachable from a starting node (LIMITED HOP VERSION)
	 * This was the key fix that solved the RadiologyService issue
	 */
	private ServiceNode findServiceReachableFrom(String startNodeId) {
		logger.debug("Finding service from: " + startNodeId + " (limited hop version)");

		// Direct check: Is the start node itself a service?
		ServiceNode directService = workflowModel.getServiceNode(startNodeId);
		if (directService != null) {
			logger.debug("Start node is itself a service: " + directService.service);
			return directService;
		}

		// ONE-HOP: Check immediate neighbors
		for (WorkflowEdge edge : workflowModel.getWorkflowEdges()) {
			if (edge.fromNode.equals(startNodeId)) {
				logger.debug("Checking immediate neighbor: " + edge.toNode);
				ServiceNode immediateService = workflowModel.getServiceNode(edge.toNode);
				if (immediateService != null) {
					logger.debug("Found immediate service: " + immediateService.service + ":" + immediateService.operation);
					return immediateService;
				}
			}
		}

		// TWO-HOP: Only if no immediate service found
		logger.debug("No immediate service found, checking two-hop paths...");
		for (WorkflowEdge edge1 : workflowModel.getWorkflowEdges()) {
			if (edge1.fromNode.equals(startNodeId)) {
				String intermediateNode = edge1.toNode;
				logger.debug("Via intermediate: " + intermediateNode);

				for (WorkflowEdge edge2 : workflowModel.getWorkflowEdges()) {
					if (edge2.fromNode.equals(intermediateNode)) {
						ServiceNode twoHopService = workflowModel.getServiceNode(edge2.toNode);
						if (twoHopService != null) {
							logger.debug("Found two-hop service: " + twoHopService.service + ":"
									+ twoHopService.operation + " via " + intermediateNode);
							return twoHopService;
						}
					}
				}
			}
		}

		logger.warn("No service found within 2 hops from: " + startNodeId);
		return null;
	}

	/**
	 * FIXED: Fail fast on DecisionNodes without proper decision edges
	 */
	private void processFallbackDecisionNode(TransitionNode decisionNode, PrintWriter writer)
			throws RuleDeployerException {

		// Fail fast instead of using fallback processing
		String errorMessage = String.format(
				"DecisionNode '%s' has no decision edges. "
						+ "Please add condition and decision_value attributes to outgoing edges in JSON file.",
				decisionNode.nodeId);

		logger.error(errorMessage);
		throw new RuleDeployerException(errorMessage);
	}

	/**
	 * Send service payload
	 */
	/**
	 * Send service payload
	 */
	private void sendServicePayload(ServiceNode sourceService, ServiceNode ruleTarget, String ruleContent)
			throws RuleDeployerException {
		try {
			String rulePayLoadFileName = buildRulePayloadFileName();
			String payloadContent = createServicePayload(sourceService, ruleTarget, ruleContent,
					rulePayLoadFileName);
			boolean commitmentConfirmed = sendAndWaitForCommitment(payloadContent, ruleTarget.service,
					ruleTarget.operation, serviceOperationPairCount);
			if (!commitmentConfirmed) {
				throw new RuleDeployerException(String.format("No commitment received for service: %s:%s",
						sourceService.service, sourceService.operation));
			}
		} catch (IOException e) {
			throw new RuleDeployerException("Error sending service payload", e);
		}
	}
	private String buildRulePayloadFileName() throws IOException {
		File commonBase = new File("../");
		String commonPath = commonBase.getCanonicalPath();
		// Normalize path to OS-native separators
		File payloadFile = new File(commonPath + "/" + Config.RULE_PAYLOAD_FOLDER + "/" + processName + "_rulepayload.xml");
		return payloadFile.getAbsolutePath();
	}

	/**
	 * Returns the path to the payload file to use, creating it from template if needed.
	 * Creates directories and payload file if missing.
	 * 
	 * SIMPLIFIED (2024): Template is now always read from root RulePayLoad folder.
	 * Previously searched up directory tree for domain-specific templates, but since
	 * all domains use the same template, we now just look in RulePayLoad/rulePayload_template.xml
	 */
	private String getPayloadFilePath(String rulePayLoadFileName) throws IOException {
		File payloadFile = new File(rulePayLoadFileName);
		String normalizedPath = payloadFile.getAbsolutePath();  // Normalize path separators

		if (payloadFile.exists()) {
			return normalizedPath;
		}
		
		logger.debug("Payload file not found, will create from template: " + normalizedPath);

		// Create parent directories if needed
		File parentDir = payloadFile.getParentFile();
		if (parentDir != null && !parentDir.exists()) {
			parentDir.mkdirs();
		}

		// Find the root RulePayLoad directory
		File rulePayLoadDir = findRulePayLoadDirectory(rulePayLoadFileName);
		if (rulePayLoadDir == null) {
			throw new IOException("Could not find RulePayLoad directory in path: " + rulePayLoadFileName);
		}
		
		// Template is always in root RulePayLoad folder (same for all domains)
		File templateFile = new File(rulePayLoadDir, "rulePayload_template.xml");

		if (templateFile.exists()) {
			logger.debug("Using template from RulePayLoad root: " + templateFile.getAbsolutePath());
			
			// Read template and write to payload file location
			String templateContent = StringFileIO.readFileAsString(templateFile.getAbsolutePath());
			StringFileIO.writeStringToFile(templateContent, normalizedPath, templateContent.length());
			
			logger.info("Created payload file from template: " + normalizedPath);
			return normalizedPath;
		} else {
			String errorMsg = String.format(
					"Rule payload template not found.\n" + 
					"Expected location: %s\n" +
					"Please ensure rulePayload_template.xml exists in the RulePayLoad root directory.",
					templateFile.getAbsolutePath());

			logger.error(errorMsg);
			throw new IOException(errorMsg);
		}
	}
	
	/*
	 * DEPRECATED: findTemplateFile - no longer used
	 * Previously searched up directory tree for domain-specific templates.
	 * Kept for reference in case domain-specific templates are needed in future.
	 *
	private File findTemplateFile(File startDir, String templateName) {
		File currentDir = startDir;
		
		// If startDir doesn't exist, we need to walk up to find an existing parent
		while (currentDir != null && !currentDir.exists()) {
			currentDir = currentDir.getParentFile();
		}
		
		while (currentDir != null) {
			File templateFile = new File(currentDir, templateName);
			if (templateFile.exists()) {
				logger.debug("Found template at: " + templateFile.getAbsolutePath());
				return templateFile;
			}
			
			// Stop AFTER checking the RulePayLoad folder (don't go higher)
			if (currentDir.getName().equals("RulePayLoad")) {
				logger.debug("Reached RulePayLoad folder, template not found there either");
				break;
			}
			
			currentDir = currentDir.getParentFile();
		}
		
		return null;
	}
	*/
	
	/**
	 * Finds the RulePayLoad directory from a payload file path
	 */
	private File findRulePayLoadDirectory(String payloadPath) {
		File current = new File(payloadPath).getParentFile();
		while (current != null) {
			if (current.getName().equals("RulePayLoad")) {
				return current;
			}
			current = current.getParentFile();
		}
		return null;
	}



	private int deployStandaloneMonitorNodes() throws RuleDeployerException {
		logger.info("=== DEPLOYING STANDALONE MONITOR NODES ===");

		List<TransitionNode> standaloneMonitorNodes = workflowModel.findStandaloneMonitorNodes();

		for (TransitionNode monitorNode : standaloneMonitorNodes) {
			deployStandaloneMonitorNode(monitorNode);
		}

		return standaloneMonitorNodes.size();
	}

	private void deployStandaloneMonitorNode(TransitionNode monitorNode) throws RuleDeployerException {
		logger.info("*** DEPLOYING STANDALONE MONITOR NODE: " + monitorNode.nodeId + " ***");
		logger.info("*** Note: If MonitorService is needed, add it as P_Monitor place in JSON file ***");

		logger.info("Monitor node " + monitorNode.nodeId
				+ " found but no P_Monitor service place defined in JSON file");
		logger.info("To enable monitor functionality, add a service place like:");
		logger.info("{ \"type\": \"PLACE\", \"id\": \"P_Monitor\", \"service\": \"MonitorService\", \"operation\": \"setMeasureProcessTime\" }");
	}

	/**
	 * Network management with robust socket handling
	 */
	private boolean sendAndWaitForCommitment(String payload, String serviceName, String operationName,
	        int commitmentCount) throws RuleDeployerException {

	    String commitmentKey = buildVersion + ":" + commitmentCount;
	    
	    // Check if we already have a commitment
	    if (Boolean.TRUE.equals(commitmentReceived.get(commitmentKey))) {
	        logger.info("Commitment already received for " + serviceName + ":" + operationName);
	        return true;
	    }
	    
	    // Initialize to false only if not already set
	    commitmentReceived.putIfAbsent(commitmentKey, Boolean.FALSE);

	    for (int attempt = 1; attempt <= Config.MAX_RETRIES; attempt++) {
	        logger.info("Sending payload for " + serviceName + ":" + operationName + 
	                   " (commitment: " + commitmentCount + "), attempt: " + attempt);

	        sendRulePayload(payload, serviceName, operationName);

	        if (waitForCommitment(commitmentKey, serviceName, operationName)) {
	            return true;
	        }

	        logger.warn("No commitment received for " + serviceName + ":" + operationName + 
	                   " after " + Config.COMMITMENT_TIMEOUT_MS + "ms, retries left: " + 
	                   (Config.MAX_RETRIES - attempt));
	    }

	    return false;
	}
	private boolean waitForCommitment(String commitmentKey, String serviceName, String operationName) {
		long startTime = System.currentTimeMillis();

		while (System.currentTimeMillis() - startTime < Config.COMMITMENT_TIMEOUT_MS) {
			if (Boolean.TRUE.equals(commitmentReceived.get(commitmentKey))) {
				logger.debug("Commitment confirmed for " + serviceName + ":" + operationName + " in "
						+ (System.currentTimeMillis() - startTime) + "ms");
				return true;
			}

			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				logger.warn("Interrupted while waiting for commitment for " + serviceName + ":" + operationName);
				return false;
			}
		}

		return false;
	}

	/**
	 * Robust UDP payload sending
	 */
	private void sendRulePayload(String payload, String serviceName, String operationName)
	        throws RuleDeployerException {

	    DatagramSocket socket = null;

	    try {
	        socket = createSocketWithRetry(serviceName, operationName);

	        InetAddress targetAddress;
	        String targetHost;
	        
	        if (this.useRemoteHost && this.resolvedChannelAddress != null) {
	            targetHost = this.resolvedChannelAddress;
	            targetAddress = InetAddress.getByName(targetHost);
	            logger.info("Using remote host: " + targetHost);
	        } else {
	            targetHost = "localhost";
	            targetAddress = InetAddress.getLoopbackAddress();
	        }
	        
	        int targetPort = calculateTargetPort();
	        logPortCalculation(serviceName, operationName, targetPort);

	        byte[] data = payload.getBytes();
	        DatagramPacket packet = new DatagramPacket(data, data.length, targetAddress, targetPort);
	        socket.send(packet);

	        logger.info("Sent UDP payload to " + targetHost + ":" + targetPort + 
	                   " (RuleHandler for " + serviceName + ":" + operationName + 
	                   ", channel " + extractChannelNumber(ruleChannel) + ")");

	    } catch (IOException e) {
	        throw new RuleDeployerException("I/O error sending rule payload", e);
	    } finally {
	        closeSocket(socket);
	    }
	}

	private DatagramSocket createSocketWithRetry(String serviceName, String operationName)
			throws RuleDeployerException {

		for (int attempt = 1; attempt <= Config.MAX_SOCKET_RETRIES; attempt++) {
			try {
				// Create unbound socket, set reuse address, then bind to ephemeral port
				DatagramSocket socket = new DatagramSocket(null);
				socket.setReuseAddress(true);
				socket.bind(null);  // Bind to any available port
				socket.setSoTimeout(Config.SOCKET_TIMEOUT_MS);
				return socket;

			} catch (SocketException e) {
				logger.warn("Socket creation attempt " + attempt + " failed for " + serviceName + ":"
						+ operationName + " - " + e.getMessage());

				if (attempt >= Config.MAX_SOCKET_RETRIES) {
					throw new RuleDeployerException(String.format("Failed to create UDP socket after %d attempts",
							Config.MAX_SOCKET_RETRIES), e);
				}

				sleepWithBackoff(attempt);
			}
		}

		throw new RuleDeployerException("Unexpected error in socket creation retry loop");
	}

	private String queryServiceHost(String serviceName) {
	    try {
	        // DON'T load anything - Service.ruleml is already loaded!
	        // Just query for serviceHost
	        String query = String.format(
	            "<Query><Atom><Rel>serviceHost</Rel><Ind>%s</Ind><Var>host</Var></Atom></Query>",
	            serviceName
	        );
	        
	        oojdrew.issueRuleMLQuery(query);
	        
	        if (oojdrew.rowsReturned > 0) {
	            for (int i = 0; i < oojdrew.rowsReturned; i++) {
	                String key = String.valueOf(oojdrew.rowData[i][0]);
	                String value = String.valueOf(oojdrew.rowData[i][1]);
	                if ("?host".equals(key)) {
	                    logger.info("Found host for " + serviceName + ": " + value);
	                    return value;
	                }
	            }
	        }
	    } catch (Exception e) {
	        logger.debug("Could not query host for " + serviceName + ": " + e.getMessage());
	    }
	    
	    return "localhost"; // Default
	}
	
	private void sleepWithBackoff(int attempt) {
		try {
			Thread.sleep(Config.RETRY_BACKOFF_BASE_MS * attempt);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException("Interrupted during socket creation retry", e);
		}
	}

	private int calculateTargetPort() {
		int channelNumber = extractChannelNumber(ruleChannel);
		int port = Integer.parseInt(rulePort);
		int channelOffset = channelNumber * Config.CHANNEL_OFFSET_MULTIPLIER;
		return Config.BASE_RULE_PORT + channelOffset + port;
	}

	private int extractChannelNumber(String rChannel) {
	    try {
	        // For "ip1", "ip2", etc - extract the number
	        if (rChannel != null && rChannel.startsWith("ip")) {
	            return Integer.parseInt(rChannel.substring(2));
	        }
	        
	        // For multicast "224.1.x.y" - extract from 4th octet
	        String[] channelParts = rChannel.split("\\.");
	        return channelParts.length >= 4 ? Integer.parseInt(channelParts[3]) : 1;
	    } catch (Exception e) {
	        logger.warn("Could not parse channel number from: " + rChannel + ", using default 1");
	        return 1;
	    }
	}

	/**
	 * FIXED: Cleaner port calculation logging
	 */
	private void logPortCalculation(String serviceName, String operationName, int targetPort) {
		int channelNumber = extractChannelNumber(ruleChannel);
		int basePort = Integer.parseInt(rulePort);
		int channelOffset = channelNumber * Config.CHANNEL_OFFSET_MULTIPLIER;

		logger.info("=== Port Calculation for " + serviceName + " ===");
		logger.info("Channel: " + ruleChannel + " (#" + channelNumber + ")");
		logger.info("Base Port: " + basePort);
		logger.info("Channel Offset: " + channelOffset + " (channel x " + Config.CHANNEL_OFFSET_MULTIPLIER + ")");
		logger.info("Target Port: " + targetPort + " (" + Config.BASE_RULE_PORT + " + " + channelOffset + " + "
				+ basePort + ")");
	}

	private void closeSocket(DatagramSocket socket) {
		if (socket != null && !socket.isClosed()) {
			try {
				socket.close();
			} catch (Exception e) {
				logger.warn("Error closing UDP socket: " + e.getMessage());
			}
		}
	}

	// Static utility methods and commitment handling
	public static void signalCommitmentReceived(String buildVersion, int commitmentCount) {
		String key = buildVersion + ":" + commitmentCount;
		commitmentReceived.put(key, Boolean.TRUE);
		logger.info("Commitment received for version: " + buildVersion + ", commitment: " + commitmentCount);
	}

	public static void sendSync(String rChannel, String rPort, String buildVersion) throws RuleDeployerException {
		DatagramSocket socket = null;
		try {
			// Create unbound socket, set reuse address, then bind to ephemeral port
			socket = new DatagramSocket(null);
			socket.setReuseAddress(true);
			socket.bind(null);  // Bind to any available port
			InetAddress targetAddress = InetAddress.getLoopbackAddress();

			int channelNumber = extractChannelNumberStatic(rChannel);
			int basePort = Integer.parseInt(rPort);
			int channelOffset = channelNumber * Config.SYNC_CHANNEL_OFFSET_MULTIPLIER;
			int targetPort = Config.BASE_SYNC_PORT + channelOffset + (basePort % 100);

			byte[] data = buildVersion.getBytes();
			DatagramPacket packet = new DatagramPacket(data, data.length, targetAddress, targetPort);

			socket.send(packet);
			logger.debug("Sent UDP sync message to localhost:" + targetPort + " (RuleCorrelator for channel "
					+ channelNumber + ", base " + rPort + "): " + buildVersion);

		} catch (SocketException e) {
			throw new RuleDeployerException("Socket error sending sync message", e);
		} catch (IOException e) {
			throw new RuleDeployerException("I/O error sending sync message", e);
		} finally {
			if (socket != null && !socket.isClosed()) {
				socket.close();
			}
		}
	}

	private static int extractChannelNumberStatic(String rChannel) {
		try {
			String[] channelParts = rChannel.split("\\.");
			return channelParts.length >= 4 ? Integer.parseInt(channelParts[3]) : 1;
		} catch (Exception e) {
			logger.warn("Could not parse channel number from: " + rChannel + ", using default 1");
			return 1;
		}
	}

	private void getServiceRuleChannel(String serviceName, String operationName, String buildVersion)
	        throws RuleDeployerException {
	    
	    // Reset the remote host flags
	    this.resolvedChannelAddress = null;
	    this.useRemoteHost = false;
	    
	    String channelId = null;
	    boolean isActiveService = false;
	    
	    try {
	        // STEP 1: First try activeService lookup (from ListofActiveServices.xml)
	        logger.info("Step 1: Checking activeService facts for " + serviceName + ":" + operationName);
	        
	        String activeServiceQuery = String.format(
	                "<Query><Atom><Rel>activeService</Rel><Ind>%s</Ind><Ind>%s</Ind><Var>channelId</Var><Var>port</Var></Atom></Query>",
	                serviceName, operationName);

	        // Use dynamically derived common folder path
	        oojdrew.parseKnowledgeBase("../" + Config.COMMON_FOLDER + "/RuleFolder." + buildVersion + "/Service.ruleml", true);
	        oojdrew.issueRuleMLQuery(activeServiceQuery);

	        if (oojdrew.rowsReturned > 0) {
	            // Found in activeService - this might be remote!
	            isActiveService = true;
	            logger.info("[OK] Found activeService entry for " + serviceName + ":" + operationName);
	            
	            // Parse the activeService results
	            for (int i = 0; i < oojdrew.rowsReturned; i++) {
	                String key = String.valueOf(oojdrew.rowData[i][0]);
	                String value = String.valueOf(oojdrew.rowData[i][1]);
	                
	                switch (key) {
	                case "?channelId":
	                    channelId = value;  // e.g., "ip1"
	                    logger.info("  Channel ID from activeService: " + channelId);
	                    break;
	                case "?port":
	                    this.rulePort = value;
	                    logger.info("  Port from activeService: " + this.rulePort);
	                    break;
	                }
	            }
	        } else {
	            // STEP 2: Fall back to hasOperation lookup (from ListofServices.xml)
	            logger.info("Step 2: No activeService found, checking hasOperation facts for " + serviceName + ":" + operationName);
	            
	            String hasOperationQuery = String.format(
	                    "<Query><Atom><Rel>hasOperation</Rel><Ind>%s</Ind><Ind>%s</Ind><Var>channelId</Var><Var>port</Var></Atom></Query>",
	                    serviceName, operationName);
	            
	            oojdrew.issueRuleMLQuery(hasOperationQuery);
	            
	            if (oojdrew.rowsReturned > 0) {
	                logger.info("[OK] Found hasOperation entry for " + serviceName + ":" + operationName);
	                
	                // Parse the hasOperation results
	                for (int i = 0; i < oojdrew.rowsReturned; i++) {
	                    String key = String.valueOf(oojdrew.rowData[i][0]);
	                    String value = String.valueOf(oojdrew.rowData[i][1]);
	                    
	                    switch (key) {
	                    case "?channelId":
	                        channelId = value;  // e.g., "a1", "a2", etc.
	                        logger.info("  Channel ID from hasOperation: " + channelId);
	                        break;
	                    case "?port":
	                        this.rulePort = value;
	                        logger.info("  Port from hasOperation: " + this.rulePort);
	                        break;
	                    }
	                }
	            }
	        }

	    } catch (Exception e) {
	        throw new RuleDeployerException(String.format(
	                "Can't find or query rule knowledge base: %s/RuleFolder.%s/Service.ruleml", Config.COMMON_FOLDER, buildVersion), e);
	    }

	    // Validate we found something
	    if (channelId == null || this.rulePort == null) {
	        throw new RuleDeployerException(String.format(
	                "Service not found in activeService or hasOperation facts: %s:%s", 
	                serviceName, operationName));
	    }

	    // STEP 3: Now resolve the channel ID to an actual address
	    // This will check both RemoteHostMappings.xml (for ip1->192.168.1.82) 
	    // and NetworkFacts.xml (for a1->224.0.1.1)
	    resolveChannelAddress(channelId, serviceName, operationName, isActiveService);
	}
	
	/**
	 * Resolve channel ID to actual network address
	 * Checks boundChannel facts which could be:
	 * - Remote IP addresses (from RemoteHostMappings.xml)
	 * - Multicast addresses (from NetworkFacts.xml)
	 */
	private void resolveChannelAddress(String channelId, String serviceName, String operationName, boolean isActiveService)
	        throws RuleDeployerException {
	    
	    logger.info("Step 3: Resolving channel ID '" + channelId + "' via boundChannel facts");
	    
	    try {
	        String boundChannelQuery = String.format(
	                "<Query><Atom><Rel>boundChannel</Rel><Ind>%s</Ind><Var>channel</Var></Atom></Query>", 
	                channelId);

	        oojdrew.issueRuleMLQuery(boundChannelQuery);

	        if (oojdrew.rowsReturned == 0) {
	            throw new RuleDeployerException(String.format(
	                    "No boundChannel mapping found for channelId: %s (service %s:%s)", 
	                    channelId, serviceName, operationName));
	        }

	        // Parse boundChannel results
	        for (int i = 0; i < oojdrew.rowsReturned; i++) {
	            String key = String.valueOf(oojdrew.rowData[i][0]);
	            String value = String.valueOf(oojdrew.rowData[i][1]);

	            if ("?channel".equals(key)) {
	                this.resolvedChannelAddress = value;
	                
	                // Determine if this is a remote host or multicast
	                if (isIPAddress(value)) {
	                    // It's an IP address - this is a remote host!
	                    this.useRemoteHost = true;
	                    this.ruleChannel = channelId;  // Keep the channel ID (e.g., "ip1")
	                    
	                    logger.info("[OK] REMOTE HOST DEPLOYMENT DETECTED");
	                    logger.info("  Service: " + serviceName + ":" + operationName);
	                    logger.info("  Channel ID: " + channelId);
	                    logger.info("  Remote Host: " + value);
	                    logger.info("  Port: " + this.rulePort);
	                    
	                } else if (value.startsWith("224.")) {
	                    // It's a multicast address - local deployment
	                    this.useRemoteHost = false;
	                    this.ruleChannel = normalizeRuleChannel(value);
	                    
	                    logger.info("[OK] LOCAL DEPLOYMENT (multicast)");
	                    logger.info("  Service: " + serviceName + ":" + operationName);
	                    logger.info("  Channel ID: " + channelId);
	                    logger.info("  Multicast: " + value);
	                    logger.info("  Normalized: " + this.ruleChannel);
	                    logger.info("  Port: " + this.rulePort);
	                    
	                } else {
	                    // Unexpected format
	                    logger.warn("Unexpected channel format: " + value + " for channelId: " + channelId);
	                    this.ruleChannel = value;
	                }
	                
	                break;
	            }
	        }

	        if (this.ruleChannel == null && this.resolvedChannelAddress == null) {
	            throw new RuleDeployerException(String.format(
	                    "Failed to resolve channel address for channelId: %s (service %s:%s)", 
	                    channelId, serviceName, operationName));
	        }

	    } catch (Exception e) {
	        if (e instanceof RuleDeployerException) {
	            throw (RuleDeployerException) e;
	        }
	        throw new RuleDeployerException(String.format(
	                "Error querying boundChannel for channelId %s: %s",
	                channelId, e.getMessage()), e);
	    }
	    
	    // Log final resolution
	    logger.info("=== CHANNEL RESOLUTION COMPLETE ===");
	    logger.info("Service: " + serviceName + ":" + operationName);
	    logger.info("From: " + (isActiveService ? "activeService" : "hasOperation") + " facts");
	    logger.info("Channel ID: " + channelId);
	    logger.info("Resolved to: " + (this.useRemoteHost ? "REMOTE " + this.resolvedChannelAddress : "LOCAL"));
	    logger.info("Rule Channel: " + this.ruleChannel);
	    logger.info("Rule Port: " + this.rulePort);
	}

	/**
	 * Check if a string is an IP address (IPv4)
	 */
	private boolean isIPAddress(String value) {
	    if (value == null) return false;
	    
	    // Simple IPv4 check - could be enhanced for IPv6
	    String[] parts = value.split("\\.");
	    if (parts.length != 4) return false;
	    
	    try {
	        for (String part : parts) {
	            int num = Integer.parseInt(part);
	            if (num < 0 || num > 255) return false;
	        }
	        // Check it's not a multicast address (224.x.x.x - 239.x.x.x)
	        int firstOctet = Integer.parseInt(parts[0]);
	        return firstOctet < 224 || firstOctet > 239;
	    } catch (NumberFormatException e) {
	        return false;
	    }
	}

	
	private void parseOOjDREWResults(String serviceName, String operationName) throws RuleDeployerException {
	    this.ruleChannel = null;
	    this.rulePort = null;
	    String channelId = null;

	    for (int i = 0; i < oojdrew.rowsReturned; i++) {
	        String key = String.valueOf(oojdrew.rowData[i][0]);
	        String value = String.valueOf(oojdrew.rowData[i][1]);

	        switch (key) {
	        case "?channelId":
	            channelId = value;
	            break;
	        case "?port":
	            this.rulePort = value;
	            break;
	        default:
	            logger.warn("Unexpected data in knowledge base query result: " + key);
	        }
	    }

	    if (channelId == null || this.rulePort == null) {
	        throw new RuleDeployerException(
	                String.format("Rule channelId or port not found for %s:%s", serviceName, operationName));
	    }

	    try {
	        String boundChannelQuery = String.format(
	                "<Query><Atom><Rel>boundChannel</Rel><Ind>%s</Ind><Var>channel</Var></Atom></Query>", channelId);

	        oojdrew.issueRuleMLQuery(boundChannelQuery);

	        for (int i = 0; i < oojdrew.rowsReturned; i++) {
	            String key = String.valueOf(oojdrew.rowData[i][0]);
	            String value = String.valueOf(oojdrew.rowData[i][1]);

	            if ("?channel".equals(key)) {
	                this.resolvedChannelAddress = value;
	                
	                if (!value.startsWith("224.")) {
	                    // It's an IP address - DON'T normalize it
	                    this.useRemoteHost = true;
	                    logger.info("Detected remote host deployment: " + value);
	                    // Store the CHANNEL ID (ip1) not the IP address!
	                    this.ruleChannel = channelId;  // "ip1"
	                } else {
	                    // Only normalize multicast addresses
	                    this.ruleChannel = normalizeRuleChannel(value);
	                }
	                break;
	            }
	        }

	        if (this.ruleChannel == null) {
	            throw new RuleDeployerException(String.format(
	                    "Rule channel not found for channelId: %s (service %s:%s)", channelId, serviceName,
	                    operationName));
	        }

	    } catch (Exception e) {
	        throw new RuleDeployerException(String.format("Error querying boundChannel for channelId %s: %s",
	                channelId, e.getMessage()), e);
	    }

	    logger.info("Rule engine provided for " + serviceName + ":" + operationName + 
	               " - Channel: " + this.ruleChannel + ", Port: " + this.rulePort);
	}
	/**
	 * Normalize multicast channel addresses
	 */
	private String normalizeRuleChannel(String channel) {
	    String[] parts = channel.split("\\.");
	    if (parts.length >= 4) {
	        return String.format("224.1.%s.%s", parts[2], parts[3]);
	    } else {
	        logger.warn("Malformed rule channel from knowledge base: " + channel);
	        return channel;
	    }
	}

	/**
	 * Write NodeType atom to rule file
	 */
	public static boolean AddNodeType(PrintWriter wFile, String[] nodeTokens) {
		wFile.print("<Atom><Rel>NodeType</Rel><Ind>" + nodeTokens[0] + "</Ind></Atom>");
		logger.debug("Wrote NodeType: " + nodeTokens[0]);
		return true;
	}

	/**
	 * Write JoinInputCount atom to rule file (for PetriNet mode)
	 * This tells ServiceThread how many tokens to wait for before merging
	 */
	public static boolean AddJoinInputCount(PrintWriter wFile, int inputCount) {
		wFile.print("<Atom><Rel>JoinInputCount</Rel><Ind>" + inputCount + "</Ind></Atom>");
		logger.debug("Wrote JoinInputCount: " + inputCount);
		return true;
	}

	/**
	 * Write DecisionValue atom to rule file
	 */
	public static boolean AddDecisionValue(PrintWriter wFile, String[] dTokens) {
		String cleanDecisionValue = dTokens[1];
		if (cleanDecisionValue.startsWith("\"") && cleanDecisionValue.endsWith("\"")
				&& cleanDecisionValue.length() > 1) {
			cleanDecisionValue = cleanDecisionValue.substring(1, cleanDecisionValue.length() - 1);
		}

		wFile.print("<Atom><Rel>DecisionValue</Rel><Ind>" + cleanDecisionValue + "</Ind></Atom>");
		logger.debug("Wrote DecisionValue (cleaned): " + cleanDecisionValue);
		return true;
	}

	/**
	 * Write meetsCondition atom to rule file
	 * FIXED: Now includes 4th argument for decision value (needed for XOR fork routing)
	 */
	public static boolean AddmeetsCondition(PrintWriter wFile, String[] dsTokens, String[] dTokens, String decisionValue) {
		// Include decision value as 4th argument for proper XOR routing alignment
		String cleanDecisionValue = decisionValue != null ? decisionValue : "";
		if (cleanDecisionValue.startsWith("\"") && cleanDecisionValue.endsWith("\"") && cleanDecisionValue.length() > 1) {
			cleanDecisionValue = cleanDecisionValue.substring(1, cleanDecisionValue.length() - 1);
		}
		
		wFile.print(String.format("<Atom><Rel>meetsCondition</Rel><Ind>%s</Ind><Ind>%s</Ind><Ind>%s</Ind><Ind>%s</Ind></Atom>",
				dsTokens[0], dsTokens[1], dTokens[1], cleanDecisionValue));
		logger.debug("Wrote meetsCondition: " + dsTokens[0] + ":" + dsTokens[1] + ":" + dTokens[1] + ":" + cleanDecisionValue);
		return true;
	}
	

	/**
	 * Write terminationCondition atom to rule file
	 */
	public static boolean AddTerminationCondition(PrintWriter wFile, String[] terminationTokens) {
		wFile.print("<Atom><Rel>terminatesOn</Rel><Ind>" + terminationTokens[0] + "</Ind><Ind>" + terminationTokens[1]
				+ "</Ind></Atom>");
		logger.debug("Wrote terminationCondition: " + terminationTokens[0] + ":" + terminationTokens[1]);
		return true;
	}

	// ========================================================================
	// PETRINET CANONICAL BINDING GENERATION
	// ========================================================================
	
	/**
	 * Get operation arguments for a destination service from the workflow model.
	 * 
	 * @param destServiceName The destination service name (e.g., "P4_Place")
	 * @return List of argument names, or null if not found
	 */
	private List<String> getDestinationArguments(String destServiceName) {
		// Look up the service node in the workflow model
		Map<String, ServiceNode> services = workflowModel.getServiceNodes();
		
		// Try to find by service name directly or by nodeId
		for (ServiceNode node : services.values()) {
			if (node.service.equals(destServiceName) || node.nodeId.equals(destServiceName)) {
				String argsStr = node.attributes.get("operationArguments");
				if (argsStr != null && !argsStr.isEmpty()) {
					return Arrays.asList(argsStr.split(","));
				}
				break;
			}
		}
		return null;
	}


	/**
	 * Generate canonical binding files for PetriNet services.
	 * 
	 * For each service (Place), examines the INCOMING transition:
	 * - JoinNode with N inputs → generates N canonicalBinding entries (token_branch1, token_branch2, etc.)
	 * - Other node types → generates single canonicalBinding entry (token)
	 * 
	 * This enables the existing SOA join synchronization logic to work for PetriNet mode
	 * without special token ID encoding.
	 * 
	 * Files are written to: {COMMON_FOLDER}/ServiceAttributeBindings/{serviceName}/
	 * Existing files with the same name are OVERWRITTEN.
	 */
	private void generatePetriNetCanonicalBindings() throws RuleDeployerException {
		logger.info("=== GENERATING PETRINET CANONICAL BINDINGS ===");
		
		// FIRST: Build join slot assignments so we can determine return attributes
		buildPetriNetJoinSlotAssignments();
		
		// Determine base path for ServiceAttributeBindings
		String bindingsBasePath;
		try {
			File commonBase = new File("../");
			String commonPath = commonBase.getCanonicalPath();
			// Normalize path to OS-native separators
			File bindingsBase = new File(commonPath + "/" + Config.SERVICE_ATTRIBUTE_BINDINGS_FOLDER);
			bindingsBasePath = bindingsBase.getAbsolutePath();
		} catch (IOException e) {
			throw new RuleDeployerException("Failed to resolve ServiceAttributeBindings path", e);
		}
		
		logger.info("ServiceAttributeBindings base path: " + bindingsBasePath);
		
		Map<String, ServiceNode> services = workflowModel.getServiceNodes();
		int generatedCount = 0;
		
		// Track generated binding files for Service.ruleml append
		List<String> generatedBindingFiles = new ArrayList<>();
		
		for (ServiceNode serviceNode : services.values()) {
			try {
				// Get incoming transition to determine T_in behavior
				List<TransitionNode> incomingTransitions = workflowModel.findIncomingTransitions(serviceNode);
				
				int joinInputCount = 1;  // Default: single input (EdgeNode semantics)
				String incomingNodeType = "EdgeNode";
				
				if (!incomingTransitions.isEmpty()) {
					TransitionNode incomingTransition = incomingTransitions.get(0);
					incomingNodeType = incomingTransition.nodeType;
					
					// Check if incoming is a JoinNode with explicit input count
					if ("JoinNode".equals(incomingNodeType)) {
						String joinInputCountStr = incomingTransition.attributes.get("joinInputCount");
						if (joinInputCountStr != null) {
							joinInputCount = Integer.parseInt(joinInputCountStr);
						} else {
							// Count incoming edges to the transition
							joinInputCount = countIncomingEdgesToTransition(incomingTransition);
						}
					}
				}
				
				// Extract argument names from the service node (if available from topology)
				List<String> argumentNames = null;
				String operationArgsStr = serviceNode.attributes.get("operationArguments");
				if (operationArgsStr != null && !operationArgsStr.isEmpty()) {
					argumentNames = Arrays.asList(operationArgsStr.split(","));
					logger.info("Using topology arguments for " + serviceNode.service + ": " + argumentNames);
				}
				
				// CRITICAL: Determine return attribute based on downstream join topology
				String returnAttribute = determineReturnAttributeForService(serviceNode);
				
				// Generate the canonical binding file with argument names AND return attribute
				generateCanonicalBindingFile(bindingsBasePath, serviceNode.service, 
				                              serviceNode.operation, joinInputCount, argumentNames, returnAttribute);
				
				// Track the generated file path (normalized)
				String folderName = serviceNode.service.contains("_") 
				                    ? serviceNode.service.substring(0, serviceNode.service.indexOf("_"))
				                    : serviceNode.service;
				File bindingFile = new File(bindingsBasePath + "/" + folderName + "/" + 
				                         serviceNode.service + "-CanonicalBindings.ruleml.xml");
				generatedBindingFiles.add(bindingFile.getAbsolutePath());
				
				generatedCount++;
				
				logger.info("Generated canonical binding for " + serviceNode.service + 
				           " (incoming: " + incomingNodeType + ", inputs: " + joinInputCount + 
				           (argumentNames != null ? ", args: " + argumentNames : "") + ")");
				
			} catch (Exception e) {
				logger.error("Failed to generate canonical binding for " + serviceNode.service, e);
				throw new RuleDeployerException(
					"Failed to generate canonical binding for " + serviceNode.service, e);
			}
		}
		
		// Append all bindings to Service.ruleml
		try {
			appendBindingsToServiceRuleml(generatedBindingFiles);
		} catch (IOException e) {
			throw new RuleDeployerException("Failed to append bindings to Service.ruleml", e);
		}
		
		logger.info("=== GENERATED " + generatedCount + " CANONICAL BINDING FILES ===");
	}
	
	/**
	 * Append canonical bindings from generated files to Service.ruleml.
	 * Reads each binding file and inserts content before the closing </Rulebase> tag.
	 */
	private void appendBindingsToServiceRuleml(List<String> bindingFilePaths) throws IOException {
		String serviceRulemlPath = "../" + Config.COMMON_FOLDER + "/RuleFolder." + buildVersion + "/Service.ruleml";
		File serviceRulemlFile = new File(serviceRulemlPath);
		
		if (!serviceRulemlFile.exists()) {
			logger.warn("Service.ruleml not found at " + serviceRulemlPath + " - skipping binding append");
			return;
		}
		
		// Read existing content
		String content;
		try (BufferedReader reader = new BufferedReader(new FileReader(serviceRulemlFile))) {
			StringBuilder sb = new StringBuilder();
			String line;
			while ((line = reader.readLine()) != null) {
				sb.append(line).append("\n");
			}
			content = sb.toString();
		}
		
		// Check if bindings already exist (avoid duplicates on re-run)
		if (content.contains("PETRINET CANONICAL BINDINGS")) {
			logger.info("PetriNet canonical bindings already exist in Service.ruleml - skipping append");
			return;
		}
		
		// Build binding content from generated files
		StringBuilder bindingContent = new StringBuilder();
		bindingContent.append("\n<!-- ================================================================ -->\n");
		bindingContent.append("<!-- PETRINET CANONICAL BINDINGS (Auto-generated)                    -->\n");
		bindingContent.append("<!-- ================================================================ -->\n\n");
		
		for (String bindingFilePath : bindingFilePaths) {
			File bindingFile = new File(bindingFilePath);
			if (!bindingFile.exists()) {
				logger.warn("Binding file not found: " + bindingFilePath);
				continue;
			}
			
			try (BufferedReader reader = new BufferedReader(new FileReader(bindingFile))) {
				String line;
				while ((line = reader.readLine()) != null) {
					// Skip XML comments (they're documentation only)
					if (line.trim().startsWith("<!--")) {
						continue;
					}
					// Include Atom elements
					if (line.contains("<Atom>") || line.contains("</Atom>") || 
					    line.contains("<Rel>") || line.contains("<Ind>")) {
						bindingContent.append(line).append("\n");
					}
				}
			}
		}
		
		// Find insertion point (before </Rulebase>)
		int insertPoint = content.lastIndexOf("</Rulebase>");
		if (insertPoint < 0) {
			logger.error("Could not find </Rulebase> in Service.ruleml - cannot append bindings");
			return;
		}
		
		// Insert bindings
		String newContent = content.substring(0, insertPoint) + bindingContent.toString() + content.substring(insertPoint);
		
		// Write back
		try (PrintWriter writer = new PrintWriter(new FileWriter(serviceRulemlFile))) {
			writer.print(newContent);
		}
		
		logger.info("Appended " + bindingFilePaths.size() + " PetriNet canonical bindings to Service.ruleml");
	}
	
	/**
	 * Determine the return attribute for a service based on downstream join topology.
	 * 
	 * If this service feeds into a JoinNode, returns the slot name (e.g., "token_branch1").
	 * Otherwise returns "token" as the default.
	 * 
	 * Logic:
	 * 1. Find the outgoing transition from this service (T_out_X)
	 * 2. Check if T_out_X feeds into any JoinNode (directly or via decision edges)
	 * 3. If so, return the slot name from the join slot assignments
	 * 4. Otherwise return "token"
	 */
	private String determineReturnAttributeForService(ServiceNode serviceNode) {
		logger.info("RETURN-ATTR: Determining return attribute for service: " + serviceNode.service + 
		           " (nodeId=" + serviceNode.nodeId + ")");
		
		// Find outgoing transition from this service (service -> T_out)
		TransitionNode outgoingTransition = null;
		for (WorkflowEdge edge : workflowModel.getWorkflowEdges()) {
			if (edge.fromNode.equals(serviceNode.nodeId)) {
				String toNodeId = edge.toNode;
				TransitionNode transition = workflowModel.getTransitionNodes().get(toNodeId);
				if (transition != null) {
					outgoingTransition = transition;
					logger.debug("RETURN-ATTR: Found edge " + serviceNode.nodeId + " -> " + toNodeId);
					break;
				}
			}
		}
		
		if (outgoingTransition == null) {
			logger.info("RETURN-ATTR: No outgoing transition found for " + serviceNode.service + " - using 'token'");
			return "token";
		}
		
		String outgoingTransitionId = outgoingTransition.nodeId;
		logger.info("RETURN-ATTR: " + serviceNode.service + " has outgoing transition: " + outgoingTransitionId);
		
		// Debug: Show all join slot assignments
		logger.debug("RETURN-ATTR: Checking against " + petriNetJoinSlotAssignments.size() + " JoinNode slot maps");
		for (Map.Entry<String, Map<String, JoinSlotInfo>> entry : petriNetJoinSlotAssignments.entrySet()) {
			logger.debug("RETURN-ATTR:   JoinNode " + entry.getKey() + " has slots: " + entry.getValue().keySet());
		}
		
		// Check if this outgoing transition feeds into any JoinNode
		// The join slot assignments are keyed by T_out IDs
		for (Map.Entry<String, Map<String, JoinSlotInfo>> entry : petriNetJoinSlotAssignments.entrySet()) {
			String joinNodeId = entry.getKey();
			Map<String, JoinSlotInfo> slotMap = entry.getValue();
			
			// Check if our outgoing transition is in this JoinNode's slot map
			if (slotMap.containsKey(outgoingTransitionId)) {
				JoinSlotInfo slotInfo = slotMap.get(outgoingTransitionId);
				logger.info("RETURN-ATTR: MATCH! " + serviceNode.service + " (via " + outgoingTransitionId + 
				           ") feeds into " + joinNodeId + " at slot " + slotInfo.slotIndex + 
				           " -> returnAttribute='" + slotInfo.argName + "'");
				return slotInfo.argName;
			}
		}
		
		// No downstream JoinNode found - use default "token"
		logger.info("RETURN-ATTR: No JoinNode match for " + outgoingTransitionId + " - using 'token'");
		return "token";
	}

	/**
	 * Build slot assignments for PetriNet JoinNodes.
	 * 
	 * PRE-PASS: Analyzes all JoinNodes and assigns each incoming arc to a specific
	 * input slot (token_branch1, token_branch2, etc.) based on arrow order in JSON.
	 * 
	 * This must be called BEFORE rule generation so that canonical bindings
	 * are generated with the correct slot assignments.
	 */
	private void buildPetriNetJoinSlotAssignments() {
		logger.info("=== BUILDING PETRINET JOIN SLOT ASSIGNMENTS ===");
		
		petriNetJoinSlotAssignments.clear();
		
		// Iterate through all transitions to find JoinNodes
		for (TransitionNode transition : workflowModel.getTransitionNodes().values()) {
			if (!"JoinNode".equals(transition.nodeType)) {
				continue;
			}
			
			String joinNodeId = transition.nodeId;
			logger.info("Processing JoinNode: " + joinNodeId);
			
			// Find the service AFTER this JoinNode (to get its argument names)
			ServiceNode destService = findServiceAfterTransition(transition);
			if (destService == null) {
				logger.warn("JoinNode " + joinNodeId + " has no destination service - skipping");
				continue;
			}
			
			// Get the destination service's operation arguments
			List<String> argNames = getDestinationArguments(destService.service);
			if (argNames == null || argNames.isEmpty()) {
				// Fallback to default token_branch naming if not specified
				int inputCount = countIncomingEdgesToTransition(transition);
				argNames = new ArrayList<>();
				for (int i = 1; i <= inputCount; i++) {
					argNames.add("token_branch" + i);
				}
				logger.info("Using default arg names for " + joinNodeId + ": " + argNames);
			} else {
				logger.info("Using topology arg names for " + joinNodeId + ": " + argNames);
			}
			
			// Find all incoming arcs to this JoinNode (in JSON order)
			// CRITICAL: Exclude feedback loops - they are NOT join inputs
			List<WorkflowEdge> incomingArcs = new ArrayList<>();
			List<WorkflowEdge> feedbackArcs = new ArrayList<>();  // Track for logging
			
			for (WorkflowEdge edge : workflowModel.getWorkflowEdges()) {
				if (edge.toNode.equals(joinNodeId)) {
					// RULE 1: Exclude EventGenerator transitions - they're token sources, not join inputs
					// Check by transition type (more robust than string matching)
					TransitionNode fromTransition = workflowModel.getTransitionNodes().get(edge.fromNode);
					if (fromTransition != null && "EventGenerator".equals(fromTransition.nodeType)) {
						logger.info("JOIN-SLOT: Skipping EventGenerator edge " + edge.fromNode + " -> " + joinNodeId);
						continue;
					}
					
					// RULE 2: Exclude feedback loops (same-suffix edges)
					// Feedback loops are retry paths, not parallel branches to synchronize
					if (isFeedbackLoop(edge.fromNode, joinNodeId)) {
						logger.info("JOIN-SLOT: Excluding FEEDBACK LOOP from slot assignment: " + 
								   edge.fromNode + " -> " + joinNodeId);
						feedbackArcs.add(edge);
						continue;
					}
					
					// RULE 3: Valid join input - add to list
					incomingArcs.add(edge);
				}
			}
			
			logger.info("JoinNode " + joinNodeId + " has " + incomingArcs.size() + 
					   " valid incoming arcs, " + feedbackArcs.size() + " feedback loops excluded, " +
					   argNames.size() + " argument slots");
			
			// Assign each arc to a slot (by order in JSON arrows array)
			Map<String, JoinSlotInfo> slotMap = new HashMap<>();
			int slotIndex = 0;
			
			for (WorkflowEdge arc : incomingArcs) {
				if (slotIndex >= argNames.size()) {
					logger.error("JoinNode " + joinNodeId + ": More incoming arcs (" + 
					            incomingArcs.size() + ") than argument slots (" + 
					            argNames.size() + ") - excess arc from " + arc.fromNode);
					continue;
				}
				
				String argName = argNames.get(slotIndex);
				JoinSlotInfo slotInfo = new JoinSlotInfo(argName, slotIndex + 1);
				slotMap.put(arc.fromNode, slotInfo);
				
				logger.info("  Assigned: " + arc.fromNode + " -> " + joinNodeId + 
				           " = slot " + (slotIndex + 1) + " (" + argName + ")");
				slotIndex++;
			}
			
			if (slotIndex < argNames.size()) {
				logger.warn("JoinNode " + joinNodeId + ": Fewer incoming arcs (" + 
				           incomingArcs.size() + ") than argument slots (" + 
				           argNames.size() + ") - some slots unassigned");
			}
			
			petriNetJoinSlotAssignments.put(joinNodeId, slotMap);
		}
		
		logger.info("=== BUILT JOIN SLOT ASSIGNMENTS FOR " + 
		           petriNetJoinSlotAssignments.size() + " JOINNODES ===");
	}
	
	
	/**
	 * SOA Mode: Do NOT generate canonical binding files.
	 * 
	 * SOA workflows use hand-crafted canonical binding files that contain
	 * domain-specific knowledge about:
	 * - Return attributes for each service (e.g., radiologyResults, treatmentResults)
	 * - Input attributes needed from upstream services (e.g., triageResults, diagnosisResults)
	 * - Multiple operations with different binding requirements
	 * 
	 * This complexity cannot be auto-generated from topology alone.
	 * SOA binding files are maintained manually in ServiceAttributeBindings folders.
	 * 
	 * Future: Could add optional JSON-based binding generation if topology
	 * includes full returnAttribute/inputAttribute specifications.
	 */
	private void generateSOACanonicalBindingsIfSpecified() throws RuleDeployerException {
		logger.info("=== SOA MODE: Preserving existing canonical binding files ===");
		logger.info("SOA workflows use hand-crafted binding files - no auto-generation");
		
		// Log which services are in the workflow for reference
		Map<String, ServiceNode> services = workflowModel.getServiceNodes();
		for (ServiceNode serviceNode : services.values()) {
			logger.info("  SOA service: " + serviceNode.service + ":" + serviceNode.operation + 
			           " - using existing binding file");
		}
		
		logger.info("=== SOA CANONICAL BINDINGS: Preserved all " + services.size() + " existing files ===");
	}
	
	/**
	 * Count incoming edges to a transition node for join synchronization.
	 * 
	 * TOPOLOGICAL EDGE COUNTING RULES FOR PETRINET JOIN NODES:
	 * =========================================================
	 * 
	 * When determining how many inputs a JoinNode should wait for, we count
	 * only VALID TOPOLOGICAL edges - edges that represent distinct parallel
	 * branches that must synchronize.
	 * 
	 * RULE 1: Exclude EVENT_GENERATOR
	 *   - EVENT_GENERATOR is an external trigger, not part of the Petri Net topology
	 *   - It initiates the workflow but doesn't represent a branch to synchronize
	 * 
	 * RULE 2: Exclude same-suffix edges (retry/self-loops)
	 *   - If source T_out and target T_in share the same suffix, it's a retry loop
	 *   - Example: T_out_NS_Green -> T_in_NS_Green (suffix "NS_Green" matches)
	 *   - These represent the SAME token retrying, not a different branch
	 *   - Retry loops typically have decision_value="false" but we use suffix
	 *     matching as it's a cleaner structural rule
	 * 
	 * RULE 3: Count different-suffix edges
	 *   - Edges from T_out transitions with DIFFERENT suffixes are valid join inputs
	 *   - Example: T_out_EW_Yellow -> T_in_NS_Green (suffixes differ)
	 *   - These represent distinct parallel branches that must synchronize
	 * 
	 * EXAMPLE - T_in_NS_Green incoming edges:
	 *   - EVENT_GENERATOR -> T_in_NS_Green     : SKIP (Rule 1 - not topology)
	 *   - T_out_NS_Green -> T_in_NS_Green      : SKIP (Rule 2 - same suffix)
	 *   - T_out_EW_Yellow -> T_in_NS_Green     : COUNT (Rule 3 - different suffix)
	 *   - T_out_NS_Red_Hold -> T_in_NS_Green   : COUNT (Rule 3 - different suffix)
	 *   Result: 2 inputs for join synchronization
	 * 
	 * @param transition The T_in transition node to count inputs for
	 * @return Number of valid topological inputs (minimum 1)
	 */
	private int countIncomingEdgesToTransition(TransitionNode transition) {
		int count = 0;
		
		for (WorkflowEdge edge : workflowModel.getWorkflowEdges()) {
			if (edge.toNode.equals(transition.nodeId)) {
				// RULE 1: Skip EVENT_GENERATOR - not part of topology
				if ("EVENT_GENERATOR".equals(edge.fromNode)) {
					logger.debug("JOIN-COUNT: Skipping EVENT_GENERATOR edge to " + transition.nodeId);
					continue;
				}
				
				// RULE 2: Skip feedback loops (same-suffix edges / retry paths)
				if (isFeedbackLoop(edge.fromNode, transition.nodeId)) {
					logger.debug("JOIN-COUNT: Skipping FEEDBACK LOOP " + edge.fromNode + " -> " + transition.nodeId);
					continue;
				}
				
				// RULE 3: Count different-suffix edges (valid parallel branches)
				logger.debug("JOIN-COUNT: Counting edge " + edge.fromNode + " -> " + transition.nodeId);
				count++;
			}
		}
		return Math.max(count, 1);  // At least 1 input
	}
	
	/**
	 * Extract the suffix from a transition name.
	 * Used for same-suffix detection in join edge counting.
	 * 
	 * Examples:
	 *   "T_in_NS_Green"  -> "NS_Green"
	 *   "T_out_EW_Yellow" -> "EW_Yellow"
	 *   "T_in_EW_Red_Hold" -> "EW_Red_Hold"
	 * 
	 * @param transitionId The transition node ID
	 * @return The suffix portion after T_in_ or T_out_, or the original ID if no match
	 */
	private String extractTransitionSuffix(String transitionId) {
		if (transitionId == null) return null;
		
		// Handle T_in_XXX and T_out_XXX patterns
		if (transitionId.startsWith("T_in_")) {
			return transitionId.substring(5);  // Remove "T_in_"
		} else if (transitionId.startsWith("T_out_")) {
			return transitionId.substring(6);  // Remove "T_out_"
		}
		
		return transitionId;  // Return as-is if no pattern match
	}
	
	/**
	 * Detect if an edge is a feedback loop (self-loop back to the same place).
	 * 
	 * A feedback loop occurs when:
	 * - Source is T_out_X and target is T_in_X (same suffix X)
	 * - Example: T_out_P4 -> T_in_P4 (both reference place P4)
	 * 
	 * Feedback loops are typically conditional retry paths (decision_value="false")
	 * and should NOT be counted as join inputs. They bypass join synchronization
	 * and use simple "token" binding instead of join slot bindings.
	 * 
	 * @param sourceTransitionId The source transition (e.g., "T_out_P4")
	 * @param targetTransitionId The target transition (e.g., "T_in_P4")
	 * @return true if this is a feedback loop to the same place
	 */
	private boolean isFeedbackLoop(String sourceTransitionId, String targetTransitionId) {
		if (sourceTransitionId == null || targetTransitionId == null) {
			return false;
		}
		
		String sourceSuffix = extractTransitionSuffix(sourceTransitionId);
		String targetSuffix = extractTransitionSuffix(targetTransitionId);
		
		if (sourceSuffix != null && sourceSuffix.equals(targetSuffix)) {
			logger.info("FEEDBACK-LOOP DETECTED: " + sourceTransitionId + " -> " + targetTransitionId + 
					   " (both reference place '" + sourceSuffix + "')");
			return true;
		}
		
		return false;
	}
	
	/**
	 * Generate a canonical binding file for a service.
	 * 
	 * @param basePath Base path for ServiceAttributeBindings folder
	 * @param serviceName Name of the service (e.g., "P1_Place")
	 * @param operationName Name of the operation (e.g., "processToken")
	 * @param inputCount Number of inputs (1 for EdgeNode, N for JoinNode)
	 */
	private void generateCanonicalBindingFile(String basePath, String serviceName, 
	                                          String operationName, int inputCount) 
			throws IOException {
		// Delegate to the version with argument names, using null for backward compatibility
		generateCanonicalBindingFile(basePath, serviceName, operationName, inputCount, null, null);
	}
	
	/**
	 * Generate a canonical binding file for a service with explicit argument names.
	 * 
	 * @param basePath Base path for ServiceAttributeBindings folder
	 * @param serviceName Name of the service (e.g., "P1_Place")
	 * @param operationName Name of the operation (e.g., "processToken")
	 * @param inputCount Number of inputs (1 for EdgeNode, N for JoinNode)
	 * @param argumentNames List of argument names from topology (null to use defaults)
	 */
	private void generateCanonicalBindingFile(String basePath, String serviceName, 
	                                          String operationName, int inputCount,
	                                          List<String> argumentNames) 
			throws IOException {
		// Delegate to full version with null returnAttribute
		generateCanonicalBindingFile(basePath, serviceName, operationName, inputCount, argumentNames, null);
	}
	
	/**
	 * Generate a canonical binding file for a service with explicit argument names and return attribute.
	 * 
	 * @param basePath Base path for ServiceAttributeBindings folder
	 * @param serviceName Name of the service (e.g., "P1_Place")
	 * @param operationName Name of the operation (e.g., "processToken")
	 * @param inputCount Number of inputs (1 for EdgeNode, N for JoinNode)
	 * @param argumentNames List of argument names from topology (null to use defaults)
	 * @param returnAttribute The return attribute name (null to use default logic)
	 */
	private void generateCanonicalBindingFile(String basePath, String serviceName, 
	                                          String operationName, int inputCount,
	                                          List<String> argumentNames, String returnAttribute) 
			throws IOException {
		
		// Derive folder name from service name (P1_Place -> P1)
		String folderName = serviceName;
		if (serviceName.contains("_")) {
			folderName = serviceName.substring(0, serviceName.indexOf("_"));
		}
		
		// Create service directory if it doesn't exist (normalized path)
		File serviceDirFile = new File(basePath + "/" + folderName);
		String serviceDir = serviceDirFile.getAbsolutePath();
		if (!serviceDirFile.exists()) {
			if (!serviceDirFile.mkdirs()) {
				throw new IOException("Failed to create directory: " + serviceDir);
			}
			logger.info("Created directory: " + serviceDir);
		}
		
		// Build file path - will OVERWRITE existing file (normalized)
		String fileName = serviceName + "-CanonicalBindings.ruleml.xml";
		File fullPathFile = new File(serviceDirFile, fileName);
		String fullPath = fullPathFile.getAbsolutePath();
		
		// Determine if we have explicit argument names from topology
		boolean hasExplicitArgs = argumentNames != null && !argumentNames.isEmpty();
		
		logger.info("Writing canonical binding file: " + fullPath + 
		           " (inputs: " + inputCount + ", explicitArgs: " + hasExplicitArgs + 
		           (hasExplicitArgs ? " " + argumentNames : "") + ")");
		
		// Generate content
		try (PrintWriter pw = new PrintWriter(new FileWriter(fullPath))) {
			pw.println("<!-- Canonical Binding for " + serviceName + " -->");
			pw.println("<!-- Auto-generated by RuleDeployer - DO NOT EDIT -->");
			pw.println("<!-- Input count: " + inputCount + " -->");
			if (hasExplicitArgs) {
				pw.println("<!-- Arguments from topology: " + argumentNames + " -->");
			}
			if (returnAttribute != null) {
				pw.println("<!-- Return attribute from topology: " + returnAttribute + " -->");
			}
			pw.println();
			
			// localDefined fact
			pw.println("<Atom>");
			pw.println("\t<Rel>localDefined</Rel>");
			pw.println("\t<Ind>" + serviceName + "</Ind>");
			pw.println("</Atom>");
			
			if (hasExplicitArgs) {
				// Use explicit argument names from topology
				// Determine return attribute:
				// 1. Use provided returnAttribute if specified
				// 2. For single input, use the argument name as return
				// 3. For multiple inputs (join):
				//    - PetriNet: default to "token"
				//    - SOA: MUST have explicit returnAttribute
				String returnAttr;
				if (returnAttribute != null && !returnAttribute.isEmpty()) {
					returnAttr = returnAttribute;
				} else if (argumentNames.size() == 1) {
					returnAttr = argumentNames.get(0);
				} else if ("PetriNet".equalsIgnoreCase(this.processType)) {
					// PetriNet join - default return to "token"
					returnAttr = "token";
					logger.info("PetriNet mode: Using default returnAttribute 'token' for join " + serviceName);
				} else {
					// SOA mode: Multiple inputs without explicit returnAttribute - FAIL LOUDLY
					String errorMsg = "FATAL [SOA]: JoinNode " + serviceName + "." + operationName + 
					                  " has " + argumentNames.size() + " inputs but no returnAttribute specified. " +
					                  "Add 'returnAttribute' to the workflow JSON for this service.";
					logger.error(errorMsg);
					throw new IOException(errorMsg);
				}
				
				for (String argName : argumentNames) {
					pw.println("<Atom>");
					pw.println("\t<Rel>canonicalBinding</Rel>");
					pw.println("\t<Ind>" + operationName + "</Ind>");
					pw.println("\t<Ind>" + returnAttr + "</Ind>");  // Return attribute
					pw.println("\t<Ind>" + argName + "</Ind>");  // Input parameter from topology
					pw.println("</Atom>");
				}
			} else {
				// No explicit argument names provided
				// PetriNet: Use simple token defaults (simpler token flow semantics)
				// SOA: FAIL - must have explicit canonical bindings for semantic correctness
				if ("PetriNet".equalsIgnoreCase(this.processType)) {
					logger.info("PetriNet mode: Using default token bindings for " + serviceName + "." + operationName);
					
					// Generate default bindings based on input count
					String defaultReturn = "token";
					if (inputCount <= 1) {
						// Single input - simple token flow
						pw.println("<Atom>");
						pw.println("\t<Rel>canonicalBinding</Rel>");
						pw.println("\t<Ind>" + operationName + "</Ind>");
						pw.println("\t<Ind>" + defaultReturn + "</Ind>");
						pw.println("\t<Ind>token</Ind>");
						pw.println("</Atom>");
					} else {
						// Multiple inputs (join) - generate token_1, token_2, etc.
						for (int i = 1; i <= inputCount; i++) {
							pw.println("<Atom>");
							pw.println("\t<Rel>canonicalBinding</Rel>");
							pw.println("\t<Ind>" + operationName + "</Ind>");
							pw.println("\t<Ind>" + defaultReturn + "</Ind>");
							pw.println("\t<Ind>token_" + i + "</Ind>");
							pw.println("</Atom>");
						}
					}
				} else {
					// SOA mode - NO DEFAULTS, must have explicit argument names
					String errorMsg = "FATAL [SOA]: No argument names provided for " + serviceName + "." + operationName + 
					                  " (inputCount=" + inputCount + "). " +
					                  "SOA workflows MUST specify explicit input/output attribute names in canonical binding files.";
					logger.error(errorMsg);
					throw new IOException(errorMsg);
				}
			}
		}
		
		logger.debug("Successfully wrote canonical binding file: " + fullPath);
	}

	/**
	 * Track commitment arrivals for a specific version.
	 * Track commitment arrivals for deployment status.
	 */
	public static void ruleNumberofCommitments(String buildVersion, Integer commitmentCount, boolean count) {
		Logger logger = Logger.getLogger(RuleDeployer.class);
		
		// Get the TreeSet for this specific version
		java.util.TreeSet<Integer> ruleArrivalRateMap = ruleArrivalRateMapByVersion.get(buildVersion);
		if (ruleArrivalRateMap == null) {
			logger.warn("No TreeSet found for version: " + buildVersion + ", creating new one");
			ruleArrivalRateMap = new java.util.TreeSet<>();
			ruleArrivalRateMapByVersion.put(buildVersion, ruleArrivalRateMap);
		}
		
		if (count) {
			ruleArrivalRateMap.add(commitmentCount);
			logger.info("Recorded commitment: " + commitmentCount + " for version: " + buildVersion);
			logger.info("Version " + buildVersion + " now has " + ruleArrivalRateMap.size() + " commitments");
			return;
		}
		
		if (ruleArrivalRateMap.size() == commitmentCount) {
			deployed = true;
			deployedProcessesCount = ruleArrivalRateMap.size();
			logger.info("All commitments received for " + buildVersion + ": " + deployedProcessesCount);
		} else {
			deployed = false;
			deployedProcessesCount = ruleArrivalRateMap.size();
			logger.warn("Deployment incomplete for " + buildVersion + 
					   ", missing commitments. Received: " + ruleArrivalRateMap.size() + 
					   " Expected: " + commitmentCount);
		}
	}

	// Exception class
	public static class RuleDeployerException extends Exception {
		public RuleDeployerException(String message) {
			super(message);
		}

		public RuleDeployerException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}