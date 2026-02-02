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
 * ARCHITECTURE: EventReactor → ServiceThread (Orchestrator) → Service →
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
				// MARKING CAPTURE: Get buffer state BEFORE dequeuing
				if (thread != null) {
					bufferSizeAtDequeue = thread.getQueueSize();
					if (maxQueueCapacity == 0) {
						maxQueueCapacity = thread.getMaxQueueCapacity();
					}
				}
				
				dataMap = thread.getScheduledToken();

				if (isShutdown) {
					logger.info("Shutdown requested, exiting processing loop");
					break;
				}

				taskArrivalTime = System.currentTimeMillis();
				attrMap.clear();
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
	    argValPriorityMap.putIfAbsent(joinID, new ConcurrentSkipListMap<>());
	    ConcurrentSkipListMap<String, String> currentJoinMap = argValPriorityMap.get(joinID);
	    if (currentJoinMap != null) {
	        // For PetriNet tokens with encoded join info, use sequence ID as key to allow
	        // multiple tokens with same attribute name (e.g., both P2 and P3 send "token")
	        int tokenRemainder = phaseSequenceID % 10000;
	        int tokenJoinCount = tokenRemainder / 100;
	        int branchNumber = tokenRemainder % 100;
	        boolean isPetriNetToken = (tokenJoinCount >= 2 && branchNumber >= 1 && branchNumber <= tokenJoinCount);
	        
	        String mapKey = isPetriNetToken ? String.valueOf(phaseSequenceID) : payloadAttributeName;
	        currentJoinMap.putIfAbsent(mapKey, payloadAttributeValue);
	        
	        logger.debug("JOIN-MAP: Added key=" + mapKey + " to join " + joinID + 
	                    " (isPetriNet=" + isPetriNetToken + ", mapSize=" + currentJoinMap.size() + ")");
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
	            getJoinInputCount();  // Get PetriNet mode join input count (if specified)
	            getThisServiceOperationParameters(serviceName, operationName);

	            // Canonical binding override - but NOT for JoinNodes!
	            // JoinNodes require multiple inputs for synchronization - overriding would break Join semantics
	            if (inputCollection.size() == 1 && !"JoinNode".equals(nodeType)) {
	                String currentAttribute = attrMap.get("attributeName");

	                if (hasCanonicalBinding(serviceName, operationName, currentAttribute)) {
	                    logger.info("ORCHESTRATOR: Canonical binding found - overriding serviceName facts");
	                    inputCollection.clear();
	                    inputCollection.add("null");
	                    logger.info("ORCHESTRATOR: Overrode inputCollection for canonical binding");
	                }
	            } else if (inputCollection.size() == 1 && "JoinNode".equals(nodeType)) {
	                // Check if this is PetriNet mode (joinInputCount > 0)
	                if (joinInputCount > 0) {
	                    // PetriNet mode: orchestrator will merge tokens, service uses single-arg method
	                    logger.info("ORCHESTRATOR: PetriNet mode JoinNode - will wait for " + joinInputCount + 
	                               " tokens then merge and call single-arg processToken()");
	                    // Override inputCollection for canonical binding (will use merged token)
	                    String currentAttribute = attrMap.get("attributeName");
	                    if (hasCanonicalBinding(serviceName, operationName, currentAttribute)) {
	                        inputCollection.clear();
	                        inputCollection.add("null");
	                        logger.info("ORCHESTRATOR: Overrode inputCollection for PetriNet mode canonical binding");
	                    }
	                } else {
	                    // SOA mode: canonical binding must specify multiple inputs
	                    logger.warn("ORCHESTRATOR: WARNING - JoinNode " + serviceName + ":" + operationName + 
	                               " has only 1 input in canonical binding. JoinNodes require 2+ inputs. " +
	                               "Check canonical binding configuration.");
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
	    // PETRI NET: Record token entering with buffer size from transition queue
	    if (petriNetHelper != null) {
	        petriNetHelper.recordTokenEntering(incomingXMLPayLoad, phaseSequenceID, 
	                                           serviceName, nodeType, currentWorkflowStartTime,
	                                           bufferSizeAtDequeue);
	    }

	    logger.info("DEBUG: About to check inputCollection.isEmpty() - size: " + inputCollection.size());
	    logger.info("DEBUG: inputCollection contents: " + inputCollection);
	    logger.info("DEBUG: nodeType: " + nodeType);

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
	        // CASE 3: EDGE NODE / TERMINATE NODE - Exactly 1 workflow data attribute required
	        // TerminateNode describes OUTPUT behavior (workflow ends), not INPUT behavior
	        // Services with TerminateNode that receive data are processed like EdgeNode
	        // ========================================================================
	        else if ("EdgeNode".equals(nodeType) || "TerminateNode".equals(nodeType)) {
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
	        // CASE 4: JOIN NODE - Multiple workflow attributes required (synchronization)
	        // ========================================================================
	        else if ("JoinNode".equals(nodeType)) {
	            logger.debug("ORCHESTRATOR: Processing JoinNode " + serviceName + ":" + operationName);
	            
	            // NEW: Extract joinCount from token ID encoding
	            // Token ID format from Fork: parentTokenId + (joinCount * 100) + branchNumber
	            // Example: 1000201 -> joinCount=2, branch=1, parent=1000000
	            int tokenRemainder = phaseSequenceID % 10000;
	            int tokenJoinCount = tokenRemainder / 100;
	            int branchNumber = tokenRemainder % 100;
	            int parentTokenId = phaseSequenceID - tokenRemainder;
	            
	            boolean hasEncodedJoinCount = (tokenJoinCount >= 2 && branchNumber >= 1 && branchNumber <= tokenJoinCount);
	            
	            if (hasEncodedJoinCount) {
	                logger.info("ORCHESTRATOR: Decoded token " + phaseSequenceID + 
	                           " -> joinCount=" + tokenJoinCount + ", branch=" + branchNumber + 
	                           ", parent=" + parentTokenId);
	            }
	            
	            // Use decoded joinCount if valid, otherwise fall back to inputCollection.size()
	            int expectedInputs = hasEncodedJoinCount ? tokenJoinCount : inputCollection.size();
	            
	            // STRICT VALIDATION: JoinNode must have multiple inputs
	            if (expectedInputs < 2) {
	                String error = String.format(
	                    "WORKFLOW DEFINITION ERROR: JoinNode %s:%s has only %d expected input(s).\n" +
	                    "JoinNode must have 2 or more inputs to synchronize parallel paths.\n" +
	                    "Fix: Either use EdgeNode for single input, or ensure Fork uses token ID encoding.",
	                    serviceName, operationName, expectedInputs
	                );
	                logger.error(error);
	                logger.error("SKIPPING EVENT - Token will time out naturally");
	                
	                // Clean up and skip this event
	                safeCleanupJoin(joinID);
	                return;
	            }
	            
	            // Store for use in isJoinComplete and processCompleteJoinSafely
	            this.petriNetJoinCount = hasEncodedJoinCount ? tokenJoinCount : -1;
	            
	            logger.info("ORCHESTRATOR: JoinNode " + serviceName + ":" + operationName + 
	                       " waiting for " + expectedInputs + " inputs" + 
	                       (hasEncodedJoinCount ? " (decoded from token ID)" : ": " + inputCollection));
	            
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
	                                        logger.debug("ORCHESTRATOR [SEQUENTIAL]: ⚠�? Complete join " + blockedKey
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
	 */
	private boolean isJoinComplete(Integer key) {
		ConcurrentSkipListMap<String, String> argVal = argValPriorityMap.get(key);
		if (argVal == null) {
			return false;
		}

		int currentSize = argVal.size();
		
		// PetriNet mode: use petriNetJoinCount (from token), SOA mode: use inputCollection.size()
		int requiredSize = (petriNetJoinCount > 0) ? petriNetJoinCount : inputCollection.size();
		boolean isPetriNetMode = (petriNetJoinCount > 0);

		if (currentSize >= requiredSize) {
			if (isPetriNetMode) {
				// PetriNet mode: just check count, not named inputs
				logger.debug("Join " + key + " complete in PetriNet mode: " + currentSize + "/" + requiredSize + " tokens");
				return true;
			} else {
				// SOA mode: check that all required named inputs are present
				for (String requiredInput : inputCollection) {
					if (!argVal.containsKey(requiredInput)) {
						logger.debug("Join " + key + " missing required input: " + requiredInput);
						return false;
					}
				}
				return true;
			}
		}

		return false;
	}


	/**
	 * Thread-safe method to process a complete join
	 * Preserves the lowest fork number AND workflowStartTime from all participating paths
	 * 
	 * PetriNet mode: Merges all incoming tokens into a single JSON payload
	 * SOA mode: Passes named inputs to multi-arg service method
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

	    // Populate input arguments - different handling for PetriNet vs SOA mode
	    inputArgs.clear();
	    boolean isPetriNetMode = (petriNetJoinCount > 0);
	    
	    if (isPetriNetMode) {
	        // PetriNet mode: Use the first/lowest token (simplest approach)
	        // All we needed was synchronization - all children arrived
	        String firstToken = argVal.values().iterator().next();
	        inputArgs.add(firstToken);
	        logger.info("ORCHESTRATOR: PetriNet mode - using first token from " + argVal.size() + " synchronized tokens");
	    } else {
	        // SOA mode: Pass named inputs to multi-arg method
	        for (String inputParam : inputCollection) {
	            String value = argVal.get(inputParam);
	            if (value == null) {
	                logger.error("ORCHESTRATOR: Missing input parameter " + inputParam + " for join " + joinKey);
	                return;
	            }
	            inputArgs.add(value);
	        }
	    }

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
	            + serviceName + "." + operationName + (isPetriNetMode ? " (PetriNet merged)" : ""));
	    logger.info("ORCHESTRATOR: Preserving lowest fork number: " + lowestForkNumber + 
	               " (base was " + joinKey + ")");
	    logger.info("ORCHESTRATOR: Preserving workflowStartTime: " + preservedWorkflowStartTime);

	    // Use the lowest fork number, NOT the base - this prevents rebasing
	    sequenceID = lowestForkNumber;
	    
	    // Override currentWorkflowStartTime with the preserved value
	    currentWorkflowStartTime = preservedWorkflowStartTime;
	    
	    headerMap.put("sequenceId", Integer.toString(lowestForkNumber));
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
					processDecisionType(serviceName, operationName, inputArgs);
					break;
				case "TerminateNode":
					processTerminateType(serviceName, operationName, inputArgs);
					break;
				case "XorNode":
					// XorNode - Conditional routing (picks path(s) based on decision value)
					processXorType(serviceName, operationName, inputArgs);
					break;
				case "GatewayNode":
					// GatewayNode - Dynamic routing strategy based on service response
					// Service returns: "FORK:target1,target2" or "EDGE:target"
					processGatewayType(serviceName, operationName, inputArgs);
					break;
				case "ForkNode":
					// ForkNode - Unconditional parallel split (ALL paths)
					processForkType(serviceName, operationName, inputArgs);
					break;
				case "FeedFwdNode":
					processFeedFwdNode(serviceName, operationName, inputArgs);
					break;
				case "EdgeNode":
					processEdgeType(serviceName, operationName, inputArgs);
					break;
				case "JoinNode":
					processJoinType(serviceName, operationName, inputArgs);
					break;
				case "MergeNode":
					// MergeNode is functionally identical to EdgeNode (single input XOR-MERGE)
					processEdgeType(serviceName, operationName, inputArgs);
					break;
				case "MonitorNode":
					processMonitorNodeType(sequenceID);
					break;
				case "Expired":
					processExpiredType(serviceName, operationName, inputArgs);
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

	private void processDecisionType(String service, String operation, ArrayList<?> inputArgs2) {
		String condition = null;
		String inference_condition = null;
		String val = null;

		try {
			logger.info("ORCHESTRATOR: Processing DecisionNode for " + service + "." + operation);

			ServiceHelper.ServiceResult serviceResult = callServiceWithCanonicalBinding(service, operation, inputArgs2, returnAttributeName);
			val = serviceResult.getResult();

			logger.info("ORCHESTRATOR: Service returned: " + (val != null ? val : "null"));

			if (val == null) {
				System.err.println("Invalid arguments or null return from service invocation.");
				return;
			}

			String returnType = serviceResult.getReturnType();
			String attributeValue = val;
			String comparisonValue = val;
			Double numericValue = null;
			boolean isJson = false;

			if ("String".equals(returnType)) {
				JSONObject parsedValue = jsonLibrary.parseString(val);

				if (parsedValue != null) {
					isJson = true;
					logger.debug("DECISION: Parsed JSON from service response");

					String routingPath = extractRoutingPath(parsedValue);

					if (routingPath != null) {
						attributeValue = routingPath;
						comparisonValue = routingPath;
						logger.info("DECISION: Extracted routing_path = '" + routingPath + "' from routing_decision");

					} else if (returnAttributeName != null && parsedValue.containsKey(returnAttributeName)) {
						attributeValue = parsedValue.get(returnAttributeName).toString();
						comparisonValue = attributeValue;
						logger.debug("DECISION: Using returnAttributeName '" + returnAttributeName + "' = "
								+ attributeValue);

					} else {
						logger.warn("DECISION: No routing_decision.routing_path found, using raw response");
					}

					try {
						numericValue = Double.parseDouble(attributeValue);
					} catch (NumberFormatException e) {
						logger.debug("DECISION: Attribute value is non-numeric: " + attributeValue);
					}
				}
			}

			for (int solutionIndex = 0; solutionIndex < decisionValueCollection.size(); solutionIndex++) {
				String nextCondition = nextServiceMap.get(solutionIndex);
				if (nextCondition == null) {
					logger.warn("No condition found for solutionIndex: " + solutionIndex);
					continue;
				}

				logger.debug("DECISION: Comparing '" + comparisonValue + "' with decision value '"
						+ decisionValueCollection.get(solutionIndex) + "' for condition " + nextCondition);

				if (returnType == null) {
					comparisonValue = String.valueOf(comparisonValue);
					if (guardsMatch(decisionValueCollection.get(solutionIndex), comparisonValue))
						condition = DECISION_EQUAL_TO;
					else
						condition = DECISION_NOT_EQUAL;
				} else {
					switch (returnType) {
					case "String":
						if (isJson && numericValue != null && (nextCondition.equals(DECISION_LESS_THAN)
								|| nextCondition.equals(DECISION_GREATER_THAN))) {
							double decisionValue;
							try {
								String cleanDecisionValue = cleanQuotes(decisionValueCollection.get(solutionIndex));
								decisionValue = Double.parseDouble(cleanDecisionValue);
							} catch (NumberFormatException e) {
								logger.debug("Skipping non-numeric decision value: "
										+ decisionValueCollection.get(solutionIndex));
								continue;
							}
							if (numericValue < decisionValue)
								condition = DECISION_LESS_THAN;
							else if (numericValue > decisionValue)
								condition = DECISION_GREATER_THAN;
							else
								condition = DECISION_EQUAL_TO;
							inference_condition = condition.equals(DECISION_EQUAL_TO) ? null : DECISION_NOT_EQUAL;
						} else {
							String cleanAttributeValue = cleanQuotes(comparisonValue);
							String cleanDecisionValue = cleanQuotes(decisionValueCollection.get(solutionIndex));

							logger.debug("DECISION: String comparison - '" + cleanAttributeValue + "' vs '"
									+ cleanDecisionValue + "'");

							// Use guardsMatch for equality (case-insensitive for true/false only)
							if (guardsMatch(cleanAttributeValue, cleanDecisionValue)) {
								condition = DECISION_EQUAL_TO;
								inference_condition = null;
								logger.info("DECISION: MATCH FOUND - '" + cleanAttributeValue + "' equals '"
										+ cleanDecisionValue + "'");
							} else {
								// For non-equal, use standard comparison for ordering
								int comparisonResult = cleanAttributeValue.compareTo(cleanDecisionValue);
								if (comparisonResult > 0) {
									condition = DECISION_GREATER_THAN;
								} else {
									condition = DECISION_LESS_THAN;
								}
								inference_condition = DECISION_NOT_EQUAL;
							}
						}
						break;
					case "boolean":
						boolean bval = Boolean.parseBoolean(comparisonValue);
						boolean bdval = Boolean.parseBoolean(decisionValueCollection.get(solutionIndex));
						if (bval == bdval)
							condition = DECISION_TRUE;
						if (bval != bdval)
							condition = DECISION_FALSE;
						comparisonValue = String.valueOf(bval);
						break;
					case "int":
						int ival = Integer.parseInt(comparisonValue);
						int idval = Integer.parseInt(decisionValueCollection.get(solutionIndex));
						comparisonValue = String.valueOf(ival);
						if (ival > idval)
							condition = DECISION_GREATER_THAN;
						if (ival == idval)
							condition = DECISION_EQUAL_TO;
						if (ival < idval)
							condition = DECISION_LESS_THAN;
						break;
					case "long":
						long lval = Long.parseLong(comparisonValue);
						Long ldvalObject = Long.valueOf(decisionValueCollection.get(solutionIndex));
						long ldval = ldvalObject;
						comparisonValue = String.valueOf(lval);
						if (lval > ldval)
							condition = DECISION_GREATER_THAN;
						if (lval == ldval)
							condition = DECISION_EQUAL_TO;
						if (lval < ldval)
							condition = DECISION_LESS_THAN;
						break;
					case "double":
						Double dvalObject = Double.valueOf(comparisonValue);
						double dval = dvalObject;
						double ddval = Double.parseDouble(decisionValueCollection.get(solutionIndex));
						comparisonValue = String.valueOf(dval);
						if (dval > ddval)
							condition = DECISION_GREATER_THAN;
						if (dval == ddval)
							condition = DECISION_EQUAL_TO;
						if (dval < ddval)
							condition = DECISION_LESS_THAN;
						break;
					default:
						comparisonValue = String.valueOf(comparisonValue);
						if (guardsMatch(decisionValueCollection.get(solutionIndex), comparisonValue))
							condition = DECISION_EQUAL_TO;
						else
							condition = DECISION_NOT_EQUAL;
						break;
					}
				}

				logger.debug("DECISION: Condition determined: " + condition + ", Expected: "
						+ nextServiceMap.get(solutionIndex));

				if (inference_condition != null) {
					if (condition.equals(nextServiceMap.get(solutionIndex))) {
						logger.info("DECISION: ROUTING MATCH - Condition " + condition + " matches expected "
								+ nextServiceMap.get(solutionIndex));
						setAttributeValidity(returnAttributeName, attributeValue);
						callNextOperation(attributeValue, solutionIndex, false);
						return;
					} else {
						if (nextServiceMap.get(solutionIndex) != null) {
							if (condition.equals(nextServiceMap.get(solutionIndex))) {
								logger.info("DECISION: ROUTING MATCH - Condition " + condition + " matches expected "
										+ nextServiceMap.get(solutionIndex));
								setAttributeValidity(returnAttributeName, attributeValue);
								callNextOperation(attributeValue, solutionIndex, false);
								return;
							}
						} else {
							logger.debug("In Service: " + serviceName + " : Ignoring based on decision outcome. "
									+ nodeType);
							return;
						}
					}
				} else {
					if (nextServiceMap.get(solutionIndex) != null) {
						if (condition.equals(nextServiceMap.get(solutionIndex))) {
							logger.info("DECISION: ROUTING MATCH - Condition " + condition + " matches expected "
									+ nextServiceMap.get(solutionIndex));
							setAttributeValidity(returnAttributeName, attributeValue);
							callNextOperation(attributeValue, solutionIndex, false);
							return;
						}
					} else {
						System.err.println("Ignoring based on decision outcome or missing nextService. " + nodeType);
						return;
					}
				}
			}

			System.err.println("DECISION: No matching condition found.");
			System.err.println("  Comparison value: '" + comparisonValue + "'");
			System.err.println("  Available decision values: " + decisionValueCollection.values());
			System.err.println("  Available conditions: " + nextServiceMap.values());

		} catch (RuntimeException e) {
			e.printStackTrace();
			System.err.println("Decision Node error processing evaluation.");
		}
	}

	private String extractRoutingPath(JSONObject jsonObj) {
		try {
			if (jsonObj == null) {
				return null;
			}

			JSONObject routingDecision = findRoutingDecisionObject(jsonObj);

			if (routingDecision != null && routingDecision.containsKey("routing_path")) {
				String routingPath = routingDecision.get("routing_path").toString();
				logger.debug("DECISION: Found routing_path = '" + routingPath + "' in routing_decision");
				return routingPath;
			}

			logger.debug("DECISION: No routing_decision.routing_path found in JSON structure");
			return null;

		} catch (Exception e) {
			logger.error("DECISION: Error extracting routing_path from JSON: " + e.getMessage());
			return null;
		}
	}

	private JSONObject findRoutingDecisionObject(JSONObject jsonObj) {
		try {
			if (jsonObj == null) {
				return null;
			}

			if (jsonObj.containsKey("routing_decision")) {
				Object routingDecisionObj = jsonObj.get("routing_decision");
				if (routingDecisionObj instanceof JSONObject) {
					logger.debug("DECISION: Found routing_decision object");
					return (JSONObject) routingDecisionObj;
				}
			}

			for (Object key : jsonObj.keySet()) {
				Object value = jsonObj.get(key);

				if (value instanceof JSONObject) {
					JSONObject nestedRoutingDecision = findRoutingDecisionObject((JSONObject) value);
					if (nestedRoutingDecision != null) {
						return nestedRoutingDecision;
					}
				}
			}

			return null;

		} catch (Exception e) {
			logger.error("DECISION: Error searching for routing_decision object: " + e.getMessage());
			return null;
		}
	}

	/**
	 * Process JoinNode - Synchronizes parallel execution paths  JOIN STRATEGY
	 * 
	 * <p>When multiple forked paths converge at a join point, this method:
	 * <ol>
	 *   <li>Executes the join service with all collected input attributes</li>
	 *   <li>Preserves the LOWEST fork number from participating paths</li>
	 *   <li>Routes the result to the next workflow step</li>
	 * </ol>
	 * 
	 * <p><b>Critical Join Coordination Rule:</b>
	 * Fork numbers MUST be preserved through joins. When paths with sequenceIDs
	 * 100001 and 100002 join, the result continues as 100001 (lowest fork number),
	 * NOT rebased to 100000 (base). This ensures all workflow paths maintain unique
	 * identifiers while sharing the same join coordination key.
	 * 
	 * <p>Example:
	 * <pre>
	 * Fork: 100000 → {100001(A), 100002(B), 100003(C)}
	 * Join A+B: 100001, 100002 → continues as 100001
	 * Join result+C: 100001, 100003 → continues as 100001
	 * </pre>
	 * 
	 * <p>The join has already been validated as complete by {@link #processCompleteJoinSafely(Integer)},
	 * which selected the lowest fork number and set it in {@code sequenceID}. This method
	 * executes the join service and routes to the next operation while maintaining that
	 * fork identity.
	 * 
	 * @param service The join service name to execute
	 * @param operation The operation name to invoke
	 * @param sargs The collected input arguments from all participating forks
	 * 
	 * @see #processCompleteJoinSafely(Integer) for fork number selection logic
	 * @see #mapFromSequenceID(Integer) for join key calculation
	 */
	private void processJoinType(String service, String operation, ArrayList<?> sargs) {
	    logger.info("ORCHESTRATOR: Processing JoinNode for " + service + "." + operation);

	    ServiceHelper.ServiceResult serviceResult = callServiceWithCanonicalBinding(service, operation, sargs, returnAttributeName);
	    String val = serviceResult.getResult();
	    logger.info("ORCHESTRATOR: Service returned: " + (val != null ? val : "null"));

	    if (val == null) {
	        return;
	    }

	    int solutionIndex = 0;
	    setAttributeValidity(returnAttributeName, val);
	    
	    // sequenceID already contains the lowest fork number from processCompleteJoinSafely()
	    // Pass it through unchanged to maintain fork coordination
	    callNextOperation(val, solutionIndex, false);
	}

	private void setAttributeValidity(String returnAttrName, String returnAttrValue) {
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

	// ========================================================================
	// XorNode Processing - Conditional Routing (NEW)
	// ========================================================================

	/**
	 * Process XorNode - Conditional routing gateway (XOR or Fork depending on guards)
	 * 
	 * FIXED: Now evaluates EACH branch individually against its own (condition_type, expected_value).
	 * 
	 * Previous bug: Only compared routing to the FIRST decision value, then found ALL services
	 * matching that condition TYPE. This failed when branches had different expected values.
	 * 
	 * Correct behavior:
	 * - For each branch, evaluate: does routing satisfy (condition_type, expected_value)?
	 *   - DECISION_EQUAL_TO: routing == expected_value
	 *   - DECISION_NOT_EQUAL: routing != expected_value
	 * - Fire all branches where condition is satisfied
	 * 
	 * This supports both XOR (one match) and Fork patterns (multiple matches) correctly.
	 */
	private void processXorType(String service, String operation, ArrayList<?> sargs) {
		logger.info("ORCHESTRATOR: Processing XorNode for " + service + "." + operation);
		logger.info("XOR-DIAG: === START XOR ROUTING ===");

		reloadKnowledgeBase();

		ServiceHelper.ServiceResult serviceResult = callServiceWithCanonicalBinding(service, operation, sargs, returnAttributeName);
		String val = serviceResult.getResult();
		logger.info("ORCHESTRATOR: Service returned: " + (val != null ? val : "null"));

		if (val == null) {
			return;
		}

		String routingPath = extractRoutingDecision(serviceResult);
		if (routingPath == null) {
			logger.error("XOR: Could not extract routing decision from service response");
			return;
		}
		
		String cleanRoutingPath = cleanQuotes(routingPath);
		logger.info("XOR-DIAG: Routing decision = '" + cleanRoutingPath + "'");
		
		// Log collection sizes for alignment debugging
		logger.info("XOR-DIAG: Collection sizes: services=" + nextServiceCollection.size() + 
		           ", conditions=" + nextServiceMap.size() + 
		           ", decisionValues=" + decisionValueCollection.size());
		
		// Check for potential misalignment
		if (nextServiceCollection.size() != decisionValueCollection.size()) {
			logger.warn("XOR-DIAG: WARNING - Collection sizes don't match! May have alignment issues.");
			logger.warn("XOR-DIAG: nextServiceCollection=" + nextServiceCollection);
			logger.warn("XOR-DIAG: decisionValueCollection=" + decisionValueCollection.values());
		}

		logger.info("XOR-DIAG: Evaluating " + nextServiceCollection.size() + " branches:");

		// FIXED: Evaluate EACH branch individually with its own condition and expected value
		List<Integer> matchingBranchIndices = new ArrayList<>();
		
		for (int i = 0; i < nextServiceCollection.size(); i++) {
			String branchConditionType = nextServiceMap.get(i);
			String branchExpectedValue = decisionValueCollection.get(i);
			
			if (branchConditionType == null) {
				logger.warn("XOR-DIAG: Branch " + i + " has no condition type, skipping");
				continue;
			}
			
			// If no expected value for this index, try first value as fallback
			if (branchExpectedValue == null && !decisionValueCollection.isEmpty()) {
				branchExpectedValue = decisionValueCollection.get(0);
				logger.warn("XOR-DIAG: Branch " + i + " missing expected value, using first: '" + branchExpectedValue + "'");
			}
			
			String cleanExpectedValue = branchExpectedValue != null ? cleanQuotes(branchExpectedValue) : null;
			
			// Evaluate this branch's condition
			boolean conditionSatisfied = evaluateGuardCondition(
				cleanRoutingPath, 
				cleanExpectedValue, 
				branchConditionType
			);
			
			logger.info("XOR-DIAG:   Branch " + i + " -> " + nextServiceCollection.get(i) + 
			           ": '" + cleanRoutingPath + "' " + branchConditionType + " '" + cleanExpectedValue + 
			           "' => " + (conditionSatisfied ? "MATCH" : "no match"));
			
			if (conditionSatisfied) {
				matchingBranchIndices.add(i);
			}
		}

		logger.info("XOR-DIAG: Total matching branches: " + matchingBranchIndices.size());

		if (matchingBranchIndices.isEmpty()) {
			logger.warn("XOR: No branches matched routing decision '" + cleanRoutingPath + "' - event dropped");
			logger.info("XOR-DIAG: === END XOR ROUTING (NO MATCH) ===");
			return;
		}

		setAttributeValidity(returnAttributeName, val);

		// Route to all matching branches
		// If only 1 match: XOR semantics (same token ID)
		// If multiple matches: Fork semantics (increment token IDs)
		boolean isMultiMatch = matchingBranchIndices.size() > 1;
		int forkIncrement = isMultiMatch ? 1 : 0;
		
		// PETRI NET FIX: For multi-match (fork), record T_out ONCE with PARENT token before loop
		boolean firstRoute = true;
		if (isMultiMatch && petriNetHelper != null && !matchingBranchIndices.isEmpty()) {
			int firstBranchIndex = matchingBranchIndices.get(0);
			String firstService = nextServiceCollection.get(firstBranchIndex);
			String firstOp = nextOperationCollection.get(firstBranchIndex);
			petriNetHelper.recordTokenExiting(sequenceID, this.serviceName,  // Parent token ID
			                                  firstService, firstOp,
			                                  nodeType, decisionValueCollection);
			logger.debug("XOR-FORK: Recorded single T_out transition for parent token " + sequenceID);
		}
		
		for (int branchIndex : matchingBranchIndices) {
			int newSequenceId = isMultiMatch ? (sequenceID + forkIncrement) : sequenceID;
			headerMap.put("sequenceId", Integer.toString(newSequenceId));
			
			String nextService = nextServiceCollection.get(branchIndex);
			String nextOp = nextOperationCollection.get(branchIndex);
			String channel = nextChannelCollection.get(branchIndex);
			String port = nextPortCollection.get(branchIndex);
			
			logger.info("XOR-DIAG: Routing to " + nextService + "." + nextOp + 
			           " with sequenceId=" + newSequenceId);
			
			serviceMap.put("serviceName", nextService);
			serviceMap.put("operation", nextOp);
			
			// PETRI NET: Record genealogy for fork children
			if (isMultiMatch && petriNetHelper != null) {
				String forkTransition = "T_out_" + this.serviceName;
				petriNetHelper.recordForkGenealogy(sequenceID, newSequenceId, forkTransition);
			}
			
			// PETRI NET FIX: Use NoRecord for multi-match (fork) since T_out already recorded
			// For single match, use normal method to record T_out
			if (isMultiMatch) {
				callNextOperationDirectNoRecord(nextService, nextOp, channel, port, val, channel);
			} else {
				callNextOperationDirect(nextService, nextOp, channel, port, val, channel);
			}
			
			if (isMultiMatch) {
				forkIncrement++;
			}
		}

		logger.info("XOR-DIAG: === END XOR ROUTING (" + matchingBranchIndices.size() + " branch(es)) ===");
	}

	/**
	 * Evaluate if a routing value satisfies a guard condition.
	 * 
	 * @param routingValue  The actual routing decision from the service
	 * @param expectedValue The value this branch expects
	 * @param conditionType The type of comparison (DECISION_EQUAL_TO, DECISION_NOT_EQUAL, etc.)
	 * @return true if the condition is satisfied
	 */
	private boolean evaluateGuardCondition(String routingValue, String expectedValue, String conditionType) {
		if (routingValue == null || conditionType == null) {
			return false;
		}
		
		// For conditions that compare against expected value
		if (expectedValue != null) {
			switch (conditionType) {
				case "DECISION_EQUAL_TO":
					return guardsMatch(routingValue, expectedValue);
					
				case "DECISION_NOT_EQUAL":
					return !guardsMatch(routingValue, expectedValue);
					
				case "DECISION_GREATER_THAN":
					try {
						return Double.parseDouble(routingValue) > Double.parseDouble(expectedValue);
					} catch (NumberFormatException e) {
						return routingValue.compareTo(expectedValue) > 0;
					}
					
				case "DECISION_LESS_THAN":
					try {
						return Double.parseDouble(routingValue) < Double.parseDouble(expectedValue);
					} catch (NumberFormatException e) {
						return routingValue.compareTo(expectedValue) < 0;
					}
			}
		}
		
		// For boolean conditions (no expected value comparison)
		switch (conditionType) {
			case "DECISION_TRUE":
				return Boolean.parseBoolean(routingValue);
				
			case "DECISION_FALSE":
				return !Boolean.parseBoolean(routingValue);
		}
		
		logger.warn("XOR: Unknown condition type: " + conditionType);
		return false;
	}

	// ========================================================================
	// GatewayNode Processing - Dynamic FORK/EDGE based on match count
	// ========================================================================

	/**
	 * Process GatewayNode - Dynamic routing based on decision_value matching
	 * 
	 * GatewayNode determines FORK vs EDGE based on how many destinations match
	 * the service's routing_path value against the decision_value in the rules.
	 * 
	 * Flow:
	 * 1. Service returns routing_path (e.g., "true" or "false")
	 * 2. Match routing_path against decision_value in meetsCondition rules
	 * 3. Multiple matches → FORK (create child tokens, expects JOIN downstream)
	 * 4. Single match → EDGE (same token continues, no JOIN needed)
	 * 
	 * Example workflow:
	 *   T_out_P1 → P2 (decision_value="true")
	 *   T_out_P1 → P3 (decision_value="true")
	 *   T_out_P1 → Monitor (decision_value="false")
	 * 
	 *   Service returns "true" → matches P2 and P3 → FORK to both
	 *   Service returns "false" → matches Monitor only → EDGE to Monitor
	 * 
	 * This keeps routing logic in infrastructure (ServiceThread) not in services.
	 */
	private void processGatewayType(String service, String operation, ArrayList<?> sargs) {
		logger.info("ORCHESTRATOR: Processing GatewayNode for " + service + "." + operation);
		logger.info("GATEWAY-DIAG: === START GATEWAY ROUTING ===");

		reloadKnowledgeBase();

		ServiceHelper.ServiceResult serviceResult = callServiceWithCanonicalBinding(service, operation, sargs, returnAttributeName);
		String val = serviceResult.getResult();
		logger.info("ORCHESTRATOR: Service returned: " + (val != null ? val : "null"));

		if (val == null) {
			logger.error("GATEWAY: Service returned null - cannot route");
			return;
		}

		// Extract routing_path from service response (e.g., "true" or "false")
		String routingPath = extractRoutingDecision(serviceResult);
		if (routingPath == null) {
			logger.error("GATEWAY: Could not extract routing_path from service response");
			return;
		}

		String cleanRoutingPath = cleanQuotes(routingPath);
		logger.info("GATEWAY-DIAG: Service routing_path = '" + cleanRoutingPath + "'");

		// Log available routes for debugging
		logger.info("GATEWAY-DIAG: Available routes: " + nextServiceCollection.size());
		for (int i = 0; i < nextServiceCollection.size(); i++) {
			String svc = nextServiceCollection.get(i);
			String decVal = decisionValueCollection.get(i);
			logger.info("GATEWAY-DIAG:   [" + i + "] " + svc + " (decision_value='" + decVal + "')");
		}

		// Find ALL destinations where decision_value matches routing_path
		List<Integer> matchingIndices = new ArrayList<>();
		for (int i = 0; i < nextServiceCollection.size(); i++) {
			String expectedValue = decisionValueCollection.get(i);
			String cleanExpected = expectedValue != null ? cleanQuotes(expectedValue) : "";
			
			if (guardsMatch(cleanRoutingPath, cleanExpected)) {
				matchingIndices.add(i);
				logger.info("GATEWAY-DIAG: MATCH - '" + cleanRoutingPath + "' == '" + cleanExpected + 
				           "' → " + nextServiceCollection.get(i));
			}
		}

		logger.info("GATEWAY-DIAG: Total matches: " + matchingIndices.size());

		if (matchingIndices.isEmpty()) {
			logger.error("GATEWAY: No destinations match routing_path '" + cleanRoutingPath + "' - token dropped");
			logger.info("GATEWAY-DIAG: === END GATEWAY ROUTING (NO MATCH) ===");
			return;
		}

		setAttributeValidity(returnAttributeName, val);

		// Determine routing strategy based on match count
		if (matchingIndices.size() > 1) {
			// Multiple matches → FORK (parallel split with child tokens)
			logger.info("GATEWAY-DIAG: Multiple matches (" + matchingIndices.size() + ") → FORK strategy");
			executeGatewayForkByIndices(matchingIndices, val);
		} else {
			// Single match → EDGE (same token continues)
			logger.info("GATEWAY-DIAG: Single match → EDGE strategy");
			executeGatewayEdgeByIndex(matchingIndices.get(0), val);
		}

		logger.info("GATEWAY-DIAG: === END GATEWAY ROUTING ===");
	}

	/**
	 * Execute FORK routing by indices - parallel split with child tokens
	 * 
	 * NEW TOKEN ID ENCODING SCHEME:
	 * Child token IDs encode the join count directly:
	 *   childTokenId = parentTokenId + (joinCount * 100) + branchNumber
	 * 
	 * Examples:
	 *   Parent 1000000, 2-way fork: children = 1000201, 1000202
	 *   Parent 1000000, 3-way fork: children = 1000301, 1000302, 1000303
	 * 
	 * At JoinNode, extract:
	 *   joinCount = (tokenId % 10000) / 100
	 *   branchNumber = tokenId % 100
	 *   parentTokenId = tokenId - (tokenId % 10000)
	 */
	private void executeGatewayForkByIndices(List<Integer> matchingIndices, String attributeValue) {
		int joinCount = matchingIndices.size();
		logger.info("GATEWAY-FORK: Parallel split to " + joinCount + " destinations");

		// Record T_out ONCE with PARENT token before forking
		if (petriNetHelper != null) {
			int firstIndex = matchingIndices.get(0);
			String firstService = nextServiceCollection.get(firstIndex);
			String firstOp = nextOperationCollection.get(firstIndex);
			petriNetHelper.recordTokenExiting(sequenceID, this.serviceName,
			                                  firstService, firstOp,
			                                  "GatewayNode", decisionValueCollection);
			logger.debug("GATEWAY-FORK: Recorded T_out for parent token " + sequenceID);
		}

		// Route to all matching destinations with child token IDs
		// NEW: childTokenId = parentTokenId + (joinCount * 100) + branchNumber
		int branchNumber = 1;
		for (int branchIndex : matchingIndices) {
			int childTokenId = sequenceID + (joinCount * 100) + branchNumber;
			headerMap.put("sequenceId", Integer.toString(childTokenId));

			String nextService = nextServiceCollection.get(branchIndex);
			String nextOp = nextOperationCollection.get(branchIndex);
			String channel = nextChannelCollection.get(branchIndex);
			String port = nextPortCollection.get(branchIndex);

			logger.info("GATEWAY-FORK: Routing child token " + childTokenId + " to " + nextService + "." + nextOp + 
			           " (encoded: joinCount=" + joinCount + ", branch=" + branchNumber + ")");

			serviceMap.put("serviceName", nextService);
			serviceMap.put("operation", nextOp);

			// Record genealogy for fork children
			if (petriNetHelper != null) {
				String forkTransition = "T_out_" + this.serviceName;
				petriNetHelper.recordForkGenealogy(sequenceID, childTokenId, forkTransition);
			}

			// Use NoRecord since T_out already recorded for parent
			// Pass original attributeValue - no joinCount injection needed (it's encoded in token ID)
			callNextOperationDirectNoRecord(nextService, nextOp, channel, port, attributeValue, channel);

			branchNumber++;
		}

		logger.info("GATEWAY-FORK: Created " + joinCount + " child tokens with encoded join info");
	}

	/**
	 * Execute EDGE routing by index - single path, same token ID
	 * Handles TERMINATE as a special case - records T_out and ends token lifecycle
	 */
	private void executeGatewayEdgeByIndex(int matchingIndex, String attributeValue) {
		String nextService = nextServiceCollection.get(matchingIndex);
		String nextOp = nextOperationCollection.get(matchingIndex);

		// Check for TERMINATE - end token lifecycle cleanly
		if ("TERMINATE".equals(nextService) && "TERMINATE".equals(nextOp)) {
			logger.info("GATEWAY-TERMINATE: Token " + sequenceID + " terminated via GatewayNode decision");
			
			// Record T_out for the terminating token
			if (petriNetHelper != null) {
				petriNetHelper.recordTokenExiting(sequenceID, this.serviceName,
				                                  "TERMINATE", "TERMINATE",
				                                  "GatewayNode", decisionValueCollection);
				logger.debug("GATEWAY-TERMINATE: Recorded T_out for terminated token " + sequenceID);
			}
			
			// Token lifecycle ends here - no further routing
			return;
		}

		String channel = nextChannelCollection.get(matchingIndex);
		String port = nextPortCollection.get(matchingIndex);

		logger.info("GATEWAY-EDGE: Single path to " + nextService + "." + nextOp + " with token " + sequenceID);

		serviceMap.put("serviceName", nextService);
		serviceMap.put("operation", nextOp);

		// Same token continues - use normal recording
		callNextOperationDirect(nextService, nextOp, channel, port, attributeValue, channel);
	}

	/**
	 * Process EdgeNode/MergeNode - Clean business logic only
	 * Both EdgeNode and MergeNode are single-input nodes (XOR-MERGE semantics)
	 */
	private void processEdgeType(String service, String operation, ArrayList<?> sargs) {
		logger.info("ORCHESTRATOR: Processing EdgeNode/MergeNode for " + service + "." + operation);

		// Normal service processing
		ServiceHelper.ServiceResult serviceResult = callServiceWithCanonicalBinding(service, operation, sargs, returnAttributeName);
		String val = serviceResult.getResult();
		logger.info("ORCHESTRATOR: Service returned: " + (val != null ? val : "null"));

		int solutionIndex = 0;
		setAttributeValidity(returnAttributeName, val);
		callNextOperation(val, solutionIndex, false);
	}

	private void processMonitorNodeType(int sequenceID) {
		logger.info("ORCHESTRATOR: Monitor node - sequenceID = " + sequenceID);

		try {
			long processStartTime = Long.parseLong(monitorDataMap.get("processStartTime"));
			long eventArrivalTime = Long.parseLong(monitorDataMap.get("eventArrivalTime"));
			long currentTime = System.currentTimeMillis();

			long elapsedTime = currentTime - processStartTime;
			long totalProcessTime = currentTime - eventArrivalTime;

			logger.info("=== MONITOR NODE TIMING ===");
			logger.info("Sequence ID: " + sequenceID);
			logger.info("Process Start Time: " + processStartTime);
			logger.info("Event Arrival Time: " + eventArrivalTime);
			logger.info("Current Time: " + currentTime);
			logger.info("Elapsed Time: " + elapsedTime + "ms");
			logger.info("Total Process Time: " + totalProcessTime + "ms");
			logger.info("Lost Events: " + monitorDataMap.get("lostEvents"));
			logger.info("===========================");

		} catch (Exception e) {
			logger.error("Error processing monitor node for sequenceID: " + sequenceID, e);
		}
	}

	private void processExpiredType(String service, String operation, ArrayList<?> sargs) {
		setAttributeValidity(returnAttributeName, "null");
		callNextOperation("null", 0, false);
	}

	// ========================================================================
	// ForkNode Processing - Unconditional Parallel Split (FIXED)
	// ========================================================================

	/**
	 * Process ForkNode - Unconditional parallel split (Parallel Gateway)
	 * 
	 * FIXED: ForkNode now routes to ALL configured outgoing services unconditionally.
	 * This is the "parallel gateway" or "AND-split" in BPMN terms.
	 * 
	 * Key differences from XorNode:
	 * - NO condition evaluation - routes to ALL paths
	 * - NO decision values checked
	 * - Always increments fork numbers for each parallel path
	 * 
	 * Flow:
	 * 1. Optionally invoke transformation service (NOT for routing decisions)
	 * 2. Collect ALL configured parallel services
	 * 3. Route to EVERY service unconditionally
	 */
	private void processForkType(String service, String operation, ArrayList<?> sargs) {
		logger.info("ORCHESTRATOR: Processing ForkNode (Parallel Gateway) for " + service + "." + operation);

		reloadKnowledgeBase();

		// Optional: invoke transformation service if configured
		// This is NOT for routing decisions, just data transformation
		String val = null;
		if (service != null && !service.isEmpty() && !service.equals("null")) {
			ServiceHelper.ServiceResult serviceResult = callServiceWithCanonicalBinding(service, operation, sargs, returnAttributeName);
			val = serviceResult.getResult();
			logger.info("FORK: Transformation service returned: " + (val != null ? val : "null"));
		} else {
			// No transformation service - use input directly
			val = sargs != null && !sargs.isEmpty() ? sargs.get(0).toString() : "";
			logger.info("FORK: No transformation service - using input directly");
		}

		if (val == null) {
			val = "";
		}

		// Get ALL outgoing services - NO condition filtering
		List<ServiceRoute> allServices = collectParallelServices();

		if (allServices.isEmpty()) {
			logger.warn("FORK: No parallel services configured - event dropped");
			return;
		}

		setAttributeValidity(returnAttributeName, val);

		int joinCount = allServices.size();
		logger.info("FORK: Parallel split to " + joinCount + " service(s)");

		// PETRI NET FIX: Record T_out ONCE with PARENT token before forking
		// This ensures marking: +1 (T_in parent) -1 (T_out parent) = 0
		if (petriNetHelper != null && !allServices.isEmpty()) {
			ServiceRoute firstRoute = allServices.get(0);
			petriNetHelper.recordTokenExiting(sequenceID, this.serviceName,  // Parent token ID
			                                  firstRoute.serviceName, firstRoute.operationName,
			                                  nodeType, decisionValueCollection);
			logger.debug("FORK: Recorded single T_out transition for parent token " + sequenceID);
		}

		// Route to ALL services with NEW TOKEN ID ENCODING:
		// childTokenId = parentTokenId + (joinCount * 100) + branchNumber
		int branchNumber = 1;
		for (ServiceRoute route : allServices) {
			int childTokenId = sequenceID + (joinCount * 100) + branchNumber;
			headerMap.put("sequenceId", Integer.toString(childTokenId));
			
			logger.info("FORK: Routing child token " + childTokenId + " to " + route.serviceName + 
			           " (encoded: joinCount=" + joinCount + ", branch=" + branchNumber + ")");
			
			// PETRI NET: Record genealogy for fork children
			if (petriNetHelper != null) {
				String forkTransition = "T_out_" + this.serviceName;
				petriNetHelper.recordForkGenealogy(sequenceID, childTokenId, forkTransition);
			}
			
			// Pass original val - no joinCount injection needed (it's encoded in token ID)
			routeToServiceNoRecord(route, val);
			branchNumber++;
		}

		logger.info("FORK: Parallel routing complete - " + joinCount + " child tokens with encoded join info");
	}

	/**
	 * Collect all services for parallel split - NO condition filtering
	 * 
	 * Queries for parallelSplit(service, operation) predicates in knowledge base.
	 * Unlike XOR which uses meetsCondition with guards, Fork uses unconditional routing.
	 * 
	 * Fallback: If no parallelSplit predicates found, collects ALL meetsCondition
	 * services regardless of condition (for backward compatibility).
	 */
	private List<ServiceRoute> collectParallelServices() {
		List<ServiceRoute> services = new ArrayList<>();

		// Query for parallelSplit predicates - no condition parameter
		String query = "<Query><Atom><Rel>parallelSplit</Rel>" + 
		               "<Var>service</Var><Var>operation</Var></Atom></Query>";

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
				logger.info("FORK: Collected parallel service " + route.serviceName + "." + route.operationName);
			}

			hasNext = oojdrew.hasNext;
			if (hasNext) {
				oojdrew.nextSolution();
			}
		}

		// Fallback: If no parallelSplit predicates, try meetsCondition with all conditions
		if (services.isEmpty()) {
			logger.info("FORK: No parallelSplit predicates found, trying meetsCondition fallback");
			
			// Try to get all services regardless of condition
			String fallbackQuery = "<Query><Atom><Rel>meetsCondition</Rel>" + 
			                       "<Var>service</Var><Var>operation</Var><Var>condition</Var></Atom></Query>";

			oojdrew.issueRuleMLQuery(fallbackQuery);

			hasNext = true;
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
					// Check if we already have this service (avoid duplicates)
					boolean exists = services.stream()
						.anyMatch(s -> s.serviceName.equals(route.serviceName) && 
						               s.operationName.equals(route.operationName));
					if (!exists) {
						services.add(route);
						logger.info("FORK: Collected (fallback) " + route.serviceName + "." + route.operationName);
					}
				}

				hasNext = oojdrew.hasNext;
				if (hasNext) {
					oojdrew.nextSolution();
				}
			}
		}

		return services;
	}

	/**
	 * Reload knowledge base for fork processing
	 */
	private void reloadKnowledgeBase() {
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

	private String extractRoutingDecision(ServiceHelper.ServiceResult serviceResult) {
		String val = serviceResult.getResult();

		if ("String".equals(serviceResult.getReturnType())) {
			JSONObject parsedValue = jsonLibrary.parseString(val);
			if (parsedValue != null) {
				String routingPath = extractRoutingPath(parsedValue);
				if (routingPath != null) {
					return routingPath;
				}

				if (returnAttributeName != null && parsedValue.containsKey(returnAttributeName)) {
					return parsedValue.get(returnAttributeName).toString();
				}
			}
		}

		return val;
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

	private static class ServiceRoute {
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

						callNextOperationDirect(route.serviceName, route.operationName, ipAddress, port, attributeValue,
								originalChannelId);

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

					callNextOperationDirect(route.serviceName, route.operationName, channel, port, attributeValue,
							channel);

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
	private void routeToServiceNoRecord(ServiceRoute route, String attributeValue) {
		if (checkAndRouteActiveServiceNoRecord(route, attributeValue)) {
			return;
		}
		routeViaPublishesNoRecord(route, attributeValue);
	}

	private boolean checkAndRouteActiveServiceNoRecord(ServiceRoute route, String attributeValue) {
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

						callNextOperationDirectNoRecord(route.serviceName, route.operationName, ipAddress, port, attributeValue,
								originalChannelId);

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

	private void routeViaPublishesNoRecord(ServiceRoute route, String attributeValue) {
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

					callNextOperationDirectNoRecord(route.serviceName, route.operationName, channel, port, attributeValue,
							channel);

					logger.info("FORK: Routed to " + route.serviceName + " via publishes on " + channel + ":" + port + " (no T_out record)");
				}
			}
		} catch (Exception e) {
			logger.error("FORK: Failed to route to " + route.serviceName + ": " + e.getMessage());
		}
	}

	/**
	 * Direct operation call WITHOUT recording T_out transition
	 * Used by FORK routing after T_out has been recorded once
	 */
	private void callNextOperationDirectNoRecord(String nextServiceName, String nextOperationName, String channel, String port,
	        String attributeValue, String originalChannelId) {
	    // Update attrMap with the provided attributeValue
	    if (attributeValue != null) {
	        attrMap.put("attributeValue", attributeValue);
	    }
	    
	    outgoingXMLPayLoad = xph.modifyMultipleXMLItems(outgoingXMLPayLoad, "//header/*", headerMap);
	    outgoingXMLPayLoad = xph.modifyMultipleXMLItems(outgoingXMLPayLoad, "//service/*", serviceMap);
	    outgoingXMLPayLoad = xph.modifyMultipleXMLItems(outgoingXMLPayLoad, "//joinAttribute/*", attrMap);

	    // NOTE: No petriNetHelper.recordTokenExiting() call - T_out already recorded for parent

	    boolean published = eventPublisher.publishServiceEvent(nextServiceName, nextOperationName, outgoingXMLPayLoad, channel,
	            port, ruleBaseVersion, originalChannelId);

	    if (!published) {
	        logger.error("FORK: Failed to publish to " + nextServiceName);
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

	private void callNextOperationDirect(String nextServiceName, String nextOperationName, String channel, String port,
	        String attributeValue, String originalChannelId) {
	    outgoingXMLPayLoad = xph.modifyMultipleXMLItems(outgoingXMLPayLoad, "//header/*", headerMap);
	    outgoingXMLPayLoad = xph.modifyMultipleXMLItems(outgoingXMLPayLoad, "//service/*", serviceMap);
	    outgoingXMLPayLoad = xph.modifyMultipleXMLItems(outgoingXMLPayLoad, "//joinAttribute/*", attrMap);

	    // PETRI NET: Record token exiting with OUTGOING token ID (may differ after FORK)
	    if (petriNetHelper != null) {
	        // FIX: Use headerMap sequenceId (forked ID), not phaseSequenceID (parent ID)
	        int outgoingTokenId = Integer.parseInt(headerMap.get("sequenceId"));
	        petriNetHelper.recordTokenExiting(outgoingTokenId, this.serviceName, 
	                                          nextServiceName, nextOperationName,
	                                          nodeType, decisionValueCollection);
	    }

	    boolean published = eventPublisher.publishServiceEvent(nextServiceName, nextOperationName, outgoingXMLPayLoad, channel,
	            port, ruleBaseVersion, originalChannelId);

	    if (!published) {
	        logger.error("FORK: Failed to publish to " + nextServiceName);
	    }
	}

	private void processFeedFwdNode(String service, String operation, ArrayList<?> sargs) {
		logger.info("ORCHESTRATOR: Invoking " + service + "." + operation + " via ServiceHelper");
		String fullClassName = SERVICE_PACKAGE + "." + service;
		
		// Safety check: ruleBaseVersion should be set from event header
		if (ruleBaseVersion == null) {
			logger.error("CRITICAL: ruleBaseVersion is null - event missing version header");
			throw new RuntimeException("Cannot invoke service without ruleBaseVersion");
		}
		
		// UPDATED: Pass returnAttributeName for token enrichment
		ServiceHelper.ServiceResult serviceResult = serviceHelper.process(sequenceID.toString(), fullClassName,
				operation, sargs, returnAttributeName, ruleBaseVersion);
		String val = serviceResult.getResult();
		logger.info("ORCHESTRATOR: Service returned: " + (val != null ? val : "null"));
		if (val == null)
			return;
		int fid = 1;
		int solutionIndex = 0;
		setAttributeValidity(returnAttributeName, val);
		headerMap.put("sequenceId", Integer.toString(sequenceID + fid));
		callNextOperation(val, solutionIndex, false);
	}

	/**
	 * Process TerminateNode - Executes service (Place) then records T_out termination
	 * 
	 * Flow: T_in -> Place (service execution) -> T_out (terminate)
	 * The service IS invoked, then the token lifecycle ends cleanly with T_out recorded.
	 */
	private void processTerminateType(String service, String operation, ArrayList<?> inputArgs2) {
		String val = null;
		try {
			// Normal service invocation - this is the Place (P) processing
			logger.info("ORCHESTRATOR: Invoking " + service + "." + operation + " via ServiceHelper");
			String fullClassName = SERVICE_PACKAGE + "." + service;
			
			// Safety check: ruleBaseVersion should be set from event header
			if (ruleBaseVersion == null) {
				logger.error("CRITICAL: ruleBaseVersion is null - event missing version header");
				throw new RuntimeException("Cannot invoke service without ruleBaseVersion");
			}
			
			// UPDATED: Pass returnAttributeName for token enrichment
			ServiceHelper.ServiceResult serviceResult = serviceHelper.process(sequenceID.toString(),
					fullClassName, operation, inputArgs2, returnAttributeName, ruleBaseVersion);
			val = serviceResult.getResult();
			logger.info("ORCHESTRATOR: Service returned: " + (val != null ? val : "null"));
			
			// Record T_out for Petri Net instrumentation - token lifecycle ends here
			if (petriNetHelper != null) {
				petriNetHelper.recordTokenExiting(sequenceID, this.serviceName,
				                                  "TERMINATE", "TERMINATE",
				                                  "TerminateNode", decisionValueCollection);
				logger.info("TERMINATE: Recorded T_out for token " + sequenceID + " after " + service + " processing");
			}
			
			logger.info("ORCHESTRATOR: Token " + sequenceID + " terminated after " + service + "." + operation);
			
		} catch (Exception e) {
			e.printStackTrace();
			System.err.println("Process Termination ->Failed to invoke service: " + service + ":" + operation);
		}
		logger.debug("Processing Terminate Type complete.");
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
	
	
	private ServiceHelper.ServiceResult callServiceWithCanonicalBinding(String service, String operation,
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
	 * Get JoinInputCount for PetriNet mode JoinNodes.
	 * This tells the orchestrator how many child tokens to wait for before merging.
	 * 
	 * For PetriNet mode, the service uses a single-arg processToken() method,
	 * but the orchestrator merges N tokens before calling it.
	 * 
	 * @return join input count, or -1 if not specified (use canonical binding count)
	 */
	private int joinInputCount = -1;  // Instance variable to store join input count (from rules)
	private int petriNetJoinCount = -1;  // Instance variable to store join count from token payload (PetriNet mode)
	
	public void getJoinInputCount() {
		joinInputCount = -1;  // Reset
		oojdrew.issueRuleMLQuery("<Query><Atom><Rel>JoinInputCount</Rel><Var>count</Var></Atom></Query>");
		try {
			if (oojdrew.rowsReturned > 0) {
				for (int i = 0; i < oojdrew.rowsReturned; i++) {
					if ("?count".equals(oojdrew.rowData[i][0].toString())) {
						joinInputCount = Integer.parseInt(oojdrew.rowData[i][1].toString());
						logger.info("ORCHESTRATOR: JoinInputCount from rules: " + joinInputCount + " (PetriNet mode)");
					}
				}
			}
		} catch (Exception e) {
			logger.debug("No JoinInputCount in rules (SOA mode or non-JoinNode)");
			joinInputCount = -1;
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

	public void getRuleBaseVersion() {
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

	private void callNextOperation(String val, int solutionIndex, boolean expired) {
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

			// PETRI NET: Record token exiting with OUTGOING token ID
	        if (petriNetHelper != null) {
	            // FIX: Use headerMap sequenceId (forked ID), not phaseSequenceID (parent ID)
	            int outgoingTokenId = Integer.parseInt(headerMap.get("sequenceId"));
	            petriNetHelper.recordTokenExiting(outgoingTokenId, serviceName, 
	                                              nextServiceName, nextOperationName,
	                                              nodeType, decisionValueCollection);
	        }

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
		// NEW: Token ID encoding is parentTokenId + (joinCount * 100) + branchNumber
		// Example: 1000201 -> parent is 1000000
		// Use % 10000 to strip off the encoded join info
		int rem = sqID % 10000;
		return sqID - rem;
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
	private boolean guardsMatch(String actual, String expected) {
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
}