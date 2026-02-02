package org.btsn.utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Flexible BuildRuleBase with configurable paths for multi-domain support
 * 
 * ENHANCED: Now supports hierarchical ServiceAttributeBindings structure
 * - Recursively searches all subdirectories for binding files
 * - No hard-coded assumptions about service relationships
 * - Maintains full backward compatibility
 * 
 * RULE FILE SEARCH: Searches only btsn.common/RuleBase directory (recursively)
 * - Includes subdirectories like Healthcare/, PetriNet/, etc.
 * - Does NOT search other directories like RulePayLoad, RuleBasex, etc.
 */
public class BuildRuleBase_writes_to_wrong_location {
	static String buildVersion = null;
	static String controlFileName = "-ControlNodeRules.ruleml.xml";
	static String attributeBindingFileName = "-CanonicalBindings.ruleml.xml";
	
	// Default paths (can be overridden)
	static String ruleBaseDirectory = "btsn.common/RuleBase";
	static String bindingsAttributesBaseDirectory = "btsn.common/ServiceAttributeBindings";
	
	// Directories to exclude from rule file search (within RuleBase)
	// Much simpler now that we only search RuleBase directory
	private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(
		".git",
		"target",
		"bin",
		"build"
	);
	
	// Static configuration variables for local testing
	private static String BUILD_VERSION_v001 = "v001";
	private static String BUILD_VERSION_v002 = "v002";

	/**
	 * Build operation-specific rule base
	 * Searches RuleBase directory recursively for rule files
	 */
	static public boolean buildOperationRuleBase(String serviceName, String operationName, String lbuildVersion)
			throws java.io.IOException {

		buildVersion = lbuildVersion;

		File appBase = new File("");
		String path = appBase.getAbsolutePath();
		String rulePath = path + "/RuleFolder." + buildVersion + "/";
		String operationRulePath = rulePath + operationName;
		String controlNodeRuleFile = serviceName + controlFileName;

		PrintWriter pw = new PrintWriter(new FileOutputStream(operationRulePath + "/Service.ruleml"));

		File commonBase = new File("../");
		String commonPath = commonBase.getCanonicalPath();

		// Find the binding file for this service
		File bindingFile = findServiceBindingFile(serviceName, commonPath);
		
		if (bindingFile == null) {
			// Create a minimal binding file to keep things running
			bindingFile = createMinimalBindingFile(serviceName, commonPath);
			if (bindingFile == null) {
				throw new IOException("Could not find or create binding file for: " + serviceName);
			}
		}

		// Search only the RuleBase directory (and its subdirectories)
		File ruleBaseDir = new File(commonPath + "/btsn.common/RuleBase");
		if (!ruleBaseDir.exists()) {
			ruleBaseDir = new File(commonPath + "/RuleBase");
		}
		
		List<File> allRuleFiles = new ArrayList<>();
		if (ruleBaseDir.exists() && ruleBaseDir.isDirectory()) {
			findRuleFilesRecursively(ruleBaseDir, allRuleFiles);
		}
		
		if (allRuleFiles.isEmpty()) {
			System.err.println("WARNING: No rule files found in " + ruleBaseDir.getPath());
			return false;
		}
		
		// Sort for consistent ordering
		allRuleFiles.sort((a, b) -> a.getName().compareTo(b.getName()));
		
		System.out.println("Found " + allRuleFiles.size() + " rule files in RuleBase");

		pw.println("<Assert>");
		pw.println("<Rulebase mapClosure=\"universal\">");

		// Include version number
		pw.println("<!-- Version On -->");
		pw.println("<Data><Atom><Rel>Version</Rel><Ind>" + buildVersion + "</Ind></Atom></Data>");
		
		// Process all discovered rule files
		for (File ruleFile : allRuleFiles) {
			System.out.println("Processing " + ruleFile.getPath() + "... ");
			BufferedReader br = new BufferedReader(new FileReader(ruleFile.getPath()));
			String line = br.readLine();
			while (line != null) {
				pw.println(line);
				line = br.readLine();
			}
			br.close();
		}
		
		// Process the binding file
		System.out.println("Processing binding file: " + bindingFile.getPath());
		BufferedReader br = new BufferedReader(new FileReader(bindingFile));
		String line = br.readLine();
		while (line != null) {
			pw.println(line);
			line = br.readLine();
		}
		br.close();

		// Lastly fold in the ControlNode File
		String cnFile = operationRulePath + "/" + controlNodeRuleFile;
		System.out.println("Processing control Node file.... " + controlNodeRuleFile);
		BufferedReader cnf = new BufferedReader(new FileReader(cnFile));
		String cnline = cnf.readLine();
		while (cnline != null) {
			pw.println(cnline);
			cnline = cnf.readLine();
		}
		cnf.close();

		pw.println("</Rulebase>");
		pw.println("</Assert>");
		pw.close();
		System.out.println("All files have been concatenated into Service.ruleml");
		return true;
	}

	/**
	 * Recursively find all rule files in the RuleBase directory tree
	 * Searches RuleBase and its subdirectories (Healthcare, PetriNet, etc.)
	 */
	private static void findRuleFilesRecursively(File dir, List<File> ruleFiles) {
		if (dir == null || !dir.exists() || !dir.isDirectory()) {
			return;
		}
		
		// Check if this directory should be excluded
		String dirName = dir.getName();
		if (EXCLUDED_DIRECTORIES.contains(dirName)) {
			System.out.println("Skipping excluded directory: " + dir.getPath());
			return;
		}
		
		// Find rule files in current directory (.ruleml.xml and .xml files)
		File[] files = dir.listFiles((d, name) -> 
			name.endsWith(".ruleml.xml") || name.endsWith(".xml"));
		
		if (files != null) {
			for (File file : files) {
				if (file.isFile()) {
					ruleFiles.add(file);
					System.out.println("  Found rule file: " + file.getPath());
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
	 * SIMPLIFIED: Find the binding file for a service
	 * Searches recursively through all directories
	 */
	private static File findServiceBindingFile(String serviceName, String commonPath) {
		String targetFileName = serviceName + attributeBindingFileName;
		
		// Define base paths to search
		String[] basePaths = {
			commonPath + "/btsn.common/ServiceAttributeBindings",
			commonPath + "/" + bindingsAttributesBaseDirectory,
			"./ServiceAttributeBindings",
			"../ServiceAttributeBindings"
		};
		
		// Search each base path
		for (String basePath : basePaths) {
			File baseDir = new File(basePath);
			if (baseDir.exists() && baseDir.isDirectory()) {
				System.out.println("Searching in: " + basePath);
				
				// Search recursively for the exact file
				File found = searchFileRecursively(baseDir, targetFileName);
				if (found != null) {
					System.out.println("Found binding file: " + found.getPath());
					return found;
				}
			}
		}
		
		System.out.println("Could not find binding file: " + targetFileName);
		return null;
	}
	
	/**
	 * Recursively search for a file in a directory tree
	 */
	private static File searchFileRecursively(File dir, String fileName) {
		// Check current directory
		File target = new File(dir, fileName);
		if (target.exists() && target.isFile()) {
			return target;
		}
		
		// Check subdirectories
		File[] subdirs = dir.listFiles(File::isDirectory);
		if (subdirs != null) {
			for (File subdir : subdirs) {
				File found = searchFileRecursively(subdir, fileName);
				if (found != null) {
					return found;
				}
			}
		}
		
		return null;
	}
	
	/**
	 * Create a minimal binding file if none exists
	 */
	private static File createMinimalBindingFile(String serviceName, String commonPath) {
		try {
			String basePath = commonPath + "/btsn.common/ServiceAttributeBindings";
			File baseDir = new File(basePath);
			
			// Ensure directory exists
			if (!baseDir.exists()) {
				baseDir.mkdirs();
			}
			
			String fileName = serviceName + attributeBindingFileName;
			File bindingFile = new File(baseDir, fileName);
			
			PrintWriter pw = new PrintWriter(new FileOutputStream(bindingFile));
			pw.println("<!-- AUTO-GENERATED MINIMAL BINDING FILE -->");
			pw.println("<!-- Service: " + serviceName + " -->");
			pw.println("<!-- Created: " + new java.util.Date() + " -->");
			pw.println("<!-- This is a minimal binding file created automatically -->");
			pw.println("<!-- Please replace with actual canonical bindings -->");
			pw.println();
			pw.println("<!-- Default minimal binding -->");
			pw.println("<Atom>");
			pw.println("    <Rel>canonicalBinding</Rel>");
			pw.println("    <Ind>default</Ind>");
			pw.println("    <Ind>result</Ind>");
			pw.println("    <Ind>request</Ind>");
			pw.println("</Atom>");
			pw.close();
			
			System.out.println("Created minimal binding file: " + bindingFile.getPath());
			return bindingFile;
			
		} catch (IOException e) {
			System.err.println("Failed to create minimal binding file: " + e.getMessage());
			return null;
		}
	}

	/**
	 * Create a template service attribute binding file in hierarchical structure
	 */
	public static boolean createTemplateServiceAttributeBinding(String serviceName, String targetPath) {
		try {
			// Determine where to create the file
			// For sub-services, try to group them logically
			String serviceDir = targetPath;
			
			// Extract base service name (e.g., "Laboratory" from "LaboratoryInitializationService")
			if (serviceName.contains("Service")) {
				String base = serviceName.substring(0, serviceName.indexOf("Service") + 7);
				if (!base.equals(serviceName)) {
					// It's a sub-service, create under parent directory
					serviceDir = targetPath + "/" + base;
				} else {
					// It's a main service, create its own directory
					serviceDir = targetPath + "/" + serviceName;
				}
			}
			
			File serviceDirFile = new File(serviceDir);
			if (!serviceDirFile.exists()) {
				serviceDirFile.mkdirs();
				System.out.println("Created service directory: " + serviceDir);
			}
			
			String fileName = serviceName + attributeBindingFileName;
			String fullPath = serviceDir + "/" + fileName;
			
			File targetFile = new File(fullPath);
			if (targetFile.exists()) {
				System.out.println("Service attribute binding file already exists: " + fullPath);
				return true;
			}
			
			// Create template content
			PrintWriter pw = new PrintWriter(new FileOutputStream(fullPath));
			pw.println("<!-- Service Attribute Bindings for " + serviceName + " -->");
			pw.println("<!-- Define canonical bindings for service operations -->");
			pw.println();
			pw.println("<!-- Example binding -->");
			pw.println("<Atom>");
			pw.println("    <Rel>canonicalBinding</Rel>");
			pw.println("    <Ind>processRequest</Ind>      <!-- operation name -->");
			pw.println("    <Ind>result</Ind>              <!-- return attribute -->");
			pw.println("    <Ind>token</Ind>               <!-- input parameter -->");
			pw.println("</Atom>");
			pw.close();
			
			System.out.println("Created template binding file: " + fullPath);
			return true;
			
		} catch (IOException e) {
			System.err.println("Error creating template binding file: " + e.getMessage());
			return false;
		}
	}

	/**
	 * Build rule base by searching RuleBase directory recursively
	 * Includes subdirectories like Healthcare/, PetriNet/, etc.
	 * 
	 * OUTPUT: Always creates Service.ruleml in btsn.common/RuleFolder.{version}/
	 * This is the MASTER rule base location used by all components.
	 */
	public static boolean buildRuleBase(String lbuildVersion, boolean cfolder) throws java.io.IOException {
		buildVersion = lbuildVersion;
		System.err.println("BUILDRULE BASE - Build Version " + buildVersion);

		// Determine btsn.common path (go up from current directory to find it)
		File currentDir = new File("").getAbsoluteFile();
		File parentDir = currentDir.getParentFile();
		File commonDir = new File(parentDir, "btsn.common");
		
		// If btsn.common not found at ../btsn.common, we might already be in btsn.common
		if (!commonDir.exists()) {
			if (currentDir.getName().equals("btsn.common")) {
				commonDir = currentDir;
			} else {
				// Try one more level up
				commonDir = new File(parentDir.getParentFile(), "btsn.common");
			}
		}
		
		if (!commonDir.exists()) {
			System.err.println("BuildRuleBase: Cannot find btsn.common directory!");
			System.err.println("  Searched from: " + currentDir.getAbsolutePath());
			throw new IOException("Cannot find btsn.common directory");
		}
		
		// Master rule base always goes in btsn.common/RuleFolder.{version}/
		String rulePath = commonDir.getAbsolutePath() + "/RuleFolder." + buildVersion + "/";
		
		if (cfolder) {
			if (!CreateDirectory.createDirectory(rulePath)) {
				System.err.println("BuildRuleBase: Unable to create rule directory" + rulePath);
				throw new IOException("Unable to create rule directory: " + rulePath);
			}
		}
		
		PrintWriter pw = new PrintWriter(new FileOutputStream(rulePath + "Service.ruleml"));
		
		// Search only the RuleBase directory (and its subdirectories)
		File ruleBaseDir = new File(commonDir, "RuleBase");
		if (!ruleBaseDir.exists()) {
			System.err.println("BuildRuleBase: RuleBase directory not found at: " + ruleBaseDir.getPath());
			return false;
		}
		
		System.err.println("BuildRuleBase: Searching for rule files in: " + ruleBaseDir.getPath());
		
		if (!ruleBaseDir.exists() || !ruleBaseDir.isDirectory()) {
			System.err.println("BuildRuleBase: RuleBase directory not found!");
			return false;
		}
		
		// Recursively find all rule files within RuleBase
		List<File> allRuleFiles = new ArrayList<>();
		findRuleFilesRecursively(ruleBaseDir, allRuleFiles);
		
		if (allRuleFiles.isEmpty()) {
			System.err.println("BuildRuleBase: No rule files found!");
			return false;
		}
		
		// Sort for consistent ordering
		allRuleFiles.sort((a, b) -> a.getName().compareTo(b.getName()));
		
		System.out.println("Found " + allRuleFiles.size() + " rule files in RuleBase:");
		for (File f : allRuleFiles) {
			System.out.println("  - " + f.getPath());
		}
		
		pw.println("<Assert>");
		pw.println("<Rulebase mapClosure=\"universal\">");

		pw.println("<!-- Version No -->");
		pw.println("<Data><Atom><Rel>Version</Rel><Ind>" + buildVersion + "</Ind></Atom></Data>");
		
		// Process all discovered rule files
		for (File ruleFile : allRuleFiles) {
			System.out.println("Processing " + ruleFile.getPath() + "... ");
			BufferedReader br = new BufferedReader(new FileReader(ruleFile.getPath()));
			String line = br.readLine();
			while (line != null) {
				pw.println(line);
				line = br.readLine();
			}
			br.close();
		}
		
		pw.println("</Rulebase>");
		pw.println("</Assert>");
		pw.close();

		System.out.println("All files have been concatenated into Service.ruleml at: " + rulePath + "Service.ruleml");
		return true;
	}

	// Keep other existing methods unchanged...
	static public void buildCandidateRuleBase(String[] ruleNames, String buildVersion) throws java.io.IOException {
		File appBase = new File("");
		String path = appBase.getAbsolutePath();
		String rulePath = path + "/RuleFolder." + buildVersion + "/";
		File folder = new File(rulePath);
		
		try {
			if (folder.mkdir())
				System.out.println("RuleBase candidate directory created");
			else
				System.err.println("Directory already exists, or error creating RuleBase candidate directory.");
		} catch (Exception e) {
			e.printStackTrace();
		}

		for (int i = 0; i < ruleNames.length; i++) {
			CreateFile.createFile(rulePath + ruleNames[i] + "." + buildVersion + ".xml");
		}

		System.out.println("Candidate RuleBase File structure created..");
	}

	static public boolean RuleBaseExists(String ruleBaseLocation) {
		boolean exists = (new File(ruleBaseLocation)).exists();
		if (!exists)
			return false;
		return true;
	}

	public static class FileExtensionFilter implements FilenameFilter {
		private String ext = "*";

		public FileExtensionFilter(String ext) {
			this.ext = ext;
		}

		public boolean accept(File dir, String name) {
			if (name.endsWith(ext))
				return true;
			return false;
		}
	}

	public static File[] ConcatArrays(File[] cfiles, File[] sfiles) {
		File[] C = new File[cfiles.length + sfiles.length];
		System.arraycopy(cfiles, 0, C, 0, cfiles.length);
		System.arraycopy(sfiles, 0, C, cfiles.length, sfiles.length);

		return C;
	}

	public static void main(String[] args) {

		boolean createFolder = true;

		try {
			System.out.println("--- Building Main Rule Base ---");
			boolean mainBuildSuccess = buildRuleBase(BUILD_VERSION_v001, createFolder);
			if (mainBuildSuccess) {
				System.out.println("Main rule base built successfully.");
			} else {
				System.out.println("Main rule base build failed.");
			}
			mainBuildSuccess = buildRuleBase(BUILD_VERSION_v002, createFolder);
			if (mainBuildSuccess) {
				System.out.println("Main rule base built successfully.");
			} else {
				System.out.println("Main rule base build failed.");
			}

		} catch (IOException e) {
			System.err.println("An I/O error occurred: " + e.getMessage());
			e.printStackTrace();
		} catch (Exception e) {
			System.err.println("An unexpected error occurred: " + e.getMessage());
			e.printStackTrace();
		}
	}
}