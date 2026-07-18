package com.hkdzagent.agent.functional;

import com.hkdzagent.agent.ai.LLMClient;
import com.hkdzagent.agent.controller.AgentController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AgentController.class)
class AgentChatFunctionalTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LLMClient llmClient;

    @Test
    void chatEndpointReturnsAgentAnswerAndPassesSessionIdToLlmClient() throws Exception {
        when(llmClient.askWithTools("Get AAPL quote", "functional-session"))
                .thenReturn("AAPL quote summary");

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

        verify(llmClient).askWithTools("Get AAPL quote", "functional-session");
    }
}
