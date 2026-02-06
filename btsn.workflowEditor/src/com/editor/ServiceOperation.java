package com.editor;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a service operation with its arguments.
 * Each operation has a name and a list of arguments that can be configured.
 */
public class ServiceOperation {
    private String name;
    private List<ServiceArgument> arguments;
    
    /**
     * Default constructor - creates an empty operation
     */
    public ServiceOperation() {
        this.name = "";
        this.arguments = new ArrayList<>();
    }
    
    /**
     * Constructor with name only - creates operation with empty argument list
     */
    public ServiceOperation(String name) {
        this.name = name != null ? name : "";
        this.arguments = new ArrayList<>();
    }
    
    /**
     * Constructor with name and arguments
     */
    public ServiceOperation(String name, List<ServiceArgument> arguments) {
        this.name = name != null ? name : "";
        this.arguments = arguments != null ? new ArrayList<>(arguments) : new ArrayList<>();
    }
    
    /**
     * Copy constructor - creates a deep copy of another ServiceOperation
     */
    public ServiceOperation(ServiceOperation other) {
        this.name = other.name;
        this.arguments = new ArrayList<>();
        for (ServiceArgument arg : other.arguments) {
            this.arguments.add(new ServiceArgument(arg));
        }
    }
    
    // Getters
    public String getName() {
        return name;
    }
    
    public List<ServiceArgument> getArguments() {
        return arguments;
    }
    
    // Setters
    public void setName(String name) {
        this.name = name != null ? name : "";
    }
    
    public void setArguments(List<ServiceArgument> arguments) {
        this.arguments = arguments != null ? new ArrayList<>(arguments) : new ArrayList<>();
    }
    
    /**
     * Add an argument to this operation
     */
    public void addArgument(ServiceArgument argument) {
        if (argument != null) {
            this.arguments.add(argument);
        }
    }
    
    /**
     * Add a new argument with the given name
     */
    public ServiceArgument addArgument(String name) {
        ServiceArgument arg = new ServiceArgument(name);
        this.arguments.add(arg);
        return arg;
    }
    
    /**
     * Add a new argument with name and value
     */
    public ServiceArgument addArgument(String name, String value) {
        ServiceArgument arg = new ServiceArgument(name, value);
        this.arguments.add(arg);
        return arg;
    }
    
    /**
     * Remove an argument from this operation
     */
    public void removeArgument(ServiceArgument argument) {
        this.arguments.remove(argument);
    }
    
    /**
     * Remove an argument by index
     */
    public void removeArgument(int index) {
        if (index >= 0 && index < this.arguments.size()) {
            this.arguments.remove(index);
        }
    }
    
    /**
     * Get an argument by index
     */
    public ServiceArgument getArgument(int index) {
        if (index >= 0 && index < this.arguments.size()) {
            return this.arguments.get(index);
        }
        return null;
    }
    
    /**
     * Get an argument by name (first match)
     */
    public ServiceArgument getArgumentByName(String name) {
        for (ServiceArgument arg : this.arguments) {
            if (arg.getName().equals(name)) {
                return arg;
            }
        }
        return null;
    }
    
    /**
     * Check if this operation has any arguments
     */
    public boolean hasArguments() {
        return !this.arguments.isEmpty();
    }
    
    /**
     * Get the number of arguments
     */
    public int getArgumentCount() {
        return this.arguments.size();
    }
    
    /**
     * Clear all arguments
     */
    public void clearArguments() {
        this.arguments.clear();
    }
    
    @Override
    public String toString() {
        return "ServiceOperation{" +
                "name='" + name + '\'' +
                ", arguments=" + arguments +
                '}';
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        ServiceOperation that = (ServiceOperation) obj;
        return name.equals(that.name) && arguments.equals(that.arguments);
    }
    
    @Override
    public int hashCode() {
        int result = name.hashCode();
        result = 31 * result + arguments.hashCode();
        return result;
    }
}