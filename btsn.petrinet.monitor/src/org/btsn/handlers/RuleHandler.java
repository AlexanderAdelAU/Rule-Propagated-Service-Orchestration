package org.btsn.handlers;

import java.io.File;
import java.io.IOException;
import java.net.*;

import org.btsn.utils.BuildRuleBase;
import org.btsn.utils.CreateDirectory;
import org.btsn.utils.CreateFile;
import org.btsn.utils.StringFileIO;
import org.btsn.utils.XPathHelper;

/**
 * RuleHandler with Remote Host Support
 * FIXED: Sends commitment responses back to originating machine
 */
public class RuleHandler {

    private static int MAX_WIRE_LENGTH = 8196;
    private static String incomingXMLrulePayLoad;
    static String controlFileName = "-ControlNodeRules.ruleml.xml";
    
    private DatagramSocket udpSocket;
    private volatile boolean running = true;
    private Thread receiverThread;
    
    // Track if running in remote mode
    private boolean isRemoteMode = false;
    
    // FIXED: Track the source of incoming rules
    private static ThreadLocal<InetAddress> lastSourceAddress = new ThreadLocal<>();
    private static ThreadLocal<String> lastRuleVersion = new ThreadLocal<>();

    /**
     * Constructor - creates UDP socket and starts receiver thread
     */
    public RuleHandler(String ruleChannel, String rulePort) throws RuntimeException, IOException {
        // Check if we're running in remote mode
        String remoteHost = System.getProperty("service.remote.host");
        if (remoteHost != null) {
            isRemoteMode = true;
            System.out.println("RuleHandler: Remote mode detected, will listen on all interfaces");
        }

        try {
            int port = Integer.parseInt(rulePort);
            
            if (isRemoteMode) {
                // REMOTE MODE: Bind to all interfaces (0.0.0.0)
                udpSocket = new DatagramSocket(port, InetAddress.getByName("0.0.0.0"));
                System.out.println("RuleHandler: Created UDP socket on port " + port + 
                    " listening on ALL INTERFACES (remote mode)");
            } else {
                // LOCAL MODE: Bind to localhost only
                udpSocket = new DatagramSocket(port, InetAddress.getLoopbackAddress());
                System.out.println("RuleHandler: Created UDP socket on port " + port + 
                    " listening on LOCALHOST ONLY (local mode)");
            }
            
            udpSocket.setSoTimeout(1000); // 1 second timeout for checking interrupts
            
            // Start receiver thread
            receiverThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    receiveLoop();
                }
            });
            receiverThread.setName("RuleHandler-UDP-" + rulePort);
            receiverThread.start();
            
            System.out.println("Rule Handler is listening on UDP port: " + rulePort + 
                " (mode: " + (isRemoteMode ? "REMOTE" : "LOCAL") + ")");

        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("Can't create UDP socket on port: " + rulePort);
            throw new IOException("Failed to create UDP socket for port: " + rulePort + ". Error: " + e.getMessage(), e);
        }
    }
    
    /**
     * UDP message receiving loop with remote source tracking
     */
    private void receiveLoop() {
        byte[] buffer = new byte[MAX_WIRE_LENGTH];
        
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                DatagramPacket rulePacket = new DatagramPacket(buffer, buffer.length);
                udpSocket.receive(rulePacket);
                
                // Log the source of the packet
                InetAddress sourceAddress = rulePacket.getAddress();
                int sourcePort = rulePacket.getPort();
                
                // FIXED: Store the source address for later use in sendSync
                lastSourceAddress.set(sourceAddress);
                
                incomingXMLrulePayLoad = new String(rulePacket.getData(), 0, rulePacket.getLength());
                
                if (isRemoteMode) {
                    System.err.println("Rule Handler received from " + sourceAddress.getHostAddress() + 
                        ":" + sourcePort + " - payload length: " + rulePacket.getLength());
                } else {
                    System.err.println("Rule Handler received: " + incomingXMLrulePayLoad);
                }
                
                // Process the message
                processRuleMessage(incomingXMLrulePayLoad);
                
                // Send acknowledgment back to sender if remote
                if (isRemoteMode) {
                    sendAcknowledgment(sourceAddress, sourcePort);
                }
                
            } catch (SocketTimeoutException e) {
                // Normal timeout, continue
            } catch (IOException e) {
                if (running) {
                    System.err.println("UDP receive error: " + e.getMessage());
                }
                break;
            }
        }
        
        System.out.println("RuleHandler receive loop terminated");
    }
    
    /**
     * Send acknowledgment back to remote sender
     */
    private void sendAcknowledgment(InetAddress sourceAddress, int sourcePort) {
        try {
            String ackMessage = "ACK:RULE_RECEIVED";
            byte[] ackData = ackMessage.getBytes();
            DatagramPacket ackPacket = new DatagramPacket(ackData, ackData.length, 
                sourceAddress, sourcePort);
            udpSocket.send(ackPacket);
            System.out.println("Sent acknowledgment to " + sourceAddress.getHostAddress());
        } catch (IOException e) {
            System.err.println("Failed to send acknowledgment: " + e.getMessage());
        }
    }
    
    /**
     * Process rule messages
     */
    private void processRuleMessage(String incomingXMLrulePayLoad) {
        try {
        	
        	System.err.println("=== RECEIVED XML PAYLOAD ===");
        	System.err.println(incomingXMLrulePayLoad);
        	System.err.println("=== END PAYLOAD ===");
        	
            String serviceName = XPathHelper.findXMLItem(incomingXMLrulePayLoad,
                    "//rulepayload/targetservice/serviceName/text()");
            String operationName = XPathHelper.findXMLItem(incomingXMLrulePayLoad,
                    "//rulepayload/targetservice/operationName/text()");

            System.out.println("Received rule payload packet.....for operation: " + operationName
                    + incomingXMLrulePayLoad);

            String xmlPath = "//rulepayload/header/ruleBaseVersion/text()";
            String ruleBaseVersion = XPathHelper.findXMLItem(incomingXMLrulePayLoad, xmlPath);
            
            // FIXED: Store the version for commitment response
            lastRuleVersion.set(ruleBaseVersion);
            
            // Also extract commitment count
            String commitmentPath = "//rulepayload/header/ruleBaseCommitment/text()";
            String commitmentCount = XPathHelper.findXMLItem(incomingXMLrulePayLoad, commitmentPath);
            
            String path = new File("").getAbsolutePath();
            String rulePath = path + "/RuleFolder." + ruleBaseVersion;
            String operationRulePath = rulePath + "/" + operationName;

            String ruleFileName = serviceName + controlFileName;
            if (!CreateDirectory.createDirectory(operationRulePath)) {
                System.err.println("RuleHandler: Unable to create rule directory" + operationRulePath);
                System.exit(1);
            }
            CreateFile.createFile(operationRulePath + "/" + ruleFileName);
            xmlPath = "//rulepayload/rulefiledata/data/text()";
            String cdata = XPathHelper.findXMLItem(incomingXMLrulePayLoad, xmlPath);
            StringFileIO.writeStringToFile(cdata, operationRulePath + "/" + ruleFileName, cdata.length());

            BuildRuleBase.buildOperationRuleBase(serviceName, operationName, ruleBaseVersion);

            // FIXED: Send commitment with version and count
         //   String commitmentMessage = "CONFIRMED:" + ruleBaseVersion + ":" + commitmentCount;
            String commitmentMessage = "<?xml version=\"1.0\"?>" +
            	    "<rulepayload>" +
            	    "<header>" +
            	    "<ruleBaseVersion>" + ruleBaseVersion + "</ruleBaseVersion>" +
            	    "<ruleBaseCommitment>" + commitmentCount + "</ruleBaseCommitment>" +
            	    "</header>" +
            	    "<targetservice>" +
            	    "<serviceName>" + serviceName + "</serviceName>" +
            	    "<operationName>" + operationName + "</operationName>" +
            	    "</targetservice>" +
            	    "</rulepayload>";
            sendCommitment(commitmentMessage);

            ServiceLoader.setRuleVersion(ruleBaseVersion, true);
            
        } catch (Exception e) {
            System.err.println("Error processing rule message: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * FIXED: Send commitment back to the originating machine
     */
    private void sendCommitment(String commitmentMessage) {
        DatagramSocket ms = null;
        try {
            ms = new DatagramSocket();
            
            // FIXED: Determine target based on mode
            InetAddress targetAddress;
            int targetPort;
            
            InetAddress sourceAddr = lastSourceAddress.get();
            String version = lastRuleVersion.get();
            
            if (sourceAddr != null && !sourceAddr.isLoopbackAddress()) {
                // Remote mode: send back to source
                targetAddress = sourceAddr;
                // Calculate commitment listener port (35000 + version number)
                targetPort = calculateCommitmentPort(version);
                System.out.println("Sending commitment to remote source: " + 
                    targetAddress.getHostAddress() + ":" + targetPort);
            } else {
                // Local mode: send to localhost
                targetAddress = InetAddress.getLoopbackAddress();
                targetPort = 30000; // Default local port
                System.out.println("Sending commitment to localhost:" + targetPort);
            }
            
            // FIX: Convert XML to simple format if needed
            String messageToSend = commitmentMessage;
            if (commitmentMessage.contains("<ruleBaseVersion>") && commitmentMessage.contains("<ruleBaseCommitment>")) {
                try {
                    // Extract version and commitment from XML
                    String extractedVersion = commitmentMessage.substring(
                        commitmentMessage.indexOf("<ruleBaseVersion>") + 17,
                        commitmentMessage.indexOf("</ruleBaseVersion>")
                    );
                    String extractedCommitment = commitmentMessage.substring(
                        commitmentMessage.indexOf("<ruleBaseCommitment>") + 20,
                        commitmentMessage.indexOf("</ruleBaseCommitment>")
                    );
                    
                    // Create the simple format expected by RulePropagation
                    messageToSend = "CONFIRMED:" + extractedVersion + ":" + extractedCommitment;
                    System.out.println("Converted XML to simple format: " + messageToSend);
                } catch (Exception e) {
                    System.err.println("Error extracting from XML, using original: " + e.getMessage());
                }
            }
            
            byte[] data = messageToSend.getBytes();
            DatagramPacket dp = new DatagramPacket(data, data.length, targetAddress, targetPort);
            
            ms.send(dp);
            System.out.println("Sent commitment response: " + messageToSend + 
                " to " + targetAddress.getHostAddress() + ":" + targetPort);
            
        } catch (SocketException se) {
            System.err.println("Socket error in sendCommitment: " + se.getMessage());
        } catch (IOException ie) {
            System.err.println("IO error in sendCommitment: " + ie.getMessage());
        } finally {
            if (ms != null) {
                ms.close();
            }
        }
    }
    
    /**
     * Calculate commitment listener port based on version
     * Must match RulePropagation's calculation
     */
    private int calculateCommitmentPort(String version) {
        int BASE_CONFIRMATION_PORT = 35000;
        
        try {
            // Extract numeric part from version (e.g., "v003" -> 3)
            String numericPart = version.replaceAll("[^0-9]", "");
            if (!numericPart.isEmpty()) {
                int versionNumber = Integer.parseInt(numericPart);
                return BASE_CONFIRMATION_PORT + versionNumber;
            }
        } catch (NumberFormatException e) {
            System.err.println("Could not parse version number from: " + version);
        }
        
        // Fallback: use hash-based offset
        int hashOffset = Math.abs(version.hashCode() % 100) + 1;
        return BASE_CONFIRMATION_PORT + hashOffset;
    }
    
    /**
     * Old sendSync method - deprecated, use sendCommitment instead
     */
    public static void sendSync(String rChannel, String rPort, String buildVersion) {
        // This method is no longer used - replaced by sendCommitment
        System.out.println("Warning: sendSync called but deprecated - use sendCommitment instead");
    }
    
    /**
     * Shutdown the RuleHandler
     */
    public void shutdown() {
        running = false;
        
        if (udpSocket != null && !udpSocket.isClosed()) {
            udpSocket.close();
            System.out.println("RuleHandler: UDP socket closed");
        }
        
        if (receiverThread != null) {
            receiverThread.interrupt();
            try {
                receiverThread.join(1000);
            } catch (InterruptedException e) {
                // Ignore
            }
        }
    }
}