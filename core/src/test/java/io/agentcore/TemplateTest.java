package io.agentcore;

import com.sun.net.httpserver.HttpServer;
import io.agentcore.auth.AccessKeyCredential;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;

class TemplateTest {
    @TempDir Path directory;
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test void loadsMixedSkillsLazilyAndPreservesPromptAndFiles() throws Exception {
        try (var f = new Fixture(Map.of(
                "manifest.json", Json.write(Map.of("skills", List.of(Map.of("name", "static"),
                    Map.of("name", "remote", "sourceType", "REFERENCE", "versionSelector", Map.of("type", "LABEL", "value", "stable"))))).getBytes(),
                "config/AGENTS.md", "Main instructions".getBytes(), "config/SOUL.md", "Personality".getBytes(),
                "config/subagents/helper/AGENTS.md", "Not main".getBytes(),
                "skills/static/SKILL.md", "Static instructions".getBytes(),
                "skills/static/assets/file.bin", new byte[]{0, (byte) 255}))) {
            var template = f.core.templates().get("assistant").block(TIMEOUT);
            assertEquals("v1", template.version());
            assertEquals("## AGENTS.md\n\nMain instructions\n\n## SOUL.md\n\nPersonality", template.instruction());
            assertEquals(List.of("GetAgentSpec", "DownloadAgentSpecViaOss"), f.actions);
            var pair = Mono.zip(template.loadSkills(), template.loadSkills()).block(TIMEOUT);
            var skills = pair.getT1(); assertEquals(List.of("static", "remote"), skills.stream().map(io.agentcore.skill.Skill::name).toList());
            assertSame(skills.get(0), pair.getT2().get(0)); assertEquals("v3", skills.get(1).version());
            assertArrayEquals(new byte[]{0, (byte) 255}, Files.readAllBytes(skills.get(0).root().resolve("assets/file.bin")));
            assertEquals(1, f.actions.stream().filter("DownloadSkillVersionViaOss"::equals).count());
            assertEquals(3, io.agentcore.skill.Skill.tools(skills, directory).size());
        }
    }
    @Test void skillFailureDoesNotBreakPromptOrPoisonRetry() throws Exception {
        try (var f = new Fixture(Map.of("manifest.json", Json.write(Map.of("skills", List.of(Map.of("name", "remote", "sourceType", "REFERENCE")))).getBytes(), "config/SOUL.md", "Hello".getBytes()))) {
            var template = f.core.templates().get("assistant", "v1").block(TIMEOUT); f.fail = true;
            var error = assertThrows(AgentCoreException.class, () -> template.loadSkills().block(TIMEOUT));
            assertEquals("request-fixture", error.requestId()); assertEquals("Hello", template.instruction());
            f.fail = false; assertEquals("v2", template.loadSkills().block(TIMEOUT).get(0).version());
            assertFalse(f.actions.contains("GetAgentSpec"));
        }
    }
    @Test void missingLabelMustNotFallBackToAnOnlineVersion() throws Exception {
        try (var f = new Fixture(Map.of("manifest.json", Json.write(Map.of("skills", List.of(Map.of("name", "remote", "sourceType", "REFERENCE")))).getBytes()))) {
            f.labels = Map.of();
            var template = f.core.templates().get("assistant", "v1").block(TIMEOUT);
            assertThrows(IllegalArgumentException.class, () -> template.loadSkills().block(TIMEOUT));
            assertFalse(f.actions.contains("DownloadSkillVersionViaOss"));
        }
    }
    @Test void promptOnlyTemplateNeedsNoSkillService() throws Exception {
        try (var f = new Fixture(Map.of("manifest.json", "{}".getBytes(), "config/AGENTS.md", "Hello".getBytes()))) {
            var template = f.core.templates().get("assistant", "v1").block(TIMEOUT);
            assertEquals("Hello", template.instruction()); assertEquals(List.of(), template.loadSkills().block(TIMEOUT));
            assertEquals(List.of("DownloadAgentSpecViaOss"), f.actions);
        }
    }
    @Test void explicitSkillVersionDoesNotQueryLabels() throws Exception {
        try (var f = new Fixture(Map.of("manifest.json", Json.write(Map.of("skills", List.of(
                Map.of("name", "remote", "sourceType", "REFERENCE", "versionSelector", Map.of("type", "VERSION", "value", "v4"))))).getBytes()))) {
            var template = f.core.templates().get("assistant", "v1").block(TIMEOUT);
            assertEquals("v4", template.loadSkills().block(TIMEOUT).get(0).version());
            assertFalse(f.actions.contains("GetSkillDetail"));
        }
    }
    @Test void defaultSourceIsStaticAndNeverFallsBackToACloudSkill() throws Exception {
        try (var f = new Fixture(Map.of("manifest.json", Json.write(Map.of("skills", List.of(Map.of("name", "remote")))).getBytes(), "config/SOUL.md", "Hello".getBytes()))) {
            var template = f.core.templates().get("assistant", "v1").block(TIMEOUT);
            assertThrows(Exception.class, () -> template.loadSkills().block(TIMEOUT));
            assertEquals("Hello", template.instruction()); assertFalse(f.actions.contains("GetSkillDetail"));
            assertFalse(f.actions.contains("DownloadSkillVersionViaOss"));
        }
    }
    @Test void discoversStaticFilesAndRejectsReferenceConflicts() throws Exception {
        try (var f = new Fixture(Map.of("manifest.json", "{}".getBytes(), "skills/static/SKILL.md", "Static instructions".getBytes()))) {
            var skills = f.core.templates().get("assistant", "v1").flatMap(io.agentcore.template.AgentTemplate::loadSkills).block(TIMEOUT);
            assertEquals("static", skills.get(0).name()); assertEquals(1, f.actions.size());
        }
        try (var f = new Fixture(Map.of("manifest.json", Json.write(Map.of("skills", List.of(Map.of("name", "remote", "sourceType", "REFERENCE")))).getBytes(), "skills/remote/SKILL.md", "Conflict".getBytes()))) {
            assertThrows(IllegalArgumentException.class, () -> f.core.templates().get("assistant", "v1").block(TIMEOUT));
        }
    }
    @Test void rejectsInvalidManifestAndArchivePaths() throws Exception {
        try (var f = new Fixture(Map.of("manifest.json", "{broken".getBytes()))) {
            assertThrows(IllegalArgumentException.class, () -> f.core.templates().get("assistant", "v1").block(TIMEOUT));
        }
        try (var f = new Fixture(Map.of("manifest.json", "{}".getBytes(), "../escape", "Invalid".getBytes()))) {
            assertThrows(Exception.class, () -> f.core.templates().get("assistant", "v1").block(TIMEOUT));
        }
    }
    private final class Fixture implements AutoCloseable {
        final HttpServer server; final AgentCore core;
        final List<String> actions = new CopyOnWriteArrayList<>();
        volatile boolean fail;
        Map<String, String> labels = Map.of("latest", "v2", "stable", "v3");
        Fixture(Map<String, byte[]> files) throws Exception {
            byte[] template = archive(files), skill = archive(Map.of("remote/SKILL.md", "Remote instructions".getBytes()));
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath(); byte[] bytes; int status = 200;
                if (path.equals("/template.zip")) bytes = template;
                else if (path.equals("/skill.zip")) bytes = skill;
                else {
                    String action = exchange.getRequestHeaders().getFirst("x-acs-action"); actions.add(action);
                    assertNotNull(exchange.getRequestHeaders().getFirst("Authorization"));
                    Object response = switch (action) {
                        case "GetAgentSpec" -> Map.of("data", Map.of("labels", Map.of("latest", "v1")));
                        case "DownloadAgentSpecViaOss" -> Map.of("data", base + "/template.zip");
                        case "GetSkillDetail" -> Map.of("data", Map.of("labels", labels, "versions", List.of(Map.of("version", "v9", "status", "online"))));
                        case "DownloadSkillVersionViaOss" -> Map.of("data", base + "/skill.zip");
                        default -> throw new AssertionError(action);
                    };
                    if (fail && action.equals("DownloadSkillVersionViaOss")) { status = 403; response = Map.of("Code", "Forbidden", "Message", "No permission", "RequestId", "request-fixture"); }
                    bytes = Json.write(response).getBytes(); exchange.getResponseHeaders().set("Content-Type", "application/json");
                }
                exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
            }); server.start();
            core = AgentCore.builder().workspaceId("ws-test").regionId("cn-hangzhou").controlPlaneEndpoint(URI.create(base))
                .skillWorkspaceDir(directory).accessKeyCredential(new AccessKeyCredential("fixture-ak", "fixture-sk")).build();
        }
        public void close() { try { core.close(); } finally { server.stop(0); } }
    }
    private static byte[] archive(Map<String, byte[]> files) throws Exception {
        var output = new java.io.ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) { for (var file : files.entrySet()) {
            zip.putNextEntry(new ZipEntry(file.getKey())); zip.write(file.getValue()); zip.closeEntry();
        } }
        return output.toByteArray();
    }
}
