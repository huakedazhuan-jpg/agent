package com.hkdzagent.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hkdzagent.agent.console.InMemoryToolConfirmationRepository;
import com.hkdzagent.agent.console.ToolConfirmation;
import com.hkdzagent.agent.console.ToolConfirmationService;
import com.hkdzagent.agent.im.FeishuResultOutboxMessage;
import com.hkdzagent.agent.im.FeishuResultOutboxService;
import com.hkdzagent.agent.im.InMemoryFeishuResultOutboxRepository;
import com.hkdzagent.agent.security.ActorIdentity;
import com.hkdzagent.agent.tool.ToolInvocationContext;
import com.hkdzagent.agent.tool.ToolPipelineResult;
import com.hkdzagent.agent.trace.AgentTraceSanitizer;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentApprovalPauseServiceBindingTest {

    @Test
    void pausesBoundFeishuInvocationAndQueuesApprovalNotification() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        ObjectMapper objectMapper = new ObjectMapper();
        AgentTraceSanitizer sanitizer = new AgentTraceSanitizer(160);
        AgentRuntimeService runtime = new AgentRuntimeService(
                new InMemoryAgentRunRepository(), new AgentRuntimeProperties(), objectMapper, clock);
        InMemoryToolConfirmationRepository confirmations =
                new InMemoryToolConfirmationRepository();
        InMemoryFeishuResultOutboxRepository outbox =
                new InMemoryFeishuResultOutboxRepository();
        AgentApprovalPauseService pauseService = new AgentApprovalPauseService(
                new ToolConfirmationService(
                        confirmations, sanitizer, Duration.ofMinutes(15), clock),
                runtime,
                sanitizer,
                objectMapper,
                new FeishuResultOutboxService(outbox, clock)
        );
        AgentRun created = runtime.create(
                ActorIdentity.feishu("open-1"),
                "open-1",
                "feishu-conversation",
                "binding-trace",
                "run command"
        );
        AgentRunClaim claim = runtime.claim(created.runId(), "binding-worker");
        String arguments = "{\"command\":\"mvn test\"}";
        ToolInvocationContext context = new ToolInvocationContext(
                created.ownerKey(), created.runId(), created.traceId(),
                "call-original", Set.of());
        ToolPipelineResult assessment = new ToolPipelineResult(
                ToolPipelineResult.Status.APPROVAL_REQUIRED,
                "commandExecuteTool", "1.0.0", "a".repeat(64),
                arguments, null, "approval required");
        String checkpoint = "{\"schemaVersion\":1,\"toolCall\":{"
                + "\"id\":\"call-original\",\"function\":{"
                + "\"name\":\"commandExecuteTool\","
                + "\"arguments\":\"{\\\"command\\\":\\\"mvn test\\\"}\"}}}";

        pauseService.pauseBoundInvocation(
                created, claim.run().leaseOwner(), 1, context,
                arguments, assessment, checkpoint);

        AgentRun waiting = runtime.find(created.runId());
        assertThat(waiting.status()).isEqualTo(AgentRunStatus.WAITING_APPROVAL);
        assertThat(waiting.pendingApprovalId()).isNotBlank();
        FeishuResultOutboxMessage notification = outbox.findByDeduplicationKey(
                "run:" + created.runId() + ":approval:" + waiting.pendingApprovalId());
        assertThat(notification).isNotNull();
        assertThat(notification.type())
                .isEqualTo(FeishuResultOutboxMessage.Type.APPROVAL_REQUIRED);
        assertThat(runtime.replayEvents(created.runId(), 0))
                .extracting(AgentRunEvent::type)
                .contains(AgentRunEventType.APPROVAL_REQUIRED);
    }

    @Test
    void rejectsTamperedCheckpointBeforeCreatingApprovalOrPausingRun() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        ObjectMapper objectMapper = new ObjectMapper();
        AgentTraceSanitizer sanitizer = new AgentTraceSanitizer(160);
        AgentRuntimeService runtime = new AgentRuntimeService(
                new InMemoryAgentRunRepository(), new AgentRuntimeProperties(), objectMapper, clock);
        InMemoryToolConfirmationRepository confirmations =
                new InMemoryToolConfirmationRepository();
        AgentApprovalPauseService pauseService = new AgentApprovalPauseService(
                new ToolConfirmationService(
                        confirmations, sanitizer, Duration.ofMinutes(15), clock),
                runtime,
                sanitizer,
                objectMapper
        );
        AgentRun created = runtime.create(
                ActorIdentity.user("binding-user"),
                "binding-session",
                "binding-conversation",
                "binding-trace",
                "run command"
        );
        AgentRunClaim claim = runtime.claim(created.runId(), "binding-worker");
        ToolInvocationContext context = new ToolInvocationContext(
                created.ownerKey(), created.runId(), created.traceId(),
                "call-original", Set.of());
        ToolPipelineResult assessment = new ToolPipelineResult(
                ToolPipelineResult.Status.APPROVAL_REQUIRED,
                "commandExecuteTool",
                "1.0.0",
                "a".repeat(64),
                "{\"command\":\"mvn test\"}",
                null,
                "tool policy always requires approval"
        );
        String tamperedCheckpoint = "{\"schemaVersion\":1,\"toolCall\":{"
                + "\"id\":\"call-replaced\",\"function\":{"
                + "\"name\":\"commandExecuteTool\","
                + "\"arguments\":\"{\\\"command\\\":\\\"mvn test\\\"}\"}}}";

        assertThatThrownBy(() -> pauseService.pauseBoundInvocation(
                created,
                claim.run().leaseOwner(),
                1,
                context,
                "{\"command\":\"mvn test\"}",
                assessment,
                tamperedCheckpoint
        )).isInstanceOf(SecurityException.class)
                .hasMessageContaining("checkpoint");

        assertThat(confirmations.findByStatus(ToolConfirmation.Status.PENDING, 10)).isEmpty();
        assertThat(runtime.find(created.runId()).status()).isEqualTo(AgentRunStatus.RUNNING);
    }
}
