package com.editor;

import java.util.Stack;

/**
 * Manages undo/redo functionality for the Petri Net Editor
 */
public class UndoRedoManager {
    private Stack<String> undoStack;
    private Stack<String> redoStack;
    private Canvas canvas;
    private static final int MAX_STACK_SIZE = 50;
    private boolean ignoreNextSave = false;
    
    public UndoRedoManager(Canvas canvas) {
        this.canvas = canvas;
        this.undoStack = new Stack<>();
        this.redoStack = new Stack<>();
    }
    
    /**
     * Save current state to undo stack before making changes
     */
    public void saveState() {
        if (ignoreNextSave) {
            ignoreNextSave = false;
            return;
        }
        
        String currentState = canvas.saveToJSON();
        undoStack.push(currentState);
        
        // Limit stack size
        if (undoStack.size() > MAX_STACK_SIZE) {
            undoStack.remove(0);
        }
        
        // Clear redo stack when new action is performed
        redoStack.clear();
    }
    
    /**
     * Undo last action
     */
    public void undo() {
        if (!canUndo()) return;
        
        try {
            // Save current state to redo stack
            String currentState = canvas.saveToJSON();
            redoStack.push(currentState);
            
            // Restore previous state
            String previousState = undoStack.pop();
            ignoreNextSave = true;  // Don't save this restoration as a new action
            canvas.loadFromJSON(previousState);
        } catch (Exception e) {
            System.err.println("Error during undo: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Redo last undone action
     */
    public void redo() {
        if (!canRedo()) return;
        
        try {
            // Save current state to undo stack
            String currentState = canvas.saveToJSON();
            undoStack.push(currentState);
            
            // Restore next state
            String nextState = redoStack.pop();
            ignoreNextSave = true;  // Don't save this restoration as a new action
            canvas.loadFromJSON(nextState);
        } catch (Exception e) {
            System.err.println("Error during redo: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Check if undo is available
     */
    public boolean canUndo() {
        return !undoStack.isEmpty();
    }
    
    /**
     * Check if redo is available
     */
    public boolean canRedo() {
        return !redoStack.isEmpty();
    }
    
    /**
     * Clear all undo/redo history
     */
    public void clear() {
        undoStack.clear();
        redoStack.clear();
    }
    
    /**
     * Get number of undo actions available
     */
    public int getUndoCount() {
        return undoStack.size();
    }
    
    /**
     * Get number of redo actions available
     */
    public int getRedoCount() {
        return redoStack.size();
    }
}