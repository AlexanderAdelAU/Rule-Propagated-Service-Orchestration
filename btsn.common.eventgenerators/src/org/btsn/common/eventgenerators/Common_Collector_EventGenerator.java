package org.btsn.common.eventgenerators;

import java.io.File;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;

import org.btsn.constants.VersionConstants;
import org.btsn.json.jsonLibrary;
import org.btsn.rulecontroller.RuleDeployer;
import org.btsn.utils.CopyFile;
import org.btsn.utils.CreateDirectory;
import org.btsn.utils.OOjdrewAPI;
import org.btsn.utils.StringFileIO;
import org.btsn.utils.XPathHelper;

/**
 * Performance Collection Event Generator
 * Self-contained collector that bypasses workflow infrastructure
 * Directly sends collection triggers to collector services via UDP
 * 
 * Pattern follows DatabaseInitialization_EventGenerator
 */
public class Common_Collector_EventGenerator {

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
    private static String queryVersion = null;  // Comma-separated versions to collect
    private static int sequenceID = -1;
    private static long timeToExpire = 10000;
    private static String status = "active";
    
    private static boolean skipDeployment = false;
    private static boolean exitOnCompletion = true;

    public static void main(String[] args) throws Exception {
        long startTime = System.currentTimeMillis();

        if (!parseArguments(args)) {
            printUsage();
            System.exit(1);
        }

        System.out.println("=================================================");
        System.out.println("=== Performance Collection Event Generator ===");
        System.out.println("=================================================");
        System.out.println("Target Service: " + targetServiceName);
        System.out.println("Operation: " + serviceOperation);
        System.out.println("Rule Base Version: " + ruleBaseVersion);
        System.out.println("Process Name: " + processName);
        System.out.println("Query Versions: " + queryVersion);
        System.out.println("Sequence ID Base: " + sequenceID);
        System.out.println("Skip Deployment: " + skipDeployment);
        System.out.println("=================================================");
        System.out.println();

        // STEP 1: Query rule base for service configuration
        System.out.println("=== Querying Rule Base for Service Configuration ===");
        
        if (!queryServiceConfiguration(targetServiceName, serviceOperation, ruleBaseVersion)) {
            System.err.println("FATAL: Could not resolve service configuration for: " + targetServiceName);
            System.err.println("Ensure service exists in ListofCollectorServices.ruleml.xml");
            System.exit(1);
        }
        
        System.out.println("Resolved Channel: " + resolvedServiceChannel);
        System.out.println("Resolved Port: " + resolvedServicePort);
        System.out.println("Resolved Channel ID: " + resolvedChannelId);
        System.out.println();

        // STEP 2: Deploy process rules (if not skipped)
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

        // STEP 3: Process collection events for single or multiple versions
        System.out.println("=== Processing Collection Events ===");

        if (queryVersion.contains(",")) {
            // Multiple versions requested
            String[] versions = queryVersion.split(",");
            System.out.println("=== Collecting Multiple Versions: " + Arrays.toString(versions) + " ===");
            
            for (String version : versions) {
                String versionTrimmed = version.trim();
                System.out.println("\nCollecting ALL data for version: " + versionTrimmed);
                
                // Get the correct workflow base for this version
                // v001 -> 100000, v002 -> 200000, v003 -> 300000, etc.
                int versionBase = VersionConstants.getWorkflowBase(versionTrimmed);
                
                // Create token: workflow_workflowBase_version
                String simpleToken = "workflow_" + versionBase + "_" + versionTrimmed;
                String collectorToken = json.encodeKeyValue("token", simpleToken);
                
                System.out.println("Token format: " + simpleToken);
                
                String xmlPayload = buildServicePayload(targetServiceName, serviceOperation, 
                    resolvedServiceChannel, resolvedServicePort, collectorToken);
                
                fireEvent(xmlPayload, resolvedServiceChannel, resolvedServicePort, 
                    versionBase, timeToExpire);
                
                if (!version.equals(versions[versions.length - 1])) {
                    Thread.sleep(500); // Small delay between versions, except after last one
                }
            }
        } else {
            // Single version
            System.out.println("Collecting ALL data for single version: " + queryVersion);
            
            // Get the correct workflow base for this version
            int versionBase = VersionConstants.getWorkflowBase(queryVersion);
            
            // Create token: workflow_workflowBase_version
            String simpleToken = "workflow_" + versionBase + "_" + queryVersion;
            String collectorToken = json.encodeKeyValue("token", simpleToken);
            
            System.out.println("Token format: " + simpleToken);
            
            String xmlPayload = buildServicePayload(targetServiceName, serviceOperation, 
                resolvedServiceChannel, resolvedServicePort, collectorToken);
            
            fireEvent(xmlPayload, resolvedServiceChannel, resolvedServicePort, 
                versionBase, timeToExpire);
        }
        
        long eventTime = System.currentTimeMillis() - startTime - timeToCommit;
        System.out.println("\n=================================================");
        System.out.println("=== Collection Complete for " + targetServiceName + " ===");
        System.out.println("=================================================");
        System.out.println("Event Generator started at: " + startTime);
        if (!skipDeployment) {
            System.out.println("Rule Commitment achieved in (ms): " + timeToCommit);
        }
        System.out.println("Events propagated (from time to commit) in (ms): " + eventTime);
        System.out.println("Target Service: " + targetServiceName + " (" + resolvedServiceChannel + ":" + resolvedServicePort + ")");
        System.out.println("Operation: " + serviceOperation);
        System.out.println("Versions collected: " + queryVersion);
        System.out.println("Data will route to: MonitorService.writeCollectorData");
        System.out.println("=================================================");
        
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
                case "-queryVersion":
                    if (i + 1 < args.length) queryVersion = args[++i];
                    else {
                        System.err.println("ERROR: -queryVersion requires a value");
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
                case "-noExit":
                    exitOnCompletion = false;
                    break;
                default:
                    System.err.println("WARNING: Unknown argument: " + args[i]);
            }
        }

        // Validate required parameters
        if (targetServiceName == null) {
            System.err.println("ERROR: -service is required");
            return false;
        }
        if (serviceOperation == null) {
            System.err.println("ERROR: -operation is required");
            return false;
        }
        if (ruleBaseVersion == null) {
            System.err.println("ERROR: -version is required");
            return false;
        }
        if (processName == null) {
            System.err.println("ERROR: -process is required");
            return false;
        }
        if (queryVersion == null) {
            System.err.println("ERROR: -queryVersion is required");
            return false;
        }
        if (sequenceID == -1) {
            System.err.println("ERROR: -sequenceId is required");
            return false;
        }

        return true;
    }

    private static void printUsage() {
        System.err.println("\n=== Performance Collection Event Generator Usage ===");
        System.err.println("Required arguments:");
        System.err.println("  -service <serviceName>        Target collector service (e.g., P1_CollectorService)");
        System.err.println("  -operation <operationName>    Service operation (e.g., collectAllData)");
        System.err.println("  -version <version>            Rule base version (e.g., v001)");
        System.err.println("  -process <processName>        Process name for deployment");
        System.err.println("  -queryVersion <versions>      Versions to collect (comma-separated, e.g., v001,v002,v003)");
        System.err.println("  -sequenceId <id>              Base sequence ID");
        System.err.println();
        System.err.println("Optional arguments:");
        System.err.println("  -skipDeploy                   Skip rule deployment");
        System.err.println("  -noExit                       Don't exit after completion");
        System.err.println();
        System.err.println("Example:");
        System.err.println("  java PerformanceCollection_EventGenerator \\");
        System.err.println("    -service P1_CollectorService \\");
        System.err.println("    -operation collectAllData \\");
        System.err.println("    -version v001 \\");
        System.err.println("    -process P1_Collector \\");
        System.err.println("    -queryVersion v001,v002,v003 \\");
        System.err.println("    -sequenceId 100000");
        System.err.println();
    }

    /**
     * Query OOjDREW rule base for collector service configuration
     */
    private static boolean queryServiceConfiguration(String serviceName, String operationName, String buildVersion) {
        try {
            System.out.println("Querying OOjDREW for activeService facts...");
            
            String ruleMLQuery = "<Query><Atom><Rel>activeService</Rel><Ind>" + serviceName + "</Ind><Ind>"
                    + operationName + "</Ind><Var>channelId</Var><Var>port</Var></Atom></Query>";
            
            System.out.println("OOjDREW Query: " + ruleMLQuery);
            
            // Navigate to common directory - Service.ruleml is in btsn.common/RuleFolder.{version}/
            String ruleBasePath = "../btsn.common/RuleFolder." + buildVersion + "/Service.ruleml";
            System.out.println("Loading rule base from: " + ruleBasePath);
            
            File ruleBaseFile = new File(ruleBasePath);
            if (!ruleBaseFile.exists()) {
                System.err.println("ERROR: Rule base file not found at: " + ruleBaseFile.getAbsolutePath());
                System.err.println("Current working directory: " + new File(".").getAbsolutePath());
                return false;
            }
            
            System.out.println("Found rule base at: " + ruleBaseFile.getAbsolutePath());
            oojdrew.parseKnowledgeBase(ruleBasePath, true);
            oojdrew.issueRuleMLQuery(ruleMLQuery);
            
            System.out.println("Rows returned from activeService query: " + oojdrew.rowsReturned);
            
            if (oojdrew.rowsReturned == 0) {
                System.err.println("No activeService facts found for: " + serviceName + ":" + operationName);
                return false;
            }

            String channelId = null;
            resolvedServicePort = null;

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
        
        System.out.println("Building payload for " + serviceName + " -> " + resolvedChannel + ":" + resolvedPort);

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
        
        System.out.println("File copied.");

        return currentXmlPayload;
    }

    /**
     * Fire collection event
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
     * Supports both local and remote addresses
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
            // Create unbound socket, set reuse address, then bind to ephemeral port
            udpSocket = new DatagramSocket(null);
            udpSocket.setReuseAddress(true);
            udpSocket.bind(null);  // Bind to any available port
            
            // Resolve target address - could be localhost or remote
            InetAddress targetAddress;
            boolean isRemote = !resolvedChannel.equals("localhost") && 
                               !resolvedChannel.equals("127.0.0.1");
            
            if (isRemote) {
                System.out.println("REMOTE service detected: " + resolvedChannel);
                targetAddress = InetAddress.getByName(resolvedChannel);
            } else {
                targetAddress = InetAddress.getLoopbackAddress();
            }
            
            // Parse channel number
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
            
            // Parse base port
            int basePort;
            try {
                basePort = Integer.parseInt(resolvedPort);
            } catch (NumberFormatException e) {
                throw new IOException("FATAL: Invalid port number: " + resolvedPort, e);
            }
            
            // Calculate target port: 10000 + (channel * 1000) + basePort
            int targetPort = 10000 + (channelNumber * 1000) + basePort;
            
            System.out.println("=== SENDING EVENT ===");
            System.out.println("Target Address: " + resolvedChannel);
            System.out.println("Target Port: " + targetPort);
            
            byte[] data = payload.getBytes();
            DatagramPacket dp = new DatagramPacket(data, data.length, targetAddress, targetPort);
            udpSocket.send(dp);
            
            System.out.println("Sent to " + resolvedChannel + ":" + targetPort);
            
        } finally {
            if (udpSocket != null) {
                udpSocket.close();
            }
        }
    }
}