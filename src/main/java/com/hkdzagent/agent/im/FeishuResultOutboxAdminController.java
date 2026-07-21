package com.hkdzagent.agent.im;

import com.hkdzagent.agent.security.RequestActorResolver;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/agent/admin/feishu-outbox")
public class FeishuResultOutboxAdminController {

    private final FeishuResultOutboxAdminService adminService;
    private final RequestActorResolver actorResolver;

    public FeishuResultOutboxAdminController(
            FeishuResultOutboxAdminService adminService,
            RequestActorResolver actorResolver
    ) {
        this.adminService = adminService;
        this.actorResolver = actorResolver;
    }

    @GetMapping("/summary")
    public FeishuResultOutboxAdminService.Summary summary() {
        return adminService.summary();
    }

    @GetMapping("/messages")
    public List<FeishuResultOutboxAdminService.MessageView> messages(
            @RequestParam(defaultValue = "DEAD") String status,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return adminService.findByStatus(status, limit);
    }

    @PostMapping("/{id}/retry")
    public FeishuResultOutboxAdminService.MessageView retry(
            @PathVariable String id,
            Authentication authentication
    ) {
        return adminService.retryDead(id, actorResolver.resolve(authentication));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
    }

    @ExceptionHandler(FeishuResultOutboxAdminService.OutboxMessageNotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(RuntimeException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("message", exception.getMessage()));
    }

    @ExceptionHandler(FeishuResultOutboxAdminService.OutboxMessageStateException.class)
    public ResponseEntity<Map<String, String>> conflict(RuntimeException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("message", exception.getMessage()));
    }
}
