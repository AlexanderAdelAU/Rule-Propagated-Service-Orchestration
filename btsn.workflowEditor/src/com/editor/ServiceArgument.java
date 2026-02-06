package com.editor;

/**
 * Represents an argument for a service operation.
 * Arguments have a name, type, value, and required flag.
 */
public class ServiceArgument {
    private String name;
    private String type;       // "String" initially, extensible later
    private String value;      // default/configured value
    private boolean required;
    
    /**
     * Default constructor - creates an empty argument
     */
    public ServiceArgument() {
        this.name = "";
        this.type = "String";
        this.value = "";
        this.required = false;
    }
    
    /**
     * Constructor with name only - defaults to String type, empty value, not required
     */
    public ServiceArgument(String name) {
        this.name = name != null ? name : "";
        this.type = "String";
        this.value = "";
        this.required = false;
    }
    
    /**
     * Constructor with name and value - defaults to String type, not required
     */
    public ServiceArgument(String name, String value) {
        this.name = name != null ? name : "";
        this.type = "String";
        this.value = value != null ? value : "";
        this.required = false;
    }
    
    /**
     * Full constructor with all fields
     */
    public ServiceArgument(String name, String type, String value, boolean required) {
        this.name = name != null ? name : "";
        this.type = type != null ? type : "String";
        this.value = value != null ? value : "";
        this.required = required;
    }
    
    /**
     * Copy constructor - creates a deep copy of another ServiceArgument
     */
    public ServiceArgument(ServiceArgument other) {
        this.name = other.name;
        this.type = other.type;
        this.value = other.value;
        this.required = other.required;
    }
    
    // Getters
    public String getName() {
        return name;
    }
    
    public String getType() {
        return type;
    }
    
    public String getValue() {
        return value;
    }
    
    public boolean isRequired() {
        return required;
    }
    
    // Setters
    public void setName(String name) {
        this.name = name != null ? name : "";
    }
    
    public void setType(String type) {
        this.type = type != null ? type : "String";
    }
    
    public void setValue(String value) {
        this.value = value != null ? value : "";
    }
    
    public void setRequired(boolean required) {
        this.required = required;
    }
    
    @Override
    public String toString() {
        return "ServiceArgument{" +
                "name='" + name + '\'' +
                ", type='" + type + '\'' +
                ", value='" + value + '\'' +
                ", required=" + required +
                '}';
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        ServiceArgument that = (ServiceArgument) obj;
        return required == that.required &&
                name.equals(that.name) &&
                type.equals(that.type) &&
                value.equals(that.value);
    }
    
    @Override
    public int hashCode() {
        int result = name.hashCode();
        result = 31 * result + type.hashCode();
        result = 31 * result + value.hashCode();
        result = 31 * result + (required ? 1 : 0);
        return result;
    }
}