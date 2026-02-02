package org.btsn.handlers;

import java.io.File;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

import org.apache.log4j.Logger;
import org.btsn.utils.BuildRuleBase;
import org.btsn.utils.OOjdrewAPI;
import org.btsn.utils.StringFileIO;
import org.btsn.utils.BuildRuleBase.FileExtensionFilter;

// Deployment mode enum
enum DeploymentMode {
    LOCAL,
    REMOTE
}

/**
 * Rule-Driven ServiceLoader with Remote Host Support and Configurable Version
 * FIXED: Properly checks activeService facts BEFORE using default channels
 * UPDATED: Build version now configurable via command-line arguments
 * UPDATED: Added graceful shutdown mechanism via file watcher and shutdown port
 */
public class ServiceLoader {
	private OOjdrewAPI oojdrew;
	private static String buildVersion = null;  // Must be explicitly provided via command line
	private static final List<String> VALID_RULE_SET = new ArrayList<>();
	public static final Logger logger = Logger.getLogger(ServiceLoader.class);

	private static final String SERVICE_LOADER_QUERIES_DIRECTORY = "ServiceLoaderQueries";
	
	// Track if we're running as a remote service host
	private static boolean isRemoteMode = false;
	private static String remoteHostAddress = null;

	// ==================== SHUTDOWN MECHANISM ====================
	// Flag to signal all threads to stop
	private static final AtomicBoolean shutdownRequested = new AtomicBoolean(false);
	
	// Track all spawned threads for cleanup
	private static final CopyOnWriteArrayList<Thread> managedThreads = new CopyOnWriteArrayList<>();
	
	// Shutdown control file - delete this file to trigger shutdown
	private static File shutdownControlFile = null;
	
	// Shutdown listener port (base + version offset)
	private static final int SHUTDOWN_PORT_BASE = 39000;
	private static DatagramSocket shutdownSocket = null;
	
	/**
	 * Check if shutdown has been requested
	 */
	public static boolean isShutdownRequested() {
		return shutdownRequested.get();
	}
	
	/**
	 * Request shutdown of all services
	 */
	public static void requestShutdown() {
		logger.info("=== SHUTDOWN REQUESTED ===");
		shutdownRequested.set(true);
		
		// Interrupt all managed threads
		for (Thread t : managedThreads) {
			if (t.isAlive()) {
				logger.info("Interrupting thread: " + t.getName());
				t.interrupt();
			}
		}
		
		// Close shutdown socket
		if (shutdownSocket != null && !shutdownSocket.isClosed()) {
			shutdownSocket.close();
		}
		
		// Clean up control file
		if (shutdownControlFile != null && shutdownControlFile.exists()) {
			shutdownControlFile.delete();
		}
	}
	
	/**
	 * Register a thread for management (shutdown tracking)
	 */
	public static void registerThread(Thread t) {
		managedThreads.add(t);
	}
	
	/**
	 * Start the shutdown file watcher thread
	 */
	private static void startShutdownFileWatcher() {
		try {
			// Create a control file that signals "running"
			shutdownControlFile = new File("service_" + buildVersion + ".running");
			shutdownControlFile.createNewFile();
			shutdownControlFile.deleteOnExit();
			
			logger.info("Created shutdown control file: " + shutdownControlFile.getAbsolutePath());
			logger.info("DELETE this file to trigger graceful shutdown");
			
			Thread watcherThread = new Thread(() -> {
				while (!shutdownRequested.get()) {
					try {
						Thread.sleep(2000); // Check every 2 seconds
						
						if (!shutdownControlFile.exists()) {
							logger.info("Shutdown control file deleted - initiating shutdown");
							requestShutdown();
							break;
						}
					} catch (InterruptedException e) {
						break;
					}
				}
			}, "ShutdownFileWatcher-" + buildVersion);
			
			watcherThread.setDaemon(true);
			watcherThread.start();
			
		} catch (IOException e) {
			logger.warn("Could not create shutdown control file: " + e.getMessage());
		}
	}
	
	/**
	 * Start UDP shutdown listener
	 */
	private static void startShutdownListener() {
		try {
			int versionNum = extractVersionNumber(buildVersion);
			int shutdownPort = SHUTDOWN_PORT_BASE + versionNum;
			
			shutdownSocket = new DatagramSocket(shutdownPort);
			
			logger.info("Shutdown listener started on port " + shutdownPort);
			logger.info("Send 'SHUTDOWN' to this port to stop services");
			
			Thread listenerThread = new Thread(() -> {
				byte[] buffer = new byte[256];
				
				while (!shutdownRequested.get()) {
					try {
						DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
						shutdownSocket.receive(packet);
						
						String message = new String(packet.getData(), 0, packet.getLength()).trim();
						
						if ("SHUTDOWN".equalsIgnoreCase(message)) {
							logger.info("Received SHUTDOWN command via UDP");
							requestShutdown();
							break;
						}
					} catch (IOException e) {
						if (!shutdownRequested.get()) {
							// Only log if not during shutdown
							logger.debug("Shutdown listener: " + e.getMessage());
						}
						break;
					}
				}
			}, "ShutdownListener-" + buildVersion);
			
			listenerThread.setDaemon(true);
			listenerThread.start();
			
		} catch (Exception e) {
			logger.warn("Could not start shutdown listener: " + e.getMessage());
		}
	}
	
	/**
	 * Extract version number from version string (e.g., "v001" -> 1)
	 */
	private static int extractVersionNumber(String version) {
		try {
			// Remove 'v' prefix and parse
			String numPart = version.replaceAll("[^0-9]", "");
			return Integer.parseInt(numPart);
		} catch (Exception e) {
			return 0;
		}
	}
	
	/**
	 * Install JVM shutdown hook for cleanup
	 */
	private static void installShutdownHook() {
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			logger.info("=== JVM SHUTDOWN HOOK TRIGGERED ===");
			requestShutdown();
			
			// Give threads a moment to clean up
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				// Ignore
			}
			
			logger.info("=== ServiceLoader shutdown complete ===");
		}, "ServiceLoader-ShutdownHook"));
	}
	// ==================== END SHUTDOWN MECHANISM ====================

	/**
	 * Parse command-line arguments to extract version and other parameters
	 */
	private static void parseArguments(String[] args) {
		
	    for (int i = 0; i < args.length; i++) {
	        if ("-version".equals(args[i]) && i + 1 < args.length) {
	            buildVersion = args[i + 1];
	            break;
	        }
	    }
	    
	    if (buildVersion == null) {
	        logger.fatal("*** BUILD VERSION REQUIRED - Use: -version v001 ***");
	        System.exit(1);
	    }
	}
	
	
	/**
	 * Auto-detect if this host should run in remote mode
	 */
	private static void checkRemoteMode() {
	    logger.info("=== Auto-detecting deployment mode from facts ===");
	    
	    try {
	        // Get all possible IPs for this machine
	        InetAddress localhost = InetAddress.getLocalHost();
	        String primaryIP = localhost.getHostAddress();
	        logger.info("This machine's primary IP: " + primaryIP);
	        
	        // Also get all network interfaces
	        java.util.Set<String> myIPs = new java.util.HashSet<>();
	        myIPs.add(primaryIP);
	        myIPs.add("127.0.0.1");
	        
	        try {
	            java.util.Enumeration<java.net.NetworkInterface> interfaces = 
	                java.net.NetworkInterface.getNetworkInterfaces();
	            while (interfaces.hasMoreElements()) {
	                java.net.NetworkInterface iface = interfaces.nextElement();
	                java.util.Enumeration<InetAddress> addresses = iface.getInetAddresses();
	                while (addresses.hasMoreElements()) {
	                    InetAddress addr = addresses.nextElement();
	                    myIPs.add(addr.getHostAddress());
	                }
	            }
	            logger.info("All IPs for this machine: " + myIPs);
	        } catch (Exception e) {
	            logger.debug("Could not enumerate all network interfaces: " + e.getMessage());
	        }
	        
	        // Use the configurable build version instead of hardcoded BUILD_VERSION
	        OOjdrewAPI checker = new OOjdrewAPI();
	        checker.parseKnowledgeBase("RuleFolder." + buildVersion + "/Service.ruleml", true);
	        
	        // Query ALL boundChannel facts to see if any map to our IP
	        String boundChannelQuery = "<Query><Atom><Rel>boundChannel</Rel>" +
	            "<Var>channelId</Var><Var>address</Var></Atom></Query>";
	        
	        checker.issueRuleMLQuery(boundChannelQuery);
	        
	        boolean foundOurIP = false;
	        String matchedChannel = null;
	        String matchedIP = null;
	        
	        // Check all boundChannel entries
	        while (checker.rowsReturned > 0) {
	            String channelId = null;
	            String address = null;
	            
	            for (int i = 0; i < checker.rowsReturned; i++) {
	                String key = String.valueOf(checker.rowData[i][0]);
	                String value = String.valueOf(checker.rowData[i][1]);
	                
	                if ("?channelId".equals(key)) {
	                    channelId = value;
	                } else if ("?address".equals(key)) {
	                    address = value;
	                }
	            }
	            
	            // Check if this address is one of ours
	            if (address != null && myIPs.contains(address)) {
	                logger.info("*** FOUND: Channel " + channelId + " maps to this machine at " + address + " ***");
	                
	                // Now check if any activeService uses this channel
	                String activeQuery = "<Query><Atom><Rel>activeService</Rel>" +
	                	    "<Var>service</Var><Var>operation</Var>" +
	                	    "<Ind>" + channelId + "</Ind><Var>port</Var></Atom></Query>";

	                	logger.info("Checking for services using channel: " + channelId);

	                	OOjdrewAPI activeChecker = new OOjdrewAPI();
	                	activeChecker.parseKnowledgeBase("RuleFolder." + buildVersion + "/Service.ruleml", true);
	                	activeChecker.issueRuleMLQuery(activeQuery);
	                	
	                	if (activeChecker.rowsReturned > 0) {
	                	    foundOurIP = true;
	                	    matchedChannel = channelId;
	                	    matchedIP = address;
	                	    logger.info("Found " + activeChecker.rowsReturned + " activeService entries using " + channelId);
	                	} else {
	                	    logger.info("Query returned 0 rows for channel " + channelId);
	                	    logger.info("Query was: " + activeQuery);
	                	}

	                	if (activeChecker.rowsReturned > 0) {
	                	    // Found services using this channel
	                	    foundOurIP = true;
	                	    matchedChannel = channelId;
	                	    matchedIP = address;
	                	    logger.info("Found " + activeChecker.rowsReturned + " activeService entries using " + channelId);
	                	}
	                while (activeChecker.rowsReturned > 0) {
	                    for (int i = 0; i < activeChecker.rowsReturned; i++) {
	                        String key = String.valueOf(activeChecker.rowData[i][0]);
	                        String value = String.valueOf(activeChecker.rowData[i][1]);
	                        
	                        if ("?channelId".equals(key) && value.equals(channelId)) {
	                            foundOurIP = true;
	                            matchedChannel = channelId;
	                            matchedIP = address;
	                            break;
	                        }
	                    }
	                    
	                    if (foundOurIP || !activeChecker.hasNext) break;
	                    activeChecker.nextSolution();
	                }
	            }
	            
	            if (checker.hasNext) {
	                checker.nextSolution();
	            } else {
	                break;
	            }
	        }
	        
	        // Set mode based on what we found
	        if (foundOurIP) {
	            isRemoteMode = true;
	            remoteHostAddress = matchedIP;
	            
	            // Set the system property so RuleHandler knows we're remote
	            System.setProperty("service.remote.host", matchedIP);
	            
	            logger.info("*** REMOTE MODE AUTO-ENABLED ***");
	            logger.info("*** This host (" + matchedIP + ") is configured for remote services ***");
	            logger.info("*** Channel " + matchedChannel + " maps to this machine ***");
	        } else {
	            isRemoteMode = false;
	            System.clearProperty("service.remote.host");
	            
	            logger.info("*** LOCAL MODE ***");
	            logger.info("*** No activeService entries map to this machine's IPs ***");
	        }
	        
	    } catch (Exception e) {
	        logger.error("Error auto-detecting mode from facts: " + e.getMessage(), e);
	        isRemoteMode = false;
	        logger.info("*** Defaulting to LOCAL MODE due to error ***");
	    }
	}
	
	public static boolean getRuleVersion(String version) {
		return VALID_RULE_SET.contains(version);
	}

	public static void setRuleVersion(String version, boolean add) {
		if (add) {
			VALID_RULE_SET.add(version);
		} else {
			VALID_RULE_SET.remove(version);
		}
	}

	public static void main(String[] args) throws Exception {
	    logger.info("=== Starting Rule-Driven ServiceLoader ===");
	    
	    // Parse command-line arguments first
	    parseArguments(args);
	    
	    logger.info("Using build version: " + buildVersion);
	    
	    // Install shutdown hook FIRST
	    installShutdownHook();
	    
	    // Start shutdown mechanisms
	    startShutdownFileWatcher();
	    startShutdownListener();
	    
	    String serviceChannel = null;
	    String servicePort = null;
	    String ruleChannel = null;
	    String rulePort = null;
	    String serviceName = null;
	    String operationName = null;

	    OOjdrewAPI oojdrew2 = new OOjdrewAPI();

	    // Build the core rule base (always rebuild in case source files changed)
	    logger.info("=== Building rule base with version " + buildVersion + " ===");
	    BuildRuleBase.buildRuleBase(buildVersion, true);
	    
	    setRuleVersion(buildVersion, true);  // Register this version as valid
	    logger.info("Registered version " + buildVersion + " as valid");
	    
	    // NOW check for remote mode after building
	    logger.info("=== Checking for remote deployment mode ===");
	    checkRemoteMode();

	    // Load service configurations from Query Directory
	    File extendedCommonBase = new File("");
	    String extendedCommonPath = extendedCommonBase.getCanonicalPath();
	    String serviceQueryPath = extendedCommonPath + "/" + SERVICE_LOADER_QUERIES_DIRECTORY;
	    File serviceQueryDirectory = new File(serviceQueryPath);
	    FileExtensionFilter fileExtensionFilter = new FileExtensionFilter(".ruleml");
	    File[] ruleMLFiles = serviceQueryDirectory.listFiles(fileExtensionFilter);
	    logger.debug("ServiceLoader: Looking in directory: " + serviceQueryPath + " for rulebase");

	    if (ruleMLFiles == null || ruleMLFiles.length == 0) {
	        throw new Exception("No rule files found in: " + serviceQueryPath);
	    }

	    for (File ruleMLFile : ruleMLFiles) {
	        System.out.println("Loading services " + ruleMLFile.getPath() + "... ");

	        try {
	            String ruleMLQuery = StringFileIO.readFileAsString(ruleMLFile.getPath());
	            
	            // Extract service and operation from query FIRST
	            String queryServiceName = extractServiceNameFromFile(ruleMLFile.getName());
	            
	            // CHECK FOR SERVICELIST QUERY - NEW LOGIC
	            if (ruleMLQuery.contains("serviceList") && queryServiceName != null) {
	                logger.info("=== DETECTED SERVICELIST QUERY FOR: " + queryServiceName + " ===");
	                
	                // Find ALL activeService entries for this service
	                String findAllQuery = String.format(
	                    "<Query><Atom><Rel>activeService</Rel><Ind>%s</Ind>" +
	                    "<Var>operation</Var><Var>channelId</Var><Var>port</Var></Atom></Query>",
	                    queryServiceName);
	                
	                OOjdrewAPI allOperationsChecker = new OOjdrewAPI();
	                allOperationsChecker.parseKnowledgeBase("RuleFolder." + buildVersion + "/Service.ruleml", true);
	                allOperationsChecker.issueRuleMLQuery(findAllQuery);
	                
	                // Collect all operations for this service
	                List<ServiceOperation> operations = new ArrayList<>();
	                
	                while (allOperationsChecker.rowsReturned > 0) {
	                    ServiceOperation op = new ServiceOperation();
	                    op.serviceName = queryServiceName;
	                    
	                    for (int i = 0; i < allOperationsChecker.rowsReturned; i++) {
	                        String key = String.valueOf(allOperationsChecker.rowData[i][0]);
	                        String value = String.valueOf(allOperationsChecker.rowData[i][1]);
	                        
	                        switch (key) {
	                            case "?operation": 
	                                op.operation = value; 
	                                break;
	                            case "?channelId": 
	                                op.channelId = value; 
	                                break;
	                            case "?port": 
	                                op.port = value; 
	                                break;
	                        }
	                    }
	                    
	                    if (op.isComplete()) {
	                        operations.add(op);
	                        logger.info("Found operation: " + queryServiceName + "." + op.operation + 
	                                   " on " + op.channelId + ":" + op.port);
	                    }
	                    
	                    if (allOperationsChecker.hasNext) {
	                        allOperationsChecker.nextSolution();
	                    } else {
	                        break;
	                    }
	                }
	                
	                // Create ServiceThread for each operation found
	                logger.info("Creating ServiceThreads for " + operations.size() + " operations");
	                
	                for (ServiceOperation op : operations) {
	                    createServiceThreadForOperation(op);
	                }
	                
	                // Skip the normal processing for this file
	                continue;
	            }
	            
	            // NORMAL SINGLE-OPERATION PROCESSING (existing code)
	            oojdrew2.parseKnowledgeBase("RuleFolder." + buildVersion + "/Service.ruleml", true);
	            oojdrew2.issueRuleMLQuery(ruleMLQuery);
	            
	        } catch (Exception e) {
	            logger.error("Service and Rule Loader: Can't find the rule directories....", e);
	            System.exit(1);
	        }

	        // REST OF EXISTING CODE FOR NORMAL QUERIES...
	        boolean loadComplete = false;
	        while (!loadComplete) {
	            try {
	                if (oojdrew2.rowsReturned == 0) {
	                    logger.error("Can't find subscribe channels - can't launch!");
	                    break;
	                }
	                
	                // [Keep all the existing processing code here - the original parsing logic]
	                // Note: This would include all the original single-operation processing
	                // from the existing code that parses rowData for individual services
	                
	                loadComplete = true; // Placeholder - add actual completion logic
	                
	            } catch (Exception e) {
	                logger.error("Service and Rule Loader: Error loading service handler....", e);
	                loadComplete = true;
	                System.exit(1);
	            }
	        }
	    }
	    
	    logger.info("=== ServiceLoader Startup Complete ===");
	    logger.info("Version: " + buildVersion);
	    logger.info("Mode: " + (isRemoteMode ? "REMOTE on " + remoteHostAddress : "LOCAL"));
	    
	    // Calculate and display shutdown port
	    int versionNum = extractVersionNumber(buildVersion);
	    int shutdownPort = SHUTDOWN_PORT_BASE + versionNum;
	    
	    logger.info("=== SHUTDOWN OPTIONS ===");
	    logger.info("1. Delete file: " + (shutdownControlFile != null ? shutdownControlFile.getAbsolutePath() : "N/A"));
	    logger.info("2. Send UDP 'SHUTDOWN' to port: " + shutdownPort);
	    logger.info("3. Ctrl+C or terminate process");
	    logger.info("========================");
	    
	    // Keep main thread alive but responsive to shutdown
	    while (!shutdownRequested.get()) {
	        try {
	            Thread.sleep(1000);
	        } catch (InterruptedException e) {
	            logger.info("Main thread interrupted");
	            break;
	        }
	    }
	    
	    logger.info("=== ServiceLoader shutting down ===");
	    
	    // Wait for threads to finish
	    for (Thread t : managedThreads) {
	        try {
	            t.join(2000); // Wait up to 2 seconds per thread
	        } catch (InterruptedException e) {
	            // Ignore
	        }
	    }
	    
	    logger.info("=== ServiceLoader terminated ===");
	}

	// Add these helper classes and methods
	private static class ServiceOperation {
	    String serviceName;
	    String operation;
	    String channelId;
	    String port;
	    
	    boolean isComplete() { 
	        return serviceName != null && operation != null && channelId != null && port != null; 
	    }
	}

	private static void createServiceThreadForOperation(ServiceOperation op) {
	    try {
	        // Determine deployment mode
	        DeploymentMode deploymentMode = isRemoteMode ? DeploymentMode.REMOTE : DeploymentMode.LOCAL;
	        
	        // Calculate ports based on the channel
	        int basePort = Integer.parseInt(op.port);
	        int channelNumber = extractChannelNumber(op.channelId);
	        int eventReactorPort = calculateEventReactorPort(channelNumber, basePort);
	        int ruleHandlerPort = calculateRuleHandlerPort(channelNumber, basePort);
	        
	        // Log the deployment configuration
	        logger.info("=== Creating ServiceThread for Operation ===");
	        logger.info("Service: " + op.serviceName + " Operation: " + op.operation);
	        logger.info("Version: " + buildVersion);
	        logger.info("Mode: " + deploymentMode);
	        logger.info("Channel: " + op.channelId + " (number: " + channelNumber + ")");
	        logger.info("Base Port: " + basePort);
	        logger.info("EventReactor Port: " + eventReactorPort);
	        logger.info("RuleHandler Port: " + ruleHandlerPort);
	        
	        // Validate port range
	        if (eventReactorPort > 65535 || ruleHandlerPort > 65535) {
	            logger.error("Port out of range - EventReactor: " + eventReactorPort + 
	                ", RuleHandler: " + ruleHandlerPort);
	            return;
	        }
	        
	        // Start RuleHandler thread
	        UDPLoadRuleHandlerThread ruleHandlerThread = new UDPLoadRuleHandlerThread(
	            op.serviceName, op.operation, op.channelId, String.valueOf(ruleHandlerPort),
	            deploymentMode);
	        Thread ruleThreadHandle = new Thread(ruleHandlerThread, 
	            "RuleHandler-" + op.serviceName + "-" + op.operation);
	        ruleThreadHandle.start();
	        registerThread(ruleThreadHandle);  // Track for shutdown

	        // Start ServiceThread with EventReactor
	        UDPServiceThread serviceThread = new UDPServiceThread(
	            op.channelId, String.valueOf(eventReactorPort), 
	            op.serviceName, op.operation, deploymentMode);
	        Thread threadHandle = new Thread(serviceThread, 
	            "ServiceThread-" + op.serviceName + "-" + op.operation);
	        threadHandle.start();
	        registerThread(threadHandle);  // Track for shutdown
	        
	        logger.info("Successfully created ServiceThread for " + op.serviceName + "." + op.operation);
	        
	    } catch (Exception e) {
	        logger.error("Failed to create ServiceThread for " + op.serviceName + "." + op.operation, e);
	    }
	}
	
	/**
	 * Extract service name from filename
	 */
	/**
	 * Extract service name from loader query filename
	 * Supports two patterns:
	 * 1. Hyphen-delimited: "ServiceName-LoaderQuery.ruleml" -> "ServiceName" (NEW)
	 * 2. Legacy CamelCase: "SomeServiceLoaderQuery.ruleml" -> "SomeService" (BACKWARD COMPATIBLE)
	 * 
	 * Examples:
	 *   "P1_Place-LoaderQuery.ruleml" -> "P1_Place"
	 *   "TriageServiceLoaderQuery.ruleml" -> "TriageService"
	 */
	private static String extractServiceNameFromFile(String fileName) {
	    // First try hyphen-delimited pattern: "ServiceName-LoaderQuery.ruleml" -> "ServiceName"
	    // This allows any characters (word chars and underscores) before the hyphen
	    Pattern hyphenPattern = Pattern.compile("([\\w_]+)-LoaderQuery\\.ruleml");
	    Matcher hyphenMatcher = hyphenPattern.matcher(fileName);
	    if (hyphenMatcher.find()) {
	        return hyphenMatcher.group(1);
	    }
	    
	    // Fall back to legacy pattern: "SomeServiceLoaderQuery.ruleml" -> "SomeService" 
	    // This maintains backward compatibility with existing files
	    Pattern legacyPattern = Pattern.compile("(\\w+Service)LoaderQuery\\.ruleml");
	    Matcher legacyMatcher = legacyPattern.matcher(fileName);
	    if (legacyMatcher.find()) {
	        return legacyMatcher.group(1);
	    }
	    
	    return null;
	}

	/**
	 * Extract channel number from channel identifier
	 * Handles "ip1" format, "a1" format, multicast, and regular IPs
	 */
	private static int extractChannelNumber(String serviceChannel) {
	    try {
	        // Handle "ip0", "ip1", "ip2" format
	        if (serviceChannel != null && serviceChannel.startsWith("ip")) {
	            return Integer.parseInt(serviceChannel.substring(2));
	        }
	        
	        // Handle "a1", "a2" format
	        if (serviceChannel != null && serviceChannel.startsWith("a")) {
	            return Integer.parseInt(serviceChannel.substring(1));
	        }
	        
	        // Check if it's a regular IP address (not multicast)
	        if (serviceChannel != null && serviceChannel.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
	            String[] parts = serviceChannel.split("\\.");
	            int firstOctet = Integer.parseInt(parts[0]);
	            
	            // If it's NOT a multicast address (224.x.x.x), return 0 for simple port calculation
	            if (firstOctet != 224) {
	                return 0;  // Use 0 for regular IPs to avoid port multiplication
	            }
	            
	            // For multicast addresses, use the last octet
	            return Integer.parseInt(parts[3]);
	        }
	        
	    } catch (Exception e) {
	        logger.warn("Could not parse channel number from: " + serviceChannel + ", using default 0");
	    }
	    return 0; // default to 0 for regular IPs
	}
	
	/**
	 * Calculate EventReactor port (where services listen for events)
	 */
	private static int calculateEventReactorPort(int channelNumber, int basePort) {
		return 10000 + (channelNumber * 1000) + basePort;
	}

	/**
	 * Calculate RuleHandler port (where rule processing happens)
	 */
	private static int calculateRuleHandlerPort(int channelNumber, int basePort) {
		return 20000 + (channelNumber * 1000) + basePort;
	}
}

/**
 * UDP-based LoadRuleHandlerThread
 */
class UDPLoadRuleHandlerThread implements Runnable {
	private final String serviceName;
	private final String operationName;
	private final String ruleChannel;
	private final String rulePort;
	private final DeploymentMode deploymentMode;
	private static final Logger logger = Logger.getLogger(UDPLoadRuleHandlerThread.class);

	UDPLoadRuleHandlerThread(String serviceName, String operationName, String ruleChannel, 
			String rulePort, DeploymentMode deploymentMode) {
		this.serviceName = serviceName;
		this.operationName = operationName;
		this.ruleChannel = ruleChannel;
		this.rulePort = rulePort;
		this.deploymentMode = deploymentMode;
	}

	@Override
	public void run() {
		String listenAddress = (deploymentMode == DeploymentMode.REMOTE) ? 
			"all interfaces" : "localhost";
			
		logger.info("Starting UDP RuleHandler for service: " + serviceName + ", operation: " + operationName + 
				   ", channel: " + ruleChannel + ", port: " + rulePort + 
				   ", listening on: " + listenAddress);
		
		try {
			RuleHandler ruleHandler = new RuleHandler(ruleChannel, rulePort);
			logger.info("Successfully created UDP RuleHandler for " + serviceName + ":" + operationName + 
				" listening on port " + rulePort);
			
			while (!Thread.currentThread().isInterrupted() && !ServiceLoader.isShutdownRequested()) {
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
					logger.info("RuleHandler thread interrupted for " + serviceName + ":" + operationName);
					ruleHandler.shutdown();
					break;
				}
			}
			
			// Clean shutdown
			ruleHandler.shutdown();
			logger.info("RuleHandler shut down for " + serviceName + ":" + operationName);
			
		} catch (IOException e) {
			logger.error("Failed to create UDP RuleHandler for " + serviceName + ":" + operationName, e);
		}
	}
}

/**
 * UDP-based ServiceThread wrapper
 */
class UDPServiceThread implements Runnable {
	private final String serviceChannel;
	private final String servicePort;
	private final String serviceName;
	private final String operationName;
	private final DeploymentMode deploymentMode;
	private static final Logger logger = Logger.getLogger(UDPServiceThread.class);

	UDPServiceThread(String serviceChannel, String servicePort, String serviceName, 
			String operationName, DeploymentMode deploymentMode) {
		this.serviceChannel = serviceChannel;
		this.servicePort = servicePort;
		this.serviceName = serviceName;
		this.operationName = operationName;
		this.deploymentMode = deploymentMode;
	}

	@Override
	public void run() {
		String listenMode = (deploymentMode == DeploymentMode.REMOTE) ? "REMOTE" : "LOCAL";
		
	    logger.info("Starting " + listenMode + " UDP ServiceThread for " + serviceName + ":" + 
	    	operationName + " on port: " + servicePort);
	    
	    try {
	        EventPublisher eventPublisher = new EventPublisher();
	       // ServiceThread serviceThread = new ServiceThread(serviceChannel, servicePort, eventPublisher);
	        ServiceThread serviceThread = new ServiceThread(serviceChannel, servicePort, eventPublisher,
                    serviceName, operationName);
	        Thread serviceThreadHandle = new Thread(serviceThread, 
	        	"ServiceThread-" + serviceName + "-" + servicePort);
	        serviceThreadHandle.start();
	        
	        logger.info("Successfully created " + listenMode + " UDP ServiceThread for port " + servicePort);
	        
	        while (!Thread.currentThread().isInterrupted() && !ServiceLoader.isShutdownRequested()) {
	            try {
	                Thread.sleep(1000);
	            } catch (InterruptedException e) {
	                logger.info("UDPServiceThread interrupted for " + serviceName);
	                serviceThread.shutdown();
	                break;
	            }
	        }
	        
	        // Clean shutdown
	        serviceThread.shutdown();
	        logger.info("ServiceThread shut down for " + serviceName + ":" + operationName);
	        
	    } catch (Exception e) {
	        logger.error("Failed to create UDP ServiceThread for " + serviceName, e);
	    }
	}
}