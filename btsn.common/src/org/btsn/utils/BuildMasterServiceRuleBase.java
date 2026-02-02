package org.btsn.utils;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * BuildMasterServiceRuleBase - Creates the MASTER Service.ruleml file
 * 
 * ENHANCED: Now supports hierarchical ServiceAttributeBindings structure
 * - Looks for bindings in both /ServiceAttributeBindings/{ServiceName}/*.xml
 * - AND in flat structure /ServiceAttributeBindings/*.xml (backward compatibility)
 * 
 * RULE FILE SEARCH: Searches only btsn.common/RuleBase directory (recursively)
 * - Includes subdirectories like Healthcare/, PetriNet/, etc.
 * - Does NOT search other directories like RulePayLoad, RuleBasex, etc.
 * - Core rule files (CoreRuleBase, NetworkFacts) are loaded first for proper precedence
 * 
 * ARCHITECTURAL DECISION:
 * - Uses OOjDREW to query and filter rules (not string manipulation!)
 * - Creates /btsn.common/RuleFolder.{version}/Service.ruleml
 * - Filters content based on requested services using OOjDREW queries
 * - This is the SINGLE SOURCE OF TRUTH for service resolution
 * 
 * Usage: 
 * - With arguments: java BuildMasterServiceRuleBase {version} {service1,service2,...}
 * - Without arguments: Uses DEFAULT_VERSION and DEFAULT_SERVICES (see static fields)
 */
public class BuildMasterServiceRuleBase {
    
    private static final Logger logger = Logger.getLogger(BuildMasterServiceRuleBase.class.getName());
    
    // DEFAULT VALUES FOR LOCAL TESTING - Change these as needed
    private static final String DEFAULT_VERSION = "v001";
    private static final String DEFAULT_SERVICES = "ALL";
    private static final boolean USE_DEFAULTS_WHEN_NO_ARGS = true;
    
    // OOjDREW API for rule queries
    private static final OOjdrewAPI oojdrew = new OOjdrewAPI();
    
    // Core rule files that must always be loaded FIRST (order matters for these)
    // These are loaded before any other files to ensure proper rule precedence
    private static final String[] CORE_RULE_FILES = {
        "CoreRuleBase.ruleml.xml",
        "NetworkFacts.ruleml.xml"
    };
    
    // Files that should NEVER be filtered (always included in full)
    // These define network topology and must always be complete
    private static final Set<String> NEVER_FILTER_FILES = new HashSet<>(Arrays.asList(
        "ListofActiveServices.ruleml.xml",
        "RemoteHostMappings.ruleml.xml"
    ));
    
    private final String version;
    private final Set<String> requestedServices;
    private final String basePath;
    private final String commonPath;
    private final String ruleBasePath;
    private final String outputPath;
    private final StringBuilder masterContent;
    
    // Track what channels and facts we need
    private final Set<String> neededChannels = new HashSet<>();
    private final Set<String> neededOperations = new HashSet<>();
    
    /**
     * Main entry point for command line
     */
    public static void main(String[] args) {
        String version;
        Set<String> services;
        
        System.out.println("DEBUG: BuildMaster args.length = " + args.length);
        for (int i = 0; i < args.length; i++) {
            System.out.println("DEBUG: BuildMaster args[" + i + "] = '" + args[i] + "'");
        }
        
        // Check argument count and handle accordingly
        if (args.length == 1) {
            System.out.println("===== USING VERSION WITH DEFAULT SERVICES =====");
            System.out.println("  Version: " + args[0]);
            System.out.println("  Services: ALL (default)");
            System.out.println("================================================");
            version = args[0];
            services = parseServices("ALL");
        } else if (args.length < 2) {
            if (USE_DEFAULTS_WHEN_NO_ARGS) {
                System.out.println("===== USING DEFAULT VALUES =====");
                System.out.println("No arguments provided, using defaults:");
                System.out.println("  Version: " + DEFAULT_VERSION);
                System.out.println("  Services: " + DEFAULT_SERVICES);
                System.out.println("================================");
                version = DEFAULT_VERSION;
                services = parseServices(DEFAULT_SERVICES);
            } else {
                printUsageError();
                throw new IllegalArgumentException("Missing required arguments");
            }
        } else {
            version = args[0];
            services = parseServices(args[1]);
            System.out.println("DEBUG: version set to: '" + version + "'");
        }
        
        try {
            BuildMasterServiceRuleBase builder = new BuildMasterServiceRuleBase(version, services);
            builder.build();
            
        } catch (Exception e) {
            logger.severe("Failed to build master Service.ruleml: " + e.getMessage());
            e.printStackTrace();
            System.err.println("===== BUILD FAILED =====");
            System.err.println("Error: " + e.getMessage());
            System.err.println("========================");
            throw new RuntimeException("Build failed: " + e.getMessage(), e);
        }
    }
    
    private static void printUsageError() {
        System.err.println("===== USAGE ERROR =====");
        System.err.println("Usage: java BuildMasterServiceRuleBase {version} {services}");
        System.err.println("Example: java BuildMasterServiceRuleBase v001 TriageService,MonitorService");
        System.err.println("Example: java BuildMasterServiceRuleBase v001 ALL");
        System.err.println("");
        System.err.println("Arguments:");
        System.err.println("  version  - Version identifier (e.g., v001)");
        System.err.println("  services - Comma-separated list of services to include");
        System.err.println("            Use 'ALL' to include all services");
        System.err.println("");
        System.err.println("Or run without arguments to use defaults:");
        System.err.println("  DEFAULT_VERSION = " + DEFAULT_VERSION);
        System.err.println("  DEFAULT_SERVICES = " + DEFAULT_SERVICES);
        System.err.println("=======================");
    }
    
    private static Set<String> parseServices(String servicesArg) {
        if ("ALL".equalsIgnoreCase(servicesArg)) {
            return new HashSet<>(); // Empty set means include all
        }
        return Arrays.stream(servicesArg.split(","))
                     .map(String::trim)
                     .filter(s -> !s.isEmpty())
                     .collect(Collectors.toSet());
    }
    
    /**
     * Constructor
     */
    public BuildMasterServiceRuleBase(String version, Set<String> requestedServices) throws IOException {
        this.version = Objects.requireNonNull(version, "Version cannot be null");
        this.requestedServices = Objects.requireNonNull(requestedServices, "Services cannot be null");
        this.masterContent = new StringBuilder();
        
        // Find paths - must initialize all final fields
        File currentDir = new File("").getAbsoluteFile();
        File searchDir = currentDir;
        String foundBasePath = null;
        String foundCommonPath = null;
        
        while (searchDir != null) {
            File commonDir = new File(searchDir, "btsn.common");
            if (commonDir.exists() && commonDir.isDirectory()) {
                foundBasePath = searchDir.getAbsolutePath();
                foundCommonPath = commonDir.getAbsolutePath();
                break;
            }
            searchDir = searchDir.getParentFile();
        }
        
        if (foundCommonPath == null) {
            throw new IOException("Cannot find btsn.common directory!");
        }
        
        this.basePath = foundBasePath;
        this.commonPath = foundCommonPath;
        this.ruleBasePath = commonPath + File.separator + "RuleBase";  // Only search RuleBase directory
        this.outputPath = commonPath + File.separator + "RuleFolder." + version;
        System.out.println("DEBUG: outputPath set to: '" + outputPath + "'");
        printBuildConfiguration();
    }
    
    private void printBuildConfiguration() {
        System.out.println("===== BUILDING MASTER SERVICE RULE BASE =====");
        System.out.println("Version: " + version);
        System.out.println("Services: " + (requestedServices.isEmpty() ? "ALL" : String.join(", ", requestedServices)));
        System.out.println("Hospital Root: " + basePath);
        System.out.println("Common Path: " + commonPath);
        System.out.println("Rule Base Path: " + ruleBasePath + " (recursive search within RuleBase only)");
        System.out.println("Output Path: " + outputPath);
        System.out.println("==============================================");
    }
    
    /**
     * Build the master Service.ruleml file using OOjDREW
     */
    public void build() throws Exception {
        Files.createDirectories(Paths.get(outputPath));
        
        System.out.println("\n=== Loading rules into OOjDREW ===");
        loadAllRulesIntoOOjDREW();
        
        System.out.println("\n=== Querying OOjDREW for required facts ===");
        queryRequiredFacts();
        
        System.out.println("\n=== Building filtered Service.ruleml ===");
        buildFilteredServiceRuleML();
        
        writeMasterFile();
        
        printBuildSummary();
    }
    
    /**
     * Load all rule files into OOjDREW for querying
     * ENHANCED v2: Now recursively searches ALL directories for rule files
     * No longer limited to just the RuleBase subdirectory
     */
    private void loadAllRulesIntoOOjDREW() throws IOException {
        StringBuilder allRules = new StringBuilder();
        allRules.append("<Assert>\n<Rulebase mapClosure=\"universal\">\n");
        
        // Track which files we've loaded to avoid duplicates
        Set<String> loadedFiles = new HashSet<>();
        
        // ENHANCED: Recursively find ALL rule files from the entire btsn.common directory
        File commonDir = new File(ruleBasePath);
        if (!commonDir.exists() || !commonDir.isDirectory()) {
            throw new IOException("Common directory not found: " + ruleBasePath);
        }
        
        List<File> allRuleFiles = new ArrayList<>();
        findRuleFilesRecursively(commonDir, allRuleFiles);
        
        if (allRuleFiles.isEmpty()) {
            System.out.println("WARNING: No rule files found in: " + ruleBasePath);
        } else {
            // Sort files - core files first, then alphabetically
            allRuleFiles.sort((a, b) -> {
                // Core files come first
                boolean aIsCore = isCoreRuleFile(a.getName());
                boolean bIsCore = isCoreRuleFile(b.getName());
                if (aIsCore && !bIsCore) return -1;
                if (!aIsCore && bIsCore) return 1;
                return a.getName().compareTo(b.getName());
            });
            
            System.out.println("\nRecursively loading rule files from: " + ruleBasePath);
            for (File ruleFile : allRuleFiles) {
                String relativePath = getRelativePath(ruleFile, commonDir);
                if (!loadedFiles.contains(ruleFile.getAbsolutePath())) {
                    System.out.println("Loading: " + relativePath);
                    String content = readRuleFile(ruleFile);
                    
                    if (content.contains("--") && !content.contains("<!--")) {
                        System.out.println("WARNING: File " + relativePath + " has -- outside of comments");
                    }
                    
                    allRules.append("\n<!-- BEGIN: ").append(relativePath).append(" -->\n");
                    allRules.append(content).append("\n");
                    allRules.append("<!-- END: ").append(relativePath).append(" -->\n");
                    loadedFiles.add(ruleFile.getAbsolutePath());
                }
            }
            System.out.println("Loaded " + loadedFiles.size() + " rule files total");
        }
        
        // Load canonical bindings - ENHANCED for hierarchical structure
        loadCanonicalBindingsHierarchical(allRules);
        
        allRules.append("</Rulebase>\n</Assert>");
        
        System.out.println("Parsing knowledge base with OOjDREW...");
        oojdrew.parseKnowledgeBase(allRules.toString(), false);
        System.out.println("Knowledge base loaded successfully");
    }
    
    /**
     * Check if a file is a core rule file (should be loaded first)
     */
    private boolean isCoreRuleFile(String fileName) {
        for (String coreFile : CORE_RULE_FILES) {
            if (fileName.equals(coreFile)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Get relative path from a base directory
     */
    private String getRelativePath(File file, File baseDir) {
        try {
            Path filePath = file.toPath().toAbsolutePath().normalize();
            Path basePath = baseDir.toPath().toAbsolutePath().normalize();
            return basePath.relativize(filePath).toString();
        } catch (Exception e) {
            return file.getName();
        }
    }
    
    // Directories to exclude from recursive search (within RuleBase)
    // Much simpler now that we only search RuleBase directory
    private static final Set<String> EXCLUDED_DIRECTORIES = new HashSet<>(Arrays.asList(
        ".git",
        "target",
        "bin",
        "build"
    ));
    
    /**
     * Recursively find all rule files in the RuleBase directory tree
     * Searches RuleBase and its subdirectories (Healthcare, PetriNet, etc.)
     */
    private void findRuleFilesRecursively(File dir, List<File> ruleFiles) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) {
            return;
        }
        
        // Check if this directory should be excluded
        String dirName = dir.getName();
        if (EXCLUDED_DIRECTORIES.contains(dirName)) {
            return;
        }
        
        // Find rule files in current directory (.ruleml.xml and .xml files)
        File[] files = dir.listFiles((d, name) -> 
            name.endsWith(".ruleml.xml") || name.endsWith(".xml"));
        
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    ruleFiles.add(file);
                }
            }
        }
        
        // Recurse into subdirectories (e.g., Healthcare, PetriNet)
        File[] subdirs = dir.listFiles(File::isDirectory);
        if (subdirs != null) {
            for (File subdir : subdirs) {
                findRuleFilesRecursively(subdir, ruleFiles);
            }
        }
    }
    
    /**
     * NEW: Load canonical bindings from hierarchical structure
     */
    private void loadCanonicalBindingsHierarchical(StringBuilder allRules) throws IOException {
        String bindingsPath = commonPath + File.separator + "ServiceAttributeBindings";
        File bindingsDir = new File(bindingsPath);
        
        if (!bindingsDir.exists() || !bindingsDir.isDirectory()) {
            System.out.println("WARNING: ServiceAttributeBindings directory not found");
            return;
        }
        
        // Collect all binding files from both hierarchical and flat structures
        Map<String, File> bindingFiles = new TreeMap<>(); // Use TreeMap for sorted output
        
        // First, check for service subdirectories (new hierarchical structure)
        File[] serviceDirs = bindingsDir.listFiles(File::isDirectory);
        if (serviceDirs != null) {
            for (File serviceDir : serviceDirs) {
                String serviceName = serviceDir.getName();
                System.out.println("Scanning service directory: " + serviceName);
                
                File[] serviceBindings = serviceDir.listFiles((dir, name) -> 
                    name.endsWith("-CanonicalBindings.ruleml.xml"));
                
                if (serviceBindings != null) {
                    for (File bindingFile : serviceBindings) {
                        String key = serviceName + "/" + bindingFile.getName();
                        bindingFiles.put(key, bindingFile);
                        System.out.println("  Found: " + bindingFile.getName());
                    }
                }
            }
        }
        
        // Then, check for files in the root directory (backward compatibility)
        File[] rootBindings = bindingsDir.listFiles((dir, name) -> 
            name.endsWith("-CanonicalBindings.ruleml.xml"));
        
        if (rootBindings != null) {
            System.out.println("Scanning root directory for legacy bindings...");
            for (File bindingFile : rootBindings) {
                String key = bindingFile.getName(); // No subdirectory prefix
                if (!bindingFiles.containsKey(key)) { // Don't override hierarchical files
                    bindingFiles.put(key, bindingFile);
                    System.out.println("  Found legacy: " + bindingFile.getName());
                }
            }
        }
        
        // Load all collected binding files
        for (Map.Entry<String, File> entry : bindingFiles.entrySet()) {
            System.out.println("Loading bindings: " + entry.getKey());
            String content = readRuleFile(entry.getValue());
            allRules.append(content).append("\n");
        }
    }
    
    /**
     * Read and clean a rule file
     */
    private String readRuleFile(File file) throws IOException {
        String content = new String(Files.readAllBytes(file.toPath()));
        
        // Remove XML declarations and wrapper tags
        content = content.replaceAll("<\\?xml[^>]*\\?>", "");
        content = content.replaceAll("<Assert[^>]*>", "");
        content = content.replaceAll("</Assert>", "");
        content = content.replaceAll("<Rulebase[^>]*>", "");
        content = content.replaceAll("</Rulebase>", "");
        
        return content.trim();
    }
    
    /**
     * Query OOjDREW to find required facts
     */
    private void queryRequiredFacts() {
        if (requestedServices.isEmpty()) {
            System.out.println("Including ALL services (no filtering)");
            return;
        }
        
        System.out.println("\nQuerying activeService facts...");
        for (String service : requestedServices) {
            String query = "<Query><Atom><Rel>activeService</Rel>" +
                          "<Ind>" + service + "</Ind>" +
                          "<Var>operation</Var><Var>ip</Var><Var>port</Var>" +
                          "</Atom></Query>";
            
            System.out.println("Querying for " + service + " activeService facts...");
            oojdrew.issueRuleMLQuery(query);
            
            if (oojdrew.rowsReturned > 0) {
                for (int i = 0; i < oojdrew.rowsReturned; i++) {
                    for (int j = 0; j < oojdrew.rowData[i].length; j++) {
                        String key = String.valueOf(oojdrew.rowData[i][0]);
                        String value = String.valueOf(oojdrew.rowData[i][1]);
                        
                        if ("?ip".equals(key)) {
                            neededChannels.add(value);
                            System.out.println("  " + service + " needs IP: " + value);
                        } else if ("?operation".equals(key)) {
                            neededOperations.add(value);
                        }
                    }
                }
            }
        }
        
        System.out.println("\nIPs needed: " + neededChannels);
        System.out.println("Operations needed: " + neededOperations);
    }
    
    /**
     * Build the filtered Service.ruleml
     * ENHANCED v2: Now recursively searches all directories for rule files
     */
    private void buildFilteredServiceRuleML() throws IOException {
        masterContent.append("<Assert>\n");
        masterContent.append("<Rulebase mapClosure=\"universal\">\n");
        masterContent.append("<!-- MASTER SERVICE RULE BASE -->\n");
        masterContent.append("<!-- Generated: ").append(new Date()).append(" -->\n");
        masterContent.append("<!-- Version: ").append(version).append(" -->\n");
        masterContent.append("<!-- Services: ").append(requestedServices.isEmpty() ? "ALL" : String.join(", ", requestedServices)).append(" -->\n");
        masterContent.append("<!-- This is the SINGLE SOURCE OF TRUTH for service resolution -->\n");
        masterContent.append("<!-- Rule files loaded recursively from: ").append(ruleBasePath).append(" -->\n\n");
        
        // Add version fact
        masterContent.append("<!-- Version -->\n");
        masterContent.append("<Data><Atom><Rel>Version</Rel><Ind>").append(version).append("</Ind></Atom></Data>\n\n");
        
        // Track which files we've added
        Set<String> addedFiles = new HashSet<>();
        
        // ENHANCED: Recursively find ALL rule files
        File commonDir = new File(ruleBasePath);
        List<File> allRuleFiles = new ArrayList<>();
        findRuleFilesRecursively(commonDir, allRuleFiles);
        
        // Sort files - core files first, then alphabetically
        allRuleFiles.sort((a, b) -> {
            boolean aIsCore = isCoreRuleFile(a.getName());
            boolean bIsCore = isCoreRuleFile(b.getName());
            if (aIsCore && !bIsCore) return -1;
            if (!aIsCore && bIsCore) return 1;
            return a.getName().compareTo(b.getName());
        });
        
        // Process all rule files
        for (File ruleFile : allRuleFiles) {
            String fileName = ruleFile.getName();
            String absolutePath = ruleFile.getAbsolutePath();
            String relativePath = getRelativePath(ruleFile, commonDir);
            
            // Skip if already added
            if (addedFiles.contains(absolutePath)) {
                continue;
            }
            
            // Check if this file should be included
            boolean shouldInclude = requestedServices.isEmpty() || // Include all when no filtering
                                    NEVER_FILTER_FILES.contains(fileName) || // Always include these
                                    isCoreRuleFile(fileName); // Core files always included
            
            if (shouldInclude) {
                masterContent.append("\n<!-- ===== BEGIN: ").append(relativePath).append(" ===== -->\n");
                String content = readRuleFile(ruleFile);
                masterContent.append(content);
                masterContent.append("\n<!-- ===== END: ").append(relativePath).append(" ===== -->\n");
                addedFiles.add(absolutePath);
            }
        }
        
        // If filtering, add filtered service content
        if (!requestedServices.isEmpty()) {
            addFilteredServiceContent(addedFiles);
        }
        
        // Add canonical bindings with hierarchical support
        addFilteredCanonicalBindingsHierarchical();
        
        masterContent.append("\n</Rulebase>\n");
        masterContent.append("</Assert>\n");
    }
    
    /**
     * Add filtered service content based on OOjDREW queries
     */
    private void addFilteredServiceContent(Set<String> alreadyAddedFiles) throws IOException {
        List<ServiceOperationInfo> serviceOperations = new ArrayList<>();
        
        for (String service : requestedServices) {
            String query = "<Query><Atom><Rel>activeService</Rel>" +
                          "<Ind>" + service + "</Ind>" +
                          "<Var>operation</Var><Var>ip</Var><Var>port</Var>" +
                          "</Atom></Query>";
            
            System.out.println("Querying activeService for " + service + "...");
            oojdrew.issueRuleMLQuery(query);
            System.out.println("  Found " + oojdrew.rowsReturned + " results");
            
            if (oojdrew.rowsReturned > 0) {
                String operation = null;
                String ip = null;
                String port = null;
                
                for (int j = 0; j < oojdrew.rowsReturned; j++) {
                    String varName = String.valueOf(oojdrew.rowData[j][0]);
                    String value = String.valueOf(oojdrew.rowData[j][1]);
                    
                    System.out.println("    " + varName + " = " + value);
                    
                    if ("?operation".equals(varName)) {
                        operation = value;
                    } else if ("?ip".equals(varName)) {
                        ip = value;
                    } else if ("?port".equals(varName)) {
                        port = value;
                    }
                    
                    if (operation != null && ip != null && port != null) {
                        System.out.println("  Collected activeService: " + service + "." + operation);
                        
                        ServiceOperationInfo info = new ServiceOperationInfo();
                        info.serviceName = service;
                        info.operation = operation;
                        info.link = ip;
                        info.port = port;
                        serviceOperations.add(info);
                        
                        operation = null;
                        ip = null;
                        port = null;
                    }
                }
            }
        }
        
        generateServiceNameFacts(serviceOperations);
    }
    
    /**
     * Generate serviceName facts from hasOperation facts
     */
    private void generateServiceNameFacts(List<ServiceOperationInfo> serviceOperations) throws IOException {
        if (serviceOperations.isEmpty()) {
            return;
        }
        
        masterContent.append("\n<!-- ===== BEGIN: AUTO-GENERATED serviceName facts ===== -->\n");
        masterContent.append("<!-- These facts are automatically generated from activeService facts -->\n");
        masterContent.append("<!-- ServiceThread requires these to process service operations -->\n\n");
        
        for (ServiceOperationInfo info : serviceOperations) {
            masterContent.append("<Atom>\n");
            masterContent.append("    <Rel>serviceName</Rel>\n");
            masterContent.append("    <Ind>").append(info.serviceName).append("</Ind>\n");
            masterContent.append("    <Ind>").append(info.operation).append("</Ind>\n");
            masterContent.append("    <Ind>").append(info.operation).append("</Ind>\n");
            masterContent.append("    <Ind>null</Ind>\n");
            masterContent.append("    <Ind>null</Ind>\n");
            masterContent.append("    <Ind>").append(info.link).append("</Ind>\n");
            masterContent.append("    <Ind>").append(info.port).append("</Ind>\n");
            masterContent.append("</Atom>\n");
            
            System.out.println("Generated serviceName fact: " + info.serviceName + "." + info.operation + 
                              " -> " + info.link + ":" + info.port);
        }
        
        masterContent.append("\n<!-- ===== END: AUTO-GENERATED serviceName facts ===== -->\n");
    }
    
    /**
     * NEW: Add filtered canonical bindings with hierarchical support
     */
    private void addFilteredCanonicalBindingsHierarchical() throws IOException {
        masterContent.append("\n<!-- ===== CANONICAL BINDINGS (filtered, hierarchical) ===== -->\n");
        
        String bindingsPath = commonPath + File.separator + "ServiceAttributeBindings";
        File bindingsDir = new File(bindingsPath);
        
        if (!bindingsDir.exists() || !bindingsDir.isDirectory()) {
            System.out.println("WARNING: ServiceAttributeBindings directory not found: " + bindingsPath);
            return;
        }
        
        Set<String> processedFiles = new HashSet<>();
        
        // Process hierarchical structure first (takes precedence)
        for (String serviceName : requestedServices) {
            File serviceDir = new File(bindingsDir, serviceName);
            if (serviceDir.exists() && serviceDir.isDirectory()) {
                masterContent.append("\n<!-- Service directory: ").append(serviceName).append(" -->\n");
                
                File[] bindingFiles = serviceDir.listFiles((dir, name) -> 
                    name.endsWith("-CanonicalBindings.ruleml.xml"));
                
                if (bindingFiles != null) {
                    Arrays.sort(bindingFiles);
                    for (File bindingFile : bindingFiles) {
                        masterContent.append("\n<!-- BEGIN: ").append(serviceName).append("/")
                                    .append(bindingFile.getName()).append(" -->\n");
                        String content = readRuleFile(bindingFile);
                        masterContent.append(content);
                        masterContent.append("\n<!-- END: ").append(serviceName).append("/")
                                    .append(bindingFile.getName()).append(" -->\n");
                        processedFiles.add(bindingFile.getName());
                    }
                }
            }
        }
        
        // Process flat structure for backward compatibility (only if not already processed)
        if (!requestedServices.isEmpty()) {
            File[] rootBindings = bindingsDir.listFiles((dir, name) -> 
                name.endsWith("-CanonicalBindings.ruleml.xml"));
            
            if (rootBindings != null) {
                Arrays.sort(rootBindings);
                for (File bindingFile : rootBindings) {
                    String serviceName = bindingFile.getName().replace("-CanonicalBindings.ruleml.xml", "");
                    
                    // Only include if requested and not already processed from hierarchical structure
                    if (requestedServices.contains(serviceName) && !processedFiles.contains(bindingFile.getName())) {
                        masterContent.append("\n<!-- BEGIN: ").append(bindingFile.getName())
                                    .append(" (legacy flat structure) -->\n");
                        String content = readRuleFile(bindingFile);
                        masterContent.append(content);
                        masterContent.append("\n<!-- END: ").append(bindingFile.getName()).append(" -->\n");
                    }
                }
            }
        } else {
            // Include all bindings when no filtering
            addAllCanonicalBindings(bindingsDir);
        }
    }
    
    /**
     * Helper method to add all canonical bindings when no filtering is applied
     */
    private void addAllCanonicalBindings(File bindingsDir) throws IOException {
        // Process all subdirectories
        File[] serviceDirs = bindingsDir.listFiles(File::isDirectory);
        if (serviceDirs != null) {
            Arrays.sort(serviceDirs);
            for (File serviceDir : serviceDirs) {
                File[] bindingFiles = serviceDir.listFiles((dir, name) -> 
                    name.endsWith("-CanonicalBindings.ruleml.xml"));
                
                if (bindingFiles != null) {
                    Arrays.sort(bindingFiles);
                    for (File bindingFile : bindingFiles) {
                        masterContent.append("\n<!-- BEGIN: ").append(serviceDir.getName())
                                    .append("/").append(bindingFile.getName()).append(" -->\n");
                        String content = readRuleFile(bindingFile);
                        masterContent.append(content);
                        masterContent.append("\n<!-- END: ").append(serviceDir.getName())
                                    .append("/").append(bindingFile.getName()).append(" -->\n");
                    }
                }
            }
        }
        
        // Process root level files
        File[] rootBindings = bindingsDir.listFiles((dir, name) -> 
            name.endsWith("-CanonicalBindings.ruleml.xml"));
        
        if (rootBindings != null) {
            Arrays.sort(rootBindings);
            for (File bindingFile : rootBindings) {
                masterContent.append("\n<!-- BEGIN: ").append(bindingFile.getName())
                            .append(" (legacy flat structure) -->\n");
                String content = readRuleFile(bindingFile);
                masterContent.append(content);
                masterContent.append("\n<!-- END: ").append(bindingFile.getName()).append(" -->\n");
            }
        }
    }
    
    /**
     * Write the master file to BOTH locations
     */
    private void writeMasterFile() throws IOException {
        // Write to current working directory
        File currentDir = new File("").getAbsoluteFile();
        String localOutputPath = currentDir.getAbsolutePath() + File.separator + "RuleFolder." + version;
        Files.createDirectories(Paths.get(localOutputPath));
        String localOutputFile = localOutputPath + File.separator + "Service.ruleml";
        Files.write(Paths.get(localOutputFile), masterContent.toString().getBytes());
        
        // Write to common directory
        String commonOutputFile = outputPath + File.separator + "Service.ruleml";
        Files.write(Paths.get(commonOutputFile), masterContent.toString().getBytes());
        
        // Verify both files were created
        File localFile = new File(localOutputFile);
        File commonFile = new File(commonOutputFile);
        
        if (!localFile.exists() || !commonFile.exists()) {
            throw new IOException("Failed to create Service.ruleml in one or both locations");
        }
        
        System.out.println("\nMaster Service.ruleml created in TWO locations:");
        System.out.println("  Local:  " + localOutputFile + " (" + localFile.length() + " bytes)");
        System.out.println("  Common: " + commonOutputFile + " (" + commonFile.length() + " bytes)");
    }
    
    /**
     * Print build summary
     */
    private void printBuildSummary() {
        String content = masterContent.toString();
        int activeServiceCount = countOccurrences(content, "<Rel>activeService</Rel>");
        int serviceNameCount = countOccurrences(content, "<Rel>serviceName</Rel>");
        int boundChannelCount = countOccurrences(content, "<Rel>boundChannel</Rel>");
        int canonicalBindingCount = countOccurrences(content, "<Rel>canonicalBinding</Rel>");
        
        System.out.println("\n===== BUILD SUCCESSFUL =====");
        System.out.println("Master Service.ruleml created at:");
        System.out.println("   " + outputPath + File.separator + "Service.ruleml");
        System.out.println("Filtered for services: " + (requestedServices.isEmpty() ? "ALL" : String.join(", ", requestedServices)));
        System.out.println("");
        System.out.println("Fact counts:");
        System.out.println("  activeService facts: " + activeServiceCount);
        System.out.println("  serviceName facts: " + serviceNameCount);
        System.out.println("  boundChannel facts: " + boundChannelCount);
        System.out.println("  canonicalBinding facts: " + canonicalBindingCount);
        System.out.println("============================");
    }
    
    private int countOccurrences(String text, String search) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(search, index)) != -1) {
            count++;
            index += search.length();
        }
        return count;
    }
    
    /**
     * Data class to hold service operation information
     */
    private static class ServiceOperationInfo {
        String serviceName;
        String operation;
        String link;
        String port;
        
        @Override
        public String toString() {
            return serviceName + ":" + operation + " @ " + link + ":" + port;
        }
    }
}