package com.hkdzagent.agent.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class RequestActorResolver {

    public ActorIdentity resolve(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            return ActorIdentity.user(jwt.getSubject());
        }
        return ActorIdentity.localAnonymous();
    }
}
