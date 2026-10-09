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
                check(registry.services().contains("ValidationService") && !registry.services().contains("StochasticService"), "Stale catalogue survived another Open");
                selectCurrent(table,0,1,"ValidationService"); selectCurrent(table,0,2,"processToken");
                check(((List<?>)validate.invoke(frame)).isEmpty(), "Ordinary contract changed after selection");
            } catch (Throwable ex) { failure[0] = ex; }
            finally { frame.dispose(); }
        });
        if (failure[0] != null) throw new AssertionError("Deployment window regression", failure[0]);
        System.out.println("PASS: real deployment Open loads dropdowns immediately; six rows preserve aliases/adapters/ports; switching definitions replaces the catalogue");
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
    private static void check(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
}
