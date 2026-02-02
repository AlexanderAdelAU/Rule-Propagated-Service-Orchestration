package org.btsn.petrinet.eventgenerators;

import java.io.File;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import org.btsn.constants.VersionConstants;
import org.btsn.rulecontroller.RuleDeployer;
import org.btsn.utils.OOjdrewAPI;
import org.btsn.utils.ParseCSV;
import org.json.simple.JSONObject;

/**
 * Generic Petri Net Token Generator
 * 
 * Generates JSON tokens for any Petri Net workflow and target place.
 * All configuration comes from command-line arguments - no hardcoded workflow names.
 * 
 * ================================================================================
 * TOKEN FORMAT (JSON inside XML payload)
 * ================================================================================
 * 
 * JSON Token Structure:
 * {
 *   "tokenId": "100000",              // Unique sequence ID
 *   "version": "v001",                // Rule base version
 *   "notAfter": 1730329315000,        // Expiry timestamp (epoch ms)
 *   "currentPlace": "P1_Place",       // Target place/service
 *   "workflow_start_time": 1730329195000,  // When token was created
 *   "data": {}                        // Custom data (optional)
 * }
 * 
 * XML Payload Wrapper (sent to ServiceThread):
 * {@code
 * <?xml version="1.0" encoding="UTF-8"?>
 * <payload>
 *   <header>
 *     <sequenceId>100000</sequenceId>
 *     <ruleBaseVersion>v001</ruleBaseVersion>
 *     <priortiseSID>true</priortiseSID>
 *     <monitorIncomingEvents>true</monitorIncomingEvents>
 *   </header>
 *   <service>
 *     <serviceName>P1_Place</serviceName>
 *     <operation>processToken</operation>
 *   </service>
 *   <joinAttribute>
 *     <attributeName>token</attributeName>
 *     <attributeValue>{...JSON token...}</attributeValue>
 *     <notAfter>1730329315000</notAfter>
 *     <status>active</status>
 *   </joinAttribute>
 *   <monitorData>
 *     <processStartTime>1730329195000</processStartTime>
 *     <processElapsedTime>0</processElapsedTime>
 *     <eventGeneratorTimestamp>1730329195000</eventGeneratorTimestamp>
 *     <sourceEventGenerator>P1_EVENTGENERATOR</sourceEventGenerator>
 *   </monitorData>
 * </payload>
 * }
 * 
 * ================================================================================
 * COMMAND LINE ARGUMENTS
 * ================================================================================
 * 
 * REQUIRED ARGUMENTS:
 *   -version <vXXX>      Rule base version (e.g., v001, v002, v003)
 *   -process <name>      Workflow/process name (e.g., P1_P2_Workflow)
 *   -place <name>        Target place name (e.g., P1_Place, P2_Place)
 * 
 * OPTIONAL ARGUMENTS:
 *   -operation <op>      Service operation (default: processToken)
 *   -tokens <n>          Number of tokens to generate (default: 10)
 *   -expire <ms>         Token expiry time in milliseconds (default: 120000)
 *   -data <json>         Custom token data
 *   -sequenceid <id>     Override starting sequence ID (normally auto-calculated)
 *   -skipdeploy          Skip rule deployment (use if rules already deployed)
 *   -noexit              Don't exit after completion (for embedded use)
 *   -generator <id>      Event generator identity for instrumentation tracking
 *                        (e.g., P1_EVENTGENERATOR) - included in token payload
 *                        so PetriNetInstrumentationHelper can track token source
 *                        (default: EVENT_GENERATOR)
 * 
 * FORK MODE ARGUMENTS (for injecting pre-forked child tokens):
 *   -forkmode            Enable fork injection mode
 *   -forkcount <n>       Number of fork branches (required if forkmode enabled)
 * 
 * JOIN MODE ARGUMENTS (for injecting specific token IDs):
 *   -joinargs <ids>      Comma-separated list of token IDs to inject
 *                        (e.g., "1000201,1000202")
 * 
 * ================================================================================
 * USAGE EXAMPLES
 * ================================================================================
 * 
 * Basic usage:
 *   java GenericPetriNetTokenGenerator -version v001 \
 *        -process P1_P2_Workflow \
 *        -place P1_Place
 * 
 * With explicit generator ID:
 *   java GenericPetriNetTokenGenerator -version v001 \
 *        -process P1_P2_Workflow \
 *        -place P1_Place \
 *        -generator P1_EVENTGENERATOR
 * 
 * Deploy rules only (no tokens):
 *   java GenericPetriNetTokenGenerator -version v001 \
 *        -process P1_P2_Workflow \
 *        -place P1_Place \
 *        -tokens 0
 * 
 * Fire tokens only (rules already deployed):
 *   java GenericPetriNetTokenGenerator -version v001 \
 *        -process P1_P2_Workflow \
 *        -place P1_Place \
 *        -tokens 10 \
 *        -skipdeploy
 * 
 * Fork mode - generate child tokens for a 2-way fork:
 *   java GenericPetriNetTokenGenerator -version v001 \
 *        -process TrafficLight_Workflow \
 *        -place P2_Place \
 *        -forkmode -forkcount 2 \
 *        -tokens 5
 * 
 * ================================================================================
 * FORK MODE BEHAVIOR
 * ================================================================================
 * 
 * When -forkmode is enabled:
 *   1. For each "token" requested, generates N child tokens (where N = forkcount)
 *   2. Child token IDs use the encoding: parentId + (joinCount * 100) + branchNumber
 *   3. Example: Base 1000000 with 2 forks -> children 1000201, 1000202
 *   4. Each child token is sent to the target place
 *   5. This simulates the output of a Fork/GatewayNode for workflows with JoinNode entry points
 * 
 * ================================================================================
 * ARCHITECTURE
 * ================================================================================
 * 
 * TokenGenerator -> UDP -> ServiceThread (buffer/orchestrator) -> Place -> Next Place
 * 
 * Service resolution:
 *   1. Query Service.ruleml for activeService(PlaceName, Operation, ChannelId, Port)
 *   2. Query for boundChannel(ChannelId, IPAddress)
 *   3. Send to calculated port: 10000 + (channelNumber * 1000) + basePort
 * 
 * @author ACameron
 */
public class GenericPetriNetTokenGenerator {

	// OOjDREW API for rule queries
	private static final OOjdrewAPI oojdrew = new OOjdrewAPI();

	// Service configuration - THESE MUST BE PROVIDED VIA COMMAND-LINE ARGS
	private static String targetPlaceName = null;  // Which place receives tokens (REQUIRED: -place)
	private static String serviceOperation = "processToken";  // Standard PN operation (can override with -operation)
	private static String attributeName = "token";  // Standard PN attribute (constant)
	
	// These will be populated by OOjDREW query
	private static String resolvedServiceChannel = null;
	private static String resolvedServicePort = null;
	private static String resolvedChannelId = null;  // "ip0", "ip1", etc.

	// Token generation settings - CONFIGURABLE VIA ARGS
	private static String ruleBaseVersion = null;  // REQUIRED: Must be provided via -version
	private static int sequenceID = -1;  // Will be set based on version (or override with -sequenceid)
	private static int numberOfTokens = 10;  // How many tokens to generate (default: 10, override with -tokens)
	private static long timeToExpire = 120000;  // Token validity window in ms (default: 120000, override with -expire)
	private static String tokenData = "";  // Optional custom data (use -data)
	private static String processName = null;  // REQUIRED: Workflow name (REQUIRED: -process)
	
	// Fork mode settings
	private static boolean forkMode = false;  // Enable fork injection mode
	private static int forkCount = 0;  // Number of fork branches (e.g., 2 for a 2-way fork)
	
	// Join args - explicit token IDs (e.g., -joinargs 1000201,1000202)
	private static List<Integer> joinArgs = new ArrayList<>();
	
	// Control settings
	private static boolean exitOnCompletion = true;
	private static boolean skipPriming = true;  // Skip priming by default
	private static boolean skipDeploy = false;  // Skip deployment if already done
	
	// Event Generator identity - REQUIRED for instrumentation tracking
	// Event Generator identity - for instrumentation tracking
	private static String eventGeneratorId = "EVENT_GENERATOR";  // Default fallback, override with -generator
	
	// OPTIMIZATION: Reuse UDP socket
	private static DatagramSocket udpSocket = null;

	public static void main(String[] args) throws Exception {
		// Debug: Show received arguments
		System.out.println("=== RECEIVED ARGUMENTS ===");
		for (int i = 0; i < args.length; i++) {
			System.out.println("  args[" + i + "] = " + args[i]);
		}
		System.out.println("==========================\n");
		
		// Parse command line arguments
		parseArguments(args);
		
		// Set sequenceID based on version
		setSequenceIDBasedOnVersion();
		
		long startTime = System.currentTimeMillis();

		System.out.println("=== PETRI NET TOKEN GENERATOR ===");
		System.out.println("Process: " + processName);
		System.out.println("Version: " + ruleBaseVersion);
		System.out.println("Event Generator ID: " + eventGeneratorId);
		System.out.println("Target Place: " + targetPlaceName);
		System.out.println("Service Operation: " + serviceOperation);
		
		if (forkMode) {
			System.out.println("MODE: FORK_NODE");
			System.out.println("Fork Count: " + forkCount);
			System.out.println("Base Tokens: " + numberOfTokens);
			System.out.println("Total Child Tokens: " + (numberOfTokens * forkCount));
		} else if (!joinArgs.isEmpty()) {
			System.out.println("MODE: JOIN_NODE");
			System.out.println("Token IDs: " + joinArgs);
		} else if (numberOfTokens > 0) {
			System.out.println("MODE: EDGE_NODE");
			System.out.println("Number of Tokens: " + numberOfTokens);
		} else {
			System.out.println("MODE: DEPLOY_ONLY");
		}
		
		System.out.println("Starting SequenceID: " + sequenceID);
		System.out.println("Token Expiry: " + timeToExpire + "ms");
		System.out.println("Token Data: " + (tokenData.isEmpty() ? "(none)" : tokenData));
		System.out.println("Skip Deploy: " + skipDeploy);
		System.out.println("=====================================\n");

		try {
			// STEP 1: Query rule base for service configuration
			System.out.println("=== Querying Rule Base for Service Configuration ===");
			System.out.println("Target Place: " + targetPlaceName);
			System.out.println("Service Operation: " + serviceOperation);
			System.out.println("Rule Base Version: " + ruleBaseVersion);
			
			if (!queryServiceConfiguration(targetPlaceName, serviceOperation, ruleBaseVersion)) {
				System.err.println("ERROR: Could not find service configuration in " + ruleBaseVersion);
				cleanup();
				if (exitOnCompletion) System.exit(1);
				return;
			}
			
			System.out.println("Resolved Channel: " + resolvedServiceChannel);
			System.out.println("Resolved Port: " + resolvedServicePort);
			System.out.println("=== Service Configuration Complete ===\n");

			// STEP 2: Deploy process rules (unless skipped)
			System.out.println("=== Deploying Process  Configuration Complete ===\n");
			if (!skipDeploy) {
				System.out.println("=== Deploying Process Rules ===");
				
				RuleDeployer ruleDeployer = null;
				try {
					ruleDeployer = new RuleDeployer(processName, ruleBaseVersion);
				} catch (Throwable t) {
					System.err.println("FATAL: RuleDeployer constructor threw exception!");
					System.err.println("Exception: " + t.getClass().getName() + ": " + t.getMessage());
					t.printStackTrace(System.err);
					throw t;
				}
				
				try {
					ruleDeployer.deploy();
				} catch (Throwable t) {
					System.err.println("FATAL: Deploy() threw exception!");
					System.err.println("Exception: " + t.getClass().getName() + ": " + t.getMessage());
					t.printStackTrace(System.err);
					throw t;
				}
				
				if (!RuleDeployer.deployed) {
					System.err.println("WARNING: Could not deploy process: " + processName);
					System.err.println("WARNING: Continuing anyway...");
				} else {
					Thread.sleep(2000); // Allow services to initialize
					System.out.println("Successfully deployed " + ruleBaseVersion + " rules");
				}
			} else {
				System.out.println("=== Skipping Deployment (already deployed) ===");
			}

			Long timeToCommit = System.currentTimeMillis() - startTime;

			// STEP 3: Generate and send tokens
			System.out.println("=== Generating Tokens ===");
			if (forkMode) {
				generateForkTokens();
			} else {
				generateTokens();
			}

			// STEP 4: Report results
			long eventTime = System.currentTimeMillis() - startTime - timeToCommit;
			System.out.println("\n=== Token Generation Complete ===");
			System.out.println("Rule Commitment: " + timeToCommit + "ms");
			System.out.println("Token Generation: " + eventTime + "ms");
			System.out.println("Target: " + targetPlaceName + " @ " + resolvedServiceChannel + ":" + resolvedServicePort);
			
			if (forkMode) {
				System.out.println("Base Tokens: " + numberOfTokens);
				System.out.println("Child Tokens Sent: " + (numberOfTokens * forkCount));
			} else {
				System.out.println("Tokens Sent: " + numberOfTokens);
			}
			
		} catch (Exception e) {
			System.err.println("ERROR: Token generator failed!");
			System.err.println("Exception: " + e.getClass().getName() + ": " + e.getMessage());
			e.printStackTrace(System.err);
			
			cleanup();
			if (exitOnCompletion) System.exit(1);
		} finally {
			cleanup();
		}
		
		if (exitOnCompletion) {
			System.out.println("\nGenerator complete. Exiting.");
			System.exit(0);
		}
	}

	/**
	 * Parse command line arguments
	 */
	private static void parseArguments(String[] args) {
		for (int i = 0; i < args.length; i++) {
			String arg = args[i].toLowerCase();
			
			switch (arg) {
				case "-version":
				case "--version":
					if (i + 1 < args.length) {
						ruleBaseVersion = args[++i];
						System.out.println("  Parsed -version: " + ruleBaseVersion);
					}
					break;
					
				case "-process":
				case "--process":
					if (i + 1 < args.length) {
						processName = args[++i];
						System.out.println("  Parsed -process: " + processName);
					}
					break;
					
				case "-service":
				case "--service":
				case "-place":
				case "--place":
				case "-targetplace":
				case "--targetplace":
					if (i + 1 < args.length) {
						targetPlaceName = args[++i];
						System.out.println("  Parsed -place: " + targetPlaceName);
					}
					break;

				case "-operation":
				case "--operation":
					if (i + 1 < args.length) {
						serviceOperation = args[++i];
						System.out.println("  Parsed -operation: " + serviceOperation);
					}
					break;
					
				case "-sequenceid":
				case "--sequenceid":
					if (i + 1 < args.length) {
						sequenceID = Integer.parseInt(args[++i]);
						System.out.println("  Parsed -sequenceid: " + sequenceID);
					}
					break;
					
				case "-tokens":
				case "--tokens":
					if (i + 1 < args.length) {
						numberOfTokens = Integer.parseInt(args[++i]);
						System.out.println("  Parsed -tokens: " + numberOfTokens);
					}
					break;
					
				case "-expire":
				case "--expire":
					if (i + 1 < args.length) {
						timeToExpire = Long.parseLong(args[++i]);
						System.out.println("  Parsed -expire: " + timeToExpire);
					}
					break;
					
				case "-data":
				case "--data":
					if (i + 1 < args.length) {
						tokenData = args[++i];
						System.out.println("  Parsed -data: " + tokenData);
					}
					break;
					
				case "-noexit":
				case "--noexit":
					exitOnCompletion = false;
					System.out.println("  Parsed -noexit");
					break;
					
				case "-skipdeploy":
				case "--skipdeploy":
					skipDeploy = true;
					System.out.println("  Parsed -skipdeploy");
					break;
					
				// Fork mode arguments
				case "-forkmode":
				case "--forkmode":
					forkMode = true;
					System.out.println("  Parsed -forkmode");
					break;
					
				case "-forkcount":
				case "--forkcount":
					if (i + 1 < args.length) {
						forkCount = Integer.parseInt(args[++i]);
						forkMode = true;  // Implicitly enable fork mode
						System.out.println("  Parsed -forkcount: " + forkCount);
					}
					break;
					
				case "-joinargs":
				case "--joinargs":
					if (i + 1 < args.length) {
						String[] ids = args[++i].split(",");
						for (String id : ids) {
							joinArgs.add(Integer.parseInt(id.trim()));
						}
						numberOfTokens = joinArgs.size();
						System.out.println("  Parsed -joinargs: " + joinArgs);
					}
					break;
				
				case "-generator":
				case "--generator":
				case "-eventgenerator":
				case "--eventgenerator":
					if (i + 1 < args.length) {
						eventGeneratorId = args[++i];
						System.out.println("  Parsed -generator: " + eventGeneratorId);
					}
					break;
					
				default:
					// Unknown argument - could be a value, skip
					break;
			}
		}
		
		System.out.println("");
		
		// Validate required parameters
		if (ruleBaseVersion == null || ruleBaseVersion.isEmpty()) {
			System.err.println("ERROR: Version is required!");
			System.err.println("Usage: java GenericPetriNetTokenGenerator -version <vXXX> -process <workflow> -place <place>");
			System.err.println("Example: -version v001 -process P1_P2_Workflow -place P1_Place");
			System.err.println("Fork mode: -version <vXXX> -process <workflow> -place <place> -forkcount <n>");
			System.exit(1);
		}
		
		if (processName == null || processName.isEmpty()) {
			System.err.println("ERROR: Process name is required!");
			System.err.println("Usage: java GenericPetriNetTokenGenerator -version <vXXX> -process <workflow> -place <place>");
			System.err.println("Example: -version v001 -process P1_P2_Workflow -place P1_Place");
			System.exit(1);
		}
		
		if (targetPlaceName == null || targetPlaceName.isEmpty()) {
			System.err.println("ERROR: Target place is required!");
			System.err.println("Usage: java GenericPetriNetTokenGenerator -version <vXXX> -process <workflow> -place <place>");
			System.err.println("Example: -version v001 -process P1_P2_Workflow -place P1_Place");
			System.exit(1);
		}
		
		// Validate fork mode
		if (forkMode && forkCount < 2) {
			System.err.println("ERROR: Fork mode requires -forkcount >= 2");
			System.err.println("Example: -forkcount 2 for a 2-way fork");
			System.exit(1);
		}
		
		// Validate version format
		if (!ruleBaseVersion.matches("v\\d{3}")) {
			System.err.println("ERROR: Invalid version format: " + ruleBaseVersion);
			System.err.println("Valid format: vXXX (e.g., v001, v002, v003)");
			System.exit(1);
		}
	}

	/**
	 * Set sequenceID based on version
	 */
	private static void setSequenceIDBasedOnVersion() {
		// Use VersionConstants.getWorkflowBase() to get the base sequenceID
		// Only calculate if not explicitly set via -sequenceId argument
		if (sequenceID == -1) {
			sequenceID = VersionConstants.getWorkflowBase(ruleBaseVersion);
		System.out.println("Calculated sequenceID from version: " + sequenceID);
		} else {
			System.out.println("Using provided sequenceID: " + sequenceID);
		}
	}

	/**
	 * Query service configuration from rule base
	 */
	private static boolean queryServiceConfiguration(String serviceName, String operation, String version) {
		try {
			// Get base path (current directory is the generator project)
			File currentDir = new File("").getAbsoluteFile();
			// Go up one level to get to BTSN root, then down to common
			File commonDir = new File(currentDir.getParent(), "btsn.common");
			File ruleBaseFile = new File(commonDir, "RuleFolder." + version + "/Service.ruleml");
			String ruleFolder = ruleBaseFile.getAbsolutePath();
			
			System.out.println("DEBUG: Loading rule base from: " + ruleFolder);
			oojdrew.parseKnowledgeBase(ruleFolder, true);
			
			// Try activeService first
			String query = String.format(
				"<Query><Atom><Rel>activeService</Rel><Ind>%s</Ind><Ind>%s</Ind><Var>channelId</Var><Var>port</Var></Atom></Query>",
				serviceName, operation);
			
			oojdrew.issueRuleMLQuery(query);
			
			// If not found, try hasOperation
			if (oojdrew.rowsReturned == 0) {
				query = String.format(
					"<Query><Atom><Rel>hasOperation</Rel><Ind>%s</Ind><Ind>%s</Ind><Var>channelId</Var><Var>port</Var></Atom></Query>",
					serviceName, operation);
				oojdrew.issueRuleMLQuery(query);
			}
			
			if (oojdrew.rowsReturned == 0) {
				System.err.println("Service not found in knowledge base: " + serviceName + ":" + operation);
				return false;
			}
			
			// Parse results
			String channelId = null;
			String port = null;
			
			for (int i = 0; i < oojdrew.rowsReturned; i++) {
				String key = String.valueOf(oojdrew.rowData[i][0]);
				String value = String.valueOf(oojdrew.rowData[i][1]);
				
				if ("?channelId".equals(key)) {
					channelId = value;
					resolvedChannelId = value;
				} else if ("?port".equals(key)) {
					port = value;
					resolvedServicePort = value;
				}
			}
			
			if (channelId == null || port == null) {
				System.err.println("Incomplete service configuration");
				return false;
			}
			
			// Resolve channel address
			query = String.format(
				"<Query><Atom><Rel>boundChannel</Rel><Ind>%s</Ind><Var>channel</Var></Atom></Query>",
				channelId);
			
			oojdrew.issueRuleMLQuery(query);
			
			for (int i = 0; i < oojdrew.rowsReturned; i++) {
				String key = String.valueOf(oojdrew.rowData[i][0]);
				String value = String.valueOf(oojdrew.rowData[i][1]);
				
				if ("?channel".equals(key)) {
					resolvedServiceChannel = value;
					break;
				}
			}
			
			return resolvedServiceChannel != null;
			
		} catch (Exception e) {
			System.err.println("Error querying service configuration: " + e.getMessage());
			e.printStackTrace();
			return false;
		}
	}

	/**
	 * Generate and send tokens in FORK MODE
	 * 
	 * For each base token, generates N child tokens and sends ALL to the same target place.
	 * The JoinNode at that place will synchronize the children.
	 * 
	 * Token ID encoding: parentId + (forkCount * 100) + branchNumber
	 * 
	 * Example with base 1000000 and forkCount=2:
	 *   - Child 1000201 -> P1_Place (branch 1)
	 *   - Child 1000202 -> P1_Place (branch 2)
	 *   - JoinNode at P1 receives both, synchronizes, workflow proceeds
	 */
	private static void generateForkTokens() throws Exception {
		System.out.println("\n=== FORK MODE: Generating Child Tokens ===");
		System.out.println("Target place: " + targetPlaceName);
		System.out.println("Base tokens: " + numberOfTokens);
		System.out.println("Fork count: " + forkCount);
		System.out.println("Total child tokens: " + (numberOfTokens * forkCount));
		
		int baseSequenceID = sequenceID;
		int sidInc = VersionConstants.TOKEN_INCREMENT;
		int totalChildTokensSent = 0;
		
		for (int tokenNum = 0; tokenNum < numberOfTokens; tokenNum++) {
			int parentTokenId = baseSequenceID + (tokenNum * sidInc);
			
			System.out.printf("\nBase token %d (parent ID: %d):\n", tokenNum + 1, parentTokenId);
			
			// Generate all child tokens and send to the SAME place
			for (int branch = 1; branch <= forkCount; branch++) {
				// Child token ID encoding: parentId + (forkCount * 100) + branchNumber
				int childTokenId = parentTokenId + (forkCount * 100) + branch;
				
				System.out.printf("  -> Child %d (ID: %d) to %s\n", branch, childTokenId, targetPlaceName);
				
				// Create and send the child token
				String jsonToken = createForkChildToken(childTokenId, parentTokenId, branch);
				sendToken(jsonToken, childTokenId);
				
				totalChildTokensSent++;
			}
		}
		
		System.out.printf("\n=== FORK MODE COMPLETE ===\n");
		System.out.printf("Base tokens processed: %d\n", numberOfTokens);
		System.out.printf("Child tokens sent: %d\n", totalChildTokensSent);
	}

	/**
	 * Create a fork child token with proper encoding
	 */
	@SuppressWarnings("unchecked")
	private static String createForkChildToken(int childTokenId, int parentTokenId, int branchNumber) {
		JSONObject token = new JSONObject();
		
		long currentTime = System.currentTimeMillis();
		
		token.put("tokenId", String.valueOf(childTokenId));
		token.put("parentTokenId", String.valueOf(parentTokenId));
		token.put("branchNumber", branchNumber);
		token.put("forkCount", forkCount);
		token.put("version", ruleBaseVersion);
		token.put("notAfter", currentTime + timeToExpire);
		token.put("currentPlace", targetPlaceName);
		token.put("workflow_start_time", currentTime);
		
		// Add custom data if provided
		JSONObject data = new JSONObject();
		if (tokenData != null && !tokenData.isEmpty()) {
			String[] pairs = tokenData.split(",");
			for (String pair : pairs) {
				String[] kv = pair.split("=");
				if (kv.length == 2) {
					data.put(kv[0].trim(), kv[1].trim());
				}
			}
		}
		token.put("data", data);
		
		return token.toJSONString();
	}

	/**
	 * Generate and send tokens - uses ParseCSV utility to read timing from CSV file
	 */
	private static void generateTokens() throws Exception {
		String triggeringPath = new File("").getAbsolutePath() + "/EventTriggeringFile";
		ParseCSV pcsv = new ParseCSV();
		// Build CSV filename using the version parameter (e.g., v001 -> V001_EventTriggeringFile.csv)
		String csvFilename = triggeringPath + "/" + ruleBaseVersion.toUpperCase() + "_EventTriggeringFile.csv";
		System.out.println("Loading trigger file: " + csvFilename);
		
		TreeMap<Integer, Integer> tokenSequence = pcsv.parseCSV(csvFilename);
		
		if (tokenSequence.isEmpty()) {
			System.out.println("No token sequence found in EventTriggeringFile.csv");
			System.out.println("Firing single test token...");
			
			// Create single test token
			String jsonToken = createPetriNetToken(sequenceID);
			sendToken(jsonToken, sequenceID);
			
		} else {
			System.out.println("Processing token sequence from EventTriggeringFile.csv...");
			System.out.println("Will fire " + numberOfTokens + " token(s) from the sequence");
			
			int numTokens = 0;
			int sidInc = VersionConstants.TOKEN_INCREMENT;
			int totalTokensProcessed = 0;
			
			// MAIN TOKEN SEQUENCE - REAL-TIME FIRING
			System.out.println("\n=== STARTING REAL-TIME TOKEN SEQUENCE ===");
			long sequenceStartTime = System.currentTimeMillis();
			
			for (int timeKey : tokenSequence.keySet()) {
				// Check if we've fired enough tokens
				if (totalTokensProcessed >= numberOfTokens) {
					System.out.printf("\nReached token limit (%d). Stopping sequence.\n", numberOfTokens);
					break;
				}
				
				// Calculate exact time this batch should fire
				long targetTime = sequenceStartTime + timeKey;
				long currentTime = System.currentTimeMillis();
				long sleepTime = targetTime - currentTime;
				
				// Wait until the exact moment to fire
				if (sleepTime > 0) {
					Thread.sleep(sleepTime);
				} else if (sleepTime < -50) {  // More than 50ms behind schedule
					System.out.printf("WARNING: Running %dms behind schedule at time %dms\n", 
						Math.abs(sleepTime), timeKey);
				}
				
				// Get number of ARRIVING tokens at this time point
				numTokens = tokenSequence.get(timeKey);
				
				if (numTokens > 0) {
					// Limit tokens to not exceed numberOfTokens
					int tokensToFire = Math.min(numTokens, numberOfTokens - totalTokensProcessed);
					
					long batchStartTime = System.currentTimeMillis();
					System.out.printf("Time %dms: Firing %d tokens NOW (all at once)\n", timeKey, tokensToFire);
					
					// FIRE ALL TOKENS IMMEDIATELY - NO DELAYS!
					for (int j = 0; j < tokensToFire; j++) {
						int tokenIdToUse;
						String tokenAttrName = attributeName;  // Default: "token"
						int tokenJoinID = -1;  // Default: no join
						
						if (!joinArgs.isEmpty() && totalTokensProcessed < joinArgs.size()) {
							// JOIN_NODE mode: Use explicit token IDs and branch attribute names
							tokenIdToUse = joinArgs.get(totalTokensProcessed);
							// Attribute name is token_branch1, token_branch2, etc. (1-indexed)
							tokenAttrName = "token_branch" + (totalTokensProcessed + 1);
							// JoinID is the parent token ID (sequenceID configured for workflow)
							tokenJoinID = sequenceID;
							System.out.printf("  JOIN_NODE: Token %d with attributeName=%s, joinID=%d\n", 
							                  tokenIdToUse, tokenAttrName, tokenJoinID);
						} else {
							// EDGE_NODE mode: Use sequential token IDs
							tokenIdToUse = sequenceID;
							sequenceID += sidInc;
						}
						
						String jsonToken = createPetriNetToken(tokenIdToUse);
						
						// FIRE IMMEDIATELY - as fast as possible
						sendToken(jsonToken, tokenIdToUse, tokenAttrName, tokenJoinID);
						
						totalTokensProcessed++;
					}
					
					long batchEndTime = System.currentTimeMillis();
					long batchDuration = batchEndTime - batchStartTime;
					
					System.out.printf("  Batch of %d tokens fired in %dms (%.1f tokens/ms)\n", 
						tokensToFire, batchDuration, 
						batchDuration > 0 ? (double)tokensToFire/batchDuration : tokensToFire);
				}
			}
			
			// Calculate timing accuracy
			long actualDuration = System.currentTimeMillis() - sequenceStartTime;
			int expectedDuration = tokenSequence.lastKey();
			
			// Print summary
			System.out.printf("\n=== TOKEN SEQUENCE SUMMARY ===\n");
			System.out.printf("Total Tokens Processed: %d\n", totalTokensProcessed);
			System.out.printf("Sequence Duration: Expected %dms, Actual %dms (drift: %+dms)\n", 
				expectedDuration, actualDuration, (actualDuration - expectedDuration));
			System.out.printf("=== Tokens fired according to EventTriggeringFile.csv timing ===\n");
		}
	}

	/**
	 * Create Petri Net JSON token
	 */
	@SuppressWarnings("unchecked")
	private static String createPetriNetToken(int seqID) {
		JSONObject token = new JSONObject();
		
		long currentTime = System.currentTimeMillis();
		
		token.put("tokenId", String.valueOf(seqID));
		token.put("version", ruleBaseVersion);
		token.put("notAfter", currentTime + timeToExpire);
		token.put("currentPlace", targetPlaceName);
		token.put("workflow_start_time", currentTime);
		
		// Add custom data if provided
		JSONObject data = new JSONObject();
		if (tokenData != null && !tokenData.isEmpty()) {
			// Parse key=value pairs
			String[] pairs = tokenData.split(",");
			for (String pair : pairs) {
				String[] kv = pair.split("=");
				if (kv.length == 2) {
					data.put(kv[0].trim(), kv[1].trim());
				}
			}
		}
		token.put("data", data);
		
		return token.toJSONString();
	}

	/**
	 * Send token to target service
	 */
	private static void sendToken(String jsonToken, int seqID) throws IOException {
		sendToken(jsonToken, seqID, attributeName, -1);
	}
	
	/**
	 * Send token to target service with specific attribute name.
	 * Used for join tokens where attributeName must be token_branch1, token_branch2, etc.
	 */
	private static void sendToken(String jsonToken, int seqID, String attrName) throws IOException {
		sendToken(jsonToken, seqID, attrName, -1);
	}
	
	/**
	 * Send token to target service with specific attribute name and joinID.
	 * Used for join tokens where:
	 * - attributeName must be token_branch1, token_branch2, etc.
	 * - joinID is the parent token ID that all children share
	 */
	private static void sendToken(String jsonToken, int seqID, String attrName, int joinID) throws IOException {
		// Build XML payload wrapper (ServiceThread expects XML wrapper around token)
		String xmlPayload = buildXMLPayload(jsonToken, seqID, attrName, joinID);
		
		// Send via UDP
		sendUDPEvent(xmlPayload, resolvedServiceChannel, resolvedServicePort);
	}

	/**
	 * Build XML payload wrapper for ServiceThread
	 */
	private static String buildXMLPayload(String tokenValue, int seqID) {
		return buildXMLPayload(tokenValue, seqID, attributeName, -1);
	}
	
	/**
	 * Build XML payload wrapper for ServiceThread with specific attribute name.
	 * 
	 * For EdgeNode tokens: attributeName = "token"
	 * For JoinNode tokens: attributeName = "token_branch1", "token_branch2", etc.
	 */
	private static String buildXMLPayload(String tokenValue, int seqID, String attrName) {
		return buildXMLPayload(tokenValue, seqID, attrName, -1);
	}
	
	/**
	 * Build XML payload wrapper for ServiceThread with specific attribute name and joinID.
	 * 
	 * For EdgeNode tokens: attributeName = "token", joinID = -1 (not used)
	 * For JoinNode tokens: attributeName = "token_branch1", "token_branch2", etc., joinID = parent token ID
	 */
	private static String buildXMLPayload(String tokenValue, int seqID, String attrName, int joinID) {
		long currentTime = System.currentTimeMillis();
		String notAfter = Long.toString(currentTime + timeToExpire);
		
		System.out.println("DEBUG: Building XML payload with sequenceID=" + seqID + 
		                   ", attributeName=" + attrName + 
		                   (joinID > 0 ? ", joinID=" + joinID : ""));
		
		StringBuilder xml = new StringBuilder();
		xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
		xml.append("<payload>\n");
		xml.append("  <header>\n");
		xml.append("    <sequenceId>").append(seqID).append("</sequenceId>\n");
		xml.append("    <ruleBaseVersion>").append(ruleBaseVersion).append("</ruleBaseVersion>\n");
		xml.append("    <priortiseSID>true</priortiseSID>\n");
		xml.append("    <monitorIncomingEvents>true</monitorIncomingEvents>\n");
		// Add joinID for join tokens
		if (joinID > 0) {
			xml.append("    <joinID>").append(joinID).append("</joinID>\n");
		}
		xml.append("  </header>\n");
		xml.append("  <service>\n");
		xml.append("    <serviceName>").append(targetPlaceName).append("</serviceName>\n");
		xml.append("    <operation>").append(serviceOperation).append("</operation>\n");
		xml.append("  </service>\n");
		xml.append("  <joinAttribute>\n");
		xml.append("    <attributeName>").append(attrName).append("</attributeName>\n");
		xml.append("    <attributeValue>").append(escapeXml(tokenValue)).append("</attributeValue>\n");
		xml.append("    <notAfter>").append(notAfter).append("</notAfter>\n");
		xml.append("    <status>active</status>\n");
		xml.append("  </joinAttribute>\n");
		xml.append("  <monitorData>\n");
		xml.append("    <processStartTime>").append(currentTime).append("</processStartTime>\n");
		xml.append("    <processElapsedTime>0</processElapsedTime>\n");
		xml.append("    <eventGeneratorTimestamp>").append(currentTime).append("</eventGeneratorTimestamp>\n");
		xml.append("    <sourceEventGenerator>").append(eventGeneratorId).append("</sourceEventGenerator>\n");
		xml.append("  </monitorData>\n");
		xml.append("</payload>");
		
		System.out.println("DEBUG: Added eventGeneratorTimestamp=" + currentTime);
		System.out.println("DEBUG: Added sourceEventGenerator=" + eventGeneratorId);
		
		return xml.toString();
	}

	/**
	 * Escape XML special characters
	 */
	private static String escapeXml(String text) {
		if (text == null) return "";
		return text.replace("&", "&amp;")
				   .replace("<", "&lt;")
				   .replace(">", "&gt;")
				   .replace("\"", "&quot;")
				   .replace("'", "&apos;");
	}

	/**
	 * Send UDP event - handles both remote and local services
	 */
	private static void sendUDPEvent(String payload, String channel, String port) throws IOException {
		try {
			if (udpSocket == null || udpSocket.isClosed()) {
				// Create unbound socket, set reuse address, then bind to ephemeral port
				udpSocket = new DatagramSocket(null);
				udpSocket.setReuseAddress(true);
				udpSocket.bind(null);  // Bind to any available port
			}
			
			InetAddress targetAddress;
			int targetPort;

			// Check if remote (IP) or local (multicast)
			if (isIPAddress(channel)) {
				// REMOTE SERVICE
				targetAddress = InetAddress.getByName(channel);
				
				// Extract channel number from channelId (e.g., "ip1" -> 1)
				int channelNumber = 0;
				if (resolvedChannelId != null && resolvedChannelId.startsWith("ip")) {
					try {
						channelNumber = Integer.parseInt(resolvedChannelId.substring(2));
					} catch (NumberFormatException e) {
						// Use default
					}
				}
				
				int basePort = Integer.parseInt(port);
				targetPort = 10000 + (channelNumber * 1000) + basePort;
				
				System.out.println("  -> Sending to REMOTE: " + targetAddress.getHostAddress() + ":" + targetPort);
			} else {
				// LOCAL SERVICE
				targetAddress = InetAddress.getLoopbackAddress();
				
				// Extract channel number from multicast address
				int channelNumber = 1;
				try {
					String[] parts = channel.split("\\.");
					if (parts.length >= 4) {
						channelNumber = Integer.parseInt(parts[3]);
					}
				} catch (Exception e) {
					// Use default
				}
				
				int basePort = Integer.parseInt(port);
				targetPort = 10000 + (channelNumber * 1000) + basePort;
				
				System.out.println("  -> Sending to LOCAL: localhost:" + targetPort);
			}

			byte[] data = payload.getBytes();
			DatagramPacket packet = new DatagramPacket(data, data.length, targetAddress, targetPort);
			udpSocket.send(packet);
			
		} catch (Exception e) {
			if (udpSocket != null) {
				udpSocket.close();
				udpSocket = null;
			}
			throw new IOException("UDP send failed: " + e.getMessage(), e);
		}
	}

	/**
	 * Check if string is an IP address
	 */
	private static boolean isIPAddress(String value) {
		if (value == null) return false;
		
		String[] parts = value.split("\\.");
		if (parts.length != 4) return false;
		
		try {
			for (String part : parts) {
				int num = Integer.parseInt(part);
				if (num < 0 || num > 255) return false;
			}
			int firstOctet = Integer.parseInt(parts[0]);
			// Not a multicast address
			return firstOctet < 224 || firstOctet > 239;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	/**
	 * Cleanup resources
	 */
	private static void cleanup() {
		if (udpSocket != null && !udpSocket.isClosed()) {
			udpSocket.close();
			udpSocket = null;
			System.out.println("UDP socket closed");
		}
	}
}