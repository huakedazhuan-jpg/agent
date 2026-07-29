package com.hkdzagent.agent.memory;

import com.hkdzagent.agent.audit.AdminAuditEvent;
import com.hkdzagent.agent.audit.AdminAuditRepository;
import com.hkdzagent.agent.security.ActorIdentity;
import com.hkdzagent.agent.security.RequestActorResolver;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
public class AgentMemoryController {

    private final MemoryRepository memories;
    private final RequestActorResolver actors;
    private final AdminAuditRepository audit;

    public AgentMemoryController(
            MemoryRepository memories,
            RequestActorResolver actors,
            AdminAuditRepository audit
    ) {
        this.memories = memories;
        this.actors = actors;
        this.audit = audit;
    }

    @GetMapping("/api/agent/memories")
    public List<MemoryView> list(
            @RequestParam(defaultValue = "50") int limit,
            Authentication authentication
    ) {
        ActorIdentity owner = actors.resolve(authentication);
        return memories.findActive(owner.key(), Math.min(100, Math.max(1, limit))).stream()
                .map(MemoryView::from)
                .toList();
    }

    @DeleteMapping("/api/agent/memories/{memoryId}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID memoryId,
            Authentication authentication
    ) {
        ActorIdentity owner = actors.resolve(authentication);
        boolean deleted = memories.delete(owner.key(), memoryId);
        audit.save(event(
                owner.key(), "DELETE_AGENT_MEMORY", memoryId.toString(),
                deleted ? AdminAuditEvent.Outcome.SUCCEEDED : AdminAuditEvent.Outcome.REJECTED,
                deleted ? "memory deleted" : "memory not found for owner"));
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/api/agent/memories")
    public ResponseEntity<ClearMemoryResponse> clear(Authentication authentication) {
        ActorIdentity owner = actors.resolve(authentication);
        int deleted = memories.deleteAll(owner.key());
        audit.save(event(
                owner.key(), "CLEAR_AGENT_MEMORIES", owner.key(),
                AdminAuditEvent.Outcome.SUCCEEDED, "deleted=" + deleted));
        return ResponseEntity.ok(new ClearMemoryResponse(deleted));
    }

    private AdminAuditEvent event(
            String ownerKey,
            String action,
            String resourceId,
            AdminAuditEvent.Outcome outcome,
            String detail
    ) {
        return new AdminAuditEvent(
                UUID.randomUUID().toString(), ownerKey, action,
                "AGENT_MEMORY", resourceId, outcome, detail, Instant.now());
    }

    public record MemoryView(
            UUID id,
            MemoryType type,
            String content,
            UUID sourceConversationId,
            UUID sourceMessageId,
            double importance,
            double confidence,
            Instant createdAt
    ) {
        static MemoryView from(MemoryItem item) {
            return new MemoryView(
                    item.id(), item.memoryType(), item.content(),
                    item.sourceConversationId(), item.sourceMessageId(),
                    item.importance(), item.confidence(), item.createdAt());
        }
    }

    public record ClearMemoryResponse(int deletedCount) {
    }
}
