package io.agentcore;

public class AgentCoreException extends RuntimeException {
    private final String operation;
    private final Integer status;
    private final String requestId;
    private final String serviceCode;
    private final String serviceMessage;
    public AgentCoreException(String operation, Integer status, String requestId) {
        this(operation, status, requestId, null, null);
    }
    public AgentCoreException(String operation, Integer status, String requestId, String serviceCode, Throwable cause) {
        this(operation, status, requestId, serviceCode, null, cause);
    }
    public AgentCoreException(String operation, Integer status, String requestId, String serviceCode, String serviceMessage, Throwable cause) {
        super(operation + " failed" + (status == null ? "" : " (HTTP " + status + ")")
            + (serviceCode == null ? "" : ", code=" + serviceCode)
            + (requestId == null ? "" : ", requestId=" + requestId), cause);
        this.operation = operation; this.status = status; this.requestId = requestId; this.serviceCode = serviceCode;
        this.serviceMessage = safeMessage(serviceMessage);
    }
    public String operation() { return operation; }
    public Integer status() { return status; }
    public String requestId() { return requestId; }
    public String serviceCode() { return serviceCode; }
    public String serviceMessage() { return serviceMessage; }
    /** Stable summary without the downstream service's diagnostic description. */
    public String summary() { return super.getMessage(); }
    @Override public String getMessage() {
        return summary() + (serviceMessage == null ? "" : ", message=" + serviceMessage);
    }
    /** Only accepts a service message field, never a response or exception dump. */
    private static String safeMessage(String value) {
        if (value == null || value.isBlank()) return null;
        String text = value.replaceAll("[\\x00-\\x1f\\x7f]+", " ")
            .replaceAll("(?i)\\b(?:authorization|api[ _-]?key(?:\\s+provided)?|"
                + "access[_-]?key(?:[_-]?(?:id|secret))?|"
                + "(?:security|access|refresh|jwt)[_-]?token|token|password|secret|"
                + "text|content|query|messages|metadata|headers)[\"']?\\s*[:=].*", "<redacted>")
            .replaceAll("(?i)\\b(?:Bearer|Basic)\\s+[^\\s,;]+", "<redacted>")
            .replaceAll("\\b(?:LTAI[A-Za-z0-9]+|eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+)\\b", "<redacted>")
            .replaceAll("https?://\\S+", "<url>");
        return text.substring(0, Math.min(text.length(), 512));
    }
}
