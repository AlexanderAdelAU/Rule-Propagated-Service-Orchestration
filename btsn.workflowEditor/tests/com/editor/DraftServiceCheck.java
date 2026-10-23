package com.editor;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;

/**
 * A service added while designing: written to the domain catalogue as a draft without disturbing the rest of the
 * file, offered to places, refused for deployment with the reason; then finished with starting code that compiles,
 * keeps the contract and routes; finished entries are not rewritten from a design.
 */
public final class DraftServiceCheck {
    public static void main(String[] args) throws Exception {
        File root = new File(args[0]).getCanonicalFile(), realCommon = new File(root, "btsn.common");
        Path common = Files.createTempDirectory("draft-service-").resolve("btsn.common");
        Path credit = common.resolve("BusinessServiceDefinitions/credit/Credit.json"), standard = common.resolve(ServiceRegistry.STANDARD_CATALOGUE);
        for (Path copy : Arrays.asList(credit, standard)) {
            Files.createDirectories(copy.getParent());
            Files.copy(realCommon.toPath().resolve(common.relativize(copy)), copy);
        }
        String original = Files.readString(credit);
        // The fraud-screen design: Fraud (FraudScreenService.screen) routes clear on to Credit and review to a Review end.
        Path process = common.resolve("ProcessDefinitionFolder/credit/Workflow/Credit_Fraud_Workflow.json");
        Files.createDirectories(process.getParent());
        Files.copy(root.toPath().resolve("btsn.workflowEditor/tests/fixtures/Credit_Fraud_Workflow.json"), process);

        // 1. A draft: only a name, an operation, an input and the routes.
        CatalogueWriter.Entry draft = new CatalogueWriter.Entry();
        draft.service = "FraudScreenService"; draft.operation = "screen"; draft.inputs.add("token"); draft.routes.addAll(Arrays.asList("clear", "review"));
        CatalogueWriter.add(credit.toFile(), draft);
        String written = Files.readString(credit);
        int added = written.indexOf("FraudScreenService") - 20;
        check(written.substring(0, original.lastIndexOf('}', original.lastIndexOf(']')) + 1).equals(original.substring(0, original.lastIndexOf('}', original.lastIndexOf(']')) + 1)),
            "Adding a service rewrote the existing entries");
        check(added > 0 && written.contains("\"status\": \"draft\"") && written.contains("\"routes\": [\"clear\", \"review\"]"), "Draft not written: " + written);
        ServiceRegistry registry = new ServiceRegistry();
        registry.loadCatalogue(credit.toFile());
        check(registry.services().contains("FraudScreenService") && registry.isDraft("FraudScreenService") && !registry.isDraft("CreditLimitService"), "Draft not offered: " + registry.services());
        check(registry.describe("FraudScreenService", "screen").contains("Draft: still needs a description"), "Draft not described as such");
        List<String> refused = registry.validateEndpoint("FraudScreenService", "screen", "", Arrays.asList("token"), "");
        Canvas canvas = new Canvas(); canvas.setDefinitionLocation(process.toFile()); canvas.loadFromJSON(Files.readString(process));
        check(canvas.validateServiceContracts().isEmpty(), "A draft should not stop the process being saved: " + canvas.validateServiceContracts());
        check(canvas.serviceBlockers().size() == 1 && canvas.serviceBlockers().get(0).startsWith("Place 'Fraud': FraudScreenService.screen is a draft"),
            "Deploy not stopped by the draft: " + canvas.serviceBlockers());
        check(refused.size() == 1 && refused.get(0).contains("is a draft in Credit.json") && refused.get(0).contains("its code: a Java class, or a service called over REST")
            && refused.get(0).contains("Edit Service in "), "Draft deployable or reason unclear: " + refused);

        // 2. Finished as a Java class with the editor's starting code.
        CatalogueWriter.Entry done = new CatalogueWriter.Entry();
        done.service = "FraudScreenService"; done.operation = "screen"; done.inputs.add("token"); done.routes.addAll(Arrays.asList("clear", "review"));
        done.description = "Screens a payment for fraud."; done.returns = "A screening result routed on clear or review.";
        done.returnAttribute = "token"; done.implementationClass = ServiceStub.className("credit", "FraudScreenService"); done.draft = false;
        Path source = ServiceStub.sourceFile(common.toFile(), done.implementationClass).toPath();
        Files.createDirectories(source.getParent());
        Files.writeString(source, ServiceStub.source(done.implementationClass, done));
        CatalogueWriter.updateDraft(credit.toFile(), "FraudScreenService", "screen", done);
        registry.reload();
        check(!registry.isDraft("FraudScreenService") && registry.validateEndpoint("FraudScreenService", "screen", "token", Arrays.asList("token"), "").isEmpty(),
            "Finished service not deployable: " + registry.validateEndpoint("FraudScreenService", "screen", "token", Arrays.asList("token"), ""));
        canvas.getServiceRegistry().reload();
        check(canvas.serviceBlockers().isEmpty(), "Finished service still stops Deploy: " + canvas.serviceBlockers());
        check(canvas.serviceNotes().size() == 1 && canvas.serviceNotes().get(0).contains("still runs the starting code"), "No reminder at Validate: " + canvas.serviceNotes());
        // A result published under a name the next place does not take stops the token there; it is reported while designing.
        for (ProcessElement place : canvas.getPlaces()) if ("Fraud".equals(place.getLabel())) place.getServiceOperations().get(0).setReturnAttribute("fraudScreenResult");
        check(canvas.chainMismatches().size() == 1 && canvas.chainMismatches().get(0).startsWith("Place 'Credit' takes token, but 'Fraud' publishes fraudScreenResult"),
            "Mismatched names not reported: " + canvas.chainMismatches());
        check(canvas.serviceBlockers().contains(canvas.chainMismatches().get(0)), "Mismatched names do not stop Deploy");
        check(registry.describe("FraudScreenService", "screen").contains("Still runs the starting code"), "No reminder to write the real logic");
        check(Files.readString(credit).startsWith(original.substring(0, original.indexOf("\"services\""))), "Catalogue header changed");
        try { CatalogueWriter.updateDraft(credit.toFile(), "CreditLimitService", "assess", done); throw new AssertionError("A finished service was rewritten from a design"); }
        catch (java.io.IOException expected) { check(expected.getMessage().contains("finished (active)"), expected.getMessage()); }

        // 2b. The place's own finished service opens filled in for editing. Starting code declaring no routes returns true,
        // so a guard testing clear is reported; adding the routes in the form rewrites the starting code to return them.
        ProcessElement fraud = null;
        for (ProcessElement place : canvas.getPlaces()) if ("Fraud".equals(place.getLabel())) fraud = place;
        fraud.getServiceOperations().get(0).setReturnAttribute("token");
        ServiceRegistry.Contract own = canvas.getServiceRegistry().domainContract(fraud);
        check(own != null && "FraudScreenService".equals(own.service) && !own.draft, "Finished service not offered for editing: " + own);
        check(canvas.getServiceRegistry().usedElsewhere("FraudScreenService", process.toFile()).isEmpty()
            && canvas.getServiceRegistry().usedElsewhere("FraudScreenService", null).equals(Arrays.asList("Credit_Fraud_Workflow.json")), "Other users of the service not found");
        CatalogueWriter.Entry noRoutes = CatalogueWriter.from(own); noRoutes.routes.clear();
        ServiceDefinitionDialog.write(credit.toFile(), common.toFile(), own, noRoutes, true);
        canvas.getServiceRegistry().reload();
        check(Files.readString(source).contains("String[] routes = {\"true\"};"), "Starting code not rewritten when its routes were removed");
        check(canvas.serviceBlockers().stream().anyMatch(n -> n.contains("tests \"clear\", but FraudScreenService (starting code) routes on true")),
            "A guard the starting code never matches is not reported: " + canvas.serviceBlockers());
        ServiceDefinitionDialog.write(credit.toFile(), common.toFile(), canvas.getServiceRegistry().domainContract(fraud), done, true);
        canvas.getServiceRegistry().reload();
        check(Files.readString(source).contains("String[] routes = {\"clear\", \"review\"};") && canvas.serviceBlockers().isEmpty(),
            "Adding the routes did not fix the starting code: " + canvas.serviceBlockers());
        // Without the review arc, review tokens would be dropped at Fraud: reported before Deploy and Run.
        List<Arrow> arcs = canvas.getArrows(), reviewArcs = new ArrayList<>();
        for (Arrow a : arcs) if ("review".equals(a.getDecisionValue())) reviewArcs.add(a);
        arcs.removeAll(reviewArcs);
        check(canvas.serviceBlockers().stream().anyMatch(n -> n.startsWith("Place 'Fraud': FraudScreenService routes on review, but no arc from T_out_Fraud tests it")
            && n.contains("so those tokens would be dropped")), "A route value with no arc is not reported: " + canvas.serviceBlockers());
        arcs.addAll(reviewArcs);
        check(canvas.serviceBlockers().isEmpty(), "Restoring the review arc did not clear the report: " + canvas.serviceBlockers());
        // The review arc drawn to an input transition instead of a Terminate node leads nowhere: it stops Deploy and Run.
        ProcessElement review = null;
        for (ProcessElement e : canvas.getElements()) if ("Review".equals(e.getLabel())) review = e;
        review.setNodeType("EdgeNode"); review.setNodeValue("EDGE_NODE");
        check(canvas.serviceBlockers().equals(Arrays.asList("Transition 'Review' leads nowhere: tokens reaching it stop there. Make it a Terminate node if the process ends there, or connect it to the next place.")),
            "A dead end is not reported: " + canvas.serviceBlockers());
        review.setNodeType("TerminateNode"); review.setNodeValue("TERMINATE_NODE");
        // A guard on the arc into a T_out is never tested: a reminder at Validate, not a reason to stop.
        Arrow into = null;
        for (Arrow a : arcs) if (a.getSource() == fraud) into = a;
        into.setDecisionValue("true");
        check(canvas.serviceBlockers().isEmpty() && canvas.serviceNotes().stream().anyMatch(n -> n.startsWith("The arc from place Fraud into T_out_Fraud carries the guard \"true\". It does nothing")),
            "An untested guard is not noted: " + canvas.serviceNotes());
        into.setDecisionValue("");
        String ownCode = Files.readString(source).replace(ServiceStub.MARKER, "real logic");
        Files.writeString(source, ownCode);
        ServiceDefinitionDialog.write(credit.toFile(), common.toFile(), canvas.getServiceRegistry().domainContract(fraud), noRoutes, true);
        check(Files.readString(source).equals(ownCode), "The designer's own code was overwritten");
        Files.writeString(source, ServiceStub.source(done.implementationClass, done));
        ServiceDefinitionDialog.write(credit.toFile(), common.toFile(), canvas.getServiceRegistry().domainContract(fraud), done, true);
        canvas.getServiceRegistry().reload();

        // 3. The starting code compiles against the service base class and routes each value in turn.
        Path classes = Files.createTempDirectory("draft-classes-");
        String classpath = new File(realCommon, "lib/json-simple-1.1.1.jar").getPath();
        int compiled = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), "-cp", classpath,
            "-sourcepath", new File(realCommon, "src").getPath(), source.toString());
        check(compiled == 0, "Starting code does not compile");
        try (URLClassLoader loader = new URLClassLoader(new URL[] {classes.toUri().toURL(), new File(classpath).toURI().toURL()})) {
            Class<?> service = loader.loadClass(done.implementationClass);
            List<String> paths = new ArrayList<>();
            for (String token : Arrays.asList("1000000", "1010000", "1020000")) {
                Object instance = service.getConstructor(String.class).newInstance(token);
                String result = (String)service.getMethod("screen", String.class).invoke(instance, "{}");
                check(result.startsWith("{\"token\":"), "Result not under the return attribute: " + result);
                paths.add(result.replaceAll(".*\"routing_path\":\"([^\"]*)\".*", "$1"));
            }
            check(paths.equals(Arrays.asList("clear", "review", "clear")), "Starting code does not take the routes in turn: " + paths);
        }
        CatalogueWriter.Entry plain = new CatalogueWriter.Entry();
        plain.service = "NoteService"; plain.operation = "note"; plain.inputs.add("token"); plain.returnAttribute = "noted";
        check(ServiceStub.source(ServiceStub.className("credit", "NoteService"), plain).contains("String[] routes = {\"true\"};"), "Starting code without routes should return true");
        check(DraftServiceCheck.class.getClassLoader() != null && ServiceDefinitionDialog.refusal(registryFor(standard.toFile())) != null, "A service could be added to the standard catalogue");
        System.out.println("PASS: draft service added without disturbing the catalogue, offered, saved with the process but refused for deployment with its reason; finished with starting code that compiles, keeps the contract, routes in turn (or true) and is flagged until replaced; mismatched names between places reported; the place own service edited, its starting code kept in step with its routes and code the designer wrote left alone; finished entries not rewritten; standard catalogue protected");
    }

    private static ServiceRegistry registryFor(File catalogue) throws Exception {
        ServiceRegistry registry = new ServiceRegistry(); registry.loadCatalogue(catalogue); return registry;
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
