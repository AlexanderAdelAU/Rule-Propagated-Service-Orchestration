package org.btsn.common.eventgenerators;

import java.io.File;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

import org.btsn.json.jsonLibrary;
import org.btsn.rulecontroller.RuleDeployer;
import org.btsn.utils.BuildRuleBase;
import org.btsn.utils.CopyFile;
import org.btsn.utils.CreateDirectory;
import org.btsn.utils.OOjdrewAPI;
import org.btsn.utils.StringFileIO;
import org.btsn.utils.XPathHelper;

/**
 * Database Initialization Event Generator
 * Clean implementation following established patterns - NO DEFAULTS, FAILS WHEN IT SHOULD
 */
public class DatabaseInitialization_EventGenerator {

    private static String payLoadVersionPath;
    private static jsonLibrary json = new jsonLibrary();
    
    private static final OOjdrewAPI oojdrew = new OOjdrewAPI();

    // Service configuration - NO DEFAULTS, must be provided via arguments
    private static String targetServiceName = null;
    private static String serviceOperation = null;
    private static String attributeName = "token";
    
    // Resolved from rule base
    private static String resolvedServiceChannel = null;
    private static String resolvedServicePort = null;
    private static String resolvedChannelId = null;

    // Event configuration
    private static boolean priortiseSID = true;
    private static boolean monitorIncomingEvents = false;
    private static String ruleBaseVersion = null;
    private static String processName = null;
    private static int sequenceID = -1;
    private static long timeToExpire = 10000;
    private static String status = "active";
    private static int numberOfTokens = 1;  // Default to 1 token
    
    private static boolean skipDeployment = false;
    private static boolean exitOnCompletion = true;  // Default to exit

    public static void main(String[] args) throws Exception {
        long startTime = System.currentTimeMillis();

        if (!parseArguments(args)) {
            printUsage();
            System.exit(1);
        }

        System.out.println("=== Database Initialization Event Generator ===");
        System.out.println("Target Service: " + targetServiceName);
        System.out.println("Operation: " + serviceOperation);
        System.out.println("Rule Base Version: " + ruleBaseVersion);
        System.out.println("Process Name: " + processName);
        System.out.println("Sequence ID: " + sequenceID);
        System.out.println("Skip Deployment: " + skipDeployment);
        System.out.println();

        // STEP 0: Build rule base (only when deploying, not when firing tokens)
        // ============================================================================
        // INITIALIZATION ARCHITECTURE NOTE:
        // ============================================================================
        // Database Initialization is INDEPENDENT of business workflow versions.
        // It runs ONCE (typically with v001) to initialize ALL shared services
        // (Triage, Laboratory, Radiology, Cardiology, Diagnosis, Treatment, Monitor).
        // 
        // The Ant file pattern is:
        //   1. Deploy initialization rules ONCE (tokens=0) - builds rule base
        //   2. Fire initialization tokens in PARALLEL (-skipDeploy) - reuses rule base
        //
        // Business workflows (v001, v002, v003, etc.) run AFTER initialization
        // and use the already-initialized services. Each business workflow version
        // builds its OWN rule base during its deploy phase.
        //
        // The same pattern applies to the Collector phase (Phase 3) - deploy once,
        // then fire collection tokens in parallel with -skipDeploy.
        // ============================================================================
        if (!skipDeployment) {
            System.out.println("=== Building Rule Base for " + ruleBaseVersion + " ===");
            try {
                boolean ruleBaseBuilt = BuildRuleBase.buildRuleBase(ruleBaseVersion, true);
                if (!ruleBaseBuilt) {
                    System.err.println("WARNING: Rule base build returned false for " + ruleBaseVersion);
                }
            } catch (Exception e) {
                System.err.println("ERROR: Failed to build rule base: " + e.getMessage());
                e.printStackTrace();
                System.exit(1);
            }
            System.out.println("=== Rule Base Built ===\n");
        }

        // STEP 1: Query rule base for service configuration
        System.out.println("=== Querying Rule Base for Service Configuration ===");
        
        if (!queryServiceConfiguration(targetServiceName, serviceOperation, ruleBaseVersion)) {
            System.err.println("FATAL: Could not resolve service configuration for: " + targetServiceName);
            System.err.println("Ensure service exists in ListofInitializationServices.ruleml.xml");
            System.exit(1);
        }
        
        System.out.println("Resolved Channel: " + resolvedServiceChannel);
        System.out.println("Resolved Port: " + resolvedServicePort);
        System.out.println("Resolved Channel ID: " + resolvedChannelId);
        System.out.println();

        // STEP 2: Build service payload with transition token
        String transitionToken = deriveTransitionToken(targetServiceName);
        String initToken = "\"" + transitionToken + "\"";
        
        System.out.println("Transition Token: " + initToken);
        
        String xmlPayload = buildServicePayload(targetServiceName, serviceOperation, 
            resolvedServiceChannel, resolvedServicePort, initToken);

        // STEP 3: Deploy process rules (if not skipped)
        Long timeToCommit = 0L;
        if (!skipDeployment) {
            System.out.println("=== Deploying Process Rules ===");
            RuleDeployer ruleDeployer = new RuleDeployer(processName, ruleBaseVersion);
            ruleDeployer.deploy();
            if (!RuleDeployer.deployed) {
                System.err.println("WARNING: Could not deploy process: " + processName);
            }
            Thread.sleep(2000);
            timeToCommit = System.currentTimeMillis() - startTime;
            System.out.println("Rule deployment complete");
        } else {
            System.out.println("=== Skipping Rule Deployment ===");
        }

        // STEP 4: Fire initialization event
        if (numberOfTokens > 0) {
            System.out.println("=== Firing Initialization Event ===");
            fireEvent(xmlPayload, resolvedServiceChannel, resolvedServicePort, sequenceID, timeToExpire);
            
            long eventTime = System.currentTimeMillis() - startTime - timeToCommit;
            System.out.println("\n=== Event Generation Complete ===");
            if (!skipDeployment) {
                System.out.println("Rule Commitment: " + timeToCommit + "ms");
            }
            System.out.println("Event propagation time: " + eventTime + "ms");
            System.out.println("Tokens Sent: " + numberOfTokens);
            System.out.println("Target: " + targetServiceName + " @ " + resolvedServiceChannel + ":" + resolvedServicePort);
        } else {
            System.out.println("=== Skipping Token Generation (tokens=0) ===");
            System.out.println("Deployment only - no events fired");
        }
        
        if (exitOnCompletion) {
            System.exit(0);
        }
    }

    private static boolean parseArguments(String[] args) {
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-service":
                    if (i + 1 < args.length) targetServiceName = args[++i];
                    else {
                        System.err.println("ERROR: -service requires a value");
                        return false;
                    }
                    break;
                case "-operation":
                    if (i + 1 < args.length) serviceOperation = args[++i];
                    else {
                        System.err.println("ERROR: -operation requires a value");
                        return false;
                    }
                    break;
                case "-version":
                    if (i + 1 < args.length) ruleBaseVersion = args[++i];
                    else {
                        System.err.println("ERROR: -version requires a value");
                        return false;
                    }
                    break;
                case "-sequenceId":
                    if (i + 1 < args.length) {
                        try {
                            sequenceID = Integer.parseInt(args[++i]);
                        } catch (NumberFormatException e) {
                            System.err.println("ERROR: Invalid sequenceId value: " + args[i]);
                            return false;
                        }
                    } else {
                        System.err.println("ERROR: -sequenceId requires a value");
                        return false;
                    }
                    break;
                case "-skipDeploy":
                    skipDeployment = true;
                    break;
                case "-process":
                    if (i + 1 < args.length) processName = args[++i];
                    else {
                        System.err.println("ERROR: -process requires a value");
                        return false;
                    }
                    break;
                case "-tokens":
                    if (i + 1 < args.length) {
                        try {
                            numberOfTokens = Integer.parseInt(args[++i]);
                        } catch (NumberFormatException e) {
                            System.err.println("ERROR: Invalid tokens value: " + args[i]);
                            return false;
                        }
                    } else {
                        System.err.println("ERROR: -tokens requires a value");
                        return false;
                    }
                    break;
                case "-noexit":
                    exitOnCompletion = false;
                    break;
                default:
                    System.err.println("ERROR: Unknown argument: " + args[i]);
                    return false;
            }
        }
        
        if (targetServiceName == null) {
            System.err.println("FATAL: -service parameter is required");
            return false;
        }
        if (serviceOperation == null) {
            System.err.println("FATAL: -operation parameter is required");
            return false;
        }
        if (ruleBaseVersion == null) {
            System.err.println("FATAL: -version parameter is required");
            return false;
        }
        if (sequenceID == -1) {
            System.err.println("FATAL: -sequenceId parameter is required");
            return false;
        }
        if (processName == null) {
            System.err.println("FATAL: -process parameter is required");
            return false;
        }
        
        return true;
    }

    private static void printUsage() {
        System.out.println("Usage: java DatabaseInitialization_EventGenerator [options]");
        System.out.println("\nRequired:");
        System.out.println("  -service <name>      Target service name");
        System.out.println("  -operation <op>      Service operation");
        System.out.println("  -version <ver>       Rule base version");
        System.out.println("  -sequenceId <id>     Sequence ID");
        System.out.println("  -process <name>      Process name");
        System.out.println("\nOptional:");
        System.out.println("  -skipDeploy          Skip rule deployment");
        System.out.println("  -tokens <n>          Number of tokens to fire (default: 1, use 0 to deploy only)");
        System.out.println("  -noexit              Don't exit after completion");
    }

    /**
     * Derives the transition token from service name
     * E.g., Monitor_InitializationService -> "init monitor"
     * NO FALLBACK - fails if format is unexpected
     */
    private static String deriveTransitionToken(String serviceName) {
        if (!serviceName.endsWith("InitializationService")) {
            throw new IllegalArgumentException("FATAL: Invalid service name format: " + serviceName + 
                " (expected format: *InitializationService)");
        }
        
        String base = serviceName.substring(0, serviceName.length() - "InitializationService".length());
        if (base.isEmpty()) {
            throw new IllegalArgumentException("FATAL: Cannot derive base name from: " + serviceName);
        }
        
        // Strip trailing underscore if present (P1_, P2_, P3_ → P1, P2, P3)
        if (base.endsWith("_")) {
            base = base.substring(0, base.length() - 1);
        }
        
        return "init " + base.toLowerCase();
    }

    /**
     * Query OOjDREW rule base for service configuration using activeService facts
     */
    private static boolean queryServiceConfiguration(String serviceName, String operationName, String buildVersion) {
        try {
            System.out.println("Querying activeService facts...");
            
            String activeServiceQuery = "<Query><Atom><Rel>activeService</Rel><Ind>" + serviceName + 
                "</Ind><Ind>" + operationName + "</Ind><Var>channelId</Var><Var>port</Var></Atom></Query>";
            
            System.out.println("Query: " + activeServiceQuery);
            
            // Master rule base is always in btsn.common/RuleFolder.{version}/
            File currentDir = new File("").getAbsoluteFile();
            File commonDir = new File(currentDir.getParent(), "btsn.common");
            File ruleBaseFile = new File(commonDir, "RuleFolder." + buildVersion + "/Service.ruleml");
            String ruleBasePath = ruleBaseFile.getAbsolutePath();
            System.out.println("Loading rule base from: " + ruleBasePath);
            oojdrew.parseKnowledgeBase(ruleBasePath, true);
            oojdrew.issueRuleMLQuery(activeServiceQuery);
            
            System.out.println("Rows returned: " + oojdrew.rowsReturned);
            
            if (oojdrew.rowsReturned == 0) {
                System.err.println("No activeService facts found for: " + serviceName + ":" + operationName);
                return false;
            }

            String channelId = null;
            for (int i = 0; i < oojdrew.rowsReturned; i++) {
                String key = String.valueOf(oojdrew.rowData[i][0]);
                String value = String.valueOf(oojdrew.rowData[i][1]);
                
                System.out.println("Result [" + i + "]: " + key + " = " + value);
                
                if ("?channelId".equals(key)) {
                    channelId = value;
                    resolvedChannelId = value;
                } else if ("?port".equals(key)) {
                    resolvedServicePort = value;
                }
            }

            if (channelId == null || resolvedServicePort == null) {
                System.err.println("Incomplete activeService result");
                return false;
            }

            // Query boundChannel facts
            System.out.println("Querying boundChannel for: " + channelId);
            
            String boundChannelQuery = "<Query><Atom><Rel>boundChannel</Rel><Ind>" + channelId + 
                "</Ind><Var>channel</Var></Atom></Query>";
            
            oojdrew.issueRuleMLQuery(boundChannelQuery);
            System.out.println("boundChannel rows returned: " + oojdrew.rowsReturned);
            
            if (oojdrew.rowsReturned == 0) {
                System.err.println("No boundChannel facts found for: " + channelId);
                return false;
            }

            for (int i = 0; i < oojdrew.rowsReturned; i++) {
                String key = String.valueOf(oojdrew.rowData[i][0]);
                String value = String.valueOf(oojdrew.rowData[i][1]);
                
                System.out.println("boundChannel result [" + i + "]: " + key + " = " + value);
                
                if ("?channel".equals(key)) {
                    resolvedServiceChannel = value;
                }
            }

            if (resolvedServiceChannel == null) {
                System.err.println("Could not resolve channel for: " + channelId);
                return false;
            }

            System.out.println("Successfully resolved service configuration");
            return true;

        } catch (Exception e) {
            System.err.println("Error querying rule base: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Build service payload XML
     */
    private static String buildServicePayload(String serviceName, String serviceOperation, 
            String resolvedChannel, String resolvedPort, String attributeValue) throws IOException {
        
        System.out.println("Building payload for " + serviceName);

        File appBase = new File("");
        String payLoadPath = appBase.getAbsolutePath() + "/Payload";
        payLoadVersionPath = appBase.getAbsolutePath() + "/" + ruleBaseVersion + "/";
        CreateDirectory.createDirectory(payLoadVersionPath);
        CopyFile.copyfile(payLoadPath + "/payLoad.xml", payLoadVersionPath + "/payload.xml");

        String currentXmlPayload = StringFileIO.readFileAsString(payLoadVersionPath + "/payload.xml");

        currentXmlPayload = XPathHelper.modifyXMLItem(currentXmlPayload, 
            "//payload/service/serviceName/text()", serviceName);
        currentXmlPayload = XPathHelper.modifyXMLItem(currentXmlPayload, 
            "//payload/service/operation/text()", serviceOperation);
        currentXmlPayload = XPathHelper.modifyXMLItem(currentXmlPayload, 
            "//payload/joinAttribute/attributeName/text()", attributeName);
        currentXmlPayload = XPathHelper.modifyXMLItem(currentXmlPayload, 
            "//payload/joinAttribute/attributeValue/text()", attributeValue);
        currentXmlPayload = XPathHelper.modifyXMLItem(currentXmlPayload, 
            "//payload/header/monitorIncomingEvents/text()", Boolean.toString(monitorIncomingEvents));
        currentXmlPayload = XPathHelper.modifyXMLItem(currentXmlPayload, 
            "//payload/header/priortiseSID/text()", Boolean.toString(priortiseSID));
        currentXmlPayload = XPathHelper.modifyXMLItem(currentXmlPayload, 
            "//payload/header/ruleBaseVersion/text()", ruleBaseVersion);
        currentXmlPayload = XPathHelper.modifyXMLItem(currentXmlPayload, 
            "//payload/joinAttribute/status/text()", status);

        String serviceSpecificFileName = serviceName.toLowerCase() + "_payload.xml";
        StringFileIO.writeStringToFile(currentXmlPayload, 
            payLoadVersionPath + "/" + serviceSpecificFileName, currentXmlPayload.length());

        return currentXmlPayload;
    }

    /**
     * Fire initialization event
     */
    private static void fireEvent(String xmlPayload, String resolvedChannel, 
            String resolvedPort, int sequenceID, long timeToExpire) {
        try {
            long currentTime = System.currentTimeMillis();
            
            xmlPayload = XPathHelper.modifyXMLItem(xmlPayload, 
                "//payload/monitorData/processStartTime/text()", Long.toString(currentTime));
            xmlPayload = XPathHelper.modifyXMLItem(xmlPayload, 
                "//payload/monitorData/processElapsedTime/text()", "0");
            xmlPayload = XPathHelper.modifyXMLItem(xmlPayload, 
                "//payload/joinAttribute/notAfter/text()", Long.toString(currentTime + timeToExpire));
            xmlPayload = XPathHelper.modifyXMLItem(xmlPayload, 
                "//payload/header/sequenceId/text()", Integer.toString(sequenceID));

            sendUDPEvent(xmlPayload, resolvedChannel, resolvedServicePort);
            
        } catch (Exception e) {
            System.err.println("Error firing event: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Send UDP event using dynamic port calculation from channel configuration
     * NO FALLBACKS - fails if configuration is invalid
     */
    private static void sendUDPEvent(String payload, String resolvedChannel, 
            String resolvedPort) throws IOException {
        
        if (resolvedChannelId == null) {
            throw new IOException("FATAL: resolvedChannelId is null - cannot determine target port");
        }
        if (resolvedPort == null) {
            throw new IOException("FATAL: resolvedPort is null - cannot determine target port");
        }
        
        DatagramSocket udpSocket = null;
        try {
            udpSocket = new DatagramSocket();
            InetAddress targetAddress = InetAddress.getLoopbackAddress();
            
            int channelNumber;
            if (!resolvedChannelId.startsWith("ip")) {
                throw new IOException("FATAL: Invalid channel ID format: " + resolvedChannelId + 
                    " (expected format: ip0, ip1, etc.)");
            }
            
            try {
                channelNumber = Integer.parseInt(resolvedChannelId.substring(2));
            } catch (Exception e) {
                throw new IOException("FATAL: Cannot parse channel number from: " + resolvedChannelId, e);
            }
            
            int basePort;
            try {
                basePort = Integer.parseInt(resolvedPort);
            } catch (NumberFormatException e) {
                throw new IOException("FATAL: Invalid port number: " + resolvedPort, e);
            }
            
            int targetPort = 10000 + (channelNumber * 1000) + basePort;
            
            System.out.println("Sending to localhost:" + targetPort + 
                " (channel: " + resolvedChannelId + ", base: " + basePort + ")");
            
            byte[] data = payload.getBytes();
            DatagramPacket dp = new DatagramPacket(data, data.length, targetAddress, targetPort);
            udpSocket.send(dp);
            
            System.out.println("Event sent successfully");
            
        } finally {
            if (udpSocket != null) {
                udpSocket.close();
            }
        }
    }
}