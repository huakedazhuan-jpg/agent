package com.hkdzagent.agent.memory;

import com.hkdzagent.agent.security.ActorIdentity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class OwnedConversationIdTest {

    @Test
    void roundTripsOwnerAndExternalIdWithoutDelimiterCollisions() {
        OwnedConversationId original = new OwnedConversationId(
                ActorIdentity.user("user:with:delimiters"),
                "session:with spaces/and?symbols"
        );

        OwnedConversationId decoded = OwnedConversationId.decode(original.encode());

        assertThat(decoded).isEqualTo(original);
        assertThat(original.encode()).startsWith("owned:v1:");
    }

    @Test
    void sameExternalIdProducesDifferentInternalIdsForDifferentActors() {
        String first = new OwnedConversationId(ActorIdentity.user("user-1"), "default").encode();
        String second = new OwnedConversationId(ActorIdentity.user("user-2"), "default").encode();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void rejectsUnsupportedOrMalformedEncodedIds() {
        assertThatIllegalArgumentException().isThrownBy(() -> OwnedConversationId.decode("default"));
        assertThatIllegalArgumentException().isThrownBy(() -> OwnedConversationId.decode("owned:v1:not-base64:x"));
    }
}
