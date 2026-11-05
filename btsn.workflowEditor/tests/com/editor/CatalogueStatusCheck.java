package com.editor;

import java.awt.Dimension;
import java.awt.Point;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.Rectangle;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;

/**
 * Regression check for the read-only legend: it reports the assigned service's true
 * catalogue without copying standard services into the selected domain catalogue.
 */
public final class CatalogueStatusCheck {
    public static void main(String[] args) throws Exception {
        File common = new File(args[0], "btsn.common").getCanonicalFile();
        File process = new File(common, "ProcessDefinitionFolder/claims/Workflow/Claims_Workflow.json");
        String json = Files.readString(process.toPath()).replaceAll(
            "\"(serviceDeployment|catalog)\"\\s*:\\s*\"[^\"]*\"", "\"$1\": \"\"");
        Throwable[] failure = new Throwable[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                Canvas canvas = new Canvas();
                canvas.setDefinitionLocation(process);
                canvas.loadFromJSON(json);
                canvas.useCatalogue(new File(common, "BusinessServiceDefinitions/claims/Claims.json"));
                ServiceRegistry registry = canvas.getServiceRegistry();
                List<ProcessElement> places = canvas.getPlaces();
                check(places.size() >= 2, "Expected two places in claims workflow");

                ProcessElement first = places.get(0), second = places.get(1);
                first.setLabel("P1");
                first.setService("StochasticService");
                first.setServiceOperations(new ArrayList<>());
                registry.applyCatalogue(first, "processToken");
                second.setLabel("P2");
                second.setService("ClaimsTriageService");
                second.setServiceOperations(new ArrayList<>());
                registry.applyCatalogue(second, "triage");

                List<CatalogueStatusOverlay.Entry> rows = CatalogueStatusOverlay.entries(canvas);
                check("P1".equals(rows.get(0).place) && "Standard Service".equals(rows.get(0).origin)
                    && rows.get(0).standard, "Standard service shown as own catalogue");
                check("P2".equals(rows.get(1).place) && "Claims".equals(rows.get(1).origin)
                    && !rows.get(1).standard, "Domain service not shown as Claims");
                check(registry.isStandard("StochasticService"), "Standard service ownership changed");

                second.setService("ServiceNotInAnyCatalogue");
                rows = CatalogueStatusOverlay.entries(canvas);
                check(rows.get(1).unresolved && "Unresolved".equals(rows.get(1).origin),
                    "Unknown service incorrectly assigned a catalogue");
                second.setService("ClaimsTriageService");
                second.setServiceOperations(new ArrayList<>());
                rows = CatalogueStatusOverlay.entries(canvas);
                check(rows.get(1).unresolved, "Service without selected operation not unresolved");
                registry.applyCatalogue(second, "triage");
                first.setLabel("RenamedP1");
                check("RenamedP1".equals(CatalogueStatusOverlay.entries(canvas).get(0).place),
                    "The legend retained an old place label");

                // Canvas overlay uses viewport pixels rather than scaled workflow coordinates.
                canvas.setSize(800, 600);
                JViewport viewport = new JViewport();
                viewport.setExtentSize(new Dimension(430, 300));
                viewport.setView(canvas);
                canvas.setSize(800, 600);
                canvas.positionCatalogueStatus();
                CatalogueStatusOverlay overlay = canvas.getCatalogueStatusOverlay();
                Rectangle expanded = overlay.getBounds();
                check(expanded.width == CatalogueStatusOverlay.WIDTH && expanded.y == 8,
                    "Expanded legend layout: " + expanded);
                overlay.setCollapsed(true);
                Rectangle collapsed = overlay.getBounds();
                check(collapsed.height == CatalogueStatusOverlay.HEADER
                    && collapsed.width == expanded.width, "Collapsed legend layout: " + collapsed);
                overlay.setCollapsed(false);
                check(overlay.getBounds().height > collapsed.height, "Expand did not restore rows");
                viewport.setViewPosition(new java.awt.Point(50, 40));
                canvas.positionCatalogueStatus();
                check(overlay.getX() == expanded.x + 50 && overlay.getY() == expanded.y + 40,
                    "Legend did not follow viewport scroll: " + overlay.getBounds());

                // Drag the header to a clear area. The panel must move but not
                // collapse, and a subsequent scroll must preserve its location
                // relative to the visible viewport.
                Rectangle beforeDrag = overlay.getBounds();
                mouse(overlay, MouseEvent.MOUSE_PRESSED, 40, 14,
                    InputEvent.BUTTON1_DOWN_MASK, MouseEvent.BUTTON1);
                mouse(overlay, MouseEvent.MOUSE_DRAGGED, -60, 64,
                    InputEvent.BUTTON1_DOWN_MASK, MouseEvent.NOBUTTON);
                mouse(overlay, MouseEvent.MOUSE_RELEASED, 40, 14, 0, MouseEvent.BUTTON1);
                Rectangle moved = overlay.getBounds();
                check(moved.x == beforeDrag.x - 100 && moved.y == beforeDrag.y + 50,
                    "Header drag did not move legend: " + beforeDrag + " -> " + moved);
                check(!overlay.isCollapsed(), "Drag should not collapse legend");

                viewport.setViewPosition(new Point(85, 100));
                canvas.positionCatalogueStatus();
                check(overlay.getX() == moved.x + 35 && overlay.getY() == moved.y + 60,
                    "Dragged legend did not follow viewport scroll: " + overlay.getBounds());

                // Zoom changes the model size, not the user's chosen legend
                // position in screen pixels.
                Point viewBeforeZoom = viewport.getViewPosition();
                Rectangle beforeZoom = overlay.getBounds();
                canvas.setZoomScale(1.5);
                viewport.doLayout();
                canvas.positionCatalogueStatus();
                Point viewAfterZoom = viewport.getViewPosition();
                check(overlay.getX() - viewAfterZoom.x == beforeZoom.x - viewBeforeZoom.x
                    && overlay.getY() - viewAfterZoom.y == beforeZoom.y - viewBeforeZoom.y,
                    "Dragged legend moved relative to viewport during zoom");

                // A short header click should still collapse and expand.
                clickHeader(overlay);
                check(overlay.isCollapsed(), "Header click did not collapse");
                clickHeader(overlay);
                check(!overlay.isCollapsed(), "Header click did not expand");

                // Dragging out of bounds should clamp the panel to the screen.
                Rectangle visible = canvas.getVisibleRect();
                canvas.moveCatalogueStatusTo(visible.x - 500, visible.y - 500);
                check(overlay.getX() == visible.x + 8 && overlay.getY() == visible.y + 8,
                    "Legend should stay within top-left viewport boundary");
                canvas.moveCatalogueStatusTo(visible.x + 5000, visible.y + 5000);
                check(overlay.getMaxX() <= visible.getMaxX() - 7
                    && overlay.getMaxY() <= visible.getMaxY() - 7,
                    "Legend should stay within right/bottom viewport boundaries");
            } catch (Throwable error) {
                failure[0] = error;
            }
        });
        if (failure[0] != null) throw new AssertionError("Catalogue status", failure[0]);
        System.out.println("PASS: catalogue status ownership, draggable viewport positioning, and click-to-collapse");
    }

    private static void clickHeader(CatalogueStatusOverlay overlay) {
        mouse(overlay, MouseEvent.MOUSE_PRESSED, 40, 14,
            InputEvent.BUTTON1_DOWN_MASK, MouseEvent.BUTTON1);
        mouse(overlay, MouseEvent.MOUSE_RELEASED, 40, 14, 0, MouseEvent.BUTTON1);
    }

    private static void mouse(CatalogueStatusOverlay overlay, int id,
                              int x, int y, int modifiers, int button) {
        overlay.dispatchEvent(new MouseEvent(overlay, id, System.currentTimeMillis(),
            modifiers, x, y, 1, false, button));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
