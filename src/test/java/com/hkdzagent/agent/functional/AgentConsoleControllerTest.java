package com.hkdzagent.agent.functional;

import com.hkdzagent.agent.ai.LLMClient;
import com.hkdzagent.agent.controller.AgentController;
import com.hkdzagent.agent.trace.AgentTraceRecorder;
import com.hkdzagent.agent.trace.AgentTraceSanitizer;
import com.hkdzagent.agent.trace.InMemoryAgentTraceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AgentController.class)
@Import(AgentConsoleControllerTest.TraceTestConfig.class)
class AgentConsoleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AgentTraceRecorder traceRecorder;

    @MockBean
    private LLMClient llmClient;

    @Test
    void streamEndpointEmitsStructuredConsoleEvents() throws Exception {
        when(llmClient.askWithTools("Get AAPL quote", "console-session"))
                .thenReturn("AAPL quote summary");

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

        verify(llmClient).askWithTools("Get AAPL quote", "console-session");
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
