package io.agentcore.auth;

import io.agentcore.Json;

public record AccessKeyCredential(String accessKeyId, String accessKeySecret, String securityToken) {
    public AccessKeyCredential {
        Json.text(accessKeyId, "accessKeyId");
        Json.text(accessKeySecret, "accessKeySecret");
    }
    public AccessKeyCredential(String accessKeyId, String accessKeySecret) {
        this(accessKeyId, accessKeySecret, null);
    }
    @Override public String toString() { return "AccessKeyCredential(<redacted>)"; }
}
