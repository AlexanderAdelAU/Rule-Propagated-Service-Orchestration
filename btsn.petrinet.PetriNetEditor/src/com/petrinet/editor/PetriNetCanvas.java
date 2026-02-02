package com.petrinet.editor;


import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Canvas panel for drawing Petri net elements with multi-selection support
 */
public class PetriNetCanvas extends JPanel {
    private static final int GRID_SIZE = 20; // Grid spacing in pixels
    
    private List<PetriNetElement> elements;
    private List<Arrow> arrows;
    
    private PetriNetElement.Type selectedType = null;
    private PetriNetElement draggedElement = null;
    private PetriNetElement connectionSource = null;
    
    // Multi-selection support
    private Set<PetriNetElement> selectedElements = new HashSet<>();
    private Arrow selectedArrow = null;  // Track selected arrow
    
    // Rubber band selection
    private Rectangle rubberBandRect = null;
    private Point rubberBandStart = null;
    
    // Group dragging
    private boolean draggingGroup = false;
    private Point groupDragStart = null;
    private List<Point> groupInitialPositions = null;
    private List<List<Point>> groupInitialWaypoints = null;  // Store waypoint positions during group drag
    
    private Point draggedWaypoint = null;  // Track dragged waypoint
    private Arrow draggedWaypointArrow = null;  // Arrow containing dragged waypoint
    private Point dragOffset = null;
    private Point mousePos = null;
    
    // Handle dragging for transitions
    private boolean draggingLeftHandle = false;
    private boolean draggingRightHandle = false;
    private boolean draggingRotationHandle = false;
    private Point lastHandlePos = null;
    
    private boolean connectMode = false;
    
    // Selection listener for attributes panel
    private SelectionListener selectionListener = null;
    
    // Undo/Redo manager
    private UndoRedoManager undoRedoManager = null;
    
    public interface SelectionListener {
        void onSelectionChanged(Object selection);
    }
    
    public void setSelectionListener(SelectionListener listener) {
        this.selectionListener = listener;
    }
    
    public void setUndoRedoManager(UndoRedoManager manager) {
        this.undoRedoManager = manager;
    }
    
    private void saveUndoState() {
        if (undoRedoManager != null) {
            undoRedoManager.saveState();
        }
    }
    
    private void notifySelectionChanged(Object selection) {
        if (selectionListener != null) {
            selectionListener.onSelectionChanged(selection);
        }
    }
    
    public PetriNetCanvas() {
        elements = new ArrayList<>();
        arrows = new ArrayList<>();
        
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
                mousePos = e.getPoint();
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
                        for (PetriNetElement element : new HashSet<>(selectedElements)) {
                            deleteElement(element);
                        }
                        selectedElements.clear();
                        repaint();
                    } else if (selectedArrow != null) {
                        saveUndoState();  // Save before deleting
                        arrows.remove(selectedArrow);
                        selectedArrow = null;
                        repaint();
                    }
                } else if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    // Clear selection on Escape
                    clearSelection();
                    connectMode = false;
                    selectedType = null;
                    connectionSource = null;
                    setCursor(Cursor.getDefaultCursor());
                }
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
        
        if (selectedType != null) {
            // Add new element - snap CENTER to grid
            saveUndoState();  // Save before adding
            int centerX = snapToGrid(e.getX());
            int centerY = snapToGrid(e.getY());
            
            // Calculate top-left from centered position
            PetriNetElement element = new PetriNetElement(selectedType, 0, 0);
            int x = centerX - element.getWidth() / 2;
            int y = centerY - element.getHeight() / 2;
            element.setPosition(x, y);
            
            elements.add(element);
            selectedType = null;
            repaint();
        } else if (connectMode) {
            // Start connection
            for (PetriNetElement element : elements) {
                if (element.contains(e.getX(), e.getY())) {
                    connectionSource = element;
                    break;
                }
            }
        } else {
            // Check if clicking on a handle first (if single element is selected)
            if (selectedElements.size() == 1) {
                PetriNetElement singleSelected = selectedElements.iterator().next();
                if (singleSelected.getType() == PetriNetElement.Type.TRANSITION) {
                    if (singleSelected.isNearHandle(singleSelected.getLeftResizeHandle(), e.getX(), e.getY())) {
                        draggingLeftHandle = true;
                        lastHandlePos = new Point(e.getX(), e.getY());
                        return;
                    } else if (singleSelected.isNearHandle(singleSelected.getRightResizeHandle(), e.getX(), e.getY())) {
                        draggingRightHandle = true;
                        lastHandlePos = new Point(e.getX(), e.getY());
                        return;
                    } else if (singleSelected.isNearHandle(singleSelected.getRotationHandle(), e.getX(), e.getY())) {
                        draggingRotationHandle = true;
                        return;
                    }
                }
            }
            
            // Check if clicking on a waypoint
            for (Arrow arrow : arrows) {
                Point wp = arrow.findWaypointAt(e.getX(), e.getY());
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
            for (PetriNetElement element : selectedElements) {
                if (element.contains(e.getX(), e.getY())) {
                    clickedOnSelection = true;
                    draggedElement = element;
                    
                    // Start group dragging
                    if (!e.isControlDown()) {
                        saveUndoState();  // Save before moving
                        draggingGroup = true;
                        groupDragStart = e.getPoint();
                        groupInitialPositions = new ArrayList<>();
                        for (PetriNetElement el : selectedElements) {
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
                    }
                    return;
                }
            }
            
            // Check if clicking on an element (not already selected)
            PetriNetElement clickedElement = null;
            for (PetriNetElement element : elements) {
                if (element.contains(e.getX(), e.getY())) {
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
                    notifySelectionChanged(clickedElement);
                    
                    // Start group dragging (even for single element)
                    saveUndoState();  // Save before moving
                    draggingGroup = true;
                    groupDragStart = e.getPoint();
                    groupInitialPositions = new ArrayList<>();
                    for (PetriNetElement el : selectedElements) {
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
                }
                repaint();
                return;
            }
            
            // Check if clicked near an arrow
            Arrow closestArrow = findClosestArrow(e.getX(), e.getY());
            if (closestArrow != null) {
                // Check if arrow connects selected elements - if so, drag the group
                if (selectedElements.size() > 1 &&
                    selectedElements.contains(closestArrow.getSource()) &&
                    selectedElements.contains(closestArrow.getTarget())) {
                    // Start group dragging when clicking arrow between selected elements
                    draggingGroup = true;
                    groupDragStart = e.getPoint();
                    groupInitialPositions = new ArrayList<>();
                    for (PetriNetElement el : selectedElements) {
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
                } else {
                    // Just select the arrow
                    selectedArrow = closestArrow;
                    selectedElements.clear();  // Clear element selection only
                    notifySelectionChanged(selectedArrow);
                }
                repaint();
                return;
            }
            
            // Clicked on empty space - start rubber band selection
            if (!e.isControlDown()) {
                clearSelection();
            }
            rubberBandStart = e.getPoint();
            rubberBandRect = new Rectangle(rubberBandStart);
            repaint();
        }
    }
    
    private void handleMouseDragged(MouseEvent e) {
        if (draggingLeftHandle && selectedElements.size() == 1) {
            PetriNetElement element = selectedElements.iterator().next();
            int dx = e.getX() - lastHandlePos.x;
            int dy = e.getY() - lastHandlePos.y;
            element.resizeFromLeft(dx, dy);
            lastHandlePos = new Point(e.getX(), e.getY());
            repaint();
        } else if (draggingRightHandle && selectedElements.size() == 1) {
            PetriNetElement element = selectedElements.iterator().next();
            int dx = e.getX() - lastHandlePos.x;
            int dy = e.getY() - lastHandlePos.y;
            element.resizeFromRight(dx, dy);
            lastHandlePos = new Point(e.getX(), e.getY());
            repaint();
        } else if (draggingRotationHandle && selectedElements.size() == 1) {
            PetriNetElement element = selectedElements.iterator().next();
            element.rotateTowards(e.getX(), e.getY());
            repaint();
        } else if (draggedWaypoint != null) {
            // Drag waypoint - snap to grid
            draggedWaypoint.x = snapToGrid(e.getX());
            draggedWaypoint.y = snapToGrid(e.getY());
            repaint();
        } else if (draggingGroup && groupDragStart != null) {
            // Drag all selected elements together AND their arrow waypoints
            // Calculate raw offset
            int rawDx = e.getX() - groupDragStart.x;
            int rawDy = e.getY() - groupDragStart.y;
            
            // Snap the first element's CENTER position to determine snapped offset
            if (!groupInitialPositions.isEmpty()) {
                PetriNetElement firstElement = selectedElements.iterator().next();
                Point firstInitialPos = groupInitialPositions.get(0);
                
                // Calculate center position
                int centerX = firstInitialPos.x + firstElement.getWidth() / 2 + rawDx;
                int centerY = firstInitialPos.y + firstElement.getHeight() / 2 + rawDy;
                
                // Snap center to grid
                int snappedCenterX = snapToGrid(centerX);
                int snappedCenterY = snapToGrid(centerY);
                
                // Calculate snapped deltas based on center movement
                int dx = snappedCenterX - (firstInitialPos.x + firstElement.getWidth() / 2);
                int dy = snappedCenterY - (firstInitialPos.y + firstElement.getHeight() / 2);
                
                int i = 0;
                for (PetriNetElement element : selectedElements) {
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
            }
            
            repaint();
        } else if (rubberBandStart != null) {
            // Update rubber band rectangle
            int x = Math.min(rubberBandStart.x, e.getX());
            int y = Math.min(rubberBandStart.y, e.getY());
            int width = Math.abs(e.getX() - rubberBandStart.x);
            int height = Math.abs(e.getY() - rubberBandStart.y);
            rubberBandRect = new Rectangle(x, y, width, height);
            repaint();
        }
    }
    
    private void handleMouseReleased(MouseEvent e) {
        if (draggingLeftHandle || draggingRightHandle || draggingRotationHandle) {
            draggingLeftHandle = false;
            draggingRightHandle = false;
            draggingRotationHandle = false;
            lastHandlePos = null;
        } else if (draggedWaypoint != null) {
            draggedWaypoint = null;
            draggedWaypointArrow = null;
        } else if (draggingGroup) {
            draggingGroup = false;
            groupDragStart = null;
            groupInitialPositions = null;
            groupInitialWaypoints = null;
            draggedElement = null;
        } else if (connectMode && connectionSource != null) {
            // Complete connection
            for (PetriNetElement element : elements) {
                if (element.contains(e.getX(), e.getY()) && element != connectionSource) {
                    saveUndoState();  // Save before adding arrow
                    Arrow newArrow = new Arrow(connectionSource, element);
                    arrows.add(newArrow);
                    
                    // Select the newly created arrow so it's visible (red highlight)
                    selectedArrow = newArrow;
                    selectedElements.clear(); // Clear element selection
                    break;
                }
            }
            connectionSource = null;
            repaint();
        } else if (rubberBandStart != null && rubberBandRect != null) {
            // Finalize rubber band selection
            if (e.isControlDown()) {
                // Add to existing selection
                for (PetriNetElement element : elements) {
                    Point center = element.getCenter();
                    if (rubberBandRect.contains(center)) {
                        selectedElements.add(element);
                    }
                }
            } else {
                // Replace selection
                selectedElements.clear();
                for (PetriNetElement element : elements) {
                    Point center = element.getCenter();
                    if (rubberBandRect.contains(center)) {
                        selectedElements.add(element);
                    }
                }
            }
            
            rubberBandStart = null;
            rubberBandRect = null;
            selectedArrow = null;  // Clear arrow selection
            repaint();
        }
        
        draggedElement = null;
        dragOffset = null;
    }
    
    private void handleDoubleClick(MouseEvent e) {
        // Check if double-clicked on an element
        for (PetriNetElement element : elements) {
            if (element.contains(e.getX(), e.getY())) {
                showPropertiesDialog(element);
                return;
            }
        }
        
        // Check if double-clicked on a waypoint - show arrow properties
        for (Arrow arrow : arrows) {
            Point wp = arrow.findWaypointAt(e.getX(), e.getY());
            if (wp != null) {
                // Double-clicked on waypoint - show arrow properties dialog
                showArrowPropertiesDialog(arrow);
                return;
            }
        }
        
        // Check if double-clicked near an arrow - show properties
        Arrow closestArrow = findClosestArrow(e.getX(), e.getY());
        if (closestArrow != null) {
            // Show properties dialog
            showArrowPropertiesDialog(closestArrow);
        }
    }
    
    private void handleRightClick(MouseEvent e) {
        // Check if right-clicked on a waypoint
        for (Arrow arrow : arrows) {
            Point wp = arrow.findWaypointAt(e.getX(), e.getY());
            if (wp != null) {
                showWaypointMenu(e, arrow, wp);
                return;
            }
        }
        
        // Check if right-clicked on an arrow
        Arrow clickedArrow = findClosestArrow(e.getX(), e.getY());
        if (clickedArrow != null) {
            selectedArrow = clickedArrow;
            selectedElements.clear();  // Clear element selection
            showArrowContextMenu(e, clickedArrow);
            repaint();
            return;
        }
        
        // Check if right-clicked on an element
        for (PetriNetElement element : elements) {
            if (element.contains(e.getX(), e.getY())) {
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
        popup.show(this, e.getX(), e.getY());
    }
    
    private void showArrowContextMenu(MouseEvent e, Arrow arrow) {
        JPopupMenu menu = new JPopupMenu();
        
        JMenuItem addWaypointItem = new JMenuItem("Add Control Point");
        addWaypointItem.addActionListener(ev -> {
            // Add waypoint at the click position - snap to grid
            saveUndoState();  // Save before adding waypoint
            arrow.addWaypoint(snapToGrid(e.getX()), snapToGrid(e.getY()));
            selectedArrow = arrow;
            repaint();
        });
        menu.add(addWaypointItem);
        
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
            for (PetriNetElement element : new HashSet<>(selectedElements)) {
                deleteElement(element);
            }
            selectedElements.clear();
            repaint();
        });
        menu.add(deleteItem);
        
        menu.show(this, e.getX(), e.getY());
    }
    
    public void showPropertiesDialog(PetriNetElement element) {
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
        
        if (element.getType() == PetriNetElement.Type.PLACE) {
            panel.add(new JLabel("Service:"));
            serviceField = new JTextField(element.getService(), 30);
            serviceField.setPreferredSize(new Dimension(300, 25));
            panel.add(serviceField);
            
            panel.add(new JLabel("Operation:"));
            operationField = new JTextField(element.getOperation(), 30);
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
            if (element.getType() == PetriNetElement.Type.PLACE) {
                element.setService(finalServiceField.getText());
                element.setOperation(finalOperationField.getText());
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
    
    private void showArrowPropertiesDialog(Arrow arrow) {
        JDialog dialog = new JDialog((Frame) SwingUtilities.getWindowAncestor(this), 
                                     "Arrow Properties", true);
        dialog.setLayout(new BorderLayout());
        
        JPanel panel = new JPanel(new GridLayout(3, 2, 5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        
        // Label
        panel.add(new JLabel("Label:"));
        JTextField labelField = new JTextField(arrow.getLabel());
        panel.add(labelField);
        
        // Condition
        panel.add(new JLabel("Condition:"));
        JTextField conditionField = new JTextField(arrow.getCondition());
        panel.add(conditionField);
        
        // Decision Value
        panel.add(new JLabel("Decision_Value:"));
        JTextField decisionValueField = new JTextField(arrow.getDecisionValue());
        panel.add(decisionValueField);
        
        dialog.add(panel, BorderLayout.CENTER);
        
        JPanel buttonPanel = new JPanel();
        JButton okButton = new JButton("OK");
        okButton.addActionListener(e -> {
            arrow.setLabel(labelField.getText());
            arrow.setCondition(conditionField.getText());
            arrow.setDecisionValue(decisionValueField.getText());
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
    
    private void deleteElement(PetriNetElement element) {
        elements.remove(element);
        // Remove all arrows connected to this element
        arrows.removeIf(arrow -> arrow.getSource() == element || arrow.getTarget() == element);
    }
    
    public void clearSelection() {
        selectedElements.clear();
        selectedArrow = null;
        notifySelectionChanged(null);
    }
    
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        
        // Draw grid lines
        g2.setColor(new Color(173, 216, 230, 100)); // Light blue, semi-transparent
        g2.setStroke(new BasicStroke(0.5f));
        
        // Vertical lines
        for (int x = 0; x < getWidth(); x += GRID_SIZE) {
            g2.drawLine(x, 0, x, getHeight());
        }
        
        // Horizontal lines
        for (int y = 0; y < getHeight(); y += GRID_SIZE) {
            g2.drawLine(0, y, getWidth(), y);
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
        for (PetriNetElement element : elements) {
            boolean isSelected = selectedElements.contains(element);
            element.draw(g2, isSelected);
        }
        
        // Draw handles AFTER all elements (so they appear on top)
        for (PetriNetElement element : elements) {
            if (selectedElements.contains(element) && element.getType() == PetriNetElement.Type.TRANSITION) {
                element.drawHandles(g2);
            }
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
        
        // Draw connection line if in connect mode
        if (connectMode && connectionSource != null && mousePos != null) {
            g2.setColor(Color.BLUE);
            g2.setStroke(new BasicStroke(2, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, new float[]{10.0f}, 0.0f));
            Point sourceCenter = connectionSource.getCenter();
            g2.drawLine(sourceCenter.x, sourceCenter.y, mousePos.x, mousePos.y);
            g2.setStroke(new BasicStroke(1));
        }
    }
    
    public void setSelectedType(PetriNetElement.Type type) {
        this.selectedType = type;
        setCursor(type != null ? Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR) : Cursor.getDefaultCursor());
    }
    
    public void setConnectMode(boolean connect) {
        this.connectMode = connect;
        setCursor(connect ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
        if (!connect) {
            connectionSource = null;
        }
    }
    
    // Public getters for external access
    public List<PetriNetElement> getElements() {
        return elements;
    }
    
    public List<Arrow> getArrows() {
        return arrows;
    }
    
    public List<PetriNetElement> getSelectedElements() {
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
        for (PetriNetElement element : elements) {
            if (element.getType() == PetriNetElement.Type.PLACE) {
                dot.append("    ").append(element.getId()).append(" [\n");
                
                // Add attributes
                if (!element.getLabel().isEmpty()) {
                    dot.append("        label=\"").append(element.getLabel()).append("\",\n");
                }
                if (!element.getService().isEmpty()) {
                    dot.append("        service=\"").append(element.getService()).append("\",\n");
                }
                if (!element.getOperation().isEmpty()) {
                    dot.append("        operation=\"").append(element.getOperation()).append("\",\n");
                }
                
                dot.append("        shape=circle,\n");
                dot.append("        style=filled,\n");
                dot.append("        fillcolor=lightblue\n");
                dot.append("    ];\n");
            }
        }
        
        dot.append("\n    // --- Transitions ---\n");
        for (PetriNetElement element : elements) {
            if (element.getType() == PetriNetElement.Type.TRANSITION) {
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
            if (arrow.getCondition() != null && !arrow.getCondition().isEmpty()) {
                attributes.add("condition=\"" + arrow.getCondition() + "\"");
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
        json.append("  \"elements\": [\n");
        
        // Export elements
        for (int i = 0; i < elements.size(); i++) {
            PetriNetElement element = elements.get(i);
            json.append("    {\n");
            json.append("      \"id\": \"").append(escapeJSON(element.getId())).append("\",\n");
            json.append("      \"type\": \"").append(element.getType()).append("\",\n");
            json.append("      \"x\": ").append(element.getX()).append(",\n");
            json.append("      \"y\": ").append(element.getY()).append(",\n");
            json.append("      \"width\": ").append(element.getWidth()).append(",\n");
            json.append("      \"height\": ").append(element.getHeight()).append(",\n");
            json.append("      \"label\": \"").append(escapeJSON(element.getLabel())).append("\"");
            
            if (element.getType() == PetriNetElement.Type.PLACE) {
                json.append(",\n      \"service\": \"").append(escapeJSON(element.getService())).append("\"");
                json.append(",\n      \"operation\": \"").append(escapeJSON(element.getOperation())).append("\"");
            } else {
                json.append(",\n      \"rotation\": ").append(element.getRotationAngle());
                json.append(",\n      \"node_type\": \"").append(escapeJSON(element.getNodeType())).append("\"");
                json.append(",\n      \"node_value\": \"").append(escapeJSON(element.getNodeValue())).append("\"");
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
            json.append("      \"condition\": \"").append(escapeJSON(arrow.getCondition())).append("\",\n");
            json.append("      \"decision_value\": \"").append(escapeJSON(arrow.getDecisionValue())).append("\"");
            
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
            
            json.append("\n    }");
            if (i < arrows.size() - 1) json.append(",");
            json.append("\n");
        }
        
        json.append("  ]\n");
        json.append("}\n");
        
        return json.toString();
    }
    
    public void loadFromJSON(String json) throws Exception {
        clear();
        
        // Simple JSON parser for our specific format
        java.util.Map<String, PetriNetElement> idMap = new java.util.HashMap<>();
        
        // Parse elements
        String elementsSection = extractSection(json, "\"elements\"");
        String[] elementBlocks = splitJSONObjects(elementsSection);
        
        for (String block : elementBlocks) {
            if (block.trim().isEmpty()) continue;
            
            String typeStr = extractValue(block, "type");
            PetriNetElement.Type type = typeStr.equals("PLACE") ? 
                PetriNetElement.Type.PLACE : PetriNetElement.Type.TRANSITION;
            
            int x = Integer.parseInt(extractValue(block, "x"));
            int y = Integer.parseInt(extractValue(block, "y"));
            
            PetriNetElement element = new PetriNetElement(type, x, y);
            
            // Load width and height if present
            String widthStr = extractValue(block, "width");
            String heightStr = extractValue(block, "height");
            if (widthStr != null && heightStr != null) {
                int width = Integer.parseInt(widthStr);
                int height = Integer.parseInt(heightStr);
                element.setWidth(width);
                element.setHeight(height);
            }
            
            // Load rotation for transitions
            if (type == PetriNetElement.Type.TRANSITION) {
                String rotationStr = extractValue(block, "rotation");
                if (rotationStr != null) {
                    element.setRotationAngle(Double.parseDouble(rotationStr));
                }
            }
            
            String id = extractValue(block, "id");
            String label = extractValue(block, "label");
            
            element.setLabel(label);
            
            if (type == PetriNetElement.Type.PLACE) {
                String service = extractValue(block, "service");
                String operation = extractValue(block, "operation");
                if (service != null) element.setService(service);
                if (operation != null) element.setOperation(operation);
            } else {
                String nodeType = extractValue(block, "node_type");
                String nodeValue = extractValue(block, "node_value");
                if (nodeType != null) element.setNodeType(nodeType);
                if (nodeValue != null) element.setNodeValue(nodeValue);
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
            String condition = extractValue(block, "condition");
            String decisionValue = extractValue(block, "decision_value");
            
            PetriNetElement source = idMap.get(sourceId);
            PetriNetElement target = idMap.get(targetId);
            
            if (source != null && target != null) {
                Arrow arrow = new Arrow(source, target);
                if (label != null) arrow.setLabel(label);
                if (condition != null) arrow.setCondition(condition);
                if (decisionValue != null) arrow.setDecisionValue(decisionValue);
                
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
                
                arrows.add(arrow);
            }
        }
        
        repaint();
    }
    
    private String escapeJSON(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
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
        } else {
            // Number value
            int end = start;
            while (end < block.length() && (Character.isDigit(block.charAt(end)) || block.charAt(end) == '-' || block.charAt(end) == '.')) end++;
            return block.substring(start, end).trim();
        }
    }
    
    
    public void clear() {
        elements.clear();
        arrows.clear();
        selectedElements.clear();
        selectedArrow = null;
        repaint();
    }
    
    /**
     * Check if a label is already used by another element
     */
    public boolean isLabelDuplicate(String label, PetriNetElement excludeElement) {
        for (PetriNetElement element : elements) {
            if (element != excludeElement && element.getLabel().equals(label)) {
                return true;
            }
        }
        return false;
    }
    
}