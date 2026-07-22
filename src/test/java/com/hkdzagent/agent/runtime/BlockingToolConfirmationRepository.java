package com.hkdzagent.agent.runtime;

import com.hkdzagent.agent.console.ToolConfirmation;
import com.hkdzagent.agent.console.ToolConfirmationRepository;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

final class BlockingToolConfirmationRepository implements ToolConfirmationRepository {

    private final ToolConfirmationRepository delegate;
    private final AtomicReference<ToolConfirmation.Status> blockedStatus =
            new AtomicReference<>();
    private volatile CountDownLatch decisionBlocked = new CountDownLatch(0);
    private volatile CountDownLatch decisionRelease = new CountDownLatch(0);

    BlockingToolConfirmationRepository(ToolConfirmationRepository delegate) {
        this.delegate = delegate;
    }

    void blockNext(ToolConfirmation.Status status) {
        blockedStatus.set(status);
        decisionBlocked = new CountDownLatch(1);
        decisionRelease = new CountDownLatch(1);
    }

    void awaitBlocked() throws InterruptedException {
        assertThat(decisionBlocked.await(5, TimeUnit.SECONDS)).isTrue();
    }

    void releaseBlockedDecision() {
        decisionRelease.countDown();
    }

    @Override
    public ToolConfirmation save(ToolConfirmation confirmation) {
        return delegate.save(confirmation);
    }

    @Override
    public List<ToolConfirmation> findPendingByOwnerAndSessionId(
            String ownerKey,
            String sessionId
    ) {
        return delegate.findPendingByOwnerAndSessionId(ownerKey, sessionId);
    }

    @Override
    public ToolConfirmation findById(String confirmationId) {
        return delegate.findById(confirmationId);
    }

    @Override
    public List<ToolConfirmation> findByStatus(ToolConfirmation.Status status, int limit) {
        return delegate.findByStatus(status, limit);
    }

    @Override
    public ToolConfirmation decidePending(
            String confirmationId,
            ToolConfirmation.Status status,
            String decisionReason,
            Instant decidedAt
    ) {
        if (blockedStatus.compareAndSet(status, null)) {
            decisionBlocked.countDown();
            try {
                if (!decisionRelease.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("timed out waiting to release decision");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("decision wait interrupted", exception);
            }
        }
        return delegate.decidePending(
                confirmationId, status, decisionReason, decidedAt);
    }

    @Override
    public int expirePendingBefore(Instant cutoff) {
        return delegate.expirePendingBefore(cutoff);
    }
}
