package io.agentcore.runtime;

import io.agentcore.Json;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public record RuntimeEnvironment(String controllerUrl, Path tokenFile, Map<String, String> values) {
    public RuntimeEnvironment { values = Map.copyOf(values); }
    public static RuntimeEnvironment parse(String input) {
        return parse(input, Map.of());
    }
    public static RuntimeEnvironment parse(String input, Map<String, String> environment) {
        var values = new LinkedHashMap<>(variables(input));
        values.putAll(environment);
        String url = values.getOrDefault("AGENTCORE_CONTROLLER_URL", values.get("AGENTTEAMS_CONTROLLER_URL"));
        String file = values.getOrDefault("AGENTCORE_AUTH_TOKEN_FILE", values.get("AGENTTEAMS_AUTH_TOKEN_FILE"));
        return new RuntimeEnvironment(AgentConfig.httpUrl(url).toString(), Path.of(Json.text(file, "SA token file")), values);
    }
    public static Map<String, String> variables(String input) {
        var values = new LinkedHashMap<String, String>();
        for (String raw : input.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("export ")) line = line.substring(7).trim();
            if (!line.contains("=")) throw new IllegalArgumentException("Invalid runtime env assignment");
            int separator = line.indexOf('=');
            String name = line.substring(0, separator).trim();
            String value = line.substring(separator + 1).trim();
            if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'")))
                value = value.substring(1, value.length() - 1);
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("Invalid runtime env name");
            values.put(name, value);
        }
        return Map.copyOf(values);
    }
    @Override public String toString() { return "RuntimeEnvironment(controllerUrl=" + controllerUrl + ")"; }
}
