package io.agentcore.template;

import io.agentcore.skill.Skill;
import java.util.List;
import reactor.core.publisher.Mono;

/** A fetched template. Referenced Skills are resolved only when loadSkills is subscribed. */
public final class AgentTemplate {
    private final String name;
    private final String version;
    private final String instruction;
    private final Mono<List<Skill>> skills;

    AgentTemplate(String name, String version, String instruction, Mono<List<Skill>> loader) {
        this.name = name; this.version = version; this.instruction = instruction;
        var log = org.slf4j.LoggerFactory.getLogger(AgentTemplate.class);
        skills = loader.doOnSubscribe(value -> log.info("agentcore.template.skills.load.started name={} version={}", name, version))
            .doOnSuccess(value -> log.info("agentcore.template.skills.load.succeeded name={} version={} count={}", name, version, value.size()))
            .doOnError(error -> log.warn("agentcore.template.skills.load.failed name={} version={}", name, version, error))
            .map(List::copyOf).cacheInvalidateIf(value -> false);
    }
    public String name() { return name; }
    public String version() { return version; }
    public String instruction() { return instruction; }
    public Mono<List<Skill>> loadSkills() { return skills; }
}
