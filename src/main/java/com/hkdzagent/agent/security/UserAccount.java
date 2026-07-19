package com.hkdzagent.agent.security;

import java.util.Set;

public record UserAccount(
        String id,
        String username,
        String passwordHash,
        boolean enabled,
        Set<String> roles
) {

    public UserAccount {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }
}
