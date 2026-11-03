package io.agentcore;

import io.agentcore.internal.ResourceDefaults;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ResourceDefaultsTest {
    @Test void pairsDefaultsAndLeavesExplicitResourcesUnchanged() {
        var defaults = new ResourceDefaults(Map.of(
            "AGENTCORE_MODEL_CONNECTION_NAME", "mc", "AGENTCORE_MODEL_NAME", "model",
            "AGENTCORE_AGENT_SPEC_NAME", "spec", "AGENTCORE_AGENT_SPEC_VERSION", "v1"));
        assertEquals(new ResourceDefaults.Model("mc", "model"), defaults.model(null, null));
        assertEquals(new ResourceDefaults.Model("mc", "override"), defaults.model(null, "override"));
        assertEquals(new ResourceDefaults.Model("other", null), defaults.model("other", null));
        assertEquals(new ResourceDefaults.VersionedResource("spec", "v1"), defaults.agentSpec(null, null));
        assertEquals(new ResourceDefaults.VersionedResource("other", null), defaults.agentSpec("other", null));
        assertEquals(new ResourceDefaults.VersionedResource("spec", "v2"), defaults.agentSpec(null, "v2"));
    }
    @Test void listsAreOrderedAndSnapshotDoesNotFollowChanges() {
        var env = new HashMap<>(Map.of("AGENTCORE_MCP_NAMES", "[\"a\",\"b\"]",
            "AGENTCORE_SKILLS", "[{\"name\":\"s1\"},{\"name\":\"s2\",\"version\":\"2\"}]"));
        var defaults = new ResourceDefaults(env);
        env.put("AGENTCORE_MCP_NAMES", "[\"changed\"]");
        assertEquals(List.of("a", "b"), defaults.mcpNames());
        assertEquals(List.of(new ResourceDefaults.VersionedResource("s1", null), new ResourceDefaults.VersionedResource("s2", "2")), defaults.skills());
        assertEquals(List.of(), new ResourceDefaults(Map.of()).mcpNames());
        assertEquals(List.of(), new ResourceDefaults(Map.of()).skills());
    }
    @Test void malformedUnusedConfigurationDoesNotBreakOtherResources() {
        for (var key : List.of("AGENTCORE_MCP_NAMES", "AGENTCORE_SKILLS"))
            for (var value : List.of("bad", "null", "{}", "[1]", "[\"\"]", "[] trailing", "[{\"name\":\"s\",\"version\":1}]")) {
                var defaults = new ResourceDefaults(Map.of(key, value));
                var error = assertThrows(IllegalArgumentException.class, () -> {
                    if (key.equals("AGENTCORE_MCP_NAMES")) defaults.mcpNames(); else defaults.skills();
                });
                assertTrue(error.getMessage().contains(key));
                assertEquals("explicit", defaults.model("explicit", null).name());
            }
    }
    @Test void missingNamesAreDiagnosableAtTheRelevantEntryPoint() {
        var defaults = new ResourceDefaults(Map.of());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> defaults.model(null, null))
            .getMessage().contains("AGENTCORE_MODEL_CONNECTION_NAME"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> defaults.agentSpec(null, null))
            .getMessage().contains("AGENTCORE_AGENT_SPEC_NAME"));
    }
}
