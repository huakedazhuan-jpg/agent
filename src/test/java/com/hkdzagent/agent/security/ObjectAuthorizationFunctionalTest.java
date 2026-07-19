package com.hkdzagent.agent.security;

import com.hkdzagent.agent.ai.LLMClient;
import com.hkdzagent.agent.console.InMemoryToolConfirmationRepository;
import com.hkdzagent.agent.console.ToolConfirmation;
import com.hkdzagent.agent.console.ToolConfirmationService;
import com.hkdzagent.agent.controller.AgentController;
import com.hkdzagent.agent.memory.OwnedConversationId;
import com.hkdzagent.agent.trace.AgentTraceRecorder;
import com.hkdzagent.agent.trace.AgentTraceSanitizer;
import com.hkdzagent.agent.trace.InMemoryAgentTraceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hkdzagent.agent.runtime.AgentRun;
import com.hkdzagent.agent.runtime.AgentRuntimeProperties;
import com.hkdzagent.agent.runtime.AgentRuntimeService;
import com.hkdzagent.agent.runtime.InMemoryAgentRunRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AgentController.class)
@Import({
        SecurityConfiguration.class,
        RequestActorResolver.class,
        ObjectAuthorizationFunctionalTest.SecurityPropertiesConfig.class,
        ObjectAuthorizationFunctionalTest.OwnershipTestConfig.class
})
@TestPropertySource(properties = {
        "agent.security.enabled=true",
        "agent.security.user-repository=memory",
        "agent.security.jwt.issuer=ownership-test",
        "agent.security.jwt.secret=ownership-test-secret-with-at-least-32-bytes",
        "agent.security.jwt.ttl=30m"
})
class ObjectAuthorizationFunctionalTest {

    private static final String USER_A = "550e8400-e29b-41d4-a716-446655440001";
    private static final String USER_B = "550e8400-e29b-41d4-a716-446655440002";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AgentTraceRecorder traceRecorder;

    @Autowired
    private ToolConfirmationService confirmationService;

    @Autowired
    private AgentRuntimeService runtimeService;

    @MockitoBean
    private LLMClient llmClient;

    @Test
    void sameClientSessionIdIsNamespacedByAuthenticatedUser() throws Exception {
        mockMvc.perform(post("/api/agent/chat")
                        .with(userJwt(USER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"first","sessionId":"shared-session"}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/agent/chat")
                        .with(userJwt(USER_B))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"second","sessionId":"shared-session"}
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<String> conversationIds = ArgumentCaptor.forClass(String.class);
        verify(llmClient, times(2)).askWithTools(
                org.mockito.ArgumentMatchers.anyString(),
                conversationIds.capture()
        );
        OwnedConversationId first = OwnedConversationId.decode(conversationIds.getAllValues().get(0));
        OwnedConversationId second = OwnedConversationId.decode(conversationIds.getAllValues().get(1));

        assertThat(first.externalId()).isEqualTo("shared-session");
        assertThat(second.externalId()).isEqualTo("shared-session");
        assertThat(first.owner()).isEqualTo(ActorIdentity.user(USER_A));
        assertThat(second.owner()).isEqualTo(ActorIdentity.user(USER_B));
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void userCannotReadAnotherUsersTraceById() throws Exception {
        traceRecorder.startTrace(
                ActorIdentity.user(USER_A),
                "trace-owned-by-a",
                "shared-session",
                "private question"
        );

        mockMvc.perform(get("/api/agent/traces/trace-owned-by-a").with(userJwt(USER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traceId").value("trace-owned-by-a"));
        mockMvc.perform(get("/api/agent/traces/trace-owned-by-a").with(userJwt(USER_B)))
                .andExpect(status().isNotFound());
    }

    @Test
    void recentTraceListOnlyContainsCurrentUsersResources() throws Exception {
        traceRecorder.startTrace(ActorIdentity.user(USER_A), "trace-list-a", "same", "a");
        traceRecorder.startTrace(ActorIdentity.user(USER_B), "trace-list-b", "same", "b");

        mockMvc.perform(get("/api/agent/traces").with(userJwt(USER_B)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.traceId == 'trace-list-b')]").exists())
                .andExpect(jsonPath("$[?(@.traceId == 'trace-list-a')]").doesNotExist());
    }

    @Test
    void pendingApprovalListIsOwnerScopedButAdminCanDecideAcrossOwners() throws Exception {
        ToolConfirmation confirmation = confirmationService.requestConfirmation(
                ActorIdentity.user(USER_A),
                "shared-session",
                "trace-approval-a",
                "commandExecuteTool",
                "{\"command\":\"mvn test\"}"
        );

        mockMvc.perform(get("/api/agent/tool-confirmations")
                        .with(userJwt(USER_B))
                        .param("sessionId", "shared-session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
        mockMvc.perform(get("/api/agent/tool-confirmations")
                        .with(userJwt(USER_A))
                        .param("sessionId", "shared-session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(confirmation.id()));

        mockMvc.perform(post("/api/agent/tool-confirmations/{id}/approve", confirmation.id())
                        .with(userJwt(USER_A)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/agent/tool-confirmations/{id}/approve", confirmation.id())
                        .with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.ownerKey").value(ActorIdentity.user(USER_A).key()));
    }

    @Test
    void userCannotReadAnotherUsersAgentRunOrReplayItsEvents() throws Exception {
        AgentRun run = runtimeService.create(
                ActorIdentity.user(USER_A), "shared-session", "owned-conversation",
                "trace-run-owned-by-a", "private runtime request");

        mockMvc.perform(get("/api/agent/runs/{runId}", run.runId()).with(userJwt(USER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(run.runId()));
        mockMvc.perform(get("/api/agent/runs/{runId}", run.runId()).with(userJwt(USER_B)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/agent/runs/{runId}/events", run.runId()).with(userJwt(USER_B)))
                .andExpect(status().isNotFound());
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor userJwt(String subject) {
        return jwt()
                .jwt(token -> token.subject(subject).claim("roles", List.of("USER")))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor adminJwt() {
        return jwt()
                .jwt(token -> token.subject("admin-user").claim("roles", List.of("ADMIN")))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    @EnableConfigurationProperties(AgentSecurityProperties.class)
    static class SecurityPropertiesConfig {
    }

    @TestConfiguration
    static class OwnershipTestConfig {

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

        @Bean
        ToolConfirmationService toolConfirmationService(AgentTraceSanitizer sanitizer) {
            return new ToolConfirmationService(
                    new InMemoryToolConfirmationRepository(),
                    sanitizer,
                    Duration.ofMinutes(15),
                    Clock.systemUTC()
            );
        }

        @Bean
        AgentRuntimeService agentRuntimeService(ObjectMapper objectMapper) {
            return new AgentRuntimeService(
                    new InMemoryAgentRunRepository(), new AgentRuntimeProperties(),
                    objectMapper, Clock.systemUTC());
        }
    }
}
