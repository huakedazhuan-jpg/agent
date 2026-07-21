package com.hkdzagent.agent.im;

import com.hkdzagent.agent.security.ActorIdentity;
import com.hkdzagent.agent.security.RequestActorResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FeishuResultOutboxAdminController.class)
@AutoConfigureMockMvc(addFilters = false)
class FeishuResultOutboxAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FeishuResultOutboxAdminService adminService;

    @MockitoBean
    private RequestActorResolver actorResolver;

    @Test
    void exposesSummaryAndFilteredMessages() throws Exception {
        Instant oldest = Instant.parse("2026-01-01T00:00:00Z");
        when(adminService.summary()).thenReturn(new FeishuResultOutboxAdminService.Summary(
                5, 1, 1, 1, 1, 1, 2, oldest, Duration.ofMinutes(3)));
        when(adminService.findByStatus("DEAD", 10)).thenReturn(List.of(view("id-1")));

        mockMvc.perform(get("/api/agent/admin/feishu-outbox/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.dead").value(1))
                .andExpect(jsonPath("$.ready").value(2));

        mockMvc.perform(get("/api/agent/admin/feishu-outbox/messages")
                        .param("status", "DEAD").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("id-1"))
                .andExpect(jsonPath("$[0].recipient").value("***1234"));
    }

    @Test
    void retriesMessageAndMapsDomainFailures() throws Exception {
        ActorIdentity admin = ActorIdentity.user("admin-id");
        when(actorResolver.resolve(any())).thenReturn(admin);
        when(adminService.retryDead(eq("dead-id"), any())).thenReturn(view("dead-id"));
        when(adminService.retryDead(eq("missing-id"), any()))
                .thenThrow(new FeishuResultOutboxAdminService.OutboxMessageNotFoundException("missing-id"));
        when(adminService.retryDead(eq("pending-id"), any()))
                .thenThrow(new FeishuResultOutboxAdminService.OutboxMessageStateException(
                        "pending-id", FeishuResultOutboxMessage.Status.PENDING));

        mockMvc.perform(post("/api/agent/admin/feishu-outbox/dead-id/retry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("dead-id"));
        mockMvc.perform(post("/api/agent/admin/feishu-outbox/missing-id/retry"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/agent/admin/feishu-outbox/pending-id/retry"))
                .andExpect(status().isConflict());
        when(adminService.retryDead(eq("invalid-id"), any()))
                .thenThrow(new IllegalArgumentException("outbox message id must be a UUID"));
        mockMvc.perform(post("/api/agent/admin/feishu-outbox/invalid-id/retry"))
                .andExpect(status().isBadRequest());
    }

    private FeishuResultOutboxAdminService.MessageView view(String id) {
        return new FeishuResultOutboxAdminService.MessageView(
                id, "run-id", "***1234", FeishuResultOutboxMessage.Type.FINAL_RESULT,
                FeishuResultOutboxMessage.Status.RETRYABLE,
                Instant.parse("2026-01-01T00:00:00Z"), null, null, null,
                Instant.parse("2026-01-01T00:01:00Z"), 0, "previous failure");
    }
}
