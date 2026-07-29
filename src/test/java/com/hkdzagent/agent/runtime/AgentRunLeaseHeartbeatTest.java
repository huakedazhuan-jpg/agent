package com.hkdzagent.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hkdzagent.agent.security.ActorIdentity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AgentRunLeaseHeartbeatTest {

    @Test
    void recordsLeaseLossWhenAnotherWorkerTakesOverExpiredRun() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.setLeaseDuration(Duration.ofSeconds(30));
        properties.setHeartbeatInterval(Duration.ofSeconds(10));
        AgentRuntimeService runtime = new AgentRuntimeService(
                new InMemoryAgentRunRepository(), properties, new ObjectMapper(), clock);
        AgentRun created = runtime.create(
                ActorIdentity.user("heartbeat-user"), "session", "conversation", "trace", "question");
        AgentRun claimed = runtime.claim(created.runId(), "worker-a").run();
        TaskScheduler scheduler = mock(TaskScheduler.class);
        @SuppressWarnings("unchecked")
        ScheduledFuture<Object> future = mock(ScheduledFuture.class);
        ArgumentCaptor<Runnable> heartbeatTask = ArgumentCaptor.forClass(Runnable.class);
        doReturn(future).when(scheduler).scheduleAtFixedRate(
                heartbeatTask.capture(), eq(Duration.ofSeconds(10)));
        AgentRunLeaseHeartbeat heartbeat = new AgentRunLeaseHeartbeatFactory(
                runtime, scheduler, Duration.ofSeconds(10))
                .start(created.runId(), "worker-a", claimed.leaseEpoch());

        clock.advance(Duration.ofSeconds(31));
        AgentRun replacement = runtime.claim(created.runId(), "worker-b").run();
        heartbeatTask.getValue().run();

        assertThat(replacement.leaseEpoch()).isEqualTo(claimed.leaseEpoch() + 1);
        assertThatThrownBy(heartbeat::assertActive)
                .isInstanceOf(AgentRunLeaseLostException.class);
        heartbeat.close();
        verify(future).cancel(false);
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
