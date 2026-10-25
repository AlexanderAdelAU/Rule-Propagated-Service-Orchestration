package com.editor;
import java.io.File;
import java.lang.reflect.*;
import java.util.*;
import javax.swing.*;

/** Exercises the real deployment window's open path and table editors under a virtual display. */
public final class DeploymentEditorCheck {
    public static void main(String[] args) throws Exception {
        File common = new File(args[0], "btsn.common").getCanonicalFile();
        final Throwable[] failure = new Throwable[1];
        SwingUtilities.invokeAndWait(() -> {
            InfrastructureDefinitionFrame frame = new InfrastructureDefinitionFrame(true);
            try {
                Method open = InfrastructureDefinitionFrame.class.getDeclaredMethod("openFile", java.awt.Component.class, File.class); open.setAccessible(true);
                check(Boolean.TRUE.equals(open.invoke(frame, frame, new File(common, "ServiceDeploymentFolder/petrinet/TrafficLightModels.json"))), "Could not open deployment");
                Field registryField = InfrastructureDefinitionFrame.class.getDeclaredField("serviceRegistry"); registryField.setAccessible(true);
                ServiceRegistry registry = (ServiceRegistry)registryField.get(frame);
                check(registry.problem() == null, "Opening did not load catalogue: " + registry.problem());
                Field tableField = InfrastructureDefinitionFrame.class.getDeclaredField("capabilityTable"); tableField.setAccessible(true);
                JTable table = (JTable)tableField.get(frame);
                Method json = InfrastructureDefinitionFrame.class.getDeclaredMethod("toJson"); json.setAccessible(true);
                String before = (String)json.invoke(frame);
                for (int row=0;row<table.getRowCount();row++) {
                    selectCurrent(table,row,0,table.getValueAt(row,0).toString());
                    selectCurrent(table,row,1,"StochasticService");
                    selectCurrent(table,row,2,"processToken");
                    selectCurrent(table,row,6,"boolean-token");
                }
                check(before.equals(json.invoke(frame)), "Reselecting current values rewrote adapters, aliases or port slots");
                Method validate = InfrastructureDefinitionFrame.class.getDeclaredMethod("validateDefinition"); validate.setAccessible(true);
                check(((List<?>)validate.invoke(frame)).isEmpty(), "Opened deployment remains invalid: " + validate.invoke(frame));
                check(Boolean.TRUE.equals(open.invoke(frame,frame,new File(common,"ServiceDeploymentFolder/financial/FinancialSystem.json"))), "Could not open ordinary deployment");
                // StochasticService is still offered, but now as a standard service beside the financial catalogue, not as the Petri-net catalogue's own.
                check(registry.services().contains("ValidationService") && registry.isStandard("StochasticService"), "Stale catalogue survived another Open");
                selectCurrent(table,0,1,"ValidationService"); selectCurrent(table,0,2,"processToken");
                check(((List<?>)validate.invoke(frame)).isEmpty(), "Ordinary contract changed after selection");
            } catch (Throwable ex) { failure[0] = ex; }
            finally { frame.dispose(); }
        });
        if (failure[0] != null) throw new AssertionError("Deployment window regression", failure[0]);
        // Add host on the supplied infrastructure gives P7 a port no node uses (4009: P4 and P6 hold 4007 and 4008), not 4007.
        SwingUtilities.invokeAndWait(() -> {
            InfrastructureDefinitionFrame frame = new InfrastructureDefinitionFrame();
            try {
                Method load = InfrastructureDefinitionFrame.class.getDeclaredMethod("loadPhysicalNodes", String.class); load.setAccessible(true);
                load.invoke(frame, new String(java.nio.file.Files.readAllBytes(new File(common, "InfrastructureDefinitionFolder/SingleHost.json").toPath()), java.nio.charset.StandardCharsets.UTF_8));
                JButton add = button(frame.getContentPane(), "Add host");
                check(add != null, "No Add host button");
                add.doClick();
                Method json = InfrastructureDefinitionFrame.class.getDeclaredMethod("toJson"); json.setAccessible(true);
                String written = ((String)json.invoke(frame)).replaceAll("\\s+", "");
                check(written.contains("\"node\":\"P7\"") && written.contains("\"basePorts\":[4009]"), "Add host did not give P7 port 4009: " + written);
                Method validate = InfrastructureDefinitionFrame.class.getDeclaredMethod("validateDefinition"); validate.setAccessible(true);
                check(((List<?>)validate.invoke(frame)).isEmpty(), "Infrastructure with P7 invalid: " + validate.invoke(frame));
                check(InfrastructureDefinitionFrame.reservedPort(4106) && InfrastructureDefinitionFrame.reservedPort(4300) && !InfrastructureDefinitionFrame.reservedPort(4009),
                    "Administrative ports not kept");
            } catch (Throwable ex) { failure[0] = ex; }
            finally { frame.dispose(); }
        });
        if (failure[0] != null) throw new AssertionError("Infrastructure window regression", failure[0]);
        // Open dialogs start in the folder for their kind of definition, not wherever another dialog last was.
        File infrastructure = new File(common, "InfrastructureDefinitionFolder"), deployments = new File(common, "ServiceDeploymentFolder");
        check(InfrastructureDefinitionFrame.wrongRepositoryFolder(new File(common, "BusinessServiceDefinitions"), infrastructure), "A catalogue folder was kept for infrastructure");
        check(InfrastructureDefinitionFrame.wrongRepositoryFolder(new File(common, "BusinessServiceDefinitions/inventory"), infrastructure), "A catalogue subfolder was kept for infrastructure");
        check(InfrastructureDefinitionFrame.wrongRepositoryFolder(infrastructure, deployments), "The infrastructure folder was kept for deployments");
        check(!InfrastructureDefinitionFrame.wrongRepositoryFolder(infrastructure, infrastructure), "The right folder was replaced");
        check(!InfrastructureDefinitionFrame.wrongRepositoryFolder(new File(deployments, "inventory"), deployments), "A deployment subfolder was replaced");
        check(!InfrastructureDefinitionFrame.wrongRepositoryFolder(new File(System.getProperty("java.io.tmpdir")), infrastructure), "A folder outside btsn.common was replaced");
        System.out.println("PASS: real deployment Open loads dropdowns immediately; six rows preserve aliases/adapters/ports; switching definitions replaces the catalogue; Open starts in the right definition folder; Add host gives P7 a free port");
    }
    private static void selectCurrent(JTable table,int row,int column,String value) {
        check(table.editCellAt(row,column), "Cell not editable: "+row+"/"+column);
        check(table.getEditorComponent() instanceof JComboBox, "Missing dropdown at column "+column);
        JComboBox combo=(JComboBox)table.getEditorComponent();
        check(!combo.isEditable(), "Dropdown permits arbitrary typing");
        boolean present=false; for(int i=0;i<combo.getItemCount();i++) if(value.equals(combo.getItemAt(i))) present=true;
        check(present && combo.getItemCount()>1, "Dropdown has no declared choices: "+value);
        JLabel rendered=(JLabel)combo.getRenderer().getListCellRendererComponent(new JList(),value,-1,false,false);
        check(!rendered.getText().startsWith("Unresolved:"), "Valid value marked unresolved");
        combo.setSelectedItem(value);
        if(table.isEditing()) table.getCellEditor().stopCellEditing();
    }
    private static JButton button(java.awt.Container parent, String text) {
        for (java.awt.Component c : parent.getComponents()) {
            if (c instanceof JButton && text.equals(((JButton)c).getText())) return (JButton)c;
            if (c instanceof java.awt.Container) { JButton found = button((java.awt.Container)c, text); if (found != null) return found; }
        }
        return null;
    }
    private static void check(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
}
