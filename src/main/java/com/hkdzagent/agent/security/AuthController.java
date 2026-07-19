package com.hkdzagent.agent.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@ConditionalOnProperty(prefix = "agent.security", name = "enabled", havingValue = "true")
public class AuthController {

    private final AuthenticationService authenticationService;

    public AuthController(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        try {
            if (request == null) {
                throw new BadCredentialsException("invalid username or password");
            }
            AuthenticationService.AuthenticationResult result = authenticationService.authenticate(
                    request.username(),
                    request.password()
            );
            return ResponseEntity.ok(new LoginResponse(
                    result.token().value(),
                    "Bearer",
                    result.token().expiresAt(),
                    result.account().id(),
                    result.account().username(),
                    List.copyOf(result.account().roles())
            ));
        } catch (BadCredentialsException exception) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", 401, "message", "invalid username or password"));
        }
    }

    @GetMapping("/me")
    public CurrentUser me(@AuthenticationPrincipal Jwt jwt) {
        return new CurrentUser(
                jwt.getSubject(),
                jwt.getClaimAsString("username"),
                jwt.getClaimAsStringList("roles")
        );
    }

    public record LoginRequest(String username, String password) {
    }

    public record LoginResponse(
            String accessToken,
            String tokenType,
            Instant expiresAt,
            String userId,
            String username,
            List<String> roles
    ) {
    }

    public record CurrentUser(String userId, String username, List<String> roles) {
    }
}
