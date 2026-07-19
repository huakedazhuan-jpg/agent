package com.hkdzagent.agent.security;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

public class AuthenticationService {

    private final UserAccountRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService tokenService;
    private final String dummyPasswordHash;

    public AuthenticationService(
            UserAccountRepository repository,
            PasswordEncoder passwordEncoder,
            JwtTokenService tokenService
    ) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.dummyPasswordHash = passwordEncoder.encode("authentication-timing-placeholder");
    }

    public AuthenticationResult authenticate(String username, String password) {
        UserAccount account = repository.findByUsername(username);
        String passwordHash = account == null ? dummyPasswordHash : account.passwordHash();
        boolean passwordMatches = password != null && passwordEncoder.matches(password, passwordHash);
        if (account == null
                || !account.enabled()
                || !passwordMatches) {
            throw new BadCredentialsException("invalid username or password");
        }
        JwtTokenService.IssuedToken token = tokenService.issue(account);
        return new AuthenticationResult(account, token);
    }

    public record AuthenticationResult(
            UserAccount account,
            JwtTokenService.IssuedToken token
    ) {
    }
}
