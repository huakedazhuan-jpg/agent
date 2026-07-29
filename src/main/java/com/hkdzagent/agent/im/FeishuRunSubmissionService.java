package com.hkdzagent.agent.im;

import com.hkdzagent.agent.runtime.AgentRun;
import com.hkdzagent.agent.runtime.AgentRunCoordinator;
import com.hkdzagent.agent.security.ActorIdentity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FeishuRunSubmissionService {

    private final FeishuEventInboxRepository inboxRepository;
    private final AgentRunCoordinator runCoordinator;

    public FeishuRunSubmissionService(
            FeishuEventInboxRepository inboxRepository,
            AgentRunCoordinator runCoordinator
    ) {
        this.inboxRepository = inboxRepository;
        this.runCoordinator = runCoordinator;
    }

    @Transactional
    public AgentRun findOrCreate(
            FeishuInboxEvent inboxEvent,
            ActorIdentity owner,
            String sessionId,
            String conversationId,
            String userMessage
    ) {
        if (inboxEvent.runId() != null) {
            AgentRun bound = runCoordinator.find(inboxEvent.runId());
            if (bound == null) {
                throw new IllegalStateException(
                        "Feishu inbox event references a missing agent run");
            }
            return bound;
        }
        AgentRun created = runCoordinator.create(
                owner, sessionId, conversationId, userMessage);
        if (!inboxRepository.bindRun(
                inboxEvent.eventId(), inboxEvent.retryCount(), created.runId())) {
            throw new IllegalStateException(
                    "Feishu inbox claim was lost before binding the agent run");
        }
        return created;
    }
}
