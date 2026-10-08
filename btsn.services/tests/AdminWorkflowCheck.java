import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import org.btsn.deployment.DeploymentConfiguration;
import org.btsn.rulecontroller.RuleDeployer;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

/** Exercises the real loader from the same working directory as the event generators. */
public final class AdminWorkflowCheck {
    public static void main(String[] args) throws Exception {
        org.apache.log4j.Logger.getRootLogger().setLevel(org.apache.log4j.Level.OFF);
        checkScalarInputs();
        for (int i = 0; i < 2; i++) if (!args[i].isEmpty()) load(args[i], null, false);
        if (!args[2].isEmpty()) {
            load(args[2], null, true);
            load(args[2], args[3], false);
        }
        Path common = Path.of("../btsn.common");
        if (!args[0].isEmpty()) {
            JSONObject workflow = DeploymentConfiguration.read(common.resolve("ProcessDefinitionFolder/" + args[0] + ".json"));
            for (Object item : (JSONArray)workflow.get("elements")) {
                JSONObject place = (JSONObject)item;
                if (!"PLACE".equals(place.get("type"))) continue;
                JSONObject operation = (JSONObject)((JSONArray)place.get("operations")).get(0);
                operation.put("name", "undefinedInfrastructureOperation");
                break;
            }
            Path invalid = common.resolve("ProcessDefinitionFolder/AdminValidationInvalid.json");
            try {
                Files.writeString(invalid, workflow.toJSONString());
                load("AdminValidationInvalid", null, true);
            } finally { Files.deleteIfExists(invalid); }
        }
        System.out.println("PASS: real initialization, collection and business loaders; missing business deployment and unknown infrastructure operation rejected");
    }
    private static void checkScalarInputs() {
        org.apache.log4j.Logger logger = org.apache.log4j.Logger.getLogger(org.btsn.json.jsonLibrary.class);
        final int[] errors = {0};
        org.apache.log4j.AppenderSkeleton capture = new org.apache.log4j.AppenderSkeleton() {
            protected void append(org.apache.log4j.spi.LoggingEvent event) { errors[0]++; }
            public void close() { }
            public boolean requiresLayout() { return false; }
        };
        logger.setLevel(org.apache.log4j.Level.ERROR); logger.addAppender(capture);
        try {
            for (String scalar : new String[]{"\"init p1\"", "true", "42", "[]", "null"})
                if (org.btsn.json.jsonLibrary.parseString(scalar) != null)
                    throw new AssertionError("Scalar converted into object: " + scalar);
            if (errors[0] != 0) throw new AssertionError("Valid scalar input logged a parsing error");
            if (org.btsn.json.jsonLibrary.parseString("{\"data\":42}") == null)
                throw new AssertionError("Object token was not parsed");
            org.btsn.json.jsonLibrary.parseString("{invalid");
            if (errors[0] != 1) throw new AssertionError("Malformed JSON no longer reports an error");
        } finally { logger.removeAppender(capture); logger.setLevel(null); }
    }
    private static void load(String process, String infrastructure, boolean rejection) throws Exception {
        RuleDeployer deployer = new RuleDeployer(process, "v998", infrastructure);
        Method loader = RuleDeployer.class.getDeclaredMethod("loadWorkflow"); loader.setAccessible(true);
        Method stop = RuleDeployer.class.getDeclaredMethod("stopCommitmentListener"); stop.setAccessible(true);
        try {
            try {
                loader.invoke(deployer);
                if (rejection) throw new AssertionError("Invalid process accepted: " + process);
            } catch (InvocationTargetException ex) {
                if (!rejection) throw ex;
                if (!(ex.getCause() instanceof RuleDeployer.RuleDeployerException) ||
                    !(ex.getCause().getMessage().contains("unregistered infrastructure operation") ||
                      ex.getCause().getMessage().contains("Infrastructure workflow cannot select a business instance"))) throw ex;
            }
        } finally { stop.invoke(deployer); }
    }
}
