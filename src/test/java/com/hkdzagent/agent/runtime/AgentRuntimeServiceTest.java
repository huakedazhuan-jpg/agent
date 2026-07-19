package com.hkdzagent.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hkdzagent.agent.security.ActorIdentity;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRuntimeServiceTest {

    @Test
    void orchestratesCreateClaimCheckpointAndReplay() {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.setMaxSteps(4);
        properties.setLeaseDuration(Duration.ofMinutes(1));
        InMemoryAgentRunRepository repository = new InMemoryAgentRunRepository();
        AgentRuntimeService service = new AgentRuntimeService(
                repository,
                properties,
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
        );

        ActorIdentity owner = ActorIdentity.user("user-a");
        AgentRun created = service.create(owner, "session-1", "conversation-1", "trace-1", "question");
        AgentRunClaim claim = service.claim(created.runId(), "worker-a");
        AgentRun checkpointed = service.checkpoint(
                created.runId(), "worker-a", 1, Map.of("messages", 2));
        service.appendEvent(created.runId(), AgentRunEventType.MODEL_STARTED, Map.of("step", 1));

        assertThat(claim.started()).isTrue();
        assertThat(checkpointed.currentStep()).isOne();
        assertThat(checkpointed.checkpointJson()).contains("messages");
        assertThat(service.findOwned(created.runId(), owner)).isNotNull();
        assertThat(service.findOwned(created.runId(), ActorIdentity.user("user-b"))).isNull();
        assertThat(service.replayEvents(created.runId(), 0))
                .extracting(AgentRunEvent::type)
                .containsExactly(AgentRunEventType.RUN_CREATED, AgentRunEventType.MODEL_STARTED);
    }
}
