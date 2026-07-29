package com.hkdzagent.agent.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SecurityProbeController.class)
@Import({
        SecurityConfiguration.class,
        AuthController.class,
        SecurityFunctionalTest.SecurityPropertiesConfig.class,
        SecurityProbeController.class
})
@TestPropertySource(properties = {
        "agent.security.enabled=true",
        "agent.security.user-repository=memory",
        "agent.security.jwt.issuer=test-issuer",
        "agent.security.jwt.secret=test-jwt-secret-with-at-least-32-bytes",
        "agent.security.jwt.ttl=30m",
        "agent.security.bootstrap.username=admin",
        "agent.security.bootstrap.password=strong-admin-password",
        "agent.security.bootstrap.role=ADMIN"
})
class SecurityFunctionalTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void anonymousUserCannotAccessAgentApi() throws Exception {
        mockMvc.perform(post("/api/agent/chat"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validLoginReturnsJwtThatCanReadCurrentUser() throws Exception {
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"admin","password":"strong-admin-password"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.username").value("admin"))
                .andExpect(jsonPath("$.roles[0]").value("ADMIN"))
                .andReturn();

        JsonNode response = objectMapper.readTree(login.getResponse().getContentAsString());
        String token = response.path("accessToken").asText();
        assertThat(token).isNotBlank();

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("admin"))
                .andExpect(jsonPath("$.roles[0]").value("ADMIN"));
    }

    @Test
    void invalidLoginUsesGenericUnauthorizedResponse() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"admin","password":"wrong-password"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("invalid username or password"));
    }

    @Test
    void nullLoginPayloadIsRejectedAsMalformedRequest() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("null"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void authenticatedUserCanUseAgentButCannotDecideApprovals() throws Exception {
        var userJwt = jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"));

        mockMvc.perform(post("/api/agent/chat").with(userJwt))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/agent/tool-confirmations/id/approve").with(userJwt))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/agent/admin/feishu-outbox/summary").with(userJwt))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/metrics").with(userJwt))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanDecideApprovalsAndFeishuWebhookRemainsPublic() throws Exception {
        mockMvc.perform(post("/api/agent/tool-confirmations/id/approve")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/agent/admin/feishu-outbox/summary")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/actuator/metrics")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/feishu/webhook"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @EnableConfigurationProperties(AgentSecurityProperties.class)
    static class SecurityPropertiesConfig {
    }

}

@RestController
@TestComponent
class SecurityProbeController {

    @PostMapping("/api/agent/chat")
    String chat() {
        return "ok";
    }

    @PostMapping("/api/agent/tool-confirmations/{id}/approve")
    String approve() {
        return "approved";
    }

    @GetMapping("/api/agent/admin/feishu-outbox/summary")
    String outboxSummary() {
        return "summary";
    }

    @GetMapping("/actuator/metrics")
    String metrics() {
        return "metrics";
    }

    @GetMapping("/actuator/health")
    String health() {
        return "up";
    }

    @PostMapping("/api/feishu/webhook")
    String webhook() {
        return "ok";
    }

    @GetMapping("/")
    String index() {
        return "index";
    }
}
