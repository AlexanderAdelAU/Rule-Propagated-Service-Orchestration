package com.editor;

import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.List;
import javax.swing.*;

/**
 * On the standard catalogue a process has no services of its own: the Attributes panel offers Choose/Create catalogue
 * but no Add or Edit service, and the place's right-click Add and Edit are greyed out. Once a domain's catalogue is
 * chosen they come back, and Edit only for the place's own (not a standard) service.
 */
public final class ServiceActionsCheck {
    public static void main(String[] args) throws Exception {
        File common = new File(args[0], "btsn.common").getCanonicalFile();
        File process = new File(common, "ProcessDefinitionFolder/claims/Workflow/Claims_Workflow.json");
        Throwable[] failure = new Throwable[1];
        SwingUtilities.invokeAndWait(() -> {
            JFrame window = null;
            try {
                Canvas canvas = new Canvas();
                canvas.loadFromJSON(new String(Files.readAllBytes(process.toPath()), "UTF-8").replaceAll("\"(serviceDeployment|catalog)\"\\s*:\\s*\"[^\"]*\"", "\"$1\": \"\""));
                canvas.getServiceRegistry().useStandardCatalogue(common);
                ServiceRegistry registry = canvas.getServiceRegistry();
                check(!registry.hasDomainCatalogue(), "Not on the standard catalogue");
                ProcessElement triage = null;
                for (ProcessElement place : canvas.getPlaces()) if (place.getLabel().equals("Triage")) triage = place;
                triage.setServiceInstance(""); triage.setServiceOperations(new ArrayList<>());
                triage.setService("StochasticService"); registry.applyCatalogue(triage, "processToken");
                window = new JFrame(); window.add(new JScrollPane(canvas)); window.setSize(1200, 650); window.setVisible(true);

                // Standard catalogue: a standard service is not "Edit service in catalogue", and nothing can be added.
                EditorFrame attributes = new EditorFrame(canvas); attributes.updateSelection(triage);
                List<String> texts = texts(attributes);
                check(texts.contains("Choose/Create catalogue...") && !texts.contains("Edit service in catalogue...") && !texts.contains("Add service to catalogue..."),
                    "Standard catalogue attributes: " + texts);
                Map<String, Boolean> menu = rightClick(canvas, triage);
                check(Boolean.TRUE.equals(menu.get("Choose/Create catalogue...")) && Boolean.FALSE.equals(menu.get("Edit Service in Catalogue..."))
                    && Boolean.FALSE.equals(menu.get("Add Service to Catalogue...")), "Standard catalogue menu: " + menu);

                // The claims catalogue: Add comes back; Edit for the place's own service, not for a standard one.
                canvas.useCatalogue(new File(common, "BusinessServiceDefinitions/claims/Claims.json"));
                attributes.updateSelection(triage);
                texts = texts(attributes);
                check(texts.contains("Add service to catalogue...") && !texts.contains("Edit service in catalogue..."), "Domain catalogue, standard service: " + texts);
                menu = rightClick(canvas, triage);
                check(Boolean.TRUE.equals(menu.get("Add Service to Catalogue...")) && Boolean.FALSE.equals(menu.get("Edit Service in Catalogue...")), "Domain catalogue, standard service menu: " + menu);
                triage.setServiceOperations(new ArrayList<>()); triage.setService("ClaimsTriageService"); registry.applyCatalogue(triage, "triage");
                attributes.updateSelection(triage);
                check(texts(attributes).contains("Edit service in catalogue..."), "Own service not editable: " + texts(attributes));
                menu = rightClick(canvas, triage);
                check(Boolean.TRUE.equals(menu.get("Edit Service in Catalogue...")) && Boolean.TRUE.equals(menu.get("Add Service to Catalogue...")), "Own service menu: " + menu);
            } catch (Throwable ex) {
                failure[0] = ex;
            } finally {
                if (window != null) window.dispose();
            }
        });
        if (failure[0] != null) throw new AssertionError("Service actions", failure[0]);
        System.out.println("PASS: on the standard catalogue, Choose/Create catalogue only: no Add or Edit service button, right-click Add and Edit greyed out; with a domain catalogue Add returns, and Edit for the place's own service only");
    }

    /** The place's right-click menu: each item's text and whether it is enabled. */
    private static Map<String, Boolean> rightClick(Canvas canvas, ProcessElement place) {
        Point centre = place.getCenter();
        canvas.dispatchEvent(new java.awt.event.MouseEvent(canvas, java.awt.event.MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(),
            java.awt.event.InputEvent.BUTTON3_DOWN_MASK, centre.x, centre.y, 1, true, java.awt.event.MouseEvent.BUTTON3));
        Map<String, Boolean> items = new LinkedHashMap<>();
        for (Component c : canvas.lastContextMenu.getComponents()) if (c instanceof JMenuItem) items.put(((JMenuItem)c).getText(), c.isEnabled());
        canvas.lastContextMenu.setVisible(false);
        return items;
    }
    private static List<String> texts(Container parent) {
        List<String> result = new ArrayList<>();
        for (Component c : parent.getComponents()) {
            if (c instanceof AbstractButton) result.add(((AbstractButton)c).getText());
            if (c instanceof Container) result.addAll(texts((Container)c));
        }
        return result;
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
