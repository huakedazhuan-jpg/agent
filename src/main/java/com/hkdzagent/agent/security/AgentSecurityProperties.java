package com.hkdzagent.agent.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "agent.security")
public class AgentSecurityProperties {

    private boolean enabled;
    private String userRepository = "memory";
    private Jwt jwt = new Jwt();
    private Bootstrap bootstrap = new Bootstrap();

    public boolean enabled() {
        return enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String userRepository() {
        return userRepository;
    }

    public String getUserRepository() {
        return userRepository;
    }

    public void setUserRepository(String userRepository) {
        this.userRepository = userRepository;
    }

    public Jwt jwt() {
        return jwt;
    }

    public Jwt getJwt() {
        return jwt;
    }

    public void setJwt(Jwt jwt) {
        this.jwt = jwt == null ? new Jwt() : jwt;
    }

    public Bootstrap bootstrap() {
        return bootstrap;
    }

    public Bootstrap getBootstrap() {
        return bootstrap;
    }

    public void setBootstrap(Bootstrap bootstrap) {
        this.bootstrap = bootstrap == null ? new Bootstrap() : bootstrap;
    }

    public static class Jwt {

        private String issuer = "xingclaw-agent";
        private String secret = "";
        private Duration ttl = Duration.ofHours(1);

        public String issuer() {
            return issuer;
        }

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public String secret() {
            return secret;
        }

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public Duration ttl() {
            return ttl;
        }

        public Duration getTtl() {
            return ttl;
        }

        public void setTtl(Duration ttl) {
            if (ttl == null || ttl.isZero() || ttl.isNegative()) {
                throw new IllegalArgumentException("agent security jwt ttl must be positive");
            }
            this.ttl = ttl;
        }
    }

    public static class Bootstrap {

        private String username = "";
        private String password = "";
        private String role = "ADMIN";

        public String username() {
            return username;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String password() {
            return password;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String role() {
            return role;
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }
    }
}
