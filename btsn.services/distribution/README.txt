BTSN manual service startup

1. Copy this ZIP to the destination machine and unzip it into a writable folder.
2. Java 15 or later must be available there. Ant and the source repository are not needed there.
3. From that folder run:

   Windows: launch.bat p1
   Linux/macOS: sh launch.sh p1

   Substitute p2 through p6 for another place. Use launch.bat monitor
   (or sh launch.sh monitor) for the observation services.
   To run several places on one machine, run the command once for each place.

The two JARs can also be started separately in two terminals:

   java -jar btsn-infrastructure.jar p1
   java -jar btsn-business-services.jar p1

Both accept an optional startup rule version, for example p1 v001.
The business JAR starts the existing ServiceThread worker for the selected place.
The infrastructure JAR starts its initialization and collection services.
Monitor runs from the infrastructure JAR.
The existing service loader and infrastructure handlers are unchanged.

4. Wait for ServiceLoader Startup Complete in both consoles and check that the
   expected service workers started successfully. A worker requires its activeService
   deployment facts; unbound places do not invent a business operation.
5. Run the existing business-process deployment Ant build from your development
   machine. It deploys the rules and sends tokens to these already running services.
   It does not upload JARs or start or stop these programs.

Before first startup, configure the channel addresses in config/btsn.common's
existing deployment definitions and rules for the actual destination machines.
Use the same channel mapping in the development project's process deployment.

Keep both JARs together. They contain all required runtime libraries and compiled
service code. The infrastructure launcher unpacks its internal runtime locally
into .runtime; working rules and databases are kept under .run/business and
.run/infrastructure. Configuration is copied on first start and later received
rules are preserved. Neither Ant nor a receiver is required for manual startup.
To replace startup configuration, stop the programs and update the corresponding
.run configuration as well; editing the initial config after first start alone
does not replace the running configuration or existing data.

Closing a service console or pressing Ctrl+C stops that console's service runtime.
