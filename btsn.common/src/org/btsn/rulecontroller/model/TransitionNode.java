package org.btsn.rulecontroller.model;

import java.util.*;

public class TransitionNode {
    public final String nodeId;
    public final String nodeType;
    public final String nodeValue;
    public final Map<String, String> attributes;
    
    public TransitionNode(String nodeId, String nodeType, String nodeValue, Map<String, String> attributes) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId cannot be null");
        this.nodeType = Objects.requireNonNull(nodeType, "nodeType cannot be null");
        this.nodeValue = nodeValue; // Can be null
        this.attributes = Collections.unmodifiableMap(new HashMap<>(attributes != null ? attributes : new HashMap<>()));
    }
    
    @Override
    public String toString() {
        return String.format("TransitionNode{id=%s, type=%s, value=%s}", nodeId, nodeType, nodeValue);
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TransitionNode that = (TransitionNode) o;
        return Objects.equals(nodeId, that.nodeId) &&
               Objects.equals(nodeType, that.nodeType) &&
               Objects.equals(nodeValue, that.nodeValue);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(nodeId, nodeType, nodeValue);
    }
}