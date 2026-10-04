import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.util.Arrays;

/** Runs in a fresh JVM with exactly one implementation JAR on its classpath. */
public class PackagedServiceCheck {
    public static void main(String[] args) throws Exception {
        Class<?> implementation = Class.forName(args[0]);
        Path loaded = Path.of(implementation.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (!loaded.equals(Path.of(args[1]))) throw new AssertionError("Wrong implementation origin: " + loaded);
        Object service;
        try {
            Constructor<?> constructor = implementation.getConstructor(String.class, String.class, String.class);
            service = constructor.newInstance("1000000", "independent-host", "deployment-check");
        } catch (NoSuchMethodException noContextConstructor) {
            service = implementation.getConstructor().newInstance();
        }
        int arity = Integer.parseInt(args[3]);
        Class<?>[] types = new Class<?>[arity];
        Arrays.fill(types, String.class);
        Object[] inputs = new Object[arity];
        Arrays.fill(inputs, "{\"tokenId\":\"example\",\"version\":\"v001\",\"notAfter\":"
                + (System.currentTimeMillis() + 60000)
                + ",\"application_id\":\"APP-001\",\"annual_income\":85000,\"requested_amount\":15000,\"credit_score\":720,\"fraud_risk\":\"low\"}");
        Object output = implementation.getMethod(args[2], types).invoke(service, inputs);
        if (!(output instanceof String) || !((String) output).startsWith("{")) {
            throw new AssertionError("Service did not return JSON: " + output);
        }
        Class<?> parserClass = Class.forName("org.json.simple.parser.JSONParser");
        Object parsed = parserClass.getMethod("parse", String.class).invoke(parserClass.getConstructor().newInstance(), output);
        java.util.Map<?, ?> result = (java.util.Map<?, ?>) parsed;
        if (!(result.get(args[4]) instanceof java.util.Map)) {
            throw new AssertionError("Missing declared result attribute: " + args[4]);
        }
        // Other implementations must not leak into the shared support JAR.
        for (int i = 5; i < args.length; i++) {
            try {
                Class.forName(args[i]);
                throw new AssertionError("Unexpected implementation available: " + args[i]);
            } catch (ClassNotFoundException expected) { }
        }
        System.out.println("PASS: isolated JAR invocation " + args[0]);
    }
}
