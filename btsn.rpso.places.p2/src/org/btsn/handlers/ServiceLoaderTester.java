package org.btsn.handlers;



/**
 * Simple test class to load specific services via ServiceLoader.
 * Run from the service directory (e.g., btsn.common.Monitor)
 * 
 * Usage:
 *   java org.btsn.handlers.LoaderTest                          # Load ALL services
 *   java org.btsn.handlers.LoaderTest MonitorService           # Load only MonitorService
 *   java org.btsn.handlers.LoaderTest Monitor_InitializationService  # Load only init service
 */
public class ServiceLoaderTester {
    
    public static void main(String[] args) {
        String version = "v001";
        
        if (args.length == 0) {
            // Load ALL services
            System.out.println("=== Loading ALL services with version " + version + " ===");
            try {
				ServiceLoader.main(new String[]{"-version", version});
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
        } 
        else if (args.length == 1) {
            // Load specific service
            String serviceName = args[0];
            System.out.println("=== Loading " + serviceName + " with version " + version + " ===");
            try {
				ServiceLoader.main(new String[]{"-version", version, "-service", serviceName});
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
        }
        else if (args.length == 2 && args[0].equals("-version")) {
            // Custom version, all services
            version = args[1];
            System.out.println("=== Loading ALL services with version " + version + " ===");
            try {
				ServiceLoader.main(new String[]{"-version", version});
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
        }
        else if (args.length == 3 && args[0].equals("-version")) {
            // Custom version, specific service
            version = args[1];
            String serviceName = args[2];
            System.out.println("=== Loading " + serviceName + " with version " + version + " ===");
            try {
				ServiceLoader.main(new String[]{"-version", version, "-service", serviceName});
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
        }
        else {
            System.out.println("Usage:");
            System.out.println("  java org.btsn.handlers.LoaderTest                    # Load ALL services (v001)");
            System.out.println("  java org.btsn.handlers.LoaderTest <ServiceName>      # Load specific service");
            System.out.println("  java org.btsn.handlers.LoaderTest -version v002      # Load ALL with version");
            System.out.println("  java org.btsn.handlers.LoaderTest -version v002 <ServiceName>");
            System.out.println();
            System.out.println("Examples:");
            System.out.println("  java org.btsn.handlers.LoaderTest MonitorService");
            System.out.println("  java org.btsn.handlers.LoaderTest Monitor_InitializationService");
        }
    }
}