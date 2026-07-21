package com.hkdzagent.agent.functional;

import com.hkdzagent.agent.ai.LLMClient;
import com.hkdzagent.agent.controller.AgentController;
import com.hkdzagent.agent.memory.OwnedConversationId;
import com.hkdzagent.agent.runtime.AgentRun;
import com.hkdzagent.agent.runtime.AgentRunCoordinator;
import com.hkdzagent.agent.runtime.AgentRunStatus;
import com.hkdzagent.agent.security.ActorIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AgentController.class)
@AutoConfigureMockMvc(addFilters = false)
class AgentChatFunctionalTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LLMClient llmClient;

    @MockitoBean
    private AgentRunCoordinator runCoordinator;

    @Test
    void chatEndpointReturnsCompletedRuntimeAnswer() throws Exception {
        String conversationId = new OwnedConversationId(
                ActorIdentity.localAnonymous(),
                "functional-session"
        ).encode();
        AgentRun run = mock(AgentRun.class);
        when(run.status()).thenReturn(AgentRunStatus.COMPLETED);
        when(run.finalAnswer()).thenReturn("AAPL quote summary");
        when(runCoordinator.execute(
                eq(ActorIdentity.localAnonymous()),
                eq("functional-session"),
                eq(conversationId),
                eq("Get AAPL quote"),
                eq("http-chat")))
                .thenReturn(run);

        mockMvc.perform(post("/api/agent/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "Get AAPL quote",
                                  "sessionId": "functional-session"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("AAPL quote summary"));

        verify(runCoordinator).execute(
                ActorIdentity.localAnonymous(), "functional-session",
                conversationId, "Get AAPL quote", "http-chat");
        verifyNoInteractions(llmClient);
    }

    @Test
    void chatEndpointReturnsAcceptedRuntimeMetadataWhileWaitingForApproval() throws Exception {
        AgentRun run = mock(AgentRun.class);
        when(run.status()).thenReturn(AgentRunStatus.WAITING_APPROVAL);
        when(run.runId()).thenReturn("550e8400-e29b-41d4-a716-446655440001");
        when(run.traceId()).thenReturn("trace-waiting");
        when(run.pendingApprovalId()).thenReturn("550e8400-e29b-41d4-a716-446655440002");
        when(runCoordinator.execute(
                eq(ActorIdentity.localAnonymous()),
                eq("functional-session"),
                anyString(),
                eq("Run command"),
                eq("http-chat")))
                .thenReturn(run);

        mockMvc.perform(post("/api/agent/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"Run command","sessionId":"functional-session"}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId")
                        .value("550e8400-e29b-41d4-a716-446655440001"))
                .andExpect(jsonPath("$.traceId").value("trace-waiting"))
                .andExpect(jsonPath("$.status").value("WAITING_APPROVAL"))
                .andExpect(jsonPath("$.pendingApprovalId")
                        .value("550e8400-e29b-41d4-a716-446655440002"));
    }
}
