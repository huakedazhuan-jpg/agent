package com.hkdzagent.agent.functional;

import com.hkdzagent.agent.ai.LLMClient;
import com.hkdzagent.agent.controller.AgentController;
import com.hkdzagent.agent.memory.OwnedConversationId;
import com.hkdzagent.agent.ai.AgentExecutionObserver;
import com.hkdzagent.agent.loop.AgentLoopResult;
import com.hkdzagent.agent.security.ActorIdentity;
import com.hkdzagent.agent.trace.AgentTraceRecorder;
import com.hkdzagent.agent.trace.AgentTraceSanitizer;
import com.hkdzagent.agent.trace.InMemoryAgentTraceRepository;
import com.hkdzagent.agent.runtime.AgentCancellationService;
import com.hkdzagent.agent.runtime.AgentRun;
import com.hkdzagent.agent.runtime.AgentRunCancellation;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AgentController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(AgentConsoleControllerTest.TraceTestConfig.class)
class AgentConsoleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AgentTraceRecorder traceRecorder;

    @MockitoBean
    private LLMClient llmClient;

    @MockitoBean
    private AgentCancellationService cancellationService;

    @Test
    void streamEndpointEmitsStructuredConsoleEvents() throws Exception {
        String conversationId = new OwnedConversationId(
                ActorIdentity.localAnonymous(),
                "console-session"
        ).encode();
        when(llmClient.runWithTools(
                eq("Get AAPL quote"), eq(conversationId), any(String.class), any(AgentExecutionObserver.class)
        )).thenAnswer(invocation -> {
            AgentExecutionObserver observer = invocation.getArgument(3);
            observer.modelStarted(1);
            observer.tokenDelta(1, "AAPL ");
            observer.tokenDelta(1, "quote summary");
            observer.modelCompleted(1);
            return new AgentLoopResult(
                    AgentLoopResult.Status.COMPLETED,
                    invocation.getArgument(2),
                    "AAPL quote summary",
                    List.of()
            );
        });

        MvcResult pending = mockMvc.perform(post("/api/agent/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "Get AAPL quote",
                                  "sessionId": "console-session"
                                }
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult result = mockMvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString(MediaType.TEXT_EVENT_STREAM_VALUE)))
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("event: started");
        assertThat(body).contains("event: token");
        assertThat(body).contains("event: final");
        assertThat(body).contains("\"traceId\"");
        assertThat(body).contains("AAPL quote summary");

        Matcher runId = Pattern.compile("\\\"runId\\\":\\\"([^\\\"]+)\\\"").matcher(body);
        assertThat(runId.find()).isTrue();
        MvcResult replayPending = mockMvc.perform(get("/api/agent/runs/{runId}/events", runId.group(1))
                        .param("after", "1"))
                .andExpect(request().asyncStarted())
                .andReturn();
        String replay = mockMvc.perform(asyncDispatch(replayPending))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(replay).doesNotContain("event: created");
        assertThat(replay).contains("event: started", "event: token", "event: final");

        mockMvc.perform(get("/api/agent/runs/{runId}", runId.group(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(runId.group(1)))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.finalAnswer").value("AAPL quote summary"))
                .andExpect(jsonPath("$.checkpointJson").doesNotExist());

        verify(llmClient).runWithTools(
                eq("Get AAPL quote"), eq(conversationId), any(String.class), any(AgentExecutionObserver.class));
    }

    @Test
    void traceEndpointReturnsStoredToolEventsForConsoleInspection() throws Exception {
        traceRecorder.startTrace("trace-console", "console-session", "write file");
        traceRecorder.recordToolCall("trace-console", 1, "commandExecuteTool", "{\"command\":\"echo hello\"}");
        traceRecorder.recordToolObservation("trace-console", 1, "commandExecuteTool", true,
                "hello", Duration.ofMillis(12));
        traceRecorder.recordFinalAnswer("trace-console", "done");
        traceRecorder.finishTrace("trace-console", "COMPLETED");

        mockMvc.perform(get("/api/agent/traces/trace-console"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traceId").value("trace-console"))
                .andExpect(jsonPath("$.sessionId").value("console-session"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.events[0].type").value("TOOL_CALL"))
                .andExpect(jsonPath("$.events[0].toolName").value("commandExecuteTool"))
                .andExpect(jsonPath("$.events[1].type").value("TOOL_OBSERVATION"))
                .andExpect(jsonPath("$.events[1].success").value(true))
                .andExpect(jsonPath("$.events[2].type").value("FINAL_ANSWER"));
    }

    @Test
    void recentTracesEndpointReturnsNewestTracesFirst() throws Exception {
        traceRecorder.startTrace("trace-old", "console-session", "old");
        traceRecorder.finishTrace("trace-old", "COMPLETED");
        traceRecorder.startTrace("trace-new", "console-session", "new");
        traceRecorder.finishTrace("trace-new", "COMPLETED");

        mockMvc.perform(get("/api/agent/traces").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].traceId").value("trace-new"))
                .andExpect(jsonPath("$[1].traceId").value("trace-old"));
    }

    @Test
    void cancelEndpointMapsIdempotentNotFoundAndTerminalOutcomes() throws Exception {
        AgentRun created = AgentRun.created(
                "550e8400-e29b-41d4-a716-446655440010",
                ActorIdentity.localAnonymous(), "session", "conversation", "trace-cancel",
                "cancel", 5, Instant.parse("2026-01-01T00:00:00Z"));
        AgentRun cancelled = created.cancel(Instant.parse("2026-01-01T00:00:01Z"));
        when(cancellationService.cancel(
                any(ActorIdentity.class), eq(created.runId()), eq("stop now")))
                .thenReturn(new AgentRunCancellation(
                        AgentRunCancellation.Outcome.CANCELLED, cancelled));
        when(cancellationService.cancel(
                any(ActorIdentity.class), eq("550e8400-e29b-41d4-a716-446655440011"), eq(null)))
                .thenReturn(new AgentRunCancellation(
                        AgentRunCancellation.Outcome.NOT_FOUND, null));
        AgentRun completed = created.claim(
                        "worker", Instant.parse("2026-01-01T00:00:00Z"),
                        Instant.parse("2026-01-01T00:01:00Z"))
                .complete("done", "worker", 1, Instant.parse("2026-01-01T00:00:02Z"));
        when(cancellationService.cancel(
                any(ActorIdentity.class), eq("550e8400-e29b-41d4-a716-446655440012"), eq(null)))
                .thenReturn(new AgentRunCancellation(
                        AgentRunCancellation.Outcome.TERMINAL_CONFLICT, completed));

        mockMvc.perform(post("/api/agent/runs/{runId}/cancel", created.runId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"stop now\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.newlyCancelled").value(true));
        mockMvc.perform(post("/api/agent/runs/{runId}/cancel",
                        "550e8400-e29b-41d4-a716-446655440011"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/agent/runs/{runId}/cancel",
                        "550e8400-e29b-41d4-a716-446655440012"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @TestConfiguration
    static class TraceTestConfig {

        @Bean
        InMemoryAgentTraceRepository inMemoryAgentTraceRepository() {
            return new InMemoryAgentTraceRepository();
        }

        @Bean
        AgentTraceSanitizer agentTraceSanitizer() {
            return new AgentTraceSanitizer(120);
        }

        @Bean
        AgentTraceRecorder agentTraceRecorder(
                InMemoryAgentTraceRepository repository,
                AgentTraceSanitizer sanitizer
        ) {
            return new AgentTraceRecorder(repository, sanitizer);
        }
    }
}
