package com.editor;


import javax.swing.*;

import com.editor.animator.*;

import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Canvas panel for drawing Petri net elements with multi-selection support
 */
public class Canvas extends JPanel {
    private static final int GRID_SIZE = 20; // Grid spacing in pixels
    
    private List<ProcessElement> elements;
    private List<Arrow> arrows;
    private List<TextElement> textElements;
    
    private ProcessElement.Type selectedType = null;
    private ProcessElement draggedElement = null;
    private ProcessElement connectionSource = null;
    
    // Multi-selection support
    private Set<ProcessElement> selectedElements = new HashSet<>();
    private Set<TextElement> selectedTextElements = new HashSet<>();  // Multi-selection for text
    private Arrow selectedArrow = null;  // Track selected arrow
    private TextElement selectedTextElement = null;  // Track selected text element (legacy/single)
    
    // Rubber band selection
    private Rectangle rubberBandRect = null;
    private Point rubberBandStart = null;
    
    // Group dragging
    private boolean draggingGroup = false;
    private Point groupDragStart = null;
    private List<Point> groupInitialPositions = null;
    private List<List<Point>> groupInitialWaypoints = null;  // Store waypoint positions during group drag
    private List<Point> groupInitialTextPositions = null;  // Store text positions during group drag
    
    private Point draggedWaypoint = null;  // Track dragged waypoint
    private Arrow draggedWaypointArrow = null;  // Arrow containing dragged waypoint
    private Point dragOffset = null;
    private Point mousePos = null;
    
    // Arrow body dragging (moves all waypoints together)
    private boolean draggingArrowBody = false;
    private Point arrowDragStart = null;
    private List<Point> arrowInitialWaypoints = null;
    
    // Label dragging (moves arrow label away from arrow line)
    private boolean draggingLabel = false;
    private Arrow labelDragArrow = null;
    private Point labelDragStart = null;
    private Point labelInitialOffset = null;
    
    // Handle dragging for transitions
    private boolean draggingLeftHandle = false;
    private boolean draggingRightHandle = false;
    private boolean draggingRotationHandle = false;
    private Point lastHandlePos = null;
    
    private boolean connectMode = false;
    private boolean textMode = false;
    
    // Click-to-connect mode: click source, click waypoints, click target
    private boolean clickConnectMode = false;
    private List<Point> clickConnectWaypoints = new ArrayList<>();
    
    // Selection listener for attributes panel
    private SelectionListener selectionListener = null;
    
    // Cancel listener for button reset
    private Runnable cancelListener = null;
    
    // Undo/Redo manager
    private UndoRedoManager undoRedoManager = null;
    
    // Change listener for tracking modifications
    private Runnable changeListener = null;
    
    // Clipboard for copy/paste
    private List<ProcessElement> clipboardElements = new ArrayList<>();
    private List<Arrow> clipboardArrows = new ArrayList<>();
    private List<TextElement> clipboardTextElements = new ArrayList<>();
    private static final int PASTE_OFFSET = 30; // Offset for pasted elements
    
    // Store raw metadata and documentation JSON for preservation
    private String metadataJSON = null;
    private String documentationJSON = null;
    
    // Store processType for preservation (e.g., "PetriNet", "SOA")
    private String processType = null;
    
    // Token animation state - now uses TokenAnimState objects
    private Map<String, TokenAnimState> animatedTokenStates = new HashMap<>();
    private Map<String, Color> tokenColors = new HashMap<>();
    private float tokenPulsePhase = 0;
    
    // Buffer state at T_in transitions: T_in label -> list of buffered tokens
    private Map<String, List<BufferedToken>> tInBufferStates = new HashMap<>();
    
    // Accumulated terminated tokens: Terminate node label -> list of token states
    private Map<String, List<TokenAnimState>> terminatedTokenStates = new HashMap<>();
    
    // Zoom support
    private double zoomScale = 1.0;
    private static final double ZOOM_MIN = 0.25;
    private static final double ZOOM_MAX = 3.0;
    private static final double ZOOM_STEP = 0.1;
    
    public interface SelectionListener {
        void onSelectionChanged(Object selection);
    }
    
    public void setSelectionListener(SelectionListener listener) {
        this.selectionListener = listener;
    }
    
    public void setCancelListener(Runnable listener) {
        this.cancelListener = listener;
    }
    
    public void setChangeListener(Runnable listener) {
        this.changeListener = listener;
    }
    
    // Zoom change listener
    private Runnable zoomChangeListener = null;
    
    public void setZoomChangeListener(Runnable listener) {
        this.zoomChangeListener = listener;
    }
    
    public void setUndoRedoManager(UndoRedoManager manager) {
        this.undoRedoManager = manager;
    }
    
    // Process type getter/setter
    public String getProcessType() {
        return processType;
    }
    
    public void setProcessType(String processType) {
        this.processType = processType;
        notifyChange();
    }
    
    private void saveUndoState() {
        if (undoRedoManager != null) {
            undoRedoManager.saveState();
        }
        notifyChange();
    }
    
    private void notifyChange() {
        if (changeListener != null) {
            changeListener.run();
        }
    }
    
    // ============ ZOOM METHODS ============
    
    /**
     * Convert screen coordinates to canvas (model) coordinates
     */
    private int toCanvasX(int screenX) {
        return (int) (screenX / zoomScale);
    }
    
    private int toCanvasY(int screenY) {
        return (int) (screenY / zoomScale);
    }
    
    private Point toCanvasPoint(Point screenPoint) {
        return new Point(toCanvasX(screenPoint.x), toCanvasY(screenPoint.y));
    }
    
    /**
     * Convert canvas coordinates to screen coordinates
     */
    private int toScreenX(int canvasX) {
        return (int) (canvasX * zoomScale);
    }
    
    private int toScreenY(int canvasY) {
        return (int) (canvasY * zoomScale);
    }
    
    /**
     * Get current zoom scale
     */
    public double getZoomScale() {
        return zoomScale;
    }
    
    /**
     * Set zoom scale (clamped to min/max)
     */
    public void setZoomScale(double scale) {
        double oldScale = zoomScale;
        zoomScale = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, scale));
        if (oldScale != zoomScale) {
            updateCanvasSize();
            if (zoomChangeListener != null) {
                zoomChangeListener.run();
            }
            repaint();
        }
    }
    
    /**
     * Zoom in by one step
     */
    public void zoomIn() {
        setZoomScale(zoomScale + ZOOM_STEP);
    }
    
    /**
     * Zoom out by one step
     */
    public void zoomOut() {
        setZoomScale(zoomScale - ZOOM_STEP);
    }
    
    /**
     * Reset zoom to 100%
     */
    public void resetZoom() {
        setZoomScale(1.0);
    }
    
    /**
     * Zoom to fit all content in the visible area
     */
    public void zoomToFit() {
        if (elements.isEmpty()) {
            resetZoom();
            return;
        }
        
        // Find bounds of all elements
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        int maxX = 0, maxY = 0;
        
        for (ProcessElement element : elements) {
            minX = Math.min(minX, element.getX());
            minY = Math.min(minY, element.getY());
            maxX = Math.max(maxX, element.getX() + element.getWidth());
            maxY = Math.max(maxY, element.getY() + element.getHeight());
        }
        
        // Add margin
        int contentWidth = maxX - minX + 100;
        int contentHeight = maxY - minY + 100;
        
        // Get visible area from parent scroll pane
        Container parent = getParent();
        if (parent instanceof JViewport) {
            Dimension viewSize = ((JViewport) parent).getExtentSize();
            double scaleX = viewSize.getWidth() / contentWidth;
            double scaleY = viewSize.getHeight() / contentHeight;
            setZoomScale(Math.min(scaleX, scaleY));
        }
    }
    
    private void notifySelectionChanged(Object selection) {
        if (selectionListener != null) {
            selectionListener.onSelectionChanged(selection);
        }
    }
    
    public Canvas() {
        elements = new ArrayList<>();
        arrows = new ArrayList<>();
        textElements = new ArrayList<>();
        
        setBackground(Color.WHITE);
        setPreferredSize(new Dimension(800, 600));
        
        MouseAdapter mouseHandler = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.getClickCount() == 1) {
                    handleMousePressed(e);
                }
            }
            
            @Override
            public void mouseReleased(MouseEvent e) {
                handleMouseReleased(e);
            }
            
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    handleDoubleClick(e);
                } else if (e.isPopupTrigger() || SwingUtilities.isRightMouseButton(e)) {
                    handleRightClick(e);
                }
            }
        };
        
        addMouseListener(mouseHandler);
        
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                handleMouseDragged(e);
            }
            
            @Override
            public void mouseMoved(MouseEvent e) {
                mousePos = toCanvasPoint(e.getPoint());
                if (connectMode && connectionSource != null) {
                    repaint();
                }
            }
        });
        
        // Add keyboard listener for delete
        setFocusable(true);
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_DELETE || e.getKeyCode() == KeyEvent.VK_BACK_SPACE) {
                    if (!selectedElements.isEmpty()) {
                        // Delete all selected elements
                        saveUndoState();  // Save before deleting
                        for (ProcessElement element : new HashSet<>(selectedElements)) {
                            deleteElement(element);
                        }
                        selectedElements.clear();
                        notifySelectionChanged(null);  // Clear attributes panel after delete
                        repaint();
                    } else if (!selectedTextElements.isEmpty()) {
                        // Delete all selected text elements (multi-selection)
                        saveUndoState();  // Save before deleting
                        for (TextElement text : new HashSet<>(selectedTextElements)) {
                            textElements.remove(text);
                        }
                        selectedTextElements.clear();
                        selectedTextElement = null;
                        notifySelectionChanged(null);  // Clear attributes panel after delete
                        repaint();
                    } else if (selectedArrow != null) {
                        saveUndoState();  // Save before deleting
                        arrows.remove(selectedArrow);
                        selectedArrow = null;
                        notifySelectionChanged(null);  // Clear attributes panel after delete
                        repaint();
                    } else if (selectedTextElement != null) {
                        saveUndoState();  // Save before deleting
                        textElements.remove(selectedTextElement);
                        selectedTextElement = null;
                        notifySelectionChanged(null);  // Clear attributes panel after delete
                        repaint();
                    }
                } else if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    // Clear selection and cancel any connect modes on Escape
                    clearSelection();;
                    connectMode = false;
                    clickConnectMode = false;
                    textMode = false;
                    selectedType = null;
                    connectionSource = null;
                    clickConnectWaypoints.clear();
                    setCursor(Cursor.getDefaultCursor());
                    if (cancelListener != null) {
                        cancelListener.run();
                    }
                    repaint();
                }
                // Note: Ctrl+C and Ctrl+V are handled by menu accelerators in PetriNetEditor
            }
        });
        
        // Add mouse wheel listener for zoom
        addMouseWheelListener(e -> {
            if (e.isControlDown()) {
                // Ctrl+Wheel = zoom
                if (e.getWheelRotation() < 0) {
                    zoomIn();
                } else {
                    zoomOut();
                }
                e.consume();
            }
        });
    }
    
    /**
     * Snap coordinate to grid
     */
    private int snapToGrid(int value) {
        return Math.round((float) value / GRID_SIZE) * GRID_SIZE;
    }
    
    private void handleMousePressed(MouseEvent e) {
        requestFocusInWindow();  // Get keyboard focus
        
        // Convert screen coordinates to canvas coordinates for zoom support
        int canvasX = toCanvasX(e.getX());
        int canvasY = toCanvasY(e.getY());
        
        if (selectedType != null) {
            // Add new element - snap CENTER to grid
            saveUndoState();  // Save before adding
            int centerX = snapToGrid(canvasX);
            int centerY = snapToGrid(canvasY);
            
            // Calculate top-left from centered position
            ProcessElement element = new ProcessElement(selectedType, 0, 0);
            int x = centerX - element.getWidth() / 2;
            int y = centerY - element.getHeight() / 2;
            element.setPosition(x, y);
            
            elements.add(element);
            selectedType = null;
            repaint();
        } else if (textMode) {
            // Add new text element
            saveUndoState();  // Save before adding
            TextElement textElement = new TextElement(canvasX, canvasY);
            textElements.add(textElement);
            textMode = false;
            setCursor(Cursor.getDefaultCursor());
            repaint();
        } else if (connectMode || clickConnectMode) {
            // Handle connection modes
            ProcessElement clickedElement = null;
            for (ProcessElement element : elements) {
                if (element.contains(canvasX, canvasY)) {
                    clickedElement = element;
                    break;
                }
            }
            
            if (clickConnectMode) {
                // Click-to-connect mode: click source, click waypoints, click target
                if (connectionSource == null) {
                    // First click: set source
                    if (clickedElement != null) {
                        connectionSource = clickedElement;
                        clickConnectWaypoints.clear();
                    }
                } else if (clickedElement != null && clickedElement != connectionSource) {
                    // Clicked on a different element: complete connection
                    completeClickConnection(clickedElement);
                } else if (clickedElement == null) {
                    // Clicked on empty space: add waypoint
                    clickConnectWaypoints.add(new Point(snapToGrid(canvasX), snapToGrid(canvasY)));
                    repaint();
                }
                // If clicked on source again, do nothing (could cancel if desired)
            } else {
                // Original drag-connect mode: just set source
                if (clickedElement != null) {
                    connectionSource = clickedElement;
                }
            }
        } else {
            // Check if clicking on a handle first (if single element is selected)
            if (selectedElements.size() == 1) {
                ProcessElement singleSelected = selectedElements.iterator().next();
                if (singleSelected.getType() == ProcessElement.Type.TRANSITION) {
                    if (singleSelected.isNearHandle(singleSelected.getLeftResizeHandle(), canvasX, canvasY)) {
                        draggingLeftHandle = true;
                        lastHandlePos = new Point(canvasX, canvasY);
                        return;
                    } else if (singleSelected.isNearHandle(singleSelected.getRightResizeHandle(), canvasX, canvasY)) {
                        draggingRightHandle = true;
                        lastHandlePos = new Point(canvasX, canvasY);
                        return;
                    } else if (singleSelected.isNearHandle(singleSelected.getRotationHandle(), canvasX, canvasY)) {
                        draggingRotationHandle = true;
                        return;
                    }
                }
            }
            
            // Check if clicking on a waypoint
            for (Arrow arrow : arrows) {
                // First check if clicking on the arrow's label (for dragging)
                if (arrow.isPointOnLabel(canvasX, canvasY)) {
                    saveUndoState();
                    selectedArrow = arrow;
                    selectedElements.clear();
                    selectedTextElement = null;
                    draggingLabel = true;
                    labelDragArrow = arrow;
                    labelDragStart = new Point(canvasX, canvasY);
                    // Store initial offset (or 0,0 if none)
                    Point currentOffset = arrow.getLabelOffset();
                    labelInitialOffset = currentOffset != null ? 
                        new Point(currentOffset.x, currentOffset.y) : new Point(0, 0);
                    notifySelectionChanged(selectedArrow);
                    repaint();
                    return;
                }
                
                Point wp = arrow.findWaypointAt(canvasX, canvasY);
                if (wp != null) {
                    draggedWaypoint = wp;
                    draggedWaypointArrow = arrow;
                    selectedArrow = arrow;
                    selectedElements.clear();  // Clear element selection only
                    notifySelectionChanged(selectedArrow);
                    repaint();
                    return;
                }
            }
            
            // Check if clicking on an already selected element
            boolean clickedOnSelection = false;
            for (ProcessElement element : selectedElements) {
                if (element.contains(canvasX, canvasY)) {
                    clickedOnSelection = true;
                    draggedElement = element;
                    
                    // Start group dragging
                    if (!e.isControlDown()) {
                        saveUndoState();  // Save before moving
                        draggingGroup = true;
                        groupDragStart = new Point(canvasX, canvasY);
                        groupInitialPositions = new ArrayList<>();
                        for (ProcessElement el : selectedElements) {
                            groupInitialPositions.add(new Point(el.getX(), el.getY()));
                        }
                        
                        // Store initial waypoint positions
                        groupInitialWaypoints = new ArrayList<>();
                        for (Arrow arrow : arrows) {
                            List<Point> waypointCopies = new ArrayList<>();
                            for (Point wp : arrow.getWaypoints()) {
                                waypointCopies.add(new Point(wp.x, wp.y));
                            }
                            groupInitialWaypoints.add(waypointCopies);
                        }
                        
                        // Store initial text element positions
                        groupInitialTextPositions = new ArrayList<>();
                        for (TextElement text : selectedTextElements) {
                            groupInitialTextPositions.add(new Point(text.getX(), text.getY()));
                        }
                    }
                    return;
                }
            }
            
            // Check if clicking on an element (not already selected)
            ProcessElement clickedElement = null;
            for (ProcessElement element : elements) {
                if (element.contains(canvasX, canvasY)) {
                    clickedElement = element;
                    break;
                }
            }
            
            if (clickedElement != null) {
                // Element clicked
                draggedElement = clickedElement;
                
                if (e.isControlDown()) {
                    // Ctrl+Click: Toggle selection
                    if (selectedElements.contains(clickedElement)) {
                        selectedElements.remove(clickedElement);
                    } else {
                        selectedElements.add(clickedElement);
                    }
                    selectedArrow = null;
                    // Notify with single element if only one selected, null otherwise
                    notifySelectionChanged(selectedElements.size() == 1 ? selectedElements.iterator().next() : null);
                } else {
                    // Regular click: select this element only
                    selectedElements.clear();
                    selectedElements.add(clickedElement);
                    selectedArrow = null;
                    selectedTextElements.clear();  // Clear text selection when clicking single element
                    notifySelectionChanged(clickedElement);
                    
                    // Start group dragging (even for single element)
                    saveUndoState();  // Save before moving
                    draggingGroup = true;
                    groupDragStart = new Point(canvasX, canvasY);
                    groupInitialPositions = new ArrayList<>();
                    for (ProcessElement el : selectedElements) {
                        groupInitialPositions.add(new Point(el.getX(), el.getY()));
                    }
                    
                    // Store initial waypoint positions
                    groupInitialWaypoints = new ArrayList<>();
                    for (Arrow arrow : arrows) {
                        List<Point> waypointCopies = new ArrayList<>();
                        for (Point wp : arrow.getWaypoints()) {
                            waypointCopies.add(new Point(wp.x, wp.y));
                        }
                        groupInitialWaypoints.add(waypointCopies);
                    }
                    
                    // Store initial text element positions
                    groupInitialTextPositions = new ArrayList<>();
                    for (TextElement text : selectedTextElements) {
                        groupInitialTextPositions.add(new Point(text.getX(), text.getY()));
                    }
                }
                repaint();
                return;
            }
            
            // Check if clicking on a text element
            TextElement clickedText = null;
            for (TextElement textElement : textElements) {
                if (textElement.contains(canvasX, canvasY)) {
                    clickedText = textElement;
                    break;
                }
            }
            
            if (clickedText != null) {
                saveUndoState();  // Save before any changes
                selectedTextElement = clickedText;
                selectedElements.clear();
                selectedArrow = null;
                draggedElement = null;
                dragOffset = new Point(canvasX - clickedText.getX(), canvasY - clickedText.getY());
                notifySelectionChanged(clickedText);
                repaint();
                return;
            }
            
            // Check if clicked near an arrow
            Arrow closestArrow = findClosestArrow(canvasX, canvasY);
            if (closestArrow != null) {
                // Check if clicking on a waypoint first
                Point wp = closestArrow.findWaypointAt(canvasX, canvasY);
                if (wp != null) {
                    // Dragging a specific waypoint
                    saveUndoState();
                    draggedWaypoint = wp;
                    draggedWaypointArrow = closestArrow;
                    selectedArrow = closestArrow;
                    selectedElements.clear();
                    notifySelectionChanged(selectedArrow);
                    repaint();
                    return;
                }
                
                // Check if arrow connects selected elements - if so, drag the group
                if (selectedElements.size() > 1 &&
                    selectedElements.contains(closestArrow.getSource()) &&
                    selectedElements.contains(closestArrow.getTarget())) {
                    // Start group dragging when clicking arrow between selected elements
                    draggingGroup = true;
                    groupDragStart = new Point(canvasX, canvasY);
                    groupInitialPositions = new ArrayList<>();
                    for (ProcessElement el : selectedElements) {
                        groupInitialPositions.add(new Point(el.getX(), el.getY()));
                    }
                    
                    // Store initial waypoint positions
                    groupInitialWaypoints = new ArrayList<>();
                    for (Arrow arrow : arrows) {
                        List<Point> waypointCopies = new ArrayList<>();
                        for (Point waypoint : arrow.getWaypoints()) {
                            waypointCopies.add(new Point(waypoint.x, waypoint.y));
                        }
                        groupInitialWaypoints.add(waypointCopies);
                    }
                    
                    // Store initial text element positions
                    groupInitialTextPositions = new ArrayList<>();
                    for (TextElement text : selectedTextElements) {
                        groupInitialTextPositions.add(new Point(text.getX(), text.getY()));
                    }
                } else if (!closestArrow.getWaypoints().isEmpty()) {
                    // Arrow has waypoints - start dragging arrow body
                    saveUndoState();
                    if (closestArrow != selectedArrow) {
                        closestArrow.clearNewlyCreatedWaypoint();
                    }
                    selectedArrow = closestArrow;
                    selectedElements.clear();
                    notifySelectionChanged(selectedArrow);
                    
                    draggingArrowBody = true;
                    arrowDragStart = new Point(canvasX, canvasY);
                    arrowInitialWaypoints = new ArrayList<>();
                    for (Point waypoint : closestArrow.getWaypoints()) {
                        arrowInitialWaypoints.add(new Point(waypoint.x, waypoint.y));
                    }
                } else {
                    // Just select the arrow - clear any newly created waypoint marker
                    if (closestArrow != selectedArrow) {
                        closestArrow.clearNewlyCreatedWaypoint();
                    }
                    selectedArrow = closestArrow;
                    selectedElements.clear();  // Clear element selection only
                    notifySelectionChanged(selectedArrow);
                }
                repaint();
                return;
            }
            
            // Clicked on empty space - cancel any active modes and start rubber band selection
            if (!e.isControlDown()) {
                clearSelection();
            }
            // Cancel connect mode and element placement
            connectMode = false;
            textMode = false;
            selectedType = null;
            connectionSource = null;
            setCursor(Cursor.getDefaultCursor());
            
            // Notify editor to reset buttons
            if (cancelListener != null) {
                cancelListener.run();
            }
            
            rubberBandStart = new Point(canvasX, canvasY);
            rubberBandRect = new Rectangle(rubberBandStart);
            repaint();
        }
    }
    
    private void handleMouseDragged(MouseEvent e) {
        // Convert screen coordinates to canvas coordinates
        int canvasX = toCanvasX(e.getX());
        int canvasY = toCanvasY(e.getY());
        
        if (selectedTextElement != null && dragOffset != null) {
            // Drag text element
            selectedTextElement.setPosition(canvasX - dragOffset.x, canvasY - dragOffset.y);
            repaint();
        } else if (draggingLeftHandle && selectedElements.size() == 1) {
            ProcessElement element = selectedElements.iterator().next();
            int dx = canvasX - lastHandlePos.x;
            int dy = canvasY - lastHandlePos.y;
            element.resizeFromLeft(dx, dy);
            lastHandlePos = new Point(canvasX, canvasY);
            repaint();
        } else if (draggingRightHandle && selectedElements.size() == 1) {
            ProcessElement element = selectedElements.iterator().next();
            int dx = canvasX - lastHandlePos.x;
            int dy = canvasY - lastHandlePos.y;
            element.resizeFromRight(dx, dy);
            lastHandlePos = new Point(canvasX, canvasY);
            repaint();
        } else if (draggingRotationHandle && selectedElements.size() == 1) {
            ProcessElement element = selectedElements.iterator().next();
            element.rotateTowards(canvasX, canvasY);
            repaint();
        } else if (draggedWaypoint != null) {
            // Drag waypoint - snap to grid
            draggedWaypoint.x = snapToGrid(canvasX);
            draggedWaypoint.y = snapToGrid(canvasY);
            repaint();
        } else if (draggingLabel && labelDragArrow != null) {
            // Drag arrow label offset
            int dx = canvasX - labelDragStart.x;
            int dy = canvasY - labelDragStart.y;
            labelDragArrow.setLabelOffset(labelInitialOffset.x + dx, labelInitialOffset.y + dy);
            repaint();
        } else if (draggingArrowBody && selectedArrow != null && arrowDragStart != null) {
            // Drag all waypoints of the arrow together
            int dx = canvasX - arrowDragStart.x;
            int dy = canvasY - arrowDragStart.y;
            
            List<Point> waypoints = selectedArrow.getWaypoints();
            for (int i = 0; i < waypoints.size(); i++) {
                Point initialPos = arrowInitialWaypoints.get(i);
                waypoints.get(i).x = snapToGrid(initialPos.x + dx);
                waypoints.get(i).y = snapToGrid(initialPos.y + dy);
            }
            repaint();
        } else if (draggingGroup && groupDragStart != null) {
            // Drag all selected elements together AND their arrow waypoints
            // Calculate raw offset
            int rawDx = canvasX - groupDragStart.x;
            int rawDy = canvasY - groupDragStart.y;
            
            // Snap the first element's CENTER position to determine snapped offset
            // Calculate dx/dy based on either process elements or text elements
            int dx = rawDx;
            int dy = rawDy;
            
            if (!groupInitialPositions.isEmpty()) {
                ProcessElement firstElement = selectedElements.iterator().next();
                Point firstInitialPos = groupInitialPositions.get(0);
                
                // Calculate center position
                int centerX = firstInitialPos.x + firstElement.getWidth() / 2 + rawDx;
                int centerY = firstInitialPos.y + firstElement.getHeight() / 2 + rawDy;
                
                // Snap center to grid
                int snappedCenterX = snapToGrid(centerX);
                int snappedCenterY = snapToGrid(centerY);
                
                // Calculate snapped deltas based on center movement
                dx = snappedCenterX - (firstInitialPos.x + firstElement.getWidth() / 2);
                dy = snappedCenterY - (firstInitialPos.y + firstElement.getHeight() / 2);
                
                int i = 0;
                for (ProcessElement element : selectedElements) {
                    Point initialPos = groupInitialPositions.get(i);
                    element.setPosition(initialPos.x + dx, initialPos.y + dy);
                    i++;
                }
                
                // Also move waypoints of arrows connected to selected elements
                for (Arrow arrow : arrows) {
                    // Move waypoints if both source and target are in selection
                    if (selectedElements.contains(arrow.getSource()) && 
                        selectedElements.contains(arrow.getTarget())) {
                        for (Point wp : arrow.getWaypoints()) {
                            // Find initial position of this waypoint
                            int arrowIndex = arrows.indexOf(arrow);
                            int wpIndex = arrow.getWaypoints().indexOf(wp);
                            wp.x = groupInitialWaypoints.get(arrowIndex).get(wpIndex).x + dx;
                            wp.y = groupInitialWaypoints.get(arrowIndex).get(wpIndex).y + dy;
                        }
                    }
                }
            } else if (!groupInitialTextPositions.isEmpty()) {
                // Only text elements selected - snap based on first text element
                Point firstTextPos = groupInitialTextPositions.get(0);
                int snappedX = snapToGrid(firstTextPos.x + rawDx);
                int snappedY = snapToGrid(firstTextPos.y + rawDy);
                dx = snappedX - firstTextPos.x;
                dy = snappedY - firstTextPos.y;
            }
            
            // Move selected text elements
            if (groupInitialTextPositions != null) {
                int i = 0;
                for (TextElement text : selectedTextElements) {
                    if (i < groupInitialTextPositions.size()) {
                        Point initialPos = groupInitialTextPositions.get(i);
                        text.setPosition(initialPos.x + dx, initialPos.y + dy);
                    }
                    i++;
                }
            }
            
            repaint();
        } else if (rubberBandStart != null) {
            // Update rubber band rectangle
            int x = Math.min(rubberBandStart.x, canvasX);
            int y = Math.min(rubberBandStart.y, canvasY);
            int width = Math.abs(canvasX - rubberBandStart.x);
            int height = Math.abs(canvasY - rubberBandStart.y);
            rubberBandRect = new Rectangle(x, y, width, height);
            repaint();
        }
    }
    
    private void handleMouseReleased(MouseEvent e) {
        // Convert screen coordinates to canvas coordinates
        int canvasX = toCanvasX(e.getX());
        int canvasY = toCanvasY(e.getY());
        
        if (selectedTextElement != null && dragOffset != null) {
            dragOffset = null;
        } else if (draggingLeftHandle || draggingRightHandle || draggingRotationHandle) {
            draggingLeftHandle = false;
            draggingRightHandle = false;
            draggingRotationHandle = false;
            lastHandlePos = null;
        } else if (draggedWaypoint != null) {
            // Waypoint was moved - clear newly created marker
            if (draggedWaypointArrow != null) {
                draggedWaypointArrow.clearNewlyCreatedWaypoint();
            }
            draggedWaypoint = null;
            draggedWaypointArrow = null;
        } else if (draggingArrowBody) {
            // Arrow body was moved - clear newly created marker
            if (selectedArrow != null) {
                selectedArrow.clearNewlyCreatedWaypoint();
            }
            draggingArrowBody = false;
            arrowDragStart = null;
            arrowInitialWaypoints = null;
        } else if (draggingLabel) {
            // Finish label dragging
            draggingLabel = false;
            labelDragArrow = null;
            labelDragStart = null;
            labelInitialOffset = null;
            notifyChange();  // Mark as changed so label offset is saved
        } else if (draggingGroup) {
            draggingGroup = false;
            groupDragStart = null;
            groupInitialPositions = null;
            groupInitialWaypoints = null;
            groupInitialTextPositions = null;
            draggedElement = null;
        } else if (connectMode && connectionSource != null) {
            // Complete connection
            for (ProcessElement element : elements) {
                if (element.contains(canvasX, canvasY) && element != connectionSource) {
                    // Validate: T_in transitions can ONLY connect to Places
                    if (connectionSource.getType() == ProcessElement.Type.TRANSITION) {
                        String label = connectionSource.getLabel();
                        boolean isTIn = label != null && label.startsWith("T_in_");
                        
                        if (isTIn && element.getType() != ProcessElement.Type.PLACE) {
                            JOptionPane.showMessageDialog(this,
                                "Cannot connect T_in transition '" + connectionSource.getLabel() + "' to '" + element.getLabel() + "'.\n" +
                                "T_in transitions can ONLY connect to Places.\n" +
                                "Routing decisions (to Terminate, other transitions, etc.) must come from T_out transitions.",
                                "Invalid Connection",
                                JOptionPane.ERROR_MESSAGE);
                            
                            // Cancel connect mode
                            connectMode = false;
                            connectionSource = null;
                            setCursor(Cursor.getDefaultCursor());
                            if (cancelListener != null) {
                                cancelListener.run();
                            }
                            repaint();
                            return;
                        }
                    }
                    
                    // Validate: T_out transitions with TERMINATION cannot have outgoing arrows
                    if (connectionSource.getType() == ProcessElement.Type.TRANSITION) {
                        String label = connectionSource.getLabel();
                        String nodeType = connectionSource.getNodeType();
                        String nodeValue = connectionSource.getNodeValue();
                        
                        boolean isTOut = label != null && label.startsWith("T_out_");
                        boolean isTermination = (nodeType != null && nodeType.toUpperCase().contains("TERMINATE")) ||
                                               (nodeValue != null && nodeValue.toUpperCase().contains("TERMINATE"));
                        
                        if (isTOut && isTermination) {
                            JOptionPane.showMessageDialog(this,
                                "Cannot create outgoing arrow from T_out transition '" + connectionSource.getLabel() + "'.\n" +
                                "TERMINATION transitions should not have outgoing connections.\n" +
                                "This would introduce logical errors in the Petri net.",
                                "Invalid Connection",
                                JOptionPane.ERROR_MESSAGE);
                            
                            // Cancel connect mode
                            connectMode = false;
                            connectionSource = null;
                            setCursor(Cursor.getDefaultCursor());
                            if (cancelListener != null) {
                                cancelListener.run();
                            }
                            repaint();
                            return;
                        }
                    }
                    
                    saveUndoState();  // Save before adding arrow
                    Arrow newArrow = new Arrow(connectionSource, element);
                    arrows.add(newArrow);
                    
                    // Select the newly created arrow so it's visible (red highlight)
                    selectedArrow = newArrow;
                    selectedElements.clear(); // Clear element selection
                    
                    // Auto-cancel connect mode after drawing arrow
                    connectMode = false;
                    setCursor(Cursor.getDefaultCursor());
                    if (cancelListener != null) {
                        cancelListener.run();
                    }
                    break;
                }
            }
            connectionSource = null;
            repaint();
        } else if (rubberBandStart != null && rubberBandRect != null) {
            // Finalize rubber band selection
            if (e.isControlDown()) {
                // Add to existing selection
                for (ProcessElement element : elements) {
                    Point center = element.getCenter();
                    if (rubberBandRect.contains(center)) {
                        selectedElements.add(element);
                    }
                }
                // Also select text elements
                for (TextElement textElement : textElements) {
                    Point textCenter = new Point(textElement.getX(), textElement.getY());
                    if (rubberBandRect.contains(textCenter)) {
                        selectedTextElements.add(textElement);
                    }
                }
            } else {
                // Replace selection
                selectedElements.clear();
                selectedTextElements.clear();
                for (ProcessElement element : elements) {
                    Point center = element.getCenter();
                    if (rubberBandRect.contains(center)) {
                        selectedElements.add(element);
                    }
                }
                // Also select text elements
                for (TextElement textElement : textElements) {
                    Point textCenter = new Point(textElement.getX(), textElement.getY());
                    if (rubberBandRect.contains(textCenter)) {
                        selectedTextElements.add(textElement);
                    }
                }
            }
            
            rubberBandStart = null;
            rubberBandRect = null;
            selectedArrow = null;  // Clear arrow selection
            selectedTextElement = null;  // Clear single text selection
            repaint();
        }
        
        draggedElement = null;
        dragOffset = null;
    }
    
    private void handleDoubleClick(MouseEvent e) {
        // Convert screen coordinates to canvas coordinates
        int canvasX = toCanvasX(e.getX());
        int canvasY = toCanvasY(e.getY());
        
        // Check if double-clicked on a text element
        for (TextElement textElement : textElements) {
            if (textElement.contains(canvasX, canvasY)) {
                showTextEditDialog(textElement);
                return;
            }
        }
        
        // Check if double-clicked on an element
        for (ProcessElement element : elements) {
            if (element.contains(canvasX, canvasY)) {
                showPropertiesDialog(element);
                return;
            }
        }
        
        // Check if double-clicked on a waypoint - delete it
        for (Arrow arrow : arrows) {
            Point wp = arrow.findWaypointAt(canvasX, canvasY);
            if (wp != null) {
                // Double-clicked on waypoint - delete it
                saveUndoState();
                arrow.removeWaypoint(wp);
                repaint();
                return;
            }
        }
        
        // Check if double-clicked near an arrow - add control point
        Arrow closestArrow = findClosestArrow(canvasX, canvasY);
        if (closestArrow != null) {
            // Add control point at click position (snapped to grid)
            saveUndoState();
            closestArrow.addWaypoint(snapToGrid(canvasX), snapToGrid(canvasY));
            selectedArrow = closestArrow;
            notifySelectionChanged(closestArrow);
            repaint();
        }
    }
    
    private void handleRightClick(MouseEvent e) {
        // Convert screen coordinates to canvas coordinates
        int canvasX = toCanvasX(e.getX());
        int canvasY = toCanvasY(e.getY());
        
        // Check if right-clicked on a waypoint
        for (Arrow arrow : arrows) {
            Point wp = arrow.findWaypointAt(canvasX, canvasY);
            if (wp != null) {
                showWaypointMenu(e, arrow, wp);
                return;
            }
        }
        
        // Check if right-clicked on an arrow
        Arrow clickedArrow = findClosestArrow(canvasX, canvasY);
        if (clickedArrow != null) {
            selectedArrow = clickedArrow;
            selectedElements.clear();  // Clear element selection
            showArrowContextMenu(e, clickedArrow, canvasX, canvasY);
            repaint();
            return;
        }
        
        // Check if right-clicked on an element
        for (ProcessElement element : elements) {
            if (element.contains(canvasX, canvasY)) {
                if (!selectedElements.contains(element)) {
                    selectedElements.clear();
                    selectedElements.add(element);
                }
                showElementContextMenu(e);
                repaint();
                return;
            }
        }
    }
    
    private void showWaypointMenu(MouseEvent e, Arrow arrow, Point waypoint) {
        JPopupMenu popup = new JPopupMenu();
        JMenuItem deleteItem = new JMenuItem("Delete Control Point");
        deleteItem.addActionListener(ev -> {
            saveUndoState();  // Save before removing waypoint
            arrow.removeWaypoint(waypoint);
            repaint();
        });
        popup.add(deleteItem);
        popup.show(this, e.getX(), e.getY());  // Keep screen coordinates for popup
    }
    
    private void showArrowContextMenu(MouseEvent e, Arrow arrow, int canvasX, int canvasY) {
        JPopupMenu menu = new JPopupMenu();
        
        JMenuItem addWaypointItem = new JMenuItem("Add Control Point");
        addWaypointItem.addActionListener(ev -> {
            // Add waypoint at the click position - snap to grid
            saveUndoState();  // Save before adding waypoint
            arrow.addWaypoint(snapToGrid(canvasX), snapToGrid(canvasY));
            selectedArrow = arrow;
            repaint();
        });
        menu.add(addWaypointItem);
        
        // Add Reset Label Position if label has an offset
        if (arrow.hasLabelOffset()) {
            JMenuItem resetLabelItem = new JMenuItem("Reset Label Position");
            resetLabelItem.addActionListener(ev -> {
                saveUndoState();
                arrow.clearLabelOffset();
                repaint();
            });
            menu.add(resetLabelItem);
        }
        
        JMenuItem propertiesItem = new JMenuItem("Properties...");
        propertiesItem.addActionListener(ev -> {
            showArrowPropertiesDialog(arrow);
        });
        menu.add(propertiesItem);
        
        menu.addSeparator();
        
        JMenuItem deleteItem = new JMenuItem("Delete");
        deleteItem.addActionListener(ev -> {
            saveUndoState();  // Save before deleting
            arrows.remove(arrow);
            selectedArrow = null;
            repaint();
        });
        menu.add(deleteItem);
        
        menu.show(this, e.getX(), e.getY());
    }
    
    private void showElementContextMenu(MouseEvent e) {
        JPopupMenu menu = new JPopupMenu();
        
        JMenuItem propertiesItem = new JMenuItem("Properties...");
        propertiesItem.addActionListener(ev -> {
            if (selectedElements.size() == 1) {
                showPropertiesDialog(selectedElements.iterator().next());
            }
        });
        menu.add(propertiesItem);
        
        JMenuItem deleteItem = new JMenuItem("Delete");
        deleteItem.addActionListener(ev -> {
            saveUndoState();  // Save before deleting
            for (ProcessElement element : new HashSet<>(selectedElements)) {
                deleteElement(element);
            }
            selectedElements.clear();
            notifySelectionChanged(null);  // Clear attributes panel after delete
            repaint();
        });
        menu.add(deleteItem);
        
        menu.show(this, e.getX(), e.getY());
    }
    
    public void showPropertiesDialog(ProcessElement element) {
        JDialog dialog = new JDialog((Frame) SwingUtilities.getWindowAncestor(this), 
                                     "Element Properties", true);
        dialog.setLayout(new BorderLayout());
        
        JPanel panel = new JPanel(new GridLayout(0, 2, 5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        
        // Label
        panel.add(new JLabel("Label:"));
        JTextField labelField = new JTextField(element.getLabel(), 30);
        labelField.setPreferredSize(new Dimension(300, 25));
        panel.add(labelField);
        
        JTextField serviceField = null;
        JTextField operationField = null;
        JTextField nodeTypeField = null;
        JTextField nodeValueField = null;
        
        if (element.getType() == ProcessElement.Type.PLACE) {
            panel.add(new JLabel("Service:"));
            serviceField = new JTextField(element.getService(), 30);
            serviceField.setPreferredSize(new Dimension(300, 25));
            panel.add(serviceField);
            
            panel.add(new JLabel("Operations:"));
            String operationsStr = String.join(", ", element.getOperations());
            operationField = new JTextField(operationsStr, 30);
            operationField.setPreferredSize(new Dimension(300, 25));
            panel.add(operationField);
        } else {
            panel.add(new JLabel("Node Type:"));
            nodeTypeField = new JTextField(element.getNodeType(), 30);
            nodeTypeField.setPreferredSize(new Dimension(300, 25));
            panel.add(nodeTypeField);
            
            panel.add(new JLabel("Node Value:"));
            nodeValueField = new JTextField(element.getNodeValue(), 30);
            nodeValueField.setPreferredSize(new Dimension(300, 25));
            panel.add(nodeValueField);
        }
        
        dialog.add(panel, BorderLayout.CENTER);
        
        JPanel buttonPanel = new JPanel();
        JButton okButton = new JButton("OK");
        final JTextField finalServiceField = serviceField;
        final JTextField finalOperationField = operationField;
        final JTextField finalNodeTypeField = nodeTypeField;
        final JTextField finalNodeValueField = nodeValueField;
        
        okButton.addActionListener(e -> {
            String newLabel = labelField.getText().trim();
            
            // Validate label
            if (newLabel.isEmpty()) {
                JOptionPane.showMessageDialog(dialog,
                    "Label cannot be empty!",
                    "Invalid Label",
                    JOptionPane.ERROR_MESSAGE);
                return;
            }
            
            if (isLabelDuplicate(newLabel, element)) {
                JOptionPane.showMessageDialog(dialog,
                    "Label '" + newLabel + "' is already used by another element!\nPlease choose a unique label.",
                    "Duplicate Label",
                    JOptionPane.ERROR_MESSAGE);
                return;
            }
            
            // Save undo state before changing
            saveUndoState();
            
            element.setLabel(newLabel);
            if (element.getType() == ProcessElement.Type.PLACE) {
                element.setService(finalServiceField.getText());
                
                // Parse comma-separated operations
                String operationsText = finalOperationField.getText().trim();
                List<String> newOperations = new ArrayList<>();
                if (!operationsText.isEmpty()) {
                    String[] opsArray = operationsText.split(",");
                    for (String op : opsArray) {
                        String trimmed = op.trim();
                        if (!trimmed.isEmpty()) {
                            newOperations.add(trimmed);
                        }
                    }
                }
                element.setOperations(newOperations);
            } else {
                element.setNodeType(finalNodeTypeField.getText());
                element.setNodeValue(finalNodeValueField.getText());
            }
            repaint();
            dialog.dispose();
        });
        buttonPanel.add(okButton);
        
        JButton cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(e -> dialog.dispose());
        buttonPanel.add(cancelButton);
        
        dialog.add(buttonPanel, BorderLayout.SOUTH);
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }
    
 // =========================================================================
 // REPLACEMENT FOR Canvas.java - showArrowPropertiesDialog method (lines 939-983)
 // =========================================================================
 // This is a MINIMAL change - only the Guard Condition field becomes a dropdown.
 // No auto-populate, no extra logic.
 // =========================================================================

     private void showArrowPropertiesDialog(Arrow arrow) {
         JDialog dialog = new JDialog((Frame) SwingUtilities.getWindowAncestor(this), 
                                      "Arrow Properties", true);
         dialog.setLayout(new BorderLayout());
         
         JPanel panel = new JPanel(new GridLayout(4, 2, 5, 5));
         panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
         
         // Label
         panel.add(new JLabel("Label:"));
         JTextField labelField = new JTextField(arrow.getLabel());
         panel.add(labelField);
         
         // Guard Condition - DROPDOWN instead of text field
         panel.add(new JLabel("Guard Condition:"));
         String[] guardOptions = {
             "",
             "DECISION_EQUAL_TO",
             "DECISION_NOT_EQUAL",
             "DECISION_GREATER_THAN",
             "DECISION_LESS_THAN"
         };
         JComboBox<String> conditionCombo = new JComboBox<>(guardOptions);
         conditionCombo.setSelectedItem(arrow.getGuardCondition());
         panel.add(conditionCombo);
         
         // Decision Value
         panel.add(new JLabel("Decision Value:"));
         JTextField decisionValueField = new JTextField(arrow.getDecisionValue());
         panel.add(decisionValueField);
         
         // Endpoint
         panel.add(new JLabel("Endpoint:"));
         JTextField endpointField = new JTextField(arrow.getEndpoint());
         panel.add(endpointField);
         
         dialog.add(panel, BorderLayout.CENTER);
         
         JPanel buttonPanel = new JPanel();
         JButton okButton = new JButton("OK");
         okButton.addActionListener(e -> {
             saveUndoState();
             arrow.setLabel(labelField.getText());
             arrow.setGuardCondition((String) conditionCombo.getSelectedItem());
             arrow.setDecisionValue(decisionValueField.getText());
             arrow.setEndpoint(endpointField.getText());
             notifySelectionChanged(arrow);
             repaint();
             dialog.dispose();
         });
         buttonPanel.add(okButton);
         
         JButton cancelButton = new JButton("Cancel");
         cancelButton.addActionListener(e -> dialog.dispose());
         buttonPanel.add(cancelButton);
         
         dialog.add(buttonPanel, BorderLayout.SOUTH);
         dialog.pack();
         dialog.setLocationRelativeTo(this);
         dialog.setVisible(true);
     }
    
    private void showTextEditDialog(TextElement textElement) {
        JDialog dialog = new JDialog((Frame) SwingUtilities.getWindowAncestor(this), 
                                     "Edit Text", true);
        dialog.setLayout(new BorderLayout());
        
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        
        panel.add(new JLabel("Text:"), BorderLayout.NORTH);
        JTextField textField = new JTextField(textElement.getText(), 30);
        panel.add(textField, BorderLayout.CENTER);
        
        dialog.add(panel, BorderLayout.CENTER);
        
        JPanel buttonPanel = new JPanel();
        JButton okButton = new JButton("OK");
        okButton.addActionListener(e -> {
            saveUndoState();  // Save before changing text
            textElement.setText(textField.getText());
            repaint();
            dialog.dispose();
        });
        buttonPanel.add(okButton);
        
        JButton cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(e -> dialog.dispose());
        buttonPanel.add(cancelButton);
        
        dialog.add(buttonPanel, BorderLayout.SOUTH);
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }
    
    private Arrow findClosestArrow(int x, int y) {
        double minDistance = 15; // Threshold for selection (increased for easier clicking)
        Arrow closest = null;
        
        for (Arrow arrow : arrows) {
            Point sourceCenter = arrow.getSource().getCenter();
            Point targetCenter = arrow.getTarget().getCenter();
            
            // Build path with waypoints
            List<Point> path = new ArrayList<>();
            path.add(sourceCenter);
            path.addAll(arrow.getWaypoints());
            path.add(targetCenter);
            
            // Check distance to each segment
            for (int i = 0; i < path.size() - 1; i++) {
                Point p1 = path.get(i);
                Point p2 = path.get(i + 1);
                double dist = distanceToSegment(x, y, p1.x, p1.y, p2.x, p2.y);
                if (dist < minDistance) {
                    minDistance = dist;
                    closest = arrow;
                }
            }
        }
        
        return closest;
    }
    
    private double distanceToSegment(int px, int py, int x1, int y1, int x2, int y2) {
        double A = px - x1;
        double B = py - y1;
        double C = x2 - x1;
        double D = y2 - y1;
        
        double dot = A * C + B * D;
        double lenSq = C * C + D * D;
        double param = -1;
        
        if (lenSq != 0) param = dot / lenSq;
        
        double xx, yy;
        
        if (param < 0) {
            xx = x1;
            yy = y1;
        } else if (param > 1) {
            xx = x2;
            yy = y2;
        } else {
            xx = x1 + param * C;
            yy = y1 + param * D;
        }
        
        double dx = px - xx;
        double dy = py - yy;
        return Math.sqrt(dx * dx + dy * dy);
    }
    
    private void deleteElement(ProcessElement element) {
        elements.remove(element);
        // Remove all arrows connected to this element
        arrows.removeIf(arrow -> arrow.getSource() == element || arrow.getTarget() == element);
    }
    
    public void clearSelection() {
        selectedElements.clear();
        selectedTextElements.clear();
        selectedArrow = null;
        selectedTextElement = null;
        // Don't notify listener - attributes panel keeps showing last selection
        // Panel only updates when user clicks on a new object
    }
    
    /**
     * Clear selection AND update the attributes panel to show "No selection"
     * Use this only when explicitly wanting to clear the panel (e.g., delete, clear canvas)
     */
    public void clearSelectionAndNotify() {
        selectedElements.clear();
        selectedTextElements.clear();
        selectedArrow = null;
        selectedTextElement = null;
        notifySelectionChanged(null);
    }
    
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        
        // Apply zoom transformation
        g2.scale(zoomScale, zoomScale);
        
        // Calculate visible area in canvas coordinates for grid drawing
        int visibleWidth = (int) (getWidth() / zoomScale) + GRID_SIZE;
        int visibleHeight = (int) (getHeight() / zoomScale) + GRID_SIZE;
        
        // Draw grid lines
        g2.setColor(new Color(173, 216, 230, 100)); // Light blue, semi-transparent
        g2.setStroke(new BasicStroke((float)(0.5f / zoomScale)));  // Keep grid lines thin regardless of zoom
        
        // Vertical lines
        for (int x = 0; x < visibleWidth; x += GRID_SIZE) {
            g2.drawLine(x, 0, x, visibleHeight);
        }
        
        // Horizontal lines
        for (int y = 0; y < visibleHeight; y += GRID_SIZE) {
            g2.drawLine(0, y, visibleWidth, y);
        }
        
        g2.setStroke(new BasicStroke(1));
        
        // Draw arrows first (behind elements)
        for (Arrow arrow : arrows) {
            boolean isSelected = (arrow == selectedArrow);
            boolean connectsSelected = selectedElements.contains(arrow.getSource()) && 
                                       selectedElements.contains(arrow.getTarget()) &&
                                       selectedElements.size() > 1;
            
            arrow.draw(g2, isSelected || connectsSelected);
            
            // Draw red highlight overlay if selected OR if connects selected elements
            if (isSelected || connectsSelected) {
                g2.setColor(new Color(255, 0, 0, 100));
                g2.setStroke(new BasicStroke(4));
                Point p1 = arrow.getSource().getCenter();
                Point p2 = arrow.getTarget().getCenter();
                g2.drawLine(p1.x, p1.y, p2.x, p2.y);
                g2.setStroke(new BasicStroke(1));
            }
        }
        
        // Draw elements
        for (ProcessElement element : elements) {
            boolean isSelected = selectedElements.contains(element);
            element.draw(g2, isSelected);
        }
        
        // Draw warning indicators for incomplete Places
        g2.setStroke(new BasicStroke(2));
        for (ProcessElement element : elements) {
            if (element.getType() == ProcessElement.Type.PLACE) {
                String service = element.getService();
                List<String> operations = element.getOperations();
                
                // Check if Place is incomplete
                boolean missingService = (service == null || service.trim().isEmpty());
                boolean missingOperations = (operations == null || operations.isEmpty());
                
                if (missingService || missingOperations) {
                    // Draw red warning dot in top-right corner of the Place
                    int dotSize = 10;
                    int dotX = element.getX() + element.getWidth() - dotSize + 2;
                    int dotY = element.getY() - 2;
                    
                    // Draw red circle with white border
                    g2.setColor(Color.RED);
                    g2.fillOval(dotX, dotY, dotSize, dotSize);
                    g2.setColor(Color.WHITE);
                    g2.drawOval(dotX, dotY, dotSize, dotSize);
                }
            }
        }
        g2.setStroke(new BasicStroke(1));
        
        // Draw handles AFTER all elements (so they appear on top)
        for (ProcessElement element : elements) {
            if (selectedElements.contains(element) && element.getType() == ProcessElement.Type.TRANSITION) {
                element.drawHandles(g2);
            }
        }
        
        // Draw text elements
        for (TextElement textElement : textElements) {
            boolean isSelected = (textElement == selectedTextElement) || selectedTextElements.contains(textElement);
            textElement.draw(g2, isSelected);
        }
        
        // Draw rubber band selection rectangle
        if (rubberBandRect != null) {
            g2.setColor(new Color(100, 100, 255, 50));  // Semi-transparent blue
            g2.fill(rubberBandRect);
            g2.setColor(new Color(100, 100, 255, 150));  // Darker blue border
            g2.setStroke(new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, new float[]{5.0f}, 0.0f));
            g2.draw(rubberBandRect);
            g2.setStroke(new BasicStroke(1));
        }
        
        // Draw connection line if in connect mode (drag mode)
        if (connectMode && connectionSource != null && mousePos != null) {
            g2.setColor(Color.BLUE);
            g2.setStroke(new BasicStroke(2, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, new float[]{10.0f}, 0.0f));
            Point sourceCenter = connectionSource.getCenter();
            g2.drawLine(sourceCenter.x, sourceCenter.y, mousePos.x, mousePos.y);
            g2.setStroke(new BasicStroke(1));
        }
        
        // Draw click-connect preview with waypoints
        if (clickConnectMode && connectionSource != null) {
            g2.setColor(Color.BLUE);
            g2.setStroke(new BasicStroke(2, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, new float[]{10.0f}, 0.0f));
            
            Point prevPoint = connectionSource.getCenter();
            
            // Draw lines through all waypoints
            for (Point wp : clickConnectWaypoints) {
                g2.drawLine(prevPoint.x, prevPoint.y, wp.x, wp.y);
                prevPoint = wp;
            }
            
            // Draw line to current mouse position
            if (mousePos != null) {
                g2.drawLine(prevPoint.x, prevPoint.y, mousePos.x, mousePos.y);
            }
            
            g2.setStroke(new BasicStroke(1));
            
            // Draw waypoint markers
            g2.setColor(new Color(0, 100, 255));
            for (Point wp : clickConnectWaypoints) {
                g2.fillOval(wp.x - 4, wp.y - 4, 8, 8);
            }
            
            // Draw source highlight
            g2.setColor(new Color(0, 150, 0, 100));
            Point sc = connectionSource.getCenter();
            g2.fillOval(sc.x - 8, sc.y - 8, 16, 16);
        }
        
        // Draw animated tokens on top of everything
        if (!animatedTokenStates.isEmpty()) {
            drawAnimatedTokens(g2);
        }
    }
    
    /**
     * Draw animated tokens at their current positions based on phase.
     * This includes both traveling tokens and buffered tokens at T_in.
     */
    private void drawAnimatedTokens(Graphics2D g2) {
        // First, draw buffered tokens at T_in transitions
        drawBufferedTokensAtTIn(g2);
        
        // Draw accumulated terminated tokens at Terminate nodes
        drawTerminatedTokens(g2);
        
        // Then draw tokens that are traveling or at places
        for (TokenAnimState state : animatedTokenStates.values()) {
            // Skip tokens that aren't visible yet or are buffered
            if (state.phase == null || state.phase == Phase.BUFFERED_AT_TIN) {
                continue;
            }
            
            // Skip terminated tokens - they're drawn by drawTerminatedTokens
            if (state.phase == Phase.CONSUMED && state.terminateNodeId != null) {
                continue;
            }
            
            Color color = tokenColors.getOrDefault(state.tokenId, Color.BLUE);
            Point pos = calculateTokenPosition(state);
            
            if (pos != null) {
                drawToken(g2, pos.x, pos.y, color);
            }
        }
    }
    
    /**
     * Draw buffered tokens stacked inside T_in transitions.
     * Tokens are arranged in a neat stack inside the transition bar.
     */
    private void drawBufferedTokensAtTIn(Graphics2D g2) {
        if (tInBufferStates == null || tInBufferStates.isEmpty()) return;
        
        int tokenSize = 8;   // Smaller tokens to fit inside transition
        int spacing = 2;     // Tight spacing
        
        for (Map.Entry<String, List<BufferedToken>> entry : tInBufferStates.entrySet()) {
            String tInId = entry.getKey();
            List<BufferedToken> buffer = entry.getValue();
            
            if (buffer == null || buffer.isEmpty()) continue;
            
            // Find the T_in transition element
            ProcessElement tIn = findTransitionByLabel(tInId);
            if (tIn == null) continue;
            
            // Get transition bounds
            int tInX = tIn.getX();
            int tInY = tIn.getY();
            int tInWidth = tIn.getWidth();
            int tInHeight = tIn.getHeight();
            Point tInCenter = getElementCenter(tIn);
            
            // Transitions are typically vertical bars (narrow width, taller height)
            boolean isVertical = tInHeight > tInWidth;
            
            // Calculate how many tokens can fit inside
            int maxTokensInside;
            if (isVertical) {
                maxTokensInside = Math.max(1, (tInHeight - 4) / (tokenSize + spacing));
            } else {
                maxTokensInside = Math.max(1, (tInWidth - 4) / (tokenSize + spacing));
            }
            
            // Draw tokens inside the transition
            int visibleCount = Math.min(buffer.size(), maxTokensInside);
            
            for (int i = 0; i < visibleCount; i++) {
                BufferedToken bt = buffer.get(i);
                Color color = getVersionColor(bt.version);
                
                int x, y;
                if (isVertical) {
                    // Stack vertically inside the bar, centered horizontally
                    x = tInCenter.x;
                    // Start from top, stack downward
                    int totalStackHeight = visibleCount * (tokenSize + spacing) - spacing;
                    int startY = tInCenter.y - totalStackHeight / 2 + tokenSize / 2;
                    y = startY + i * (tokenSize + spacing);
                } else {
                    // Stack horizontally inside the bar, centered vertically
                    y = tInCenter.y;
                    int totalStackWidth = visibleCount * (tokenSize + spacing) - spacing;
                    int startX = tInCenter.x - totalStackWidth / 2 + tokenSize / 2;
                    x = startX + i * (tokenSize + spacing);
                }
                
                // Draw the buffered token
                drawBufferedToken(g2, x, y, color, tokenSize);
            }
            
            // Draw overflow count badge if more tokens than can fit
            if (buffer.size() > maxTokensInside) {
                int badgeX, badgeY;
                if (isVertical) {
                    // Badge to the left of transition
                    badgeX = tInX - 24;
                    badgeY = tInCenter.y - 7;
                } else {
                    // Badge above transition
                    badgeX = tInCenter.x - 11;
                    badgeY = tInY - 16;
                }
                g2.setColor(new Color(50, 50, 50, 220));
                g2.fillRoundRect(badgeX, badgeY, 22, 14, 4, 4);
                g2.setColor(Color.WHITE);
                g2.setFont(new Font("SansSerif", Font.BOLD, 9));
                g2.drawString(String.valueOf(buffer.size()), badgeX + 4, badgeY + 11);
            }
        }
    }
    
    /**
     * Draw a single buffered token (compact style for buffer visualization)
     */
    private void drawBufferedToken(Graphics2D g2, int x, int y, Color color, int size) {
        // Subtle glow
        g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 30));
        g2.fillOval(x - size/2 - 2, y - size/2 - 2, size + 4, size + 4);
        
        // Token body
        g2.setColor(color);
        g2.fillOval(x - size/2, y - size/2, size, size);
        
        // Border
        g2.setColor(color.darker());
        g2.setStroke(new BasicStroke(1.0f));
        g2.drawOval(x - size/2, y - size/2, size, size);
        g2.setStroke(new BasicStroke(1));
    }
    
    /**
     * Draw accumulated terminated tokens at Terminate nodes.
     * Shows a count badge indicating how many tokens have terminated.
     */
    private void drawTerminatedTokens(Graphics2D g2) {
        if (terminatedTokenStates == null || terminatedTokenStates.isEmpty()) return;
        
        for (Map.Entry<String, List<TokenAnimState>> entry : terminatedTokenStates.entrySet()) {
            String terminateNodeId = entry.getKey();
            List<TokenAnimState> tokens = entry.getValue();
            
            if (tokens == null || tokens.isEmpty()) continue;
            
            // Find the Terminate node element
            ProcessElement terminateNode = findTerminateNodeByLabel(terminateNodeId);
            if (terminateNode == null) continue;
            
            Point center = getElementCenter(terminateNode);
            int nodeWidth = terminateNode.getWidth();
            int nodeHeight = terminateNode.getHeight();
            
            // Draw count badge showing total terminated tokens
            String countText = String.valueOf(tokens.size());
            Font oldFont = g2.getFont();
            g2.setFont(new Font("SansSerif", Font.BOLD, 11));
            FontMetrics fm = g2.getFontMetrics();
            int textWidth = fm.stringWidth(countText);
            int textHeight = fm.getHeight();
            
            // Position badge below the terminate node
            int badgeX = center.x - textWidth/2 - 3;
            int badgeY = center.y + nodeHeight/2 + 4;
            
            // Badge background
            g2.setColor(new Color(60, 60, 60, 220));
            g2.fillRoundRect(badgeX - 2, badgeY - 1, textWidth + 8, textHeight + 2, 6, 6);
            
            // Badge border
            g2.setColor(Color.GRAY);
            g2.drawRoundRect(badgeX - 2, badgeY - 1, textWidth + 8, textHeight + 2, 6, 6);
            
            // Badge text
            g2.setColor(Color.WHITE);
            g2.drawString(countText, badgeX + 3, badgeY + fm.getAscent());
            
            g2.setFont(oldFont);
        }
    }
    
    /**
     * Get color for a version string
     */
    private Color getVersionColor(String version) {
        if (version == null) return Color.GRAY;
        switch (version) {
            case "v001": return new Color(0, 100, 200);     // Blue
            case "v002": return new Color(200, 50, 50);      // Red
            case "v003": return new Color(50, 150, 50);      // Green
            case "v004": return new Color(150, 100, 200);    // Purple
            case "v005": return new Color(200, 150, 50);     // Orange
            case "v006": return new Color(50, 150, 150);     // Teal
            case "v007": return new Color(150, 50, 150);     // Magenta
            case "v008": return new Color(100, 100, 100);    // Gray
            default: return Color.GRAY;
        }
    }

    /**
     * Calculate token position based on its animation phase
     */
    private Point calculateTokenPosition(TokenAnimState state) {
        if (state == null || state.phase == null) return null;
        
        switch (state.phase) {
            case AT_EVENT_GENERATOR:
                // Token at Event Generator
                ProcessElement eventGen = findEventGeneratorByLabel(state.eventGenId);
                if (eventGen != null) {
                    return getElementCenter(eventGen);
                } else {
                    System.out.println("AT_EVENT_GENERATOR_DEBUG: token=" + state.tokenId + 
                        " eventGenId=" + state.eventGenId + " NOT FOUND");
                }
                break;
                
            case TRAVELING_TO_TIN:
                // Interpolate from Event Generator to T_in - follow arrow path if it has waypoints
                ProcessElement eg = findEventGeneratorByLabel(state.eventGenId);
                ProcessElement tIn = findTransitionByLabel(state.tInId);
                
                // DEBUG: Log if elements not found
                if (eg == null || tIn == null) {
                    System.out.println("TRAVELING_TO_TIN_DEBUG: token=" + state.tokenId + 
                        " eventGenId=" + state.eventGenId + " (found=" + (eg != null) + ")" +
                        " tInId=" + state.tInId + " (found=" + (tIn != null) + ")" +
                        " progress=" + state.progress);
                }
                
                if (eg != null && tIn != null) {
                    // Find the arrow connecting event generator to T_in
                    Arrow egToTinArrow = findArrowBetween(eg, tIn);
                    if (egToTinArrow != null && !egToTinArrow.getWaypoints().isEmpty()) {
                        // Follow the arrow's path including waypoints
                        return interpolateAlongArrowPath(egToTinArrow, state.progress);
                    } else {
                        // Direct line (no waypoints)
                        Point start = getElementCenter(eg);
                        Point end = getElementCenter(tIn);
                        return interpolate(start, end, state.progress);
                    }
                }
                break;
                
            case BUFFERED_AT_TIN:
                // Buffered tokens are drawn separately by drawBufferedTokensAtTIn
                return null;
                
            case TRAVELING_TO_PLACE:
                // Interpolate from T_in to Place
                ProcessElement tInStart = findTransitionByLabel(state.tInId);
                ProcessElement place = findPlaceByName(state.currentPlaceId);
                if (tInStart != null && place != null) {
                    Point start = getElementCenter(tInStart);
                    Point end = getElementCenter(place);
                    return interpolate(start, end, state.progress);
                }
                break;
                
            case AT_PLACE:
                // Token at Place center
                ProcessElement atPlace = findPlaceByName(state.currentPlaceId);
                if (atPlace != null) {
                    return getElementCenter(atPlace);
                }
                break;
                
            case TRAVELING_TO_TOUT:
                // Interpolate from Place to T_out
                // Try using fromElementId/toElementId first (more reliable)
                ProcessElement fromElemTout = findElementByIdOrLabel(state.fromElementId);
                ProcessElement toElemTout = findElementByIdOrLabel(state.toElementId);
                if (fromElemTout != null && toElemTout != null) {
                    Point start = getElementCenter(fromElemTout);
                    Point end = getElementCenter(toElemTout);
                    return interpolate(start, end, state.progress);
                }
                // Fallback to original lookup
                ProcessElement fromPlaceToTout = findPlaceByName(state.currentPlaceId);
                ProcessElement tOutDest = findTransitionByLabel(state.tOutId);
                if (fromPlaceToTout != null && tOutDest != null) {
                    Point start = getElementCenter(fromPlaceToTout);
                    Point end = getElementCenter(tOutDest);
                    return interpolate(start, end, state.progress);
                }
                break;
                
            case TRAVELING_TO_NEXT_TIN:
                // Interpolate from T_out to next T_in - follow arrow path if it has waypoints
                // Try using fromElementId/toElementId first (more reliable)
                ProcessElement fromElemNext = findElementByIdOrLabel(state.fromElementId);
                ProcessElement toElemNext = findElementByIdOrLabel(state.toElementId);
                if (fromElemNext != null && toElemNext != null) {
                    // Try to find arrow for waypoints
                    Arrow arrowFromTo = findArrowBetween(fromElemNext, toElemNext);
                    if (arrowFromTo != null && !arrowFromTo.getWaypoints().isEmpty()) {
                        return interpolateAlongArrowPath(arrowFromTo, state.progress);
                    } else {
                        Point start = getElementCenter(fromElemNext);
                        Point end = getElementCenter(toElemNext);
                        return interpolate(start, end, state.progress);
                    }
                }
                // Fallback to original lookup
                ProcessElement tOutElem = findTransitionByLabel(state.tOutId);
                ProcessElement nextTIn = findTransitionByLabel(state.nextTInId);
                
                // DEBUG: Log if elements not found
                if (tOutElem == null || nextTIn == null) {
                    System.out.println("TRAVEL_DEBUG: token=" + state.tokenId + 
                        " tOutId=" + state.tOutId + " (found=" + (tOutElem != null) + ")" +
                        " nextTInId=" + state.nextTInId + " (found=" + (nextTIn != null) + ")" +
                        " fromElementId=" + state.fromElementId + " toElementId=" + state.toElementId +
                        " progress=" + state.progress);
                }
                
                if (tOutElem != null && nextTIn != null) {
                    // Find the arrow connecting these elements
                    Arrow connectingArrow = findArrowBetween(tOutElem, nextTIn);
                    if (connectingArrow != null && !connectingArrow.getWaypoints().isEmpty()) {
                        // Follow the arrow's path including waypoints
                        return interpolateAlongArrowPath(connectingArrow, state.progress);
                    } else {
                        // Direct line (no waypoints)
                        Point start = getElementCenter(tOutElem);
                        Point end = getElementCenter(nextTIn);
                        return interpolate(start, end, state.progress);
                    }
                }
                break;
                
            case TRAVELING_TO_TERMINATE:
                // Interpolate from T_out to Terminate node
                // Try using fromElementId/toElementId first
                ProcessElement fromElemTerm = findElementByIdOrLabel(state.fromElementId);
                ProcessElement toElemTerm = findElementByIdOrLabel(state.toElementId);
                if (fromElemTerm != null && toElemTerm != null) {
                    Point start = getElementCenter(fromElemTerm);
                    Point end = getElementCenter(toElemTerm);
                    return interpolate(start, end, state.progress);
                }
                // Fallback
                ProcessElement tOutForTerminate = findTransitionByLabel(state.tOutId);
                ProcessElement terminateNode = findTerminateNodeByLabel(state.terminateNodeId);
                if (tOutForTerminate != null && terminateNode != null) {
                    Point start = getElementCenter(tOutForTerminate);
                    Point end = getElementCenter(terminateNode);
                    return interpolate(start, end, state.progress);
                }
                break;
                
            case AT_TERMINATE:
                // Token at Terminate node (briefly visible before disappearing)
                ProcessElement atTerminate = findTerminateNodeByLabel(state.terminateNodeId);
                if (atTerminate != null) {
                    return getElementCenter(atTerminate);
                }
                break;
                
            case CONSUMED:
                // Token consumed - not visible
                return null;
        }
        
        return null;
    }
    
    /**
     * Find an Event Generator element by its label
     */
    public ProcessElement findEventGeneratorByLabel(String label) {
        if (label == null) return null;
        
        for (ProcessElement element : elements) {
            if (element.getType() == ProcessElement.Type.EVENT_GENERATOR) {
                if (label.equals(element.getLabel())) {
                    return element;
                }
            }
        }
        return null;
    }
    
    /**
     * Find a Terminate node element by its label
     */
    public ProcessElement findTerminateNodeByLabel(String label) {
        if (label == null) return null;
        
        for (ProcessElement element : elements) {
            // Check if element is a Terminate node by its label or node_type/node_value
            String nodeType = element.getNodeType();
            String nodeValue = element.getNodeValue();
            boolean isTermination = (nodeType != null && nodeType.toUpperCase().contains("TERMINATE")) ||
                                   (nodeValue != null && nodeValue.toUpperCase().contains("TERMINATE")) ||
                                   (element.getLabel() != null && element.getLabel().toLowerCase().contains("terminate"));
            
            if (isTermination && label.equals(element.getLabel())) {
                return element;
            }
        }
        return null;
    }
    
    /**
     * Interpolate between two points
     */
    private Point interpolate(Point start, Point end, double t) {
        t = Math.max(0, Math.min(1, t)); // Clamp to 0-1
        int x = (int)(start.x + (end.x - start.x) * t);
        int y = (int)(start.y + (end.y - start.y) * t);
        return new Point(x, y);
    }
    
    /**
     * Find an arrow connecting source element to target element
     */
    private Arrow findArrowBetween(ProcessElement source, ProcessElement target) {
        for (Arrow arrow : arrows) {
            if (arrow.getSource() == source && arrow.getTarget() == target) {
                return arrow;
            }
        }
        return null;
    }
    
    /**
     * Interpolate along an arrow's full path (source -> waypoints -> target)
     * @param arrow The arrow to follow
     * @param progress 0.0 to 1.0 progress along the path
     * @return The interpolated point
     */
    private Point interpolateAlongArrowPath(Arrow arrow, double progress) {
        // Build the full path: source center -> waypoints -> target center
        List<Point> path = new ArrayList<>();
        path.add(arrow.getSource().getCenter());
        path.addAll(arrow.getWaypoints());
        path.add(arrow.getTarget().getCenter());
        
        if (path.size() < 2) {
            return path.isEmpty() ? null : path.get(0);
        }
        
        // Calculate total path length
        double totalLength = 0;
        double[] segmentLengths = new double[path.size() - 1];
        for (int i = 0; i < path.size() - 1; i++) {
            Point p1 = path.get(i);
            Point p2 = path.get(i + 1);
            segmentLengths[i] = Math.sqrt(Math.pow(p2.x - p1.x, 2) + Math.pow(p2.y - p1.y, 2));
            totalLength += segmentLengths[i];
        }
        
        if (totalLength == 0) {
            return path.get(0);
        }
        
        // Find position at progress along total length
        double targetDistance = progress * totalLength;
        double accumulatedDistance = 0;
        
        for (int i = 0; i < segmentLengths.length; i++) {
            if (accumulatedDistance + segmentLengths[i] >= targetDistance) {
                // We're in this segment
                double segmentProgress = segmentLengths[i] > 0 ? 
                    (targetDistance - accumulatedDistance) / segmentLengths[i] : 0;
                return interpolate(path.get(i), path.get(i + 1), segmentProgress);
            }
            accumulatedDistance += segmentLengths[i];
        }
        
        // At the end
        return path.get(path.size() - 1);
    }
    
    /**
     * Draw a single animated token - smaller, no label
     */
    private void drawToken(Graphics2D g2, int x, int y, Color color) {
        int baseSize = 12;  // Smaller token
        
        // Subtle pulse effect
        float pulse = (float)(1.0 + 0.08 * Math.sin(tokenPulsePhase));
        int size = (int)(baseSize * pulse);
        
        // Glow effect
        g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 40));
        g2.fillOval(x - size/2 - 3, y - size/2 - 3, size + 6, size + 6);
        
        // Token body
        g2.setColor(color);
        g2.fillOval(x - size/2, y - size/2, size, size);
        
        // Border
        g2.setColor(color.darker());
        g2.setStroke(new BasicStroke(1.5f));
        g2.drawOval(x - size/2, y - size/2, size, size);
        g2.setStroke(new BasicStroke(1));
    }
    
    public void setSelectedType(ProcessElement.Type type) {
        this.selectedType = type;
        setCursor(type != null ? Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR) : Cursor.getDefaultCursor());
    }
    
    public void setConnectMode(boolean connect) {
        this.connectMode = connect;
        this.clickConnectMode = false;  // Ensure click-connect is off
        setCursor(connect ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
        if (!connect) {
            connectionSource = null;
            clickConnectWaypoints.clear();
        }
    }
    
    /**
     * Set click-to-connect mode: click source, click to add waypoints, click target to complete.
     * Press Escape to cancel.
     */
    public void setClickConnectMode(boolean connect) {
        this.clickConnectMode = connect;
        this.connectMode = false;  // Ensure drag-connect is off
        setCursor(connect ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
        if (!connect) {
            connectionSource = null;
            clickConnectWaypoints.clear();
        }
    }
    
    /**
     * Complete a click-to-connect connection with the given target element.
     * Includes validation and adds waypoints to the arrow.
     */
    private void completeClickConnection(ProcessElement target) {
        // Validate: T_in transitions can ONLY connect to Places
        if (connectionSource.getType() == ProcessElement.Type.TRANSITION) {
            String label = connectionSource.getLabel();
            boolean isTIn = label != null && label.startsWith("T_in_");
            
            if (isTIn && target.getType() != ProcessElement.Type.PLACE) {
                JOptionPane.showMessageDialog(this,
                    "Cannot connect T_in transition '" + connectionSource.getLabel() + "' to '" + target.getLabel() + "'.\n" +
                    "T_in transitions can ONLY connect to Places.\n" +
                    "Routing decisions (to Terminate, other transitions, etc.) must come from T_out transitions.",
                    "Invalid Connection",
                    JOptionPane.ERROR_MESSAGE);
                cancelClickConnect();
                return;
            }
        }
        
        // Validate: T_out transitions with TERMINATION cannot have outgoing arrows
        if (connectionSource.getType() == ProcessElement.Type.TRANSITION) {
            String label = connectionSource.getLabel();
            String nodeType = connectionSource.getNodeType();
            String nodeValue = connectionSource.getNodeValue();
            
            boolean isTOut = label != null && label.startsWith("T_out_");
            boolean isTermination = (nodeType != null && nodeType.toUpperCase().contains("TERMINATE")) ||
                                   (nodeValue != null && nodeValue.toUpperCase().contains("TERMINATE"));
            
            if (isTOut && isTermination) {
                JOptionPane.showMessageDialog(this,
                    "Cannot create outgoing arrow from T_out transition '" + connectionSource.getLabel() + "'.\n" +
                    "TERMINATION transitions should not have outgoing connections.\n" +
                    "This would introduce logical errors in the Petri net.",
                    "Invalid Connection",
                    JOptionPane.ERROR_MESSAGE);
                cancelClickConnect();
                return;
            }
        }
        
        // Create the arrow with waypoints
        saveUndoState();
        Arrow newArrow = new Arrow(connectionSource, target);
        
        // Add all the waypoints
        for (Point wp : clickConnectWaypoints) {
            newArrow.addWaypoint(wp.x, wp.y);
        }
        
        arrows.add(newArrow);
        
        // Select the newly created arrow
        selectedArrow = newArrow;
        selectedElements.clear();
        
        // Reset for next connection (stay in click-connect mode)
        connectionSource = null;
        clickConnectWaypoints.clear();
        
        if (selectionListener != null) {
            selectionListener.onSelectionChanged(newArrow);
        }
        
        repaint();
    }
    
    /**
     * Cancel click-connect mode and reset state
     */
    private void cancelClickConnect() {
        clickConnectMode = false;
        connectionSource = null;
        clickConnectWaypoints.clear();
        setCursor(Cursor.getDefaultCursor());
        if (cancelListener != null) {
            cancelListener.run();
        }
        repaint();
    }
    
    public void setTextMode(boolean text) {
        this.textMode = text;
        setCursor(text ? Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR) : Cursor.getDefaultCursor());
    }
    
    // Public getters for external access
    public List<ProcessElement> getElements() {
        return elements;
    }
    
    public List<Arrow> getArrows() {
        return arrows;
    }
    
    public List<TextElement> getTextElements() {
        return textElements;
    }
    
    /**
     * Set animated token states for visualization
     * @param states Map of tokenId -> TokenAnimState
     * @param colors Map of tokenId -> Color
     */
    public void setAnimatedTokenStates(Map<String, TokenAnimState> states, Map<String, Color> colors) {
        this.animatedTokenStates = states != null ? states : new HashMap<>();
        this.tokenColors = colors != null ? colors : new HashMap<>();
        repaint();
    }
    
    /**
     * Set buffer states at T_in transitions
     * @param bufferStates Map of T_in label -> list of buffered tokens
     */
    public void setTInBufferStates(Map<String, List<BufferedToken>> bufferStates) {
        this.tInBufferStates = bufferStates != null ? bufferStates : new HashMap<>();
        repaint();
    }
    
    /**
     * Set both token states and buffer states together (for synchronized updates)
     */
    public void setAnimationState(
            Map<String, TokenAnimState> states,
            Map<String, Color> colors,
            Map<String, List<BufferedToken>> bufferStates) {
        this.animatedTokenStates = states != null ? states : new HashMap<>();
        this.tokenColors = colors != null ? colors : new HashMap<>();
        this.tInBufferStates = bufferStates != null ? bufferStates : new HashMap<>();
        
        // Accumulate terminated tokens by terminate node
        this.terminatedTokenStates.clear();
        if (states != null) {
            for (TokenAnimState state : states.values()) {
                // Track tokens that have terminated (AT_TERMINATE or CONSUMED with terminateNodeId)
                if (state.terminateNodeId != null && 
                    (state.phase == Phase.AT_TERMINATE || 
                     state.phase == Phase.CONSUMED)) {
                    terminatedTokenStates
                        .computeIfAbsent(state.terminateNodeId, k -> new ArrayList<>())
                        .add(state);
                }
            }
        }
        
        repaint();
    }
    
    /**
     * Set animation state from an AnimationSnapshot (single source of truth).
     * This is the preferred method - use animator.getAnimationSnapshotAt(time) to get the snapshot.
     */
    public void setAnimationSnapshot(AnimationSnapshot snapshot, Map<String, Color> colors) {
        if (snapshot == null) {
            clearAnimatedTokens();
            return;
        }
        setAnimationState(snapshot.tokenStates, colors, snapshot.bufferStates);
    }
    
    /**
     * Old method for compatibility - not used by new animation
     */
    public void setAnimatedTokens(Map<String, String> positions, Map<String, Color> colors) {
        // Convert to new format - just put tokens at places
        this.animatedTokenStates.clear();
        this.tokenColors = colors != null ? colors : new HashMap<>();
        repaint();
    }
    
    /**
     * Clear animated tokens and buffer states
     */
    public void clearAnimatedTokens() {
        this.animatedTokenStates.clear();
        this.tokenColors.clear();
        this.tInBufferStates.clear();
        this.terminatedTokenStates.clear();
        repaint();
    }
    
    /**
     * Set token pulse phase for animation effect
     */
    public void setTokenPulsePhase(float phase) {
        this.tokenPulsePhase = phase;
    }
    
    /**
     * Find a place element by its service name or label
     */
    public ProcessElement findPlaceByName(String name) {
        if (name == null) return null;
        
        for (ProcessElement element : elements) {
            if (element.getType() == ProcessElement.Type.PLACE) {
                String service = element.getService();
                String label = element.getLabel();
                
                // Direct matches
                if (name.equals(service) || name.equals(label)) {
                    return element;
                }
                
                // Try with/without _Place suffix
                if (name.equals(label + "_Place") || (label + "_Place").equals(name)) {
                    return element;
                }
                
                // Service name match
                if (service != null && service.equals(name)) {
                    return element;
                }
                
                // Try stripping _Place suffix from search name
                String nameWithoutSuffix = name.endsWith("_Place") ? 
                    name.substring(0, name.length() - 6) : name;
                if (nameWithoutSuffix.equals(label) || nameWithoutSuffix.equals(service)) {
                    return element;
                }
                
                // Try matching service without _Place suffix
                if (service != null) {
                    String serviceWithoutSuffix = service.endsWith("_Place") ?
                        service.substring(0, service.length() - 6) : service;
                    if (nameWithoutSuffix.equals(serviceWithoutSuffix)) {
                        return element;
                    }
                }
            }
        }
        return null;
    }
    
    /**
     * Find a transition element by its label.
     * Handles service-to-label mapping for T_in/T_out transitions.
     * 
     * When instrumentation generates T_out_P1 (from service P1_Place),
     * but topology labels it T_out_NS_Green (from place label NS_Green),
     * this method resolves the mapping.
     */
    public ProcessElement findTransitionByLabel(String label) {
        if (label == null) return null;
        
        // First try exact match
        for (ProcessElement element : elements) {
            if (element.getType() == ProcessElement.Type.TRANSITION) {
                if (label.equals(element.getLabel())) {
                    return element;
                }
            }
        }
        
        // If not found and it's a T_in_ or T_out_ transition, try service-to-label mapping
        // Instrumentation uses service names (P1_Place → T_out_P1)
        // Topology uses place labels (NS_Green → T_out_NS_Green)
        if (label.startsWith("T_in_") || label.startsWith("T_out_")) {
            String prefix = label.startsWith("T_in_") ? "T_in_" : "T_out_";
            String placePart = label.substring(prefix.length()); // "P1" from "T_out_P1"
            
            // Find the place that has this as its service base name
            // e.g., placePart="P1" matches service="P1_Place"
            for (ProcessElement place : elements) {
                if (place.getType() == ProcessElement.Type.PLACE) {
                    String service = place.getService();
                    if (service != null) {
                        // Check if service matches (P1_Place for P1, or just P1)
                        String serviceBase = service.endsWith("_Place") ? 
                            service.substring(0, service.length() - 6) : service;
                        if (placePart.equals(serviceBase) || placePart.equals(service)) {
                            // Found the place - now look for transition with place's LABEL
                            String placeLabel = place.getLabel();
                            if (placeLabel != null && !placeLabel.equals(placePart)) {
                                // Labels differ - look for T_out_NS_Green instead of T_out_P1
                                String mappedLabel = prefix + placeLabel;
                                for (ProcessElement transition : elements) {
                                    if (transition.getType() == ProcessElement.Type.TRANSITION) {
                                        if (mappedLabel.equals(transition.getLabel())) {
                                            return transition;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        
        return null;
    }
    
    /**
     * Find any element by ID or label - flexible lookup for animation
     * Tries multiple matching strategies to find the element.
     */
    public ProcessElement findElementByIdOrLabel(String idOrLabel) {
        if (idOrLabel == null || idOrLabel.isEmpty()) return null;
        
        for (ProcessElement element : elements) {
            // Exact ID match
            if (idOrLabel.equals(element.getId())) {
                return element;
            }
            // Exact label match
            if (idOrLabel.equals(element.getLabel())) {
                return element;
            }
            // Service name match (for places)
            if (element.getType() == ProcessElement.Type.PLACE) {
                String service = element.getService();
                if (idOrLabel.equals(service)) {
                    return element;
                }
                // Try with/without _Place suffix
                if (service != null && service.endsWith("_Place")) {
                    String serviceBase = service.substring(0, service.length() - 6);
                    if (idOrLabel.equals(serviceBase)) {
                        return element;
                    }
                }
            }
        }
        return null;
    }
    
    /**
     * Get center point of an element
     */
    public Point getElementCenter(ProcessElement element) {
        if (element == null) return null;
        return new Point(
            element.getX() + element.getWidth() / 2,
            element.getY() + element.getHeight() / 2
        );
    }
    
    public List<ProcessElement> getSelectedElements() {
        return new ArrayList<>(selectedElements);
    }
    
    public void startConnection() {
        setConnectMode(true);
    }
    
    public void clearAll() {
        elements.clear();
        arrows.clear();
        selectedElements.clear();
        selectedArrow = null;
        repaint();
    }
    
    public String exportToGraphviz() {
        StringBuilder dot = new StringBuilder();
        dot.append("digraph PetriNet {\n");
        dot.append("    rankdir=LR;\n");
        dot.append("    node [fontname=\"Helvetica\"];\n\n");
        
        // Export places (circles) with new format
        dot.append("    // --- Service Places ---\n");
        for (ProcessElement element : elements) {
            if (element.getType() == ProcessElement.Type.PLACE) {
                dot.append("    ").append(element.getId()).append(" [\n");
                
                // Add attributes
                if (!element.getLabel().isEmpty()) {
                    dot.append("        label=\"").append(element.getLabel()).append("\",\n");
                }
                if (!element.getService().isEmpty()) {
                    dot.append("        service=\"").append(element.getService()).append("\",\n");
                }
                if (!element.getOperations().isEmpty()) {
                    String operationsStr = String.join(", ", element.getOperations());
                    dot.append("        operations=\"").append(operationsStr).append("\",\n");
                }
                
                dot.append("        shape=circle,\n");
                dot.append("        style=filled,\n");
                dot.append("        fillcolor=lightblue\n");
                dot.append("    ];\n");
            }
        }
        
        dot.append("\n    // --- Transitions ---\n");
        for (ProcessElement element : elements) {
            if (element.getType() == ProcessElement.Type.TRANSITION) {
                dot.append("    ").append(element.getId()).append(" [\n");
                
                // Add attributes
                if (!element.getLabel().isEmpty()) {
                    dot.append("        label=\"").append(element.getLabel()).append("\",\n");
                }
                if (!element.getNodeType().isEmpty()) {
                    dot.append("        node_type=\"").append(element.getNodeType()).append("\",\n");
                }
                if (!element.getNodeValue().isEmpty()) {
                    dot.append("        node_value=\"").append(element.getNodeValue()).append("\",\n");
                }
                
                dot.append("        shape=box,\n");
                dot.append("        style=filled,\n");
                dot.append("        fillcolor=lightgray\n");
                dot.append("    ];\n");
            }
        }
        
        dot.append("\n    // --- Arcs ---\n");
        for (Arrow arrow : arrows) {
            dot.append("    ").append(arrow.getSource().getId()).append(" -> ");
            dot.append(arrow.getTarget().getId());
            
            // Build attributes list
            List<String> attributes = new ArrayList<>();
            if (arrow.getLabel() != null && !arrow.getLabel().isEmpty()) {
                attributes.add("label=\"" + arrow.getLabel() + "\"");
            }
            if (arrow.getGuardCondition() != null && !arrow.getGuardCondition().isEmpty()) {
                attributes.add("guard_condition=\"" + arrow.getGuardCondition() + "\"");
            }
            if (arrow.getDecisionValue() != null && !arrow.getDecisionValue().isEmpty()) {
                attributes.add("decision_value=\"" + arrow.getDecisionValue() + "\"");
            }
            
            // Add attributes if any
            if (!attributes.isEmpty()) {
                dot.append(" [");
                dot.append(String.join(", ", attributes));
                dot.append("]");
            }
            
            dot.append(";\n");
        }
        
        dot.append("}\n");
        
        return dot.toString();
    }
    
    public String saveToJSON() {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        
        // Include metadata if present
        if (metadataJSON != null && !metadataJSON.isEmpty()) {
            json.append("  \"metadata\": ").append(metadataJSON).append(",\n");
        }
        
        // Include processType if present
        if (processType != null && !processType.isEmpty()) {
            json.append("   \"processType\":\"").append(escapeJSON(processType)).append("\",\n");
        }
        
        // Include documentation if present
        if (documentationJSON != null && !documentationJSON.isEmpty()) {
            json.append("  \"documentation\": ").append(documentationJSON).append(",\n");
        }
        
        json.append("  \"elements\": [\n");
        
        // Export elements
        for (int i = 0; i < elements.size(); i++) {
            ProcessElement element = elements.get(i);
            json.append("    {\n");
            json.append("      \"id\": \"").append(escapeJSON(element.getId())).append("\",\n");
            json.append("      \"type\": \"").append(element.getType()).append("\",\n");
            json.append("      \"x\": ").append(element.getX()).append(",\n");
            json.append("      \"y\": ").append(element.getY()).append(",\n");
            json.append("      \"width\": ").append(element.getWidth()).append(",\n");
            json.append("      \"height\": ").append(element.getHeight()).append(",\n");
            json.append("      \"label\": \"").append(escapeJSON(element.getLabel())).append("\"");
            
            if (element.getType() == ProcessElement.Type.PLACE) {
                json.append(",\n      \"service\": \"").append(escapeJSON(element.getService())).append("\"");
                
                // Save operations as array with arguments
                json.append(",\n      \"operations\": [");
                List<ServiceOperation> serviceOps = element.getServiceOperations();
                for (int j = 0; j < serviceOps.size(); j++) {
                    ServiceOperation op = serviceOps.get(j);
                    json.append("\n        {\n");
                    json.append("          \"name\": \"").append(escapeJSON(op.getName())).append("\"");
                    
                    // Save arguments if present
                    List<ServiceArgument> args = op.getArguments();
                    if (!args.isEmpty()) {
                        json.append(",\n          \"arguments\": [");
                        for (int k = 0; k < args.size(); k++) {
                            ServiceArgument arg = args.get(k);
                            json.append("\n            {");
                            json.append("\"name\": \"").append(escapeJSON(arg.getName())).append("\", ");
                            json.append("\"type\": \"").append(escapeJSON(arg.getType())).append("\", ");
                            json.append("\"value\": \"").append(escapeJSON(arg.getValue())).append("\", ");
                            json.append("\"required\": ").append(arg.isRequired());
                            json.append("}");
                            if (k < args.size() - 1) json.append(",");
                        }
                        json.append("\n          ]");
                    }
                    
                    json.append("\n        }");
                    if (j < serviceOps.size() - 1) json.append(",");
                }
                json.append("\n      ]");
            } else if (element.getType() == ProcessElement.Type.EVENT_GENERATOR) {
                // EVENT_GENERATOR is simplified - just label, rate, and version
                // The label serves as the identity (e.g., TRIAGE_EVENTGENERATOR)
                // for instrumentation tracking. Service/operations are not needed
                // as the actual token generation is done by external GenericHealthcareTokenGenerator
                json.append(",\n      \"rotation\": ").append(element.getRotationAngle());
                json.append(",\n      \"generator_rate\": \"").append(escapeJSON(element.getGeneratorRate())).append("\"");
                json.append(",\n      \"token_version\": \"").append(escapeJSON(element.getTokenVersion())).append("\"");
                
                // Save fork_behavior if enabled
                if (element.isForkEnabled()) {
                    json.append(",\n      \"fork_behavior\": {");
                    json.append("\n        \"enabled\": true");
                    json.append(",\n        \"child_count\": ").append(element.getForkChildCount());
                    if (element.getForkJoinTarget() != null) {
                        json.append(",\n        \"join_target\": \"").append(escapeJSON(element.getForkJoinTarget())).append("\"");
                    }
                    json.append("\n      }");
                }
            } else {
                json.append(",\n      \"rotation\": ").append(element.getRotationAngle());
                json.append(",\n      \"node_type\": \"").append(escapeJSON(element.getNodeType())).append("\"");
                json.append(",\n      \"node_value\": \"").append(escapeJSON(element.getNodeValue())).append("\"");
                json.append(",\n      \"transition_type\": \"").append(escapeJSON(element.getTransitionType())).append("\"");
                // Only save buffer for T_in transitions
                if ("T_in".equals(element.getTransitionType())) {
                    json.append(",\n      \"buffer\": \"").append(escapeJSON(element.getBuffer())).append("\"");
                }
            }
            
            // Save colors if custom (applies to all element types)
            if (element.getFillColor() != null) {
                json.append(",\n      \"fill_color\": \"").append(colorToHex(element.getFillColor())).append("\"");
            }
            if (element.getBorderColor() != null) {
                json.append(",\n      \"border_color\": \"").append(colorToHex(element.getBorderColor())).append("\"");
            }
            
            json.append("\n    }");
            if (i < elements.size() - 1) json.append(",");
            json.append("\n");
        }
        
        json.append("  ],\n");
        json.append("  \"arrows\": [\n");
        
        // Export arrows
        for (int i = 0; i < arrows.size(); i++) {
            Arrow arrow = arrows.get(i);
            json.append("    {\n");
            json.append("      \"source\": \"").append(escapeJSON(arrow.getSource().getId())).append("\",\n");
            json.append("      \"target\": \"").append(escapeJSON(arrow.getTarget().getId())).append("\",\n");
            json.append("      \"label\": \"").append(escapeJSON(arrow.getLabel())).append("\",\n");
            json.append("      \"guardCondition\": \"").append(escapeJSON(arrow.getGuardCondition())).append("\",\n");
            json.append("      \"decision_value\": \"").append(escapeJSON(arrow.getDecisionValue())).append("\",\n");
            json.append("      \"endpoint\": \"").append(escapeJSON(arrow.getEndpoint())).append("\"");
            
            // Add waypoints if any
            if (!arrow.getWaypoints().isEmpty()) {
                json.append(",\n      \"waypoints\": [\n");
                List<Point> waypoints = arrow.getWaypoints();
                for (int j = 0; j < waypoints.size(); j++) {
                    Point wp = waypoints.get(j);
                    json.append("        {\"x\": ").append(wp.x).append(", \"y\": ").append(wp.y).append("}");
                    if (j < waypoints.size() - 1) json.append(",");
                    json.append("\n");
                }
                json.append("      ]");
            }
            
            // Add label offset if any
            Point labelOffset = arrow.getLabelOffset();
            if (labelOffset != null && (labelOffset.x != 0 || labelOffset.y != 0)) {
                json.append(",\n      \"labelOffsetX\": ").append(labelOffset.x);
                json.append(",\n      \"labelOffsetY\": ").append(labelOffset.y);
            }
            
            // Add connection type and availability for network connections
            if (arrow.isNetworkConnection()) {
                json.append(",\n      \"connection_type\": \"NETWORK\"");
                json.append(",\n      \"availability\": ").append(arrow.getAvailability());
            }
            
            json.append("\n    }");
            if (i < arrows.size() - 1) json.append(",");
            json.append("\n");
        }
        
        json.append("  ],\n");
        json.append("  \"textElements\": [\n");
        
        // Export text elements
        for (int i = 0; i < textElements.size(); i++) {
            TextElement textElement = textElements.get(i);
            json.append("    {\n");
            json.append("      \"x\": ").append(textElement.getX()).append(",\n");
            json.append("      \"y\": ").append(textElement.getY()).append(",\n");
            json.append("      \"text\": \"").append(escapeJSON(textElement.getText())).append("\",\n");
            json.append("      \"fontSize\": ").append(textElement.getFont().getSize()).append(",\n");
            json.append("      \"fontStyle\": ").append(textElement.getFont().getStyle()).append(",\n");
            json.append("      \"color\": ").append(textElement.getColor().getRGB()).append("\n");
            json.append("    }");
            if (i < textElements.size() - 1) json.append(",");
            json.append("\n");
        }
        
        json.append("  ]\n");
        json.append("}\n");
        
        return json.toString();
    }
    
    public void loadFromJSON(String json) throws Exception {
        clear();
        
        // Extract and store metadata section if present
        if (json.contains("\"metadata\"")) {
            metadataJSON = extractSectionAsJSON(json, "\"metadata\"");
        } else {
            metadataJSON = null;
        }
        
        // Extract and store processType if present
        if (json.contains("\"processType\"")) {
            processType = extractValue(json, "processType");
        } else {
            processType = null;
        }
        
        // Extract and store documentation section if present
        if (json.contains("\"documentation\"")) {
            documentationJSON = extractSectionAsJSON(json, "\"documentation\"");
        } else {
            documentationJSON = null;
        }
        
        // Simple JSON parser for our specific format
        Map<String, ProcessElement> idMap = new HashMap<>();
        
        // Parse elements
        String elementsSection = extractSection(json, "\"elements\"");
        String[] elementBlocks = splitJSONObjects(elementsSection);
        
        for (String block : elementBlocks) {
            if (block.trim().isEmpty()) continue;
            
            String typeStr = extractValue(block, "type");
            ProcessElement.Type type;
            if ("PLACE".equals(typeStr)) {
                type = ProcessElement.Type.PLACE;
            } else if ("EVENT_GENERATOR".equals(typeStr)) {
                type = ProcessElement.Type.EVENT_GENERATOR;
            } else {
                type = ProcessElement.Type.TRANSITION;
            }
            
            int x = Integer.parseInt(extractValue(block, "x"));
            int y = Integer.parseInt(extractValue(block, "y"));
            
            ProcessElement element = new ProcessElement(type, x, y);
            
            // Load width and height if present
            String widthStr = extractValue(block, "width");
            String heightStr = extractValue(block, "height");
            if (widthStr != null && heightStr != null) {
                int width = Integer.parseInt(widthStr);
                int height = Integer.parseInt(heightStr);
                
                element.setWidth(width);
                element.setHeight(height);
            }
            
            // Load rotation for transitions and event generators
            if (type == ProcessElement.Type.TRANSITION || type == ProcessElement.Type.EVENT_GENERATOR) {
                String rotationStr = extractValue(block, "rotation");
                if (rotationStr != null) {
                    element.setRotationAngle(Double.parseDouble(rotationStr));
                }
            }
            
            String id = extractValue(block, "id");
            String label = extractValue(block, "label");
            
            element.setLabel(label);
            
            if (type == ProcessElement.Type.PLACE) {
                String service = extractValue(block, "service");
                if (service != null) element.setService(service);
                
                // Try to load operations - handle both new format (with arguments) and old format (string array)
                try {
                    String operationsSection = extractArraySection(block, "operations");
                    if (operationsSection != null && !operationsSection.trim().isEmpty()) {
                        // Check if this is the new format (contains objects) or old format (contains strings)
                        if (operationsSection.contains("{")) {
                            // New format: array of operation objects with arguments
                            String[] operationBlocks = splitJSONObjects(operationsSection);
                            for (String opBlock : operationBlocks) {
                                if (opBlock.trim().isEmpty()) continue;
                                
                                String opName = extractValue(opBlock, "name");
                                if (opName != null && !opName.trim().isEmpty()) {
                                    ServiceOperation serviceOp = new ServiceOperation(opName.trim());
                                    
                                    // Try to load arguments
                                    String argsSection = extractArraySection(opBlock, "arguments");
                                    if (argsSection != null && !argsSection.trim().isEmpty()) {
                                        String[] argBlocks = splitJSONObjects(argsSection);
                                        for (String argBlock : argBlocks) {
                                            if (argBlock.trim().isEmpty()) continue;
                                            
                                            String argName = extractValue(argBlock, "name");
                                            String argType = extractValue(argBlock, "type");
                                            String argValue = extractValue(argBlock, "value");
                                            String argRequired = extractValue(argBlock, "required");
                                            
                                            if (argName != null && !argName.trim().isEmpty()) {
                                                ServiceArgument arg = new ServiceArgument(
                                                    argName.trim(),
                                                    argType != null ? argType : "String",
                                                    argValue != null ? argValue : "",
                                                    "true".equalsIgnoreCase(argRequired)
                                                );
                                                serviceOp.addArgument(arg);
                                            }
                                        }
                                    }
                                    
                                    element.addServiceOperation(serviceOp);
                                }
                            }
                        } else {
                            // Old format: array of operation name strings
                            String[] operationsArray = splitArrayValues(operationsSection);
                            for (String op : operationsArray) {
                                op = op.trim();
                                if (!op.isEmpty()) {
                                    element.addOperation(op);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    // Operations array not found or error parsing, try old format
                    System.out.println("Error parsing operations: " + e.getMessage());
                }
                
                // Backward compatibility: try loading old "operation" field (single operation)
                if (element.getServiceOperations().isEmpty()) {
                    String operation = extractValue(block, "operation");
                    if (operation != null && !operation.trim().isEmpty()) {
                        element.addOperation(operation);
                    }
                }
            } else if (type == ProcessElement.Type.EVENT_GENERATOR) {
                // Load Event Generator specific properties
                // EVENT_GENERATOR is simplified - just rate and version
                // Service/operations not needed as token generation is external
                String generatorRate = extractValue(block, "generator_rate");
                String tokenVersion = extractValue(block, "token_version");
                
                if (generatorRate != null && !generatorRate.isEmpty()) {
                    element.setGeneratorRate(generatorRate);
                }
                if (tokenVersion != null && !tokenVersion.isEmpty()) {
                    element.setTokenVersion(tokenVersion);
                }
                
                // Parse fork_behavior if present
                if (block.contains("\"fork_behavior\"")) {
                    String forkSection = extractSectionAsJSON(block, "\"fork_behavior\"");
                    if (forkSection != null) {
                        String enabledStr = extractValue(forkSection, "enabled");
                        String childCountStr = extractValue(forkSection, "child_count");
                        String joinTarget = extractValue(forkSection, "join_target");
                        
                        boolean enabled = "true".equalsIgnoreCase(enabledStr);
                        int childCount = 0;
                        if (childCountStr != null) {
                            try {
                                childCount = Integer.parseInt(childCountStr);
                            } catch (NumberFormatException e) {
                                // ignore
                            }
                        }
                        
                        element.setForkEnabled(enabled);
                        element.setForkChildCount(childCount);
                        element.setForkJoinTarget(joinTarget);
                    }
                }
                
                // Note: service and operations are ignored for EVENT_GENERATOR
                // as token generation is handled by external GenericHealthcareTokenGenerator
            } else {
                String nodeType = extractValue(block, "node_type");
                String nodeValue = extractValue(block, "node_value");
                String transitionType = extractValue(block, "transition_type");
                
                // Set defaults if empty or null
                if (nodeType == null || nodeType.trim().isEmpty()) {
                    nodeType = "EdgeNode";
                }
                if (nodeValue == null || nodeValue.trim().isEmpty()) {
                    nodeValue = "EDGE_NODE";
                }
                if (transitionType == null || transitionType.trim().isEmpty()) {
                    // Infer from label if not specified
                    transitionType = element.inferTransitionTypeFromLabel();
                }
                
                element.setNodeType(nodeType);
                element.setNodeValue(nodeValue);
                element.setTransitionType(transitionType);
                
                // Only load buffer for T_in transitions
                if ("T_in".equals(transitionType)) {
                    String buffer = extractValue(block, "buffer");
                    if (buffer == null || buffer.trim().isEmpty()) {
                        buffer = "10";
                    }
                    element.setBuffer(buffer);
                }
            }
            
            // Load colors if present (applies to all element types)
            String fillColorStr = extractValue(block, "fill_color");
            if (fillColorStr != null && !fillColorStr.isEmpty()) {
                element.setFillColor(hexToColor(fillColorStr));
            }
            String borderColorStr = extractValue(block, "border_color");
            if (borderColorStr != null && !borderColorStr.isEmpty()) {
                element.setBorderColor(hexToColor(borderColorStr));
            }
            
            elements.add(element);
            idMap.put(id, element);
        }
        
        // Parse arrows
        String arrowsSection = extractSection(json, "\"arrows\"");
        String[] arrowBlocks = splitJSONObjects(arrowsSection);
        
        for (String block : arrowBlocks) {
            if (block.trim().isEmpty()) continue;
            
            String sourceId = extractValue(block, "source");
            String targetId = extractValue(block, "target");
            String label = extractValue(block, "label");
            // Try new key first, fall back to old key for backward compatibility
            String guardCondition = extractValue(block, "guardCondition");
            if (guardCondition == null) {
                guardCondition = extractValue(block, "condition");
            }
            String decisionValue = extractValue(block, "decision_value");
            
            ProcessElement source = idMap.get(sourceId);
            ProcessElement target = idMap.get(targetId);
            
            if (source != null && target != null) {
                Arrow arrow = new Arrow(source, target);
                if (label != null) arrow.setLabel(label);
                if (guardCondition != null) arrow.setGuardCondition(guardCondition);
                if (decisionValue != null) arrow.setDecisionValue(decisionValue);
                
                // Load endpoint if present
                String endpoint = extractValue(block, "endpoint");
                if (endpoint != null) arrow.setEndpoint(endpoint);
                
                // Load waypoints if present
                String waypointsSection = extractSection(block, "\"waypoints\"");
                if (!waypointsSection.isEmpty()) {
                    String[] waypointBlocks = splitJSONObjects(waypointsSection);
                    for (String wpBlock : waypointBlocks) {
                        if (wpBlock.trim().isEmpty()) continue;
                        int wpX = Integer.parseInt(extractValue(wpBlock, "x"));
                        int wpY = Integer.parseInt(extractValue(wpBlock, "y"));
                        arrow.getWaypoints().add(new Point(wpX, wpY));
                    }
                }
                
                // Load label offset if present
                String labelOffsetXStr = extractValue(block, "labelOffsetX");
                String labelOffsetYStr = extractValue(block, "labelOffsetY");
                if (labelOffsetXStr != null && labelOffsetYStr != null) {
                    int offsetX = Integer.parseInt(labelOffsetXStr);
                    int offsetY = Integer.parseInt(labelOffsetYStr);
                    arrow.setLabelOffset(offsetX, offsetY);
                }
                
                // Load connection type and availability
                String connectionType = extractValue(block, "connection_type");
                if ("NETWORK".equals(connectionType)) {
                    arrow.setNetworkConnection(true);
                    String availabilityStr = extractValue(block, "availability");
                    if (availabilityStr != null) {
                        try {
                            arrow.setAvailability(Double.parseDouble(availabilityStr));
                        } catch (NumberFormatException e) {
                            arrow.setAvailability(1.0);
                        }
                    }
                }
                
                arrows.add(arrow);
            }
        }
        
        // Auto-cleanup: Remove invalid self-loops on places
        arrows.removeIf(arrow -> {
            if (arrow.getSource() == arrow.getTarget() && 
                arrow.getSource().getType() == ProcessElement.Type.PLACE) {
                System.out.println("Removed invalid self-loop on Place: " + arrow.getSource().getLabel());
                return true;
            }
            return false;
        });
        
        // Parse text elements if present
        try {
            String textSection = extractSection(json, "\"textElements\"");
            if (!textSection.isEmpty()) {
                String[] textBlocks = splitJSONObjects(textSection);
                
                for (String block : textBlocks) {
                    if (block.trim().isEmpty()) continue;
                    
                    int x = Integer.parseInt(extractValue(block, "x"));
                    int y = Integer.parseInt(extractValue(block, "y"));
                    String text = extractValue(block, "text");
                    
                    TextElement textElement = new TextElement(x, y, text);
                    
                    // Load font size and style if present
                    String fontSizeStr = extractValue(block, "fontSize");
                    String fontStyleStr = extractValue(block, "fontStyle");
                    if (fontSizeStr != null) {
                        textElement.setFontSize(Integer.parseInt(fontSizeStr));
                    }
                    if (fontStyleStr != null) {
                        textElement.setFontStyle(Integer.parseInt(fontStyleStr));
                    }
                    
                    // Load color if present
                    String colorStr = extractValue(block, "color");
                    if (colorStr != null) {
                        textElement.setColor(new Color(Integer.parseInt(colorStr)));
                    }
                    
                    textElements.add(textElement);
                }
            }
        } catch (Exception e) {
            // Text elements section not found or error parsing - that's okay, continue
            System.out.println("No text elements found or error loading text elements: " + e.getMessage());
        }
        
        // Update canvas size based on loaded elements to restore scroll bars
        updateCanvasSize();
        
        repaint();
    }
    
    /**
     * Update canvas preferred size based on element positions and zoom level
     */
    private void updateCanvasSize() {
        int baseWidth = 800;
        int baseHeight = 600;
        
        if (!elements.isEmpty()) {
            for (ProcessElement element : elements) {
                int right = element.getX() + element.getWidth() + 100; // Add margin
                int bottom = element.getY() + element.getHeight() + 100;
                baseWidth = Math.max(baseWidth, right);
                baseHeight = Math.max(baseHeight, bottom);
            }
        }
        
        // Apply zoom to the preferred size
        int scaledWidth = (int) (baseWidth * zoomScale);
        int scaledHeight = (int) (baseHeight * zoomScale);
        
        setPreferredSize(new Dimension(scaledWidth, scaledHeight));
        revalidate();
    }
    
    private String escapeJSON(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }
    
    /**
     * Convert a Color to hex string (e.g., "#FF5500")
     */
    private String colorToHex(Color c) {
        if (c == null) return "";
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }
    
    /**
     * Convert a hex string (e.g., "#FF5500") to Color
     */
    private Color hexToColor(String hex) {
        if (hex == null || hex.isEmpty()) return null;
        try {
            if (hex.startsWith("#")) {
                hex = hex.substring(1);
            }
            int r = Integer.parseInt(hex.substring(0, 2), 16);
            int g = Integer.parseInt(hex.substring(2, 4), 16);
            int b = Integer.parseInt(hex.substring(4, 6), 16);
            return new Color(r, g, b);
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * Extract a JSON section (object) as a complete JSON string for preservation
     */
    private String extractSectionAsJSON(String json, String key) {
        int start = json.indexOf(key);
        if (start == -1) return null;
        
        // Find the opening brace after the key
        start = json.indexOf("{", start);
        if (start == -1) return null;
        
        // Count braces to find matching close
        int braceCount = 0;
        int end = start;
        for (int i = start; i < json.length(); i++) {
            if (json.charAt(i) == '{') {
                braceCount++;
            } else if (json.charAt(i) == '}') {
                braceCount--;
                if (braceCount == 0) {
                    end = i;
                    break;
                }
            }
        }
        
        return json.substring(start, end + 1);
    }
    
    private String extractSection(String json, String key) {
        int start = json.indexOf(key + ": [");
        if (start == -1) return "";
        start = json.indexOf("[", start);
        
        // Count brackets to handle nested arrays properly
        int bracketCount = 0;
        int end = start;
        for (int i = start; i < json.length(); i++) {
            if (json.charAt(i) == '[') {
                bracketCount++;
            } else if (json.charAt(i) == ']') {
                bracketCount--;
                if (bracketCount == 0) {
                    end = i;
                    break;
                }
            }
        }
        
        return json.substring(start + 1, end);
    }
    
    private String[] splitJSONObjects(String section) {
        List<String> objects = new ArrayList<>();
        int braceCount = 0;
        int start = 0;
        
        for (int i = 0; i < section.length(); i++) {
            char c = section.charAt(i);
            if (c == '{') {
                if (braceCount == 0) start = i;
                braceCount++;
            } else if (c == '}') {
                braceCount--;
                if (braceCount == 0) {
                    objects.add(section.substring(start, i + 1));
                }
            }
        }
        
        return objects.toArray(new String[0]);
    }
    
    private String extractValue(String block, String key) {
        String pattern = "\"" + key + "\":";
        int start = block.indexOf(pattern);
        if (start == -1) return null;
        
        start = block.indexOf(":", start) + 1;
        while (start < block.length() && Character.isWhitespace(block.charAt(start))) start++;
        
        if (block.charAt(start) == '"') {
            // String value
            start++;
            int end = start;
            while (end < block.length()) {
                if (block.charAt(end) == '"' && block.charAt(end - 1) != '\\') break;
                end++;
            }
            return block.substring(start, end)
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
        } else if (block.substring(start).startsWith("true")) {
            // Boolean true
            return "true";
        } else if (block.substring(start).startsWith("false")) {
            // Boolean false
            return "false";
        } else if (block.substring(start).startsWith("null")) {
            // Null value
            return null;
        } else {
            // Number value
            int end = start;
            while (end < block.length() && (Character.isDigit(block.charAt(end)) || block.charAt(end) == '-' || block.charAt(end) == '.')) end++;
            return block.substring(start, end).trim();
        }
    }
    
    private String extractArraySection(String block, String key) {
        String pattern = "\"" + key + "\":";
        int start = block.indexOf(pattern);
        if (start == -1) return null;
        
        start = block.indexOf("[", start);
        if (start == -1) return null;
        
        // Count brackets to handle nested arrays
        int bracketCount = 0;
        int end = start;
        for (int i = start; i < block.length(); i++) {
            if (block.charAt(i) == '[') {
                bracketCount++;
            } else if (block.charAt(i) == ']') {
                bracketCount--;
                if (bracketCount == 0) {
                    end = i;
                    break;
                }
            }
        }
        
        return block.substring(start + 1, end);
    }
    
    private String[] splitArrayValues(String arrayContent) {
        List<String> values = new ArrayList<>();
        boolean inQuotes = false;
        int start = 0;
        
        for (int i = 0; i < arrayContent.length(); i++) {
            char c = arrayContent.charAt(i);
            if (c == '"' && (i == 0 || arrayContent.charAt(i - 1) != '\\')) {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                String value = arrayContent.substring(start, i).trim();
                if (value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                // Unescape JSON strings
                value = value.replace("\\n", "\n")
                             .replace("\\r", "\r")
                             .replace("\\t", "\t")
                             .replace("\\\"", "\"")
                             .replace("\\\\", "\\");
                if (!value.isEmpty()) {
                    values.add(value);
                }
                start = i + 1;
            }
        }
        
        // Add last value
        if (start < arrayContent.length()) {
            String value = arrayContent.substring(start).trim();
            if (value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            // Unescape JSON strings
            value = value.replace("\\n", "\n")
                         .replace("\\r", "\r")
                         .replace("\\t", "\t")
                         .replace("\\\"", "\"")
                         .replace("\\\\", "\\");
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        
        return values.toArray(new String[0]);
    }
    
    
    public void clear() {
        elements.clear();
        arrows.clear();
        textElements.clear();
        selectedElements.clear();
        selectedArrow = null;
        selectedTextElement = null;
        metadataJSON = null;
        documentationJSON = null;
        processType = null;
        notifySelectionChanged(null);  // Clear attributes panel
        repaint();
    }
    
    /**
     * Check if a label is already used by another element
     */
    public boolean isLabelDuplicate(String label, ProcessElement excludeElement) {
        for (ProcessElement element : elements) {
            if (element != excludeElement && element.getLabel().equals(label)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Validate the Petri net for duplicate labels/IDs and other issues
     * @return List of validation error messages (empty if valid)
     */
    public List<String> validatePetriNet() {
        List<String> errors = new ArrayList<>();
        Map<String, List<ProcessElement>> labelMap = new HashMap<>();
        
        // Check for duplicate labels/IDs
        for (ProcessElement element : elements) {
            String label = element.getLabel();
            if (label == null || label.trim().isEmpty()) {
                errors.add("Element at (" + element.getX() + ", " + element.getY() + ") has no label");
                continue;
            }
            
            labelMap.computeIfAbsent(label, k -> new ArrayList<>()).add(element);
        }
        
        // Report duplicates
        for (Map.Entry<String, List<ProcessElement>> entry : labelMap.entrySet()) {
            if (entry.getValue().size() > 1) {
                StringBuilder locations = new StringBuilder();
                for (ProcessElement elem : entry.getValue()) {
                    if (locations.length() > 0) locations.append(", ");
                    locations.append("(").append(elem.getX()).append(", ").append(elem.getY()).append(")");
                }
                errors.add("Duplicate label '" + entry.getKey() + "' found at positions: " + locations);
            }
        }
        
        // Check for incomplete Places (missing service or operations)
        List<String> incompletePlaces = new ArrayList<>();
        for (ProcessElement element : elements) {
            if (element.getType() == ProcessElement.Type.PLACE) {
                String service = element.getService();
                List<String> operations = element.getOperations();
                
                boolean missingService = (service == null || service.trim().isEmpty());
                boolean missingOperations = (operations == null || operations.isEmpty());
                
                if (missingService && missingOperations) {
                    incompletePlaces.add("Place '" + element.getLabel() + "' is missing both service name and operations");
                } else if (missingService) {
                    incompletePlaces.add("Place '" + element.getLabel() + "' is missing service name");
                } else if (missingOperations) {
                    incompletePlaces.add("Place '" + element.getLabel() + "' is missing operations (empty list)");
                }
            }
        }
        
        if (!incompletePlaces.isEmpty()) {
            StringBuilder message = new StringBuilder(
                "Incomplete Place definitions found. Each Place must have a service name and at least one operation:\n"
            );
            for (String issue : incompletePlaces) {
                message.append("\n  - ").append(issue);
            }
            errors.add(message.toString());
        }
        
        // Check for disconnected arrows (source or target deleted)
        List<Arrow> invalidArrows = new ArrayList<>();
        for (Arrow arrow : arrows) {
            if (!elements.contains(arrow.getSource()) || !elements.contains(arrow.getTarget())) {
                invalidArrows.add(arrow);
            }
        }
        
        if (!invalidArrows.isEmpty()) {
            errors.add(invalidArrows.size() + " arrow(s) have invalid source or target");
        }
        
        // Check for invalid place-to-place connections
        List<String> placeToPlaceConnections = new ArrayList<>();
        List<String> selfLoopPlaces = new ArrayList<>();
        
        for (Arrow arrow : arrows) {
            if (elements.contains(arrow.getSource()) && elements.contains(arrow.getTarget())) {
                ProcessElement source = arrow.getSource();
                ProcessElement target = arrow.getTarget();
                
                // Places cannot connect to places (invalid in Petri nets)
                if (source.getType() == ProcessElement.Type.PLACE && 
                    target.getType() == ProcessElement.Type.PLACE) {
                    
                    if (source == target) {
                        // Self-loop on a place
                        selfLoopPlaces.add(source.getLabel() + " (self-loop - may be invisible)");
                    } else {
                        placeToPlaceConnections.add(source.getLabel() + " -> " + target.getLabel());
                    }
                }
                // Transitions CAN connect to transitions (valid in Petri nets)
            }
        }
        
        if (!selfLoopPlaces.isEmpty()) {
            StringBuilder message = new StringBuilder("Invalid self-loops on Places (these may be invisible):");
            for (String connection : selfLoopPlaces) {
                message.append("\n  - ").append(connection);
            }
            errors.add(message.toString());
        }
        
        if (!placeToPlaceConnections.isEmpty()) {
            StringBuilder message = new StringBuilder("Places cannot connect to Places. Invalid connections found:");
            for (String connection : placeToPlaceConnections) {
                message.append("\n  - ").append(connection);
            }
            errors.add(message.toString());
        }
        
        // Check for T_out TERMINATION transitions with outgoing arrows
        List<String> terminationWithOutgoing = new ArrayList<>();
        
        for (ProcessElement element : elements) {
            if (element.getType() == ProcessElement.Type.TRANSITION) {
                String label = element.getLabel();
                String nodeType = element.getNodeType();
                String nodeValue = element.getNodeValue();
                
                // Check if this is a T_out transition (by label)
                boolean isTOut = label != null && label.startsWith("T_out_");
                
                // Check if it has TERMINATE in either nodeType or nodeValue
                // (matches both "TERMINATION" and "TerminateNode")
                boolean isTermination = (nodeType != null && nodeType.toUpperCase().contains("TERMINATE")) ||
                                       (nodeValue != null && nodeValue.toUpperCase().contains("TERMINATE"));
                
                // If this is a T_out TERMINATION transition, check for outgoing arrows
                if (isTOut && isTermination) {
                    for (Arrow arrow : arrows) {
                        if (arrow.getSource() == element) {
                            String targetLabel = arrow.getTarget().getLabel();
                            terminationWithOutgoing.add(
                                element.getLabel() + " -> " + targetLabel + 
                                " (T_out TERMINATION transitions should not have outgoing connections)"
                            );
                        }
                    }
                }
            }
        }
        
        if (!terminationWithOutgoing.isEmpty()) {
            StringBuilder message = new StringBuilder(
                "T_out TERMINATION transitions with outgoing arrows found. " +
                "TERMINATION transitions mark the end of a workflow and should not have outgoing connections:"
            );
            for (String issue : terminationWithOutgoing) {
                message.append("\n  - ").append(issue);
            }
            errors.add(message.toString());
        }
        
        // Check for missing endpoints at merge points with multi-operation places
        List<String> missingEndpoints = new ArrayList<>();
        
        for (ProcessElement place : elements) {
            if (place.getType() == ProcessElement.Type.PLACE && place.getOperations().size() > 1) {
                // This place has multiple operations
                // Find transitions that lead to this place (incoming transitions)
                List<ProcessElement> incomingTransitions = new ArrayList<>();
                for (Arrow arrow : arrows) {
                    if (arrow.getTarget() == place && 
                        arrow.getSource().getType() == ProcessElement.Type.TRANSITION) {
                        incomingTransitions.add(arrow.getSource());
                    }
                }
                
                // For each incoming transition, check if it's a merge point
                for (ProcessElement transition : incomingTransitions) {
                    // Count arrows coming into this transition
                    List<Arrow> arrowsToTransition = new ArrayList<>();
                    for (Arrow arrow : arrows) {
                        if (arrow.getTarget() == transition) {
                            arrowsToTransition.add(arrow);
                        }
                    }
                    
                    // If this transition has multiple incoming arrows (merge point)
                    if (arrowsToTransition.size() > 1) {
                        // Each incoming arrow must have an endpoint specified
                        for (Arrow arrow : arrowsToTransition) {
                            String endpoint = arrow.getEndpoint();
                            if (endpoint == null || endpoint.trim().isEmpty()) {
                                String sourceLabel = arrow.getSource().getLabel();
                                String transitionLabel = transition.getLabel();
                                String placeLabel = place.getLabel();
                                
                                missingEndpoints.add(
                                    sourceLabel + " -> " + transitionLabel + " -> " + placeLabel + 
                                    " (place has " + place.getOperations().size() + " operations: " + 
                                    String.join(", ", place.getOperations()) + ")"
                                );
                            }
                        }
                    }
                }
            }
        }
        
        if (!missingEndpoints.isEmpty()) {
            StringBuilder message = new StringBuilder(
                "Missing endpoint specification at merge points. " +
                "When multiple paths merge into a transition leading to a multi-operation place, " +
                "each incoming arrow must specify which operation to invoke:\n"
            );
            for (String issue : missingEndpoints) {
                message.append("\n  - ").append(issue);
            }
            errors.add(message.toString());
        }
        
        // Check for T_in_<name> -> <name> -> T_out_<name> pattern consistency
        List<String> namingMismatches = new ArrayList<>();
        
        for (ProcessElement place : elements) {
            if (place.getType() != ProcessElement.Type.PLACE) continue;
            
            String placeName = place.getLabel();
            
            // Find direct incoming transitions (source is transition, target is this place)
            List<ProcessElement> incomingTransitions = new ArrayList<>();
            for (Arrow arrow : arrows) {
                if (arrow.getTarget() == place && 
                    arrow.getSource().getType() == ProcessElement.Type.TRANSITION) {
                    incomingTransitions.add(arrow.getSource());
                }
            }
            
            // Find direct outgoing transitions (source is this place, target is transition)
            List<ProcessElement> outgoingTransitions = new ArrayList<>();
            for (Arrow arrow : arrows) {
                if (arrow.getSource() == place && 
                    arrow.getTarget().getType() == ProcessElement.Type.TRANSITION) {
                    outgoingTransitions.add(arrow.getTarget());
                }
            }
            
            // Check T_in_ naming pattern
            for (ProcessElement inTransition : incomingTransitions) {
                String inLabel = inTransition.getLabel();
                if (inLabel.startsWith("T_in_")) {
                    String expectedName = inLabel.substring(5); // Remove "T_in_"
                    if (!expectedName.equals(placeName)) {
                        namingMismatches.add(
                            "T_in transition '" + inLabel + "' should connect to Place '" + 
                            expectedName + "' but connects to '" + placeName + "'"
                        );
                    }
                }
            }
            
            // Check T_out_ naming pattern
            for (ProcessElement outTransition : outgoingTransitions) {
                String outLabel = outTransition.getLabel();
                if (outLabel.startsWith("T_out_")) {
                    String expectedName = outLabel.substring(6); // Remove "T_out_"
                    if (!expectedName.equals(placeName)) {
                        namingMismatches.add(
                            "T_out transition '" + outLabel + "' should connect to Place '" + 
                            expectedName + "' but connects to '" + placeName + "'"
                        );
                    }
                }
            }
            
            // NEW: Check if Place has T_in_<name> then it MUST have T_out_<name>
            boolean hasCorrectTIn = false;
            for (ProcessElement inTransition : incomingTransitions) {
                String inLabel = inTransition.getLabel();
                if (inLabel.equals("T_in_" + placeName)) {
                    hasCorrectTIn = true;
                    break;
                }
            }
            
            if (hasCorrectTIn) {
                // This place has T_in_<name>, so it MUST have T_out_<name>
                boolean hasCorrectTOut = false;
                for (ProcessElement outTransition : outgoingTransitions) {
                    String outLabel = outTransition.getLabel();
                    if (outLabel.equals("T_out_" + placeName)) {
                        hasCorrectTOut = true;
                        break;
                    }
                }
                
                if (!hasCorrectTOut) {
                    // Report what it's actually connected to
                    if (outgoingTransitions.isEmpty()) {
                        namingMismatches.add(
                            "Place '" + placeName + "' has 'T_in_" + placeName + 
                            "' but has no outgoing transition (expected 'T_out_" + placeName + "')"
                        );
                    } else {
                        StringBuilder outNames = new StringBuilder();
                        for (int i = 0; i < outgoingTransitions.size(); i++) {
                            if (i > 0) outNames.append(", ");
                            outNames.append("'").append(outgoingTransitions.get(i).getLabel()).append("'");
                        }
                        namingMismatches.add(
                            "Place '" + placeName + "' has 'T_in_" + placeName + 
                            "' but connects to " + outNames + " instead of 'T_out_" + placeName + "'"
                        );
                    }
                }
            }
        }
        
        if (!namingMismatches.isEmpty()) {
            StringBuilder message = new StringBuilder(
                "Naming pattern inconsistencies detected. " +
                "When using T_in_<name> or T_out_<name> transitions, " +
                "the <name> should match the connected Place name:\n"
            );
            for (String issue : namingMismatches) {
                message.append("\n  - ").append(issue);
            }
            errors.add(message.toString());
        }
        
        return errors;
    }
    
    /**
     * Copy selected elements, arrows, and text to clipboard
     */
    public void copySelection() {
        System.out.println("=== COPY SELECTION CALLED ===");
        System.out.println("Selected elements: " + selectedElements.size());
        System.out.println("Selected text: " + (selectedTextElement != null ? 1 : 0));
        
        clipboardElements.clear();
        clipboardArrows.clear();
        clipboardTextElements.clear();
        
        // Copy selected elements (store COPIES, not references!)
        for (ProcessElement element : selectedElements) {
            clipboardElements.add(copyElement(element));
        }
        
        // Copy selected text elements (create copies, not references!)
        if (selectedTextElement != null) {
            TextElement textCopy = new TextElement(selectedTextElement.getX(), 
                                                   selectedTextElement.getY(), 
                                                   selectedTextElement.getText());
            textCopy.setFont(selectedTextElement.getFont());
            textCopy.setColor(selectedTextElement.getColor());
            clipboardTextElements.add(textCopy);
        }
        
        // Copy arrows that connect selected elements (both source and target must be selected)
        // Store arrow DATA (labels + properties), not arrow objects
        clipboardArrows.clear();
        for (Arrow arrow : arrows) {
            if (selectedElements.contains(arrow.getSource()) && 
                selectedElements.contains(arrow.getTarget())) {
                // Create a NEW arrow with clipboard element references
                // Find the clipboard copies of source and target
                ProcessElement clipSource = null;
                ProcessElement clipTarget = null;
                
                for (ProcessElement clipElem : clipboardElements) {
                    if (clipElem.getLabel().equals(arrow.getSource().getLabel())) {
                        clipSource = clipElem;
                    }
                    if (clipElem.getLabel().equals(arrow.getTarget().getLabel())) {
                        clipTarget = clipElem;
                    }
                }
                
                if (clipSource != null && clipTarget != null) {
                    // Create a new arrow referencing clipboard copies
                    Arrow clipArrow = new Arrow(clipSource, clipTarget);
                    clipArrow.setLabel(arrow.getLabel());
                    clipArrow.setGuardCondition(arrow.getGuardCondition());
                    clipArrow.setDecisionValue(arrow.getDecisionValue());
                    clipArrow.setEndpoint(arrow.getEndpoint());
                    
                    // Copy waypoints
                    for (Point wp : arrow.getWaypoints()) {
                        clipArrow.getWaypoints().add(new Point(wp.x, wp.y));
                    }
                    
                    // Copy label offset if present
                    if (arrow.getLabelOffset() != null) {
                        clipArrow.setLabelOffset(new Point(arrow.getLabelOffset().x, 
                                                           arrow.getLabelOffset().y));
                    }
                    
                    clipboardArrows.add(clipArrow);
                }
            }
        }
        
        System.out.println("Copied to clipboard:");
        System.out.println("  Elements: " + clipboardElements.size());
        System.out.println("  Arrows: " + clipboardArrows.size());
        System.out.println("  Text: " + clipboardTextElements.size());
    }
    
    /**
     * Paste elements from clipboard with offset
     */
    public void pasteFromClipboard() {
        System.out.println("=== PASTE FROM CLIPBOARD CALLED ===");
        System.out.println("Clipboard contents:");
        System.out.println("  Elements: " + clipboardElements.size());
        System.out.println("  Arrows: " + clipboardArrows.size());
        System.out.println("  Text: " + clipboardTextElements.size());
        
        if (clipboardElements.isEmpty() && clipboardTextElements.isEmpty()) {
            System.out.println("Nothing to paste!");
            return; // Nothing to paste
        }
        
        saveUndoState(); // Save state before pasting
        
        // Clear current selection
        clearSelection();
        
        // Map clipboard elements to new pasted elements
        Map<ProcessElement, ProcessElement> clipToNew = new HashMap<>();
        
        // Paste elements with offset
        for (ProcessElement clipElement : clipboardElements) {
            ProcessElement newElement = copyElement(clipElement);
            newElement.setPosition(clipElement.getX() + PASTE_OFFSET, 
                                  clipElement.getY() + PASTE_OFFSET);
            
            // Ensure unique labels
            String baseLabel = newElement.getLabel();
            if (baseLabel.endsWith("_copy")) {
                baseLabel = baseLabel.substring(0, baseLabel.length() - 5);
            }
            String uniqueLabel = baseLabel + "_copy";
            int counter = 1;
            while (isLabelDuplicate(uniqueLabel, null)) {
                uniqueLabel = baseLabel + "_copy" + counter;
                counter++;
            }
            newElement.setLabel(uniqueLabel);
            
            elements.add(newElement);
            selectedElements.add(newElement); // Select pasted elements
            
            // Map clipboard element to new pasted element
            clipToNew.put(clipElement, newElement);
        }
        
        // Paste arrows - now arrows reference clipboard elements directly
        for (Arrow clipArrow : clipboardArrows) {
            ProcessElement newSource = clipToNew.get(clipArrow.getSource());
            ProcessElement newTarget = clipToNew.get(clipArrow.getTarget());
            
            if (newSource != null && newTarget != null) {
                Arrow newArrow = new Arrow(newSource, newTarget);
                newArrow.setLabel(clipArrow.getLabel());
                newArrow.setGuardCondition(clipArrow.getGuardCondition());
                newArrow.setDecisionValue(clipArrow.getDecisionValue());
                newArrow.setEndpoint(clipArrow.getEndpoint());
                
                // Copy waypoints with offset
                for (Point wp : clipArrow.getWaypoints()) {
                    newArrow.getWaypoints().add(new Point(wp.x + PASTE_OFFSET, wp.y + PASTE_OFFSET));
                }
                
                // Copy label offset if present
                if (clipArrow.getLabelOffset() != null) {
                    newArrow.setLabelOffset(new Point(clipArrow.getLabelOffset().x, 
                                                      clipArrow.getLabelOffset().y));
                }
                
                arrows.add(newArrow);
            }
        }
        
        // Paste text elements with offset
        for (TextElement clipText : clipboardTextElements) {
            TextElement newText = new TextElement(clipText.getX() + PASTE_OFFSET, 
                                                 clipText.getY() + PASTE_OFFSET, 
                                                 clipText.getText());
            newText.setFont(clipText.getFont());
            newText.setColor(clipText.getColor());
            textElements.add(newText);
            selectedTextElement = newText; // Select last pasted text element
        }
        
        repaint();
        notifyChange();
        
        System.out.println("Pasted " + selectedElements.size() + " elements");
    }
    
    /**
     * Create a deep copy of an element
     */
    private ProcessElement copyElement(ProcessElement original) {
        ProcessElement copy = new ProcessElement(original.getType(), 
                                                    original.getX(), 
                                                    original.getY());
        
        copy.setLabel(original.getLabel());
        copy.setWidth(original.getWidth());
        copy.setHeight(original.getHeight());
        copy.setRotationAngle(original.getRotationAngle());
        
        // Copy Place attributes
        if (original.getType() == ProcessElement.Type.PLACE) {
            copy.setService(original.getService());
            // Deep copy ServiceOperations with their arguments
            for (ServiceOperation op : original.getServiceOperations()) {
                ServiceOperation opCopy = new ServiceOperation(op);
                copy.addServiceOperation(opCopy);
            }
        }
        
        // Copy Transition attributes
        if (original.getType() == ProcessElement.Type.TRANSITION) {
            copy.setNodeType(original.getNodeType());
            copy.setNodeValue(original.getNodeValue());
            copy.setTransitionType(original.getTransitionType());
            copy.setBuffer(original.getBuffer());
        }
        
        return copy;
    }
}