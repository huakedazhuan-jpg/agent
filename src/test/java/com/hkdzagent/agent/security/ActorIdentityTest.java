package com.hkdzagent.agent.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ActorIdentityTest {

    @Test
    void createsDistinctNamespacedActors() {
        ActorIdentity user = ActorIdentity.user("550e8400-e29b-41d4-a716-446655440000");
        ActorIdentity feishu = ActorIdentity.feishu("open-1");

        assertThat(user.key()).isEqualTo("user:550e8400-e29b-41d4-a716-446655440000");
        assertThat(user.namespace()).isEqualTo("user");
        assertThat(user.subject()).isEqualTo("550e8400-e29b-41d4-a716-446655440000");
        assertThat(feishu.key()).isEqualTo("feishu:open-1");
        assertThat(ActorIdentity.localAnonymous().key()).isEqualTo("local:anonymous");
        assertThat(ActorIdentity.legacyUnowned().key()).isEqualTo("legacy:unowned");
    }

    @Test
    void rejectsMalformedOrOversizedActorKeys() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ActorIdentity("missing-namespace"));
        assertThatIllegalArgumentException().isThrownBy(() -> ActorIdentity.user(" "));
        assertThatIllegalArgumentException().isThrownBy(
                () -> ActorIdentity.namespaced("INVALID NAMESPACE", "subject")
        );
        assertThatIllegalArgumentException().isThrownBy(
                () -> new ActorIdentity("user:" + "x".repeat(ActorIdentity.MAX_KEY_LENGTH))
        );
    }
}
