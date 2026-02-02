package org.btsn.handlers;

import java.io.File;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.apache.log4j.Level;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.btsn.constants.VersionConstants;
import org.btsn.derby.Analysis.BuildServiceAnalysisDatabase;
import org.btsn.handlers.PetriNetInstrumentationHelper;
import org.btsn.json.jsonLibrary;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.JSONArray;
import org.btsn.utils.ChannelPublish;
import org.btsn.utils.OOjdrewAPI;
import org.btsn.utils.StringFileIO;
import org.btsn.utils.XPathHelperCommon;

/**
 * ServiceThread - THE ORCHESTRATOR
 * 
 * ROLE: Orchestrates the complete service workflow: 1. Receives events from
 * EventReactor 2. Invokes services via reflection (ServiceHelper) 3. Publishes
 * results via EventPublisher
 * 
 * ARCHITECTURE: EventReactor -> ServiceThread (Orchestrator) -> Service ->
 * EventPublisher
 * 
 * PRIORITY SYSTEM: Based on sequence ID value (lower = higher priority)
 */
class ServiceThread implements Runnable {

	private String operationName;
	private String serviceName;
	private String returnAttributeName;
	private String ruleBaseVersion;
	private Integer sequenceID;
	private Integer phaseSequenceID;
	protected long taskArrivalTime;
	// Service identity - set during construction
	private final String myServiceName;
	private final String myOperationName;

	private long serviceInvocationTime;
	private long servicePublishTime;

	private String nodeType;
	private ArrayList<String> inputCollection = new ArrayList<String>();
	private ArrayList<String> inputArgs = new ArrayList<String>();

	private String basePath;

	// ========================================================================
	// DOMAIN CONFIGURATION - Dynamically derived from ServiceThread's package
	// ========================================================================
	
	/**
	 * Derives the service package from the ServiceThread's own package.
	 * e.g., org.btsn.healthcare.handlers -> org.btsn.healthcare.places
	 *       org.btsn.petrinet.handlers -> org.btsn.petrinet.places
	 */
	private static final String SERVICE_PACKAGE = deriveServicePackage();
	
	private static String deriveServicePackage() {
		String myPackage = ServiceThread.class.getPackage().getName();
		// Replace ".handlers" with ".places"
		if (myPackage.endsWith(".handlers")) {
			String derivedPackage = myPackage.substring(0, myPackage.length() - ".handlers".length()) + ".places";
			System.out.println("ServiceThread: Derived SERVICE_PACKAGE from own package: " + derivedPackage);
			return derivedPackage;
		}
		// Fallback - try to extract domain from package (org.btsn.{domain}.handlers)
		String[] parts = myPackage.split("\\.");
		if (parts.length >= 3 && "org".equals(parts[0]) && "btsn".equals(parts[1])) {
			String domain = parts[2]; // healthcare, petrinet, etc.
			String derivedPackage = "org.btsn." + domain + ".places";
			System.out.println("ServiceThread: Derived SERVICE_PACKAGE from domain: " + derivedPackage);
			return derivedPackage;
		}
		// Ultimate fallback
		System.err.println("ServiceThread: WARNING - Could not derive SERVICE_PACKAGE, using default");
		// Ultimate fallback - FAIL EXPLICITLY
		throw new RuntimeException("ServiceThread: Cannot derive SERVICE_PACKAGE from package: " + myPackage + 
		    ". Expected package format: org.btsn.{domain}.handlers");
	}
	
	// ========================================================================

	private ArrayList<String> nextServiceCollection = new ArrayList<String>();
	private ArrayList<String> nextOperationCollection = new ArrayList<String>();
	private ArrayList<String> nextChannelCollection = new ArrayList<String>();
	private ArrayList<String> nextPortCollection = new ArrayList<String>();
	private ArrayList<Integer> keyRemovalCollection = new ArrayList<Integer>();

	private boolean enableCompletedJoinPriority = true;
	private boolean loadJoinProcessingSettings = true;

	private ServiceHelper serviceHelper = null;
	private PetriNetInstrumentationHelper petriNetHelper = null;
	private boolean enablePetriNetInstrumentation = true;
	private TransitionHandler nodeTypeProcessor = null;

	private EventPublisher eventPublisher;
	private ChannelPublish publish = new ChannelPublish();
	private static TreeMap<Integer, String> nextServiceMap = new TreeMap<Integer, String>();

	private TreeMap<Integer, Long> sequenceIDJoinWindow = new TreeMap<Integer, Long>();
	public static ConcurrentHashMap<Integer, ConcurrentSkipListMap<String, String>> argValPriorityMap = new ConcurrentHashMap<Integer, ConcurrentSkipListMap<String, String>>();

	private static String DECISION_GREATER_THAN = "DECISION_GREATER_THAN";
	private static String DECISION_LESS_THAN = "DECISION_LESS_THAN";
	private static String DECISION_NOT_EQUAL = "DECISION_NOT_EQUAL";
	private static String DECISION_EQUAL_TO = "DECISION_EQUAL_TO";
	private static String DECISION_TRUE = "DECISION_TRUE";
	private static String DECISION_FALSE = "DECISION_FALSE";
	protected static String incomingXMLPayLoad;
	private static String outgoingXMLPayLoad;
	protected TreeMap<Long, String> dataMap = new TreeMap<Long, String>();
	// Track fork contributions for join coordination
	private static ConcurrentHashMap<Integer, List<JoinContribution>> joinContributions = new ConcurrentHashMap<>();
	
	// FIX: Store expected join count PER JOIN, not as instance variable
	// Key = joinID (base token ID), Value = expected number of inputs
	private static ConcurrentHashMap<Integer, Integer> joinExpectedCounts = new ConcurrentHashMap<>();

	private String workingRuleBaseVersion = "vzzz";

	private TreeMap<Integer, String> decisionValueCollection = new TreeMap<Integer, String>();

	ConcurrentNavigableMap<Long, Integer> sequenceIDCostMap = new ConcurrentSkipListMap<Long, Integer>();

	private OOjdrewAPI oojdrew = new OOjdrewAPI();
	public TreeMap<String, String> knowledgeBaseMap = new TreeMap<String, String>();
	private boolean ruleBaseLoaded = false;
	private boolean monitorIncomingEvents = false;

	protected TreeMap<String, String> headerMap = new TreeMap<String, String>();
	protected TreeMap<String, String> attrMap = new TreeMap<String, String>();
	protected TreeMap<String, String> serviceMap = new TreeMap<String, String>();
	protected TreeMap<String, String> monitorDataMap = new TreeMap<String, String>();
	private TreeMap<String, String> monitorSettingsMap = new TreeMap<String, String>();
	private TreeMap<String, String> serviceMeasuresDBMap = new TreeMap<String, String>();

	private BuildServiceAnalysisDatabase serviceMeasures = new BuildServiceAnalysisDatabase();

	protected XPathHelperCommon xph = new XPathHelperCommon();
	private jsonLibrary jsonAttributes;

	private Logger logger = Logger.getLogger(ServiceThread.class.getName());
	private boolean logginOn = true;
	protected long costKey = 0;

	protected volatile boolean isShutdown = false;
	private EventReactor thread;
	private String serviceChannel;
	protected long currentWorkflowStartTime = 0;  // Captured per-event to prevent race conditions
	
	// Marking capture fields for Petri Net analysis
	protected int bufferSizeAtDequeue = 0;  // Buffer size when token dequeued
	protected int maxQueueCapacity = 0;     // Configured max queue size

	// Statistics tracking
	private ScheduledExecutorService statsExecutor;
	private static final long STATS_INTERVAL_MINUTES = 15;
	private AtomicInteger eventCounter = new AtomicInteger(0);
	private static final int STATS_LOG_INTERVAL = 100;

	static {
		System.setProperty("java.net.preferIPv4Stack", "true");
		System.setProperty("java.net.preferIPv6Addresses", "false");
		System.setProperty("java.net.useSystemProxies", "false");
		System.setProperty("java.net.preferIPv4Addresses", "true");
		System.setProperty("jdk.net.useExclusiveBind", "false");
	}

	ServiceThread(String serviceChannel, String rulePort, EventPublisher eventPublisher, String myServiceName,
			String myOperationName) {
		this.serviceChannel = serviceChannel;
		this.eventPublisher = eventPublisher;
		this.myServiceName = myServiceName;
		this.myOperationName = myOperationName;

		System.out.println("=== SERVICE ORCHESTRATOR INITIALIZING ===");
		System.out.println("My Identity: " + myServiceName + ":" + myOperationName);
		System.out.println("Channel: " + serviceChannel + ", Port: " + rulePort);
		if (!logginOn) {
			List<Logger> loggers = new ArrayList<>();
			Enumeration currentLoggers = LogManager.getCurrentLoggers();
			while (currentLoggers.hasMoreElements()) {
				Logger logger = (Logger) currentLoggers.nextElement();
				loggers.add(logger);
			}
			loggers.add(Logger.getRootLogger());
			for (Logger logger : loggers) {
				logger.setLevel(Level.OFF);
			}
		}

		try {
			serviceHelper = new ServiceHelper();
			nodeTypeProcessor = new TransitionHandler(this);
			if (enablePetriNetInstrumentation) {
			    petriNetHelper = new PetriNetInstrumentationHelper(serviceChannel, rulePort);
			    if (PetriNetInstrumentationHelper.isStub()) {
			        logger.info("ORCHESTRATOR: Petri Net instrumentation STUB (no-op mode)");
			    } else {
			        logger.info("ORCHESTRATOR: Petri Net instrumentation ENABLED (full mode)");
			    }
			}

			File commonBase = new File("./");
			String commonPath = commonBase.getCanonicalPath();
			this.basePath = commonPath;

			String serviceLoaderDirectory = commonPath + "/ServiceLoaderQueries/";
			String loaderSettings = serviceLoaderDirectory + "loaderSettings.xml";
			String xmlLoaderSettings = StringFileIO.readFileAsString(loaderSettings);

			monitorSettingsMap = xph.findMultipleXMLItems(xmlLoaderSettings, "//MonitorSettings/*");
			monitorIncomingEvents = Boolean.parseBoolean(monitorSettingsMap.get("monitorIncomingEvents"));

			if (loadJoinProcessingSettings) {
				String joinPriorityEnabled = monitorSettingsMap.get("enableCompletedJoinPriority");
				if (joinPriorityEnabled != null) {
					enableCompletedJoinPriority = Boolean.parseBoolean(joinPriorityEnabled);
					logger.info("Join processing strategy: "
							+ (enableCompletedJoinPriority ? "OPTIMIZED (any complete join)"
									: "SEQUENTIAL (strict order)"));
				}
			}

			// Setup statistics executor
			statsExecutor = Executors
					.newSingleThreadScheduledExecutor(r -> new Thread(r, "ServiceStats-" + serviceChannel));

			// Schedule periodic statistics logging
			statsExecutor.scheduleAtFixedRate(() -> {
				try {
					logTimingStatistics();
				} catch (Exception e) {
					logger.error("Error logging timing statistics", e);
				}
			}, STATS_INTERVAL_MINUTES, STATS_INTERVAL_MINUTES, TimeUnit.MINUTES);

			logger.info("Starting EventReactor for channel: " + serviceChannel + ", port: " + rulePort);

			int maxRetries = 3;
			int retryDelay = 1000;

			for (int attempt = 1; attempt <= maxRetries; attempt++) {
				try {
					thread = new EventReactor(serviceChannel, rulePort);
					thread.start();
					logger.info("EventReactor started successfully on attempt " + attempt);
					break;

				} catch (Exception e) {
					logger.warn("Attempt " + attempt + "/" + maxRetries + " failed to start EventReactor: "
							+ e.getMessage());

					if (attempt == maxRetries) {
						logger.error("All attempts failed to start EventReactor", e);
						throw new RuntimeException(
								"Failed to initialize ServiceThread after " + maxRetries + " attempts", e);
					} else {
						try {
							Thread.sleep(retryDelay);
							retryDelay *= 2;
						} catch (InterruptedException ie) {
							Thread.currentThread().interrupt();
							throw new RuntimeException("Interrupted while retrying EventReactor creation", ie);
						}
					}
				}
			}

			System.out.println("=== SERVICE ORCHESTRATOR READY ===");

		} catch (Exception e) {
			logger.error("Failed to initialize ServiceThread for channel: " + serviceChannel, e);
			throw new RuntimeException("ServiceThread initialization failed", e);
		}
	}

	/**
	 * Validate timing data to ensure all timestamps are present and logical
	 */
	private TreeMap<Integer, ArrayList<Object>> validateTimingData(TreeMap<Integer, ArrayList<Object>> rawData) {
		TreeMap<Integer, ArrayList<Object>> validData = new TreeMap<>();

		for (Map.Entry<Integer, ArrayList<Object>> entry : rawData.entrySet()) {
			ArrayList<Object> data = entry.getValue();

			if (data.size() >= 8) {
				try {
					long sequenceId = (Long) data.get(1);
					String serviceName = (String) data.get(2);
					long arrivalTime = (Long) data.get(4);
					long invocationTime = (Long) data.get(5);
					long publishTime = (Long) data.get(6);
					long workflowStartTime = (Long) data.get(7);

					// Validate timing sequence
					boolean isValid = true;
					StringBuilder issues = new StringBuilder();

					// Skip MonitorService records - they're not workflow services
					if ("MonitorService".equals(serviceName)) {
						logger.debug("Skipping MonitorService record for sequenceId " + sequenceId);
						continue;
					}

					// Check for zero timestamps
					if (arrivalTime == 0 || invocationTime == 0 || publishTime == 0) {
						isValid = false;
						issues.append("Zero timestamps; ");
					}

					// Check logical sequence: arrival <= invocation <= publish
					if (arrivalTime > invocationTime) {
						isValid = false;
						issues.append("Arrival after invocation (" + arrivalTime + " > " + invocationTime + "); ");
					}

					if (invocationTime > publishTime) {
						isValid = false;
						issues.append("Invocation after publish (" + invocationTime + " > " + publishTime + "); ");
					}

					// Check for unreasonable timing (> 30 seconds total)
					long totalTime = publishTime - arrivalTime;
					if (totalTime > 30000) {
						isValid = false;
						issues.append("Excessive total time (" + totalTime + "ms); ");
					}

					// Check for negative times
					if (totalTime < 0) {
						isValid = false;
						issues.append("Negative total time (" + totalTime + "ms); ");
					}

					if (isValid) {
						validData.put(entry.getKey(), data);
						logger.debug("Validated sequenceId " + sequenceId + " - " + serviceName + " (queue:"
								+ (invocationTime - arrivalTime) + "ms, " + "service:" + (publishTime - invocationTime)
								+ "ms)");
					} else {
						logger.warn("Excluded sequenceId " + sequenceId + " (" + serviceName + ") - timing issues: "
								+ issues.toString());
					}

				} catch (Exception e) {
					logger.warn("Excluded record due to data parsing error: " + e.getMessage());
				}
			} else {
				logger.warn("Excluded record with insufficient data fields (has " + data.size() + ", needs 8)");
			}
		}

		logger.info("Validation complete: " + validData.size() + " valid records from " + rawData.size() + " total");
		return validData;
	}

	// Then replace the workflow base calculation with:
	private int calculateWorkflowBase(long sequenceId) {
	    return VersionConstants.getWorkflowBaseFromSequenceId((int) sequenceId);
	}

	public void shutdown() {
		logger.info("Shutting down ServiceThread for channel: " + serviceChannel);

		try {
			logger.info("=== FINAL TIMING STATISTICS AT SHUTDOWN ===");
			logTimingStatistics();

		} catch (Exception e) {
			logger.error("Error logging final statistics", e);
		}

		isShutdown = true;

		if (statsExecutor != null) {
			statsExecutor.shutdown();
		}

		if (thread != null) {
			try {
				thread.shutdown();
				logger.info("EventReactor shutdown completed");
			} catch (Exception e) {
				logger.error("Error shutting down EventReactor", e);
			}
		}

		try {
			argValPriorityMap.clear();
			sequenceIDJoinWindow.clear();
			joinExpectedCounts.clear();  // FIX: Clear expected counts map
			joinContributions.clear();   // FIX: Clear contributions map
			knowledgeBaseMap.clear();
			logger.info("ServiceThread shutdown completed");
		} catch (Exception e) {
			logger.error("Error clearing ServiceThread data structures", e);
		}
	}

	@Override
	public void run() {
		logger.info("ServiceThread ORCHESTRATOR starting main processing loop");

		while (!isShutdown) {
			try {
				
		         // Get token FIRST (this blocks if queue is empty)
	            dataMap = thread.getScheduledToken();
	            // MARKING CAPTURE: Get buffer state AFTER dequeuing
	            // This captures "tokens still waiting" not "tokens including this one"
	            // In a cyclic workflow, this prevents phantom accumulation
	        
				if (thread != null) {
					bufferSizeAtDequeue = thread.getQueueSize();
					if (maxQueueCapacity == 0) {
						maxQueueCapacity = thread.getMaxQueueCapacity();
					}
				}
				
			//	dataMap = thread.getScheduledToken();

				if (isShutdown) {
					logger.info("Shutdown requested, exiting processing loop");
					break;
				}

				taskArrivalTime = System.currentTimeMillis();
				attrMap.clear();
				
				// FIX: Reset per-message state that should NOT persist between messages
				// These were causing stale values when processing different services/transitions
				nodeType = null;
				ruleBaseLoaded = false;  // Force re-query of rule base facts for each message
				
				costKey = dataMap.firstKey();
				jsonAttributes = new jsonLibrary();

				incomingXMLPayLoad = dataMap.remove(costKey);
				headerMap = xph.findMultipleXMLItems(incomingXMLPayLoad, "//header/*");
				attrMap = xph.findMultipleXMLItems(incomingXMLPayLoad, "//joinAttribute/*");
				serviceMap = xph.findMultipleXMLItems(incomingXMLPayLoad, "//service/*");
				monitorDataMap = xph.findMultipleXMLItems(incomingXMLPayLoad, "//monitorData/*");

				String payloadAttributeName = attrMap.get("attributeName");
				String payloadAttributeValue = attrMap.get("attributeValue");

				logger.debug("ORCHESTRATOR: Processing:: " + payloadAttributeName + " = " + payloadAttributeValue);

				jsonAttributes.put(payloadAttributeName, payloadAttributeValue);
				parsePayLoad();

			} catch (InterruptedException e) {
				logger.info("ServiceThread interrupted, checking shutdown status");
				if (isShutdown) {
					logger.info("Shutdown requested, exiting gracefully");
					break;
				}
				Thread.currentThread().interrupt();
				logger.warn("Unexpected interruption, continuing", e);
			} catch (Exception e) {
				logger.error("Error in ServiceThread main loop", e);
				continue;
			}
		}

		logger.info("ServiceThread ORCHESTRATOR processing loop exited");
	}

	public <T, E> T getKeyByValue(ConcurrentNavigableMap<T, E> map, E value) {
		for (Entry<T, E> entry : map.entrySet()) {
			if (value.equals(entry.getValue())) {
				return entry.getKey();
			}
		}
		return null;
	}

	protected void parsePayLoad() {
	    if (isShutdown) {
	        return;
	    }

	    outgoingXMLPayLoad = incomingXMLPayLoad;
	    phaseSequenceID = Integer.parseInt(headerMap.get("sequenceId"));
	    
	    // CRITICAL FIX: Capture workflowStartTime immediately - before any other event can arrive
	    String workflowStartTimeStr = monitorDataMap.get("processStartTime");
	    if (workflowStartTimeStr != null) {
	        try {
	            currentWorkflowStartTime = Long.parseLong(workflowStartTimeStr);
	            logger.debug("CAPTURE: WorkflowStartTime=" + currentWorkflowStartTime + " for seqID=" + phaseSequenceID);
	        } catch (NumberFormatException e) {
	            logger.warn("Failed to parse workflowStartTime: " + workflowStartTimeStr);
	            currentWorkflowStartTime = 0;
	        }
	    } else {
	        currentWorkflowStartTime = 0;
	    }


	    monitorIncomingEvents |= Boolean.parseBoolean(headerMap.get("monitorIncomingEvents"));
	    int joinID = mapFromSequenceID(phaseSequenceID);
	    
	    logger.info("Incoming Sequence ID = " + phaseSequenceID);
	    logger.info("MonitorIncomingEvents+ = " + monitorIncomingEvents);

	    // Check local setting first
	    boolean localSetting = Boolean.parseBoolean(monitorSettingsMap.get("monitorIncomingEvents"));
	    String eventMonitoring = headerMap.get("monitorIncomingEvents");

	    // Only monitor if local is true AND (event is null OR event is true)
	    monitorIncomingEvents = localSetting && 
	        (eventMonitoring == null || Boolean.parseBoolean(eventMonitoring));

	    ruleBaseVersion = headerMap.get("ruleBaseVersion");
	    String payloadRuleBaseVersion = ruleBaseVersion;
	    if (!ServiceLoader.getRuleVersion(payloadRuleBaseVersion)) {
	        System.err.println("Non valid ruleSet - Dropping payload with version: " + payloadRuleBaseVersion);
	        return;
	    }
	    logger.debug("Using RuleBaseVersion: " + ruleBaseVersion);

	    String payloadAttributeName = attrMap.get("attributeName");
	    String payloadAttributeValue = attrMap.get("attributeValue");

	    serviceName = serviceMap.get("serviceName");
	    operationName = serviceMap.get("operation");
	    
	    // AGNOSTIC FILTER: Only process events addressed to this ServiceThread
	    if (!this.myServiceName.equals(serviceName)) {
	        logger.debug("FILTER: Event for " + serviceName + "." + operationName + 
	                    " ignored by " + this.myServiceName + " thread");
	        return;
	    }

	    logger.debug("FILTER: Event for " + serviceName + "." + operationName + 
	                " accepted by " + this.myServiceName + " thread");
	    
	    jsonAttributes.put(payloadAttributeName, payloadAttributeValue);
	    ConcurrentSkipListMap<String, String> incomingArgValPair = new ConcurrentSkipListMap<>();
	    incomingArgValPair.put(payloadAttributeName, payloadAttributeValue);

	    // Thread-safe join map management
	    // The sender derives attribute names from token ID (token_branch1, token_branch2, etc.)
	    // based on the branch number encoded in the token ID. The payloadAttributeName arrives
	    // with the correct name so no receiver-side derivation is needed.
	    argValPriorityMap.putIfAbsent(joinID, new ConcurrentSkipListMap<>());
	    ConcurrentSkipListMap<String, String> currentJoinMap = argValPriorityMap.get(joinID);
	    if (currentJoinMap != null) {
	        currentJoinMap.putIfAbsent(payloadAttributeName, payloadAttributeValue);
	        
	        logger.debug("JOIN-MAP: Added key=" + payloadAttributeName + " to join " + joinID + 
	                    " (tokenId=" + phaseSequenceID + ", mapSize=" + currentJoinMap.size() + ")");
	    }

	    sequenceIDJoinWindow.put(joinID, AdjustJoinWindow(joinID, Long.parseLong(attrMap.get("notAfter"))));

	    String ruleBaseLocation = basePath + "/RuleFolder." + ruleBaseVersion + "/" + operationName + "/Service.ruleml";
	    String knowledgeBase = null;

	    logger.info("DEBUG: Loading rule base for operation: " + operationName);
	    logger.info("DEBUG: Rule base location: " + ruleBaseLocation);

	    try {
	        if ((workingRuleBaseVersion.equals(ruleBaseVersion))) {
	            knowledgeBase = knowledgeBaseMap.get(ruleBaseVersion);
	            logger.info("DEBUG: Using cached knowledge base for version: " + ruleBaseVersion);
	        } else {
	            logger.info("DEBUG: Loading fresh knowledge base from: " + ruleBaseLocation);
	            knowledgeBase = oojdrew.getRuleBaset(ruleBaseLocation);

	            if (knowledgeBase == null || knowledgeBase.isEmpty()) {
	                logger.error("DEBUG: Knowledge base is null or empty!");
	                return;
	            }

	            logger.info("DEBUG: Loaded knowledge base length: " + knowledgeBase.length());
	            logger.debug("DEBUG: Knowledge base content preview: "
	                    + knowledgeBase.substring(0, Math.min(500, knowledgeBase.length())));

	            knowledgeBaseMap.put(ruleBaseVersion, knowledgeBase);
	            workingRuleBaseVersion = ruleBaseVersion;
	            ruleBaseLoaded = false;
	        }

	        if (!ruleBaseLoaded) {
	            logger.info("DEBUG: Parsing knowledge base...");
	            oojdrew.parseKnowledgeBase(knowledgeBase, false);
	            logger.info("DEBUG: Knowledge base parsed successfully");

	            getThisNodeType();
	            getThisServiceOperationParameters(serviceName, operationName);
	            
	            // ====================================================================
	            // T_IN SYNCHRONIZATION DETECTION
	            // Synchronization is determined purely by inputCollection.size()
	            // from canonical bindings - no workflow-type conditionals.
	            // - Join points have multiple canonicalBinding entries -> size > 1
	            // - Non-join points have single canonicalBinding entry -> size = 1
	            // ====================================================================
	            boolean needsInputSynchronization = (inputCollection.size() > 1);
	            
	            if (needsInputSynchronization) {
	                logger.info("T_IN-SYNC: Token " + phaseSequenceID + " needs input synchronization" +
	                           " (" + inputCollection.size() + " inputs required: " + inputCollection + ")");
	            }

	            // Canonical binding override - but NOT if we need input synchronization!
	            // Tokens needing synchronization must NOT have inputCollection overridden
	            if (inputCollection.size() == 1 && !needsInputSynchronization) {
	                String currentAttribute = attrMap.get("attributeName");

	                if (hasCanonicalBinding(serviceName, operationName, currentAttribute)) {
	                    logger.info("ORCHESTRATOR: Canonical binding found - overriding serviceName facts");
	                    inputCollection.clear();
	                    inputCollection.add("null");
	                    logger.info("ORCHESTRATOR: Overrode inputCollection for canonical binding");
	                }
	            }

	            getDecisionValue();
	            if (!"TerminateNode".equals(nodeType)) {
	                getNextService();
	            } else {
	                logger.info("DEBUG: Skipping getNextService for TerminateNode");
	            }
	            ruleBaseLoaded = true;
	        }
	    } catch (Exception e) {
	        logger.error("Error loading or parsing knowledge base: " + ruleBaseLocation, e);
	        return;
	    }
	    
	    // PETRI NET: Record token entering place
	  //  instrumentTokenEnter(phaseSequenceID, serviceName, nodeType, currentWorkflowStartTime, bufferSizeAtDequeue);
	    instrumentTokenEnter(phaseSequenceID, serviceName, nodeType, currentWorkflowStartTime, bufferSizeAtDequeue, (inputCollection.size() > 1));    // ====================================================================
	    // ====================================================================
	    // T_IN SYNCHRONIZATION ANALYSIS
	    // AGNOSTIC: Synchronization is determined purely by inputCollection.size()
	    // from canonical bindings - same logic for all workflow types.
	    // ====================================================================
	    boolean needsInputSynchronization = (inputCollection.size() > 1);

	    logger.info("DEBUG: About to check inputCollection.isEmpty() - size: " + inputCollection.size());
	    logger.info("DEBUG: inputCollection contents: " + inputCollection);
	    logger.info("DEBUG: nodeType: " + nodeType);
	    logger.info("DEBUG: needsInputSynchronization: " + needsInputSynchronization);

	    if (inputCollection.isEmpty()) {
	    	if ("TerminateNode".equals(nodeType)) {
	            logger.info("ORCHESTRATOR: TerminateNode " + serviceName + ":" + operationName + " - no routing needed");
	            sequenceID = joinID;
	            inputArgs.clear();
	            String attributeValue = attrMap.get("attributeValue");
	            inputArgs.add(attributeValue);
	            processControlNode();
	            return;
	        } else {
	            System.err.println("Error: Rule Collection for service:operation " + serviceName + ":" + operationName
	                    + " is empty.  Rejecting as wrong service name.");
	            return;
	        }
	    }
	    logger.info("DEBUG: Past inputCollection check, about to enter JOIN processing");
	    logger.info("DEBUG: inputCollection.get(0): " + (inputCollection.isEmpty() ? "EMPTY" : inputCollection.get(0)));

	    /* JOIN PROCESSING - Using proper sequence ID priority */
	    /* JOIN PROCESSING - Strict separation by NodeType with clear validation */
	    try {
	        inputArgs.clear();
	        
	        // ========================================================================
	        // CASE 1: NULL INPUT - Service needs NO workflow data (trigger token only)
	        // ========================================================================
	        if (inputCollection.get(0).equals("null")) {
	            // Service requires no workflow data attributes
	            // Receives only trigger/configuration token (e.g., version, time range)
	            // Examples: PerformanceCollectorService, InitializationService
	            
	            sequenceID = joinID;
	            inputArgs.clear();
	            String attributeValue = attrMap.get("attributeValue");
	            inputArgs.add(attributeValue);

	            logger.info("ORCHESTRATOR: Zero-input service " + serviceName + ":" + operationName + 
	                       " executing with trigger token (no workflow data required)");
	            
	            processControlNode();
	            safeCleanupJoin(joinID);
	            
	            logger.debug("ORCHESTRATOR: Service " + serviceName + " sequence ID: " + sequenceID + " complete.");
	        }
	        
	        // ========================================================================
	        // CASE 2: ANYOF SEMANTICS - Service accepts ANY ONE of multiple attributes
	        // ========================================================================
	        else if (inputCollection.get(0).equals("anyof")) {
	            // Service can execute with any one of several possible input attributes
	            // Used for monitoring/sink services that accept multiple data types
	            // Examples: MonitorService.acknowledgeTokenArrival
	            
	            sequenceID = joinID;
	            inputArgs.clear();
	            String attributeValue = attrMap.get("attributeValue");
	            inputArgs.add(attributeValue);

	            logger.info("ORCHESTRATOR: ANYOF service " + serviceName + ":" + operationName
	                    + " executing with attribute '" + payloadAttributeName + "' (sequence ID: " + sequenceID + ")");
	            
	            processControlNode();
	            safeCleanupJoin(joinID);
	            
	            logger.debug("ORCHESTRATOR: Service " + serviceName + " sequence ID: " + sequenceID + " complete.");
	        }
	        
	        // ========================================================================
	        // CASE 3: SINGLE INPUT NODE - Exactly 1 workflow data attribute required
	        // Handles: EdgeNode, TerminateNode, GatewayNode (single input)
	        // TerminateNode describes OUTPUT behavior (workflow ends), not INPUT behavior
	        // GatewayNode with single input processes input, then does decision routing on OUTPUT
	        // ========================================================================
	        else if ("EdgeNode".equals(nodeType) || "TerminateNode".equals(nodeType) || 
	                 ("GatewayNode".equals(nodeType) && inputCollection.size() == 1)) {
	            logger.debug("ORCHESTRATOR: Processing " + nodeType + " " + serviceName + ":" + operationName);
	            
	            // STRICT VALIDATION: EdgeNode must have exactly 1 input
	            if (inputCollection.size() != 1) {
	                String error = String.format(
	                    "WORKFLOW DEFINITION ERROR: EdgeNode %s:%s has %d inputs in inputCollection. " +
	                    "EdgeNode must have exactly 1 input.\n" +
	                    "InputCollection: %s\n" +
	                    "Fix: Correct the canonical binding rules for this service.",
	                    serviceName, operationName, inputCollection.size(), inputCollection
	                );
	                logger.error(error);
	                logger.error("SKIPPING EVENT - Token will time out naturally");
	                
	                // Clean up and skip this event
	                safeCleanupJoin(joinID);
	                return;
	            }
	            
	            // STRICT VALIDATION: EdgeNode should not receive multiple concurrent attributes
	            ConcurrentSkipListMap<String, String> argVal = argValPriorityMap.get(joinID);
	            if (argVal != null && argVal.size() > 1) {
	                String error = String.format(
	                    "WORKFLOW DEFINITION ERROR: EdgeNode %s:%s received %d concurrent attributes: %s\n" +
	                    "EdgeNode accepts only 1 input at a time.\n" +
	                    "This indicates multiple fork paths are incorrectly converging at an EdgeNode.\n" +
	                    "Fix: Either use a JoinNode if convergence is intended, or fix the fork routing.",
	                    serviceName, operationName, argVal.size(), argVal.keySet()
	                );
	                logger.error(error);
	                logger.error("SKIPPING EVENT - Token will time out naturally");
	                
	                // Clean up and skip this event
	                safeCleanupJoin(joinID);
	                return;
	            }
	            
	            // STRICT VALIDATION: Verify correct attribute was received
	            String requiredAttribute = inputCollection.get(0);
	            String receivedAttribute = payloadAttributeName;
	            
	            if (!requiredAttribute.equals(receivedAttribute)) {
	                String error = String.format(
	                    "WORKFLOW DEFINITION ERROR: EdgeNode %s:%s expects attribute '%s' but received '%s'\n" +
	                    "This indicates incorrect routing in the workflow.\n" +
	                    "Fix: Check that upstream services are publishing the correct attribute.",
	                    serviceName, operationName, requiredAttribute, receivedAttribute
	                );
	                logger.error(error);
	                logger.error("SKIPPING EVENT - Token will time out naturally");
	                
	                // Clean up and skip this event
	                safeCleanupJoin(joinID);
	                return;
	            }
	            
	            // All validations passed - execute immediately with single input
	            sequenceID = joinID;
	            inputArgs.clear();
	            String attributeValue = attrMap.get("attributeValue");
	            inputArgs.add(attributeValue);
	            
	            logger.info("ORCHESTRATOR: " + nodeType + " " + serviceName + ":" + operationName + 
	                       " executing immediately with single input '" + requiredAttribute + 
	                       "' (sequence ID: " + sequenceID + ")");
	            
	            processControlNode();
	            safeCleanupJoin(joinID);
	            
	            logger.debug("ORCHESTRATOR: " + nodeType + " " + serviceName + " sequence ID: " + sequenceID + " complete.");
	        }
	        
	        // ========================================================================
	        // CASE 4: INPUT SYNCHRONIZATION - Wait for multiple inputs before firing
	        // Synchronization is determined purely
	        // by inputCollection.size() from canonical bindings.
	        // NOTE: nodeType is preserved for T_OUT routing after synchronization
	        // ========================================================================
	        else if (needsInputSynchronization) {
	            logger.debug("ORCHESTRATOR: Processing input synchronization for " + serviceName + ":" + operationName);
	            
	            // Expected inputs comes directly from canonical bindings
	            int expectedInputs = inputCollection.size();

	            logger.info("ORCHESTRATOR: Input sync " + serviceName + ":" + operationName +
	                        " waiting for " + expectedInputs + " inputs (from canonical binding: " + inputCollection + ")");

	            
	            // STRICT VALIDATION: Synchronization requires multiple inputs
	            if (expectedInputs < 2) {
	                String error = String.format(
	                    "WORKFLOW DEFINITION ERROR: %s:%s has only %d expected input(s).\n" +
	                    "Input synchronization requires 2 or more inputs.\n" +
	                    "Fix: Check canonical bindings for this service.",
	                    serviceName, operationName, expectedInputs
	                );
	                logger.error(error);
	                logger.error("SKIPPING EVENT - Token will time out naturally");
	                
	                // Clean up and skip this event
	                safeCleanupJoin(joinID);
	                return;
	            }
            
	            // FIX: Store the expected count PER JOIN in the map, not just as instance variable
	            // This ensures each join uses its OWN expected count, not the current token's
	            if (expectedInputs > 0) {
	                joinExpectedCounts.putIfAbsent(joinID, expectedInputs);
	                logger.debug("JOIN-COUNT: Stored expectedCount=" + expectedInputs + " for joinID=" + joinID);
	            }
	            
	            logger.info("ORCHESTRATOR: Input sync " + serviceName + ":" + operationName + 
	                       " waiting for " + expectedInputs + " inputs: " + inputCollection);
	            
	            keyRemovalCollection.clear();

	            // Track this fork's contribution with workflow timing
	            int currentForkNumber = phaseSequenceID;
	            
	            joinContributions.putIfAbsent(joinID, new ArrayList<>());
	            List<JoinContribution> contributions = joinContributions.get(joinID);
	            
	            JoinContribution contribution = contributions.stream()
	                .filter(c -> c.forkNumber == currentForkNumber)
	                .findFirst()
	                .orElseGet(() -> {
	                    JoinContribution newContrib = new JoinContribution(currentForkNumber, currentWorkflowStartTime);
	                    contributions.add(newContrib);
	                    logger.debug("TRACK: Fork " + currentForkNumber + " workflowStartTime=" + currentWorkflowStartTime);
	                    return newContrib;
	                });
	            
	            contribution.attributes.put(payloadAttributeName, payloadAttributeValue);

	            // Process joins based on configuration (OPTIMIZED or SEQUENTIAL mode)
	            List<Integer> sortedKeys = argValPriorityMap.keySet().stream().sorted().collect(Collectors.toList());

	            if (enableCompletedJoinPriority) {
	                // OPTIMIZED MODE: Process any complete join immediately
	                Integer readyJoinKey = null;

	                logger.debug("ORCHESTRATOR [OPTIMIZED]: Checking " + sortedKeys.size()
	                        + " joins for completeness in sequence ID order");

	                if (logger.isDebugEnabled() && !sortedKeys.isEmpty()) {
	                    logger.debug("ORCHESTRATOR [OPTIMIZED]: Sequence ID sorted join order:");
	                    for (Integer key : sortedKeys.subList(0, Math.min(5, sortedKeys.size()))) {
	                        logger.debug("  - Sequence " + key);
	                    }
	                }

	                for (Integer key : sortedKeys) {
	                    if (isShutdown) {
	                        return;
	                    }

	                    boolean bypassJoinWindow = false;
	                    if (!bypassJoinWindow) {
	                        Long windowTime = sequenceIDJoinWindow.get(key);
	                        if (windowTime == null || AdjustJoinWindow(key, windowTime) == 0L) {
	                            keyRemovalCollection.add(key);
	                            logger.warn("Key " + key + " has expired - flagged for removal.");
	                            continue;
	                        }
	                    }

	                    if (isJoinComplete(key)) {
	                        readyJoinKey = key;
	                        logger.info("ORCHESTRATOR [OPTIMIZED]: Found COMPLETE join " + key
	                                + " - processing immediately");
	                        break;
	                    } else {
	                        logger.debug("ORCHESTRATOR [OPTIMIZED]: Join " + key + " is incomplete - checking next");
	                    }
	                }

	                if (readyJoinKey != null) {
	                    processCompleteJoinSafely(readyJoinKey);
	                } else {
	                    logger.debug("ORCHESTRATOR [OPTIMIZED]: No complete joins found in " + sortedKeys.size()
	                            + " pending joins - waiting for more inputs");
	                }

	            } else {
	                // SEQUENTIAL MODE: Process only lowest sequence ID when complete
	                logger.debug("ORCHESTRATOR [SEQUENTIAL]: Processing " + sortedKeys.size()
	                        + " joins in sequence ID order");

	                if (!sortedKeys.isEmpty()) {
	                    Integer lowestSequenceKey = sortedKeys.get(0);

	                    Long windowTime = sequenceIDJoinWindow.get(lowestSequenceKey);
	                    if (windowTime == null || AdjustJoinWindow(lowestSequenceKey, windowTime) == 0L) {
	                        keyRemovalCollection.add(lowestSequenceKey);
	                        logger.warn("ORCHESTRATOR [SEQUENTIAL]: Lowest sequence key " + lowestSequenceKey
	                                + " has expired - removing");
	                    } else {
	                        if (isJoinComplete(lowestSequenceKey)) {
	                            logger.info("ORCHESTRATOR [SEQUENTIAL]: Lowest sequence join " + lowestSequenceKey
	                                    + " is complete - processing");
	                            processCompleteJoinSafely(lowestSequenceKey);
	                        } else {
	                            logger.debug("ORCHESTRATOR [SEQUENTIAL]: Lowest sequence join " + lowestSequenceKey
	                                    + " is incomplete - BLOCKING until complete");

	                            if (logger.isDebugEnabled()) {
	                                for (int i = 1; i < sortedKeys.size(); i++) {
	                                    Integer blockedKey = sortedKeys.get(i);
	                                    if (isJoinComplete(blockedKey)) {
	                                        logger.debug("ORCHESTRATOR [SEQUENTIAL]: [WARNING] Complete join " + blockedKey
	                                                + " is BLOCKED by incomplete lower sequence join "
	                                                + lowestSequenceKey);
	                                    }
	                                }
	                            }
	                        }
	                    }
	                }
	            }

	            // Cleanup expired joins
	            safeCleanupExpiredJoins();

	            // Log current join status for debugging
	            if (logger.isDebugEnabled() && !argValPriorityMap.isEmpty()) {
	                logCurrentJoinStatus();
	            }
	        }
	        
	        // ========================================================================
	        // CASE 5: INVALID NODE TYPE - Not supported for input coordination
	        // ========================================================================
	        else {
	            String error = String.format(
	                "WORKFLOW DEFINITION ERROR: Service %s:%s has nodeType '%s' which is not valid for input coordination.\n" +
	                "Valid types for data flow: EdgeNode (1 input), JoinNode (2+ inputs)\n" +
	                "Note: ForkNode, DecisionNode are for OUTPUT routing, not INPUT coordination.\n" +
	                "InputCollection: %s\n" +
	                "Fix: Check the workflow DOT file and ensure correct node type is assigned.",
	                serviceName, operationName, nodeType, inputCollection
	            );
	            logger.error(error);
	            logger.error("SKIPPING EVENT - Token will time out naturally");
	            
	            // Clean up and skip this event
	            safeCleanupJoin(joinID);
	            return;
	        }

	    } catch (Exception e) {
	        // Keep the catch block for unexpected errors
	        logger.error("Unexpected error processing " + serviceName + ":" + operationName + 
	                    " (nodeType: " + nodeType + ", inputs: " + inputCollection + ")", e);
	        logger.error("SKIPPING EVENT - Token will time out naturally");
	        
	        try {
	            safeCleanupJoin(joinID);
	        } catch (Exception cleanupError) {
	            logger.error("Error during cleanup: " + cleanupError.getMessage());
	        }
	        return;  // Don't throw, just skip
	    }
	}
	
	/**
	 * Thread-safe method to check if a join is complete
	 * AGNOSTIC: Uses per-join expected count from joinExpectedCounts map
	 * which is populated from inputCollection.size() (canonical bindings)
	 */
	private boolean isJoinComplete(Integer key) {
		ConcurrentSkipListMap<String, String> argVal = argValPriorityMap.get(key);
		if (argVal == null) {
			return false;
		}

		int currentSize = argVal.size();
		
		// Look up the expected count for THIS SPECIFIC JOIN from the map
		// Do NOT use instance variables (inputCollection) as fallback
		// because they belong to the currently processing token, not the join being checked
		Integer storedExpectedCount = joinExpectedCounts.get(key);
		
		if (storedExpectedCount == null || storedExpectedCount <= 0) {
			// No expected count stored for this join yet - cannot determine completeness
			// This join's first token hasn't been fully processed yet
			logger.debug("Join " + key + " has no stored expectedCount yet - cannot check completeness (currentSize=" + currentSize + ")");
			return false;
		}
		
		int requiredSize = storedExpectedCount;
		logger.debug("Join " + key + " using stored expectedCount=" + storedExpectedCount + 
		            ", currentSize=" + currentSize);
		
		// Count-based completion check (from canonical bindings)
		if (currentSize >= requiredSize) {
			logger.debug("Join " + key + " is COMPLETE: " + currentSize + " >= " + requiredSize);
			return true;
		}

		return false;
	}


	/**
	 * Thread-safe method to process a complete join
	 * Preserves the lowest fork number AND workflowStartTime from all participating paths
	 * 
	 * AGNOSTIC: Collects inputs by canonical binding names from inputCollection
	 * Works for all workflow types - purely binding-driven
	 */
	private void processCompleteJoinSafely(Integer joinKey) {
	    // Get the join data atomically
	    ConcurrentSkipListMap<String, String> argVal = argValPriorityMap.get(joinKey);
	    if (argVal == null) {
	        logger.warn("ORCHESTRATOR: Join " + joinKey + " was removed before processing");
	        return;
	    }

	    // Verify join window is still valid
	    Long windowTime = sequenceIDJoinWindow.get(joinKey);
	    if (windowTime == null || windowTime <= 0) {
	        logger.warn("ORCHESTRATOR: Join " + joinKey + " expired during processing");
	        safeCleanupJoin(joinKey);
	        return;
	    }

	    // Verify join is still complete
	    if (!isJoinComplete(joinKey)) {
	        logger.warn("ORCHESTRATOR: Join " + joinKey + " became incomplete during processing");
	        return;
	    }

	    // Mark for cleanup
	    keyRemovalCollection.add(joinKey);

	    // Populate input arguments
	    // AGNOSTIC: Use canonical binding names directly from inputCollection
	    // The keys in argVal match the canonical bindings exactly
	    inputArgs.clear();
	    for (String inputParam : inputCollection) {
	        String value = argVal.get(inputParam);
	        if (value == null) {
	            logger.error("ORCHESTRATOR: Missing input parameter '" + inputParam + "' for join " + joinKey +
	                        " (available: " + argVal.keySet() + ")");
	            return;
	        }
	        inputArgs.add(value);
	        logger.debug("ORCHESTRATOR: Added argument '" + inputParam + "' to inputArgs");
	    }
	    logger.info("ORCHESTRATOR: Collected " + inputArgs.size() + " arguments for join " + joinKey + 
	               " (inputs: " + inputCollection + ")");

	    // Find the lowest fork number AND preserve its workflowStartTime
	    List<JoinContribution> contributions = joinContributions.get(joinKey);
	    int lowestForkNumber = joinKey;  // Default to base if no contributions tracked
	    long preservedWorkflowStartTime = currentWorkflowStartTime;  // Default to current
	    
	    if (contributions != null && !contributions.isEmpty()) {
	        // Find the contribution with the lowest fork number
	        JoinContribution lowestContribution = contributions.stream()
	            .min(Comparator.comparingInt(c -> c.forkNumber))
	            .orElse(null);
	            
	        if (lowestContribution != null) {
	            lowestForkNumber = lowestContribution.forkNumber;
	            preservedWorkflowStartTime = lowestContribution.workflowStartTime;
	            
	            logger.info("ORCHESTRATOR: Join " + joinKey + " complete - participating forks: " + 
	                       contributions.stream()
	                           .map(c -> "fork=" + c.forkNumber + ",wfStart=" + c.workflowStartTime)
	                           .collect(Collectors.toList()));
	        }
	    }

	    logger.info("ORCHESTRATOR: Join " + joinKey + " complete with " + inputArgs.size() + " inputs for "
	            + serviceName + "." + operationName);
	    
	    // After JOIN completes, continue with the LOWEST child token ID
	    // All other participants are consumed - the lowest child survives
	    int continuingTokenId = lowestForkNumber;
	    
	    logger.info("JOIN-CONTINUE: Token " + continuingTokenId + " continues after JOIN at " + serviceName);
	    logger.info("ORCHESTRATOR: Preserving workflowStartTime: " + preservedWorkflowStartTime);
	    
	    // Override currentWorkflowStartTime with the preserved value
	    currentWorkflowStartTime = preservedWorkflowStartTime;
	    
	    // Use the lowest child token ID for continuation
	    sequenceID = continuingTokenId;
	    headerMap.put("sequenceId", Integer.toString(continuingTokenId));
	    
	    // Record join completion - continuing token ENTERS, others CONSUMED
	    List<Integer> participantTokenIds = new ArrayList<>();
	    if (contributions != null) {
	        for (JoinContribution contrib : contributions) {
	            participantTokenIds.add(contrib.forkNumber);
	        }
	    }
	    instrumentJoinComplete(continuingTokenId, this.serviceName, participantTokenIds, currentWorkflowStartTime);
	    
	    processControlNode();

	    logger.info("ORCHESTRATOR: Service " + serviceName + " sequence ID: " + sequenceID + " complete.");
	}
	
	/**
	 * Thread-safe cleanup of a single join
	 */
	private void safeCleanupJoin(Integer joinKey) {
	    argValPriorityMap.remove(joinKey);
	    sequenceIDJoinWindow.remove(joinKey);
	    joinContributions.remove(joinKey);  // Clean up fork tracking
	    joinExpectedCounts.remove(joinKey);  // FIX: Clean up expected count for this join
	    logger.debug("ORCHESTRATOR: Cleaned up join " + joinKey);
	}

	/**
	 * Thread-safe cleanup of expired joins
	 */
	private void safeCleanupExpiredJoins() {
		for (Integer rkey : keyRemovalCollection) {
			safeCleanupJoin(rkey);
		}
		keyRemovalCollection.clear();
	}

	/**
	 * Thread-safe logging of current join status
	 */
	private void logCurrentJoinStatus() {
		logger.debug("ORCHESTRATOR: Current join status (mode="
				+ (enableCompletedJoinPriority ? "OPTIMIZED" : "SEQUENTIAL") + "):");

		// Create snapshot of current state
		List<Integer> statusKeys = argValPriorityMap.keySet().stream().sorted().collect(Collectors.toList());

		for (Integer key : statusKeys) {
			ConcurrentSkipListMap<String, String> argVal = argValPriorityMap.get(key);
			int currentInputs = argVal != null ? argVal.size() : 0;
			int requiredInputs = inputCollection.size();
			String status = (currentInputs == requiredInputs) ? "READY" : "WAITING";
			logger.debug("  Join " + key + ": " + currentInputs + "/" + requiredInputs + " inputs [" + status + "]");
		}
	}

	

	private void processCompleteJoin(Integer joinKey) {
		ConcurrentSkipListMap<String, String> argVal = argValPriorityMap.get(joinKey);

		keyRemovalCollection.add(joinKey);

		if (sequenceIDJoinWindow.get(joinKey) > 0) {
			inputArgs.clear();
			for (int j = 0; j < inputCollection.size(); j++) {
				inputArgs.add(argVal.get(inputCollection.get(j)));
			}

			logger.info("ORCHESTRATOR: Join " + joinKey + " complete with " + inputArgs.size() + " inputs for "
					+ serviceName + "." + operationName);

			sequenceID = joinKey;
			headerMap.put("sequenceId", joinKey.toString());
			processControlNode();

			logger.info("ORCHESTRATOR: Service " + serviceName + " sequence ID: " + sequenceID + " complete.");
		} else {
			logger.warn("ORCHESTRATOR: Join " + joinKey + " expired during processing");
		}
	}

	private Long AdjustJoinWindow(Integer keyID, Long notAfter) {
		long ctime = System.currentTimeMillis();
		if (notAfter == null) {
			logger.debug("ServiceThread : notAfter is null");
			return 0L;
		}
		if (ctime < notAfter)
			return notAfter;
		else
			return 0L;
	}

	private void processControlNode() {
		if (nodeType == null) {
			logger.warn("ServiceHandler: Invalid node type");
		} else {
			try {
				logger.info("ORCHESTRATOR: Processing " + nodeType + " for " + serviceName + "." + operationName);

				// Capture invocation time before service execution
				serviceInvocationTime = System.currentTimeMillis();

				switch (nodeType) {
				case "DecisionNode":
					nodeTypeProcessor.processDecisionType(serviceName, operationName, inputArgs);
					break;
				case "TerminateNode":
					nodeTypeProcessor.processTerminateType(serviceName, operationName, inputArgs);
					break;
				case "XorNode":
					// XorNode - Conditional routing (picks path(s) based on decision value)
					nodeTypeProcessor.processXorType(serviceName, operationName, inputArgs);
					break;
				case "GatewayNode":
					// GatewayNode - Dynamic routing strategy based on service response
					// Service returns: "FORK:target1,target2" or "EDGE:target"
					nodeTypeProcessor.processGatewayType(serviceName, operationName, inputArgs);
					break;
				case "ForkNode":
					// ForkNode - Unconditional parallel split (ALL paths)
					nodeTypeProcessor.processForkType(serviceName, operationName, inputArgs);
					break;
				case "FeedFwdNode":
					nodeTypeProcessor.processFeedFwdNode(serviceName, operationName, inputArgs);
					break;
				case "EdgeNode":
					nodeTypeProcessor.processEdgeType(serviceName, operationName, inputArgs);
					break;
				case "JoinNode":
					nodeTypeProcessor.processJoinType(serviceName, operationName, inputArgs);
					break;
				case "MergeNode":
					// MergeNode is functionally identical to EdgeNode (single input XOR-MERGE)
					nodeTypeProcessor.processEdgeType(serviceName, operationName, inputArgs);
					break;
				case "MonitorNode":
					nodeTypeProcessor.processMonitorNodeType(sequenceID);
					break;
				case "Expired":
					nodeTypeProcessor.processExpiredType(serviceName, operationName, inputArgs);
					break;
				default:
					logger.warn("Unknown node type: " + nodeType);
					break;
				}

				// Capture publish time after service execution
				servicePublishTime = System.currentTimeMillis();

				logger.error("monitorIncomingEvents: " + monitorIncomingEvents);
				if (monitorIncomingEvents) {
					putServicePerformanceData(sequenceID);
				}

			} catch (Exception e) {
				logger.error("Error processing control node of type: " + nodeType, e);
			}
		}
	}


	void setAttributeValidity(String returnAttrName, String returnAttrValue) {
		if (returnAttrName != null) {
			attrMap.put("attributeName", returnAttrName);
			attrMap.put("attributeValue", returnAttrValue);

			JSONObject parsedValue = jsonLibrary.parseString(returnAttrValue);
			if (parsedValue != null) {
				jsonAttributes.appendNestedObject(returnAttrName, parsedValue);
			} else {
				jsonAttributes.put(returnAttrName, returnAttrValue);
			}
		}
		attrMap.put("status", "success");
	}

	/**
	 * Derive attribute name for routing based on DESTINATION's canonical binding.
	 * 
	 * - Destination needs 1 input: use "token"
	 * - Destination needs N inputs (Join): 
	 *   1. Query canonical binding to get slot names (in query return order)
	 *   2. Map branch number to slot: branch 1 → slot[0], branch 2 → slot[1], etc.
	 * 
	 * @param tokenId The token ID (contains encoded branch number)
	 * @param explicitBranch Explicit branch from FORK loop (1, 2, ...) or 0 to derive from tokenId
	 * @param destService Destination service name
	 * @param destOperation Destination operation name
	 * @return The attribute name to use
	 */
	/**
	 * Derive attribute name for routing based on canonical binding.
	 * 
	 * For EXTERNAL routing: use this service's RETURN attribute (returnAttributeName)
	 * For SELF-FEEDBACK: use this service's INPUT attribute (from canonical binding)
	 * 
	 * FAIL LOUDLY if attribute cannot be determined - no silent defaults.
	 * 
	 * @param tokenId The token ID
	 * @param explicitBranch Explicit branch number (for logging)
	 * @param destService Destination service name
	 * @param destOperation Destination operation name
	 * @return The attribute name to use
	 * @throws IllegalStateException if attribute cannot be determined
	 */
	String deriveAttributeNameForRouting(int tokenId, int explicitBranch, String destService, String destOperation) {
		// Self-feedback - publish what I expect as input (from my canonical binding)
		if (destService.equals(serviceName)) {
			logger.debug("ROUTING-DEBUG: Self-feedback detected, querying binding for operation='" + operationName + "'");
			String inputAttr = queryInputAttributeFromBinding(operationName);
			if (inputAttr == null) {
				// FAIL LOUDLY - no silent defaults
				logger.error("ROUTING: FATAL - Self-feedback routing failed");
				logger.error("ROUTING: Service: " + serviceName + ", Operation: " + operationName);
				logger.error("ROUTING: Could not find input attribute in canonical binding");
				logger.error("ROUTING: Expected: canonicalBinding(" + operationName + ", returnAttr, inputAttr)");
				throw new IllegalStateException(
					"ROUTING: Self-feedback to " + destService + " failed - no input attribute found in canonical binding for operation '" + operationName + "'");
			}
			logger.debug("ROUTING: Self-feedback to " + destService + " -> '" + inputAttr + "'");
			return inputAttr;
		}
		
		// External routing - publish what my binding says I publish
		if (returnAttributeName != null && !returnAttributeName.equals("null")) {
			logger.debug("ROUTING: External to " + destService + " -> '" + returnAttributeName + "'");
			return returnAttributeName;
		}
		
		// FAIL LOUDLY - no silent defaults
		logger.error("ROUTING: FATAL - External routing failed");
		logger.error("ROUTING: Source service: " + serviceName + ", Operation: " + operationName);
		logger.error("ROUTING: Destination: " + destService + "." + destOperation);
		logger.error("ROUTING: returnAttributeName is null or 'null'");
		logger.error("ROUTING: Check serviceName predicate includes attribute parameter");
		throw new IllegalStateException(
			"ROUTING: Cannot route from " + serviceName + " to " + destService + " - returnAttributeName not configured. Check serviceName predicate deployment.");
	}
	
	/**
	 * Query canonical binding to get the input attribute for an operation.
	 * canonicalBinding(operation, returnAttr, inputAttr) -> returns inputAttr
	 */
	private String queryInputAttributeFromBinding(String operation) {
		try {
			String query = "<Query><Atom><Rel>canonicalBinding</Rel><Ind>" + operation + 
			               "</Ind><Var>returnAttr</Var><Var>inputAttr</Var></Atom></Query>";
			oojdrew.issueRuleMLQuery(query);
			
			logger.debug("ROUTING-QUERY: rowsReturned=" + oojdrew.rowsReturned + " for operation=" + operation);
			
			if (oojdrew.rowsReturned > 0) {
				for (int i = 0; i < oojdrew.rowsReturned; i++) {
					String key = oojdrew.rowData[i][0].toString();
					String value = oojdrew.rowData[i][1].toString();
					logger.debug("ROUTING-QUERY: row[" + i + "] key='" + key + "' value='" + value + "'");
					if ("?inputAttr".equals(key) && value != null && !"null".equals(value)) {
						return value;
					}
				}
			}
		} catch (Exception e) {
			logger.warn("ROUTING: Error querying canonical binding for input: " + e.getMessage());
		}
		return null;
	}
	
	/**
	 * Query canonical binding to get the RETURN attribute for an operation.
	 * 
	 * Canonical binding structure: canonicalBinding(operation, returnAttr, inputAttr)
	 * 
	 * For FORK routing: the source service sends its RETURN attribute,
	 * which matches the destination's INPUT attribute.
	 * 
	 * Example:
	 *   P1_Place: canonicalBinding(processToken, token, token_branch1)
	 *             canonicalBinding(processToken, token, token_branch2)
	 *   -> P1 accepts token_branch1/token_branch2, RETURNS "token"
	 *   
	 *   P2_Place: canonicalBinding(processToken, token_branch1, token)
	 *   -> P2 accepts "token", returns token_branch1
	 *   
	 *   When P1 forks to P2: P1 sends "token" (its return), P2 expects "token" (its input)
	 * 
	 * @param operation The operation name to query
	 * @return The return attribute name
	 * @throws IllegalStateException if return attribute cannot be determined
	 */
	String queryCanonicalReturnAttribute(String operation) {
		try {
			String query = "<Query><Atom><Rel>canonicalBinding</Rel><Ind>" + operation + 
			               "</Ind><Var>returnAttr</Var><Var>inputAttr</Var></Atom></Query>";
			oojdrew.issueRuleMLQuery(query);
			
			logger.debug("CANONICAL-RETURN: Querying return attribute for operation=" + operation + 
			            ", rowsReturned=" + oojdrew.rowsReturned);
			
			if (oojdrew.rowsReturned > 0) {
				for (int i = 0; i < oojdrew.rowsReturned; i++) {
					String key = oojdrew.rowData[i][0].toString();
					String value = oojdrew.rowData[i][1].toString();
					
					logger.debug("CANONICAL-RETURN: row[" + i + "] key='" + key + "' value='" + value + "'");
					
					if ("?returnAttr".equals(key) && value != null && !"null".equals(value)) {
						logger.info("CANONICAL-RETURN: Found return attribute '" + value + "' for operation=" + operation);
						return value;
					}
				}
			}
			
			// FAIL LOUDLY - no silent defaults
			logger.error("CANONICAL-RETURN: FATAL - No return attribute found in canonical binding");
			logger.error("CANONICAL-RETURN: Operation: " + operation);
			logger.error("CANONICAL-RETURN: Expected: canonicalBinding(" + operation + ", returnAttr, inputAttr)");
			logger.error("CANONICAL-RETURN: Check that canonical binding is deployed for this service");
			
			throw new IllegalStateException(
				"CANONICAL-RETURN: Cannot determine return attribute for operation '" + operation + "'. " +
				"Canonical binding missing or malformed. Check deployment.");
			
		} catch (IllegalStateException e) {
			// Re-throw our own exception
			throw e;
		} catch (Exception e) {
			logger.error("CANONICAL-RETURN: Exception querying canonical binding for operation=" + operation, e);
			throw new IllegalStateException(
				"CANONICAL-RETURN: Failed to query canonical binding for operation '" + operation + "': " + e.getMessage(), e);
		}
	}
	
	/**
	 * @deprecated Use queryCanonicalReturnAttribute() for fork routing.
	 * This method returns INPUT slots, not the RETURN attribute.
	 * Fork routing should use the source's RETURN attribute.
	 * 
	 * Query canonical binding to get list of input slot names for an operation.
	 * Returns slots in query return order.
	 */
	@Deprecated
	List<String> queryCanonicalBindingSlots(String operation) {
		List<String> slots = new ArrayList<>();
		try {
			String query = "<Query><Atom><Rel>canonicalBinding</Rel><Ind>" + operation + 
			               "</Ind><Var>returnAttr</Var><Var>inputSlot</Var></Atom></Query>";
			oojdrew.issueRuleMLQuery(query);
			
			boolean hasNext = true;
			while (hasNext && oojdrew.rowsReturned > 0) {
				for (int i = 0; i < oojdrew.rowsReturned; i++) {
					String key = oojdrew.rowData[i][0].toString();
					String value = oojdrew.rowData[i][1].toString();
					if ("?inputSlot".equals(key) && !"anyof".equals(value)) {
						if (!slots.contains(value)) {
							slots.add(value);
						}
					}
				}
				if (oojdrew.hasNext) {
					oojdrew.nextSolution();
				} else {
					hasNext = false;
				}
			}
		} catch (Exception e) {
			logger.warn("ROUTING: Error querying canonical binding for " + operation + ": " + e.getMessage());
		}
		return slots;
	}
	
	/**
	 * Convenience overload for non-fork routing.
	 */
	String deriveAttributeNameForRouting(int tokenId, String destService, String destOperation) {
		return deriveAttributeNameForRouting(tokenId, 0, destService, destOperation);
	}


	/**
	 * Collect all services for FORK - unconditional parallel routing
	 * 
	 * For FORK nodes, queries meetsCondition and returns ALL services,
	 * ignoring guard conditions. The NodeType determines routing behavior:
	 * - FORK: Routes to ALL services unconditionally
	 * - XOR/Gateway: Routes conditionally based on guard evaluation
	 */
	List<ServiceRoute> collectParallelServices() {
		List<ServiceRoute> services = new ArrayList<>();

		// Query for ALL meetsCondition predicates - FORK ignores the guard values
		String query = "<Query><Atom><Rel>meetsCondition</Rel>" + 
		               "<Var>service</Var><Var>operation</Var><Var>guardType</Var><Var>guardValue</Var></Atom></Query>";

		oojdrew.issueRuleMLQuery(query);

		boolean hasNext = true;
		while (hasNext) {
			ServiceRoute route = new ServiceRoute();

			for (int i = 0; i < oojdrew.rowsReturned; i++) {
				String key = oojdrew.rowData[i][0].toString();
				String value = oojdrew.rowData[i][1].toString();

				if ("?service".equals(key)) {
					route.serviceName = value;
				} else if ("?operation".equals(key)) {
					route.operationName = value;
				}
				// Ignore guardType and guardValue - FORK is unconditional
			}

			if (route.serviceName != null && route.operationName != null) {
				// Route what we're told to route - no deduplication
				// Multiple arrows to same service = multiple children
				services.add(route);
				logger.info("FORK: Collected service " + route.serviceName + "." + route.operationName + " (unconditional)");
			}

			hasNext = oojdrew.hasNext;
			if (hasNext) {
				oojdrew.nextSolution();
			}
		}

		if (services.isEmpty()) {
			logger.warn("FORK: No services configured in meetsCondition predicates");
		}

		return services;
	}

	/**
	 * Reload knowledge base for fork processing
	 */
	void reloadKnowledgeBase() {
		try {
			String ruleBaseLocation = basePath + "/RuleFolder." + ruleBaseVersion + "/" + operationName + "/Service.ruleml";

			// Get fresh knowledge base content instead of parsing file path directly
			String knowledgeBase = oojdrew.getRuleBaset(ruleBaseLocation);

			if (knowledgeBase != null && !knowledgeBase.isEmpty()) {
				oojdrew.parseKnowledgeBase(knowledgeBase, false);
				logger.debug("FORK: Reloaded knowledge base successfully");
			} else {
				logger.warn("FORK: Knowledge base is null or empty for location: " + ruleBaseLocation);
			}

		} catch (Exception e) {
			logger.error("FORK: Failed to reload knowledge base for operation: " + operationName, e);

			// Try to continue with existing parsed knowledge base
			logger.info("FORK: Continuing with existing parsed knowledge base");
		}
	}


	/**
	 * Handle timing data requests from MonitorService This method responds to
	 * requests for performance data from this JVM's services
	 */
	public String handleTimingDataRequest(String jsonRequest) {
		try {
			JSONObject request = jsonLibrary.parseString(jsonRequest);
			if (request == null) {
				return "{\"found\":false,\"error\":\"Invalid JSON request\"}";
			}

			long sequenceId = (Long) request.get("sequenceId");
			String requestedServiceName = (String) request.get("serviceName");
			String requestedOperation = (String) request.get("operation");

			logger.info("Handling timing data request for sequence " + sequenceId + " from service "
					+ requestedServiceName + "." + requestedOperation);

			// Query local service measurements database
			TreeMap<Integer, ArrayList<Object>> allMeasurements = serviceMeasures
					.readServiceMeasurements(calculateWorkflowBase(sequenceId));

			// Find the specific measurement
			for (Map.Entry<Integer, ArrayList<Object>> entry : allMeasurements.entrySet()) {
				ArrayList<Object> data = entry.getValue();

				if (data.size() >= 8) {
					long recordSequenceId = (Long) data.get(1);
					String serviceName = (String) data.get(2);
					String operation = (String) data.get(3);

					// Match the specific record
					if (recordSequenceId == sequenceId && serviceName.equals(requestedServiceName)
							&& operation.equals(requestedOperation)) {

						// Extract timing data
						long arrivalTime = (Long) data.get(4);
						long invocationTime = (Long) data.get(5);
						long publishTime = (Long) data.get(6);

						// Calculate metrics
						long queueTime = invocationTime - arrivalTime;
						long serviceTime = publishTime - invocationTime;
						long totalTime = publishTime - arrivalTime;

						// Build response
						JSONObject response = new JSONObject();
						response.put("found", true);
						response.put("sequenceId", sequenceId);
						response.put("serviceName", serviceName);
						response.put("operation", operation);
						response.put("queueTime", queueTime);
						response.put("serviceTime", serviceTime);
						response.put("totalTime", totalTime);
						response.put("arrivalTime", arrivalTime);
						response.put("invocationTime", invocationTime);
						response.put("publishTime", publishTime);

						logger.info("Found timing data for " + serviceName + " seq=" + sequenceId + " queue="
								+ queueTime + "ms service=" + serviceTime + "ms");

						return response.toJSONString();
					}
				}
			}

			logger.debug("No timing data found for sequence " + sequenceId + " service " + requestedServiceName + "."
					+ requestedOperation);

			return "{\"found\":false,\"sequenceId\":" + sequenceId + ",\"serviceName\":\"" + requestedServiceName
					+ "\"}";

		} catch (Exception e) {
			logger.error("Error handling timing data request: " + e.getMessage(), e);
			return "{\"found\":false,\"error\":\"" + e.getMessage() + "\"}";
		}
	}

	private String determineCondition(String runtimeValue, String ruleValue) {
		String cleanRuntime = cleanQuotes(runtimeValue);
		String cleanRule = cleanQuotes(ruleValue);

		if (cleanRuntime.equals(cleanRule)) {
			return "DECISION_EQUAL_TO";
		} else {
			return "DECISION_NOT_EQUAL";
		}
	}

	private List<ServiceRoute> collectMatchingServices(String condition) {
		List<ServiceRoute> services = new ArrayList<>();

		String query = "<Query><Atom><Rel>meetsCondition</Rel>" + "<Var>service</Var><Var>operation</Var><Ind>"
				+ condition + "</Ind></Atom></Query>";

		oojdrew.issueRuleMLQuery(query);

		boolean hasNext = true;
		while (hasNext) {
			ServiceRoute route = new ServiceRoute();

			for (int i = 0; i < oojdrew.rowsReturned; i++) {
				String key = oojdrew.rowData[i][0].toString();
				String value = oojdrew.rowData[i][1].toString();

				if ("?service".equals(key)) {
					route.serviceName = value;
				} else if ("?operation".equals(key)) {
					route.operationName = value;
				}
			}

			if (route.serviceName != null && route.operationName != null) {
				services.add(route);
				logger.info("FORK: Collected " + route.serviceName + "." + route.operationName);
			}

			hasNext = oojdrew.hasNext;
			if (hasNext) {
				oojdrew.nextSolution();
			}
		}

		return services;
	}

	private void routeToService(ServiceRoute route, String attributeValue) {
		if (checkAndRouteActiveService(route, attributeValue)) {
			return;
		}

		routeViaPublishes(route, attributeValue);
	}

	static class ServiceRoute {
		String serviceName;
		String operationName;
	}

	private boolean checkAndRouteActiveService(ServiceRoute route, String attributeValue) {
		try {
			String query = String.format(
					"<Query><Atom><Rel>activeService</Rel><Ind>%s</Ind><Ind>%s</Ind>"
							+ "<Var>channelId</Var><Var>port</Var></Atom></Query>",
					route.serviceName, route.operationName);

			oojdrew.issueRuleMLQuery(query);

			if (oojdrew.rowsReturned > 0) {
				String channelId = null;
				String port = null;

				for (int i = 0; i < oojdrew.rowsReturned; i++) {
					String key = String.valueOf(oojdrew.rowData[i][0]);
					String value = String.valueOf(oojdrew.rowData[i][1]);

					if ("?channelId".equals(key)) {
						channelId = value;
					} else if ("?port".equals(key)) {
						port = value;
					}
				}

				if (channelId != null && port != null) {
					String originalChannelId = channelId;
					String ipAddress = resolveChannelToIP(channelId);
					if (ipAddress != null) {
						serviceMap.put("serviceName", route.serviceName);
						serviceMap.put("operation", route.operationName);

						// PETRI NET: Record token exiting
						int outgoingTokenId = Integer.parseInt(headerMap.get("sequenceId"));
						instrumentTokenExit(outgoingTokenId, serviceName, route.serviceName, route.operationName, nodeType, null);

						publishToService(route.serviceName, route.operationName, ipAddress, port, originalChannelId);

						logger.info("FORK: Routed to " + route.serviceName + " via activeService on " + ipAddress + ":"
								+ port);
						return true;
					}
				}
			}
		} catch (Exception e) {
			logger.debug("FORK: No activeService entry for " + route.serviceName);
		}

		return false;
	}

	private void routeViaPublishes(ServiceRoute route, String attributeValue) {
		try {
			String query = String.format(
					"<Query><Atom><Rel>publishes</Rel><Ind>%s</Ind><Var>condition</Var>"
							+ "<Ind>%s</Ind><Var>channel</Var><Var>link</Var><Var>port</Var></Atom></Query>",
					route.serviceName, route.operationName);

			oojdrew.issueRuleMLQuery(query);

			if (oojdrew.rowsReturned > 0) {
				String channel = null;
				String port = null;

				for (int i = 0; i < oojdrew.rowsReturned; i++) {
					String key = oojdrew.rowData[i][0].toString();
					String value = oojdrew.rowData[i][1].toString();

					if ("?channel".equals(key)) {
						channel = value;
					} else if ("?port".equals(key)) {
						port = value;
					}
				}

				if (channel != null && port != null) {
					serviceMap.put("serviceName", route.serviceName);
					serviceMap.put("operation", route.operationName);

					// PETRI NET: Record token exiting
					int outgoingTokenId = Integer.parseInt(headerMap.get("sequenceId"));
					instrumentTokenExit(outgoingTokenId, serviceName, route.serviceName, route.operationName, nodeType, null);

					publishToService(route.serviceName, route.operationName, channel, port, channel);

					logger.info("FORK: Routed to " + route.serviceName + " via publishes on " + channel + ":" + port);
				}
			}
		} catch (Exception e) {
			logger.error("FORK: Failed to route to " + route.serviceName + ": " + e.getMessage());
		}
	}

	// =============================================================================
	// NO-RECORD ROUTING METHODS (For FORK - T_out already recorded once)
	// =============================================================================

	/**
	 * Route to service WITHOUT recording T_out transition
	 * Used by FORK after T_out has been recorded once for the parent token
	 */
	/**
	 * Backward-compatible overload for non-fork routing.
	 */
	void routeToServiceNoRecord(ServiceRoute route, String attributeValue) {
		routeToServiceNoRecord(route, attributeValue, 0, null);
	}
	
	/**
	 * Route to service without recording T_out (caller already recorded).
	 * 
	 * @param route The service route
	 * @param attributeValue The attribute value
	 * @param branchNumber The fork branch number (1, 2, ...) or 0 for non-fork
	 */
	void routeToServiceNoRecord(ServiceRoute route, String attributeValue, int branchNumber) {
		routeToServiceNoRecord(route, attributeValue, branchNumber, null);
	}
	
	/**
	 * Route to service without recording T_out, with explicit attribute name.
	 * 
	 * @param route The service route
	 * @param attributeValue The attribute value  
	 * @param branchNumber The fork branch number (for logging only)
	 * @param explicitAttrName If non-null, use this attribute name instead of deriving it
	 */
	void routeToServiceNoRecord(ServiceRoute route, String attributeValue, int branchNumber, String explicitAttrName) {
		if (checkAndRouteActiveServiceNoRecord(route, attributeValue, branchNumber, explicitAttrName)) {
			return;
		}
		routeViaPublishesNoRecord(route, attributeValue, branchNumber, explicitAttrName);
	}

	private boolean checkAndRouteActiveServiceNoRecord(ServiceRoute route, String attributeValue, int branchNumber) {
		return checkAndRouteActiveServiceNoRecord(route, attributeValue, branchNumber, null);
	}
	
	private boolean checkAndRouteActiveServiceNoRecord(ServiceRoute route, String attributeValue, int branchNumber, String explicitAttrName) {
		try {
			String query = String.format(
					"<Query><Atom><Rel>activeService</Rel><Ind>%s</Ind><Ind>%s</Ind>"
							+ "<Var>channelId</Var><Var>port</Var></Atom></Query>",
					route.serviceName, route.operationName);

			oojdrew.issueRuleMLQuery(query);

			if (oojdrew.rowsReturned > 0) {
				String channelId = null;
				String port = null;

				for (int i = 0; i < oojdrew.rowsReturned; i++) {
					String key = String.valueOf(oojdrew.rowData[i][0]);
					String value = String.valueOf(oojdrew.rowData[i][1]);

					if ("?channelId".equals(key)) {
						channelId = value;
					} else if ("?port".equals(key)) {
						port = value;
					}
				}

				if (channelId != null && port != null) {
					String originalChannelId = channelId;
					String ipAddress = resolveChannelToIP(channelId);
					if (ipAddress != null) {
						serviceMap.put("serviceName", route.serviceName);
						serviceMap.put("operation", route.operationName);

						// NOTE: No instrumentTokenExit - caller has already recorded

						publishToService(route.serviceName, route.operationName, ipAddress, port, originalChannelId, branchNumber, explicitAttrName);

						logger.info("FORK: Routed to " + route.serviceName + " via activeService on " + ipAddress + ":"
								+ port + " (no T_out record)");
						return true;
					}
				}
			}
		} catch (Exception e) {
			logger.debug("FORK: No activeService entry for " + route.serviceName);
		}

		return false;
	}

	private void routeViaPublishesNoRecord(ServiceRoute route, String attributeValue, int branchNumber) {
		routeViaPublishesNoRecord(route, attributeValue, branchNumber, null);
	}
	
	private void routeViaPublishesNoRecord(ServiceRoute route, String attributeValue, int branchNumber, String explicitAttrName) {
		try {
			String query = String.format(
					"<Query><Atom><Rel>publishes</Rel><Ind>%s</Ind><Var>condition</Var>"
							+ "<Ind>%s</Ind><Var>channel</Var><Var>link</Var><Var>port</Var></Atom></Query>",
					route.serviceName, route.operationName);

			oojdrew.issueRuleMLQuery(query);

			if (oojdrew.rowsReturned > 0) {
				String channel = null;
				String port = null;

				for (int i = 0; i < oojdrew.rowsReturned; i++) {
					String key = oojdrew.rowData[i][0].toString();
					String value = oojdrew.rowData[i][1].toString();

					if ("?channel".equals(key)) {
						channel = value;
					} else if ("?port".equals(key)) {
						port = value;
					}
				}

				if (channel != null && port != null) {
					serviceMap.put("serviceName", route.serviceName);
					serviceMap.put("operation", route.operationName);

					// NOTE: No instrumentTokenExit - caller has already recorded

					publishToService(route.serviceName, route.operationName, channel, port, channel, branchNumber, explicitAttrName);

					logger.info("FORK: Routed to " + route.serviceName + " via publishes on " + channel + ":" + port + " (no T_out record)");
				}
			}
		} catch (Exception e) {
			logger.error("FORK: Failed to route to " + route.serviceName + ": " + e.getMessage());
		}
	}

	/**
	 * Core method to publish a message to a service.
	 * Handles payload preparation, agnostic routing (attribute mapping), and event publishing.
	 */
	private void publishToService(String nextServiceName, String nextOperationName, 
	                               String channel, String port, String originalChannelId) {
		publishToService(nextServiceName, nextOperationName, channel, port, originalChannelId, 0);
	}
	
	/**
	 * Core method to publish a message to a service with explicit branch number.
	 * 
	 * @param nextServiceName Destination service name
	 * @param nextOperationName Destination operation name
	 * @param channel Channel/IP address
	 * @param port Port number
	 * @param originalChannelId Original channel ID for tracking
	 * @param branchNumber Fork branch number (1, 2, ...) or 0 for non-fork
	 */
	private void publishToService(String nextServiceName, String nextOperationName, 
	                               String channel, String port, String originalChannelId, int branchNumber) {
		publishToService(nextServiceName, nextOperationName, channel, port, originalChannelId, branchNumber, null);
	}
	
	/**
	 * Core method to publish a message to a service with explicit attribute name.
	 * 
	 * @param nextServiceName Destination service name
	 * @param nextOperationName Destination operation name
	 * @param channel Channel/IP address
	 * @param port Port number
	 * @param originalChannelId Original channel ID for tracking
	 * @param branchNumber Fork branch number (for logging only)
	 * @param explicitAttrName If non-null, use this attribute name instead of deriving it
	 */
	private void publishToService(String nextServiceName, String nextOperationName, 
	                               String channel, String port, String originalChannelId, int branchNumber, String explicitAttrName) {
		// Derive attribute name based on destination's canonical binding
		String seqIdStr = headerMap.get("sequenceId");
		if (seqIdStr == null) {
			throw new IllegalStateException("ROUTING ERROR: headerMap.sequenceId is null");
		}
		int tokenId = Integer.parseInt(seqIdStr);
		
		// Use explicit attribute name if provided, otherwise derive it
		String mappedAttrName;
		if (explicitAttrName != null) {
			mappedAttrName = explicitAttrName;
			logger.info("ROUTING: Token " + tokenId + " -> '" + mappedAttrName + "' (explicit) to " + nextServiceName + "." + nextOperationName);
		} else {
			mappedAttrName = deriveAttributeNameForRouting(tokenId, branchNumber, nextServiceName, nextOperationName);
			logger.info("ROUTING: Token " + tokenId + " -> '" + mappedAttrName + "'" +
					   (branchNumber > 0 ? " (fork branch " + branchNumber + ")" : "") +
					   " to " + nextServiceName + "." + nextOperationName);
		}
		attrMap.put("attributeName", mappedAttrName);
		
		outgoingXMLPayLoad = xph.modifyMultipleXMLItems(outgoingXMLPayLoad, "//header/*", headerMap);
		outgoingXMLPayLoad = xph.modifyMultipleXMLItems(outgoingXMLPayLoad, "//service/*", serviceMap);
		outgoingXMLPayLoad = xph.modifyMultipleXMLItems(outgoingXMLPayLoad, "//joinAttribute/*", attrMap);
		outgoingXMLPayLoad = xph.modifyMultipleXMLItems(outgoingXMLPayLoad, "//monitorData/*", monitorDataMap);

		boolean published = eventPublisher.publishServiceEvent(nextServiceName, nextOperationName, 
		        outgoingXMLPayLoad, channel, port, ruleBaseVersion, originalChannelId);

		if (!published) {
			logger.error("PUBLISH FAILED: " + nextServiceName + "." + nextOperationName);
		}
	}

	/**
	 * Update the sequenceId in the JSON payload body to match the header sequenceId.
	 * This ensures consistency between XML header and JSON body for forked tokens.
	 * 
	 * @param newSequenceId The new sequence ID to set in the payload
	 */
	void updateSequenceIdInPayload(int newSequenceId) {
		try {
			// Find and update tokenId in JSON payload
			String tokenIdPattern = "\"tokenId\":\"\\d+\"";
			String tokenIdReplacement = "\"tokenId\":\"" + newSequenceId + "\"";
			outgoingXMLPayLoad = outgoingXMLPayLoad.replaceAll(tokenIdPattern, tokenIdReplacement);
			
			// Find and update sequenceId in JSON payload
			String seqIdPattern = "\"sequenceId\":\"\\d+\"";
			String seqIdReplacement = "\"sequenceId\":\"" + newSequenceId + "\"";
			outgoingXMLPayLoad = outgoingXMLPayLoad.replaceAll(seqIdPattern, seqIdReplacement);
			
			logger.debug("FORK: Updated payload sequenceId/tokenId to " + newSequenceId);
		} catch (Exception e) {
			logger.warn("FORK: Failed to update sequenceId in payload: " + e.getMessage());
		}
	}

	private String resolveChannelToIP(String channelId) {
		if (channelId == null || !channelId.startsWith("ip")) {
			return null;
		}

		try {
			String query = String.format(
					"<Query><Atom><Rel>boundChannel</Rel><Ind>%s</Ind><Var>address</Var></Atom></Query>", channelId);

			oojdrew.issueRuleMLQuery(query);

			if (oojdrew.rowsReturned > 0) {
				for (int i = 0; i < oojdrew.rowsReturned; i++) {
					String key = String.valueOf(oojdrew.rowData[i][0]);
					String value = String.valueOf(oojdrew.rowData[i][1]);

					if ("?address".equals(key)) {
						return value;
					}
				}
			}
		} catch (Exception e) {
			logger.error("FORK: Failed to resolve channel " + channelId + ": " + e.getMessage());
		}

		return null;
	}


	/**
	 * Tracks which fork number contributed which attributes to a join
	 * NOW ALSO TRACKS workflowStartTime to prevent corruption
	 */
	private static class JoinContribution {
	    int forkNumber;
	    long workflowStartTime; 
	    Map<String, String> attributes;
	    
	    JoinContribution(int forkNumber, long workflowStartTime) {  
	        this.forkNumber = forkNumber;
	        this.workflowStartTime = workflowStartTime;  
	        this.attributes = new HashMap<>();
	    }
	}
	
	
	ServiceHelper.ServiceResult callServiceWithCanonicalBinding(String service, String operation,
			ArrayList<?> sargs, String returnAttrName) {
		String currentAttribute = attrMap.get("attributeName");

		if (hasCanonicalBinding(service, operation, currentAttribute)) {
			logger.info("ORCHESTRATOR: Found single-input canonical binding - calling " + service + "." + operation
					+ " immediately");

			String attributeValue = attrMap.get("attributeValue");
			ArrayList<String> singleParam = new ArrayList<>();
			singleParam.add(attributeValue);

			logger.info(
					"ORCHESTRATOR: Invoking " + service + "." + operation + " via ServiceHelper (canonical binding)");
			String fullClassName = SERVICE_PACKAGE + "." + service;
			
			if (ruleBaseVersion == null) {
				logger.error("CRITICAL: ruleBaseVersion is null - event missing version header");
				throw new RuntimeException("Cannot invoke service without ruleBaseVersion");
			}
			
			// Pass returnAttrName parameter for token enrichment
		return serviceHelper.process(sequenceID.toString(), fullClassName, operation, singleParam, returnAttrName, ruleBaseVersion);
		}

		logger.debug("ORCHESTRATOR: No single-input canonical binding - proceeding with normal service call");
		logger.info("ORCHESTRATOR: Invoking " + service + "." + operation + " via ServiceHelper (normal)");

		String fullClassName = SERVICE_PACKAGE + "." + service;
		
		if (ruleBaseVersion == null) {
			logger.error("CRITICAL: ruleBaseVersion is null - event missing version header");
			throw new RuntimeException("Cannot invoke service without ruleBaseVersion");
		}
		
		// Pass returnAttrName parameter for token enrichment
	return serviceHelper.process(sequenceID.toString(), fullClassName, operation, sargs, returnAttrName, ruleBaseVersion);
	}

	
	/**
	 * Handle individual workflow timing data requests
	 */
	public String handleWorkflowTimingDataRequest(String jsonRequest) {
		try {
			JSONObject request = jsonLibrary.parseString(jsonRequest);
			if (request == null) {
				return "{\"found\":false,\"error\":\"Invalid JSON request\"}";
			}

			Long sequenceId = (Long) request.get("sequenceId");
			String requestedServiceName = (String) request.get("serviceName");

			logger.info("Handling workflow timing data request for sequence " + sequenceId + " from service "
					+ requestedServiceName);

			TreeMap<Integer, ArrayList<Object>> measurements = serviceMeasures
					.readServiceMeasurementsBySequenceId(sequenceId.intValue());

			if (measurements.isEmpty()) {
				logger.debug("No timing data found for sequence " + sequenceId);
				return "{\"found\":false,\"sequenceId\":" + sequenceId + ",\"serviceName\":\"" + requestedServiceName
						+ "\"}";
			}

			// Build response in workflowGroups format that writeServiceTimingRecord expects
			for (ArrayList<Object> data : measurements.values()) {
				if (data.size() >= 8) {
					try {
						String serviceName = (String) data.get(2);
						String operation = (String) data.get(3);
						long arrivalTime = (Long) data.get(4);
						long invocationTime = (Long) data.get(5);
						long publishTime = (Long) data.get(6);

						if (serviceName.equals(requestedServiceName)) {
							long queueTime = invocationTime - arrivalTime;
							long serviceTime = publishTime - invocationTime;
							long totalTime = publishTime - arrivalTime;

							// Create metric object
							JSONObject metric = new JSONObject();
							metric.put("sequenceId", sequenceId);
							metric.put("serviceName", serviceName);
							metric.put("operation", operation);
							metric.put("queueTime", queueTime);
							metric.put("serviceTime", serviceTime);
							metric.put("totalTime", totalTime);

							// Wrap in array
							JSONArray metrics = new JSONArray();
							metrics.add(metric);

							// Wrap in workflowGroups structure
							JSONObject workflowGroups = new JSONObject();
							workflowGroups.put("workflow_" + sequenceId, metrics);

							// Build final response in expected format
							JSONObject response = new JSONObject();
							response.put("workflowGroups", workflowGroups);
							response.put("reportingService", requestedServiceName);
							response.put("reportingChannel", serviceChannel);
							response.put("collectionTime", System.currentTimeMillis());

							logger.info("Found timing record for " + requestedServiceName + " sequence " + sequenceId);
							return response.toJSONString();
						}
					} catch (Exception e) {
						logger.warn("Error processing timing record: " + e.getMessage());
					}
				}
			}

			return "{\"found\":false,\"sequenceId\":" + sequenceId + ",\"serviceName\":\"" + requestedServiceName
					+ "\"}";

		} catch (Exception e) {
			logger.error("Error handling workflow timing data request: " + e.getMessage(), e);
			return "{\"found\":false,\"error\":\"" + e.getMessage() + "\"}";
		}
	}

	/**
	 * Handle version-based timing data requests (get all timing data for a version)
	 */
	public String handleVersionTimingDataRequest(String jsonRequest) {
		try {
			JSONObject request = jsonLibrary.parseString(jsonRequest);
			if (request == null) {
				return "{\"found\":false,\"error\":\"Invalid JSON request\"}";
			}

			String version = (String) request.get("version");
			String requestedServiceName = (String) request.get("serviceName");

			logger.info("Handling version timing data request for version " + version + " from service "
					+ requestedServiceName);

			// Use VersionConstants to get workflow base
			int workflowBase = VersionConstants.getWorkflowBase(version);

			// Query local service measurements database for this workflow base
			TreeMap<Integer, ArrayList<Object>> allMeasurements = serviceMeasures.readServiceMeasurements(workflowBase);

			if (allMeasurements.isEmpty()) {
				logger.debug("No timing data found for version " + version + " (workflow base " + workflowBase + ")");
				return "{\"found\":false,\"version\":\"" + version + "\",\"serviceName\":\"" + requestedServiceName
						+ "\"}";
			}

			// Build response with all timing data for this version
			JSONObject workflowGroups = new JSONObject();
			JSONArray metrics = new JSONArray();

			for (Map.Entry<Integer, ArrayList<Object>> entry : allMeasurements.entrySet()) {
				ArrayList<Object> data = entry.getValue();

				if (data.size() >= 8) {
					try {
						long sequenceId = (Long) data.get(1);
						String serviceName = (String) data.get(2);
						String operation = (String) data.get(3);
						long arrivalTime = (Long) data.get(4);
						long invocationTime = (Long) data.get(5);
						long publishTime = (Long) data.get(6);

						// Only include data for the requested service
						if (serviceName.equals(requestedServiceName)) {
							long queueTime = invocationTime - arrivalTime;
							long serviceTime = publishTime - invocationTime;
							long totalTime = publishTime - arrivalTime;

							JSONObject metric = new JSONObject();
							metric.put("sequenceId", sequenceId);
							metric.put("serviceName", serviceName);
							metric.put("operation", operation);
							metric.put("queueTime", queueTime);
							metric.put("serviceTime", serviceTime);
							metric.put("totalTime", totalTime);

							metrics.add(metric);
						}
					} catch (Exception e) {
						logger.warn("Error processing timing record: " + e.getMessage());
					}
				}
			}

			if (metrics.isEmpty()) {
				logger.debug("No timing data found for service " + requestedServiceName + " in version " + version);
				return "{\"found\":false,\"version\":\"" + version + "\",\"serviceName\":\"" + requestedServiceName
						+ "\"}";
			}

			// Package response in expected format
			workflowGroups.put("version_" + version, metrics);

			JSONObject response = new JSONObject();
			response.put("found", true);
			response.put("version", version);
			response.put("serviceName", requestedServiceName);
			response.put("workflowGroups", workflowGroups);
			response.put("reportingService", requestedServiceName);
			response.put("reportingChannel", serviceChannel);
			response.put("collectionTime", System.currentTimeMillis());
			response.put("recordCount", metrics.size());

			logger.info(
					"Found " + metrics.size() + " timing records for " + requestedServiceName + " version " + version);

			return response.toJSONString();

		} catch (Exception e) {
			logger.error("Error handling version timing data request: " + e.getMessage(), e);
			return "{\"found\":false,\"error\":\"" + e.getMessage() + "\"}";
		}
	}

	
	private boolean hasCanonicalBinding(String serviceName, String operationName, String currentAttribute) {
		if (currentAttribute == null || currentAttribute.isEmpty()) {
			return false;
		}

		try {
			logger.debug("CANONICAL-BINDING: Checking canonical binding for " + serviceName + "." + operationName
					+ " with attribute '" + currentAttribute + "'");

			String markerQuery = "<Query><Atom><Rel>canonicalBinding</Rel><Ind>" + operationName
					+ "</Ind><Var>returnAttribute</Var><Ind>anyof</Ind></Atom></Query>";

			oojdrew.issueRuleMLQuery(markerQuery);
			boolean isAnyOf = (oojdrew.rowsReturned > 0);

			if (isAnyOf) {
				logger.info("CANONICAL-BINDING: Operation " + operationName + " uses 'anyof' semantics");

				String checkQuery = "<Query><Atom><Rel>canonicalBinding</Rel><Ind>" + operationName
						+ "</Ind><Var>returnAttribute</Var><Ind>" + currentAttribute + "</Ind></Atom></Query>";

				oojdrew.issueRuleMLQuery(checkQuery);

				if (oojdrew.rowsReturned > 0) {
					logger.info("CANONICAL-BINDING: 'anyof' match - " + operationName
							+ " can execute immediately with '" + currentAttribute + "'");
					return true;
				} else {
					logger.debug("CANONICAL-BINDING: Current attribute '" + currentAttribute
							+ "' is not in the 'anyof' list for " + operationName);
					return false;
				}
			}

			logger.debug("CANONICAL-BINDING: Operation " + operationName + " uses standard (AND) semantics");

			String bindingQuery = "<Query><Atom><Rel>canonicalBinding</Rel><Ind>" + operationName
					+ "</Ind><Var>returnAttribute</Var><Var>input</Var></Atom></Query>";

			oojdrew.issueRuleMLQuery(bindingQuery);

			if (oojdrew.rowsReturned == 0) {
				logger.debug("CANONICAL-BINDING: No canonical binding found for " + operationName);
				return false;
			}

			int totalInputsRequired = 0;
			boolean foundCurrentAttribute = false;

			boolean hasNext = true;
			while (hasNext) {
				for (int i = 0; i < oojdrew.rowsReturned; i++) {
					String key = oojdrew.rowData[i][0].toString();
					String value = oojdrew.rowData[i][1].toString();

					if ("?input".equals(key)) {
						if (value.equals(currentAttribute)) {
							foundCurrentAttribute = true;
						}
						if (!"anyof".equals(value)) {
							totalInputsRequired++;
						}
					}
				}

				if (oojdrew.hasNext) {
					oojdrew.nextSolution();
				} else {
					hasNext = false;
				}
			}

			logger.info("CANONICAL-BINDING: " + operationName + " requires " + totalInputsRequired
					+ " inputs total (standard AND logic)");

			if (!foundCurrentAttribute) {
				logger.debug("CANONICAL-BINDING: Current attribute '" + currentAttribute
						+ "' not found in canonical binding");
				return false;
			}

			if (totalInputsRequired == 1) {
				logger.info("CANONICAL-BINDING: " + operationName + " needs 1 input - can call immediately with '"
						+ currentAttribute + "'");
				return true;
			} else {
				logger.info("CANONICAL-BINDING: " + operationName + " needs " + totalInputsRequired
						+ " inputs - should use JOIN processing");
				return false;
			}

		} catch (Exception e) {
			logger.error("CANONICAL-BINDING: Error checking canonical binding: " + e.getMessage(), e);
			return false;
		}
	}

	public void getThisServiceOperationParameters(String serviceName, String operationName) {
		oojdrew.issueRuleMLQuery("<Query><Atom><Rel>serviceName</Rel><Ind>" + serviceName
				+ "</Ind><Var>operation</Var><Ind>" + operationName
				+ "</Ind><Var>attribute</Var><Var>input</Var><Var>channel</Var>" + "<Var>port</Var></Atom></Query>");
		inputCollection.clear();

		if (oojdrew.rowsReturned == 0)
			return;
		boolean hasNext = true;

		while (hasNext) {
			try {
				for (int i = 0; i < oojdrew.rowsReturned; i++) {
					switch (oojdrew.rowData[i][0].toString()) {
					case "?channel":
						break;
					case "?port":
						break;
					case "?operation":
						break;
					case "?attribute":
						returnAttributeName = oojdrew.rowData[i][1].toString();
						if (returnAttributeName.equals("null"))
							returnAttributeName = null;
						break;
					case "?input":
						inputCollection.add(oojdrew.rowData[i][1].toString());
						break;
					}
				}
			} catch (Exception e) {
				e.printStackTrace();
				System.err.println("Invalid service operation:" + operationName);
				inputCollection.clear();
				returnAttributeName = null;
				return;
			}
			if (oojdrew.hasNext)
				oojdrew.nextSolution();
			else
				hasNext = false;
		}
	}

	public void getThisNodeType() {
		oojdrew.issueRuleMLQuery("<Query><Atom><Rel>NodeType</Rel><Var>nodeType</Var></Atom></Query>");
		try {
			if (oojdrew.rowsReturned == 0)
				nodeType = null;
			for (int i = 0; i < oojdrew.rowsReturned; i++) {
				switch (oojdrew.rowData[i][0].toString()) {
				case "?nodeType":
					nodeType = oojdrew.rowData[i][1].toString();
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
			System.err.println("Failed to get Node type");
			nodeType = null;
		}
	}

	/**
	 * LEGACY FALLBACK: Get decision values from global DecisionValue facts
	 * 
	 * NOTE: This method is now only used as a fallback for old RuleBase files
	 * that don't include decisionValue in the publishes relation.
	 * 
	 * With the fixed CoreRuleBase, decisionValueCollection is populated directly
	 * in getNextService() from the 7th argument of the publishes query.
	 * 
	 * This legacy approach has a bug: it queries global DecisionValue facts and
	 * assumes they align by index with nextServiceCollection, which fails when
	 * multiple branches share the same condition type (e.g., DECISION_EQUAL_TO)
	 * but have different expected values (true vs false).
	 */
	public void getDecisionValue() {
		// If already populated by getNextService(), skip legacy query
		if (!decisionValueCollection.isEmpty()) {
			logger.debug("decisionValueCollection already populated from publishes query - skipping legacy getDecisionValue");
			return;
		}
		
		// Legacy fallback for old RuleBase files without decisionValue in publishes
		logger.warn("Using legacy getDecisionValue - consider updating CoreRuleBase to include decisionValue in publishes");
		try {
			oojdrew.issueRuleMLQuery("<Query><Atom><Rel>DecisionValue</Rel><Var>Value</Var></Atom></Query>");

			if (oojdrew.rowsReturned == 0) {
				decisionValueCollection.clear();
				return;
			}
			boolean hasNext = true;
			int j = 0;
			while (hasNext) {
				for (int i = 0; i < oojdrew.rowsReturned; i++) {
					switch (oojdrew.rowData[i][0].toString()) {
					case "?Value":
						decisionValueCollection.put(j, (String) oojdrew.rowData[i][1]);
					}
				}
				j++;
				if (oojdrew.hasNext)
					oojdrew.nextSolution();
				else
					hasNext = false;
			}
		} catch (Exception e) {
			e.printStackTrace();
			System.err.println("Failed to get Node type");
			decisionValueCollection.clear();
		}
	}

	public void loadRuleBaseVersionFromKnowledgeBase() {
		oojdrew.issueRuleMLQuery("<Atom><Rel>Version</Rel><Var>ruleBaseVersion</Var></Atom>");
		try {
			if (oojdrew.rowsReturned == 0)
				ruleBaseVersion = null;
			for (int i = 0; i < oojdrew.rowsReturned; i++) {
				switch (oojdrew.rowData[i][0].toString()) {
				case "?ruleBaseVersion":
					ruleBaseVersion = oojdrew.rowData[i][1].toString();
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
			System.err.println("Failed to get ruleBase Version from KnowledgeBase");
			ruleBaseVersion = null;
		}
	}

	private void getNextService() {
		nextServiceCollection.clear();
		nextChannelCollection.clear();
		nextPortCollection.clear();
		nextOperationCollection.clear();
		nextServiceMap.clear();
		decisionValueCollection.clear();  // Clear here - populated directly from publishes query

		// FIXED: Added 7th argument (decisionValue) to publishes query
		// This retrieves the expected decision value for each branch directly from the derivation rule
		oojdrew.issueRuleMLQuery(
				"<Query><Atom><Rel>publishes</Rel><Var>nextService</Var><Var>condition</Var><Var>nextOperation</Var><Var>nextChannel</Var><Var>nextLink</Var><Var>nextPort</Var><Var>decisionValue</Var></Atom></Query>");

		// VALIDATION - but don't fail, just log warning
		if (oojdrew.rowsReturned == 0) {
		    logger.warn("No routing rules found for " + serviceName + "." + operationName + 
		                " - may be terminal node or routing to TERMINATE");
		    return;  // Exit gracefully instead of throwing
		}

		int j = 0;
		while (true) {
			try {
				for (int i = 0; i < oojdrew.rowsReturned; i++) {
					switch ((String) oojdrew.rowData[i][0]) {
					case "?nextService":
						nextServiceCollection.add((String) oojdrew.rowData[i][1]);
						break;
					case "?nextChannel":
						nextChannelCollection.add((String) oojdrew.rowData[i][1]);
						break;
					case "?nextPort":
						nextPortCollection.add((String) oojdrew.rowData[i][1]);
						break;
					case "?nextOperation":
						nextOperationCollection.add((String) oojdrew.rowData[i][1]);
						break;
					case "?nextLink":
						break;
					case "?condition":
						nextServiceMap.put(j, (String) oojdrew.rowData[i][1]);
						break;
					// FIXED: Capture decisionValue directly from publishes result
					// This ensures proper alignment between service and its expected decision value
					case "?decisionValue":
						decisionValueCollection.put(j, (String) oojdrew.rowData[i][1]);
						break;
					}
				}
			} catch (Exception e) {
				e.printStackTrace();
				System.out.println("Failed to get Next Service: ");
				nextServiceCollection.clear();
				nextServiceMap.clear();
				return;
			}
			j++;
			if (oojdrew.hasNext) {
				oojdrew.nextSolution();
			} else
				return;
		}
	}

	/**
	 * Backward-compatible overload for non-fork routing.
	 */
	void callNextOperation(String val, int solutionIndex, boolean expired) {
		callNextOperation(val, solutionIndex, expired, 0);
	}
	
	/**
	 * Route to next operation with explicit branch number for fork routing.
	 * 
	 * @param val The attribute value to route
	 * @param solutionIndex Index into the service/operation collections
	 * @param expired Whether the token has expired
	 * @param branchNumber The fork branch number (1, 2, ...) or 0 for non-fork routing
	 */
	void callNextOperation(String val, int solutionIndex, boolean expired, int branchNumber) {
		if (nextServiceCollection.isEmpty()) {
			logger.info("ORCHESTRATOR: WORKFLOW TERMINATION - No next services configured");
			return;
		}

		if (solutionIndex >= nextServiceCollection.size()) {
			logger.warn("ORCHESTRATOR: Invalid solution index " + solutionIndex + " - workflow terminating");
			return;
		}

		String nextServiceName = nextServiceCollection.get(solutionIndex);
		String nextOperationName = nextOperationCollection.get(solutionIndex);
		String nextChannelName = nextChannelCollection.get(solutionIndex);
		String nextPortNumber = nextPortCollection.get(solutionIndex);

		String originalChannelId = nextChannelName;

		// Try activeService override
		try {
			String activeServiceQuery = String.format("<Query><Atom><Rel>activeService</Rel><Ind>%s</Ind><Ind>%s</Ind>"
					+ "<Var>channelId</Var><Var>port</Var></Atom></Query>", nextServiceName, nextOperationName);

			logger.info("DEBUG: Querying activeService for " + nextServiceName + "." + nextOperationName);
			oojdrew.issueRuleMLQuery(activeServiceQuery);
			logger.info("DEBUG: ActiveService query returned " + oojdrew.rowsReturned + " rows");

			if (oojdrew.rowsReturned > 0) {
				String foundChannelId = null;
				String foundPort = null;

				// Extract the activeService results
				for (int i = 0; i < oojdrew.rowsReturned; i++) {
					String key = String.valueOf(oojdrew.rowData[i][0]);
					String value = String.valueOf(oojdrew.rowData[i][1]);

					logger.info("DEBUG: ActiveService result - " + key + " = " + value);

					if ("?channelId".equals(key)) {
						foundChannelId = value;
					} else if ("?port".equals(key)) {
						foundPort = value;
					}
				}

				// Apply activeService override if we found both values
				if (foundChannelId != null && foundPort != null) {
					logger.info("ORCHESTRATOR: APPLYING activeService override - Channel: " + foundChannelId
							+ ", Port: " + foundPort);

					// Override the routing variables
					nextChannelName = foundChannelId;
					nextPortNumber = foundPort;
					originalChannelId = foundChannelId;

					// If it's an IP channel, resolve to actual IP address
					if (nextChannelName != null && nextChannelName.startsWith("ip")) {
						String boundChannelQuery = String.format(
								"<Query><Atom><Rel>boundChannel</Rel><Ind>%s</Ind><Var>address</Var></Atom></Query>",
								nextChannelName);

						oojdrew.issueRuleMLQuery(boundChannelQuery);

						if (oojdrew.rowsReturned > 0) {
							for (int i = 0; i < oojdrew.rowsReturned; i++) {
								String key = String.valueOf(oojdrew.rowData[i][0]);
								String value = String.valueOf(oojdrew.rowData[i][1]);

								if ("?address".equals(key)) {
									String ipAddress = value;
									logger.info("ORCHESTRATOR: Resolved " + nextChannelName + " to IP " + ipAddress);
									nextChannelName = ipAddress;
									break;
								}
							}
						} else {
							logger.warn("ORCHESTRATOR: Could not resolve " + foundChannelId + " to IP address");
						}
					}
				} else {
					logger.warn("ORCHESTRATOR: ActiveService query incomplete - missing channelId or port");
				}
			} else {
				logger.info("ORCHESTRATOR: No activeService override found for " + nextServiceName + "."
						+ nextOperationName);
			}
		} catch (Exception e) {
			logger.error("ORCHESTRATOR: Error querying activeService for " + nextServiceName + "." + nextOperationName,
					e);
		}

		// Update service map
		serviceMap.put("serviceName", nextServiceName);
		serviceMap.put("operation", nextOperationName);

		// Derive attribute name based on destination's canonical binding
		String seqIdStr = headerMap.get("sequenceId");
		if (seqIdStr == null) {
			throw new IllegalStateException("ROUTING ERROR: headerMap.sequenceId is null in callNextOperation");
		}
		int tokenId = Integer.parseInt(seqIdStr);
		
		String mappedAttrName = deriveAttributeNameForRouting(tokenId, branchNumber, nextServiceName, nextOperationName);
		attrMap.put("attributeName", mappedAttrName);
		logger.info("ROUTING: Token " + tokenId + " -> '" + mappedAttrName + "'" +
				   (branchNumber > 0 ? " (fork branch " + branchNumber + ")" : "") +
				   " to " + nextServiceName + "." + nextOperationName);

		// Update XML payload
		outgoingXMLPayLoad = xph.modifyMultipleXMLItems(outgoingXMLPayLoad, "//header/*", headerMap);
		outgoingXMLPayLoad = xph.modifyMultipleXMLItems(outgoingXMLPayLoad, "//service/*", serviceMap);
		outgoingXMLPayLoad = xph.modifyMultipleXMLItems(outgoingXMLPayLoad, "//joinAttribute/*", attrMap);

		monitorDataMap.put("processElapsedTime", Long.toString(System.currentTimeMillis()));
		monitorDataMap.put("callingService ", serviceName);

		outgoingXMLPayLoad = xph.modifyMultipleXMLItems(outgoingXMLPayLoad, "//monitorData/*", monitorDataMap);

		if (nextServiceName.equals("null") || nextOperationName.equals("null")) {
			logger.info("ORCHESTRATOR: WORKFLOW TERMINATION - Next service is null");
			return;
		}

		try {
			if (sequenceID == 0) {
				publish.ruleCommitmentPublish(outgoingXMLPayLoad);
			}

			logger.info("ORCHESTRATOR: Publishing to Service " + nextServiceName + " Operation: " + nextOperationName);
			logger.info("ORCHESTRATOR: Channel: " + nextChannelName + ", Port: " + nextPortNumber);
			logger.info(
					"ORCHESTRATOR: Attribute " + attrMap.get("attributeName") + " = " + attrMap.get("attributeValue"));

			// NOTE: Token exit recording (instrumentTokenExit) should be done by caller before calling this method
			// This allows callers to control whether/when to record (e.g., fork children vs parent)

			boolean published = eventPublisher.publishServiceEvent(nextServiceName, nextOperationName,
					outgoingXMLPayLoad, nextChannelName, nextPortNumber, ruleBaseVersion, originalChannelId);
			logger.info(
					"ORCHESTRATOR: Completed callNextOperation for " + nextServiceName + ", published=" + published);

			if (published) {
				logger.info("ORCHESTRATOR: Successfully published to " + nextServiceName + " via EventPublisher");
			} else {
				logger.error("ORCHESTRATOR: Failed to publish to " + nextServiceName + " via EventPublisher");
			}

		} catch (Exception e) {
			logger.error("ORCHESTRATOR: Failed to publish to " + nextServiceName + " via EventPublisher", e);
		}
	}

	public void putServicePerformanceData(int sequenceID) {
	    try {
	        serviceMeasuresDBMap.clear();

	        // Write the exact fields needed for workflow timing
	        serviceMeasuresDBMap.put("sequenceID", Integer.toString(phaseSequenceID));
	        serviceMeasuresDBMap.put("serviceName", serviceName);
	        serviceMeasuresDBMap.put("operation", operationName);
	        
	        // CRITICAL FIX: Use true arrival time from EventReactor instead of ServiceThread dequeue time
	        String trueArrivalTime = monitorDataMap.get("eventArrivalTime");
	        if (trueArrivalTime != null) {
	            serviceMeasuresDBMap.put("arrivalTime", trueArrivalTime);
	            logger.debug("TIMING: Using EventReactor arrival time=" + trueArrivalTime + 
	                        " for seqID=" + phaseSequenceID);
	        } else {
	            // Fallback to ServiceThread dequeue time if EventReactor time not available
	            serviceMeasuresDBMap.put("arrivalTime", Long.toString(taskArrivalTime));
	            logger.warn("TIMING: EventReactor arrival time not found, using ServiceThread dequeue time=" + 
	                       taskArrivalTime + " for seqID=" + phaseSequenceID);
	        }
	        
	        serviceMeasuresDBMap.put("invocationTime", Long.toString(serviceInvocationTime));
	        serviceMeasuresDBMap.put("publishTime", Long.toString(servicePublishTime));

	        // CRITICAL FIX: Use captured workflowStartTime, not potentially corrupted monitorDataMap
	        if (currentWorkflowStartTime > 0) {
	            serviceMeasuresDBMap.put("workflowStartTime", Long.toString(currentWorkflowStartTime));
	            logger.debug("WRITE: Using captured workflowStartTime=" + currentWorkflowStartTime + 
	                        " for seqID=" + phaseSequenceID);
	        } else {
	            // Fallback to monitorDataMap only if capture failed
	            String workflowStartTime = monitorDataMap.get("processStartTime");
	            if (workflowStartTime != null) {
	                serviceMeasuresDBMap.put("workflowStartTime", workflowStartTime);
	                logger.warn("WRITE: Fell back to monitorDataMap workflowStartTime=" + workflowStartTime + 
	                           " for seqID=" + phaseSequenceID);
	            }
	        }
	        
	        // MARKING CAPTURE: Write Petri Net marking data
	        serviceMeasuresDBMap.put("bufferSize", Integer.toString(bufferSizeAtDequeue));
	        serviceMeasuresDBMap.put("maxQueueCapacity", Integer.toString(maxQueueCapacity));
	        
	        // Calculate total system marking: buffer + place (1 if processing, 0 if not in place yet)
	        // Note: At dequeue time, token is leaving buffer but hasn't entered place yet
	        // So total marking = bufferSize (after dequeue) + 1 (this token being processed)
	        int totalMarking = bufferSizeAtDequeue + 1;
	        serviceMeasuresDBMap.put("totalMarking", Integer.toString(totalMarking));

	        logger.info("=== SERVICE TIMING RECORD ===");
	        logger.info("  Sequence ID: " + phaseSequenceID);
	        logger.info("  Service: " + serviceName);
	        logger.info("  Operation: " + operationName);
	        logger.info("  Arrival Time (EventReactor): " + (trueArrivalTime != null ? trueArrivalTime : taskArrivalTime));
	        logger.info("  Invocation Time: " + serviceInvocationTime);
	        logger.info("  Publish Time: " + servicePublishTime);
	        logger.info("  Workflow Start Time: " + currentWorkflowStartTime);
	        logger.info("  MARKING: Buffer=" + bufferSizeAtDequeue + ", Total=" + totalMarking + 
	                   ", Capacity=" + maxQueueCapacity);
	        logger.info("==============================");

	        serviceMeasures.writeServiceTimingRecord(serviceMeasuresDBMap);

	        // Calculate and log performance metrics for debugging
	        if (logger.isDebugEnabled()) {
	            long arrivalTime = trueArrivalTime != null ? 
	                Long.parseLong(trueArrivalTime) : taskArrivalTime;
	            long queueTime = serviceInvocationTime - arrivalTime;
	            long serviceTime = servicePublishTime - serviceInvocationTime;
	            long totalTime = servicePublishTime - arrivalTime;

	            logger.debug("  Queue Time (including priority queue): " + queueTime + "ms");
	            logger.debug("  Service Time: " + serviceTime + "ms");
	            logger.debug("  Total Time: " + totalTime + "ms");
	            logger.debug("  Buffer Utilization: " + 
	                        String.format("%.1f%%", (double)bufferSizeAtDequeue/maxQueueCapacity * 100));
	        }

	    } catch (Exception e) {
	        logger.error("Error writing service timing data for sequenceID: " + phaseSequenceID, e);
	    }
	}
	public void logTimingStatistics() {
		try {
			// Use readServiceMeasurements instead of readEventResponseData
			TreeMap<Integer, ArrayList<Object>> timingData = serviceMeasures
					.readServiceMeasurements(phaseSequenceID / 100 * 100); // Get workflow base

			if (timingData.isEmpty()) {
				logger.info("No timing data available yet.");
				return;
			}

			long totalQueueTime = 0;
			long totalServiceTime = 0;
			long totalTime = 0;
			int count = 0;

			for (ArrayList<Object> data : timingData.values()) {
				if (data.size() >= 8) {
					// Extract timing fields from service measurements
					long arrivalTime = (Long) data.get(4); // arrivalTime
					long invocationTime = (Long) data.get(5); // invocationTime
					long publishTime = (Long) data.get(6); // publishTime

					long queueTime = invocationTime - arrivalTime;
					long serviceTime = publishTime - invocationTime;
					long total = publishTime - arrivalTime;

					totalQueueTime += queueTime;
					totalServiceTime += serviceTime;
					totalTime += total;
					count++;
				}
			}

			if (count > 0) {
				logger.info("=== TIMING STATISTICS SUMMARY ===");
				logger.info("Total Events Processed: " + count);
				logger.info("Average Queue Time: " + (totalQueueTime / count) + "ms");
				logger.info("Average Service Time: " + (totalServiceTime / count) + "ms");
				logger.info("Average Total Time: " + (totalTime / count) + "ms");
				logger.info("==================================");
			}

		} catch (Exception e) {
			logger.error("Error calculating timing statistics", e);
		}
	}

	private Integer mapFromSequenceID(Integer sqID) {
		// NEW ENCODING: childTokenId = parentTokenId + branchNumber
		// branchNumber is 1-99, so parent = sqID - (sqID % 100)
		// Example: 1000001 -> parent is 1000000
		int branchNumber = sqID % 100;
		return sqID - branchNumber;
	}

	private String cleanQuotes(String value) {
		if (value != null && value.startsWith("\"") && value.endsWith("\"") && value.length() > 1) {
			return value.substring(1, value.length() - 1);
		}
		return value;
	}

	/**
	 * Compare guard/decision values - case-insensitive only for boolean values.
	 * "True"/"true"/"TRUE" and "False"/"false"/"FALSE" match regardless of case.
	 * All other values use exact case-sensitive comparison.
	 */
	boolean guardsMatch(String actual, String expected) {
		if (actual == null || expected == null) {
			return actual == expected;
		}
		// Case-insensitive for boolean-like values only
		if (isBooleanValue(actual) && isBooleanValue(expected)) {
			return actual.equalsIgnoreCase(expected);
		}
		// Exact match for everything else
		return actual.equals(expected);
	}

	private boolean isBooleanValue(String value) {
		return value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false");
	}

	// ========================================================================
	// ACCESSOR METHODS FOR NodeTypeProcessorHelper
	// ========================================================================

	String getServiceName() {
		return serviceName;
	}

	String getOperationName() {
		return operationName;
	}

	String getReturnAttributeName() {
		return returnAttributeName;
	}

	String getNodeType() {
		return nodeType;
	}

	Integer getSequenceID() {
		return sequenceID;
	}

	/**
	 * Returns the actual token ID from the incoming message header.
	 * This is the forked child token ID, not the parent token ID.
	 * Use this for EXIT recording at EdgeNodes where the token that entered is the one that exits.
	 */
	Integer getPhaseSequenceID() {
		return phaseSequenceID;
	}

	String getRuleBaseVersion() {
		return ruleBaseVersion;
	}

	String getServicePackage() {
		return SERVICE_PACKAGE;
	}

	TreeMap<String, String> getHeaderMap() {
		return headerMap;
	}

	TreeMap<String, String> getAttrMap() {
		return attrMap;
	}

	TreeMap<String, String> getServiceMap() {
		return serviceMap;
	}

	TreeMap<String, String> getMonitorDataMap() {
		return monitorDataMap;
	}

	TreeMap<Integer, String> getDecisionValueCollection() {
		return decisionValueCollection;
	}

	TreeMap<Integer, String> getNextServiceMap() {
		return nextServiceMap;
	}

	ArrayList<String> getNextServiceCollection() {
		return nextServiceCollection;
	}

	ArrayList<String> getNextOperationCollection() {
		return nextOperationCollection;
	}

	ArrayList<String> getNextChannelCollection() {
		return nextChannelCollection;
	}

	ArrayList<String> getNextPortCollection() {
		return nextPortCollection;
	}

	PetriNetInstrumentationHelper getPetriNetHelper() {
		return petriNetHelper;
	}

	ServiceHelper getServiceHelper() {
		return serviceHelper;
	}

	// ========================================================================
	// PETRI NET INSTRUMENTATION WRAPPERS
	// Centralized methods for all PetriNet recording - easier to track and maintain
	// ========================================================================

	/**
	 * Record token entering a place.
	 * 
	 * For JoinNode T_in: Records BUFFERED only (ENTER recorded later when join completes)
	 * For EdgeNode T_in: Records BUFFERED then ENTER (token passes through immediately)
	 * 
	 * This ensures the animator can show tokens arriving at T_in transitions before
	 * entering the place, even for non-join (EdgeNode) transitions.
	 * 
	 * @param tokenId The token ID
	 * @param placeName The place name
	 * @param nodeType The node type (describes T_out routing behavior)
	 * @param workflowStartTime The workflow start time
	 * @param bufferSize The buffer size at dequeue
	 * @param needsInputSynchronization True if this place requires multiple inputs (T_in is a join)
	 */
	void instrumentTokenEnter(int tokenId, String placeName, String nodeType, 
	                          long workflowStartTime, int bufferSize, boolean needsInputSynchronization) {
	    if (petriNetHelper == null) return;
	    
	    // Extract eventGeneratorTimestamp from monitorDataMap (set during payload parsing at line 470)
	    String eventGenTimestamp = monitorDataMap.get("eventGeneratorTimestamp");
	    
	    // Extract sourceEventGenerator from monitorDataMap (identifies which event generator created this token)
	    String sourceEventGenerator = monitorDataMap.get("sourceEventGenerator");
	    
	    // Zero out the timestamp so downstream services won't record duplicate GENERATED events
	    // The zero will be written to XML by modifyMultipleXMLItems at line 2653
	    monitorDataMap.put("eventGeneratorTimestamp", "0");
	    
	    // Determine if this is a child token at a join point
	    // needsInputSynchronization = true means T_in expects multiple inputs (join behavior)
	    // tokenId % 100 != 0 means this is a child token (forked)
	    boolean isChildAtJoin = needsInputSynchronization && (tokenId % 100) != 0;
	    
	    if (isChildAtJoin) {
	        // JoinNode: Record BUFFERED only - ENTER recorded later when join completes
	        petriNetHelper.recordTokenBuffered(incomingXMLPayLoad, tokenId, 
	                                           placeName, nodeType, workflowStartTime, bufferSize,
	                                           eventGenTimestamp, sourceEventGenerator);
	        logger.debug("PETRI-NET: Token " + tokenId + " BUFFERED at " + placeName + " (waiting for join)");
	    } else {
	        // EdgeNode: Record BUFFERED first (arrival at T_in), then ENTER (passes through immediately)
	        // This gives the animator visibility of the token at the T_in transition
	        petriNetHelper.recordTokenBuffered(incomingXMLPayLoad, tokenId, 
	                                           placeName, nodeType, workflowStartTime, bufferSize,
	                                           eventGenTimestamp, sourceEventGenerator);
	        logger.debug("PETRI-NET: Token " + tokenId + " BUFFERED at " + placeName + " (T_in arrival)");
	        
	        // Immediately record ENTER since EdgeNode has no synchronization wait
	        petriNetHelper.recordTokenEntering(incomingXMLPayLoad, tokenId, 
	                                           placeName, nodeType, workflowStartTime, bufferSize,
	                                           null, null);  // No eventGen - already recorded in BUFFERED
	        logger.debug("PETRI-NET: Token " + tokenId + " ENTER at " + placeName);
	    }
	}

	/**
	 * Record token entering a place (backward compatible overload).
	 * Assumes no input synchronization - use for non-join entry points.
	 */
	void instrumentTokenEnter(int tokenId, String placeName, String nodeType, 
	                          long workflowStartTime, int bufferSize) {
	    instrumentTokenEnter(tokenId, placeName, nodeType, workflowStartTime, bufferSize, false);
	}
	/**
	 * Record token entering a place (simple version for join reconstitution).
	 * Note: eventGeneratorTimestamp is null for reconstituted tokens since they weren't
	 * directly generated - they're created when child tokens merge at a JoinNode.
	 */
	void instrumentTokenEnterSimple(int tokenId, String placeName, String nodeType, 
	                                long workflowStartTime) {
		if (petriNetHelper == null) return;
		
		petriNetHelper.recordTokenEntering(incomingXMLPayLoad, tokenId, 
		                                   placeName, nodeType, workflowStartTime, 0, null, null);
		logger.debug("PETRI-NET: Token " + tokenId + " ENTER at " + placeName + " (reconstituted)");
	}

	/**
	 * Record token exiting a place to next destination.
	 */
	void instrumentTokenExit(int tokenId, String fromPlace, String toService, 
	                         String toOperation, String nodeType, String arcValue) {
		if (petriNetHelper == null) return;
		
		petriNetHelper.recordTokenExitingWithArc(tokenId, fromPlace, toService, toOperation,
		                                         nodeType, decisionValueCollection, arcValue);
		logger.debug("PETRI-NET: Token " + tokenId + " EXIT from " + fromPlace + 
		            " -> " + toService + "." + toOperation + " [arc=" + arcValue + "]");
	}

	/**
	 * Record token exiting without arc value (for simple edge routing).
	 */
	void instrumentTokenExitSimple(int tokenId, String fromPlace, String toService, 
	                               String toOperation, String nodeType) {
		if (petriNetHelper == null) return;
		
		petriNetHelper.recordTokenExiting(tokenId, fromPlace, toService, toOperation,
		                                  nodeType, decisionValueCollection);
		logger.debug("PETRI-NET: Token " + tokenId + " EXIT from " + fromPlace + 
		            " -> " + toService + "." + toOperation);
	}

	/**
	 * Record fork creating a child token from parent.
	 */
	void instrumentForkChild(int parentTokenId, int childTokenId, String forkPlace) {
		if (petriNetHelper == null) return;
		
		String forkTransition = "T_out_" + forkPlace;
		petriNetHelper.recordForkGenealogy(parentTokenId, childTokenId, forkTransition);
		logger.debug("PETRI-NET: FORK " + parentTokenId + " -> child " + childTokenId + 
		            " at " + forkPlace);
	}

	/**
	 * Record join completion: continuing token ENTERS, others are CONSUMED.
	 * 
	 * With lowest-child-continues model:
	 * - All tokens BUFFER on arrival at JOIN (children have tokenId % 100 != 0)
	 * - When JOIN fires, the lowest child ID continues (ENTERS the place)
	 * - Other participants are CONSUMED
	 * 
	 * @param continuingTokenId The token that continues after the join (lowest child)
	 * @param joinPlace The place where the join occurs
	 * @param participantTokenIds The token IDs that synchronized at this join
	 * @param workflowStartTime The workflow start time to preserve
	 */
	void instrumentJoinComplete(int continuingTokenId, String joinPlace, List<Integer> participantTokenIds, 
	                            long workflowStartTime) {
		if (petriNetHelper == null) return;
		
		// Record ENTER for the continuing token (it only had BUFFERED on arrival)
		petriNetHelper.recordTokenEntering(incomingXMLPayLoad, continuingTokenId, 
		                                   joinPlace, "JoinNode", workflowStartTime, 0, null, null);
		logger.debug("PETRI-NET: JOIN complete - token " + continuingTokenId + " ENTER at " + joinPlace);
		
		// Record CONSUMED for all other participants
		for (Integer participantId : participantTokenIds) {
			if (participantId != continuingTokenId) {
				petriNetHelper.recordTokenConsumedAtJoin(participantId, joinPlace, 
				                                         continuingTokenId, workflowStartTime);
				logger.debug("PETRI-NET: JOIN consumed participant " + participantId + " at " + joinPlace);
			}
		}
	}

	/**
	 * Record token termination (workflow end).
	 */
	void instrumentTokenTerminate(int tokenId, String fromPlace, String nodeType) {
		if (petriNetHelper == null) return;
		
		petriNetHelper.recordTokenExiting(tokenId, fromPlace, "TERMINATE", "TERMINATE",
		                                  nodeType, decisionValueCollection);
		logger.debug("PETRI-NET: Token " + tokenId + " TERMINATED at " + fromPlace);
	}
}