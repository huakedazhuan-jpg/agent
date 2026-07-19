package com.hkdzagent.agent.console;

import java.time.Instant;
import java.util.List;

public interface ToolConfirmationRepository {

    ToolConfirmation save(ToolConfirmation confirmation);

    List<ToolConfirmation> findPendingByOwnerAndSessionId(String ownerKey, String sessionId);

    ToolConfirmation findById(String confirmationId);

    List<ToolConfirmation> findByStatus(ToolConfirmation.Status status, int limit);

    ToolConfirmation decidePending(
            String confirmationId,
            ToolConfirmation.Status status,
            String decisionReason,
            Instant decidedAt
    );

    int expirePendingBefore(Instant cutoff);
}
