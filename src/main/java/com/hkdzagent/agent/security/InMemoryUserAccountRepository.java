package com.hkdzagent.agent.security;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public class InMemoryUserAccountRepository implements UserAccountRepository {

    private final Map<String, UserAccount> accounts = new LinkedHashMap<>();

    @Override
    public synchronized UserAccount findByUsername(String username) {
        return accounts.get(normalize(username));
    }

    @Override
    public synchronized boolean existsByUsername(String username) {
        return accounts.containsKey(normalize(username));
    }

    @Override
    public synchronized UserAccount save(UserAccount account) {
        accounts.put(normalize(account.username()), account);
        return account;
    }

    private String normalize(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }
}
