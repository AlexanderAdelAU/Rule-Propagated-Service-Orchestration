package org.btsn.launch;

/** Executable infrastructure JAR: starts administration or observation services. */
public final class InfrastructureMain {
    public static void main(String[] args) throws Exception {
        ManualServiceLauncher.run("infrastructure", args);
    }
}
