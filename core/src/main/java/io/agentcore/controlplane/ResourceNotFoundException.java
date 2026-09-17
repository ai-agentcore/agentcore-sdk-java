package io.agentcore.controlplane;

public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String resource, String name) { super(resource + " not found: " + name); }
}
