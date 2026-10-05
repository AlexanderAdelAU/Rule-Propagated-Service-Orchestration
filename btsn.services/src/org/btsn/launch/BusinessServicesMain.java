package org.btsn.launch;

/** Executable business JAR: starts the existing worker for the requested place. */
public final class BusinessServicesMain {
    public static void main(String[] args) throws Exception {
        ManualServiceLauncher.run("business", args);
    }
}
