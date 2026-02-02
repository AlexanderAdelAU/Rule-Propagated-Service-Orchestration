package org.btsn.healthcare.eventgenerators;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.TreeMap;

import org.btsn.constants.VersionConstants;
import org.btsn.rulecontroller.RuleDeployer;
import org.btsn.utils.BuildRuleBase;
import org.btsn.utils.OOjdrewAPI;
import org.btsn.utils.ParseCSV;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

/**
 * Generic Healthcare Token Generator
 * Generates JSON tokens for any Healthcare workflow and target place.
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
 *   "currentPlace": "TriageService",  // Target place/service
 *   "workflow_start_time": 1730329195000,  // When token was created
 *   "data": {                         // Custom healthcare data
 *     "patientId": "P_Triage",
 *     "providerId": "RN001"
 *   }
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
 *     <serviceName>TriageService</serviceName>
 *     <operation>processTriageAssessment</operation>
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
 *   </monitorData>
 * </payload>
 * }
 * 
 * ================================================================================
 * COMMAND LINE ARGUMENTS
 * ================================================================================
 * 
 * REQUIRED ARGUMENTS:
 *   -version <vXXX>      Rule base version (e.g., v001, v002)
 *   -process <name>      Process/workflow name (e.g., healthcare/Triage_Monitor_Workflow)
 *   -place <name>        Target place/service name (e.g., TriageService)
 * 
 * OPTIONAL ARGUMENTS:
 *   -operation <op>      Service operation (default: processToken)
 *                        Healthcare typically uses: processTriageAssessment
 *   -tokens <n>          Number of tokens to generate (default: 10)
 *   -expire <ms>         Token expiry time in milliseconds (default: 120000)
 *   -data <pairs>        Custom token data as key=value,key=value
 *                        Example: patientId=P123,providerId=RN001
 *   -sequenceid <id>     Override starting sequence ID (normally auto-calculated)
 *   -skipdeploy          Skip rule deployment (use if rules already deployed)
 *   -noexit              Don't exit after completion (for embedded use)
 *   -generator <id>      Event generator identity for instrumentation tracking
 *                        (e.g., TRIAGE_EVENTGENERATOR) - included in token payload
 *                        so PetriNetInstrumentationHelper can track token source
 * 
 * ================================================================================
 * USAGE EXAMPLES
 * ================================================================================
 * 
 * Basic usage:
 *   java GenericHealthcareTokenGenerator -version v001 \
 *        -process healthcare/Triage_Monitor_Workflow \
 *        -place TriageService \
 *        -operation processTriageAssessment
 * 
 * With custom data:
 *   java GenericHealthcareTokenGenerator -version v001 \
 *        -process healthcare/Triage_Monitor_Workflow \
 *        -place TriageService \
 *        -operation processTriageAssessment \
 *        -tokens 50 \
 *        -data patientId=P123,providerId=DR001
 * 
 * Deploy rules only (no tokens):
 *   java GenericHealthcareTokenGenerator -version v001 \
 *        -process healthcare/Triage_Monitor_Workflow \
 *        -place TriageService \
 *        -operation processTriageAssessment \
 *        -tokens 0
 * 
 * Fire tokens only (rules already deployed):
 *   java GenericHealthcareTokenGenerator -version v001 \
 *        -process healthcare/Triage_Monitor_Workflow \
 *        -place TriageService \
 *        -operation processTriageAssessment \
 *        -tokens 10 \
 *        -skipdeploy
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
public class GenericHealthcareTokenGenerator {

	// OOjDREW API for rule queries
	private static final OOjdrewAPI oojdrew = new OOjdrewAPI();

	// Service configuration - POPULATED VIA COMMAND-LINE ARGS
	private static String targetPlaceName = null;  // Which place receives tokens (REQUIRED: -place)
	private static String serviceOperation = "processToken";  // Default operation (override with -operation)
	private static String attributeName = "token";  // Standard attribute (constant)
	
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
	
	// Control settings
	private static boolean exitOnCompletion = true;
	private static boolean skipPriming = true;  // Skip priming by default
	private static boolean skipDeploy = false;  // Skip deployment if already done
	
	// Event Generator identity - REQUIRED for instrumentation tracking
	private static String eventGeneratorId = null;  // REQUIRED: Must be provided via -generator arg
	
	// Token format settings
	private static String tokenFormatsFolder = "TokenFormats";  // Relative to btsn.common
	private static JSONObject loadedTokenFormat = null;  // Loaded format from JSON file
	private static String dataVariant = null;  // Optional variant to use (e.g., "chest_pain")
	
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

		System.out.println("=== HEALTHCARE TOKEN GENERATOR ===");
		System.out.println("Process: " + processName);
		System.out.println("Version: " + ruleBaseVersion);
		System.out.println("Event Generator ID: " + eventGeneratorId);
		System.out.println("Target Place: " + targetPlaceName);
		System.out.println("Service Operation: " + serviceOperation);
		System.out.println("Number of Tokens: " + numberOfTokens);
		System.out.println("Starting SequenceID: " + sequenceID);
		System.out.println("Token Expiry: " + timeToExpire + "ms");
		System.out.println("Token Data: " + (tokenData.isEmpty() ? "(from format file)" : tokenData));
		System.out.println("Data Variant: " + (dataVariant != null ? dataVariant : "(default)"));
		System.out.println("Skip Deploy: " + skipDeploy);
		System.out.println("=====================================\n");

		try {
			// STEP 0: Build rule base (only when deploying, not when firing tokens)
			// ============================================================================
			// BUSINESS WORKFLOW ARCHITECTURE NOTE:
			// ============================================================================
			// Each business workflow version (v001, v002, v003, etc.) has its OWN rule base
			// containing the workflow-specific Petri net definitions.
			//
			// The Ant file pattern for concurrent workflows is:
			//   1. Deploy ALL workflow rules SEQUENTIALLY (tokens=0) - each builds its rule base
			//   2. Fire tokens for ALL workflows in PARALLEL (-skipDeploy) - reuses rule bases
			//
			// This avoids rule deployment conflicts when running concurrent workflows.
			// The rule base is built ONCE per version during the deploy phase.
			//
			// Database Initialization (Phase 1) and Collection (Phase 3) follow the same
			// pattern but are INDEPENDENT of business workflow versions - they initialize
			// and collect from shared services used by ALL workflows.
			// ============================================================================
			if (!skipDeploy) {
				System.out.println("=== Building Rule Base for " + ruleBaseVersion + " ===");
				boolean ruleBaseBuilt = BuildRuleBase.buildRuleBase(ruleBaseVersion, true);
				if (!ruleBaseBuilt) {
					System.err.println("WARNING: Rule base build returned false for " + ruleBaseVersion);
				}
				System.out.println("=== Rule Base Built ===\n");
			}

			// STEP 1: Query rule base for service configuration
			System.out.println("=== Querying Rule Base for Service Configuration ===");
			System.out.println("Target Place: " + targetPlaceName);
			System.out.println("Service Operation: " + serviceOperation);
			System.out.println("Rule Base Version: " + ruleBaseVersion);
			
			if (!queryServiceConfiguration(targetPlaceName, serviceOperation, ruleBaseVersion)) {
				System.err.println("ERROR: Could not find service configuration in " + ruleBaseVersion);
				System.err.println("Looking for: " + targetPlaceName + ":" + serviceOperation);
				System.err.println("Check that activeService fact exists in Service.ruleml");
				cleanup();
				if (exitOnCompletion) System.exit(1);
				return;
			}
			
			System.out.println("Resolved Channel: " + resolvedServiceChannel);
			System.out.println("Resolved Port: " + resolvedServicePort);
			System.out.println("Resolved Channel ID: " + resolvedChannelId);
			System.out.println("=== Service Configuration Complete ===\n");

			// STEP 1.5: Load token format for target service
			System.out.println("=== Loading Token Format ===");
			loadTokenFormat(targetPlaceName);
			System.out.println("=== Token Format Loaded ===\n");

			// STEP 2: Deploy process rules (unless skipped)
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
					System.err.println("FATAL: Could not deploy process: " + processName);
					System.err.println("Deployment failed - cannot continue without valid workflow rules.");
					System.err.println("Please fix the workflow definition errors and try again.");
					System.exit(1);
				} else {
					Thread.sleep(2000); // Allow services to initialize
					System.out.println("Successfully deployed " + ruleBaseVersion + " rules");
				}
				System.out.println("=== Deployment Complete ===\n");
			} else {
				System.out.println("=== Skipping Deployment (already deployed) ===\n");
			}

			Long timeToCommit = System.currentTimeMillis() - startTime;

			// STEP 3: Generate and send tokens
			if (numberOfTokens > 0) {
				System.out.println("=== Generating Tokens ===");
				generateTokens();
			} else {
				System.out.println("=== No Tokens Requested (deploy only mode) ===");
			}

			// STEP 4: Report results
			long eventTime = System.currentTimeMillis() - startTime - timeToCommit;
			System.out.println("\n=== Token Generation Complete ===");
			System.out.println("Rule Commitment: " + timeToCommit + "ms");
			System.out.println("Token Generation: " + eventTime + "ms");
			System.out.println("Tokens Sent: " + numberOfTokens);
			System.out.println("Target: " + targetPlaceName + ":" + serviceOperation + " @ " + resolvedServiceChannel + ":" + resolvedServicePort);
			
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
				
				case "-variant":
				case "--variant":
					if (i + 1 < args.length) {
						dataVariant = args[++i];
						System.out.println("  Parsed -variant: " + dataVariant);
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
			System.err.println("Usage: java GenericHealthcareTokenGenerator -version <vXXX> -process <workflow> -place <place> -operation <op>");
			System.err.println("Example: -version v001 -process healthcare/Triage_Monitor_Workflow -place TriageService -operation processTriageAssessment");
			System.exit(1);
		}
		
		if (processName == null || processName.isEmpty()) {
			System.err.println("ERROR: Process name is required!");
			System.err.println("Usage: java GenericHealthcareTokenGenerator -version <vXXX> -process <workflow> -place <place> -operation <op>");
			System.err.println("Example: -version v001 -process healthcare/Triage_Monitor_Workflow -place TriageService -operation processTriageAssessment");
			System.exit(1);
		}
		
		if (targetPlaceName == null || targetPlaceName.isEmpty()) {
			System.err.println("ERROR: Target place is required!");
			System.err.println("Usage: java GenericHealthcareTokenGenerator -version <vXXX> -process <workflow> -place <place> -operation <op>");
			System.err.println("Example: -version v001 -process healthcare/Triage_Monitor_Workflow -place TriageService -operation processTriageAssessment");
			System.exit(1);
		}
		
		// FAIL LOUDLY: Event generator ID is REQUIRED
		if (eventGeneratorId == null || eventGeneratorId.trim().isEmpty()) {
			System.err.println("================================================================================");
			System.err.println("FATAL ERROR: -generator argument is REQUIRED");
			System.err.println("================================================================================");
			System.err.println("");
			System.err.println("The event generator ID must be provided to track token provenance.");
			System.err.println("This value MUST match the EVENT_GENERATOR 'id' in your workflow JSON file.");
			System.err.println("");
			System.err.println("Example: -generator TRIAGE_EVENTGENERATOR");
			System.err.println("================================================================================");
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
	 * Query OOjDREW for service configuration
	 */
	private static boolean queryServiceConfiguration(String serviceName, String operation, String version) {
		try {
			// Master rule base is always in btsn.common/RuleFolder.{version}/
			File currentDir = new File("").getAbsoluteFile();
			File commonDir = new File(currentDir.getParent(), "btsn.common");
			File ruleBaseFile = new File(commonDir, "RuleFolder." + version + "/Service.ruleml");
			String ruleFolder = ruleBaseFile.getAbsolutePath();
			
			System.out.println("DEBUG: Loading rule base from: " + ruleFolder);
			oojdrew.parseKnowledgeBase(ruleFolder, true);
			
			// Try activeService first
			String query = String.format(
				"<Query><Atom><Rel>activeService</Rel><Ind>%s</Ind><Ind>%s</Ind><Var>channelId</Var><Var>port</Var></Atom></Query>",
				serviceName, operation);
			
			System.out.println("Querying activeService facts...");
			System.out.println("Query: " + query);
			
			oojdrew.issueRuleMLQuery(query);
			
			// If not found, try hasOperation
			if (oojdrew.rowsReturned == 0) {
				query = String.format(
					"<Query><Atom><Rel>hasOperation</Rel><Ind>%s</Ind><Ind>%s</Ind><Var>channelId</Var><Var>port</Var></Atom></Query>",
					serviceName, operation);
				System.out.println("Trying hasOperation fallback...");
				oojdrew.issueRuleMLQuery(query);
			}
			
			System.out.println("Rows returned: " + oojdrew.rowsReturned);
			
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
				System.out.println("Result [" + i + "]: " + key + " = " + value);
				
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
			System.out.println("Querying boundChannel for: " + channelId);
			query = String.format(
				"<Query><Atom><Rel>boundChannel</Rel><Ind>%s</Ind><Var>channel</Var></Atom></Query>",
				channelId);
			
			oojdrew.issueRuleMLQuery(query);
			System.out.println("boundChannel rows returned: " + oojdrew.rowsReturned);
			
			for (int i = 0; i < oojdrew.rowsReturned; i++) {
				String key = String.valueOf(oojdrew.rowData[i][0]);
				String value = String.valueOf(oojdrew.rowData[i][1]);
				System.out.println("boundChannel result [" + i + "]: " + key + " = " + value);
				
				if ("?channel".equals(key)) {
					resolvedServiceChannel = value;
					break;
				}
			}
			
			if (resolvedServiceChannel != null && resolvedServicePort != null) {
				System.out.println("Successfully resolved service configuration");
				return true;
			}
			
			return false;
			
		} catch (Exception e) {
			System.err.println("Error querying service configuration: " + e.getMessage());
			e.printStackTrace();
			return false;
		}
	}

	/**
	 * Generate and send tokens
	 * Uses EventTriggeringFile.csv if present, otherwise fires immediately
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
			System.out.println("Firing tokens immediately...");
			generateTokensImmediate();
		} else {
			System.out.println("Processing token sequence from EventTriggeringFile.csv...");
			System.out.println("Will fire " + numberOfTokens + " token(s) from the sequence");
			generateTokensWithTiming(tokenSequence);
		}
	}

	/**
	 * Generate tokens immediately (no timing file)
	 */
	private static void generateTokensImmediate() throws Exception {
		int sidInc = VersionConstants.TOKEN_INCREMENT;
		for (int i = 0; i < numberOfTokens; i++) {
			String jsonToken = createHealthcareToken(sequenceID);
			System.out.println("Sending token " + (i + 1) + "/" + numberOfTokens + " (seqID=" + sequenceID + ")");
			sendToken(jsonToken, sequenceID);
			sequenceID += sidInc;
		}
	}

	/**
	 * Generate tokens according to EventTriggeringFile.csv timing
	 */
	private static void generateTokensWithTiming(TreeMap<Integer, Integer> tokenSequence) throws Exception {
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
					String jsonToken = createHealthcareToken(sequenceID);
					
					// FIRE IMMEDIATELY - as fast as possible
					sendToken(jsonToken, sequenceID);
					
					sequenceID += sidInc;
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

	/**
	 * Create Healthcare JSON token using loaded format file
	 */
	@SuppressWarnings("unchecked")
	private static String createHealthcareToken(int seqID) {
		JSONObject token = new JSONObject();
		
		long currentTime = System.currentTimeMillis();
		
		// Core token fields (always set)
		token.put("tokenId", String.valueOf(seqID));
		token.put("version", ruleBaseVersion);
		token.put("notAfter", currentTime + timeToExpire);
		token.put("currentPlace", targetPlaceName);
		token.put("workflow_start_time", currentTime);
		
		// Build data object from format file, variant, and command-line overrides
		JSONObject data = buildTokenData(seqID, currentTime);
		token.put("data", data);
		
		return token.toJSONString();
	}
	
	/**
	 * Build token data from format file, variant, and command-line overrides
	 * 
	 * Priority (highest to lowest):
	 * 1. Command-line -data argument (always wins)
	 * 2. Data variant from format file (if -variant specified)
	 * 3. Default data from format file tokenStructure
	 * 4. Empty data (if no format file found)
	 */
	@SuppressWarnings("unchecked")
	private static JSONObject buildTokenData(int seqID, long currentTime) {
		JSONObject data = new JSONObject();
		
		// Step 1: Start with default data from format file
		if (loadedTokenFormat != null) {
			JSONObject tokenStructure = (JSONObject) loadedTokenFormat.get("tokenStructure");
			if (tokenStructure != null) {
				JSONObject defaultData = (JSONObject) tokenStructure.get("data");
				if (defaultData != null) {
					// Copy default data, replacing placeholders
					for (Object key : defaultData.keySet()) {
						String value = String.valueOf(defaultData.get(key));
						value = replacePlaceholders(value, seqID, currentTime);
						data.put(key, value);
					}
				}
			}
			
			// Step 2: Apply variant if specified
			if (dataVariant != null && !dataVariant.isEmpty()) {
				JSONObject variants = (JSONObject) loadedTokenFormat.get("dataVariants");
				if (variants != null) {
					JSONObject variantData = (JSONObject) variants.get(dataVariant);
					if (variantData != null) {
						// Overlay variant data
						for (Object key : variantData.keySet()) {
							String keyStr = String.valueOf(key);
							if (!keyStr.startsWith("_")) {  // Skip comment fields
								String value = String.valueOf(variantData.get(key));
								value = replacePlaceholders(value, seqID, currentTime);
								data.put(key, value);
							}
						}
						System.out.println("  Applied variant: " + dataVariant);
					} else {
						System.out.println("  WARNING: Variant '" + dataVariant + "' not found in format file");
					}
				}
			}
		}
		
		// Step 3: Apply command-line overrides (highest priority)
		if (tokenData != null && !tokenData.isEmpty()) {
			String[] pairs = tokenData.split(",");
			for (String pair : pairs) {
				String[] kv = pair.split("=");
				if (kv.length == 2) {
					String value = replacePlaceholders(kv[1].trim(), seqID, currentTime);
					data.put(kv[0].trim(), value);
				}
			}
		}
		
		return data;
	}
	
	/**
	 * Replace placeholders in token data values
	 */
	private static String replacePlaceholders(String value, int seqID, long currentTime) {
		if (value == null) return "";
		
		return value
			.replace("${sequenceId}", String.valueOf(seqID))
			.replace("${version}", ruleBaseVersion)
			.replace("${targetPlace}", targetPlaceName)
			.replace("${notAfter}", String.valueOf(currentTime + timeToExpire))
			.replace("${workflowStartTime}", String.valueOf(currentTime))
			.replace("${currentTime}", String.valueOf(currentTime));
	}
	
	/**
	 * Load token format from TokenFormats folder
	 * 
	 * Search order:
	 * 1. Current working directory: ./TokenFormats/{serviceName}.json
	 * 2. Event generator project: btsn.healthcare.eventgenerators/TokenFormats/
	 * 3. Common directory: btsn.common/TokenFormats/
	 * 
	 * Falls back to _default.json if service-specific format not found
	 */
	private static void loadTokenFormat(String serviceName) {
		loadedTokenFormat = null;
		
		// Search paths in priority order
		String[] searchPaths = {
			"./TokenFormats",                          // Current working directory
			"../btsn.healthcare.eventgenerators/TokenFormats",  // Sibling project
			"../btsn.common/TokenFormats",             // Common directory (sibling)
			"../../btsn.common/TokenFormats"           // Common directory (parent's sibling)
		};
		
		File tokenFormatsDir = null;
		
		// Find the first existing TokenFormats directory
		for (String path : searchPaths) {
			File dir = new File(path);
			if (dir.exists() && dir.isDirectory()) {
				tokenFormatsDir = dir;
				System.out.println("Found TokenFormats directory: " + dir.getAbsolutePath());
				break;
			}
		}
		
		// Also check via absolute path resolution
		if (tokenFormatsDir == null) {
			File currentDir = new File("").getAbsoluteFile();
			System.out.println("Current working directory: " + currentDir.getAbsolutePath());
			
			// Check if TokenFormats is in current directory
			File localFormats = new File(currentDir, "TokenFormats");
			if (localFormats.exists() && localFormats.isDirectory()) {
				tokenFormatsDir = localFormats;
				System.out.println("Found TokenFormats in current directory: " + localFormats.getAbsolutePath());
			}
		}
		
		if (tokenFormatsDir == null) {
			System.out.println("WARNING: TokenFormats directory not found in any search path");
			System.out.println("Searched: " + String.join(", ", searchPaths));
			System.out.println("Token format will use defaults only");
			return;
		}
		
		// Try service-specific format first
		File formatFile = new File(tokenFormatsDir, serviceName + ".json");
		if (!formatFile.exists()) {
			System.out.println("No specific format for " + serviceName + ", trying _default.json");
			formatFile = new File(tokenFormatsDir, "_default.json");
		}
		
		if (!formatFile.exists()) {
			System.out.println("WARNING: No token format file found in " + tokenFormatsDir.getAbsolutePath());
			System.out.println("Token format will use defaults only");
			return;
		}
		
		// Load the format file
		try {
			System.out.println("Loading token format from: " + formatFile.getAbsolutePath());
			JSONParser parser = new JSONParser();
			FileReader reader = new FileReader(formatFile);
			loadedTokenFormat = (JSONObject) parser.parse(reader);
			reader.close();
			
			// Log what we loaded
			String desc = (String) loadedTokenFormat.get("description");
			if (desc != null) {
				System.out.println("Format description: " + desc);
			}
			
			JSONObject tokenStructure = (JSONObject) loadedTokenFormat.get("tokenStructure");
			if (tokenStructure != null) {
				JSONObject defaultData = (JSONObject) tokenStructure.get("data");
				if (defaultData != null) {
					System.out.println("Default data fields: " + defaultData.keySet());
				}
			}
			
			JSONObject variants = (JSONObject) loadedTokenFormat.get("dataVariants");
			if (variants != null) {
				System.out.println("Available variants: " + variants.keySet());
			}
			
		} catch (Exception e) {
			System.err.println("ERROR: Failed to load token format: " + e.getMessage());
			loadedTokenFormat = null;
		}
	}

	/**
	 * Send token to target service
	 */
	private static void sendToken(String jsonToken, int seqID) throws IOException {
		// Build XML payload wrapper (ServiceThread expects XML wrapper around token)
		String xmlPayload = buildXMLPayload(jsonToken, seqID);
		
		// Send via UDP
		sendUDPEvent(xmlPayload, resolvedServiceChannel, resolvedServicePort);
	}

	/**
	 * Build XML payload wrapper for ServiceThread
	 */
	private static String buildXMLPayload(String tokenValue, int seqID) {
		long currentTime = System.currentTimeMillis();
		String notAfter = Long.toString(currentTime + timeToExpire);
		
		System.out.println("DEBUG: Building XML payload with sequenceID=" + seqID);
		
		StringBuilder xml = new StringBuilder();
		xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
		xml.append("<payload>\n");
		xml.append("  <header>\n");
		xml.append("    <sequenceId>").append(seqID).append("</sequenceId>\n");
		xml.append("    <ruleBaseVersion>").append(ruleBaseVersion).append("</ruleBaseVersion>\n");
		xml.append("    <priortiseSID>true</priortiseSID>\n");
		xml.append("    <monitorIncomingEvents>true</monitorIncomingEvents>\n");
		xml.append("  </header>\n");
		xml.append("  <service>\n");
		xml.append("    <serviceName>").append(targetPlaceName).append("</serviceName>\n");
		xml.append("    <operation>").append(serviceOperation).append("</operation>\n");
		xml.append("  </service>\n");
		xml.append("  <joinAttribute>\n");
		xml.append("    <attributeName>").append(attributeName).append("</attributeName>\n");
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