package com.hkdzagent.agent.security;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public class BootstrapUserInitializer implements InitializingBean {

    private static final Set<String> SUPPORTED_ROLES = Set.of("USER", "ADMIN");

    private final UserAccountRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final AgentSecurityProperties.Bootstrap properties;

    public BootstrapUserInitializer(
            UserAccountRepository repository,
            PasswordEncoder passwordEncoder,
            AgentSecurityProperties securityProperties
    ) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.properties = securityProperties.bootstrap();
    }

    @Override
    public void afterPropertiesSet() {
        boolean usernamePresent = properties.username() != null && !properties.username().isBlank();
        boolean passwordPresent = properties.password() != null && !properties.password().isBlank();
        if (!usernamePresent && !passwordPresent) {
            return;
        }
        if (!usernamePresent || !passwordPresent) {
            throw new IllegalStateException(
                    "agent security bootstrap username and password must be configured together"
            );
        }
        if (properties.password().length() < 12) {
            throw new IllegalStateException("agent security bootstrap password must contain at least 12 characters");
        }
        String role = properties.role() == null
                ? "ADMIN"
                : properties.role().trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_ROLES.contains(role)) {
            throw new IllegalStateException("unsupported bootstrap role: " + role);
        }
        if (repository.existsByUsername(properties.username())) {
            return;
        }
        try {
            repository.save(new UserAccount(
                    UUID.randomUUID().toString(),
                    properties.username().trim(),
                    passwordEncoder.encode(properties.password()),
                    true,
                    Set.of(role)
            ));
        } catch (DuplicateKeyException exception) {
            if (!repository.existsByUsername(properties.username())) {
                throw exception;
            }
        }
    }
}
