package com.hkdzagent.agent.security;

import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

public class JwtTokenService {

    private final JwtEncoder encoder;
    private final AgentSecurityProperties.Jwt properties;
    private final Clock clock;

    public JwtTokenService(JwtEncoder encoder, AgentSecurityProperties properties) {
        this(encoder, properties.jwt(), Clock.systemUTC());
    }

    JwtTokenService(JwtEncoder encoder, AgentSecurityProperties.Jwt properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    public IssuedToken issue(UserAccount account) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(properties.ttl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(account.id())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("username", account.username())
                .claim("roles", List.copyOf(account.roles()))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, expiresAt);
    }

    public record IssuedToken(String value, Instant expiresAt) {
    }
}
