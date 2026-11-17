package io.agentcore;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.yaml.snakeyaml.Yaml;

class PublishingWorkflowTest {
  @TempDir Path temporary;

  @ParameterizedTest
  @CsvSource({
    "sdk,0.1.6,pom.xml,'!collaboration,!integration-tests,!examples'",
    "collaboration,0.1.1,collaboration/pom.xml,:agentcore-collaboration"
  })
  void matchingTagSelectsOnlyItsReleaseUnit(
      String selected, String version, String pom, String projects) throws Exception {
    var output = select("push", selected + "-v" + version, "", version);
    assertEquals(0, output.exitCode(), output.log());
    assertEquals("pom=" + pom + "\nprojects=" + projects + "\n", output.selection());
    var steps = steps();
    for (String stepName : List.of("Verify selected package", "Publish selected package")) {
      var step =
          steps.stream().filter(s -> stepName.equals(s.get("name"))).findFirst().orElseThrow();
      var invocation =
          invoke(
              (String) step.get("run"), Map.of("PUBLISH_POM", pom, "PUBLISH_PROJECTS", projects));
      assertEquals(0, invocation.exitCode(), invocation.log());
      String arguments = Files.readString(temporary.resolve("maven-args"));
      assertTrue(arguments.contains("\n-f\n" + pom + "\n-pl\n" + projects + "\n"), arguments);
      assertFalse(arguments.lines().anyMatch("-am"::equals), arguments);
    }
  }

  @Test
  void collaborationHasIndependentManualAndTagTriggers() throws Exception {
    var workflow = workflow();
    // SnakeYAML resolves GitHub's unquoted "on" as a YAML 1.1 boolean.
    var triggers = (Map<?, ?>) workflow.getOrDefault("on", workflow.get(Boolean.TRUE));
    var dispatch = (Map<?, ?>) triggers.get("workflow_dispatch");
    var inputs = (Map<?, ?>) dispatch.get("inputs");
    assertTrue(
        ((List<?>) ((Map<?, ?>) inputs.get("package")).get("options")).contains("collaboration"));
    assertTrue(
        ((List<?>) ((Map<?, ?>) triggers.get("push")).get("tags"))
            .contains("collaboration-v*.*.*"));
  }

  @Test
  void manualCollaborationPublishRequiresSnapshot() throws Exception {
    var snapshot = select("workflow_dispatch", "main", "collaboration", "0.1.1-SNAPSHOT");
    assertEquals(0, snapshot.exitCode(), snapshot.log());
    assertEquals(
        "pom=collaboration/pom.xml\nprojects=:agentcore-collaboration\n", snapshot.selection());
    assertNotEquals(0, select("workflow_dispatch", "main", "collaboration", "0.1.0").exitCode());
  }

  @Test
  void mismatchedTagAndUnsupportedPackageCannotPublish() throws Exception {
    assertNotEquals(0, select("push", "collaboration-v9.9.9", "", "0.1.0").exitCode());
    assertNotEquals(
        0, select("push", "collaboration-v0.1.1-SNAPSHOT", "", "0.1.1-SNAPSHOT").exitCode());
    assertNotEquals(0, select("push", "unknown-v0.1.0", "", "0.1.0").exitCode());
  }

  private Result select(String event, String tag, String input, String version) throws Exception {
    var step =
        steps().stream()
            .filter(s -> "Select package and check version".equals(s.get("name")))
            .findFirst()
            .orElseThrow();
    return invoke(
        (String) step.get("run"),
        Map.of(
            "EVENT_NAME", event, "TAG_NAME", tag, "INPUT_PACKAGE", input, "TEST_VERSION", version));
  }

  private Result invoke(String script, Map<String, String> environment) throws Exception {
    var bin = Files.createDirectories(temporary.resolve("bin"));
    var maven = bin.resolve("mvn");
    Files.writeString(
        maven,
        "#!/bin/bash\nprintf '%s\\n' \"$@\" > \"$MAVEN_ARGUMENTS\"\n"
            + "if [[ \" $* \" == *' help:evaluate '* ]]; then printf '%s' \"$TEST_VERSION\"; fi\n");
    assertTrue(maven.toFile().setExecutable(true));
    var output = temporary.resolve("output");
    Files.writeString(output, "");
    var process = new ProcessBuilder("bash", "-e", "-c", script);
    process.environment().putAll(environment);
    process.environment().put("PATH", bin + ":" + System.getenv("PATH"));
    process.environment().put("GITHUB_OUTPUT", output.toString());
    process.environment().put("MAVEN_ARGUMENTS", temporary.resolve("maven-args").toString());
    var log = temporary.resolve("log");
    var child = process.redirectErrorStream(true).redirectOutput(log.toFile()).start();
    try {
      assertTrue(child.waitFor(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS));
      return new Result(child.exitValue(), Files.readString(output), Files.readString(log));
    } finally {
      child.destroyForcibly();
    }
  }

  private record Result(int exitCode, String selection, String log) {}

  private Map<String, Object> workflow() throws Exception {
    return new Yaml().load(Files.readString(Path.of("../.github/workflows/publish.yml")));
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> steps() throws Exception {
    var jobs = (Map<?, ?>) workflow().get("jobs");
    return (List<Map<String, Object>>) ((Map<?, ?>) jobs.get("publish")).get("steps");
  }
}
