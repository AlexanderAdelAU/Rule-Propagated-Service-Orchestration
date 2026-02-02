package org.btsn.handlers;

import java.io.File;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Scanner;
import java.util.TreeMap;

/**
 * ServiceThreadDebugger - Refactored for current ServiceThread implementation
 * 
 * Compatible with ServiceThread's new agnostic filter and identity model
 */
public class ServiceThreadDebugger {
    
    private static final String TEST_CHANNEL = "224.0.1.7";
    private static final int CALCULATED_UDP_PORT = 10000 + (7 * 1000) + 7; // 17007
    
    // Identity for this ServiceThread instance
    private static final String MY_SERVICE_NAME = "TriageService";
    private static final String MY_OPERATION_NAME = "processTriageAssessment";
    
    private ServiceThread serviceThread;
    private Thread serviceThreadHandle;
    private MockEventReactor mockEventReactor;
    private DebugEventPublisher debugEventPublisher;
    
    public static void main(String[] args) {
        System.out.println("=== ServiceThread Debugger (Updated) ===");
        System.out.println("ServiceThread Identity: " + MY_SERVICE_NAME + ":" + MY_OPERATION_NAME);
        System.out.println("Note: Packets for OTHER services will be filtered out!");
        System.out.println("");
        System.out.println("Choose debugging mode:");
        System.out.println("1. Mock Mode (Controlled packets - RECOMMENDED)");
        System.out.println("2. Real UDP Mode (Full integration)");
        System.out.print("Enter choice (1 or 2): ");
        
        Scanner scanner = new Scanner(System.in);
        String choice = scanner.nextLine();
        
        ServiceThreadDebugger debugger = new ServiceThreadDebugger();
        
        try {
            if ("1".equals(choice)) {
                debugger.runMockMode();
            } else if ("2".equals(choice)) {
                debugger.runRealUDPMode();
            } else {
                System.out.println("Invalid choice. Running Mock Mode...");
                debugger.runMockMode();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        scanner.close();
    }
    
    /**
     * MOCK MODE: Perfect for Eclipse debugging
     */
    public void runMockMode() throws Exception {
        System.out.println("\n=== MOCK MODE: Controlled Debugging ===");
        System.out.println("ServiceThread will ONLY process packets for: " + MY_SERVICE_NAME);
        System.out.println("");
        System.out.println("RULE BASE SETUP:");
        System.out.println("- Adding 'v001', 'v002', 'v003' to valid rule set");
        System.out.println("");
        System.out.println("SET BREAKPOINTS NOW in ServiceThread.parsePayLoad()");
        System.out.println("Press Enter when ready...");
        new Scanner(System.in).nextLine();
        
        // Setup
        setupTestEnvironment();
        debugEventPublisher = new DebugEventPublisher();
        mockEventReactor = new MockEventReactor();
        
        // Create ServiceThread with mock - NOW with identity parameters
        serviceThread = new ServiceThread(
            TEST_CHANNEL, 
            Integer.toString(CALCULATED_UDP_PORT), 
            debugEventPublisher,
            MY_SERVICE_NAME,      // NEW: Service identity
            MY_OPERATION_NAME     // NEW: Operation identity
        );
        
        // Start ServiceThread
        serviceThreadHandle = new Thread(serviceThread, "DebugServiceThread");
        serviceThreadHandle.start();
        
        System.out.println("✓ ServiceThread started (Identity: " + MY_SERVICE_NAME + ":" + MY_OPERATION_NAME + ")");
        System.out.println("✓ Valid rule versions: v001, v002, v003");
        Thread.sleep(1000);
        
        // Interactive packet sending
        runInteractivePacketSender();
    }
    
    /**
     * REAL UDP MODE: Full integration testing
     */
    public void runRealUDPMode() throws Exception {
        System.out.println("\n=== REAL UDP MODE: Full Integration ===");
        System.out.println("ServiceThread Identity: " + MY_SERVICE_NAME + ":" + MY_OPERATION_NAME);
        System.out.println("SET BREAKPOINTS in ServiceThread.parsePayLoad()");
        System.out.println("Press Enter when ready...");
        new Scanner(System.in).nextLine();
        
        // Setup
        setupTestEnvironment();
        debugEventPublisher = new DebugEventPublisher();
        
        // Create ServiceThread with real EventReactor and identity
        serviceThread = new ServiceThread(
            TEST_CHANNEL, 
            Integer.toString(CALCULATED_UDP_PORT), 
            debugEventPublisher,
            MY_SERVICE_NAME,
            MY_OPERATION_NAME
        );
        
        // Start ServiceThread
        serviceThreadHandle = new Thread(serviceThread, "DebugServiceThread");
        serviceThreadHandle.start();
        
        System.out.println("✓ ServiceThread started with Real EventReactor");
        System.out.println("✓ Listening on UDP port: " + CALCULATED_UDP_PORT);
        Thread.sleep(2000);
        
        // Interactive UDP packet sending
        runInteractiveUDPSender();
    }
    
    /**
     * Interactive packet sender for Mock Mode
     */
    private void runInteractivePacketSender() {
        Scanner scanner = new Scanner(System.in);
        
        while (true) {
            System.out.println("\n=== Send Test Packet ===");
            System.out.println("Current ServiceThread Identity: " + MY_SERVICE_NAME);
            System.out.println("");
            System.out.println("1. Triage Assessment (WILL BE ACCEPTED - matches identity)");
            System.out.println("2. Patient Validation (WILL BE FILTERED - wrong service)");
            System.out.println("3. Billing Calculation (WILL BE FILTERED - wrong service)");
            System.out.println("4. Monitor Service (WILL BE FILTERED - wrong service)");
            System.out.println("5. Real Triage Token Format");
            System.out.println("6. Decision Node Test (wrong service)");
            System.out.println("7. Test Invalid Rule Version");
            System.out.println("8. Custom Packet (specify service)");
            System.out.println("0. Exit");
            System.out.print("Choice: ");
            
            String choice = scanner.nextLine();
            
            try {
                switch (choice) {
                    case "1":
                        sendPacket(createTriagePacket());
                        break;
                    case "2":
                        sendPacket(createPatientPacket());
                        System.out.println("NOTE: This will be filtered (wrong service)");
                        break;
                    case "3":
                        sendPacket(createBillingPacket());
                        System.out.println("NOTE: This will be filtered (wrong service)");
                        break;
                    case "4":
                        sendPacket(createMonitorPacket());
                        System.out.println("NOTE: This will be filtered (wrong service)");
                        break;
                    case "5":
                        sendPacket(createRealTriageTokenPacket());
                        break;
                    case "6":
                        sendPacket(createDecisionPacket());
                        System.out.println("NOTE: This will be filtered (wrong service)");
                        break;
                    case "7":
                        sendPacket(createInvalidRuleVersionPacket());
                        break;
                    case "8":
                        sendCustomPacket(scanner);
                        break;
                    case "0":
                        shutdown();
                        return;
                    default:
                        System.out.println("Invalid choice");
                }
                
                System.out.println("Packet sent! Check your debugger...");
                System.out.println("Press Enter to continue...");
                scanner.nextLine();
                
            } catch (Exception e) {
                System.err.println("Error sending packet: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }
    
    /**
     * Interactive UDP sender for Real UDP Mode
     */
    private void runInteractiveUDPSender() {
        Scanner scanner = new Scanner(System.in);
        
        while (true) {
            System.out.println("\n=== Send UDP Packet ===");
            System.out.println("1. Triage Assessment (matches identity)");
            System.out.println("2. Patient Validation (will be filtered)");
            System.out.println("3. Real Triage Token Format");
            System.out.println("0. Exit");
            System.out.print("Choice: ");
            
            String choice = scanner.nextLine();
            
            try {
                String packet = null;
                switch (choice) {
                    case "1":
                        packet = createTriagePacket();
                        break;
                    case "2":
                        packet = createPatientPacket();
                        break;
                    case "3":
                        packet = createRealTriageTokenPacket();
                        break;
                    case "0":
                        shutdown();
                        return;
                    default:
                        System.out.println("Invalid choice");
                        continue;
                }
                
                if (packet != null) {
                    sendUDPPacket(packet, CALCULATED_UDP_PORT);
                    System.out.println("UDP packet sent! Check your debugger...");
                }
                
                System.out.println("Press Enter to continue...");
                scanner.nextLine();
                
            } catch (Exception e) {
                System.err.println("Error sending UDP packet: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }
    
    /**
     * Send packet through Mock EventReactor
     */
    private void sendPacket(String xmlPacket) {
        System.out.println("=== SENDING PACKET TO MOCK ===");
        System.out.println("Service: " + extractServiceInfo(xmlPacket));
        System.out.println("XML Length: " + xmlPacket.length());
        
        String ruleVersion = extractRuleVersion(xmlPacket);
        boolean isValidRule = ServiceLoader.getRuleVersion(ruleVersion);
        System.out.println("Rule Version: " + ruleVersion + " (" + (isValidRule ? "VALID" : "INVALID") + ")");
        
        String targetService = extractServiceName(xmlPacket);
        boolean willBeAccepted = MY_SERVICE_NAME.equals(targetService);
        System.out.println("Target Service: " + targetService + 
                         " (" + (willBeAccepted ? "WILL BE ACCEPTED" : "WILL BE FILTERED") + ")");
        
        mockEventReactor.injectPacket(xmlPacket);
    }
    
    /**
     * Extract service name from XML
     */
    private String extractServiceName(String xml) {
        try {
            String startTag = "<serviceName>";
            String endTag = "</serviceName>";
            int start = xml.indexOf(startTag);
            if (start != -1) {
                start += startTag.length();
                int end = xml.indexOf(endTag, start);
                if (end != -1) {
                    return xml.substring(start, end);
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return "unknown";
    }
    
    /**
     * Extract rule version from XML
     */
    private String extractRuleVersion(String xml) {
        try {
            String startTag = "<ruleBaseVersion>";
            String endTag = "</ruleBaseVersion>";
            int start = xml.indexOf(startTag);
            if (start != -1) {
                start += startTag.length();
                int end = xml.indexOf(endTag, start);
                if (end != -1) {
                    return xml.substring(start, end);
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return "unknown";
    }
    
    /**
     * Send custom packet
     */
    private void sendCustomPacket(Scanner scanner) {
        System.out.print("Service Name: ");
        String service = scanner.nextLine();
        System.out.print("Operation: ");
        String operation = scanner.nextLine();
        System.out.print("Attribute Name: ");
        String attrName = scanner.nextLine();
        System.out.print("Attribute Value: ");
        String attrValue = scanner.nextLine();
        
        boolean willMatch = MY_SERVICE_NAME.equals(service);
        System.out.println("NOTE: " + (willMatch ? "This WILL be processed" : "This will be FILTERED (wrong service)"));
        
        String packet = createTestXMLPacket(service, operation, attrName, attrValue, generateSequenceId());
        sendPacket(packet);
    }
    
    // ===== Pre-built Test Packets =====
    
    private String createTriagePacket() {
        return createTestXMLPacket(MY_SERVICE_NAME, MY_OPERATION_NAME, "token", "basicTriageToken", generateSequenceId());
    }
    
    private String createRealTriageTokenPacket() {
        String triageTokenValue = "{\"token\": \"\\\"P_Triage\\\", \\\"RN001\\\"\"}";
        return createTestXMLPacket(MY_SERVICE_NAME, MY_OPERATION_NAME, "token", triageTokenValue, generateSequenceId());
    }
    
    private String createPatientPacket() {
        return createTestXMLPacket("PatientService", "validatePatient", "patientId", "P12345", generateSequenceId());
    }
    
    private String createBillingPacket() {
        return createTestXMLPacket("BillingService", "calculateBill", "amount", "150.00", generateSequenceId());
    }
    
    private String createMonitorPacket() {
        return createTestXMLPacket("MonitorService", "setMeasureProcessTime", "token", "debugToken", generateSequenceId());
    }
    
    private String createDecisionPacket() {
        return createTestXMLPacket("DecisionService", "evaluateCondition", "score", "85", generateSequenceId());
    }
    
    private String createInvalidRuleVersionPacket() {
        int sequenceId = generateSequenceId();
        long currentTime = System.currentTimeMillis();
        long notAfter = currentTime + 30000;
        
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
               "<payload>\n" +
               "  <header>\n" +
               "    <sequenceId>" + sequenceId + "</sequenceId>\n" +
               "    <ruleBaseVersion>vINVALID</ruleBaseVersion>\n" +
               "    <priortiseSID>false</priortiseSID>\n" +
               "    <monitorIncomingEvents>true</monitorIncomingEvents>\n" +
               "  </header>\n" +
               "  <service>\n" +
               "    <serviceName>" + MY_SERVICE_NAME + "</serviceName>\n" +
               "    <operation>" + MY_OPERATION_NAME + "</operation>\n" +
               "  </service>\n" +
               "  <joinAttribute>\n" +
               "    <attributeName>token</attributeName>\n" +
               "    <attributeValue>testValue</attributeValue>\n" +
               "    <notAfter>" + notAfter + "</notAfter>\n" +
               "    <status>active</status>\n" +
               "  </joinAttribute>\n" +
               "  <monitorData>\n" +
               "    <processStartTime>" + currentTime + "</processStartTime>\n" +
               "    <processElapsedTime>0</processElapsedTime>\n" +
               "  </monitorData>\n" +
               "</payload>";
    }
    
    // ===== Helper Methods =====
    
    private String createTestXMLPacket(String serviceName, String operation, String attributeName, String attributeValue, int sequenceId) {
        long currentTime = System.currentTimeMillis();
        long notAfter = currentTime + 30000;
        
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
               "<payload>\n" +
               "  <header>\n" +
               "    <sequenceId>" + sequenceId + "</sequenceId>\n" +
               "    <ruleBaseVersion>v001</ruleBaseVersion>\n" +
               "    <priortiseSID>false</priortiseSID>\n" +
               "    <monitorIncomingEvents>true</monitorIncomingEvents>\n" +
               "  </header>\n" +
               "  <service>\n" +
               "    <serviceName>" + serviceName + "</serviceName>\n" +
               "    <operation>" + operation + "</operation>\n" +
               "  </service>\n" +
               "  <joinAttribute>\n" +
               "    <attributeName>" + attributeName + "</attributeName>\n" +
               "    <attributeValue>" + attributeValue + "</attributeValue>\n" +
               "    <notAfter>" + notAfter + "</notAfter>\n" +
               "    <status>active</status>\n" +
               "  </joinAttribute>\n" +
               "  <monitorData>\n" +
               "    <processStartTime>" + currentTime + "</processStartTime>\n" +
               "    <processElapsedTime>0</processElapsedTime>\n" +
               "    <callingService>DebugService</callingService>\n" +
               "  </monitorData>\n" +
               "</payload>";
    }
    
    private void sendUDPPacket(String xmlPayload, int targetPort) throws IOException {
        DatagramSocket udpSocket = null;
        try {
            udpSocket = new DatagramSocket();
            
            InetAddress targetAddress = InetAddress.getLoopbackAddress();
            byte[] data = xmlPayload.getBytes();
            DatagramPacket packet = new DatagramPacket(data, data.length, targetAddress, targetPort);
            
            int channelNumber = extractChannelNumber(TEST_CHANNEL);
            int basePort = 7;
            int channelOffset = channelNumber * 1000;
            int calculatedPort = 10000 + channelOffset + basePort;
            
            System.out.println("=== UDP PORT CALCULATION ===");
            System.out.println("Channel: " + TEST_CHANNEL + " -> Channel Number: " + channelNumber);
            System.out.println("Calculated Port: " + calculatedPort);
            System.out.println("Target Port: " + targetPort);
            
            udpSocket.send(packet);
            System.out.println("✓ Sent UDP packet to localhost:" + targetPort + " (" + data.length + " bytes)");
            
        } finally {
            if (udpSocket != null) {
                udpSocket.close();
            }
        }
    }
    
    private int extractChannelNumber(String channel) {
        try {
            String[] parts = channel.split("\\.");
            if (parts.length >= 4) {
                return Integer.parseInt(parts[3]);
            }
        } catch (Exception e) {
            System.err.println("Could not parse channel number from: " + channel);
        }
        return 1;
    }
    
    private String extractServiceInfo(String xml) {
        try {
            String service = xml.substring(xml.indexOf("<serviceName>") + 13, xml.indexOf("</serviceName>"));
            String operation = xml.substring(xml.indexOf("<operation>") + 11, xml.indexOf("</operation>"));
            return service + "." + operation;
        } catch (Exception e) {
            return "Unknown service";
        }
    }
    
    private int generateSequenceId() {
        return (int) (System.currentTimeMillis() % 100000);
    }
    
    private void setupTestEnvironment() throws Exception {
        File serviceLoaderDir = new File("./ServiceLoaderQueries");
        if (!serviceLoaderDir.exists()) {
            serviceLoaderDir.mkdirs();
        }
        
        String loaderSettings = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<settings>" +
                "<ReactorSettings>" +
                "<maxQueue>5</maxQueue>" +
                "<poolSize>2</poolSize>" +
                "</ReactorSettings>" +
                "<MonitorSettings>" +
                "<monitorIncomingEvents>true</monitorIncomingEvents>" +
                "<enableCompletedJoinPriority>true</enableCompletedJoinPriority>" +
                "</MonitorSettings>" +
                "</settings>";
        
        java.nio.file.Files.write(
            java.nio.file.Paths.get("./ServiceLoaderQueries/loaderSettings.xml"), 
            loaderSettings.getBytes()
        );
        
        // Add valid rule versions
        ServiceLoader.setRuleVersion("v001", true);
        ServiceLoader.setRuleVersion("v002", true);
        ServiceLoader.setRuleVersion("v003", true);
        
        System.out.println("✓ Test environment setup complete");
        System.out.println("✓ Valid rule versions: v001, v002, v003");
    }
    
    private void shutdown() {
        System.out.println("\n=== Shutting Down ===");
        
        if (serviceThread != null) {
            serviceThread.shutdown();
        }
        
        if (serviceThreadHandle != null) {
            serviceThreadHandle.interrupt();
        }
        
        if (mockEventReactor != null) {
            mockEventReactor.shutdown();
        }
        
        System.out.println("✓ Shutdown complete");
        System.exit(0);
    }
    
    // ===== Mock Classes =====
    
    /**
     * Mock EventReactor for controlled debugging
     */
    private class MockEventReactor {
        private TreeMap<Long, String> tokenQueue = new TreeMap<>();
        private boolean shutdown = false;
        private long costKey = 1000L;
        
        public synchronized TreeMap<Long, String> getScheduledToken() throws InterruptedException {
            TreeMap<Long, String> result = new TreeMap<>();
            
            while (tokenQueue.isEmpty() && !shutdown) {
                wait(100);
            }
            
            if (!tokenQueue.isEmpty()) {
                Long firstKey = tokenQueue.firstKey();
                String token = tokenQueue.remove(firstKey);
                result.put(firstKey, token);
            }
            
            return result;
        }
        
        public synchronized void injectPacket(String xmlPacket) {
            costKey++;
            tokenQueue.put(costKey, xmlPacket);
            System.out.println("MockEventReactor: Injected packet with costKey=" + costKey);
            notifyAll();
        }
        
        public void shutdown() {
            shutdown = true;
            synchronized (this) {
                notifyAll();
            }
        }
    }
    
    /**
     * Debug EventPublisher
     */
    private class DebugEventPublisher extends EventPublisher {
        public DebugEventPublisher() throws Exception {
            super();
        }

        @Override
        public boolean publishServiceEvent(String serviceName, String operationName, 
                String xmlPayload, String channel, String port, String ruleVersion, String originalChannelId) {
            
            System.out.println("\n=== DEBUG: EventPublisher Called ===");
            System.out.println("Would publish to: " + serviceName + "." + operationName);
            System.out.println("Channel: " + channel + ", Port: " + port);
            System.out.println("Rule Version: " + ruleVersion);
            System.out.println("=== END DEBUG ===\n");
            
            return true;
        }
    }
}