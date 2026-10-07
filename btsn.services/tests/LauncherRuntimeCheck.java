import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.ProjectHelper;
import org.btsn.invocation.BusinessCapabilityResolver;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

/** Configure the real Ant entry points, prepare their runtimes, and resolve every selected operation. */
public final class LauncherRuntimeCheck {
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]).toAbsolutePath();
        java.util.Map<Path,String> before=sourceConfiguration(root);
        List<Path> builds=new ArrayList<>();
        for(Path dir:Arrays.asList(root.resolve("btsn.financial.ProjectLoader"),root.resolve("btsn.petrinet.ProjectLoader"),root.resolve("btsn.petrinet.ProjectLoader/utility files")))
            try(java.util.stream.Stream<Path> files=Files.list(dir)) { files.filter(p->p.toString().endsWith(".xml")).sorted().forEach(builds::add); }
        int operations=0;
        for(Path build:builds) {
            javax.xml.parsers.DocumentBuilderFactory factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();
            org.w3c.dom.Document xml=factory.newDocumentBuilder().parse(build.toFile());
            org.w3c.dom.NodeList tasks=xml.getElementsByTagName("java");
            for(int task=0;task<tasks.getLength();task++) {
                org.w3c.dom.Element java=(org.w3c.dom.Element)tasks.item(task);
                if(!"${token.generator.class}".equals(java.getAttribute("classname")))continue;
                boolean infrastructure=false;
                org.w3c.dom.NodeList arguments=java.getElementsByTagName("arg");
                for(int argument=0;argument<arguments.getLength();argument++)
                    if("-infrastructure".equals(((org.w3c.dom.Element)arguments.item(argument)).getAttribute("value")))infrastructure=true;
                check(infrastructure,"Token generator missing its logical-to-physical infrastructure profile: "+build);
            }
            Project p=new Project(); p.init();
            p.setUserProperty("launcher.skip.build","true");
            ProjectHelper.configureProject(p,build.toFile());
            p.executeTarget("prepare-runtime");
            if(p.getTargets().containsKey("generate-workflow-bindings")) p.executeTarget("generate-workflow-bindings");
            for(String name:Arrays.asList("common","generator","monitor","p1","p2","p3","p4","p5","p6")) {
                String[] entries=((org.apache.tools.ant.types.Path)p.getReference(name+".classpath")).list();
                for(String entry:entries) check(entry.endsWith(".jar")&&Files.isRegularFile(Path.of(entry)),"Non-JAR/missing classpath entry: "+entry);
            }
            Path common=Path.of(p.getProperty("common.project.dir"));
            check(!common.equals(root.resolve("btsn.common"))&&common.startsWith(root.resolve("btsn.services/target/launchers")),"Source configuration used as a runtime: "+build);
            for(int task=0;task<tasks.getLength();task++) {
                org.w3c.dom.Element java=(org.w3c.dom.Element)tasks.item(task);
                if(!"${token.generator.class}".equals(java.getAttribute("classname")))continue;
                org.w3c.dom.NodeList arguments=java.getElementsByTagName("arg");
                for(int argument=0;argument<arguments.getLength();argument++) {
                    if(!"-infrastructure".equals(((org.w3c.dom.Element)arguments.item(argument)).getAttribute("value")))continue;
                    check(argument+1<arguments.getLength(),"Missing infrastructure name: "+build);
                    String name=p.replaceProperties(((org.w3c.dom.Element)arguments.item(argument+1)).getAttribute("value"));
                    if(name.endsWith(".json"))name=name.substring(0,name.length()-5);
                    Path definition=common.resolve("InfrastructureDefinitionFolder").resolve(name+".json");
                    check(Files.isRegularFile(definition),"Missing infrastructure definition: "+definition);
                    check("Infrastructure".equals(json(definition).get("definitionType")),"Wrong infrastructure definition type: "+definition);
                }
            }
            BusinessCapabilityResolver resolver=new BusinessCapabilityResolver(common);
            JSONObject config=json(common.resolve("BusinessServiceDefinitions/Deployment.json"));
            JSONObject catalogue=json(common.resolve((String)config.get("catalog")));
            for(String property:Arrays.asList("workflow.process.name","workflow1.process.name","workflow2.process.name","process.name","init.process.name","collector.process.name")) {
                String process=p.getProperty(property); if(process==null) continue;
                Path definition=common.resolve("ProcessDefinitionFolder").resolve(process+".json");
                check(Files.isRegularFile(definition),"Missing process definition: "+definition);
                JSONObject data=json(definition);
                for(Object item:(JSONArray)data.get("elements")) {
                    JSONObject node=(JSONObject)item; if(!"PLACE".equals(node.get("type"))) continue;
                    String service=(String)node.get("service");
                    for(Object candidate:(JSONArray)catalogue.get("services")) {
                        JSONObject capability=(JSONObject)candidate;
                        if(!service.equals(capability.get("service"))) continue;
                        check("active".equals(capability.get("status")),"Workflow selects an inactive capability: "+service);
                        for(Object operation:(JSONArray)node.get("operations")) {
                            JSONObject op=(JSONObject)operation;
                            if(!op.get("name").equals(capability.get("operation")))continue;
                            JSONArray expected=(JSONArray)capability.get("inputs"),actual=(JSONArray)op.get("arguments");
                            if(actual!=null) {
                                List<String> names=new ArrayList<>();for(Object argument:actual)names.add((String)((JSONObject)argument).get("name"));
                                check(expected.equals(names),"Workflow/catalogue input order mismatch: "+process+" / "+service);
                            }
                            check(java.util.Objects.toString(op.get("returnAttribute"),java.util.Objects.toString(node.get("returnAttribute"),"token")).equals(capability.get("returnAttribute")),"Workflow/catalogue return mismatch: "+service);
                            String implementation=resolver.resolve(service,(String)capability.get("operation"),(String)capability.get("returnAttribute"),expected.size(),null);
                            check(implementation.equals(capability.get("implementationClass")),"Wrong configured implementation: "+service);
                            operations++;
                        }
                    }
                    // Monitor is observation infrastructure, never an active model business PLACE.
                    if(property.startsWith("workflow")||property.equals("process.name")&&process.contains("/Workflow/"))
                        check(!"MonitorService".equals(service)&&!service.matches("P[1-6]_Place"),"Physical host retained as a business capability: "+process+" / "+service);
                }
            }
            check(Files.isRegularFile(Path.of(p.getProperty("generator.project.dir")).resolve("Payload/payLoad.xml")),"Admin payload alias missing");
            System.out.println("PASS: "+root.relativize(build));
        }
        check(before.equals(sourceConfiguration(root)),"Launcher preparation changed source deployment or workflow configuration");
        System.out.println("PASS: "+builds.size()+" real Ant entry points, JAR-only classpaths, isolated domain profiles, process paths, unchanged source configuration and "+operations+" workflow capability contracts");
    }
    private static JSONObject json(Path p) throws Exception { return (JSONObject)new JSONParser().parse(Files.readString(p)); }
    private static java.util.Map<Path,String> sourceConfiguration(Path root) throws Exception {
        java.util.Map<Path,String> hashes=new java.util.TreeMap<>();
        for(String directory:Arrays.asList("RuleBase","ServiceAttributeBindings","BusinessServiceDefinitions","ProcessDefinitionFolder","InfrastructureDefinitionFolder","RulePayLoad"))
            try(java.util.stream.Stream<Path> files=Files.walk(root.resolve("btsn.common").resolve(directory))) {
                for(Path file:(Iterable<Path>)files.filter(Files::isRegularFile)::iterator)
                    hashes.put(file,java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
            }
        return hashes;
    }
    private static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
}
