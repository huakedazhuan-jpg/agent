package com.hkdzagent.agent.security;

public interface UserAccountRepository {

    UserAccount findByUsername(String username);

    boolean existsByUsername(String username);

    UserAccount save(UserAccount account);
}
